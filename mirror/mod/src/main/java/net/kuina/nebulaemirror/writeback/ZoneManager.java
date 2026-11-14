package net.kuina.nebulaemirror.writeback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.sync.SyncGuard;
import net.kuina.nebulaemirror.sync.SyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checked-out zones: areas where the mirror is authoritative until they are submitted or abandoned.
 * Each lives in {@code <world>/nebulaemirror/zones/<name>/}: zone.json, baseline.nbt (the snapshot), and the
 * last lint.json / patch.nbt. Active zones are excluded from the incremental sync ({@link SyncGuard}).
 * <ul>
 *   <li>checkout: lock the box at the origin (bridge) -> pull + refresh it from the origin -> snapshot.</li>
 *   <li>submit: lint/diff -> send the patch to the bridge -> wait for its job (which unlocks the zone) ->
 *       pull + refresh the zone from the origin and compare with what was sent (round trip) -> closed.</li>
 *   <li>abandon: unlock at the origin, drop the zone, refresh it so the mirror shows the origin again.</li>
 * </ul>
 * Closed zones are kept under {@code zones/_closed/<name>-<time>/}.
 * <p>
 * Clients draw the boundary of every active zone; {@link ZoneOutlines} is told whenever {@code active} changes.
 */
public final class ZoneManager {
    public static final long MAX_VOLUME = 256L * 256 * 256;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> RUNNING = Set.of("check", "apply", "verify");
    private static volatile ZoneManager instance;

