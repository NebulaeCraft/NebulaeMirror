package net.kuina.nebulaemirror.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.writeback.ReverseTable;
import net.kuina.nebulaemirror.writeback.WritebackMarks;
import net.minecraft.Util;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Colours the slots of container screens ({@code mixin.client.AbstractContainerScreenMixin}) whose stack places
 * blocks the origin cannot store: light red when none of the states it can place can be written back, light
 * yellow when only some can ({@link WritebackMarks}). The reverse table is built in the background once the
 * server has sent the origin's vanilla ids; until then, and on servers that send none, nothing is marked.
 */
public final class SlotMarks {
    /** ARGB drawn over the slot background, under the item. */
    static final int NONE_COLOR = 0x80FF5555, PARTIAL_COLOR = 0x80FFDD55;

    private static volatile WritebackMarks.Index index;
    /** Counts joins and disconnects, so a table still being built for a server that was left is dropped. */
    private static int session;

    private SlotMarks() {
    }

    static void install() {
        ClientPlayNetworking.registerGlobalReceiver(WritebackMarks.Payload.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            ClientPacketListener connection = ctx.client().getConnection();
            if (connection == null) {
                return;
            }
            int mine = ++session;
            HolderLookup.Provider registries = connection.registryAccess();
            Map<String, Integer> ids = payload.vanillaIds();
            // Running the DataFixer over every 1.12 meta takes a moment: keep it off the render thread.
            CompletableFuture.supplyAsync(() -> {
                Map<String, String> problems = new TreeMap<>();
                ReverseTable table = ReverseTable.build(ids, p -> problems.put(p, p));
                if (!problems.isEmpty()) {
                    NebulaeMirror.LOG.warn("Slot marks: {}", problems.keySet());
                }
                return new WritebackMarks.Index(table, registries);
            }, Util.backgroundExecutor()).whenCompleteAsync((built, error) -> {
                if (error != null) {
                    NebulaeMirror.LOG.error("Slot marks: cannot build the write-back tables", error);
                } else if (mine == session) {
                    index = built;
                }
            }, ctx.client());
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            session++;
            index = null;
        }));
    }

    /** The colour to draw behind this stack's slot, or 0 for none. Render thread only. */
    public static int color(ItemStack stack) {
        WritebackMarks.Index i = index;
        if (i == null || stack.isEmpty()) {
            return 0;
        }
        return switch (i.mark(stack)) {
            case NONE -> NONE_COLOR;
            case PARTIAL -> PARTIAL_COLOR;
            case OK -> 0;
        };
    }

    /**
     * How many of these stacks get each mark, e.g. "45 red, 12 yellow, 1300 unmarked", and the first marked ones
     * that are not vanilla items: the mirror's own items should all be writable (the tour logs it).
     */
    static String summary(Iterable<ItemStack> stacks) {
        WritebackMarks.Index i = index;
        if (i == null) {
            return "no write-back table";
        }
        int red = 0, yellow = 0, none = 0;
        List<String> modded = new ArrayList<>();
        for (ItemStack s : stacks) {
            WritebackMarks.Mark m = i.mark(s);
            switch (m) {
                case NONE -> red++;
                case PARTIAL -> yellow++;
                case OK -> none++;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (m != WritebackMarks.Mark.OK && !id.getNamespace().equals("minecraft") && modded.size() < 20) {
                modded.add(id + s.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).properties()
                        .toString() + "=" + m);
            }
        }
        return red + " red, " + yellow + " yellow, " + none + " unmarked" + (modded.isEmpty() ? "" : ", modded " + modded);
    }
}
