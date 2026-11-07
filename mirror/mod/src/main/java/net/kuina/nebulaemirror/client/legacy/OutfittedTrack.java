package net.kuina.nebulaemirror.client.legacy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.kuina.nebulaemirror.client.MeshBakedModel;
import net.kuina.nebulaemirror.legacy.OutfittedTrackKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Railcraft 12 outfitted track: the mesh Railcraft's {@code OutfittedTrackModel} bakes for this TileEntity's rail
 * type, kit, shape and kit state, emitted into the chunk mesh in place of the static (default-kit) model.
 *
 * <p>The meshes come from the dumper ({@code extras/railcraft_tracks.json}) via the generator, which writes
 * {@code assets/nebulaemirror/legacy/railcraft_tracks.json} (key -> {@code nebulaemirror:mesh/<hash>}); the model
 * loading plugin registers those meshes as extra models and installs the table with {@link #setTable}. The key is
 * computed at emit time from the current block state's {@code shape} (falling back to the 1.12 meta), so a mirror
 * edit of the shape is followed. A key the table does not know (old generator output, unknown kit) draws the
 * static model as before.
 */
public final class OutfittedTrack implements LegacyRender {
    public static final String TABLE = "legacy/railcraft_tracks.json";
    private static volatile Map<String, ResourceLocation> table = Map.of();
    private static final AtomicInteger DRAWN = new AtomicInteger();
    private static final AtomicInteger UNMATCHED = new AtomicInteger();

    private final CompoundTag te;
    private final int legacyMeta;

    OutfittedTrack(CompoundTag te, int legacyMeta) {
        this.te = te.copy();
        this.legacyMeta = legacyMeta;
    }

    /** Parses the generator table: {"variants": {key: mesh id}}. */
    public static Map<String, ResourceLocation> parseTable(JsonObject o) {
        Map<String, ResourceLocation> out = new HashMap<>();
        JsonObject variants = o.getAsJsonObject("variants");
        if (variants != null) {
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getValue().getAsString());
                if (id != null) {
                    out.put(e.getKey(), id);
                }
            }
        }
        return Map.copyOf(out);
    }

    public static void setTable(Map<String, ResourceLocation> t) {
        table = Map.copyOf(t);
    }

    public static Map<String, ResourceLocation> table() {
        return table;
    }

    static String stats() {
        return table.size() + " track variants (" + DRAWN.get() + " drawn, " + UNMATCHED.get() + " unmatched)";
    }

    /** 1.12 shape name: the mirror state's {@code shape} property, else the 1.12 meta. */
    static String shape(BlockState state, int legacyMeta) {
        for (Property<?> p : state.getProperties()) {
            if ("shape".equals(p.getName())) {
                return name(p, state);
            }
        }
        return OutfittedTrackKey.shapeFromMeta(legacyMeta);
    }

    private static <T extends Comparable<T>> String name(Property<T> p, BlockState state) {
        return p.getName(state.getValue(p));
    }

    @Override
    public boolean emit(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter) {
        String key = OutfittedTrackKey.key(te, shape(state, legacyMeta));
        ResourceLocation id = key == null ? null : table.get(key);
        BakedModel model = id == null ? null : Minecraft.getInstance().getModelManager().getModel(id);
        if (!(model instanceof MeshBakedModel mesh)) {
            UNMATCHED.incrementAndGet();
            return false;
        }
        mesh.mesh().outputTo(emitter);
        DRAWN.incrementAndGet();
        return true;
    }
}
