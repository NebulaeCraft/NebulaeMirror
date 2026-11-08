package net.kuina.nebulaemirror.writeback;

import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overworld chunks whose content changed in the mirror itself (players, WorldEdit, Axiom, commands, water, fire,
 * ...), so the next sync re-applies them from the origin: outside checked-out zones the origin is authoritative.
 * Fed by mixins on {@code LevelChunk#setBlockState} and {@code ChunkAccess#setUnsaved(true)}; changes the mirror
 * makes itself (the sync's patcher, relighting, UpgradeData when a chunk loads) run {@link #quietly}.
 * Kept in {@code <world>/nebulaemirror/sync/mirror-edits.bin} across restarts.
 */
public final class MirrorEdits {
    private static final ThreadLocal<int[]> QUIET = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile MirrorEdits instance;

    private final Path file;
    /** chunk pos -> sequence number of its latest change (to forget only what a sync run has covered). */
    private final Map<Long, Long> edited = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean dirty;

    private MirrorEdits(Path file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ hooks (any thread)

    public static void enterQuiet() {
        QUIET.get()[0]++;
    }

    public static void exitQuiet() {
        int[] q = QUIET.get();
        if (q[0] > 0) {
            q[0]--;
        }
    }

    /** Runs {@code r} without recording its changes (the mirror's own writes). */
    public static void quietly(Runnable r) {
        enterQuiet();
        try {
            r.run();
        } finally {
            exitQuiet();
        }
    }

    /** From the mixins: this chunk's content changed. */
    public static void changed(ChunkAccess chunk) {
        MirrorEdits m = instance;
        if (m == null || QUIET.get()[0] > 0 || !(chunk instanceof LevelChunk lc)) {
            return;
        }
        Level level = lc.getLevel();
        if (level.isClientSide() || level.dimension() != Level.OVERWORLD) {
            return;
        }
        m.edited.put(lc.getPos().toLong(), m.seq.incrementAndGet());
        m.dirty = true;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void start(Path syncRoot) {
        MirrorEdits m = new MirrorEdits(syncRoot.resolve("mirror-edits.bin"));
        m.load();
        instance = m;
    }

    public static void stop() {
        MirrorEdits m = instance;
        instance = null;
        if (m != null) {
            m.save();
        }
    }

    public static MirrorEdits get() {
        return instance;
    }

    /** The chunks edited so far (pos -> sequence), optionally only those inside a chunk range [x1, z1, x2, z2]. */
    public Map<Long, Long> snapshot(int[] range) {
        Map<Long, Long> out = new HashMap<>();
        edited.forEach((pos, s) -> {
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            if (range == null || (x >= Math.min(range[0], range[2]) && x <= Math.max(range[0], range[2])
                    && z >= Math.min(range[1], range[3]) && z <= Math.max(range[1], range[3]))) {
                out.put(pos, s);
            }
        });
        return out;
    }

    /** Forgets the snapshot's chunks unless they changed again since. */
    public void forget(Map<Long, Long> covered) {
        covered.forEach((pos, s) -> edited.remove(pos, s));
        dirty = true;
        save();
    }

    public int size() {
        return edited.size();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file); DataInputStream d = new DataInputStream(in)) {
            int n = d.readInt();
            for (int i = 0; i < n; i++) {
                edited.put(d.readLong(), seq.incrementAndGet());
            }
            if (n > 0) {
                NebulaeMirror.LOG.info("Sync: {} chunk(s) edited in the mirror are re-applied from the origin at the next sync", n);
            }
        } catch (IOException e) {
            NebulaeMirror.LOG.warn("Sync: cannot read {}: {}", file, e.toString());
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Long[] keys = edited.keySet().toArray(new Long[0]);
            try (OutputStream out = Files.newOutputStream(tmp); DataOutputStream d = new DataOutputStream(out)) {
                d.writeInt(keys.length);
                for (Long k : keys) {
                    d.writeLong(k);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            NebulaeMirror.LOG.warn("Sync: cannot write {}: {}", file, e.toString());
        }
    }
}
