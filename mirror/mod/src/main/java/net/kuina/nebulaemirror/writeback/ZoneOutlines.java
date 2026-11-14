package net.kuina.nebulaemirror.writeback;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells clients where the checked-out zones are, so they can draw their boundaries
 * ({@code client.ZoneOutlineRenderer}). The whole list is sent when a player joins and again whenever a zone
 * becomes checked out or stops being so (submitted or abandoned). Each player's list says which zones are
 * their own, so the client can colour the others differently. Zones are always in the overworld.
 * Clients without the channel (mod older than 1.7) are sent nothing.
 */
public final class ZoneOutlines {
    /** At most this many zones are sent; more than that is not a sensible number of open checkouts. */
    static final int MAX_ZONES = 4096;

    private ZoneOutlines() {
    }

    /** Inclusive block coordinates, as in zone.json. {@code own}: checked out by the player receiving it. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean own) {
    }

    public record Payload(List<Box> zones) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(NebulaeMirror.id("zones"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(Payload::write, Payload::read);

        private static void write(FriendlyByteBuf buf, Payload p) {
            buf.writeVarInt(p.zones.size());
            for (Box b : p.zones) {
                buf.writeInt(b.minX).writeInt(b.minY).writeInt(b.minZ);
                buf.writeInt(b.maxX).writeInt(b.maxY).writeInt(b.maxZ);
                buf.writeBoolean(b.own);
            }
        }

        private static Payload read(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ZONES) {
                throw new IllegalArgumentException("zone count " + n);
            }
            List<Box> zones = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                zones.add(new Box(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                        buf.readBoolean()));
            }
            return new Payload(List.copyOf(zones));
        }

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
    }

    /** Sends the current zones to everyone; called by {@link ZoneManager} whenever the set of zones changes. */
    static void broadcast(MinecraftServer server) {
        server.execute(() -> server.getPlayerList().getPlayers().forEach(ZoneOutlines::send));
    }

    /** The zones as {@code player} sees them. A zone checked out from the console is nobody's own. */
    private static Payload zonesFor(ServerPlayer player) {
        ZoneManager m = ZoneManager.get();
        String uuid = player.getStringUUID();
        List<Box> boxes = new ArrayList<>();
        if (m != null) {
            for (ZoneManager.Zone z : m.zones()) {
                if (boxes.size() == MAX_ZONES) {
                    break;
                }
                boxes.add(new Box(z.min[0], z.min[1], z.min[2], z.max[0], z.max[1], z.max[2], uuid.equals(z.ownerUuid)));
            }
        }
        return new Payload(boxes);
    }

    private static void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Payload.TYPE)) {
            ServerPlayNetworking.send(player, zonesFor(player));
        }
    }
}
