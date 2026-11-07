package net.kuina.nebulaemirror.dumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.model.WeightedBakedModel;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Biomes;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.IRegistryDelegate;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Output layout (schema 1), rooted at {@code <gameDir>/mirrordump/<timestamp>/}:
 * <pre>
 * manifest.json                    versions, mod list, options, summary
 * blocks/index.json                every block: name, file, state count, flags
 * blocks/&lt;ns&gt;/&lt;path&gt;.json        properties, meta table, per-state shape/light/layers/model refs
 * models/&lt;h0h1&gt;/&lt;sha1&gt;.json       baked quads of one (state, layer), content-addressed
 * textures/&lt;ns&gt;/&lt;path&gt;.png(.mcmeta) original sprite sources
 * sprites.json                     per sprite: size, frames, copy status
 * lang/&lt;ns&gt;/&lt;lang&gt;.lang           en_us / zh_cn of every namespace that has blocks
 * biomes.json                      biome colours (for tinting)
 * extras/railcraft_tracks.json     Railcraft outfitted track models per rail type / kit / shape / kit state
 * errors.json                      everything that threw, per block/state/stage
 * </pre>
 */
final class Dumper {
    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private static final EnumFacing[] SIDES_AND_NULL = {null, EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH,
            EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

    private final DumpOptions options;
    private final FakeWorld world = new FakeWorld();
    private final JsonArray errors = new JsonArray();
    private final Map<Material, String> materialNames = materialNames();
    private Map<IRegistryDelegate<Block>, IBlockColor> colorHandlers;
    private ModelStore models;
    private TextureExporter textures;
    private int blockCount;
    private int stateCount;
    private int truncatedBlocks;

    Dumper(DumpOptions options) {
        this.options = options;
    }

    String summary() {
        return blockCount + " blocks, " + stateCount + " states, " + (models == null ? 0 : models.size())
                + " models, " + (textures == null ? 0 : textures.copied + textures.fromAtlas) + " textures, " + errors.size() + " errors";
    }

    File run() throws IOException {
        long start = System.currentTimeMillis();
        File out = new File(Loader.instance().getConfigDir().getParentFile(),
                "mirrordump/" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        out.mkdirs();
        MirrorDumper.LOG.info("Dumping to {}", out);

        models = new ModelStore(new File(out, "models"));
        textures = new TextureExporter(new File(out, "textures"), options.vanillaTextures);
        colorHandlers = colorHandlers(Minecraft.getMinecraft().getBlockColors());

        List<Block> blocks = new ArrayList<>(ForgeRegistries.BLOCKS.getValuesCollection());
        blocks.sort(Comparator.comparing(b -> b.getRegistryName().toString()));
        JsonArray index = new JsonArray();
        Set<String> namespaces = new TreeSet<>();
        for (Block block : blocks) {
            ResourceLocation name = block.getRegistryName();
            namespaces.add(name.getResourceDomain());
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name.toString());
            try {
                JsonObject dump = dumpBlock(block);
                String rel = "blocks/" + name.getResourceDomain() + "/" + name.getResourcePath() + ".json";
                Json.write(new File(out, rel), dump, true);
                entry.addProperty("file", rel);
                entry.addProperty("states", dump.get("stateCount").getAsInt());
                entry.addProperty("models", dump.get("modelsDumped").getAsBoolean());
                if (dump.has("truncated")) {
                    entry.addProperty("truncated", true);
                }
                blockCount++;
            } catch (Throwable t) {
                error(name.toString(), null, "block", t);
                entry.addProperty("failed", true);
            }
            index.add(entry);
        }
        Json.write(new File(out, "blocks/index.json"), index, true);

        int trackVariants = exportTrackKits(out);

        Json.write(new File(out, "sprites.json"), textures.export(models.sprites), true);
        exportLang(out, namespaces);
        Json.write(new File(out, "biomes.json"), dumpBiomes(), true);
        Json.write(new File(out, "errors.json"), errors, true);

        JsonObject manifest = manifest();
        JsonObject summary = new JsonObject();
        summary.addProperty("blocks", blockCount);
        summary.addProperty("states", stateCount);
        summary.addProperty("truncatedBlocks", truncatedBlocks);
        summary.addProperty("models", models.size());
        summary.addProperty("quads", models.quadCount);
        summary.addProperty("sprites", models.sprites.size());
        summary.addProperty("texturesCopied", textures.copied);
        summary.addProperty("texturesFromAtlas", textures.fromAtlas);
        summary.addProperty("texturesVanillaSkipped", textures.skipped);
        summary.add("texturesMissing", textures.missing);
        summary.addProperty("trackKitVariants", trackVariants);
        summary.addProperty("errors", errors.size());
        summary.addProperty("seconds", (System.currentTimeMillis() - start) / 1000.0);
        manifest.add("summary", summary);
        Json.write(new File(out, "manifest.json"), manifest, true);
        MirrorDumper.LOG.info("Dump finished: {}", summary());
        return out;
    }

    // ------------------------------------------------------------------ blocks

    private JsonObject dumpBlock(Block block) {
        String name = block.getRegistryName().toString();
        boolean full = options.fullDump(block.getRegistryName().getResourceDomain());
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.add("classes", classChain(block.getClass()));
        o.add("interfaces", interfaces(block.getClass()));
        o.addProperty("translationKey", safeString(block::getUnlocalizedName));
        o.addProperty("offsetType", safeString(() -> block.getOffsetType().name()));

        Collection<IProperty<?>> props = block.getBlockState().getProperties();
        JsonArray propArray = new JsonArray();
        for (IProperty<?> p : props) {
            propArray.add(property(p));
        }
        o.add("properties", propArray);
        o.add("defaultState", stateProps(block.getDefaultState()));

        // Meta table: what the world file can actually hold.
        JsonArray metaTable = new JsonArray();
        List<IBlockState> metaStates = new ArrayList<>();
        Set<String> storedProps = new LinkedHashSet<>();
        IBlockState first = null;
        for (int meta = 0; meta < 16; meta++) {
            JsonObject m = new JsonObject();
            m.addProperty("meta", meta);
            try {
                IBlockState s = block.getStateFromMeta(meta);
                int roundTrip = block.getMetaFromState(s);
                m.add("state", stateProps(s));
                m.addProperty("roundTrip", roundTrip);
                if (roundTrip == meta) {
                    metaStates.add(s);
                    if (first == null) {
                        first = s;
                    } else {
                        for (IProperty<?> p : props) {
                            if (!first.getValue(p).equals(s.getValue(p))) {
                                storedProps.add(p.getName());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // Normal for unused metas (e.g. anvil 12..15): getStateFromMeta builds an illegal value.
                m.addProperty("invalid", t.toString());
            }
            metaTable.add(m);
        }
        o.add("metaTable", metaTable);
        JsonArray stored = new JsonArray();
        JsonArray derived = new JsonArray();
        for (IProperty<?> p : props) {
            (storedProps.contains(p.getName()) ? stored : derived).add(p.getName());
        }
        o.add("storedProperties", stored);
        o.add("derivedProperties", derived);

        // Items (creative subtypes) for naming and tabs.
        o.add("items", items(block));

        // States: meta-reachable first, then everything else the container allows.
        LinkedHashSet<IBlockState> ordered = new LinkedHashSet<>(metaStates);
        ordered.addAll(block.getBlockState().getValidStates());
        int total = ordered.size();
        o.addProperty("stateCount", total);
        if (total > options.maxStates) {
            o.addProperty("truncated", true);
            truncatedBlocks++;
        }

        BlockModelShapes shapes = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes();
        Map<IBlockState, ModelResourceLocation> variants = null;
        if (full) {
            try {
                variants = shapes.getBlockStateMapper().getVariants(block);
            } catch (Throwable t) {
                error(name, null, "variants", t);
            }
        }
        o.addProperty("modelsDumped", full);
        IBlockColor colorHandler = colorHandlers == null ? null : colorHandlers.get(block.delegate);
        if (colorHandler != null) {
            o.addProperty("colorHandler", colorHandler.getClass().getName());
        }

        JsonArray states = new JsonArray();
        int i = 0;
        for (IBlockState state : ordered) {
            if (i++ >= options.maxStates) {
                break;
            }
            try {
                states.add(dumpState(block, state, full, shapes, variants, colorHandler != null));
            } catch (Throwable t) {
                error(name, state, "state", t);
            }
            stateCount++;
        }
        o.add("states", states);
        return o;
    }

    private JsonObject dumpState(Block block, IBlockState state, boolean full, BlockModelShapes shapes,
                                 Map<IBlockState, ModelResourceLocation> variants, boolean hasColor) {
        String name = block.getRegistryName().toString();
        world.center = state;
        world.biome = Biomes.PLAINS;
        JsonObject s = new JsonObject();
        s.add("props", stateProps(state));
        try {
            s.addProperty("meta", block.getMetaFromState(state));
        } catch (Throwable t) {
            error(name, state, "meta", t);
        }
        // Item damage this state drops as; maps creative subtypes (item meta) back to block states.
        tryAdd(name, state, "damageDropped", () -> s.addProperty("drop", block.damageDropped(state)));
        // Pick-block damage is what most mods (and MCreator code) keep consistent with their item subtypes.
        // World/player are null here, so it only works for blocks that don't need them; failures are expected.
        try {
            net.minecraft.item.ItemStack pick = block.getPickBlock(state, null, null, FakeWorld.POS, null);
            if (pick != null && !pick.isEmpty() && pick.getItem() == Item.getItemFromBlock(block)) {
                s.addProperty("pick", pick.getMetadata());
            }
        } catch (Throwable ignored) {
            // needs a real world or player
        }
        tryAdd(name, state, "material", () -> s.addProperty("material", materialNames.getOrDefault(state.getMaterial(), "?")));
        tryAdd(name, state, "light", () -> s.addProperty("light", state.getLightValue(world, FakeWorld.POS)));
        tryAdd(name, state, "opacity", () -> s.addProperty("opacity", state.getLightOpacity(world, FakeWorld.POS)));
        tryAdd(name, state, "flags", () -> {
            s.addProperty("fullCube", state.isFullCube());
            s.addProperty("opaqueCube", state.isOpaqueCube());
            s.addProperty("fullBlock", state.isFullBlock());
            s.addProperty("translucent", state.isTranslucent());
            s.addProperty("renderType", state.getRenderType().name());
        });
        tryAdd(name, state, "hasTileEntity", () -> s.addProperty("hasTileEntity", block.hasTileEntity(state)));
        tryAdd(name, state, "bounds", () -> s.add("bounds", Json.box(state.getBoundingBox(world, FakeWorld.POS))));
        tryAdd(name, state, "collision", () -> {
            AxisAlignedBB c = state.getCollisionBoundingBox(world, FakeWorld.POS);
            if (c == null) {
                s.addProperty("noCollision", true);
            } else {
                s.add("collision", Json.box(c));
            }
        });
        JsonArray layers = new JsonArray();
        for (BlockRenderLayer layer : LAYERS) {
            try {
                if (block.canRenderInLayer(state, layer)) {
                    layers.add(layer.name());
                }
            } catch (Throwable t) {
                error(name, state, "layer " + layer, t);
            }
        }
        s.add("layers", layers);

        if (full) {
            Set<Integer> tints = new TreeSet<>();
            s.add("model", dumpModel(block, state, shapes, variants, layers, tints));
            if (hasColor && !tints.isEmpty()) {
                s.add("colors", colors(name, state, tints));
            }
        }
        return s;
    }

    private JsonObject dumpModel(Block block, IBlockState state, BlockModelShapes shapes,
                                 Map<IBlockState, ModelResourceLocation> variants, JsonArray layers, Set<Integer> tints) {
        String name = block.getRegistryName().toString();
        JsonObject m = new JsonObject();
        if (variants != null && variants.get(state) != null) {
            m.addProperty("variant", variants.get(state).toString());
        }
        IBakedModel model = shapes.getModelForState(state);
        m.addProperty("class", model.getClass().getName());
        m.addProperty("ao", model.isAmbientOcclusion(state));
        m.addProperty("builtIn", model.isBuiltInRenderer());
        if (model instanceof WeightedBakedModel) {
            m.addProperty("weighted", true);
        }
        try {
            if (model.getParticleTexture() != null) {
                m.addProperty("particle", model.getParticleTexture().getIconName());
                models.sprites.add(model.getParticleTexture().getIconName());
            }
        } catch (Throwable t) {
            error(name, state, "particle", t);
        }

        IBlockState renderState = state;
        try {
            renderState = block.getExtendedState(state, world, FakeWorld.POS);
        } catch (Throwable t) {
            error(name, state, "extendedState", t);
        }

        m.add("layers", bakeLayers(name, state, renderState, model, layers, tints));
        return m;
    }

    /** Baked quads of {@code renderState} per render layer, stored in the model store: {LAYER: hash}. */
    private JsonObject bakeLayers(String name, IBlockState state, IBlockState renderState, IBakedModel model,
                                  JsonArray layers, Set<Integer> tints) {
        JsonObject refs = new JsonObject();
        for (int i = 0; i < layers.size(); i++) {
            BlockRenderLayer layer = BlockRenderLayer.valueOf(layers.get(i).getAsString());
            ModelStore.Builder b = models.builder();
            try {
                ForgeHooksClient.setRenderLayer(layer);
                for (EnumFacing side : SIDES_AND_NULL) {
                    List<BakedQuad> quads = model.getQuads(renderState, side, 0L);
                    if (ModelStore.anyQuads(quads)) {
                        for (BakedQuad q : quads) {
                            b.add(side, q);
                        }
                    }
                }
                String hash = b.store();
                if (hash != null) {
                    refs.addProperty(layer.name(), hash);
                }
                tints.addAll(b.tints);
            } catch (Throwable t) {
                error(name, state, "quads " + layer, t);
            } finally {
                ForgeHooksClient.setRenderLayer(null);
            }
        }
        return refs;
    }

    /** Render layers a state is drawn in (exceptions count as "not in that layer"). */
    static JsonArray layersOf(Block block, IBlockState state) {
        JsonArray layers = new JsonArray();
        for (BlockRenderLayer layer : LAYERS) {
            try {
                if (block.canRenderInLayer(state, layer)) {
                    layers.add(layer.name());
                }
            } catch (Throwable ignored) {
                // reported by the regular block dump
            }
        }
        return layers;
    }

    /**
     * One TileEntity-driven appearance of a block: the quads of an explicitly built (extended) render state.
     * Same shape as a state's {@code model} object ({@code ao}, {@code particle}, {@code layers}); null if empty.
     */
    JsonObject bakeVariant(String name, IBlockState state, IBlockState renderState, IBakedModel model, JsonArray layers) {
        JsonObject refs = bakeLayers(name, state, renderState, model, layers, new TreeSet<>());
        if (refs.size() == 0) {
            return null;
        }
        JsonObject m = new JsonObject();
        m.addProperty("ao", model.isAmbientOcclusion(state));
        try {
            if (model.getParticleTexture() != null) {
                m.addProperty("particle", model.getParticleTexture().getIconName());
                models.sprites.add(model.getParticleTexture().getIconName());
            }
        } catch (Throwable t) {
            error(name, state, "particle", t);
        }
        m.add("layers", refs);
        return m;
    }

    /** TileEntity-driven model variants (see {@link TrackKitExporter}); @return the number of variants written. */
    private int exportTrackKits(File out) {
        if (!options.fullDump("railcraft")) {
            return 0;
        }
        try {
            JsonObject tracks = TrackKitExporter.export(this,
                    Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes());
            if (tracks == null) {
                return 0;
            }
            Json.write(new File(out, "extras/railcraft_tracks.json"), tracks, false);
            return tracks.get("variantCount").getAsInt();
        } catch (Throwable t) {
            error(TrackKitExporter.BLOCK, null, "trackKits", t);
            return 0;
        }
    }

    /** Colour per tint index, sampled in two biomes so biome-dependent tints can be detected. */
    private JsonObject colors(String name, IBlockState state, Set<Integer> tints) {
        BlockColors colors = Minecraft.getMinecraft().getBlockColors();
        JsonObject out = new JsonObject();
        for (int tint : tints) {
            JsonObject c = new JsonObject();
            try {
                world.biome = Biomes.PLAINS;
                int plains = colors.colorMultiplier(state, world, FakeWorld.POS, tint);
                world.biome = Biomes.SWAMPLAND;
                int swamp = colors.colorMultiplier(state, world, FakeWorld.POS, tint);
                c.addProperty("plains", plains);
                c.addProperty("swamp", swamp);
                c.addProperty("biomeDependent", plains != swamp);
            } catch (Throwable t) {
                error(name, state, "color " + tint, t);
                c.addProperty("error", t.toString());
            } finally {
                world.biome = Biomes.PLAINS;
            }
            out.add(String.valueOf(tint), c);
        }
        return out;
    }

    private JsonArray items(Block block) {
        JsonArray out = new JsonArray();
        try {
            Item item = Item.getItemFromBlock(block);
            if (item == Items.AIR) {
                return out;
            }
            CreativeTabs tab = block.getCreativeTabToDisplayOn();
            NonNullList<ItemStack> list = NonNullList.create();
            block.getSubBlocks(tab != null ? tab : CreativeTabs.SEARCH, list);
            for (ItemStack stack : list) {
                JsonObject i = new JsonObject();
                i.addProperty("meta", stack.getMetadata());
                // getSubBlocks may list other blocks' items (Railcraft lists minecraft:stone in its andesite).
                i.addProperty("item", String.valueOf(stack.getItem().getRegistryName()));
                i.addProperty("translationKey", safeString(stack::getUnlocalizedName));
                i.addProperty("displayName", safeString(stack::getDisplayName));
                if (tab != null) {
                    i.addProperty("tab", tab.getTabLabel());
                }
                out.add(i);
            }
        } catch (Throwable t) {
            error(block.getRegistryName().toString(), null, "items", t);
        }
        return out;
    }

    // ------------------------------------------------------------------ properties

    private static JsonObject property(IProperty<?> p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName());
        String type;
        if (p instanceof PropertyBool) {
            type = "bool";
        } else if (p instanceof PropertyInteger) {
            type = "int";
        } else if (p instanceof PropertyDirection) {
            type = "direction";
        } else if (p instanceof PropertyEnum) {
            type = "enum";
        } else {
            type = "other";
        }
        o.addProperty("type", type);
        o.addProperty("class", p.getClass().getName());
        o.addProperty("valueClass", p.getValueClass().getName());
        JsonArray values = new JsonArray();
        for (Comparable<?> v : p.getAllowedValues()) {
            values.add(valueName(p, v));
        }
        o.add("values", values);
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String valueName(IProperty p, Comparable v) {
        return p.getName(v);
    }

    private static JsonObject stateProps(IBlockState state) {
        JsonObject o = new JsonObject();
        for (Map.Entry<IProperty<?>, Comparable<?>> e : state.getProperties().entrySet()) {
            o.addProperty(e.getKey().getName(), valueName(e.getKey(), e.getValue()));
        }
        return o;
    }

    // ------------------------------------------------------------------ misc exports

    private void exportLang(File out, Set<String> namespaces) {
        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        String[][] langs = {{"en_us", "en_US"}, {"zh_cn", "zh_CN"}};
        for (String ns : namespaces) {
            for (String[] lang : langs) {
                for (String file : lang) {
                    try (IResource res = rm.getResource(new ResourceLocation(ns, "lang/" + file + ".lang"));
                         InputStream in = res.getInputStream()) {
                        File dest = new File(out, "lang/" + ns + "/" + lang[0] + ".lang");
                        dest.getParentFile().mkdirs();
                        Files.write(dest.toPath(), IOUtils.toByteArray(in));
                        break;
                    } catch (IOException ignored) {
                        // try the other spelling / language
                    }
                }
            }
        }
    }

    private JsonObject dumpBiomes() {
        JsonObject out = new JsonObject();
        for (Biome b : ForgeRegistries.BIOMES.getValuesCollection()) {
            JsonObject o = new JsonObject();
            try {
                o.addProperty("name", b.getBiomeName());
                o.addProperty("temperature", b.getDefaultTemperature());
                o.addProperty("rainfall", b.getRainfall());
                o.addProperty("waterColor", b.getWaterColorMultiplier());
                o.addProperty("grassColor", b.getGrassColorAtPos(FakeWorld.POS));
                o.addProperty("foliageColor", b.getFoliageColorAtPos(FakeWorld.POS));
                o.addProperty("class", b.getClass().getName());
            } catch (Throwable t) {
                o.addProperty("error", t.toString());
            }
            out.add(b.getRegistryName().toString(), o);
        }
        return out;
    }

    private JsonObject manifest() {
        JsonObject m = new JsonObject();
        m.addProperty("schema", MirrorDumper.SCHEMA);
        m.addProperty("dumper", MirrorDumper.VERSION);
        m.addProperty("minecraft", Loader.MC_VERSION);
        m.addProperty("forge", net.minecraftforge.common.ForgeVersion.getVersion());
        m.addProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date()));
        m.addProperty("language", safeString(() ->
                Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode()));
        JsonObject opts = new JsonObject();
        JsonArray mods = new JsonArray();
        for (String s : new TreeSet<>(options.mods)) {
            mods.add(s);
        }
        opts.add("mods", mods);
        opts.addProperty("vanillaModels", options.vanillaModels);
        opts.addProperty("vanillaTextures", options.vanillaTextures);
        opts.addProperty("maxStates", options.maxStates);
        m.add("options", opts);
        JsonArray modList = new JsonArray();
        for (ModContainer mc : Loader.instance().getActiveModList()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", mc.getModId());
            o.addProperty("name", mc.getName());
            o.addProperty("version", mc.getVersion());
            o.addProperty("source", mc.getSource() == null ? null : mc.getSource().getName());
            modList.add(o);
        }
        m.add("mods", modList);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonArray classChain(Class<?> c) {
        JsonArray a = new JsonArray();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            a.add(k.getName());
        }
        return a;
    }

    private static JsonArray interfaces(Class<?> c) {
        Set<String> names = new TreeSet<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                names.add(i.getName());
            }
        }
        JsonArray a = new JsonArray();
        for (String n : names) {
            a.add(n);
        }
        return a;
    }

    private static Map<Material, String> materialNames() {
        Map<Material, String> out = new IdentityHashMap<>();
        for (Field f : Material.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Material.class) {
                try {
                    out.put((Material) f.get(null), f.getName());
                } catch (IllegalAccessException ignored) {
                    // public static; cannot happen
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<IRegistryDelegate<Block>, IBlockColor> colorHandlers(BlockColors colors) {
        try {
            // Forge-added field, not obfuscated.
            Field f = ReflectionHelper.findField(BlockColors.class, "blockColorMap");
            return (Map<IRegistryDelegate<Block>, IBlockColor>) f.get(colors);
        } catch (Throwable t) {
            error("*", null, "colorHandlers", t);
            return null;
        }
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private static String safeString(ThrowingSupplier s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private void tryAdd(String block, IBlockState state, String stage, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            error(block, state, stage, t);
        }
    }

    private void error(String block, IBlockState state, String stage, Throwable t) {
        JsonObject e = new JsonObject();
        e.addProperty("block", block);
        if (state != null) {
            try {
                e.add("state", stateProps(state));
            } catch (Throwable ignored) {
                // state itself is broken
            }
        }
        e.addProperty("stage", stage);
        e.addProperty("error", t.toString());
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) {
            e.addProperty("at", st[0].toString());
        }
        errors.add(e);
        if (errors.size() <= 50) {
            MirrorDumper.LOG.warn("{} [{}]: {}", block, stage, t.toString());
        }
    }
}
