package net.kuina.nebulaemirror.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.writeback.MirrorEdits;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Online incremental sync, origin -> mirror (P2). Owns the config, the interval timer, the single coordinator
 * thread and the per-tick hook that applies converted chunks. At most one run at a time; see {@link SyncRun}.
 */
public final class SyncService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile SyncService instance;

    private final MinecraftServer server;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NebulaeMirror-Sync");
        t.setDaemon(true);
        return t;
    });
    private ScheduledExecutorService timer;
    private volatile SyncConfig config;
    private volatile SyncRun current;
    private volatile JsonObject lastReport;
    private volatile String lastError;

    private SyncService(MinecraftServer server) {
        this.server = server;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SyncService s = new SyncService(server);
            s.loadConfig();
            MirrorEdits.start(new SyncState(server.getWorldPath(LevelResource.ROOT)).root());
            instance = s;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SyncService s = instance;
            instance = null;
            if (s != null) {
                s.shutdown();
            }
            MirrorEdits.stop();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SyncService s = instance;
            SyncRun r = s == null ? null : s.current;
            if (r != null) {
                r.tick();
            }
        });
    }

    public static SyncService get() {
        return instance;
    }

    /** (Re)reads config/nebulaemirror-sync.json and restarts the interval timer. */
    public synchronized String loadConfig() {
        try {
            config = SyncConfig.load(FabricLoader.getInstance().getConfigDir());
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.error("Sync: cannot read config/{}: {}", SyncConfig.FILE, e.toString());
            config = new SyncConfig();
            net.kuina.nebulaemirror.chat.ChatSync.reconfigure(server, null);
            return "无法读取 config/" + SyncConfig.FILE + "：" + e;
        }
        // Chat sync only needs the bridge, not the sync source.
        String chat = net.kuina.nebulaemirror.chat.ChatSync.reconfigure(server, config);
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        if (!config.enabled()) {
            NebulaeMirror.LOG.info("Sync: off (no \"source\" in config/{})", SyncConfig.FILE);
            return "同步未开启：请在 config/" + SyncConfig.FILE + " 中设置 \"source\"。" + chat;
        }
        String msg = "同步源 " + config.source + (config.intervalMinutes > 0
                ? "，每 " + config.intervalMinutes + " 分钟一次" : "，仅手动执行");
        if (config.intervalMinutes > 0) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NebulaeMirror-Sync-Timer");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleWithFixedDelay(() -> {
                String why = start(SyncRun.Mode.PULL_AND_APPLY, null, false, null);
                if (why != null) {
                    NebulaeMirror.LOG.info("Sync: scheduled run skipped: {}", why);
                }
            }, 1, config.intervalMinutes, TimeUnit.MINUTES);
        }
        NebulaeMirror.LOG.info("Sync: {}", msg);
        return msg + "。" + chat;
    }

    /** How a run ended: ok = finished, not cancelled, no chunk errors. Delivered on the server thread. */
    public record Outcome(boolean ok, String message, JsonObject report) {
    }

    /**
     * Re-syncs an inclusive chunk range from the origin regardless of what was recorded (zone checkout and
     * abandon). With {@code pull} the configured pull runs first. Returns null when started, otherwise why not.
     */
    public String refresh(int[] chunkRange, boolean pull, Consumer<Outcome> done) {
        return start(SyncRun.Mode.REFRESH, chunkRange, pull, done);
    }

    /** True when sync is configured and has a baseline, i.e. a refresh can run. */
    public boolean ready() {
        return config.enabled() && new SyncState(server.getWorldPath(LevelResource.ROOT)).hasBaseline();
    }

    public SyncConfig config() {
        return config;
    }

    /**
     * Starts a run in the background. Returns null when started, otherwise why not.
     * {@code force} (REFRESH only) is an inclusive chunk range; {@code done} receives the outcome.
     */
    synchronized String start(SyncRun.Mode mode, int[] force, boolean pullFirst, Consumer<Outcome> done) {
        SyncConfig c = config;
        if (!c.enabled()) {
            return "同步未开启：请在 config/" + SyncConfig.FILE + " 中设置 \"source\"";
        }
        if (current != null) {
            return "已有一次“" + current.mode.label + "”同步在进行中（" + current.progress() + "）";
        }
        if (!Files.isRegularFile(Path.of(c.source).resolve("level.dat"))) {
            return c.source + " 中没有 level.dat";
        }
        SyncState state = new SyncState(server.getWorldPath(LevelResource.ROOT));
        if (mode != SyncRun.Mode.BASELINE && !state.hasBaseline()) {
            return state.root() + " 中没有同步基线：请从同步源做一次完整导入；如果这个世界正是从当前同步源导入的，"
                    + "也可以执行 /nebulaemirror sync baseline";
        }
        MirrorEdits edits = MirrorEdits.get();
        // Chunks edited in the mirror are re-applied from the origin (a refresh only takes those in its range).
        Map<Long, Long> edited = edits == null || mode == SyncRun.Mode.BASELINE ? Map.of() : edits.snapshot(force);
        SyncRun run = new SyncRun(mode, server, c, force, pullFirst, edited);
        current = run;
        coordinator.execute(() -> {
            Outcome outcome;
            try {
                JsonObject report = run.run();
                lastReport = report;
                lastError = null;
                writeReport(state, report);
                boolean ok = !run.cancelled && report.getAsJsonObject("converter").get("failedChunks").getAsLong() == 0;
                if (ok && edits != null && !edited.isEmpty()) {
                    edits.forget(edited);
                }
                outcome = new Outcome(ok, summary(report), report);
                NebulaeMirror.LOG.info("Sync: {}", outcome.message());
            } catch (Throwable t) {
                lastError = t.toString();
                JsonObject report = run.report(Path.of(c.source));
                report.addProperty("error", t.toString());
                lastReport = report;
                writeReport(state, report);
                outcome = new Outcome(false, "同步（" + mode.label + "）失败：" + t, report);
                if (run.stopping) {
                    NebulaeMirror.LOG.info("Sync: interrupted by server stop; the next run redoes unrecorded chunks");
                } else if (t instanceof IOException) {
                    NebulaeMirror.LOG.error("Sync: run failed: {}", t.getMessage());
                } else {
                    NebulaeMirror.LOG.error("Sync: run failed", t);
                }
            } finally {
                current = null;
            }
            if (done != null) {
                Outcome o = outcome;
                server.execute(() -> done.accept(o));
            }
        });
        return null;
    }

    static String summary(JsonObject r) {
        String s = "同步（" + SyncRun.Mode.label(r.get("mode").getAsString()) + "）"
                + (r.get("cancelled").getAsBoolean() ? "已取消" : "完成") + "，用时 " + r.get("seconds").getAsDouble() + " 秒";
        if ("baseline".equals(r.get("mode").getAsString())) {
            return s + "：从 " + r.get("regions").getAsLong() + " 个区域文件记录了 " + r.get("recorded").getAsLong()
                    + " 个区块";
        }
        JsonObject conv = r.getAsJsonObject("converter");
        return s + "：区域文件 " + r.get("regionsChanged").getAsLong() + "/" + r.get("regions").getAsLong() + " 个有变化，"
                + r.get("chunksTouched").getAsLong() + " 个区块被重新保存，其中 " + r.get("chunksConverted").getAsLong()
                + " 个有改动（写盘 " + r.get("chunksWrittenToDisk").getAsLong() + " 个，在线修补 "
                + r.get("chunksPatchedLive").getAsLong() + " 个：" + r.get("liveBlocksChanged").getAsLong()
                + " 个方块、" + r.get("liveBlockEntitiesChanged").getAsLong() + " 个方块实体），"
                + "排除 " + r.get("chunksExcluded").getAsLong() + " 个，"
                + mirrorEdits(r)
                + "未知方块 " + conv.get("unknownBlocks").getAsLong()
                + " 个，错误 " + conv.get("failedChunks").getAsLong() + " 个";
    }

    private static String mirrorEdits(JsonObject r) {
        long edited = r.has("mirrorEditedChunks") ? r.get("mirrorEditedChunks").getAsLong() : 0;
        if (edited == 0) {
            return "";
        }
        long restored = r.get("mirrorEditedChunksRestored").getAsLong();
        return "镜像中改动过的区块有 " + restored + " 个已按原服内容恢复"
                + (restored < edited ? "（另有 " + (edited - restored) + " 个不在原服副本中，保持不变）" : "") + "，";
    }

    private void writeReport(SyncState state, JsonObject report) {
        try {
            Files.createDirectories(state.root());
            Files.writeString(state.root().resolve("last-run.json"), GSON.toJson(report), StandardCharsets.UTF_8);
            Files.writeString(state.root().resolve("history.jsonl"), new Gson().toJson(report) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            NebulaeMirror.LOG.warn("Sync: cannot write the run report: {}", e.toString());
        }
    }

    String cancel() {
        SyncRun r = current;
        if (r == null) {
            return "当前没有进行中的同步";
        }
        r.cancelled = true;
        return r.pulling()
                ? "“" + r.mode.label + "”同步会在当前拉取结束后停止（拉取不会被中断，以保证原服恢复 save-on）"
                : "正在取消“" + r.mode.label + "”同步";
    }

    String status() {
        SyncConfig c = config;
        StringBuilder sb = new StringBuilder();
        if (!c.enabled()) {
            sb.append("同步未开启（config/").append(SyncConfig.FILE).append(" 中没有 \"source\"）。");
        } else {
            sb.append("同步源 ").append(c.source).append(c.intervalMinutes > 0 ? "，每 " + c.intervalMinutes + " 分钟一次"
                    : "，仅手动执行").append(c.pullCommand.isEmpty() ? "，未配置拉取命令" : "");
            SyncState state = new SyncState(server.getWorldPath(LevelResource.ROOT));
            sb.append(state.hasBaseline() ? "，基线时间 " + state.baselineInfo().get("time") : "，没有基线");
            if (!c.exclude.isEmpty()) {
                sb.append("，").append(c.exclude.size()).append(" 个排除区域");
            }
        }
        MirrorEdits edits = MirrorEdits.get();
        if (edits != null && edits.size() > 0) {
            sb.append("\n镜像中改动过的 ").append(edits.size()).append(" 个区块会在下次同步时按原服内容恢复");
        }
        SyncRun r = current;
        if (r != null) {
            sb.append("\n正在进行“").append(r.mode.label).append("”，已用 ")
                    .append((System.currentTimeMillis() - r.started) / 1000).append(" 秒：").append(r.progress());
        }
        JsonObject last = lastReport;
        if (last != null) {
            sb.append("\n上次：").append(last.has("error") ? "失败 " + last.get("error").getAsString() : summary(last));
        } else if (lastError != null) {
            sb.append("\n上次错误：").append(lastError);
        }
        return sb.toString();
    }

    private void shutdown() {
        if (timer != null) {
            timer.shutdownNow();
        }
        SyncRun r = current;
        if (r != null) {
            r.stopping = true;
            r.cancelled = true;
        }
        coordinator.shutdown();
        try {
            if (!coordinator.awaitTermination(30, TimeUnit.SECONDS)) {
                NebulaeMirror.LOG.warn("Sync: run did not stop within 30 s");
                coordinator.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
