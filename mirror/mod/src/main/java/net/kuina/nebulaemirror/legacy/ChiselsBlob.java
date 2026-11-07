package net.kuina.nebulaemirror.legacy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.InflaterInputStream;

/**
 * Independent reader for the voxel data written by Chisels&Bits 1.12 (TileEntity "X", compact format version 0).
 * No Chisels&Bits code is included; the format is documented here.
 * <p>Layout after zlib inflate: varint version, varint palette size, palette (varint 1.12 state ids,
 * id | meta << 12), varint byteOffset, varint bytesOfInterest, then a bit stream of big-endian ints read
 * LSB-first; each of the 4096 voxels is a palette index of max(1, ceil(log2(size))) bits, MSB first.
 * Voxel index = x | y << 4 | z << 8. Bits before byteOffset are zero.
 */
public final class ChiselsBlob {
    /** 1.12 state ids, palette[0..n). */
    public final int[] palette;
    /** Palette index of every voxel. */
    public final short[] voxels;

    private ChiselsBlob(int[] palette, short[] voxels) {
        this.palette = palette;
        this.voxels = voxels;
    }

    public static int index(int x, int y, int z) {
        return x | y << 4 | z << 8;
    }

    /** @return the decoded blob, or null for unsupported (cross-world) versions. */
    public static ChiselsBlob decode(byte[] blob) throws IOException {
        byte[] data;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(blob))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            in.transferTo(out);
            data = out.toByteArray();
        }
        int[] pos = {0};
        if (varInt(data, pos) != 0) {
            return null;
        }
        int n = varInt(data, pos);
        if (n <= 0 || n > 4096) {
            throw new IOException("bad palette size " + n);
        }
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) {
            palette[i] = varInt(data, pos);
        }
        int byteOffset = varInt(data, pos);
        int bytesOfInterest = varInt(data, pos);
        ByteBuffer bits = ByteBuffer.wrap(data, pos[0], Math.min(bytesOfInterest, data.length - pos[0])).slice();
        int ints = bits.remaining() / 4;
        int intOffset = byteOffset / 4;
        int bitsPer = Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
        short[] voxels = new short[4096];
        int offset = 0, bit = 0;
        int current = readInt(bits, 0 - intOffset, ints);
        for (int v = 0; v < 4096; v++) {
            int idx = 0;
            for (int b = bitsPer - 1; b >= 0; b--) {
                if ((current & (1 << bit)) != 0) {
                    idx |= 1 << b;
                }
                if (++bit >= 32) {
                    offset++;
                    bit = 0;
                    current = readInt(bits, offset - intOffset, ints);
                }
            }
            voxels[v] = (short) (idx < n ? idx : 0);
        }
        return new ChiselsBlob(palette, voxels);
    }

    private static int readInt(ByteBuffer b, int index, int count) {
        return index >= 0 && index < count ? b.getInt(index * 4) : 0;
    }

    private static int varInt(byte[] d, int[] pos) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (pos[0] >= d.length) {
                throw new IOException("truncated varint");
            }
            int b = d[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
