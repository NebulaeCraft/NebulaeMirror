package net.kuina.nebulaemirror.importer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Anvil region files (.mca), which share their container format between 1.12 and 1.21: a 4 KiB location table,
 * a 4 KiB timestamp table, then 4 KiB sectors holding [length][compression][compressed NBT].
 * Chunks over 255 sectors are stored externally in c.X.Z.mcc (compression byte | 128), which 1.21 reads.
 */
public final class RegionIO {
    private static final int SECTOR = 4096;

    private RegionIO() {
    }

    /** Location and timestamp tables of a region file; a chunk is present iff its location is non-zero. */
    public record Header(int[] locations, int[] timestamps) {
        public boolean present(int index) {
            return (locations[index] >>> 8) != 0;
        }
    }

    /** Reads only the 8 KiB header (cheap change detection). A file shorter than that has no chunks. */
    public static Header readHeader(Path file) throws IOException {
        byte[] head = new byte[2 * SECTOR];
        int n = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
        }
        return header(head, n);
    }

    private static Header header(byte[] data, int length) {
        int[] loc = new int[1024];
        int[] ts = new int[1024];
        if (length >= 2 * SECTOR) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            for (int i = 0; i < 1024; i++) {
                loc[i] = buf.getInt(i * 4);
                ts[i] = buf.getInt(SECTOR + i * 4);
            }
        }
        return new Header(loc, ts);
    }

    /** A region file read at one moment: its header and the requested chunks. */
    public record Region(Header header, Map<Integer, CompoundTag> chunks) {
    }

    /** All chunks of a region file keyed by local index (x & 31) + (z & 31) * 32. */
    public static Map<Integer, CompoundTag> read(Path file) throws IOException {
        return read(file, i -> true).chunks();
    }

    /** The header and the chunks whose local index passes {@code which}, from a single read of the file. */
    public static Region read(Path file, IntPredicate which) throws IOException {
        byte[] data = Files.readAllBytes(file);
        Header header = header(data, data.length);
        Map<Integer, CompoundTag> out = new TreeMap<>();
        if (data.length < 2 * SECTOR) {
            return new Region(header, out);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        for (int i = 0; i < 1024; i++) {
            int loc = header.locations()[i];
            int offset = loc >>> 8;
            if (offset == 0 || !which.test(i)) {
                continue;
            }
            int pos = offset * SECTOR;
            if (pos + 5 > data.length) {
                throw new IOException(file + ": chunk " + i + " points past the end of the file");
            }
            int length = buf.getInt(pos);
            int type = data[pos + 4] & 0xFF;
            InputStream raw;
            if ((type & 128) != 0) {
                Path ext = file.resolveSibling("c." + chunkX(file, i) + "." + chunkZ(file, i) + ".mcc");
                raw = Files.newInputStream(ext);
                type &= 127;
            } else {
                raw = new ByteArrayInputStream(data, pos + 5, length - 1);
            }
            InputStream in = switch (type) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IOException(file + ": chunk " + i + " has unknown compression " + type);
            };
            try (DataInputStream din = new DataInputStream(in)) {
                out.put(i, NbtIo.read(din, NbtAccounter.unlimitedHeap()));
            }
        }
        return new Region(header, out);
    }

    /** Writes a complete region file atomically (temp file + move). */
    public static void write(Path file, Map<Integer, CompoundTag> chunks) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        int nextSector = 2;
        int now = (int) (System.currentTimeMillis() / 1000);
        for (Map.Entry<Integer, CompoundTag> e : chunks.entrySet()) {
            int i = e.getKey();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DataOutputStream dout = new DataOutputStream(new DeflaterOutputStream(z))) {
                NbtIo.write(e.getValue(), dout);
            }
            byte[] compressed = z.toByteArray();
            Path ext = file.resolveSibling("c." + chunkX(file, i) + "." + chunkZ(file, i) + ".mcc");
            byte[] payload;
            int type = 2;
            if (compressed.length + 5 > 255 * SECTOR) {
                Files.write(ext, compressed);
                payload = new byte[0];
                type |= 128;
            } else {
                Files.deleteIfExists(ext);
                payload = compressed;
            }
            int length = payload.length + 1;
            int sectors = (length + 4 + SECTOR - 1) / SECTOR;
            ByteBuffer chunk = ByteBuffer.allocate(sectors * SECTOR);
            chunk.putInt(length).put((byte) type).put(payload);
            body.write(chunk.array());
            header.putInt(i * 4, (nextSector << 8) | sectors);
            header.putInt(SECTOR + i * 4, now);
            nextSector += sectors;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            out.write(header.array());
            body.writeTo(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Region coordinates from "r.X.Z.mca". */
    public static int[] regionXZ(Path file) {
        String[] p = file.getFileName().toString().split("\\.");
        return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    /** Chunk coordinates of local index {@code index} in region (rx, rz). */
    public static int chunkX(int rx, int index) {
        return rx * 32 + (index & 31);
    }

    public static int chunkZ(int rz, int index) {
        return rz * 32 + (index >> 5);
    }

    private static int chunkX(Path file, int index) {
        return regionXZ(file)[0] * 32 + (index & 31);
    }

    private static int chunkZ(Path file, int index) {
        return regionXZ(file)[1] * 32 + (index >> 5);
    }
}
