package net.kuina.nebulaemirror.writeback;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 1.21 vanilla block state -> vanilla 1.12 (name, meta), built by running Minecraft's own DataFixer forward over
 * every canonical 1.12 meta (generator: vanilla_metas.json) and inverting the result:
 * <ul>
 *   <li>1.12 (id, meta) -> 1.13 state: {@code BlockStateData}, the flattening table the chunk upgrade uses;</li>
 *   <li>1.13 -> 1.21: the BLOCK_STATE fixes after the flattening (renames such as grass -> short_grass).</li>
 * </ul>
 * Lookup: among the preimages of a 1.21 block (split by its {@code half}, as 1.12 doors and double plants store
 * different things in each half), only properties that actually vary between them are compared. Everything
 * 1.12 computes itself (connections, stair shape, snowy, waterlogged) is constant there and so ignored.
 * <p>
 * 1.12 kept some of what 1.21 has in the block in its TileEntity; those families share the default-colour /
 * default-type preimage and {@link TileEntityDowngrade} writes the rest: beds (colour), banners (colour),
 * skulls (type), flower pots (plant), note blocks (note).
 */
public final class VanillaReverse {
    /** BlockStateData output is the post-flattening format; start after every 1451.x fix so none applies twice. */
    static final int FLATTENED_VERSION = 1452;

    record Preimage(Map<String, String> props, LegacyBlock legacy) {
    }

    private static final LegacyBlock FLOWER_POT = new LegacyBlock("minecraft:flower_pot", 0);
    private final Map<String, List<Preimage>> groups = new HashMap<>();
    private final Map<String, List<String>> varying = new HashMap<>();
    /** Canonical meta of the upper half of minecraft:double_plant (1.12 stores no variant there). */
    private LegacyBlock doublePlantUpper;
    final Map<String, String> collisions = new TreeMap<>();
    int preimages;

    private VanillaReverse() {
    }

    /**
     * @param ids   1.12 numeric ids of the vanilla blocks (origin level.dat)
     * @param metas vanilla_metas.json: name -> canonical meta -> 1.12 state (only the keys are used)
     */
    public static VanillaReverse build(Map<String, Integer> ids, Map<String, Map<Integer, Map<String, String>>> metas,
                                       Consumer<String> problems) {
        VanillaReverse r = new VanillaReverse();
        int to = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        for (Map.Entry<String, Map<Integer, Map<String, String>>> e : new TreeMap<>(metas).entrySet()) {
            String name = e.getKey();
            Integer id = ids.get(name);
            if (id == null || id < 0 || id > 255) {
                problems.accept(name + ": no vanilla id in level.dat");
                continue;
            }
            for (int meta : new java.util.TreeSet<>(e.getValue().keySet())) {
                BlockState s = upgrade(id << 4 | meta, to);
                if (s == null) {
                    problems.accept(name + ":" + meta + ": DataFixer gave no 1.21 state");
                    continue;
                }
                if (s.isAir() && !name.equals("minecraft:air")) {
                    continue; // 1.12 blocks with no 1.21 form (e.g. piston_extension)
                }
                r.add(s, new LegacyBlock(name, meta));
            }
        }
        for (Map.Entry<String, List<Preimage>> g : r.groups.entrySet()) {
            List<Preimage> list = g.getValue();
            List<String> keys = new ArrayList<>();
            for (String prop : list.get(0).props().keySet()) {
                String first = list.get(0).props().get(prop);
                if (list.stream().anyMatch(p -> !first.equals(p.props().get(prop)))) {
                    keys.add(prop);
                }
            }
            r.varying.put(g.getKey(), keys);
        }
        return r;
    }

