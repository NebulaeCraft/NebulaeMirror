package net.kuina.nebulaemirror.sync;

import com.google.gson.JsonObject;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.importer.ChunkConverter;
import net.kuina.nebulaemirror.importer.ForwardTable;
import net.kuina.nebulaemirror.importer.ImportStats;
import net.kuina.nebulaemirror.importer.LegacyLevel;
import net.kuina.nebulaemirror.importer.RegionIO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * One sync run, driven from the sync coordinator thread:
 * <ol>
 *   <li>pull (optional): run the configured command that refreshes the local 1.12 copy;</li>
 *   <li>scan: compare every region header's chunk timestamps with {@link SyncState}; for the chunks that moved,
 *       compare the {@link SourceHash} and convert only those whose content really changed (worker threads);</li>
 *   <li>apply (main thread, a time budget per tick): a chunk the server does not hold is replaced on disk through
 *       the server's own chunk IO worker; a held chunk is patched block by block ({@link LiveChunkPatcher});</li>
 *   <li>checkpoint: save the world, flush chunk IO, then record what was applied. Records are only written after
 *       the world is on disk, so a crash at any point just makes the next run redo some chunks.</li>
 * </ol>
 */
final class SyncRun {
    enum Mode {
        PULL_AND_APPLY("拉取并应用"), APPLY("应用"), REFRESH("刷新"), BASELINE("建立基线");

        /** Chinese name for in-game messages. */
        final String label;

        Mode(String label) {
            this.label = label;
        }

