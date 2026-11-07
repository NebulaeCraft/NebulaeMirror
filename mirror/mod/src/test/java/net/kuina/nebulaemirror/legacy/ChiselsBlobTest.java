package net.kuina.nebulaemirror.legacy;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChiselsBlobTest {
    /** Real Chisels&Bits blob from the 1.12 world at (1616, 77, -1010). */
    private static final byte[] BLOB = HexFormat.of().parseHex(
            "78da636066d0fabd9099e1083b03d702068ac060d0cfca4018e3b53f80083c88fd3fd2f58ff0f80700f53c1332");

    @Test
    void decodesPaletteAndVoxelCounts() throws Exception {
        ChiselsBlob blob = ChiselsBlob.decode(BLOB);
        assertNotNull(blob);
        // air, minecraft:iron_block (id 42), minecraft:concrete meta 13 (id 251 | 13 << 12)
        assertArrayEquals(new int[]{0, 42, 251 | 13 << 12}, blob.palette);
        int[] counts = new int[blob.palette.length];
        for (short v : blob.voxels) {
            counts[v]++;
        }
        // Matches the independent Python decoder used during development.
        assertArrayEquals(new int[]{3960, 64, 72}, counts);
    }

    @Test
    void indexOrderIsXThenYThenZ() {
        assertEquals(1, ChiselsBlob.index(1, 0, 0));
        assertEquals(16, ChiselsBlob.index(0, 1, 0));
        assertEquals(256, ChiselsBlob.index(0, 0, 1));
    }
}