    /** zone.json */
    public static final class Zone {
        public String name;
        public int[] min;
        public int[] max;
        public String owner;
        /** Player UUID of the owner; null for the console. */
        public String ownerUuid;
        public String checkedOut;
        public boolean refreshed;
        /** Locked at the origin by the bridge. */
        public boolean locked;
        /** Bridge job of the last submit. */
        public String lastJob;
        transient volatile boolean busy;

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
    /** Zones whose checkout is still locking/refreshing; they already reserve their name and area. */
    private final Map<String, Zone> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NebulaeMirror-Bridge-Poll");
        t.setDaemon(true);
        return t;
    });
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
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ZoneManager m = instance;
            instance = null;
            if (m != null) {
                m.poller.shutdownNow();
            }
        });
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

    /** True when checkout locks at the origin (a bridge is configured). */
    public boolean locksOrigin() {
        return bridge() != null;
    }

    private BridgeClient bridge() {
        SyncService sync = SyncService.get();
        return sync == null ? null : BridgeClient.of(sync.config().bridge);
    }

    private void save(Zone z) throws IOException {
        Files.createDirectories(dir(z.name));
        Files.writeString(dir(z.name).resolve("zone.json"), GSON.toJson(z), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Starts a checkout. Returns null when started; {@code done} gets the outcome message on the server thread.
     */
    public String checkout(String name, BoundingBox box, String owner, String ownerUuid, Consumer<String> done) {
        if (!NAME.matcher(name).matches()) {
            return "区域名只能由 1-32 个 a-z、0-9、_ 和 - 组成";
        }
        if (active.containsKey(name) || pending.containsKey(name)) {
            return "区域 " + name + " 已存在";
        }
        if (ZoneSnapshot.volume(box) > MAX_VOLUME) {
            return "区域有 " + ZoneSnapshot.volume(box) + " 个方块，最多只能 " + MAX_VOLUME + "（256³）";
        }
        for (Zone z : active.values()) {
            if (z.box().intersects(box)) {
                return "与区域 " + z + " 重叠";
            }
        }
        for (Zone z : pending.values()) {
            if (z.box().intersects(box)) {
                return "与区域 " + z.name + " 重叠（该区域正在签出）";
            }
        }
        Zone z = new Zone();
        z.name = name;
        z.min = new int[]{box.minX(), box.minY(), box.minZ()};
        z.max = new int[]{box.maxX(), box.maxY(), box.maxZ()};
        z.owner = owner;
        z.ownerUuid = ownerUuid;
        pending.put(name, z);
        BridgeClient bridge = bridge();
        if (bridge == null) {
            refreshAndSnapshot(z, "（未配置桥接，原服没有锁定）", done);
            return null;
        }
        JsonObject req = new JsonObject();
        req.addProperty("zone", name);
        req.add("min", ints(z.min));
        req.add("max", ints(z.max));
        req.addProperty("owner", owner);
        bridge.post("/v1/lock", req).whenComplete((r, err) -> server.execute(() -> {
            if (err != null) {
                pending.remove(name);
                done.accept("签出 " + name + " 失败：无法在原服锁定：" + BridgeClient.reason(err));
                return;
            }
            z.locked = true;
            refreshAndSnapshot(z, "；已在原服锁定", done);
        }));
        return null;
    }

    private void refreshAndSnapshot(Zone z, String note, Consumer<String> done) {
        SyncService sync = SyncService.get();
        if (sync == null || !sync.ready()) {
            // No sync configured (development): the mirror's current content is taken as the origin's.
            z.refreshed = false;
            done.accept(finishCheckout(z) + note + "（同步未开启，镜像没有从原服刷新）");
            return;
        }
        String why = sync.refresh(z.chunks(), !sync.config().pullCommand.isEmpty(), outcome -> {
            if (!outcome.ok()) {
                failCheckout(z, done, "从原服刷新时出错：" + outcome.message());
                return;
            }
            z.refreshed = true;
            done.accept(finishCheckout(z) + note);
        });
        if (why != null) {
            failCheckout(z, done, "现在无法从原服刷新该区域：" + why);
        }
    }

    private void failCheckout(Zone z, Consumer<String> done, String why) {
        pending.remove(z.name);
        unlockQuietly(z);
        done.accept("签出 " + z.name + " 失败，" + why);
    }

    /** Snapshot + activate (server thread). */
    private String finishCheckout(Zone z) {
        try {
            long t0 = System.currentTimeMillis();
            ZoneSnapshot snap = ZoneSnapshot.capture(server.overworld(), z.box());
            snap.save(dir(z.name).resolve("baseline.nbt"));
            z.checkedOut = java.time.OffsetDateTime.now().toString();
            save(z);
            active.put(z.name, z);
            ZoneOutlines.broadcast(server);
            NebulaeMirror.LOG.info("Zones: checked out {} for {} ({} blocks, snapshot in {} ms)", z, z.owner,
                    ZoneSnapshot.volume(z.box()), System.currentTimeMillis() - t0);
            return "区域 " + z + " 已签出：编辑完成后执行 /nebulaemirror zone lint " + z.name;
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.error("Zones: checkout of {} failed", z.name, e);
            unlockQuietly(z);
            return "签出 " + z.name + " 失败：" + e;
        } finally {
            pending.remove(z.name);
        }
    }

    private void unlockQuietly(Zone z) {
        BridgeClient bridge = bridge();
        if (z.locked && bridge != null) {
            JsonObject req = new JsonObject();
            req.addProperty("zone", z.name);
            bridge.post("/v1/unlock", req).whenComplete((r, err) -> {
                if (err != null) {
                    NebulaeMirror.LOG.warn("Zones: could not unlock {} at the origin: {} (use /mirrorbridge unlock {} there)",
                            z.name, BridgeClient.reason(err), z.name);
                }
            });
        }
    }

    // ------------------------------------------------------------------ lint / diff

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

    // ------------------------------------------------------------------ submit

    /**
     * Sends the zone's changes to the origin. Returns null when sent; {@code done} receives progress and the
     * outcome on the server thread.
     */
    public String submit(Zone z, boolean force, Consumer<String> done) throws IOException {
        BridgeClient bridge = bridge();
        if (bridge == null) {
            return "未配置桥接（config/nebulaemirror-sync.json 中的 \"bridge\"）：请把 /nebulaemirror zone diff "
                    + "生成的 patch.nbt 手动复制到原服";
        }
        if (z.busy) {
            return "区域 " + z.name + " 已经在提交或放弃中";
        }
        PatchBuilder.Result r = diff(z);
        if (r.errorCount() > 0) {
            return "区域 " + z.name + " 有 " + r.errorCount() + " 个检查错误，详见 /nebulaemirror zone lint " + z.name;
        }
        if (r.entries.isEmpty()) {
            return "区域 " + z.name + " 没有需要回写的改动（要释放它请用 abandon）";
        }
        CompoundTag patch = PatchBuilder.toNbt(z.name, z.box(), r.entries);
        PatchBuilder.writePatch(dir(z.name).resolve("patch.nbt"), patch);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(patch, bytes);
        z.busy = true;
        List<PatchBuilder.Entry> sent = List.copyOf(r.entries);
        String warn = r.warningCount() > 0 ? "（" + r.warningCount() + " 个检查警告）" : "";
        bridge.post("/v1/submit?zone=" + z.name + (force ? "&force=true" : ""), bytes.toByteArray())
                .whenComplete((resp, err) -> server.execute(() -> {
                    if (err != null) {
                        z.busy = false;
                        done.accept("提交 " + z.name + " 失败：" + BridgeClient.reason(err));
                        return;
                    }
                    String job = resp.get("job").getAsString();
                    z.lastJob = job;
                    try {
                        save(z);
                    } catch (IOException e) {
                        NebulaeMirror.LOG.warn("Zones: cannot save {}: {}", z.name, e.toString());
                    }
                    done.accept("已提交 " + z.name + " 的 " + sent.size() + " 个方块" + warn + "，原服任务 " + job
                            + "，等待执行……");
                    poll(z, bridge, job, sent, done, 0);
                }));
        return null;
    }

    private void poll(Zone z, BridgeClient bridge, String job, List<PatchBuilder.Entry> sent, Consumer<String> done, int errors) {
        poller.schedule(() -> bridge.get("/v1/job?id=" + job).whenComplete((rep, err) -> server.execute(() -> {
            if (err != null) {
                if (errors >= 30) {
                    z.busy = false;
                    done.accept("与原服任务 " + job + " 失去联系：" + BridgeClient.reason(err)
                            + "（请在原服查看 /mirrorbridge status，然后重新提交或放弃）");
                } else {
                    poll(z, bridge, job, sent, done, errors + 1);
                }
                return;
            }
            String phase = rep.get("phase").getAsString();
            if (RUNNING.contains(phase)) {
                poll(z, bridge, job, sent, done, 0);
                return;
            }
            jobFinished(z, job, phase, rep, sent, done);
        })), 2, TimeUnit.SECONDS);
    }

    private void jobFinished(Zone z, String job, String phase, JsonObject rep, List<PatchBuilder.Entry> sent,
                             Consumer<String> done) {
        if (!phase.equals("done")) {
            z.busy = false;
            StringBuilder sb = new StringBuilder("原服任务 " + job + " " + phaseLabel(phase));
            if (rep.has("error")) {
                sb.append("：").append(rep.get("error").getAsString());
            }
            for (JsonElement c : rep.getAsJsonArray("conflictSamples")) {
                sb.append("\n  ").append(c.getAsString());
            }
            sb.append("\n区域仍保持签出和锁定状态。");
            sb.append(rep.get("conflicts").getAsInt() > 0
                    ? "要覆盖原服上的改动：/nebulaemirror zone submit " + z.name + " force"
                    : "请解决问题后重新提交。");
            done.accept(sb.toString());
            return;
        }
        // Applied (and unlocked) at the origin: release the zone and check the round trip.
        z.locked = false;
        active.remove(z.name);
        ZoneOutlines.broadcast(server);
        pending.put(z.name, z); // keeps name and area reserved until the round trip is checked
        String applied = "原服任务 " + job + " 已写入 " + rep.get("changed").getAsInt() + " 个方块"
                + (rep.get("verifyMismatches").getAsInt() > 0 ? "，原服回读时有 " + rep.get("verifyMismatches").getAsInt()
                + " 个与发送的不一致" : "");
        SyncService sync = SyncService.get();
        if (sync == null || !sync.ready()) {
            done.accept(close(z, applied + "；同步未开启，未做往返校验", null));
            return;
        }
        verifyWhenPossible(z, sync, sent, applied, done, 0);
    }

    /** Pull + refresh the zone from the origin, then compare it with what was sent. Retries while sync is busy. */
    private void verifyWhenPossible(Zone z, SyncService sync, List<PatchBuilder.Entry> sent, String applied,
                                    Consumer<String> done, int attempt) {
        String why = sync.refresh(z.chunks(), !sync.config().pullCommand.isEmpty(), outcome -> {
            if (!outcome.ok()) {
                done.accept(close(z, applied + "；往返校验刷新失败：" + outcome.message(), null));
                return;
            }
            JsonObject v = verify(sent);
            int bad = v.get("mismatches").getAsInt();
            done.accept(close(z, applied + "；往返校验：" + (bad == 0
                    ? "全部 " + sent.size() + " 个方块从原服回来时与发送的一致"
                    : sent.size() + " 个方块中有 " + bad + " 个不一致，详见 verify.json"), v));
        });
        if (why != null) {
            if (attempt < 60) {
                poller.schedule(() -> server.execute(() -> verifyWhenPossible(z, sync, sent, applied, done, attempt + 1)),
                        5, TimeUnit.SECONDS);
            } else {
                done.accept(close(z, applied + "；未做往返校验：" + why, null));
            }
        }
    }

    /** The mirror after a refresh from the origin vs the patch: does every block reverse to what was sent? */
    private JsonObject verify(List<PatchBuilder.Entry> sent) {
        ServerLevel level = server.overworld();
        JsonObject o = new JsonObject();
        JsonArray samples = new JsonArray();
        int bad = 0;
        try {
            ReverseTable t = table();
            for (PatchBuilder.Entry e : sent) {
                BlockPos p = e.pos();
                BlockEntity be = level.getBlockEntity(p);
                ReverseTable.Result r = t.reverse(level.getBlockState(p),
                        be == null ? null : be.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
                if (!r.ok() || !r.block().equals(e.block())) {
                    bad++;
                    if (samples.size() < 20) {
                        samples.add(p.getX() + " " + p.getY() + " " + p.getZ() + ": sent " + e.block() + ", mirror now "
                                + (r.ok() ? r.block() : r.error()) + " (" + level.getBlockState(p) + ")");
                    }
                }
            }
        } catch (IOException e) {
            o.addProperty("error", e.toString());
        }
        o.addProperty("sent", sent.size());
        o.addProperty("mismatches", bad);
        o.add("samples", samples);
        return o;
    }

    /** Moves the zone's files to zones/_closed/ and returns {@code message}. */
    private String close(Zone z, String message, JsonObject verify) {
        z.busy = false;
        if (active.remove(z.name) != null) {
            ZoneOutlines.broadcast(server);
        }
        pending.remove(z.name);
        try {
            if (verify != null) {
                Files.writeString(dir(z.name).resolve("verify.json"), GSON.toJson(verify), StandardCharsets.UTF_8);
            }
            Path to = root.resolve("_closed").resolve(z.name + "-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(to.getParent());
            Files.move(dir(z.name), to);
            NebulaeMirror.LOG.info("Zones: {} closed: {} ({})", z.name, message, to);
            return message + "（文件：" + to + "）";
        } catch (IOException e) {
            NebulaeMirror.LOG.warn("Zones: cannot archive {}: {}", z.name, e.toString());
            return message;
        }
    }

    // ------------------------------------------------------------------ abandon

    /**
     * Unlocks at the origin, drops the zone (sync covers it again) and refreshes its chunks so the mirror shows
     * the origin's content.
     */
    public String abandon(Zone z, Consumer<String> done) throws IOException {
        if (z.busy) {
            return "区域 " + z.name + " 正在提交，请等待结果";
        }
        unlockQuietly(z);
        active.remove(z.name);
        ZoneOutlines.broadcast(server);
        deleteDir(dir(z.name));
        SyncService sync = SyncService.get();
        if (sync == null || !sync.ready()) {
            return "区域 " + z.name + " 已放弃；同步未开启，镜像上的改动会保留到下次刷新";
        }
        String why = sync.refresh(z.chunks(), false, o -> done.accept(o.ok()
                ? "区域 " + z.name + " 已放弃，镜像已恢复为原服内容"
                : "区域 " + z.name + " 已放弃，但恢复原服内容失败：" + o.message()
                + "（请执行 /nebulaemirror sync refresh " + String.join(" ", ints2(z.chunks())) + "）"));
        return why == null ? null : "区域 " + z.name + " 已放弃，稍后再恢复内容：" + why
                + "（请执行 /nebulaemirror sync refresh " + String.join(" ", ints2(z.chunks())) + "）";
    }

    /** Bridge job phase (see the bridge's Job) for in-game messages. */
    private static String phaseLabel(String phase) {
        return switch (phase) {
            case "refused" -> "被拒绝";
            case "failed" -> "失败";
            case "cancelled" -> "已取消";
            case "interrupted" -> "被中断";
            default -> phase;
        };
    }

    private static JsonArray ints(int[] a) {
        JsonArray j = new JsonArray();
        for (int i : a) {
            j.add(i);
        }
        return j;
    }

    private static List<String> ints2(int[] a) {
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
                throw new IOException("请在 config/nebulaemirror-sync.json 中设置 \"source\"：回写需要原服的 level.dat");
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
