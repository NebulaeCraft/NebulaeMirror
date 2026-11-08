package net.kuina.nebulaemirror.writeback;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mirror's content of a zone right after checkout (= the origin's content): block states (palette + one index
 * per block, x fastest, then z, then y) and block entity data. Submit writes back only what differs from this.
 */
public final class ZoneSnapshot {
    private final BoundingBox box;
    private final List<BlockState> palette;
    private final int[] blocks;
    private final Map<BlockPos, CompoundTag> blockEntities;

    private ZoneSnapshot(BoundingBox box, List<BlockState> palette, int[] blocks, Map<BlockPos, CompoundTag> bes) {
        this.box = box;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = bes;
    }

    public static long volume(BoundingBox b) {
        return (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
    }

    private int index(int x, int y, int z) {
        return ((y - box.minY()) * box.getZSpan() + (z - box.minZ())) * box.getXSpan() + (x - box.minX());
    }

    /** Captures the box from the live world (main thread; loads the chunks it needs). */
    public static ZoneSnapshot capture(ServerLevel level, BoundingBox box) {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> ids = new HashMap<>();
        int[] blocks = new int[Math.toIntExact(volume(box))];
        Map<BlockPos, CompoundTag> bes = new HashMap<>();
        ZoneSnapshot snap = new ZoneSnapshot(box, palette, blocks, bes);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                ChunkPos cp = chunk.getPos();
                int x0 = Math.max(box.minX(), cp.getMinBlockX()), x1 = Math.min(box.maxX(), cp.getMaxBlockX());
                int z0 = Math.max(box.minZ(), cp.getMinBlockZ()), z1 = Math.min(box.maxZ(), cp.getMaxBlockZ());
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            BlockState s = chunk.getBlockState(m.set(x, y, z));
                            blocks[snap.index(x, y, z)] = ids.computeIfAbsent(s, k -> {
                                palette.add(k);
                                return palette.size() - 1;
                            });
                        }
                    }
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (box.isInside(e.getKey())) {
                        bes.put(e.getKey().immutable(), e.getValue().saveWithoutMetadata(level.registryAccess()));
                    }
                }
            }
        }
        return snap;
    }

    public BoundingBox box() {
        return box;
    }

    public BlockState state(BlockPos p) {
        return palette.get(blocks[index(p.getX(), p.getY(), p.getZ())]);
    }

    @Nullable
    public CompoundTag blockEntity(BlockPos p) {
        return blockEntities.get(p);
    }

    public void save(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putIntArray("Min", new int[]{box.minX(), box.minY(), box.minZ()});
        root.putIntArray("Max", new int[]{box.maxX(), box.maxY(), box.maxZ()});
        ListTag pal = new ListTag();
        for (BlockState s : palette) {
            pal.add(NbtUtils.writeBlockState(s));
        }
        root.put("Palette", pal);
        root.put("Blocks", new IntArrayTag(blocks));
        ListTag bes = new ListTag();
        blockEntities.forEach((p, tag) -> {
            CompoundTag e = new CompoundTag();
            e.putIntArray("Pos", new int[]{p.getX(), p.getY(), p.getZ()});
            e.put("Data", tag);
            bes.add(e);
        });
        root.put("BlockEntities", bes);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static ZoneSnapshot load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        int[] min = root.getIntArray("Min"), max = root.getIntArray("Max");
        BoundingBox box = new BoundingBox(min[0], min[1], min[2], max[0], max[1], max[2]);
        HolderGetter<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        List<BlockState> palette = new ArrayList<>();
        for (Tag t : root.getList("Palette", Tag.TAG_COMPOUND)) {
            palette.add(NbtUtils.readBlockState(blocks, (CompoundTag) t));
        }
        Map<BlockPos, CompoundTag> bes = new HashMap<>();
        for (Tag t : root.getList("BlockEntities", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            int[] p = e.getIntArray("Pos");
            bes.put(new BlockPos(p[0], p[1], p[2]), e.getCompound("Data"));
        }
        int[] idx = root.getIntArray("Blocks");
        if (idx.length != volume(box)) {
            throw new IOException(file + ": block count does not match the box");
        }
        return new ZoneSnapshot(box, palette, idx, bes);
    }
}
