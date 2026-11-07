package net.kuina.nebulaemirror.importer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Full offline import at server start (before any level is loaded):
 * <pre>
 *   -Dnebulaemirror.import=/path/to/1.12/world        required; its level.dat + region/
 *   -Dnebulaemirror.import.regions=x1,z1,x2,z2        optional inclusive region range (testing)
 *   -Dnebulaemirror.import.threads=8                  optional, default: available processors
 *   -Dnebulaemirror.import.overwrite=true             allow replacing an existing region/ directory
 * </pre>
 * The mirror world should be a void flat world (see docs/importer.md). The 1.12 spawn is applied once.
 */
public final class ImportHook {
    private ImportHook() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(ImportHook::maybeImport);
        ServerLifecycleEvents.SERVER_STARTED.register(ImportHook::applySpawn);
    }

    private static void maybeImport(MinecraftServer server) {
        String source = System.getProperty("nebulaemirror.import");
        if (source == null || source.isBlank()) {
            return;
        }
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path region = world.resolve("region");
        try {
            if (!Boolean.getBoolean("nebulaemirror.import.overwrite") && Files.isDirectory(region)) {
                try (Stream<Path> s = Files.list(region)) {
                    if (s.findAny().isPresent()) {
                        throw new IllegalStateException(region + " is not empty; refusing to overwrite (set "
                                + "-Dnebulaemirror.import.overwrite=true)");
                    }
                }
            }
            int threads = Integer.getInteger("nebulaemirror.import.threads", Runtime.getRuntime().availableProcessors());
            WorldImporter.run(Path.of(source), world, threads, regionFilter(System.getProperty("nebulaemirror.import.regions")));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("NebulaeMirror import failed", e);
        }
    }

    static Predicate<int[]> regionFilter(String spec) {
        if (spec == null || spec.isBlank()) {
            return r -> true;
        }
        String[] p = spec.split(",");
        int x1 = Integer.parseInt(p[0].trim()), z1 = Integer.parseInt(p[1].trim());
        int x2 = Integer.parseInt(p[2].trim()), z2 = Integer.parseInt(p[3].trim());
        return r -> r[0] >= Math.min(x1, x2) && r[0] <= Math.max(x1, x2) && r[1] >= Math.min(z1, z2) && r[1] <= Math.max(z1, z2);
    }

    private static void applySpawn(MinecraftServer server) {
        Path report = server.getWorldPath(LevelResource.ROOT).resolve(WorldImporter.REPORT);
        if (!Files.isRegularFile(report)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(report, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("spawnApplied") && o.get("spawnApplied").getAsBoolean()) {
                return;
            }
            JsonObject s = o.getAsJsonObject("legacySpawn");
            BlockPos pos = new BlockPos(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
            server.overworld().setDefaultSpawnPos(pos, 0f);
            o.addProperty("spawnApplied", true);
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
            NebulaeMirror.LOG.info("World spawn set to the 1.12 spawn {}", pos);
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.warn("Could not apply the 1.12 spawn: {}", e.toString());
        }
    }
}
