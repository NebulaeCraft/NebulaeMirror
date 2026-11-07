package net.kuina.nebulaemirror.importer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Converts the overworld region files of a 1.12.2 world into a 1.21.1 world directory, in parallel. */
public final class WorldImporter {
    public static final String REPORT = "nebulaemirror-import.json";

    private WorldImporter() {
    }

    public static ImportStats run(Path source, Path targetWorld, int threads, Predicate<int[]> regionFilter)
            throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        LegacyLevel level = new LegacyLevel(source.resolve("level.dat"));
        ForwardTable table = new ForwardTable(level);
        ImportStats stats = new ImportStats();
        ChunkConverter converter = new ChunkConverter(table, stats);
        Path targetRegion = targetWorld.resolve("region");
        List<Path> regions;
        try (Stream<Path> s = Files.list(source.resolve("region"))) {
            regions = s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .filter(p -> regionFilter.test(RegionIO.regionXZ(p)))
                    .sorted().toList();
        }
        NebulaeMirror.LOG.info("Importing {} regions from {} with {} threads (biomes: {})",
                regions.size(), source, threads, table.biomeNotes);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "NebulaeMirror-Import");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> jobs = new java.util.ArrayList<>();
            for (Path region : regions) jobs.add(pool.submit(() -> {
                Map<Integer, CompoundTag> out = new TreeMap<>();
                try {
                    for (Map.Entry<Integer, CompoundTag> e : RegionIO.read(region).entrySet()) {
                        try {
                            out.put(e.getKey(), converter.convert(e.getValue()));
                            stats.chunks.incrementAndGet();
                        } catch (Exception ex) {
                            stats.error(region.getFileName() + "#" + e.getKey(), ex);
                        }
                    }
                    RegionIO.write(targetRegion.resolve(region.getFileName()), out);
                } catch (IOException ex) {
                    stats.error(region.getFileName().toString(), ex);
                }
                long done = stats.regions.incrementAndGet();
                if (done % 10 == 0 || done == regions.size()) {
                    long s = (System.currentTimeMillis() - start) / 1000;
                    NebulaeMirror.LOG.info("Import: {}/{} regions, {} chunks, {} s", done, regions.size(),
                            stats.chunks.get(), s);
                }
            }));
            for (Future<?> f : jobs) {
                try {
                    f.get();
                } catch (Exception e) {
                    stats.error("job", e);
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        JsonObject report = stats.toJson();
        report.addProperty("source", source.toString());
        report.addProperty("seconds", (System.currentTimeMillis() - start) / 1000.0);
        JsonObject spawn = new JsonObject();
        spawn.addProperty("x", level.spawn.getX());
        spawn.addProperty("y", level.spawn.getY());
        spawn.addProperty("z", level.spawn.getZ());
        report.add("legacySpawn", spawn);
        report.addProperty("spawnApplied", false);
        Files.writeString(targetWorld.resolve(REPORT), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardCharsets.UTF_8);
        NebulaeMirror.LOG.info("Import finished: {} chunks ({} failed), {} modded blocks, {} unknown, {} legacy BEs in {} s",
                stats.chunks.get(), stats.failedChunks.get(), stats.moddedBlocks.get(), stats.unknownBlocks.get(),
                stats.legacyBlockEntities.get(), report.get("seconds").getAsDouble());
        return stats;
    }
}
