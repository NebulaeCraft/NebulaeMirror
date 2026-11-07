package net.kuina.nebulaemirror.dumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Railcraft 12 outfitted tracks ({@code railcraft:track_outfitted}) take their look from the TileEntity: the rail
 * type ({@code rail}), the kit ({@code kit}) and the kit's render state, fed to {@code OutfittedTrackModel} as the
 * unlisted properties {@code track_type} / {@code track_kit} / {@code state}. The block dump only sees the default
 * (iron rail, missing kit). This bakes the real composite model for every rail type x kit x shape x render state,
 * exactly as the 1.12 client draws it with the track goggles aura off (so kits that Railcraft hides on
 * electric / high-speed / reinforced rails stay hidden).
 *
 * <p>Railcraft is reached by reflection only, so the dumper has no compile dependency on it and silently skips this
 * when Railcraft is absent. Railcraft's own method names survive in its production jar, except those that implement
 * Minecraft methods: {@code TrackType/TrackKit.getName()} implement {@code IStringSerializable.getName()} and are
 * reobfuscated to {@code func_176610_l}, so they are called through that interface (our reobf maps the call), never
 * looked up by name. The dev client remaps mods back to MCP names, which hides this; only a production client
 * shows it.
 *
 * <p>Output {@code extras/railcraft_tracks.json}:
 * <pre>
 * { "block": "railcraft:track_outfitted",
 *   "types": ["railcraft_iron", ...],
 *   "kits": {"railcraft_turnout": {"renderer": "UNIFIED", "states": 8, "slopes": false}, ...},
 *   "variants": {"&lt;rail&gt;|&lt;kit&gt;|&lt;shape&gt;|&lt;state&gt;": {"ao", "particle", "layers": {LAYER: model hash}}} }
 * </pre>
 * Variant keys use the TileEntity's own NBT names ({@code TrackType/TrackKit.getName()}) and the 1.12 shape name.
 */
final class TrackKitExporter {
    static final String BLOCK = "railcraft:track_outfitted";
    private static final String API = "mods.railcraft.api.tracks.";

    private TrackKitExporter() {
    }

    /** @return null if Railcraft (or its outfitted track block) is not loaded. */
    static JsonObject export(Dumper dumper, BlockModelShapes shapes) throws ReflectiveOperationException {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(BLOCK));
        if (block == null || !(block.getBlockState() instanceof ExtendedBlockState)) {
            return null;
        }
        Class<?> registryClass;
        try {
            registryClass = Class.forName(API + "TrackRegistry");
        } catch (ClassNotFoundException e) {
            return null;
        }
        Class<?> kitClass = Class.forName(API + "TrackKit");
        Method kitRenderer = kitClass.getMethod("getRenderer");
        Method kitStates = kitClass.getMethod("getRenderStates");
        Method kitSlopes = kitClass.getMethod("isAllowedOnSlopes");
        Iterable<?> types = (Iterable<?>) registryClass.getField("TRACK_TYPE").get(null);
        Iterable<?> kits = (Iterable<?>) registryClass.getField("TRACK_KIT").get(null);

        ExtendedBlockState container = (ExtendedBlockState) block.getBlockState();
        IUnlistedProperty<?> typeProp = unlisted(container, "track_type");
        IUnlistedProperty<?> kitProp = unlisted(container, "track_kit");
        IUnlistedProperty<?> stateProp = unlisted(container, "state");
        IProperty<?> shapeProp = container.getProperty("shape");
        IProperty<?> tickingProp = container.getProperty("ticking");
        if (typeProp == null || kitProp == null || stateProp == null || shapeProp == null) {
            throw new IllegalStateException("unexpected track_outfitted state container: " + container);
        }

        // One base state per shape (ticking only picks the TileEntity class, the model ignores it).
        List<IBlockState> bases = new ArrayList<>();
        for (IBlockState s : container.getValidStates()) {
            if (tickingProp == null || Boolean.FALSE.equals(s.getValue(tickingProp))) {
                bases.add(s);
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("block", BLOCK);
        JsonArray typeNames = new JsonArray();
        for (Object t : types) {
            typeNames.add(name(t));
        }
        out.add("types", typeNames);
        JsonObject kitInfo = new JsonObject();
        for (Object k : kits) {
            JsonObject o = new JsonObject();
            o.addProperty("renderer", String.valueOf(kitRenderer.invoke(k)));
            o.addProperty("states", (Integer) kitStates.invoke(k));
            o.addProperty("slopes", (Boolean) kitSlopes.invoke(k));
            kitInfo.add(name(k), o);
        }
        out.add("kits", kitInfo);

        JsonObject variants = new JsonObject();
        int count = 0;
        for (IBlockState base : bases) {
            String shape = Dumper.valueName(shapeProp, base.getValue(shapeProp));
            JsonArray layers = Dumper.layersOf(block, base);
            IBakedModel model = shapes.getModelForState(base);
            for (Object type : types) {
                for (Object kit : kits) {
                    int states = Math.max(1, (Integer) kitStates.invoke(kit));
                    for (int st = 0; st < states; st++) {
                        String key = name(type) + "|" + name(kit) + "|" + shape + "|" + st;
                        IBlockState extended = with(with(with((IExtendedBlockState) base, typeProp, type),
                                kitProp, kit), stateProp, st);
                        JsonObject m = dumper.bakeVariant(BLOCK + " " + key, base, extended, model, layers);
                        if (m != null) {
                            variants.add(key, m);
                            count++;
                        }
                    }
                }
            }
        }
        out.add("variants", variants);
        out.addProperty("variantCount", count);
        return out;
    }

    /** TrackType/TrackKit NBT name ({@code getName()}, i.e. registry name with ':' and '.' replaced by '_'). */
    private static String name(Object entry) {
        return ((IStringSerializable) entry).getName();
    }

    private static IUnlistedProperty<?> unlisted(ExtendedBlockState container, String name) {
        for (IUnlistedProperty<?> p : container.getUnlistedProperties()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IExtendedBlockState with(IExtendedBlockState state, IUnlistedProperty prop, Object value) {
        return state.withProperty(prop, value);
    }
}
