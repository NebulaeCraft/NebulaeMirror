package net.kuina.nebulaemirror.writeback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.sync.SyncGuard;
import net.kuina.nebulaemirror.sync.SyncService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checked-out zones: areas where the mirror is authoritative until they are submitted or abandoned.
 * Each lives in {@code <world>/nebulaemirror/zones/<name>/}: zone.json, baseline.nbt (the snapshot), and the
 * last lint.json / patch.nbt. Active zones are excluded from the incremental sync ({@link SyncGuard}).
 * <p>
 * Checkout: (P3 step 2/3: lock at the origin) -> pull + refresh the zone's chunks from the origin -> snapshot ->
 * active. Abandon: drop the zone, then refresh its chunks so the mirror shows the origin again.
 */
public final class ZoneManager {
    public static final long MAX_VOLUME = 256L * 256 * 256;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile ZoneManager instance;

    /** zone.json */
    public static final class Zone {
        public String name;
        public int[] min;
        public int[] max;
        public String owner;
        public String checkedOut;
        public boolean refreshed;

        public BoundingBox box() {
            return new BoundingBox(min[0], min[1], min[2], max[0], max[1], max[2]);
        }

        /** Inclusive chunk range [x1, z1, x2, z2]. */
        public int[] chunks() {
            return new int[]{min[0] >> 4, min[2] >> 4, max[0] >> 4, max[2] >> 4};
        }

        SyncGuard.Box guardBox() {
            return new SyncGuard.Box(min[0], min[1], min[2], max[0], max[1], max[2]);
        }

        @Override
        public String toString() {
            return name + " [" + min[0] + " " + min[1] + " " + min[2] + " .. " + max[0] + " " + max[1] + " " + max[2] + "]";
        }
    }

    private final MinecraftServer server;
    private final Path root;
    private final Map<String, Zone> active = new ConcurrentHashMap<>();
    /** Zones whose checkout is still refreshing; they already reserve their name and area. */
    private final Map<String, Zone> pending = new ConcurrentHashMap<>();
    private volatile ReverseTable table;

