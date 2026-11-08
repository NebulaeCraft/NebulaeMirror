package net.kuina.nebulaemirror;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.kuina.nebulaemirror.block.MirrorBlock;
import net.kuina.nebulaemirror.block.MirrorBlockHolder;
import net.kuina.nebulaemirror.block.MirrorBlockSupport;
import net.kuina.nebulaemirror.block.MirrorDoorBlock;
import net.kuina.nebulaemirror.block.MirrorEntityBlock;
import net.kuina.nebulaemirror.block.MirrorFenceBlock;
import net.kuina.nebulaemirror.block.MirrorFenceGateBlock;
import net.kuina.nebulaemirror.block.MirrorPaneBlock;
import net.kuina.nebulaemirror.block.MirrorProps;
import net.kuina.nebulaemirror.block.MirrorStairBlock;
import net.kuina.nebulaemirror.block.MirrorTrapDoorBlock;
import net.kuina.nebulaemirror.block.MirrorWallBlock;
import net.kuina.nebulaemirror.block.UnknownBlock;
import net.kuina.nebulaemirror.data.MirrorData;
import net.kuina.nebulaemirror.importer.ImportHook;
import net.kuina.nebulaemirror.sync.SyncService;
import net.kuina.nebulaemirror.writeback.ZoneManager;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.material.PushReaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NebulaeMirror implements ModInitializer {
    public static final String MOD_ID = "nebulaemirror";
    public static final Logger LOG = LoggerFactory.getLogger("NebulaeMirror");

    public static MirrorData DATA;
    /** Every block registered from blocks.json, in definition order. */
    public static final Map<Block, MirrorData.BlockDef> BLOCKS = new LinkedHashMap<>();
    public static Block UNKNOWN;
    public static BlockEntityType<LegacyBlockEntity> LEGACY_BLOCK_ENTITY;

    private static final Map<String, SoundType> SOUNDS = Map.ofEntries(
            Map.entry("stone", SoundType.STONE), Map.entry("metal", SoundType.METAL), Map.entry("wood", SoundType.WOOD),
            Map.entry("wool", SoundType.WOOL), Map.entry("glass", SoundType.GLASS), Map.entry("gravel", SoundType.GRAVEL),
            Map.entry("grass", SoundType.GRASS), Map.entry("sand", SoundType.SAND), Map.entry("vine", SoundType.VINE),
            Map.entry("anvil", SoundType.ANVIL), Map.entry("snow", SoundType.SNOW), Map.entry("cobweb", SoundType.COBWEB),
            Map.entry("coral", SoundType.CORAL_BLOCK));

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        long start = System.currentTimeMillis();
        DATA = MirrorData.load();
        List<Block> legacyHolders = new ArrayList<>();
        Map<String, Integer> archetypes = new HashMap<>();
        for (MirrorData.BlockDef def : DATA.blocks) {
            // A constructed Block already holds a registry reference; one that is never registered makes the
            // registry refuse to freeze. So reject bad definitions *before* constructing anything.
            String problem = validate(def);
            if (problem != null) {
                LOG.error("Skipping {} ({}): {}", def.id(), def.archetype(), problem);
                continue;
            }
            Block block = createBlock(def);
            ResourceLocation rl = ResourceLocation.parse(def.id());
            Registry.register(BuiltInRegistries.BLOCK, rl, block);
            Registry.register(BuiltInRegistries.ITEM, rl, new BlockItem(block, new Item.Properties()));
            BLOCKS.put(block, def);
            archetypes.merge(def.archetype(), 1, Integer::sum);
            if (block instanceof MirrorEntityBlock) {
                legacyHolders.add(block);
            }
        }

        UNKNOWN = Registry.register(BuiltInRegistries.BLOCK, id("unknown"),
                new UnknownBlock(BlockBehaviour.Properties.of().strength(-1, 3600000).noLootTable()
                        .pushReaction(PushReaction.BLOCK)));
        Registry.register(BuiltInRegistries.ITEM, id("unknown"), new BlockItem(UNKNOWN, new Item.Properties()));
        legacyHolders.add(UNKNOWN);
        LEGACY_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("legacy"),
                BlockEntityType.Builder.of(LegacyBlockEntity::new, legacyHolders.toArray(new Block[0])).build(null));

        registerTabs();
        ImportHook.register();
        SyncService.register();
        ZoneManager.register();
        MirrorCommands.register();
        LOG.info("Registered {} mirror blocks {} in {} ms", BLOCKS.size(), archetypes,
                System.currentTimeMillis() - start);
    }

    private static String validate(MirrorData.BlockDef def) {
        if (ResourceLocation.tryParse(def.id()) == null) {
            return "invalid id";
        }
        if (BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(def.id()))) {
            return "id already registered";
        }
        for (MirrorData.PropDef p : def.extraProperties()) {
            if (p.values().size() < 2) {
                return "property " + p.name() + " has fewer than 2 values";
            }
            if (!p.name().matches("[a-z0-9_]+")) {
                return "invalid property name " + p.name();
            }
            for (String v : p.values()) {
                if (!v.matches("[a-z0-9_]+")) {
                    return "invalid value " + v + " of " + p.name();
                }
            }
        }
        return null;
    }

    private static Block createBlock(MirrorData.BlockDef def) {
        MirrorBlockSupport support = new MirrorBlockSupport(def);
        BlockBehaviour.Properties props = properties(def, support);
        List<Property<?>> extras = MirrorProps.createAll(def.extraProperties());
        Block block;
        MirrorProps.PENDING.set(extras);
        try {
            block = switch (def.archetype()) {
                case "stairs" -> new MirrorStairBlock(Blocks.STONE.defaultBlockState(), props, support);
                case "door" -> new MirrorDoorBlock(setType(def), props, support);
                case "trapdoor" -> new MirrorTrapDoorBlock(setType(def), props, support);
                case "fence_gate" -> new MirrorFenceGateBlock(WoodType.OAK, props, support);
                case "wall" -> new MirrorWallBlock(props, support);
                case "fence" -> new MirrorFenceBlock(props, support);
                case "pane" -> new MirrorPaneBlock(props, support);
                default -> def.legacyBlockEntity() ? new MirrorEntityBlock(props, support) : new MirrorBlock(props, support);
            };
        } finally {
            MirrorProps.PENDING.remove();
        }
        // Default state: the archetype's own defaults plus the generator's default for every keyed property.
        try {
            BlockState state = MirrorProps.apply(block.defaultBlockState(), def.defaultState());
            ((MirrorBlockHolder) block).mirrorSetDefault(state);
            support.index(block, DATA.shapes);
        } catch (RuntimeException e) {
            // The block exists now and must be registered regardless; it just keeps vanilla defaults.
            LOG.error("Incomplete setup of {}: {}", def.id(), e.toString());
        }
        return block;
    }

    private static BlockBehaviour.Properties properties(MirrorData.BlockDef def, MirrorBlockSupport support) {
        boolean anyTransparent = def.states().values().stream().anyMatch(s -> !s.opaque());
        boolean anyLight = def.states().values().stream().anyMatch(s -> s.light() > 0);
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of()
                .strength(1.5f, 6.0f)
                .sound(SOUNDS.getOrDefault(def.sound(), SoundType.STONE));
        if (anyLight) {
            // Evaluated while the state definition is being built, so key the lookup off the state's own values.
            p.lightLevel(state -> {
                MirrorData.StateInfo si = def.states().get(MirrorProps.key(state, support.keyed));
                return si == null ? 0 : si.light();
            });
        }
        if (anyTransparent) {
            p.noOcclusion().isViewBlocking((s, l, pos) -> false).isSuffocating((s, l, pos) -> false)
                    .isRedstoneConductor((s, l, pos) -> false);
        }
        if ("XZ".equals(def.offset())) {
            p.offsetType(BlockBehaviour.OffsetType.XZ);
        } else if ("XYZ".equals(def.offset())) {
            p.offsetType(BlockBehaviour.OffsetType.XYZ);
        }
        return p;
    }

    private static BlockSetType setType(MirrorData.BlockDef def) {
        return "IRON".equals(def.material()) ? BlockSetType.IRON : BlockSetType.OAK;
    }

    /** Creative tabs: one per source mod; one stack per 1.12 item subtype, carrying the state it places. */
    private static void registerTabs() {
        Map<String, List<ItemStack>> byNamespace = new LinkedHashMap<>();
        for (Map.Entry<Block, MirrorData.BlockDef> e : BLOCKS.entrySet()) {
            Block block = e.getKey();
            MirrorData.BlockDef def = e.getValue();
            List<ItemStack> stacks = byNamespace.computeIfAbsent(def.namespace(), k -> new ArrayList<>());
            Set<String> seen = new HashSet<>();
            boolean added = false;
            for (MirrorData.ItemDef it : def.items()) {
                if (it.state() == null || !seen.add(MirrorData.variantKey(it.state()))) {
                    continue;
                }
                MirrorData.StateInfo si = def.states().get(MirrorData.variantKey(it.state()));
                if (si != null && !si.writable()) {
                    continue;
                }
                ItemStack stack = new ItemStack(block);
                stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(it.state()));
                if (def.items().size() > 1) {
                    stack.set(DataComponents.ITEM_NAME, Component.translatable(it.key()));
                }
                stacks.add(stack);
                added = true;
            }
            if (!added) {
                stacks.add(new ItemStack(block));
            }
        }
        for (Map.Entry<String, List<ItemStack>> e : byNamespace.entrySet()) {
            List<ItemStack> stacks = e.getValue();
            String ns = e.getKey();
            CreativeModeTab tab = FabricItemGroup.builder()
                    .title(Component.literal("Mirror: " + ns.substring(0, 1).toUpperCase(Locale.ROOT) + ns.substring(1)))
                    .icon(() -> stacks.get(0).copy())
                    .displayItems((params, output) -> stacks.forEach(s -> output.accept(s.copy())))
                    .build();
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("tab_" + ns), tab);
        }
    }

}
