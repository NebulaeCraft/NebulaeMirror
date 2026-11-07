package net.kuina.nebulaemirror.importer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterUnitTest {
    private static CompoundTag state(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    @Test
    void palettedBlocksRoundTripAcrossBitWidths() {
        // 1 entry (no data), 16 entries (4 bits), 17 entries (5 bits), 300 entries (9 bits)
        for (int kinds : new int[]{1, 16, 17, 300}) {
            CompoundTag empty = new CompoundTag();
            ListTag palette = new ListTag();
            palette.add(state("minecraft:air"));
            empty.put("palette", palette);
            PalettedBlocks pb = new PalettedBlocks(empty);
            Random r = new Random(kinds);
            String[] expected = new String[4096];
            for (int i = 0; i < 4096; i++) {
                expected[i] = kinds == 1 ? "minecraft:air" : "test:b" + (i < kinds ? i : r.nextInt(kinds));
                pb.set(i, state(expected[i]));
            }
            PalettedBlocks back = new PalettedBlocks(pb.write());
            for (int i = 0; i < 4096; i++) {
                assertEquals(expected[i], back.get(i).getString("Name"), "kinds=" + kinds + " index=" + i);
            }
            assertEquals(kinds > 1, pb.write().contains("data"));
        }
    }

    @Test
    void unusedPaletteEntriesAreDropped() {
        CompoundTag tag = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(state("minecraft:air"));
        tag.put("palette", palette);
        PalettedBlocks pb = new PalettedBlocks(tag);
        pb.set(5, state("test:a"));
        pb.set(5, state("test:b"));
        ListTag written = pb.write().getList("palette", 10);
        assertEquals(2, written.size());
        assertFalse(written.toString().contains("test:a"));
    }

    @Test
    void regionFileRoundTrip(@TempDir Path dir) throws Exception {
        Map<Integer, CompoundTag> chunks = new TreeMap<>();
        for (int i : new int[]{0, 31, 32, 1023}) {
            CompoundTag c = new CompoundTag();
            c.putInt("index", i);
            c.putByteArray("payload", new byte[i * 37]);
            chunks.put(i, c);
        }
        Path file = dir.resolve("r.-1.2.mca");
        RegionIO.write(file, chunks);
        Map<Integer, CompoundTag> back = RegionIO.read(file);
        assertEquals(chunks.keySet(), back.keySet());
        for (int i : chunks.keySet()) {
            assertEquals(chunks.get(i), back.get(i));
        }
        assertEquals(-1, RegionIO.regionXZ(file)[0]);
        assertEquals(2, RegionIO.regionXZ(file)[1]);
    }

    @Test
    void oversizedChunkGoesToExternalFile(@TempDir Path dir) throws Exception {
        CompoundTag big = new CompoundTag();
        byte[] noise = new byte[1_200_000];
        new Random(1).nextBytes(noise); // incompressible, > 255 sectors
        big.putByteArray("noise", noise);
        Path file = dir.resolve("r.0.0.mca");
        RegionIO.write(file, Map.of(33, big));
        assertTrue(dir.resolve("c.1.1.mcc").toFile().isFile());
        assertEquals(big, RegionIO.read(file).get(33));
    }

    @Test
    void regionFilter() {
        var f = ImportHook.regionFilter("4,-1,2,-3");
        assertTrue(f.test(new int[]{3, -2}));
        assertTrue(f.test(new int[]{2, -3}));
        assertFalse(f.test(new int[]{5, -2}));
        assertTrue(ImportHook.regionFilter(null).test(new int[]{99, 99}));
    }
}
