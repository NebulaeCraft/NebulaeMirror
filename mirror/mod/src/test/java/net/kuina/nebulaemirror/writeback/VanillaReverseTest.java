package net.kuina.nebulaemirror.writeback;

import com.google.gson.JsonParser;
import net.kuina.nebulaemirror.importer.LegacyLevel;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Needs the generator output (work/gen) and a 1.12 level.dat (work/import-test/world12); skipped without them.
 */
class VanillaReverseTest {
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final Path METAS = ROOT.resolve("work/gen/mirror/vanilla_metas.json");
    static final Path LEVEL = ROOT.resolve("work/import-test/world12/level.dat");
    static VanillaReverse vanilla;
    static ItemDowngrade items;
    static Map<String, Integer> ids = new HashMap<>();
    static Map<String, Map<Integer, Map<String, String>>> metas;
    static HolderLookup.Provider registries;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(METAS) && Files.isRegularFile(LEVEL), "needs work/gen and work/import-test");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        new LegacyLevel(LEVEL).blocks.forEach((id, name) -> {
            if (name.startsWith("minecraft:")) {
                ids.put(name, id);
            }
        });
        metas = ReverseTable.parseVanillaMetas(JsonParser.parseString(Files.readString(METAS)).getAsJsonObject());
        List<String> problems = new ArrayList<>();
        vanilla = VanillaReverse.build(ids, metas, problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
        items = ItemDowngrade.build();
    }

    @Test
    void everyCanonicalMetaRoundTrips() {
        int to = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int checked = 0;
        Map<String, String> wrong = new TreeMap<>();
        for (var e : new TreeMap<>(metas).entrySet()) {
            for (int meta : e.getValue().keySet()) {
                BlockState s = VanillaReverse.upgrade(ids.get(e.getKey()) << 4 | meta, to);
                if (s.isAir() && !e.getKey().equals("minecraft:air")) {
                    continue;
                }
                checked++;
                LegacyBlock back = vanilla.reverse(s);
                LegacyBlock want = new LegacyBlock(e.getKey(), meta);
                if (!want.equals(back) && !vanilla.collisions.containsKey(s.toString())) {
                    wrong.put(want.toString(), s + " -> " + back);
                }
            }
        }
        System.out.println("vanilla round trip: " + checked + " metas, " + vanilla.size() + " distinct 1.21 states, "
                + vanilla.collisions.size() + " collisions: " + vanilla.collisions);
        assertTrue(checked > 1000, "only " + checked);
        assertEquals(Map.of(), wrong, "metas that do not come back");
    }

    private static LegacyBlock rev(BlockState s) {
        return vanilla.reverse(s);
    }

    @Test
    void flatteningRenamesAndSplitBlocks() {
        assertEquals(new LegacyBlock("minecraft:grass", 0), rev(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(new LegacyBlock("minecraft:grass", 0), rev(Blocks.GRASS_BLOCK.defaultBlockState()
                .setValue(BlockStateProperties.SNOWY, true)), "snowy is derived");
        assertEquals(new LegacyBlock("minecraft:tallgrass", 1), rev(Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(new LegacyBlock("minecraft:stone", 1), rev(Blocks.GRANITE.defaultBlockState()));
        assertEquals("minecraft:wool", rev(Blocks.RED_WOOL.defaultBlockState()).name());
        assertEquals(14, rev(Blocks.RED_WOOL.defaultBlockState()).meta());
        // stairs: facing/half stored, shape derived
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.INNER_LEFT);
        assertEquals(new LegacyBlock("minecraft:oak_stairs", 5), rev(stairs));
        // fences: connections derived
        assertEquals(new LegacyBlock("minecraft:fence", 0), rev(Blocks.OAK_FENCE.defaultBlockState()
                .setValue(BlockStateProperties.NORTH, true)));
        // doors: lower half stores facing/open, upper half hinge/powered
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, true);
        assertEquals(new LegacyBlock("minecraft:wooden_door", 7), rev(door));
        assertEquals(new LegacyBlock("minecraft:wooden_door", 9), rev(door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)));
        // double plants: the upper half has no variant in 1.12
        BlockState lilac = Blocks.LILAC.defaultBlockState();
        assertEquals(new LegacyBlock("minecraft:double_plant", 1), rev(lilac));
        assertEquals(new LegacyBlock("minecraft:double_plant", 10), rev(lilac.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
        // lit furnace is its own 1.12 block
        assertEquals("minecraft:lit_furnace", rev(Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.LIT, true)).name());
        // liquids: a source is the still block, anything else flows
        assertEquals(new LegacyBlock("minecraft:water", 0), rev(Blocks.WATER.defaultBlockState()));
        assertEquals(new LegacyBlock("minecraft:flowing_water", 3), rev(Blocks.WATER.defaultBlockState()
                .setValue(BlockStateProperties.LEVEL, 3)));
        assertEquals(new LegacyBlock("minecraft:lava", 0), rev(Blocks.LAVA.defaultBlockState()));
        // 1.21-only blocks have none
        assertNull(rev(Blocks.CHERRY_PLANKS.defaultBlockState()));
        assertNull(rev(Blocks.SPRUCE_SIGN.defaultBlockState()));
        assertNull(rev(Blocks.TALL_SEAGRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
    }

    @Test
    void tileEntityFamilies() throws Exception {
        List<String> warnings = new ArrayList<>();
        BlockState bed = Blocks.CYAN_BED.defaultBlockState();
        LegacyBlock b = rev(bed);
        assertEquals("minecraft:bed", b.name());
        CompoundTag te = TileEntityDowngrade.downgrade(bed, null, b, items, registries, warnings);
        assertEquals(9, te.getInt("color"));

        BlockState pot = Blocks.POTTED_FERN.defaultBlockState();
        assertEquals(new LegacyBlock("minecraft:flower_pot", 0), rev(pot));
        te = TileEntityDowngrade.downgrade(pot, null, rev(pot), items, registries, warnings);
        assertEquals("minecraft:tallgrass", te.getString("Item"));
        assertEquals(2, te.getInt("Data"));

        BlockState head = Blocks.PLAYER_HEAD.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 6);
        assertEquals(new LegacyBlock("minecraft:skull", 1), rev(head));
        CompoundTag profile = TagParser.parseTag("{profile:{name:\"Kuina\",id:[I;1,2,3,4],properties:[{name:\"textures\",value:\"abc\"}]}}");
        te = TileEntityDowngrade.downgrade(head, profile, rev(head), items, registries, warnings);
        assertEquals(3, te.getByte("SkullType"));
        assertEquals(6, te.getByte("Rot"));
        assertEquals("Kuina", te.getCompound("Owner").getString("Name"));
        assertEquals("abc", te.getCompound("Owner").getCompound("Properties").getList("textures", 10).getCompound(0).getString("Value"));
        BlockState wallSkull = Blocks.CREEPER_WALL_HEAD.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertEquals(new LegacyBlock("minecraft:skull", 5), rev(wallSkull));

        BlockState banner = Blocks.BLACK_WALL_BANNER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        assertEquals(new LegacyBlock("minecraft:wall_banner", 3), rev(banner));
        CompoundTag pattern = TagParser.parseTag("{patterns:[{pattern:\"minecraft:stripe_bottom\",color:\"red\"},{pattern:\"minecraft:globe\",color:\"white\"}]}");
        te = TileEntityDowngrade.downgrade(banner, pattern, rev(banner), items, registries, warnings);
        assertEquals(0, te.getInt("Base"), "black = dye damage 0");
        ListTag ps = te.getList("Patterns", 10);
        assertEquals(1, ps.size(), "globe is 1.14+");
        assertEquals("bs", ps.getCompound(0).getString("Pattern"));
        assertEquals(1, ps.getCompound(0).getInt("Color"), "red = dye damage 1");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("globe")));

        assertThrows(TileEntityDowngrade.Unsupported.class, () -> TileEntityDowngrade.downgrade(
                Blocks.PIGLIN_HEAD.defaultBlockState(), null, new LegacyBlock("minecraft:skull", 1), items, registries, new ArrayList<>()));
    }

    @Test
    void chestItemsAndSigns() throws Exception {
        List<String> warnings = new ArrayList<>();
        CompoundTag chest = TagParser.parseTag("{Items:[{Slot:0b,id:\"minecraft:red_wool\",count:12},"
                + "{Slot:3b,id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:damage\":40,"
                + "\"minecraft:custom_name\":'{\"text\":\"Blade\"}',\"minecraft:enchantments\":{levels:{\"minecraft:sharpness\":5}}}},"
                + "{Slot:4b,id:\"minecraft:cherry_log\",count:1},{Slot:5b,id:\"minecraft:short_grass\",count:2}]}");
        BlockState s = Blocks.CHEST.defaultBlockState();
        CompoundTag te = TileEntityDowngrade.downgrade(s, chest, rev(s), items, registries, warnings);
        assertEquals("minecraft:chest", te.getString("id"));
        ListTag it = te.getList("Items", 10);
        assertEquals(3, it.size(), "cherry log has no 1.12 form");
        assertEquals("minecraft:wool", it.getCompound(0).getString("id"));
        assertEquals(14, it.getCompound(0).getShort("Damage"));
        assertEquals(12, it.getCompound(0).getByte("Count"));
        CompoundTag sword = it.getCompound(1);
        assertEquals("minecraft:diamond_sword", sword.getString("id"));
        assertEquals(40, sword.getShort("Damage"));
        assertEquals("Blade", sword.getCompound("tag").getCompound("display").getString("Name"));
        assertEquals(16, sword.getCompound("tag").getList("ench", 10).getCompound(0).getShort("id"));
        assertEquals("minecraft:tallgrass", it.getCompound(2).getString("id"));
        assertEquals(1, it.getCompound(2).getShort("Damage"));
        assertEquals(5, it.getCompound(2).getByte("Slot"), "slot kept");

        CompoundTag sign = TagParser.parseTag("{front_text:{messages:['\"Hello\"','\"\"','{\"text\":\"x\"}','\"\"'],color:\"red\",has_glowing_text:0b},"
                + "back_text:{messages:['\"\"','\"\"','\"\"','\"\"']}}");
        BlockState wallSign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        assertEquals(new LegacyBlock("minecraft:wall_sign", 4), rev(wallSign));
        warnings.clear();
        te = TileEntityDowngrade.downgrade(wallSign, sign, rev(wallSign), items, registries, warnings);
        assertEquals("\"Hello\"", te.getString("Text1"));
        assertEquals("{\"text\":\"x\"}", te.getString("Text3"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertNotNull(items.legacy("minecraft:grass_block"));
        assertEquals(new ItemDowngrade.Legacy("minecraft:grass", 0), items.legacy("minecraft:grass_block"));
    }

    @Test
    void generatorReverseTable() throws Exception {
        ModdedReverse m = ModdedReverse.parse(JsonParser.parseString(Files.readString(METAS.resolveSibling("reverse.json"))).getAsJsonObject());
        assertTrue(m.size() > 1000);
        // Quark stairs onto vanilla andesite stairs: facing/half stored, shape derived
        BlockState s = Blocks.ANDESITE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT);
        assertEquals(new LegacyBlock("quark:stone_andesite_stairs", 1), m.reverse(s));
        // the vanilla table does not know andesite stairs (1.12 had none), so the generator's mapping applies
        assertNull(vanilla.reverse(s));
        ReverseTable t = new ReverseTable(vanilla, m, items);
        ReverseTable.Result r = t.reverse(s, null, registries);
        assertTrue(r.ok());
        assertEquals("quark:stone_andesite_stairs", r.block().name());
        // vanilla wins where 1.12 vanilla has the block
        assertEquals("minecraft:stone", t.reverse(Blocks.GRANITE.defaultBlockState(), null, registries).block().name());
        ReverseTable.Result cherry = t.reverse(Blocks.CHERRY_PLANKS.defaultBlockState(), null, registries);
        assertTrue(!cherry.ok() && cherry.error().contains("在 1.12 中没有对应方块"), String.valueOf(cherry.error()));
        assertEquals(LegacyBlock.AIR, t.reverse(Blocks.CAVE_AIR.defaultBlockState(), null, registries).block());
    }

    private static <T extends Throwable> void assertThrows(Class<T> c, org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(c, e);
    }
}
