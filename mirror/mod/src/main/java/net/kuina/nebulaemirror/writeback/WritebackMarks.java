package net.kuina.nebulaemirror.writeback;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.importer.LegacyLevel;
import net.kuina.nebulaemirror.sync.SyncService;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which item stacks place blocks the origin cannot store, so that the client can colour their inventory slots
 * ({@code client.SlotMarks}). The verdict is the write-back's own: {@link ReverseTable#reverse} of every state
 * the stack can place, i.e. what {@code zone lint} would report as {@code no_1_12_form}.
 * <p>
 * The client builds the same reverse table from the tables in the mod jar; the one thing it lacks is the
 * origin's numeric ids of the vanilla 1.12 blocks (level.dat), which the server sends when a player joins.
 * Without a sync {@code source} there is no level.dat, nothing is sent and no slot is marked.
 * Clients without the channel (mod older than 1.8) are sent nothing.
 */
public final class WritebackMarks {
    /** More vanilla 1.12 blocks than there are (ids 0..255). */
    static final int MAX_IDS = 4096;

    /** What the origin can store of the states a stack can place: all of them, some, or none. */
    public enum Mark {
        OK, PARTIAL, NONE
    }

    private static volatile Payload current;

    private WritebackMarks() {
    }

    /** Vanilla 1.12 block name -> the origin's numeric id. */
    public record Payload(Map<String, Integer> vanillaIds) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(NebulaeMirror.id("legacy_ids"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(Payload::write, Payload::read);

        private static void write(FriendlyByteBuf buf, Payload p) {
            buf.writeVarInt(p.vanillaIds.size());
            p.vanillaIds.forEach((name, id) -> {
                buf.writeUtf(name);
                buf.writeVarInt(id);
            });
        }

        private static Payload read(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_IDS) {
                throw new IllegalArgumentException("id count " + n);
            }
            Map<String, Integer> ids = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                String name = buf.readUtf();
                ids.put(name, buf.readVarInt());
            }
            return new Payload(Map.copyOf(ids));
        }

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.CODEC);
        // After SyncService's own SERVER_STARTED listener (registered earlier), which reads the config.
        ServerLifecycleEvents.SERVER_STARTED.register(WritebackMarks::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> current = null);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
    }

    /** Reads the ids off-thread, then sends them to whoever is already online. */
    private static void load(MinecraftServer server) {
        current = null;
        SyncService sync = SyncService.get();
        String source = sync == null ? "" : sync.config().source;
        if (source == null || source.isBlank()) {
            NebulaeMirror.LOG.info("Write-back marks: off (no sync \"source\", so no origin level.dat)");
            return;
        }
        Path levelDat = Path.of(source).resolve("level.dat");
        Util.backgroundExecutor().execute(() -> {
            Payload p;
            try {
                p = new Payload(Map.copyOf(ReverseTable.vanillaIds(new LegacyLevel(levelDat))));
            } catch (Exception e) {
                NebulaeMirror.LOG.warn("Write-back marks: off, cannot read {}: {}", levelDat, e.toString());
                return;
            }
            server.execute(() -> {
                if (!server.isRunning()) {
                    return;
                }
                current = p;
                NebulaeMirror.LOG.info("Write-back marks: {} vanilla 1.12 ids from {}", p.vanillaIds().size(), levelDat);
                server.getPlayerList().getPlayers().forEach(WritebackMarks::send);
            });
        });
    }

    private static void send(ServerPlayer player) {
        Payload p = current;
        if (p != null && ServerPlayNetworking.canSend(player, Payload.TYPE)) {
            ServerPlayNetworking.send(player, p);
        }
    }

    /**
     * Item stack -> {@link Mark}. A stack places the states of its item's block (and of the wall variant: torches,
     * signs, banners, heads) that agree with its {@code block_state} component; the mirror's own items carry
     * one, as one mirror block holds several 1.12 variants and has states no 1.12 meta stands for.
     * <p>
     * Left out, as placing the item never gives them: states whose {@link #WORLD_DRIVEN} properties are not at
     * their default (a bed's foot half is never occupied, FutureMC's hive is empty or full), and the other
     * blocks an item is merely the drop of (the filled cauldrons).
     * <p>
     * Verdicts are computed on first use and kept. Not thread-safe: use from one thread (the client's).
     */
    public static final class Index {
        /** Properties only the world changes after placement, where 1.12 stores fewer values than 1.21. */
        static final Set<String> WORLD_DRIVEN = Set.of("occupied", "honey_level");

        private record Key(Item item, BlockItemStateProperties state) {
        }

        private final ReverseTable table;
        private final HolderLookup.Provider registries;
        private final Map<Item, List<Block>> blocks = new IdentityHashMap<>();
        private final Map<BlockState, Boolean> writable = new IdentityHashMap<>();
        private final Map<Item, Mark> plain = new IdentityHashMap<>();
        private final Map<Key, Mark> withState = new HashMap<>();

        public Index(ReverseTable table, HolderLookup.Provider registries) {
            this.table = table;
            this.registries = registries;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof BlockItem blockItem) {
                    List<Block> placed = new ArrayList<>(2);
                    placed.add(blockItem.getBlock());
                    blocks.put(item, placed);
                }
            }
            // The wall variant is only in BY_BLOCK (StandingAndWallBlockItem keeps it to itself).
            for (Block block : BuiltInRegistries.BLOCK) {
                Item item = Item.BY_BLOCK.get(block);
                if (item instanceof StandingAndWallBlockItem && !blocks.get(item).contains(block)) {
                    blocks.get(item).add(block);
                }
            }
        }

        /** {@link Mark#OK} for anything that places no block. */
        public Mark mark(ItemStack stack) {
            Item item = stack.getItem();
            List<Block> placed = blocks.get(item);
            if (placed == null) {
                return Mark.OK;
            }
            BlockItemStateProperties state = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
            if (state.isEmpty()) {
                Mark m = plain.get(item);
                if (m == null) {
                    plain.put(item, m = classify(placed, state));
                }
                return m;
            }
            Key key = new Key(item, state);
            Mark m = withState.get(key);
            if (m == null) {
                withState.put(key, m = classify(placed, state));
            }
            return m;
        }

        private Mark classify(List<Block> placed, BlockItemStateProperties component) {
            boolean ok = false, bad = false;
            for (Block block : placed) {
                for (BlockState s : block.getStateDefinition().getPossibleStates()) {
                    if (!agrees(s, component) || !asPlaced(s)) {
                        continue;
                    }
                    if (writable(s)) {
                        ok = true;
                    } else {
                        bad = true;
                    }
                    if (ok && bad) {
                        return Mark.PARTIAL;
                    }
                }
            }
            return bad ? Mark.NONE : Mark.OK;
        }

        /** As {@link BlockItemStateProperties#apply}: a property the block lacks, or a value it lacks, is ignored. */
        private static boolean agrees(BlockState s, BlockItemStateProperties component) {
            for (Map.Entry<String, String> e : component.properties().entrySet()) {
                Property<?> p = s.getBlock().getStateDefinition().getProperty(e.getKey());
                if (p != null && p.getValue(e.getValue()).isPresent() && !name(s, p).equals(e.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean asPlaced(BlockState s) {
            BlockState def = s.getBlock().defaultBlockState();
            for (Property<?> p : s.getProperties()) {
                if (WORLD_DRIVEN.contains(p.getName()) && !s.getValue(p).equals(def.getValue(p))) {
                    return false;
                }
            }
            return true;
        }

        private static <T extends Comparable<T>> String name(BlockState s, Property<T> p) {
            return p.getName(s.getValue(p));
        }

        private boolean writable(BlockState s) {
            Boolean w = writable.get(s);
            if (w == null) {
                writable.put(s, w = table.reverse(s, null, registries).ok());
            }
            return w;
        }
    }
}