        /** Label for a mode as recorded in a run report ({@code name().toLowerCase()}). */
        static String label(String reportMode) {
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(reportMode)) {
                    return m.label;
                }
            }
            return reportMode;
        }
    }

    /** A chunk to record in SyncState once the world holding it has been saved. */
    record Applied(int rx, int rz, int index, int timestamp, long hash) {
    }

    record Patch(ChunkPos pos, CompoundTag chunk, Applied record) {
    }

    final Mode mode;
    /** REFRESH after a pull (zone checkout fetches the latest origin state first). */
    private final boolean pullFirst;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final SyncConfig config;
    private final SyncState state;
    private final SyncGuard guard;
    /** REFRESH: inclusive chunk range [x1, z1, x2, z2] converted and applied regardless of the recorded state. */
    private final int[] force;
    /** Chunks changed in the mirror itself (MirrorEdits snapshot): re-applied from the origin like forced ones. */
    final Map<Long, Long> edits;
    final long started = System.currentTimeMillis();

    volatile String phase = "启动中";
    boolean pulling() {
        return "拉取中".equals(phase);
    }

    volatile boolean cancelled;
    volatile boolean stopping;

    final AtomicLong regionsTotal = new AtomicLong();
    final AtomicLong regionsScanned = new AtomicLong();
    final AtomicLong regionsChanged = new AtomicLong();
    final AtomicLong chunksTouched = new AtomicLong();
    final AtomicLong chunksSameContent = new AtomicLong();
    final AtomicLong chunksConverted = new AtomicLong();
    final AtomicLong chunksWritten = new AtomicLong();
    final AtomicLong chunksPatched = new AtomicLong();
    final AtomicLong chunksPatchedNoChange = new AtomicLong();
    final AtomicLong chunksExcluded = new AtomicLong();
    final AtomicLong chunksMissingInSource = new AtomicLong();
    final AtomicLong chunksMirrorEdited = new AtomicLong();
    final AtomicLong blocks = new AtomicLong();
    final AtomicLong blockEntities = new AtomicLong();
    final AtomicLong shapes = new AtomicLong();
    final AtomicLong recorded = new AtomicLong();
    final AtomicLong checkpoints = new AtomicLong();
    double pullSeconds;
    final ImportStats stats = new ImportStats();

    private final ConcurrentLinkedQueue<Patch> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Applied> applied = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final Semaphore permits;

    SyncRun(Mode mode, MinecraftServer server, SyncConfig config, int[] force, boolean pullFirst, Map<Long, Long> edits) {
        this.edits = edits == null ? Map.of() : edits;
        this.mode = mode;
        this.pullFirst = pullFirst;
        this.server = server;
        this.level = server.overworld();
        this.config = config;
        this.state = new SyncState(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        this.guard = new SyncGuard(config.exclude, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        this.force = force;
        this.permits = new Semaphore(config.maxQueuedChunks);
    }

    // ------------------------------------------------------------------ coordinator thread

    JsonObject run() throws Exception {
        Path source = Path.of(config.source);
        if (mode == Mode.PULL_AND_APPLY || pullFirst) {
            pull();
        }
        if (cancelled) {
            return report(source);
        }
        phase = "读取 level.dat";
        LegacyLevel legacy = new LegacyLevel(source.resolve("level.dat"));
        ForwardTable table = mode == Mode.BASELINE ? null : new ForwardTable(legacy);
        ChunkConverter converter = table == null ? null : new ChunkConverter(table, stats);
        Predicate<int[]> regionFilter = net.kuina.nebulaemirror.importer.ImportHook.regionFilter(config.regions);
        List<Path> regions;
        try (Stream<Path> s = Files.list(source.resolve("region"))) {
            regions = s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .filter(p -> regionFilter.test(RegionIO.regionXZ(p)))
                    .filter(p -> force == null || touchesForced(RegionIO.regionXZ(p)))
                    .sorted().toList();
        }
        regionsTotal.set(regions.size());
        phase = mode == Mode.BASELINE ? "记录基线" : "扫描中";
        ExecutorService pool = Executors.newFixedThreadPool(config.threads, r -> {
            Thread t = new Thread(r, "NebulaeMirror-Sync-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (Path region : regions) {
                jobs.add(pool.submit(() -> {
                    if (cancelled) {
                        return;
                    }
                    try {
                        if (mode == Mode.BASELINE) {
                            baselineRegion(region);
                        } else {
                            scanRegion(region, converter);
                        }
                    } catch (Exception e) {
                        stats.error(region.getFileName().toString(), e);
                    }
                    regionsScanned.incrementAndGet();
                }));
            }
            long lastCheckpoint = 0;
            while (true) {
                boolean producing = jobs.stream().anyMatch(f -> !f.isDone());
                if (!producing && outstanding.get() == 0) {
                    break;
                }
                if (stopping) {
                    throw new java.util.concurrent.CancellationException("服务器正在关闭");
                }
                if (cancelled) {
                    pool.shutdownNow();
                    if (outstanding.get() == 0) {
                        break;
                    }
                }
                if (!producing) {
                    phase = "应用中";
                }
                long done = chunksWritten.get() + chunksPatched.get();
                if (done - lastCheckpoint >= config.checkpointChunks) {
                    lastCheckpoint = done;
                    checkpoint();
                }
                Thread.sleep(100);
            }
        } finally {
            pool.shutdownNow();
        }
        if (mode != Mode.BASELINE) {
            checkpoint();
        } else if (!cancelled) {
            state.writeBaseline(source.toString(), "sync baseline");
        }
        return report(source);
    }

    private void pull() throws Exception {
        if (config.pullCommand.isEmpty()) {
            NebulaeMirror.LOG.info("Sync: no pullCommand configured, applying {} as it is", config.source);
            return;
        }
        phase = "拉取中";
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(config.pullCommand).redirectErrorStream(true)
                .directory(server.getServerDirectory().toAbsolutePath().toFile());
        Process p = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    NebulaeMirror.LOG.info("[pull] {}", line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "NebulaeMirror-Pull-Output");
        reader.setDaemon(true);
        reader.start();
        // The pull is never killed for a cancel or a server stop: on Windows that is TerminateProcess, which
        // would skip pull.py's save-on and leave the origin not saving. pull.py bounds itself with its own
        // timeouts; pullTimeoutSeconds (keep it above them) is only the last resort for a hung process.
        try {
            while (!p.waitFor(1, TimeUnit.SECONDS)) {
                if (stopping) {
                    NebulaeMirror.LOG.info("Sync: server stopping; the pull keeps running on its own and turns "
                            + "saving back on at the origin when done");
                    throw new java.util.concurrent.CancellationException("拉取期间服务器正在关闭");
                }
                if (System.currentTimeMillis() - t0 > config.pullTimeoutSeconds * 1000L) {
                    killPull(p);
                    throw new IOException("拉取命令超时（" + config.pullTimeoutSeconds
                            + " 秒）已被终止；请确认原服已恢复保存（RCON：save-on）");
                }
            }
        } finally {
            pullSeconds = (System.currentTimeMillis() - t0) / 1000.0;
        }
        reader.join(2000);
        if (cancelled) {
            return;
        }
        if (p.exitValue() != 0) {
            throw new IOException("拉取命令退出码为 " + p.exitValue() + "（见日志中的 [pull] 行）");
        }
    }

    /** Last resort for a hung pull: the whole process tree (SIGTERM first where the OS has it). */
    private static void killPull(Process p) {
        List<ProcessHandle> children = p.descendants().toList();
        p.destroy();
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        children.forEach(ProcessHandle::destroyForcibly);
    }

    private void checkpoint() throws Exception {
        List<Applied> batch = new ArrayList<>();
        for (Applied a; (a = applied.poll()) != null; ) {
            batch.add(a);
        }
        if (batch.isEmpty()) {
            return;
        }
        String was = phase;
        phase = "保存中";
        onMain(() -> level.getChunkSource().save(false));
        level.getChunkSource().chunkMap.flushWorker();
        Map<Long, List<Applied>> byRegion = new HashMap<>();
        for (Applied a : batch) {
            byRegion.computeIfAbsent(ChunkPos.asLong(a.rx(), a.rz()), k -> new ArrayList<>()).add(a);
        }
        for (List<Applied> list : byRegion.values()) {
            SyncState.RegionState s = state.load(list.get(0).rx(), list.get(0).rz());
            for (Applied a : list) {
                s.set(a.index(), a.timestamp(), a.hash());
            }
            state.save(list.get(0).rx(), list.get(0).rz(), s);
        }
        recorded.addAndGet(batch.size());
        checkpoints.incrementAndGet();
        phase = was;
    }

    /** Runs on the server thread and waits, giving up if the server stops meanwhile. */
    private void onMain(Runnable r) throws Exception {
        CompletableFuture<Void> f = server.submit(r);
        while (true) {
            try {
                f.get(1, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                if (stopping) {
                    throw new java.util.concurrent.CancellationException("服务器正在关闭");
                }
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            }
        }
    }

    // ------------------------------------------------------------------ worker threads

    private boolean touchesForced(int[] r) {
        return r[0] * 32 + 31 >= Math.min(force[0], force[2]) && r[0] * 32 <= Math.max(force[0], force[2])
                && r[1] * 32 + 31 >= Math.min(force[1], force[3]) && r[1] * 32 <= Math.max(force[1], force[3]);
    }

    private boolean forced(int cx, int cz) {
        return (force != null && cx >= Math.min(force[0], force[2]) && cx <= Math.max(force[0], force[2])
                && cz >= Math.min(force[1], force[3]) && cz <= Math.max(force[1], force[3]))
                || edits.containsKey(ChunkPos.asLong(cx, cz));
    }

    private void scanRegion(Path file, ChunkConverter converter) throws IOException, InterruptedException {
        int[] r = RegionIO.regionXZ(file);
        int rx = r[0], rz = r[1];
        SyncState.RegionState st = state.load(rx, rz);
        RegionIO.Header head = RegionIO.readHeader(file);
        boolean any = false;
        for (int i = 0; i < 1024; i++) {
            if (!head.present(i)) {
                if (st.hashes[i] != 0 && force == null) {
                    chunksMissingInSource.incrementAndGet(); // 1.12 never deletes chunks; an editor did. Left as is.
                }
            } else if (wanted(head, st, rx, rz, i)) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        regionsChanged.incrementAndGet();
        RegionIO.Region region = RegionIO.read(file, i -> head.present(i) && wanted(head, st, rx, rz, i));
        for (Map.Entry<Integer, CompoundTag> e : region.chunks().entrySet()) {
            if (cancelled) {
                return;
            }
            int index = e.getKey();
            int cx = RegionIO.chunkX(rx, index), cz = RegionIO.chunkZ(rz, index);
            int ts = region.header().timestamps()[index];
            chunksTouched.incrementAndGet();
            long hash;
            CompoundTag converted;
            try {
                hash = SourceHash.of(e.getValue());
                Applied record = new Applied(rx, rz, index, ts, hash);
                if (!forced(cx, cz) && hash == st.hashes[index]) {
                    chunksSameContent.incrementAndGet(); // only re-saved at the origin
                    applied.add(record);
                    continue;
                }
                converted = converter.convert(e.getValue());
                chunksConverted.incrementAndGet();
                if (edits.containsKey(ChunkPos.asLong(cx, cz))) {
                    chunksMirrorEdited.incrementAndGet();
                }
                while (!permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled) {
                        return;
                    }
                }
                outstanding.incrementAndGet();
                queue.add(new Patch(new ChunkPos(cx, cz), converted, record));
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception ex) {
                stats.error(file.getFileName() + "#" + index, ex);
            }
        }
    }

    private boolean wanted(RegionIO.Header head, SyncState.RegionState st, int rx, int rz, int i) {
        if (force != null || edits.containsKey(ChunkPos.asLong(RegionIO.chunkX(rx, i), RegionIO.chunkZ(rz, i)))) {
            return forced(RegionIO.chunkX(rx, i), RegionIO.chunkZ(rz, i));
        }
        return head.timestamps()[i] != st.timestamps[i] || st.hashes[i] == 0;
    }

    private void baselineRegion(Path file) throws IOException {
        int[] r = RegionIO.regionXZ(file);
        RegionIO.Region region = RegionIO.read(file, i -> true);
        SyncState.RegionState st = new SyncState.RegionState();
        for (Map.Entry<Integer, CompoundTag> e : region.chunks().entrySet()) {
            st.set(e.getKey(), region.header().timestamps()[e.getKey()], SourceHash.of(e.getValue()));
            chunksTouched.incrementAndGet();
        }
        state.save(r[0], r[1], st);
        recorded.addAndGet(region.chunks().size());
    }

    // ------------------------------------------------------------------ server thread

    /** Applies queued chunks until this tick's budget is spent. */
    void tick() {
        if (cancelled) {
            for (Patch p; (p = queue.poll()) != null; ) {
                done();
            }
            return;
        }
        long deadline = System.nanoTime() + config.tickBudgetMillis * 1_000_000L;
        Patch p;
        while (System.nanoTime() < deadline && (p = queue.poll()) != null) {
            try {
                apply(p);
            } catch (Exception e) {
                stats.error("apply " + p.pos(), e);
                LiveChunkPatcher.logFailure(p.pos(), e);
                done();
            }
        }
    }

    private void apply(Patch p) {
        SyncGuard.Coverage coverage = guard.coverage(p.pos());
        if (coverage == SyncGuard.Coverage.FULL) {
            chunksExcluded.incrementAndGet();
            done();
            return;
        }
        LiveChunkPatcher.Residency where = LiveChunkPatcher.residency(level, p.pos());
        if (where == LiveChunkPatcher.Residency.ABSENT && coverage == SyncGuard.Coverage.NONE) {
            chunksWritten.incrementAndGet();
            level.getChunkSource().chunkMap.write(p.pos(), p.chunk()).whenComplete((v, err) -> {
                if (err != null) {
                    stats.error("write " + p.pos(), err);
                } else {
                    applied.add(p.record());
                }
                done();
            });
            return;
        }
        // Held by the server (or partly excluded): patch the live chunk, loading it fully if needed.
        LevelChunk chunk = where == LiveChunkPatcher.Residency.LOADED
                ? level.getChunkSource().getChunkNow(p.pos().x, p.pos().z)
                : (LevelChunk) level.getChunk(p.pos().x, p.pos().z, ChunkStatus.FULL, true);
        LiveChunkPatcher.Result[] holder = new LiveChunkPatcher.Result[1];
        // The sync's own writes are not mirror edits.
        net.kuina.nebulaemirror.writeback.MirrorEdits.quietly(() -> holder[0] = LiveChunkPatcher.apply(level, chunk, p.chunk(), guard));
        LiveChunkPatcher.Result r = holder[0];
        chunksPatched.incrementAndGet();
        if (!r.changed()) {
            chunksPatchedNoChange.incrementAndGet();
        }
        blocks.addAndGet(r.blocks());
        blockEntities.addAndGet(r.blockEntities());
        shapes.addAndGet(r.shapes());
        if (coverage == SyncGuard.Coverage.NONE) {
            applied.add(p.record());
        } else {
            chunksExcluded.incrementAndGet(); // partly applied; stays pending until the exclusion is lifted
        }
        done();
    }

    private void done() {
        outstanding.decrementAndGet();
        permits.release();
    }

    // ------------------------------------------------------------------ reporting

    JsonObject report(Path source) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name().toLowerCase());
        o.addProperty("source", source.toString());
        o.addProperty("started", java.time.Instant.ofEpochMilli(started).toString());
        o.addProperty("seconds", (System.currentTimeMillis() - started) / 1000.0);
        o.addProperty("cancelled", cancelled);
        o.addProperty("pullSeconds", pullSeconds);
        o.addProperty("regions", regionsTotal.get());
        o.addProperty("regionsChanged", regionsChanged.get());
        o.addProperty("chunksTouched", chunksTouched.get());
        o.addProperty("chunksSameContent", chunksSameContent.get());
        o.addProperty("chunksConverted", chunksConverted.get());
        o.addProperty("chunksWrittenToDisk", chunksWritten.get());
        o.addProperty("chunksPatchedLive", chunksPatched.get());
        o.addProperty("chunksPatchedLiveNoChange", chunksPatchedNoChange.get());
        o.addProperty("chunksExcluded", chunksExcluded.get());
        o.addProperty("chunksMissingInSource", chunksMissingInSource.get());
        o.addProperty("mirrorEditedChunks", edits.size());
        o.addProperty("mirrorEditedChunksRestored", chunksMirrorEdited.get());
        o.addProperty("liveBlocksChanged", blocks.get());
        o.addProperty("liveBlockEntitiesChanged", blockEntities.get());
        o.addProperty("liveShapeUpdates", shapes.get());
        o.addProperty("recorded", recorded.get());
        o.addProperty("checkpoints", checkpoints.get());
        JsonObject conv = stats.toJson();
        conv.remove("regions");
        conv.remove("chunks");
        o.add("converter", conv);
        if (!guard.isEmpty()) {
            o.addProperty("exclusions", guard.boxes().toString());
        }
        return o;
    }

    String progress() {
        return phase + "：区域文件 " + regionsScanned.get() + "/" + regionsTotal.get() + "（" + regionsChanged.get()
                + " 个有变化），涉及区块 " + chunksTouched.get() + "，转换 " + chunksConverted.get()
                + "，写盘 " + chunksWritten.get() + "，在线修补 " + chunksPatched.get() + "，排队 "
                + outstanding.get() + "，错误 " + stats.failedChunks.get();
    }
}
