package net.kuina.nebulaemirror.importer;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One 1.12.2 Forge chunk -> one 1.21.1 chunk.
 * <ol>
 *   <li>Every modded block is lifted out of the 1.12 section arrays (replaced by air) into a side list together
 *       with its TileEntity, using the source world's FML id registry.</li>
 *   <li>The remaining, purely vanilla chunk goes through Minecraft's own DataFixer (1343 -> current), with the
 *       context the server would use for a flat overworld, so no below-zero regeneration or blending is scheduled.</li>
 *   <li>The side list is written into the 1.21 palettes from forward.json; blocks that kept 1.12 data get a
 *       nebulaemirror:legacy block entity; neighbour-shaped targets are queued in UpgradeData.</li>
 * </ol>
 */
public final class ChunkConverter {
    public static final int LEGACY_DATA_VERSION = 1343; // 1.12.2
    private static final int MIN_SECTION = -4;          // 1.21 overworld bottom section
    private static final ResourceKey<MapCodec<? extends ChunkGenerator>> FLAT =
            ResourceKey.create(Registries.CHUNK_GENERATOR, ResourceLocation.withDefaultNamespace("flat"));

    private final ForwardTable table;
    private final StateResolver resolver;
    private final ImportStats stats;
    private final DataFixer fixer = DataFixers.getDataFixer();
    private final CompoundTag unknownState = new CompoundTag();

    private record Lifted(int index, CompoundTag state, CompoundTag legacy, boolean shapeUpdate, int x, int y, int z) {
    }

    public ChunkConverter(ForwardTable table, ImportStats stats) {
        this.table = table;
        this.resolver = new StateResolver(table);
        this.stats = stats;
        unknownState.putString("Name", ForwardTable.UNKNOWN_ID);
    }

    public CompoundTag convert(CompoundTag chunk) {
        CompoundTag level = chunk.getCompound("Level");
        int cx = level.getInt("xPos");
        int cz = level.getInt("zPos");

        Map<Long, CompoundTag> tileEntities = new LinkedHashMap<>();
        for (Tag t : level.getList("TileEntities", Tag.TAG_COMPOUND)) {
            CompoundTag te = (CompoundTag) t;
            tileEntities.put(BlockPos.asLong(te.getInt("x"), te.getInt("y"), te.getInt("z")), te);
        }

        // 1. lift modded blocks out of the 1.12 arrays
        Map<Integer, List<Lifted>> lifted = new TreeMap<>();
        for (Tag st : level.getList("Sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) st;
            byte[] blocks = section.getByteArray("Blocks");
            byte[] data = section.getByteArray("Data");
            byte[] add = section.contains("Add", Tag.TAG_BYTE_ARRAY) ? section.getByteArray("Add") : null;
            if (blocks.length != 4096 || data.length != 2048) {
                continue;
            }
            int sy = section.getByte("Y");
            for (int i = 0; i < 4096; i++) {
                int id = (blocks[i] & 0xFF) | (add == null ? 0 : nibble(add, i) << 8);
                if (id == 0 || !table.isModded(id)) {
                    continue;
                }
                int meta = nibble(data, i);
                int x = cx * 16 + (i & 15);
                int y = sy * 16 + (i >> 8);
                int z = cz * 16 + ((i >> 4) & 15);
                CompoundTag te = tileEntities.remove(BlockPos.asLong(x, y, z));
                ForwardTable.Target target = table.target(id, meta);
                CompoundTag legacy = null;
                CompoundTag state;
                boolean shape = false;
                if (target == null) {
                    state = unknownState;
                    legacy = legacy(table.name(id), meta, te);
                    stats.unknown(table.name(id) + ":" + meta);
                } else {
                    state = target.palette();
                    shape = target.shapeUpdate();
                    if (target.legacyBlockEntity()) {
                        legacy = legacy(table.name(id), meta, te);
                    } else if (te != null) {
                        stats.droppedTileEntities.incrementAndGet();
                    }
                }
                if (legacy != null) {
                    stats.legacyBlockEntities.incrementAndGet();
                }
                lifted.computeIfAbsent(sy, k -> new ArrayList<>()).add(new Lifted(i, state, legacy, shape, x, y, z));
                blocks[i] = 0;
                setNibble(data, i, 0);
            }
            // Every id left is vanilla (< 256), so the high nibbles are no longer needed.
            section.remove("Add");
            section.put("Blocks", new ByteArrayTag(blocks));
            section.put("Data", new ByteArrayTag(data));
        }

        ListTag remainingTe = new ListTag();
        remainingTe.addAll(tileEntities.values());
        level.put("TileEntities", remainingTe);

        // Scheduled ticks of modded blocks would name blocks DFU cannot resolve.
        ListTag ticks = new ListTag();
        for (Tag t : level.getList("TileTicks", Tag.TAG_COMPOUND)) {
            if (((CompoundTag) t).getString("i").startsWith("minecraft:")) {
                ticks.add(t);
            }
        }
        level.put("TileTicks", ticks);

        // Entities are not imported yet (mobs are irrelevant; decorative ones are later work).
        stats.droppedEntities.addAndGet(level.getList("Entities", Tag.TAG_COMPOUND).size());
        level.put("Entities", new ListTag());

        remapBiomes(level);

        // 2. vanilla upgrade, exactly as ChunkStorage#upgradeChunkTag does it for a flat overworld
        int version = chunk.contains("DataVersion", Tag.TAG_ANY_NUMERIC) ? chunk.getInt("DataVersion") : LEGACY_DATA_VERSION;
        CompoundTag out = DataFixTypes.CHUNK.update(fixer, chunk, version, 1493);
        ChunkStorage.injectDatafixingContext(out, Level.OVERWORLD, Optional.of(FLAT));
        out = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, out, 1493);
        out.remove("__context");
        NbtUtils.addCurrentDataVersion(out);

        // 3. write the modded blocks into the 1.21 sections
        ListTag sections = out.getList("sections", Tag.TAG_COMPOUND);
        Map<Integer, CompoundTag> byY = new HashMap<>();
        for (Tag t : sections) {
            byY.put((int) ((CompoundTag) t).getByte("Y"), (CompoundTag) t);
        }
        ListTag blockEntities = out.getList("block_entities", Tag.TAG_COMPOUND);
        Map<Integer, List<Integer>> upgrade = new TreeMap<>();
        for (Map.Entry<Integer, List<Lifted>> e : lifted.entrySet()) {
            int sy = e.getKey();
            CompoundTag section = byY.computeIfAbsent(sy, y -> {
                CompoundTag s = new CompoundTag();
                s.putByte("Y", (byte) (int) y);
                sections.add(s);
                return s;
            });
            PalettedBlocks pb = new PalettedBlocks(section.getCompound("block_states"));
            for (Lifted l : e.getValue()) {
                pb.set(l.index(), l.state());
                if (l.legacy() != null) {
                    CompoundTag be = new CompoundTag();
                    be.putString("id", ForwardTable.LEGACY_BE_ID);
                    be.putInt("x", l.x());
                    be.putInt("y", l.y());
                    be.putInt("z", l.z());
                    be.putBoolean("keepPacked", false);
                    be.put("Legacy", l.legacy());
                    CompoundTag render = RenderDataDeriver.derive(l.legacy().getCompound("TileEntity"), resolver);
                    if (render != null) {
                        be.put("Render", render);
                        stats.renderData.incrementAndGet();
                    }
                    blockEntities.add(be);
                }
                if (l.shapeUpdate()) {
                    upgrade.computeIfAbsent(sy - MIN_SECTION, k -> new ArrayList<>()).add(l.index());
                }
            }
            section.put("block_states", pb.write());
            stats.moddedBlocks.addAndGet(e.getValue().size());
        }
        out.put("sections", sections);
        out.put("block_entities", blockEntities);
        if (!upgrade.isEmpty()) {
            addUpgradeIndices(out, upgrade);
        }

        // 4. a finished, static chunk: no regeneration, no terrain blending, relight + heightmaps on load
        out.remove("below_zero_retrogen");
        out.remove("blending_data");
        out.remove("Heightmaps");
        out.putString("Status", "minecraft:full");
        out.putBoolean("isLightOn", false);
        return out;
    }

