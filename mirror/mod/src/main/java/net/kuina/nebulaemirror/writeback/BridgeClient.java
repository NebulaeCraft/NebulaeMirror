package net.kuina.nebulaemirror.writeback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kuina.nebulaemirror.sync.SyncConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Client for the origin's MirrorBridge HTTP API (see legacy/bridge BridgeHttp). Results arrive off-thread. */
public final class BridgeClient {
    /** A refusal or failure reported by the bridge (HTTP status + its "error" text). */
    public static final class BridgeException extends RuntimeException {
        public final int status;

        BridgeException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final String token;

    private BridgeClient(String base, String token) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token == null ? "" : token;
    }

    /** Null when no bridge is configured. */
    public static BridgeClient of(SyncConfig.Bridge b) {
        return b == null || !b.enabled() ? null : new BridgeClient(b.url.trim(), b.token);
    }

    public CompletableFuture<JsonObject> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public CompletableFuture<JsonObject> post(String path, JsonObject body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
    }

    public CompletableFuture<JsonObject> post(String path, byte[] body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private CompletableFuture<JsonObject> send(HttpRequest.Builder b) {
        HttpRequest req = b.header("X-Mirror-Token", token).timeout(Duration.ofSeconds(60)).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            JsonObject o;
            try {
                o = JsonParser.parseString(r.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new BridgeException(r.statusCode(), "桥接返回了 HTTP " + r.statusCode() + "，但不是 JSON");
            }
            if (r.statusCode() != 200) {
                throw new BridgeException(r.statusCode(), o.has("error") ? o.get("error").getAsString()
                        : "HTTP " + r.statusCode());
            }
            return o;
        });
    }

    /** The bridge's message for a failed future (unwraps CompletionException, names connection problems). */
    public static String reason(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof BridgeException) {
            return t.getMessage();
        }
        if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException) {
            return "无法连接原服的桥接（" + t + "）";
        }
        return t.toString();
    }
}
