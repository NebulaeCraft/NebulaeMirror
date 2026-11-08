package net.kuina.nebulaemirror.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.security.SecureRandom;
import java.util.ArrayDeque;

/**
 * Chat sync with the mirror (docs/chat.md). The origin's chat, joins and leaves go into a small ring buffer that
 * the mirror long-polls ({@code GET /v1/chat}); text the mirror formatted is broadcast here ({@code POST /v1/chat/say}).
 * Whether anything is synced, and how it looks, is configured on the mirror only.
 */
final class ChatRelay {
    static final int CAPACITY = 200;
    static final int MAX_TEXT = 1024;

    /** Random per start: a mirror that polled an earlier start of the bridge takes this one's buffer from the beginning. */
    final String boot;
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
    private long seq;

    ChatRelay() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        boot = sb.toString();
    }

    /** Thread-safe: 1.12 fires chat on the network thread, joins and leaves on the server thread. */
    synchronized void add(String kind, String player, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("seq", ++seq);
        e.addProperty("kind", kind);
        e.addProperty("player", player);
        if (message != null) {
            e.addProperty("message", message);
        }
        events.addLast(e);
        while (events.size() > CAPACITY) {
            events.removeFirst();
        }
        notifyAll();
    }

    /**
     * Events after {@code since}, waiting up to {@code waitMillis} for one. {@code since < 0}: none, only the current
     * seq (a mirror that just started does not replay old chat). A different {@code boot}: everything buffered.
     */
    synchronized JsonObject poll(String boot, long since, long waitMillis) throws InterruptedException {
        if (since >= 0 && !this.boot.equals(boot)) {
            since = 0;
        }
        if (since >= 0) {
            long deadline = System.currentTimeMillis() + waitMillis;
            long left;
            while (seq <= since && (left = deadline - System.currentTimeMillis()) > 0) {
                wait(left);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("boot", this.boot);
        o.addProperty("seq", seq);
        JsonArray list = new JsonArray();
        if (since >= 0) {
            for (JsonObject e : events) {
                if (e.get("seq").getAsLong() > since) {
                    list.add(e);
                }
            }
        }
        o.add("events", list);
        return o;
    }

    /** Shows mirror text (already formatted, § codes) to every player here; logged without the codes. */
    static void broadcast(MinecraftServer server, String text) {
        TextComponentString c = new TextComponentString(text);
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            p.sendMessage(c);
        }
        server.sendMessage(new TextComponentString(TextFormatting.getTextWithoutFormattingCodes(text)));
    }

    // Lowest priority without cancelled events: chat a mute or a chat plugin stopped is not relayed.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent e) {
        add("chat", e.getUsername(), e.getMessage());
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        add("join", e.player.getName(), null);
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        add("leave", e.player.getName(), null);
    }
}