    private static CompoundTag legacy(String name, int meta, CompoundTag tileEntity) {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("Name", name);
        legacy.putByte("Meta", (byte) meta);
        if (tileEntity != null) {
            legacy.put("TileEntity", tileEntity);
        }
        return legacy;
    }

    private void remapBiomes(CompoundTag level) {
        if (level.contains("Biomes", Tag.TAG_BYTE_ARRAY)) {
            byte[] b = level.getByteArray("Biomes");
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) table.biomeRemap[b[i] & 0xFF];
            }
            level.put("Biomes", new ByteArrayTag(b));
        } else if (level.contains("Biomes", Tag.TAG_INT_ARRAY)) {
            int[] b = level.getIntArray("Biomes");
            for (int i = 0; i < b.length; i++) {
                b[i] = b[i] >= 0 && b[i] < 256 ? table.biomeRemap[b[i]] : b[i];
            }
            level.put("Biomes", new IntArrayTag(b));
        }
    }

    private void addUpgradeIndices(CompoundTag chunk, Map<Integer, List<Integer>> upgrade) {
        CompoundTag data = chunk.getCompound("UpgradeData");
        CompoundTag indices = data.getCompound("Indices");
        for (Map.Entry<Integer, List<Integer>> e : upgrade.entrySet()) {
            String key = String.valueOf(e.getKey());
            int[] existing = indices.getIntArray(key);
            int[] merged = new int[existing.length + e.getValue().size()];
            System.arraycopy(existing, 0, merged, 0, existing.length);
            for (int i = 0; i < e.getValue().size(); i++) {
                merged[existing.length + i] = e.getValue().get(i);
            }
            indices.putIntArray(key, merged);
            stats.shapeUpdates.addAndGet(e.getValue().size());
        }
        data.put("Indices", indices);
        // Also fix shapes across chunk borders (all eight sides).
        data.putInt("Sides", data.getInt("Sides") | 0xFF);
        chunk.put("UpgradeData", data);
    }

    private static int nibble(byte[] a, int i) {
        int b = a[i >> 1];
        return (i & 1) == 0 ? b & 0xF : (b >> 4) & 0xF;
    }

    private static void setNibble(byte[] a, int i, int v) {
        int j = i >> 1;
        a[j] = (byte) ((i & 1) == 0 ? (a[j] & 0xF0) | (v & 0xF) : (a[j] & 0x0F) | ((v & 0xF) << 4));
    }
}
