package net.kuina.nebulaemirror.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Overworld areas checked out by the mirror: nobody may change them at the origin until the mirror submits or
 * abandons the zone (the job of a submit unlocks it when done). Kept in
 * {@code <world>/nebulaemirror-bridge/locks.json}. Read from any thread (FAWE edits run asynchronously), so the
 * list is replaced, never modified in place.
 */
final class ZoneLocks {
    static final class Lock {
        String zone;
        int[] min;
        int[] max;
        String owner;
        long created;

        boolean contains(int x, int y, int z) {
            return x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
        }

        boolean intersects(int[] lo, int[] hi) {
            return lo[0] <= max[0] && hi[0] >= min[0] && lo[1] <= max[1] && hi[1] >= min[1] && lo[2] <= max[2] && hi[2] >= min[2];
        }

        @Override
        public String toString() {
            return zone + " [" + min[0] + " " + min[1] + " " + min[2] + " .. " + max[0] + " " + max[1] + " " + max[2]
                    + "]，签出者 " + owner;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final File file;
    private volatile List<Lock> locks = Collections.emptyList();

    ZoneLocks(File root) {
        this.file = new File(root, "locks.json");
        if (file.isFile()) {
            try (Reader r = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                List<Lock> l = GSON.fromJson(r, new TypeToken<List<Lock>>() { }.getType());
                locks = l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
            } catch (IOException | RuntimeException e) {
                MirrorBridge.LOG.error("Cannot read {}: {} (no zones locked)", file, e.toString());
            }
        }
    }

    List<Lock> all() {
        return locks;
    }

    /** The lock covering this block, or null. */
    Lock at(int x, int y, int z) {
        for (Lock l : locks) {
            if (l.contains(x, y, z)) {
                return l;
            }
        }
        return null;
    }

    Lock get(String zone) {
        for (Lock l : locks) {
            if (l.zone.equals(zone)) {
                return l;
            }
        }
        return null;
    }

    /** Returns null when locked (also when the same zone with the same box is locked again), else why not. */
    synchronized String lock(String zone, int[] min, int[] max, String owner) {
        if (min == null || max == null || min.length != 3 || max.length != 3) {
            return "锁定需要 min 和 max [x, y, z]";
        }
        int[] lo = {Math.min(min[0], max[0]), Math.min(min[1], max[1]), Math.min(min[2], max[2])};
        int[] hi = {Math.max(min[0], max[0]), Math.max(min[1], max[1]), Math.max(min[2], max[2])};
        for (Lock l : locks) {
            if (l.zone.equals(zone)) {
                return java.util.Arrays.equals(l.min, lo) && java.util.Arrays.equals(l.max, hi) ? null
                        : "区域 " + zone + " 已用另一个范围锁定：" + l;
            }
            if (l.intersects(lo, hi)) {
                return "与已锁定的区域 " + l + " 重叠";
            }
        }
        Lock n = new Lock();
        n.zone = zone;
        n.min = lo;
        n.max = hi;
        n.owner = owner;
        n.created = System.currentTimeMillis();
        List<Lock> next = new ArrayList<>(locks);
        next.add(n);
        return save(next) ? null : "无法写入 " + file;
    }

    /** True if the zone was locked. */
    synchronized boolean unlock(String zone) {
        List<Lock> next = new ArrayList<>(locks);
        if (!next.removeIf(l -> l.zone.equals(zone))) {
            return false;
        }
        save(next);
        return true;
    }

    private boolean save(List<Lock> next) {
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp.toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(next, w);
        } catch (IOException e) {
            MirrorBridge.LOG.error("Cannot write {}: {}", tmp, e.toString());
            return false;
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MirrorBridge.LOG.error("Cannot write {}: {}", file, e.toString());
            return false;
        }
        locks = Collections.unmodifiableList(next);
        return true;
    }
}
