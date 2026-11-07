package net.kuina.nebulaemirror.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Areas where the mirror, not the origin, is authoritative, so the sync leaves them alone: the config's
 * "exclude" boxes, plus anything registered with {@link #addSource} (P3 zone checkouts will register theirs).
 * A chunk that is only partly excluded is diffed block by block on the live world, skipping excluded blocks;
 * excluded chunks are not recorded as applied, so they are synced once the exclusion is lifted.
 */
public final class SyncGuard {
    /** Inclusive block box; y1/y2 may be omitted for the whole height. */
    public static final class Box {
        public int x1, z1, x2, z2;
        public Integer y1, y2;

        public Box() {
        }

        public Box(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
        }

        int minX() { return Math.min(x1, x2); }
        int maxX() { return Math.max(x1, x2); }
        int minZ() { return Math.min(z1, z2); }
        int maxZ() { return Math.max(z1, z2); }
        int minY() { return y1 == null || y2 == null ? Integer.MIN_VALUE : Math.min(y1, y2); }
        int maxY() { return y1 == null || y2 == null ? Integer.MAX_VALUE : Math.max(y1, y2); }

        boolean contains(int x, int y, int z) {
            return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ() && y >= minY() && y <= maxY();
        }

        @Override
        public String toString() {
            return "[" + minX() + "," + (y1 == null ? "*" : minY()) + "," + minZ() + " .. " + maxX() + ","
                    + (y2 == null ? "*" : maxY()) + "," + maxZ() + "]";
        }
    }

    public enum Coverage { NONE, PARTIAL, FULL }

    private static final List<Supplier<List<Box>>> SOURCES = new CopyOnWriteArrayList<>();
    private final List<Box> boxes;
    private final int minY;
    private final int maxY;

    /** A snapshot of all exclusions for one run; minY/maxY are the world's build limits. */
    public SyncGuard(List<Box> configured, int minY, int maxY) {
        List<Box> all = new ArrayList<>(configured);
        for (Supplier<List<Box>> s : SOURCES) {
            all.addAll(s.get());
        }
        this.boxes = List.copyOf(all);
        this.minY = minY;
        this.maxY = maxY;
    }

    public static void addSource(Supplier<List<Box>> source) {
        SOURCES.add(source);
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public List<Box> boxes() {
        return boxes;
    }

    public Coverage coverage(ChunkPos pos) {
        int x0 = pos.getMinBlockX(), x1 = pos.getMaxBlockX(), z0 = pos.getMinBlockZ(), z1 = pos.getMaxBlockZ();
        Coverage c = Coverage.NONE;
        for (Box b : boxes) {
            if (b.maxX() < x0 || b.minX() > x1 || b.maxZ() < z0 || b.minZ() > z1 || b.maxY() < minY || b.minY() > maxY) {
                continue;
            }
            if (b.minX() <= x0 && b.maxX() >= x1 && b.minZ() <= z0 && b.maxZ() >= z1 && b.minY() <= minY && b.maxY() >= maxY) {
                return Coverage.FULL;
            }
            c = Coverage.PARTIAL;
        }
        return c;
    }

    public boolean excluded(BlockPos pos) {
        for (Box b : boxes) {
            if (b.contains(pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }
}
