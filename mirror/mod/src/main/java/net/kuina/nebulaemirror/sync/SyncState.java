package net.kuina.nebulaemirror.sync;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What the mirror last applied from each 1.12 chunk: the 1.12 region-header timestamp and the {@link SourceHash}.
 * One small file per region in {@code <world>/nebulaemirror/sync/state/r.X.Z.bin}
 * ("NMS1", then 1024 x [int timestamp, long hash]); 0/0 means "never applied".
 * {@code baseline.json} marks that the state matches the world (written by the full import or
 * {@code /nebulaemirror sync baseline}); incremental sync refuses to run without it.
 */
public final class SyncState {
    private static final int MAGIC = 0x4E4D5331; // "NMS1"
    private static final int SIZE = 4 + 1024 * 12;
    public static final String BASELINE = "baseline.json";

    private final Path root;

    public SyncState(Path world) {
        this.root = world.resolve("nebulaemirror").resolve("sync");
    }

    public Path root() {
        return root;
    }

    /** Applied timestamps and hashes of one region. */
    public static final class RegionState {
        public final int[] timestamps = new int[1024];
        public final long[] hashes = new long[1024];

        public void set(int index, int timestamp, long hash) {
            timestamps[index] = timestamp;
            hashes[index] = hash;
        }
    }

    private Path file(int rx, int rz) {
        return root.resolve("state").resolve("r." + rx + "." + rz + ".bin");
    }

    public RegionState load(int rx, int rz) throws IOException {
        RegionState s = new RegionState();
        Path f = file(rx, rz);
        if (!Files.isRegularFile(f)) {
            return s;
        }
        byte[] data = Files.readAllBytes(f);
        ByteBuffer buf = ByteBuffer.wrap(data);
        if (data.length != SIZE || buf.getInt() != MAGIC) {
            throw new IOException(f + " is not a NebulaeMirror sync state file");
        }
        for (int i = 0; i < 1024; i++) {
            s.timestamps[i] = buf.getInt();
            s.hashes[i] = buf.getLong();
        }
        return s;
    }

    /** Writes atomically (temp file + move). */
    public void save(int rx, int rz, RegionState s) throws IOException {
        Path f = file(rx, rz);
        Files.createDirectories(f.getParent());
        ByteBuffer buf = ByteBuffer.allocate(SIZE);
        buf.putInt(MAGIC);
        for (int i = 0; i < 1024; i++) {
            buf.putInt(s.timestamps[i]).putLong(s.hashes[i]);
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, buf.array());
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean hasBaseline() {
        return Files.isRegularFile(root.resolve(BASELINE));
    }

    public JsonObject baselineInfo() {
        try {
            return JsonParser.parseString(Files.readString(root.resolve(BASELINE), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    public void writeBaseline(String source, String how) throws IOException {
        Files.createDirectories(root);
        JsonObject o = new JsonObject();
        o.addProperty("source", source);
        o.addProperty("how", how);
        o.addProperty("time", java.time.OffsetDateTime.now().toString());
        Files.writeString(root.resolve(BASELINE), new GsonBuilder().setPrettyPrinting().create().toJson(o),
                StandardCharsets.UTF_8);
    }
}
