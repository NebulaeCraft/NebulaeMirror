package net.kuina.nebulaemirror.writeback;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Zone diff + lint: compares the live zone with its checkout snapshot and turns every block whose 1.12 outcome
 * changed into a patch entry. A block that only changed in a way 1.12 does not store (fence connections, stair
 * shape, leaf distance) produces no entry. Lint codes:
 * <ul>
 *   <li>error {@code no_1_12_form}: a block or block entity 1.12 cannot represent;</li>
 *   <li>error {@code outside_height}: a non-air block outside y 0..255;</li>
 *   <li>warning {@code waterlogged}: the water is dropped (1.12 has no waterlogging);</li>
 *   <li>warning {@code lossy}: written back with something left out (item components, sign colour, ...).</li>
 * </ul>
 * The patch is only written when there are no errors.
 */
public final class PatchBuilder {
    public static final int PATCH_VERSION = 1;
    private static final int SAMPLES = 20;

    /** One block to write at the origin; {@code expect} is what the origin had at checkout. */
    public record Entry(BlockPos pos, LegacyBlock block, @Nullable CompoundTag tileEntity, @Nullable LegacyBlock expect) {
    }

    public record Problem(BlockPos pos, String detail) {
    }

    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        /** code -> (count, first samples); errors first. */
        public final Map<String, List<Problem>> errors = new TreeMap<>();
        public final Map<String, List<Problem>> warnings = new TreeMap<>();
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public long changedBlocks;
        public long shapeOnly;

        void problem(Map<String, List<Problem>> into, String code, BlockPos pos, String detail) {
            counts.merge((into == errors ? "error." : "warning.") + code, 1, Integer::sum);
            List<Problem> list = into.computeIfAbsent(code, k -> new ArrayList<>());
            if (list.size() < SAMPLES) {
                list.add(new Problem(pos.immutable(), detail));
            }
        }

        public int errorCount() {
            return counts.entrySet().stream().filter(e -> e.getKey().startsWith("error.")).mapToInt(Map.Entry::getValue).sum();
        }

        public int warningCount() {
            return counts.entrySet().stream().filter(e -> e.getKey().startsWith("warning.")).mapToInt(Map.Entry::getValue).sum();
        }
    }

    private PatchBuilder() {
    }

    public static Result build(ServerLevel level, ZoneSnapshot base, ReverseTable table) {
        HolderLookup.Provider registries = level.registryAccess();
        BoundingBox box = base.box();
        Result r = new Result();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                ChunkPos cp = chunk.getPos();
                int x0 = Math.max(box.minX(), cp.getMinBlockX()), x1 = Math.min(box.maxX(), cp.getMaxBlockX());
                int z0 = Math.max(box.minZ(), cp.getMinBlockZ()), z1 = Math.min(box.maxZ(), cp.getMaxBlockZ());
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            m.set(x, y, z);
                            BlockState cur = chunk.getBlockState(m);
                            BlockState was = base.state(m);
                            BlockEntity be = chunk.getBlockEntities().get(m);
                            CompoundTag curBe = be == null ? null : be.saveWithoutMetadata(registries);
                            CompoundTag wasBe = base.blockEntity(m);
                            if (cur == was && Objects.equals(curBe, wasBe)) {
                                continue;
                            }
                            r.changedBlocks++;
                            diffOne(r, m.immutable(), cur, curBe, was, wasBe, table, registries);
                        }
                    }
                }
            }
        }
        return r;
    }

    private static void diffOne(Result r, BlockPos pos, BlockState cur, @Nullable CompoundTag curBe, BlockState was,
                                @Nullable CompoundTag wasBe, ReverseTable table, HolderLookup.Provider registries) {
        ReverseTable.Result now = table.reverse(cur, curBe, registries);
        ReverseTable.Result before = table.reverse(was, wasBe, registries);
        if (now.sameAs(before)) {
            r.shapeOnly++;
            return;
        }
        if (pos.getY() < 0 || pos.getY() > 255) {
            if (!cur.isAir()) {
                r.problem(r.errors, "outside_height", pos, cur.toString());
            }
            return;
        }
        if (!now.ok()) {
            r.problem(r.errors, "no_1_12_form", pos, now.error());
            return;
        }
        if (cur.hasProperty(BlockStateProperties.WATERLOGGED) && cur.getValue(BlockStateProperties.WATERLOGGED)) {
            r.problem(r.warnings, "waterlogged", pos, cur.getBlock().getName().getString());
        }
        for (String w : now.warnings()) {
            r.problem(r.warnings, "lossy", pos, w);
        }
        r.entries.add(new Entry(pos, now.block(), now.tileEntity(), before.ok() ? before.block() : null));
    }

    /** The patch the bridge applies (gzip NBT). Positions are absolute; TileEntities get their x/y/z here. */
    public static CompoundTag toNbt(String zone, BoundingBox box, List<Entry> entries) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", PATCH_VERSION);
        root.putString("Zone", zone);
        root.putLong("Created", System.currentTimeMillis());
        root.putIntArray("Min", new int[]{box.minX(), box.minY(), box.minZ()});
        root.putIntArray("Max", new int[]{box.maxX(), box.maxY(), box.maxZ()});
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.putIntArray("Pos", new int[]{e.pos().getX(), e.pos().getY(), e.pos().getZ()});
            t.putString("Name", e.block().name());
            t.putByte("Meta", (byte) e.block().meta());
            if (e.expect() != null) {
                CompoundTag ex = new CompoundTag();
                ex.putString("Name", e.expect().name());
                ex.putByte("Meta", (byte) e.expect().meta());
                t.put("Expect", ex);
            }
            if (e.tileEntity() != null) {
                CompoundTag te = e.tileEntity().copy();
                te.putInt("x", e.pos().getX());
                te.putInt("y", e.pos().getY());
                te.putInt("z", e.pos().getZ());
                t.put("TileEntity", te);
            }
            list.add(t);
        }
        root.put("Entries", list);
        return root;
    }

    public static void writePatch(Path file, CompoundTag patch) throws IOException {
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(patch, file);
    }

    /** Human-readable lint report (lint.json next to the zone). */
    public static void writeReport(Path file, String zone, Result r) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("zone", zone);
        o.addProperty("time", java.time.OffsetDateTime.now().toString());
        o.addProperty("changedBlocks", r.changedBlocks);
        o.addProperty("shapeOnly", r.shapeOnly);
        o.addProperty("entries", r.entries.size());
        JsonObject counts = new JsonObject();
        r.counts.forEach(counts::addProperty);
        o.add("counts", counts);
        o.add("errors", problems(r.errors));
        o.add("warnings", problems(r.warnings));
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o),
                StandardCharsets.UTF_8);
    }

    private static JsonObject problems(Map<String, List<Problem>> map) {
        JsonObject o = new JsonObject();
        map.forEach((code, list) -> {
            JsonArray a = new JsonArray();
            for (Problem p : list) {
                a.add(p.pos().getX() + " " + p.pos().getY() + " " + p.pos().getZ() + ": " + p.detail());
            }
            o.add(code, a);
        });
        return o;
    }
}
