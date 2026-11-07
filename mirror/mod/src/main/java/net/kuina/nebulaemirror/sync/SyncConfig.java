package net.kuina.nebulaemirror.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/nebulaemirror-sync.json (created with sync switched off on first start). See docs/sync.md.
 */
public final class SyncConfig {
    /** Local copy of the 1.12 world (level.dat + region/) that {@link #pullCommand} keeps up to date. Empty: sync off. */
    public String source = "";
    /** Command that refreshes {@link #source} from the origin (ops/sync/pull.py); run in the server directory. */
    public List<String> pullCommand = new ArrayList<>();
    public int pullTimeoutSeconds = 1800;
    /** Minutes between automatic runs (pull + apply); 0: only on command. */
    public int intervalMinutes = 0;
    /** Converter threads (the server keeps the remaining cores). */
    public int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    /** Main-thread time per tick spent applying chunks. */
    public int tickBudgetMillis = 15;
    /** Converted chunks waiting to be applied or written, at most (bounds memory). */
    public int maxQueuedChunks = 256;
    /** Save the world and record progress every this many applied chunks. */
    public int checkpointChunks = 4000;
    /** Optional region range "x1,z1,x2,z2" to sync (when only that range was imported). Empty: all regions. */
    public String regions = "";
    /** Block areas the sync never touches (inclusive; y defaults to the whole height). */
    public List<SyncGuard.Box> exclude = new ArrayList<>();

    public static final String FILE = "nebulaemirror-sync.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public boolean enabled() {
        return source != null && !source.isBlank();
    }

    public static SyncConfig load(Path configDir) throws IOException {
        Path f = configDir.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            SyncConfig c = new SyncConfig();
            Files.createDirectories(configDir);
            Files.writeString(f, GSON.toJson(c), StandardCharsets.UTF_8);
            return c;
        }
        SyncConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), SyncConfig.class);
        if (c.pullCommand == null) {
            c.pullCommand = new ArrayList<>();
        }
        if (c.exclude == null) {
            c.exclude = new ArrayList<>();
        }
        c.threads = Math.max(1, c.threads);
        c.tickBudgetMillis = Math.max(1, c.tickBudgetMillis);
        c.maxQueuedChunks = Math.max(1, c.maxQueuedChunks);
        c.checkpointChunks = Math.max(1, c.checkpointChunks);
        return c;
    }
}