    private ZoneManager(MinecraftServer server) {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT).resolve("nebulaemirror").resolve("zones");
    }

    public static void register() {
        SyncGuard.addSource(() -> {
            ZoneManager m = instance;
            if (m == null) {
                return List.of();
            }
            List<SyncGuard.Box> out = new ArrayList<>();
            m.active.values().forEach(z -> out.add(z.guardBox()));
            return out;
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ZoneManager m = new ZoneManager(server);
            m.load();
            instance = m;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> instance = null);
    }

    public static ZoneManager get() {
        return instance;
    }

    private void load() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.toList()) {
                Path f = d.resolve("zone.json");
                if (Files.isRegularFile(f) && Files.isRegularFile(d.resolve("baseline.nbt"))) {
                    Zone z = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Zone.class);
                    active.put(z.name, z);
                }
            }
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.error("Zones: cannot read {}: {}", root, e.toString());
        }
        if (!active.isEmpty()) {
            NebulaeMirror.LOG.info("Zones: {} checked out, excluded from sync: {}", active.size(), active.values());
        }
    }

    public List<Zone> zones() {
        List<Zone> out = new ArrayList<>(active.values());
        out.sort(Comparator.comparing(z -> z.name));
        return out;
    }

    public Zone zone(String name) {
        return active.get(name);
    }

    Path dir(String name) {
        return root.resolve(name);
    }

    /**
     * Starts a checkout. Returns null when started; {@code done} gets the outcome message on the server thread.
     */
    public String checkout(String name, BoundingBox box, String owner, Consumer<String> done) {
        if (!NAME.matcher(name).matches()) {
            return "zone names are 1-32 characters of a-z, 0-9, _ and -";
        }
        if (active.containsKey(name) || pending.containsKey(name)) {
            return "zone " + name + " already exists";
        }
        if (ZoneSnapshot.volume(box) > MAX_VOLUME) {
            return "zone is " + ZoneSnapshot.volume(box) + " blocks; at most " + MAX_VOLUME + " (256^3)";
        }
        for (Zone z : active.values()) {
            if (z.box().intersects(box)) {
                return "overlaps zone " + z;
            }
        }
        for (Zone z : pending.values()) {
            if (z.box().intersects(box)) {
                return "overlaps zone " + z.name + " (checkout in progress)";
            }
        }
        Zone z = new Zone();
        z.name = name;
        z.min = new int[]{box.minX(), box.minY(), box.minZ()};
        z.max = new int[]{box.maxX(), box.maxY(), box.maxZ()};
        z.owner = owner;
        SyncService sync = SyncService.get();
        if (sync == null || !sync.ready()) {
            // No sync configured (development): the mirror's current content is taken as the origin's.
            z.refreshed = false;
            pending.put(name, z);
            done.accept(finish(z) + " (mirror NOT refreshed from the origin: sync is off)");
            return null;
        }
        pending.put(name, z);
        String why = sync.refresh(z.chunks(), !sync.config().pullCommand.isEmpty(), outcome -> {
            if (!outcome.ok()) {
                pending.remove(name);
                done.accept("checkout of " + name + " failed while refreshing from the origin: " + outcome.message());
                return;
            }
            z.refreshed = true;
            done.accept(finish(z));
        });
        if (why != null) {
            pending.remove(name);
            return "cannot refresh the zone from the origin now: " + why;
        }
        return null;
    }

    /** Snapshot + activate (server thread). */
    private String finish(Zone z) {
        try {
            long t0 = System.currentTimeMillis();
            ZoneSnapshot snap = ZoneSnapshot.capture(server.overworld(), z.box());
            snap.save(dir(z.name).resolve("baseline.nbt"));
            z.checkedOut = java.time.OffsetDateTime.now().toString();
            Files.writeString(dir(z.name).resolve("zone.json"), GSON.toJson(z), StandardCharsets.UTF_8);
            active.put(z.name, z);
            NebulaeMirror.LOG.info("Zones: checked out {} for {} ({} blocks, snapshot in {} ms)", z, z.owner,
                    ZoneSnapshot.volume(z.box()), System.currentTimeMillis() - t0);
            return "Zone " + z + " checked out: edit it, then /nebulaemirror zone lint " + z.name;
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.error("Zones: checkout of {} failed", z.name, e);
            return "checkout of " + z.name + " failed: " + e;
        } finally {
            pending.remove(z.name);
        }
    }

    /** Lint + diff against the snapshot (server thread). */
    public PatchBuilder.Result diff(Zone z) throws IOException {
        ZoneSnapshot base = ZoneSnapshot.load(dir(z.name).resolve("baseline.nbt"));
        PatchBuilder.Result r = PatchBuilder.build(server.overworld(), base, table());
        PatchBuilder.writeReport(dir(z.name).resolve("lint.json"), z.name, r);
        return r;
    }

    /** Writes patch.nbt when lint found no errors; returns its path. */
    public Path writePatch(Zone z, PatchBuilder.Result r) throws IOException {
        Path f = dir(z.name).resolve("patch.nbt");
        PatchBuilder.writePatch(f, PatchBuilder.toNbt(z.name, z.box(), r.entries));
        return f;
    }

    /**
     * Drops the zone (sync covers it again) and refreshes its chunks so the mirror shows the origin's content.
     */
    public String abandon(Zone z, Consumer<String> done) throws IOException {
        active.remove(z.name);
        deleteDir(dir(z.name));
        SyncService sync = SyncService.get();
        if (sync == null || !sync.ready()) {
            return "Zone " + z.name + " abandoned; sync is off, so mirror edits stay until the next refresh";
        }
        String why = sync.refresh(z.chunks(), false, o -> done.accept(o.ok()
                ? "Zone " + z.name + " abandoned; the mirror shows the origin again"
                : "Zone " + z.name + " abandoned, but restoring the origin's content failed: " + o.message()
                + " (use /nebulaemirror sync refresh " + String.join(" ", ints(z.chunks())) + ")"));
        return why == null ? null : "Zone " + z.name + " abandoned; restoring its content later: " + why
                + " (use /nebulaemirror sync refresh " + String.join(" ", ints(z.chunks())) + ")";
    }

    private static List<String> ints(int[] a) {
        List<String> out = new ArrayList<>();
        for (int i : a) {
            out.add(Integer.toString(i));
        }
        return out;
    }

    private static void deleteDir(Path d) throws IOException {
        if (!Files.exists(d)) {
            return;
        }
        try (Stream<Path> s = Files.walk(d)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Built on first use from the sync source's level.dat (the origin's vanilla ids). */
    public ReverseTable table() throws IOException {
        ReverseTable t = table;
        if (t == null) {
            SyncService sync = SyncService.get();
            String source = sync == null ? "" : sync.config().source;
            if (source == null || source.isBlank()) {
                throw new IOException("set \"source\" in config/nebulaemirror-sync.json: write-back needs the "
                        + "origin's level.dat");
            }
            Map<String, String> problems = new TreeMap<>();
            t = ReverseTable.build(Path.of(source).resolve("level.dat"), p -> problems.put(p, p));
            if (!problems.isEmpty()) {
                NebulaeMirror.LOG.warn("Write-back tables: {}", problems.keySet());
            }
            table = t;
        }
        return t;
    }
}
