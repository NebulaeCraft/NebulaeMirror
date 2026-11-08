package net.kuina.nebulaemirror.writeback;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1.21 block entity data -> the 1.12 TileEntity for a vanilla block (without x/y/z; the patch adds them).
 * Covers the decorative and container blocks a build server has: signs (front text), beds, banners, skulls
 * (incl. player heads with skins), flower pots, note blocks, chests, furnaces, dispensers/droppers, hoppers,
 * brewing stands, shulker boxes, jukeboxes, spawners (entity type only) and the data-less ones. Anything lossy
 * adds a warning; command and structure blocks are refused.
 */
public final class TileEntityDowngrade {
    /** Thrown for block entities whose data must not be written back approximately. */
    public static final class Unsupported extends Exception {
        public Unsupported(String message) {
            super(message);
        }
    }

    /** 1.21 potted block -> 1.12 flower pot {Item, Data} (inverse of DataFixer's FLOWER_POT_MAP). */
    static final Map<String, Object[]> POTTED = Map.ofEntries(
            Map.entry("flower_pot", new Object[]{"minecraft:air", 0}),
            Map.entry("potted_poppy", new Object[]{"minecraft:red_flower", 0}),
            Map.entry("potted_blue_orchid", new Object[]{"minecraft:red_flower", 1}),
            Map.entry("potted_allium", new Object[]{"minecraft:red_flower", 2}),
            Map.entry("potted_azure_bluet", new Object[]{"minecraft:red_flower", 3}),
            Map.entry("potted_red_tulip", new Object[]{"minecraft:red_flower", 4}),
            Map.entry("potted_orange_tulip", new Object[]{"minecraft:red_flower", 5}),
            Map.entry("potted_white_tulip", new Object[]{"minecraft:red_flower", 6}),
            Map.entry("potted_pink_tulip", new Object[]{"minecraft:red_flower", 7}),
            Map.entry("potted_oxeye_daisy", new Object[]{"minecraft:red_flower", 8}),
            Map.entry("potted_dandelion", new Object[]{"minecraft:yellow_flower", 0}),
            Map.entry("potted_oak_sapling", new Object[]{"minecraft:sapling", 0}),
            Map.entry("potted_spruce_sapling", new Object[]{"minecraft:sapling", 1}),
            Map.entry("potted_birch_sapling", new Object[]{"minecraft:sapling", 2}),
            Map.entry("potted_jungle_sapling", new Object[]{"minecraft:sapling", 3}),
            Map.entry("potted_acacia_sapling", new Object[]{"minecraft:sapling", 4}),
            Map.entry("potted_dark_oak_sapling", new Object[]{"minecraft:sapling", 5}),
            Map.entry("potted_red_mushroom", new Object[]{"minecraft:red_mushroom", 0}),
            Map.entry("potted_brown_mushroom", new Object[]{"minecraft:brown_mushroom", 0}),
            Map.entry("potted_dead_bush", new Object[]{"minecraft:deadbush", 0}),
            Map.entry("potted_fern", new Object[]{"minecraft:tallgrass", 2}),
            Map.entry("potted_cactus", new Object[]{"minecraft:cactus", 0}));

    /** 1.21 banner pattern id -> 1.12 pattern code (inverse of DataFixer's BannerPatternFormatFix). */
    static final Map<String, String> PATTERNS = new HashMap<>();

    static {
        String[] p = {"b", "base", "bl", "square_bottom_left", "br", "square_bottom_right", "tl", "square_top_left",
                "tr", "square_top_right", "bs", "stripe_bottom", "ts", "stripe_top", "ls", "stripe_left",
                "rs", "stripe_right", "cs", "stripe_center", "ms", "stripe_middle", "drs", "stripe_downright",
                "dls", "stripe_downleft", "ss", "small_stripes", "cr", "cross", "sc", "straight_cross",
                "bt", "triangle_bottom", "tt", "triangle_top", "bts", "triangles_bottom", "tts", "triangles_top",
                "ld", "diagonal_left", "rd", "diagonal_up_right", "lud", "diagonal_up_left", "rud", "diagonal_right",
                "mc", "circle", "mr", "rhombus", "vh", "half_vertical", "hh", "half_horizontal",
                "vhr", "half_vertical_right", "hhb", "half_horizontal_bottom", "bo", "border", "cbo", "curly_border",
                "gra", "gradient", "gru", "gradient_up", "bri", "bricks", "cre", "creeper", "sku", "skull",
                "flo", "flower", "moj", "mojang"};
        for (int i = 0; i < p.length; i += 2) {
            PATTERNS.put("minecraft:" + p[i + 1], p[i]);
        }
    }

    /** SkullBlock types 1.12 has, by its SkullType number. */
    static final Map<SkullBlock.Type, Integer> SKULLS = Map.of(SkullBlock.Types.SKELETON, 0,
            SkullBlock.Types.WITHER_SKELETON, 1, SkullBlock.Types.ZOMBIE, 2, SkullBlock.Types.PLAYER, 3,
            SkullBlock.Types.CREEPER, 4, SkullBlock.Types.DRAGON, 5);