    static BlockState upgrade(int idMeta, int toVersion) {
        Dynamic<?> flat = BlockStateData.getTag(idMeta);
        Tag tag = flat.convert(NbtOps.INSTANCE).getValue();
        if (tag instanceof CompoundTag c && c.getString("Name").equals("%%FILTER_ME%%")) {
            // Skulls: the flattening leaves them to the chunk upgrade, which reads the TileEntity. Take the
            // skeleton variant (the default family member); the TileEntity carries type and rotation.
            String facing = c.getCompound("Properties").getString("facing");
            if (facing.equals("up") || facing.equals("down")) {
                return Blocks.SKELETON_SKULL.defaultBlockState();
            }
            return Blocks.SKELETON_WALL_SKULL.defaultBlockState().setValue(
                    net.minecraft.world.level.block.WallSkullBlock.FACING, net.minecraft.core.Direction.byName(facing));
        }
        Dynamic<Tag> out = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                new Dynamic<>(NbtOps.INSTANCE, tag), FLATTENED_VERSION, toVersion);
        if (!(out.getValue() instanceof CompoundTag c)) {
            return null;
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), c);
    }

    private void add(BlockState s, LegacyBlock legacy) {
        Map<String, String> props = props(s);
        if (legacy.name().equals("minecraft:double_plant") && "upper".equals(props.get("half"))) {
            // DataFixer turns a lone upper half into a peony; every upper half writes back as this meta.
            if (doublePlantUpper == null) {
                doublePlantUpper = legacy;
            }
            return;
        }
        String key = groupKey(s.getBlock(), props.getOrDefault("half", ""));
        List<Preimage> list = groups.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            Preimage p = list.get(i);
            if (p.props().equals(props)) {
                // Two 1.12 states 1.21 cannot tell apart (leaves' check_decay, flowing/still liquids, ...):
                // the lower penalty wins, then the first meta.
                collisions.merge(s.toString(), legacy.toString(), (a, b) -> a + ", " + b);
                if (penalty(s, legacy) < penalty(s, p.legacy())) {
                    list.set(i, new Preimage(props, legacy));
                }
                return;
            }
        }
        list.add(new Preimage(props, legacy));
        preimages++;
    }

    /**
     * Prefer still liquids for sources and flowing ones otherwise, floor skulls facing up, and the comparator block whose name matches
     * its powered state.
     */
    private static int penalty(BlockState s, LegacyBlock legacy) {
        int p = 0;
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL)
                && (legacy.name().endsWith("water") || legacy.name().endsWith("lava"))) {
            // A source is the still block; anything else is flowing (still blocks with a level never settle).
            boolean source = s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL) == 0;
            if (legacy.name().startsWith("minecraft:flowing_") == source) {
                p++;
            }
        }
        if (legacy.name().equals("minecraft:skull") && (legacy.meta() & 7) == 0) {
            p++; // floor skulls are placed facing up (meta 1); "down" is never used
        }
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                && legacy.name().endsWith("_comparator")) {
            boolean powered = s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
            if (legacy.name().startsWith("minecraft:powered_") != powered) {
                p++;
            }
        }
        return p;
    }

    private static String groupKey(Block block, String half) {
        return BuiltInRegistries.BLOCK.getKey(block) + "|" + half;
    }

    static Map<String, String> props(BlockState s) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Property<?> p : s.getProperties()) {
            out.put(p.getName(), value(s, p));
        }
        return out;
    }

    private static <T extends Comparable<T>> String value(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /**
     * The 1.12 block for a 1.21 vanilla state, or null if 1.12 has none. TileEntity-carried data (bed colour,
     * banner colour, skull type, potted plant) is not part of the answer; see {@link TileEntityDowngrade}.
     */
    public LegacyBlock reverse(BlockState s) {
        Block b = s.getBlock();
        Map<String, String> props = props(s);
        Block family = family(b);
        if (b instanceof FlowerPotBlock) {
            // One 1.12 block; the plant lives in the TileEntity (DataFixer maps a pot without one to cactus).
            String potted = BuiltInRegistries.BLOCK.getKey(b).getPath();
            return TileEntityDowngrade.POTTED.containsKey(potted) ? FLOWER_POT : null;
        }
        if (b instanceof DoublePlantBlock && "upper".equals(props.get("half"))) {
            // 1.12 derives the upper half's variant from the lower half; its meta only says "upper".
            return doublePlantUpper != null && reverseLower(b) ? doublePlantUpper : null;
        }
        return lookup(family, props);
    }

    private boolean reverseLower(Block b) {
        return groups.containsKey(groupKey(b, "lower"));
    }

    private LegacyBlock lookup(Block block, Map<String, String> props) {
        String key = groupKey(block, props.getOrDefault("half", ""));
        List<Preimage> list = groups.get(key);
        if (list == null) {
            return null;
        }
        List<String> keys = varying.get(key);
        for (Preimage p : list) {
            boolean match = true;
            for (String k : keys) {
                if (!p.props().get(k).equals(props.get(k))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return p.legacy();
            }
        }
        return null;
    }

    /** Blocks whose 1.21 variants were one 1.12 block plus TileEntity data map onto that block's default variant. */
    static Block family(Block b) {
        if (b instanceof BedBlock) {
            return Blocks.RED_BED;
        }
        if (b instanceof WallBannerBlock) {
            return Blocks.WHITE_WALL_BANNER;
        }
        if (b instanceof AbstractBannerBlock) {
            return Blocks.WHITE_BANNER;
        }
        if (b instanceof WallSkullBlock) {
            return Blocks.SKELETON_WALL_SKULL;
        }
        if (b instanceof AbstractSkullBlock) {
            return Blocks.SKELETON_SKULL;
        }
        return b;
    }

    public int size() {
        return preimages;
    }
}
