package net.kuina.nebulaemirror.sync;

import net.kuina.nebulaemirror.importer.RegionIO;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncUnitTest {
    /** A minimal 1.12 chunk: one section with a few blocks, two TileEntities, biomes and the volatile fields. */
    private static CompoundTag chunk(long lastUpdate, boolean teOrderSwapped) {
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", 3);
        level.putInt("zPos", -2);
        level.putLong("LastUpdate", lastUpdate);
        level.putLong("InhabitedTime", lastUpdate * 7);
        level.putBoolean("LightPopulated", true);
        level.putIntArray("HeightMap", new int[]{(int) lastUpdate});
        ListTag sections = new ListTag();
        CompoundTag s = new CompoundTag();
        s.putByte("Y", (byte) 4);
        byte[] blocks = new byte[4096];
        blocks[0] = 1;
        blocks[100] = 54;
        s.putByteArray("Blocks", blocks);
        s.putByteArray("Data", new byte[2048]);
        s.putByteArray("BlockLight", new byte[]{(byte) lastUpdate});
        s.putByteArray("SkyLight", new byte[]{(byte) (lastUpdate >> 8)});
        sections.add(s);
        CompoundTag empty = new CompoundTag();
        empty.putByte("Y", (byte) 9);
        empty.putByteArray("Blocks", new byte[4096]);
        empty.putByteArray("Data", new byte[2048]);
        if (teOrderSwapped) {
            sections.add(0, empty);
        }
        level.put("Sections", sections);
        CompoundTag a = te("minecraft:chest", 48, 64, -32);
        CompoundTag b = te("chiselsandbits:chiseled", 49, 64, -32);
        ListTag tes = new ListTag();
        tes.add(teOrderSwapped ? b : a);
        tes.add(teOrderSwapped ? a : b);
        level.put("TileEntities", tes);
        level.putByteArray("Biomes", new byte[256]);
        ListTag entities = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", "minecraft:cow");
        e.putDouble("Pos", lastUpdate);
        entities.add(e);
        level.put("Entities", entities);
        CompoundTag root = new CompoundTag();
        root.put("Level", level);
        root.putInt("DataVersion", 1343);
        return root;
    }

    private static CompoundTag te(String id, int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        t.putString("payload", id + x);
        return t;
    }

    @Test
    void hashIgnoresResaveNoiseAndOrdering() {
        long h = SourceHash.of(chunk(100, false));
        assertNotEquals(0, h);
        assertEquals(h, SourceHash.of(chunk(987654, false)), "LastUpdate, light, height map and entities are noise");
        assertEquals(h, SourceHash.of(chunk(100, true)), "TileEntity order and empty sections do not matter");
    }

    @Test
    void hashSeesBlocksMetasTileEntitiesAndBiomes() {
        long h = SourceHash.of(chunk(100, false));

        CompoundTag block = chunk(100, false);
        block.getCompound("Level").getList("Sections", 10).getCompound(0).getByteArray("Blocks")[5] = 3;
        assertNotEquals(h, SourceHash.of(block));

        CompoundTag meta = chunk(100, false);
        meta.getCompound("Level").getList("Sections", 10).getCompound(0).getByteArray("Data")[50] = 1;
        assertNotEquals(h, SourceHash.of(meta));

        CompoundTag add = chunk(100, false);
        add.getCompound("Level").getList("Sections", 10).getCompound(0).putByteArray("Add", new byte[2048]);
        add.getCompound("Level").getList("Sections", 10).getCompound(0).getByteArray("Add")[0] = 1;
        assertNotEquals(h, SourceHash.of(add));

        CompoundTag te = chunk(100, false);
        te.getCompound("Level").getList("TileEntities", 10).getCompound(1).putString("payload", "other bits");
        assertNotEquals(h, SourceHash.of(te));

        CompoundTag biome = chunk(100, false);
        biome.getCompound("Level").getByteArray("Biomes")[7] = 4;
        assertNotEquals(h, SourceHash.of(biome));
    }

    @Test
    void stateRoundTripAndBaseline(@TempDir Path world) throws Exception {
        SyncState state = new SyncState(world);
        assertFalse(state.hasBaseline());
        SyncState.RegionState s = state.load(-3, 7);
        assertEquals(0, s.hashes[5]);
        s.set(5, 1234, -99L);
        s.set(1023, Integer.MAX_VALUE, Long.MIN_VALUE);
        state.save(-3, 7, s);
        SyncState.RegionState back = state.load(-3, 7);
        assertArrayEquals(s.timestamps, back.timestamps);
        assertArrayEquals(s.hashes, back.hashes);
        assertEquals(0, state.load(-3, 8).hashes[5], "other regions stay empty");
        state.writeBaseline("/src", "test");
        assertTrue(state.hasBaseline());
        assertEquals("/src", state.baselineInfo().get("source").getAsString());
        Files.writeString(world.resolve("nebulaemirror/sync/state/r.0.0.bin"), "junk");
        assertThrows(java.io.IOException.class, () -> state.load(0, 0));
    }

    @Test
    void regionHeaderAndSelectiveRead(@TempDir Path dir) throws Exception {
        Map<Integer, CompoundTag> chunks = new TreeMap<>();
        for (int i : new int[]{0, 33, 1023}) {
            CompoundTag c = new CompoundTag();
            c.putInt("index", i);
            chunks.put(i, c);
        }
        Path file = dir.resolve("r.2.-1.mca");
        long before = System.currentTimeMillis() / 1000;
        RegionIO.write(file, chunks);
        RegionIO.Header h = RegionIO.readHeader(file);
        for (int i = 0; i < 1024; i++) {
            assertEquals(chunks.containsKey(i), h.present(i), "index " + i);
            if (chunks.containsKey(i)) {
                assertTrue(h.timestamps()[i] >= before);
            }
        }
        RegionIO.Region r = RegionIO.read(file, i -> i == 33);
        assertEquals(List.of(33), List.copyOf(r.chunks().keySet()));
        assertEquals(33, r.chunks().get(33).getInt("index"));
        assertArrayEquals(h.timestamps(), r.header().timestamps());
        assertEquals(2 * 32 + 1, RegionIO.chunkX(2, 33));
        assertEquals(-32 + 1, RegionIO.chunkZ(-1, 33));
        Path empty = dir.resolve("r.0.0.mca");
        Files.write(empty, new byte[0]);
        assertFalse(RegionIO.readHeader(empty).present(0));
    }

    @Test
    void guardCoverage() {
        SyncGuard none = new SyncGuard(List.of(), -64, 319);
        assertTrue(none.isEmpty());
        assertEquals(SyncGuard.Coverage.NONE, none.coverage(new ChunkPos(0, 0)));

        SyncGuard.Box column = new SyncGuard.Box();
        column.x1 = 31;
        column.z1 = -16;
        column.x2 = 0;
        column.z2 = 15; // chunks x 0..1, z -1..0, whole height
        SyncGuard.Box slab = new SyncGuard.Box(100, 60, 100, 140, 70, 140);
        SyncGuard g = new SyncGuard(List.of(column, slab), -64, 319);
        assertEquals(SyncGuard.Coverage.FULL, g.coverage(new ChunkPos(1, -1)));
        assertEquals(SyncGuard.Coverage.NONE, g.coverage(new ChunkPos(2, 0)));
        assertEquals(SyncGuard.Coverage.PARTIAL, g.coverage(new ChunkPos(7, 7)), "y-limited box is partial");
        assertTrue(g.excluded(new BlockPos(120, 65, 120)));
        assertFalse(g.excluded(new BlockPos(120, 71, 120)));
        assertTrue(g.excluded(new BlockPos(0, -64, -16)));
        assertFalse(g.excluded(new BlockPos(32, 0, 0)));
    }
}
