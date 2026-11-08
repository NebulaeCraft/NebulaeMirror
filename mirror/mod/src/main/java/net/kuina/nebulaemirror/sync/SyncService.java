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
            return "Cannot read config/" + SyncConfig.FILE + ": " + e;
        }
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        if (!config.enabled()) {
            NebulaeMirror.LOG.info("Sync: off (no \"source\" in config/{})", SyncConfig.FILE);
            return "Sync is off: set \"source\" in config/" + SyncConfig.FILE;
        }
        String msg = "Sync source " + config.source + (config.intervalMinutes > 0
                ? ", every " + config.intervalMinutes + " min" : ", on command only");
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
        return msg;
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
            return "sync is off: set \"source\" in config/" + SyncConfig.FILE;
        }
        if (current != null) {
            return "a " + current.mode.name().toLowerCase() + " run is in progress (" + current.progress() + ")";
        }
        if (!Files.isRegularFile(Path.of(c.source).resolve("level.dat"))) {
            return c.source + " has no level.dat";
        }
        SyncState state = new SyncState(server.getWorldPath(LevelResource.ROOT));
        if (mode != SyncRun.Mode.BASELINE && !state.hasBaseline()) {
            return "no sync baseline in " + state.root() + ": do the full import from the sync source, or run "
                    + "\"/nebulaemirror sync baseline\" if this world was imported from exactly the current source";
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
                outcome = new Outcome(false, "sync " + mode.name().toLowerCase() + " failed: " + t, report);
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
        String s = "sync " + r.get("mode").getAsString() + (r.get("cancelled").getAsBoolean() ? " (cancelled)" : "")
                + " in " + r.get("seconds").getAsDouble() + " s";
        if ("baseline".equals(r.get("mode").getAsString())) {
            return s + ": recorded " + r.get("recorded").getAsLong() + " chunks from " + r.get("regions").getAsLong()
                    + " regions";
        }
        JsonObject conv = r.getAsJsonObject("converter");
        return s + ": " + r.get("regionsChanged").getAsLong() + "/" + r.get("regions").getAsLong() + " regions changed, "
                + r.get("chunksTouched").getAsLong() + " chunks re-saved, " + r.get("chunksConverted").getAsLong()
                + " changed (" + r.get("chunksWrittenToDisk").getAsLong() + " written to disk, "
                + r.get("chunksPatchedLive").getAsLong() + " patched live: " + r.get("liveBlocksChanged").getAsLong()
                + " blocks, " + r.get("liveBlockEntitiesChanged").getAsLong() + " block entities), "
                + r.get("chunksExcluded").getAsLong() + " excluded, "
                + mirrorEdits(r)
                + conv.get("unknownBlocks").getAsLong()
                + " unknown blocks, " + conv.get("failedChunks").getAsLong() + " errors";
    }

    private static String mirrorEdits(JsonObject r) {
        long edited = r.has("mirrorEditedChunks") ? r.get("mirrorEditedChunks").getAsLong() : 0;
        if (edited == 0) {
            return "";
        }
        long restored = r.get("mirrorEditedChunksRestored").getAsLong();
        return restored + " chunk(s) edited in the mirror re-applied from the origin"
                + (restored < edited ? " (" + (edited - restored) + " not in the origin copy, left as they are)" : "") + ", ";
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
            return "no sync run in progress";
        }
        r.cancelled = true;
        return r.pulling()
                ? "the " + r.mode.name().toLowerCase() + " run stops once the running pull has finished (the pull is "
                + "not interrupted, so the origin gets save-on)"
                : "cancelling the " + r.mode.name().toLowerCase() + " run";
    }

    String status() {
        SyncConfig c = config;
        StringBuilder sb = new StringBuilder();
        if (!c.enabled()) {
            sb.append("Sync is off (no \"source\" in config/").append(SyncConfig.FILE).append(").");
        } else {
            sb.append("Source ").append(c.source).append(c.intervalMinutes > 0 ? ", every " + c.intervalMinutes + " min"
                    : ", on command only").append(c.pullCommand.isEmpty() ? ", no pull command" : "");
            SyncState state = new SyncState(server.getWorldPath(LevelResource.ROOT));
            sb.append(state.hasBaseline() ? ", baseline " + state.baselineInfo().get("time") : ", NO BASELINE");
            if (!c.exclude.isEmpty()) {
                sb.append(", ").append(c.exclude.size()).append(" excluded area(s)");
            }
        }
        MirrorEdits edits = MirrorEdits.get();
        if (edits != null && edits.size() > 0) {
            sb.append("\n").append(edits.size()).append(" chunk(s) edited in the mirror are re-applied from the origin "
                    + "at the next sync");
        }
        SyncRun r = current;
        if (r != null) {
            sb.append("\nRunning ").append(r.mode.name().toLowerCase()).append(" for ")
                    .append((System.currentTimeMillis() - r.started) / 1000).append(" s: ").append(r.progress());
        }
        JsonObject last = lastReport;
        if (last != null) {
            sb.append("\nLast: ").append(last.has("error") ? "FAILED " + last.get("error").getAsString() : summary(last));
        } else if (lastError != null) {
            sb.append("\nLast error: ").append(lastError);
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