    private static final Set<String> ITEM_CONTAINERS = Set.of("minecraft:dispenser", "minecraft:dropper",
            "minecraft:hopper", "minecraft:brewing_stand");
    private static final Set<String> NO_DATA = Set.of("minecraft:enchanting_table", "minecraft:beacon",
            "minecraft:daylight_detector", "minecraft:daylight_detector_inverted", "minecraft:end_portal",
            "minecraft:end_gateway", "minecraft:ender_chest");
    private static final Set<String> REFUSED = Set.of("minecraft:command_block", "minecraft:repeating_command_block",
            "minecraft:chain_command_block", "minecraft:structure_block");

    private TileEntityDowngrade() {
    }

    /**
     * @param legacy the 1.12 block this state writes back as (from the reverse table)
     * @param be     the 1.21 block entity data ({@code saveWithoutMetadata}), null if there is none
     * @return the 1.12 TileEntity, or null when the 1.12 block has none
     */
    @Nullable
    public static CompoundTag downgrade(BlockState state, @Nullable CompoundTag be, LegacyBlock legacy,
                                        ItemDowngrade items, HolderLookup.Provider registries, List<String> warnings)
            throws Unsupported {
        String n = legacy.name();
        CompoundTag data = be == null ? new CompoundTag() : be;
        if (REFUSED.contains(n)) {
            throw new Unsupported(n + " 不会回写（命令方块/结构方块）");
        }
        CompoundTag te = new CompoundTag();
        switch (n) {
            case "minecraft:bed" -> {
                te.putString("id", "minecraft:bed");
                te.putInt("color", ((BedBlock) state.getBlock()).getColor().getId());
            }
            case "minecraft:standing_banner", "minecraft:wall_banner" -> banner(state, data, te, registries, warnings);
            case "minecraft:skull" -> skull(state, data, te);
            case "minecraft:flower_pot" -> {
                String potted = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                Object[] p = POTTED.get(potted);
                if (p == null || !(state.getBlock() instanceof FlowerPotBlock)) {
                    throw new Unsupported(potted + " 在 1.12 花盆中没有对应内容");
                }
                te.putString("id", "minecraft:flower_pot");
                te.putString("Item", (String) p[0]);
                te.putInt("Data", (Integer) p[1]);
            }
            case "minecraft:noteblock" -> {
                te.putString("id", "minecraft:noteblock");
                te.putByte("note", (byte) (int) state.getValue(NoteBlock.NOTE));
                te.putBoolean("powered", state.getValue(NoteBlock.POWERED));
            }
            case "minecraft:standing_sign", "minecraft:wall_sign" -> sign(data, te, warnings);
            case "minecraft:chest", "minecraft:trapped_chest" -> container("minecraft:chest", data, te, items, registries, warnings);
            case "minecraft:furnace", "minecraft:lit_furnace" -> {
                container("minecraft:furnace", data, te, items, registries, warnings);
                te.putShort("BurnTime", (short) 0);
                te.putShort("CookTime", (short) 0);
                te.putShort("CookTimeTotal", (short) 200);
            }
            case "minecraft:jukebox" -> {
                te.putString("id", "minecraft:jukebox");
                if (data.contains("RecordItem", Tag.TAG_COMPOUND)) {
                    CompoundTag rec = items.downgrade(data.getCompound("RecordItem"), registries, warnings);
                    if (rec != null) {
                        te.put("RecordItem", rec);
                        te.putInt("Record", 0);
                    }
                }
            }
            case "minecraft:mob_spawner" -> spawner(data, te, warnings);
            case "minecraft:powered_comparator", "minecraft:unpowered_comparator" -> {
                te.putString("id", "minecraft:comparator");
                te.putInt("OutputSignal", data.getInt("OutputSignal"));
            }
            default -> {
                if (ITEM_CONTAINERS.contains(n)) {
                    container(n, data, te, items, registries, warnings);
                } else if (n.endsWith("_shulker_box")) {
                    container("minecraft:shulker_box", data, te, items, registries, warnings);
                } else if (NO_DATA.contains(n)) {
                    te.putString("id", n.equals("minecraft:daylight_detector_inverted") ? "minecraft:daylight_detector" : n);
                    customName(data, te, registries);
                } else {
                    return null;
                }
            }
        }
        return te;
    }

    private static void container(String id, CompoundTag data, CompoundTag te, ItemDowngrade items,
                                  HolderLookup.Provider registries, List<String> warnings) {
        te.putString("id", id);
        te.put("Items", items.downgradeItems(data.getList("Items", Tag.TAG_COMPOUND), registries, warnings));
        customName(data, te, registries);
        if (data.contains("Lock", Tag.TAG_STRING) && !data.getString("Lock").isEmpty()) {
            te.putString("Lock", data.getString("Lock"));
        }
        if (data.contains("LootTable", Tag.TAG_STRING)) {
            te.putString("LootTable", data.getString("LootTable"));
            te.putLong("LootTableSeed", data.getLong("LootTableSeed"));
        }
    }

