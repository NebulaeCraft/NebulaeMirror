package net.kuina.nebulaemirror.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The mirror's way in: a small HTTP API on {@code httpHost:httpPort} (default 127.0.0.1 only), every request
 * carrying {@code X-Mirror-Token: <token from config/mirrorbridge.cfg>}. All work runs on the server thread.
 * <pre>
 *   GET  /v1/status                      version, locks, current job
 *   POST /v1/lock     {zone, min, max, owner}
 *   POST /v1/unlock   {zone}
 *   POST /v1/submit?zone=Z[&force=true]  body: patch (gzip NBT) -> {job}
 *   GET  /v1/job?id=J                    job report (job.json)
 *   GET  /v1/chat?boot=B&since=S&wait=W  origin chat/joins/leaves after S (long poll, W s at most; see ChatRelay)
 *   POST /v1/chat/say {text}             show mirror text (formatted, § codes) to every player here
 * </pre>
 * A submit must lie inside its zone's lock; its job unlocks the zone when it is done.
 */
final class BridgeHttp {
    private static final Gson GSON = new Gson();
    private static final int MAX_PATCH = 256 * 1024 * 1024;
    private final MinecraftServer server;
    private HttpServer http;
    private ExecutorService pool;

    BridgeHttp(MinecraftServer server) {
        this.server = server;
    }

    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(BridgeConfig.httpHost, BridgeConfig.httpPort), 16);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "MirrorBridge-HTTP");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/v1/", this::handle);
        http.start();
        MirrorBridge.LOG.info("HTTP API on {}:{}", BridgeConfig.httpHost, BridgeConfig.httpPort);
    }

    void stop() {
        if (http != null) {
            http.stop(0);
            pool.shutdownNow();
        }
    }

    private static final class HttpError extends Exception {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        int code = 200;
        JsonObject out;
        try {
            if (!tokenOk(ex.getRequestHeaders().getFirst("X-Mirror-Token"))) {
                throw new HttpError(401, "X-Mirror-Token 缺失或不正确");
            }
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String method = ex.getRequestMethod();
            if (path.equals("/v1/status") && method.equals("GET")) {
                out = onMain(this::status);
            } else if (path.equals("/v1/lock") && method.equals("POST")) {
                JsonObject in = json(ex);
                out = onMain(() -> lock(in));
            } else if (path.equals("/v1/unlock") && method.equals("POST")) {
                JsonObject in = json(ex);
                out = onMain(() -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("unlocked", MirrorBridge.locks.unlock(in.get("zone").getAsString()));
                    return o;
                });
            } else if (path.equals("/v1/submit") && method.equals("POST")) {
                String zone = q.getOrDefault("zone", "");
                boolean force = "true".equals(q.get("force"));
                byte[] body = read(ex.getRequestBody(), MAX_PATCH);
                out = onMain(() -> submit(zone, force, body));
            } else if (path.equals("/v1/job") && method.equals("GET")) {
                String id = q.getOrDefault("id", "");
                out = onMain(() -> job(id));
            } else if (path.equals("/v1/chat") && method.equals("GET")) {
                // Waits on this HTTP thread, not the server thread.
                long since = parseLong(q.get("since"), -1);
                long wait = Math.max(0, Math.min(30, parseLong(q.get("wait"), 0)));
                out = MirrorBridge.chat.poll(q.getOrDefault("boot", ""), since, wait * 1000);
            } else if (path.equals("/v1/chat/say") && method.equals("POST")) {
                JsonObject in = json(ex);
                String text = in.has("text") ? in.get("text").getAsString() : "";
                if (text.isEmpty() || text.length() > ChatRelay.MAX_TEXT) {
                    throw new HttpError(400, "文本长度必须为 1.." + ChatRelay.MAX_TEXT + " 个字符");
                }
                server.addScheduledTask(() -> ChatRelay.broadcast(server, text));
                out = new JsonObject();
                out.addProperty("ok", true);
            } else {
                throw new HttpError(404, "没有这个接口：" + method + " " + path);
            }
        } catch (HttpError e) {
            code = e.code;
            out = error(e.getMessage());
        } catch (Exception e) {
            code = 500;
            out = error(e.toString());
            MirrorBridge.LOG.warn("HTTP {} failed: {}", ex.getRequestURI(), e.toString());
        }
        byte[] bytes = GSON.toJson(out).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static boolean tokenOk(String given) {
        String want = BridgeConfig.token;
        return given != null && !want.isEmpty() && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private interface MainTask {
        JsonObject run() throws HttpError, IOException;
    }

    private JsonObject onMain(MainTask task) throws Exception {
        Callable<Object> c = () -> {
            try {
                return task.run();
            } catch (HttpError e) {
                return e;
            }
        };
        Object r = server.callFromMainThread(c).get(30, TimeUnit.SECONDS);
        if (r instanceof HttpError) {
            throw (HttpError) r;
        }
        return (JsonObject) r;
    }

    private JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("version", MirrorBridge.VERSION);
        JsonArray locks = new JsonArray();
        for (ZoneLocks.Lock l : MirrorBridge.locks.all()) {
            locks.add(GSON.toJsonTree(l));
        }
        o.add("locks", locks);
        Job j = MirrorBridge.current;
        if (j != null) {
            o.add("job", j.report());
        }
        return o;
    }

    private JsonObject lock(JsonObject in) throws HttpError {
        String zone = in.has("zone") ? in.get("zone").getAsString() : "";
        if (!zone.matches("[a-z0-9_-]{1,32}")) {
            throw new HttpError(400, "区域名无效");
        }
        String why = MirrorBridge.locks.lock(zone, ints(in.get("min")), ints(in.get("max")),
                in.has("owner") ? in.get("owner").getAsString() : "mirror");
        if (why != null) {
            throw new HttpError(409, why);
        }
        MirrorBridge.LOG.info("Locked {}", MirrorBridge.locks.get(zone));
        JsonObject o = new JsonObject();
        o.addProperty("locked", zone);
        return o;
    }

    private JsonObject submit(String zone, boolean force, byte[] body) throws HttpError, IOException {
        ZoneLocks.Lock lock = MirrorBridge.locks.get(zone);
        if (lock == null && !force) {
            throw new HttpError(409, "区域 " + zone + " 在原服没有锁定（请重新签出，或用 force 提交）");
        }
        File f = new File(MirrorBridge.store.inbox, zone + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".nbt");
        Files.write(f.toPath(), body);
        Patch patch;
        try {
            patch = Patch.read(f);
        } catch (IOException e) {
            Files.deleteIfExists(f.toPath());
            throw new HttpError(400, "不是有效的补丁：" + e.getMessage());
        }
        if (!zone.equals(patch.zone)) {
            Files.deleteIfExists(f.toPath());
            throw new HttpError(400, "补丁属于区域 " + patch.zone + "，而不是 " + zone);
        }
        if (lock != null) {
            for (Patch.Entry e : patch.entries) {
                if (!lock.contains(e.x, e.y, e.z)) {
                    Files.deleteIfExists(f.toPath());
                    throw new HttpError(400, "条目 " + e.x + " " + e.y + " " + e.z + " 在锁定区域之外");
                }
            }
        }
        String why = MirrorBridge.start("submit", f, true, force);
        if (why != null) {
            Files.deleteIfExists(f.toPath());
            throw new HttpError(409, why);
        }
        JsonObject o = new JsonObject();
        o.addProperty("job", MirrorBridge.current.id);
        o.addProperty("entries", patch.entries.size());
        return o;
    }

    private JsonObject job(String id) throws HttpError {
        Job j = MirrorBridge.current;
        if (j != null && j.id.equals(id)) {
            return j.report();
        }
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new HttpError(400, "任务 ID 无效");
        }
        JsonObject o = BridgeStore.readReport(MirrorBridge.store.job(id));
        if (o == null) {
            throw new HttpError(404, "没有任务 " + id);
        }
        return o;
    }

    private static long parseLong(String s, long fallback) throws HttpError {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "不是数字：" + s);
        }
    }

    private static int[] ints(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static JsonObject json(HttpExchange ex) throws IOException, HttpError {
        String s = new String(read(ex.getRequestBody(), 1 << 20), StandardCharsets.UTF_8);
        try {
            return new JsonParser().parse(s).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new HttpError(400, "请求体不是 JSON 对象");
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException, HttpError {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) {
                throw new HttpError(413, "请求体超过 " + max + " 字节");
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> query(String raw) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            }
        }
        return m;
    }
}
