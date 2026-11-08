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
    /** Chat, join and leave sync with the origin through the bridge (docs/chat.md). Needs {@link #bridge}. */
    public Chat chat = new Chat();

    public static final class Bridge {
        /** e.g. http://127.0.0.1:21201 (bridge config: httpHost/httpPort) */
        public String url = "";
        /** The bridge's token (its config/mirrorbridge.cfg). */
        public String token = "";

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    public static final class Chat {
        /** Master switch. */
        public boolean enabled = false;
        /** What mirror players say, shown on the origin. */
        public Direction shownOnOrigin = new Direction("&7[来自镜像] &f{player} &7: &f{message}",
                "&7[来自镜像] &e{player} 加入了镜像服", "&7[来自镜像] &e{player} 离开了镜像服");
        /** What origin players say, shown on the mirror. */
        public Direction shownOnMirror = new Direction("&7[来自源] &f{player} &7: &f{message}",
                "&7[来自源] &e{player} 加入了源服务器", "&7[来自源] &e{player} 离开了源服务器");

        void fill() {
            Chat d = new Chat();
            if (shownOnOrigin == null) {
                shownOnOrigin = d.shownOnOrigin;
            }
            if (shownOnMirror == null) {
                shownOnMirror = d.shownOnMirror;
            }
        }
    }

    /**
     * One direction: templates with {player} and {message}, & colour codes (&0-9a-f, &k-o, &r, &#RRGGBB).
     * An empty template: that kind of message is not synced.
     */
    public static final class Direction {
        public boolean enabled = true;
        public String chat = "";
        public String join = "";
        public String leave = "";

        public Direction() {
        }

        Direction(String chat, String join, String leave) {
            this.chat = chat;
            this.join = join;
            this.leave = leave;
        }

        /** The template for a kind ("chat", "join", "leave"); null when this kind is not synced. */
        public String template(String kind) {
            if (!enabled) {
                return null;
            }
            String t = switch (kind) {
                case "chat" -> chat;
                case "join" -> join;
                case "leave" -> leave;
                default -> null;
            };
            return t == null || t.isEmpty() ? null : t;
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
        if (c.pullCommand == null) {
            c.pullCommand = new ArrayList<>();
        }
        if (c.exclude == null) {
            c.exclude = new ArrayList<>();
        }
        if (c.bridge == null) {
            c.bridge = new Bridge();
        }
        if (c.chat == null) {
            c.chat = new Chat();
        }
        c.chat.fill();
        if (!hasAllKeys(com.google.gson.JsonParser.parseString(text), GSON.toJsonTree(c))) {
            // Written by an older version (or partly filled in): add the missing settings with their defaults, so
            // they can be seen and changed. Values already there are kept as they are.
            Files.writeString(f, GSON.toJson(c), StandardCharsets.UTF_8);
        }
        c.threads = Math.max(1, c.threads);
        c.tickBudgetMillis = Math.max(1, c.tickBudgetMillis);
        c.maxQueuedChunks = Math.max(1, c.maxQueuedChunks);
        c.checkpointChunks = Math.max(1, c.checkpointChunks);
        return c;
    }

    /** True when every object key of {@code full} (at any depth) is also in {@code present}. */
    static boolean hasAllKeys(com.google.gson.JsonElement present, com.google.gson.JsonElement full) {
        if (!full.isJsonObject()) {
            return true;
        }
        if (!present.isJsonObject()) {
            return false;
        }
        com.google.gson.JsonObject p = present.getAsJsonObject();
        for (var e : full.getAsJsonObject().entrySet()) {
            if (!p.has(e.getKey()) || !hasAllKeys(p.get(e.getKey()), e.getValue())) {
                return false;
            }
        }
        return true;
    }
}