    private static void customName(CompoundTag data, CompoundTag te, HolderLookup.Provider registries) {
        if (data.contains("CustomName", Tag.TAG_STRING)) {
            te.putString("CustomName", ItemDowngrade.plain(data.getString("CustomName"), registries));
        }
    }

    private static void sign(CompoundTag data, CompoundTag te, List<String> warnings) {
        te.putString("id", "minecraft:sign");
        CompoundTag front = data.getCompound("front_text");
        ListTag messages = front.getList("messages", Tag.TAG_STRING);
        for (int i = 0; i < 4; i++) {
            String line = i < messages.size() ? messages.getString(i) : "\"\"";
            te.putString("Text" + (i + 1), line.isEmpty() ? "\"\"" : line);
        }
        if (!"black".equals(front.getString("color")) && front.contains("color")) {
            warnings.add("告示牌文字颜色 " + front.getString("color") + " 不会保留（1.12 告示牌只有黑色）");
        }
        if (front.getBoolean("has_glowing_text")) {
            warnings.add("发光告示牌文字不会保留");
        }
        for (Tag t : data.getCompound("back_text").getList("messages", Tag.TAG_STRING)) {
            String s = t.getAsString();
            if (!s.isEmpty() && !s.equals("\"\"") && !s.equals("{\"text\":\"\"}")) {
                warnings.add("告示牌背面文字不会保留（1.12 告示牌只有一面）");
                break;
            }
        }
    }

    private static void banner(BlockState state, CompoundTag data, CompoundTag te, HolderLookup.Provider registries,
                               List<String> warnings) {
        te.putString("id", "minecraft:banner");
        DyeColor base = ((AbstractBannerBlock) state.getBlock()).getColor();
        te.putInt("Base", 15 - base.getId()); // 1.12 banners count dye damage (black = 0)
        ListTag patterns = new ListTag();
        for (Tag t : data.getList("patterns", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            String code = PATTERNS.get(p.getString("pattern"));
            DyeColor c = DyeColor.byName(p.getString("color"), null);
            if (code == null || c == null) {
                warnings.add("旗帜图案 " + p.getString("pattern") + " 在 1.12 中没有对应形式，已略去");
                continue;
            }
            CompoundTag old = new CompoundTag();
            old.putString("Pattern", code);
            old.putInt("Color", 15 - c.getId());
            patterns.add(old);
        }
        te.put("Patterns", patterns);
        customName(data, te, registries);
    }

    private static void skull(BlockState state, CompoundTag data, CompoundTag te) throws Unsupported {
        SkullBlock.Type type = ((AbstractSkullBlock) state.getBlock()).getType();
        Integer n = SKULLS.get(type);
        if (n == null) {
            throw new Unsupported("1.12 中没有 " + type + " 头颅");
        }
        te.putString("id", "minecraft:skull");
        te.putByte("SkullType", (byte) (int) n);
        te.putByte("Rot", (byte) (state.hasProperty(BlockStateProperties.ROTATION_16)
                ? state.getValue(BlockStateProperties.ROTATION_16) : 0));
        CompoundTag profile = data.getCompound("profile");
        if (!profile.isEmpty()) {
            CompoundTag owner = new CompoundTag();
            if (profile.contains("id", Tag.TAG_INT_ARRAY)) {
                UUID id = UUIDUtil.uuidFromIntArray(profile.getIntArray("id"));
                owner.putString("Id", id.toString());
            }
            if (profile.contains("name", Tag.TAG_STRING)) {
                owner.putString("Name", profile.getString("name"));
            }
            CompoundTag props = new CompoundTag();
            for (Tag t : profile.getList("properties", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) t;
                CompoundTag v = new CompoundTag();
                v.putString("Value", p.getString("value"));
                if (p.contains("signature")) {
                    v.putString("Signature", p.getString("signature"));
                }
                ListTag list = props.getList(p.getString("name"), Tag.TAG_COMPOUND);
                list.add(v);
                props.put(p.getString("name"), list);
            }
            if (!props.isEmpty()) {
                owner.put("Properties", props);
            }
            te.put("Owner", owner);
        }
    }

    private static void spawner(CompoundTag data, CompoundTag te, List<String> warnings) {
        te.putString("id", "minecraft:mob_spawner");
        for (String k : new String[]{"Delay", "MinSpawnDelay", "MaxSpawnDelay", "SpawnCount", "MaxNearbyEntities",
                "RequiredPlayerRange", "SpawnRange"}) {
            if (data.contains(k)) {
                te.putShort(k, data.getShort(k));
            }
        }
        String entity = data.getCompound("SpawnData").getCompound("entity").getString("id");
        if (!entity.isEmpty()) {
            CompoundTag spawn = new CompoundTag();
            spawn.putString("id", entity);
            te.put("SpawnData", spawn);
        }
        if (data.contains("SpawnPotentials")) {
            warnings.add("刷怪笼的 SpawnPotentials 不会保留");
        }
    }
}
