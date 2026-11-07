package net.kuina.nebulaemirror.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * -Dnebulaemirror.selftest=true: once resources have loaded, check that every mirror block state and item got a
 * mesh model (not the missing model), write run/nebulaemirror-selftest.json, render the icon sheet, and quit.
 */
final class SelfTest {
    private static boolean done;

    private SelfTest() {
    }

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done || mc.getOverlay() != null) {
                return;
            }
            done = true;
            try {
                run(mc);
            } catch (Throwable t) {
                NebulaeMirror.LOG.error("Self-test crashed", t);
            }
            mc.stop();
        });
    }

    private static void run(Minecraft mc) throws IOException {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int states = 0, meshStates = 0, items = 0, meshItems = 0, quads = 0, emptyStates = 0, legacyWrapped = 0;
        JsonArray missingStates = new JsonArray();
        JsonArray missingItems = new JsonArray();
        for (Map.Entry<Block, MirrorData.BlockDef> e : NebulaeMirror.BLOCKS.entrySet()) {
            for (BlockState s : e.getKey().getStateDefinition().getPossibleStates()) {
                states++;
                BakedModel m = mc.getBlockRenderer().getBlockModel(s);
                // Peel wrappers: ours (LegacyModel) and other mods' (Continuity wraps every block model).
                while (m instanceof net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel fm) {
                    if (fm instanceof net.kuina.nebulaemirror.client.legacy.LegacyModel) {
                        legacyWrapped++;
                    }
                    m = fm.getWrappedModel();
                }
                if (m instanceof MeshBakedModel mm) {
                    meshStates++;
                    int n = mm.quadCount();
                    quads += n;
                    if (n == 0) {
                        emptyStates++;
                    }
                } else if (m == missing && missingStates.size() < 200) {
                    missingStates.add(s.toString());
                }
            }
            items++;
            BakedModel im = mc.getItemRenderer().getModel(new ItemStack(e.getKey()), null, null, 0);
            if (im instanceof MeshBakedModel) {
                meshItems++;
            } else if (missingItems.size() < 200) {
                missingItems.add(BuiltInRegistries.ITEM.getKey(e.getKey().asItem()).toString());
            }
        }
        // Railcraft outfitted-track kit meshes (extra models, not reachable through any block state).
        int trackVariants = 0, trackBaked = 0;
        for (var id : net.kuina.nebulaemirror.client.legacy.OutfittedTrack.table().values()) {
            trackVariants++;
            if (mc.getModelManager().getModel(id) instanceof MeshBakedModel) {
                trackBaked++;
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("blocks", NebulaeMirror.BLOCKS.size());
        out.addProperty("trackVariants", trackVariants);
        out.addProperty("trackVariantsBaked", trackBaked);
        out.addProperty("states", states);
        out.addProperty("statesWithMesh", meshStates);
        out.addProperty("statesWithEmptyMesh", emptyStates);
        out.addProperty("statesWithLegacyRenderer", legacyWrapped);
        out.addProperty("quads", quads);
        out.addProperty("items", items);
        out.addProperty("itemsWithMesh", meshItems);
        out.add("missingStates", missingStates);
        out.add("missingItems", missingItems);
        out.addProperty("iconSheet", IconSheet.render(mc, mc.gameDirectory.toPath().resolve("nebulaemirror-icons")));
        Path file = mc.gameDirectory.toPath().resolve("nebulaemirror-selftest.json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
        NebulaeMirror.LOG.info("Self-test: {}/{} states and {}/{} items have mesh models -> {}",
                meshStates, states, meshItems, items, file);
    }
}
