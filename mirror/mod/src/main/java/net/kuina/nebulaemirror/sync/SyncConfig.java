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
    /**
     * Players and WorldEdit may only change blocks inside zones they checked out (see EditGuard); edits outside
     * zones are re-applied from the origin at the next sync either way.
     */
    public boolean protectOutsideZones = true;
    /** The origin's MirrorBridge HTTP API (zone lock and submit). Empty url: zones are not locked at the origin. */
    public Bridge bridge = new Bridge();

    public static final class Bridge {
        /** e.g. http://127.0.0.1:21201 (bridge config: httpHost/httpPort) */
        public String url = "";
        /** The bridge's token (its config/mirrorbridge.cfg). */
        public String token = "";

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

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
        String text = Files.readString(f, StandardCharsets.UTF_8);
        SyncConfig c = GSON.fromJson(text, SyncConfig.class);
        com.google.gson.JsonObject present = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        if (!present.keySet().containsAll(GSON.toJsonTree(new SyncConfig()).getAsJsonObject().keySet())) {
            // Written by an older version: add the new settings (with their defaults) so they can be filled in.
            if (c.bridge == null) {
                c.bridge = new Bridge();
            }
            Files.writeString(f, GSON.toJson(c), StandardCharsets.UTF_8);
        }
        if (c.pullCommand == null) {
            c.pullCommand = new ArrayList<>();
        }
        if (c.exclude == null) {
            c.exclude = new ArrayList<>();
        }
        if (c.bridge == null) {
            c.bridge = new Bridge();
        }
        c.threads = Math.max(1, c.threads);
        c.tickBudgetMillis = Math.max(1, c.tickBudgetMillis);
        c.maxQueuedChunks = Math.max(1, c.maxQueuedChunks);
        c.checkpointChunks = Math.max(1, c.checkpointChunks);
        return c;
    }
}
