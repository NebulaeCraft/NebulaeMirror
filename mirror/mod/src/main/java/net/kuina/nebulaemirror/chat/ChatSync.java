package net.kuina.nebulaemirror.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.sync.SyncConfig;
import net.kuina.nebulaemirror.writeback.BridgeClient;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chat, join and leave sync with the origin through its bridge (docs/chat.md). Mirror -> origin: each message is
 * formatted with {@code chat.shownOnOrigin} and posted to {@code /v1/chat/say}, in order, from one sender thread.
 * Origin -> mirror: one thread long-polls {@code /v1/chat} and shows what arrives, formatted with
 * {@code chat.shownOnMirror}, as system messages (which the chat events here do not see, so nothing echoes back).
 * Configured by config/nebulaemirror-sync.json ({@code chat}, {@code bridge}); rebuilt on every sync reload.
 */
public final class ChatSync {
    private static final int WAIT_SECONDS = 25;
    private static ChatSync instance;

    private final MinecraftServer server;
    private final SyncConfig.Chat config;
    private final BridgeClient bridge;
    private final ThreadPoolExecutor sender;
    private final Thread poller;
    private volatile boolean running = true;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile String sendError;
    private volatile String pollError;
    private volatile boolean polling;

    private ChatSync(MinecraftServer server, SyncConfig.Chat config, BridgeClient bridge) {
        this.server = server;
        this.config = config;
        this.bridge = bridge;
        // One thread keeps the order; a bounded queue drops messages while the bridge is unreachable.
        sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), r -> {
            Thread t = new Thread(r, "NebulaeMirror-ChatSend");
            t.setDaemon(true);
            return t;
        }, (r, ex) -> dropped.incrementAndGet());
        poller = new Thread(this::pollLoop, "NebulaeMirror-ChatPoll");
        poller.setDaemon(true);
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                outgoing("chat", sender, message.signedContent()));
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) ->
                outgoing("join", handler.getPlayer(), null));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                outgoing("leave", handler.getPlayer(), null));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> reconfigure(server, null));
    }

    /** (Re)starts chat sync from the config; null or switched off: stops it. Returns a one-line summary. */
    public static synchronized String reconfigure(MinecraftServer server, SyncConfig c) {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
        if (c == null) {
            return "聊天同步已停止";
        }
        if (!c.chat.enabled) {
            NebulaeMirror.LOG.info("Chat sync: off");
            return "聊天同步未开启。";
        }
        BridgeClient b = BridgeClient.of(c.bridge);
        if (b == null) {
            NebulaeMirror.LOG.warn("Chat sync: switched on, but there is no bridge url in config/{}", SyncConfig.FILE);
            return "聊天同步需要在 config/" + SyncConfig.FILE + " 中设置 bridge.url。";
        }
        ChatSync s = new ChatSync(server, c.chat, b);
        if (c.chat.shownOnMirror.enabled) {
            s.poller.start();
        }
        instance = s;
        NebulaeMirror.LOG.info("Chat sync: on: mirror -> origin {}, origin -> mirror {}",
                c.chat.shownOnOrigin.enabled ? "on" : "off", c.chat.shownOnMirror.enabled ? "on" : "off");
        return "聊天同步已开启：镜像 → 原服" + (c.chat.shownOnOrigin.enabled ? "开" : "关")
                + "，原服 → 镜像" + (c.chat.shownOnMirror.enabled ? "开" : "关") + "。";
    }

    private static synchronized ChatSync current() {
        return instance;
    }

    public static String status() {
        ChatSync s = current();
        if (s == null) {
            return "聊天同步未开启";
        }
        StringBuilder sb = new StringBuilder("聊天同步已开启。");
        sb.append("镜像 → 原服：");
        if (!s.config.shownOnOrigin.enabled) {
            sb.append("关");
        } else {
            sb.append("已发送 ").append(s.sent.get()).append(" 条，丢弃 ").append(s.dropped.get()).append(" 条")
                    .append(s.sendError == null ? "" : "，上次错误：" + s.sendError);
        }
        sb.append("；原服 → 镜像：");
        if (!s.config.shownOnMirror.enabled) {
            sb.append("关");
        } else {
            sb.append("已接收 ").append(s.received.get()).append(" 条，")
                    .append(s.pollError != null ? "未连接：" + s.pollError : s.polling ? "已连接" : "连接中");
        }
        return sb.toString();
    }

    private static void outgoing(String kind, ServerPlayer player, String message) {
        ChatSync s = current();
        if (s == null || player == null) {
            return;
        }
        String template = s.config.shownOnOrigin.template(kind);
        if (template == null) {
            return;
        }
        String text = ChatFormat.toLegacy(template, player.getGameProfile().getName(), message);
        if (text.length() > 1024) {
            text = text.substring(0, 1024);
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        s.sender.execute(() -> s.send(body));
    }

    private void send(JsonObject body) {
        if (!running) {
            return;
        }
        try {
            bridge.post("/v1/chat/say", body).get(15, TimeUnit.SECONDS);
            sent.incrementAndGet();
            if (sendError != null) {
                sendError = null;
                NebulaeMirror.LOG.info("Chat sync: sending to the origin works again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            dropped.incrementAndGet();
            String why = BridgeClient.reason(e);
            if (!why.equals(sendError)) {
                NebulaeMirror.LOG.warn("Chat sync: cannot send to the origin (messages are dropped meanwhile): {}", why);
            }
            sendError = why;
        }
    }

    private void pollLoop() {
        String boot = "";
        long since = -1;
        long backoff = 5;
        while (running) {
            try {
                JsonObject r = bridge.get("/v1/chat?boot=" + URLEncoder.encode(boot, StandardCharsets.UTF_8)
                        + "&since=" + since + "&wait=" + WAIT_SECONDS).get();
                for (JsonElement el : r.getAsJsonArray("events")) {
                    deliver(el.getAsJsonObject());
                }
                boot = r.get("boot").getAsString();
                since = r.get("seq").getAsLong();
                backoff = 5;
                polling = true;
                if (pollError != null) {
                    pollError = null;
                    NebulaeMirror.LOG.info("Chat sync: receiving from the origin again");
                }
            } catch (InterruptedException e) {
                return;
            } catch (ExecutionException | RuntimeException e) {
                if (!running) {
                    return;
                }
                polling = false;
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof BridgeClient.BridgeException be && be.status == 404) {
                    pollError = "原服的桥接 mod 版本低于 1.3（旧版号 0.3.0），不支持聊天转发；请更新后执行 /nebulaemirror sync reload";
                    NebulaeMirror.LOG.warn("Chat sync: the origin's bridge is older than 1.3 (no chat relay); update it, then sync reload");
                    return;
                }
                String why = BridgeClient.reason(e);
                if (!why.equals(pollError)) {
                    NebulaeMirror.LOG.warn("Chat sync: cannot receive from the origin, retrying: {}", why);
                }
                pollError = why;
                try {
                    Thread.sleep(backoff * 1000);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(60, backoff * 2);
            }
        }
    }

    private void deliver(JsonObject e) {
        String kind = e.has("kind") ? e.get("kind").getAsString() : "";
        String template = config.shownOnMirror.template(kind);
        if (template == null) {
            return;
        }
        Component c = ChatFormat.toComponent(template, e.has("player") ? e.get("player").getAsString() : "",
                e.has("message") ? e.get("message").getAsString() : "");
        received.incrementAndGet();
        server.execute(() -> {
            if (running) {
                server.getPlayerList().broadcastSystemMessage(c, false);
            }
        });
    }

    private void stop() {
        running = false;
        poller.interrupt();
        sender.shutdownNow();
    }
}
