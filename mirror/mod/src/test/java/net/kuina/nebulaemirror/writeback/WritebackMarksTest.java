package net.kuina.nebulaemirror.writeback;

import io.netty.buffer.Unpooled;
import net.kuina.nebulaemirror.importer.LegacyLevel;
import net.kuina.nebulaemirror.writeback.WritebackMarks.Mark;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Needs the generator output (work/gen, bundled into the test classpath) and a 1.12 level.dat
 * (work/import-test/world12); the marks are skipped without them. Only vanilla blocks are registered here, so
 * this covers vanilla items and the FutureMC/Quark override targets.
 */
class WritebackMarksTest {
    static WritebackMarks.Index index;

    @BeforeAll
    static void setUp() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!Files.isRegularFile(VanillaReverseTest.METAS) || !Files.isRegularFile(VanillaReverseTest.LEVEL)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        ReverseTable table = ReverseTable.build(ReverseTable.vanillaIds(new LegacyLevel(VanillaReverseTest.LEVEL)),
                problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
        index = new WritebackMarks.Index(table, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private static Mark mark(Item item) {
        assumeTrue(index != null, "needs work/gen and work/import-test");
        return index.mark(new ItemStack(item));
    }

    private static Mark mark(Item item, Map<String, String> state) {
        assumeTrue(index != null, "needs work/gen and work/import-test");
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(state));
        return index.mark(stack);
    }

    @Test
    void payloadRoundTrip() {
        WritebackMarks.Payload sent = new WritebackMarks.Payload(Map.of("minecraft:stone", 1,
                "minecraft:double_stone_slab", 43, "minecraft:structure_block", 255));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WritebackMarks.Payload.CODEC.encode(buf, sent);
        assertEquals(sent, WritebackMarks.Payload.CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
        buf.writeVarInt(WritebackMarks.MAX_IDS + 1);
        assertThrows(IllegalArgumentException.class, () -> WritebackMarks.Payload.CODEC.decode(buf));
    }

    @Test
    void blocksTheOriginHas() {
        // Vanilla 1.12 blocks, a 1.12 state that became a block (double_stone_slab:8), a Quark and a FutureMC
        // override target, and items whose wall variant or crop is the block.
        for (Item item : List.of(Items.STONE, Items.SMOOTH_STONE, Items.ANDESITE_STAIRS, Items.BARREL, Items.TORCH,
                Items.OAK_SIGN, Items.WHITE_BANNER, Items.PLAYER_HEAD, Items.WHEAT_SEEDS, Items.REDSTONE)) {
            assertEquals(Mark.OK, mark(item), BuiltInRegistries.ITEM.getKey(item).toString());
        }
    }

    @Test
    void itemsThatPlaceNoBlock() {
        assertEquals(Mark.OK, mark(Items.DIAMOND_SWORD));
        assertEquals(Mark.OK, mark(Items.WATER_BUCKET));
        assertEquals(Mark.OK, mark(Items.AIR));
    }

    @Test
    void blocksTheOriginLacks() {
        // Newer than 1.12, refused block entities (command block), a skull type 1.12 lacks, and 1.21 blocks
        // whose 1.12 namesake became something else (pumpkin: carved; purple shulker box: the undyed one).
        for (Item item : List.of(Items.CHERRY_PLANKS, Items.DEEPSLATE, Items.COMMAND_BLOCK, Items.STRUCTURE_BLOCK,
                Items.PIGLIN_HEAD, Items.CHERRY_SIGN, Items.PUMPKIN, Items.STONE_SLAB, Items.PURPLE_SHULKER_BOX)) {
            assertEquals(Mark.NONE, mark(item), BuiltInRegistries.ITEM.getKey(item).toString());
        }
    }

    @Test
    void blocksTheOriginHasInPart() {
        // 1.12 floor and ceiling levers point two ways, its buttons one; its mushroom blocks have 13 face sets.
        for (Item item : List.of(Items.LEVER, Items.STONE_BUTTON, Items.OAK_BUTTON, Items.RED_MUSHROOM_BLOCK)) {
            assertEquals(Mark.PARTIAL, mark(item), BuiltInRegistries.ITEM.getKey(item).toString());
        }
    }

    @Test
    void worldDrivenStatesDoNotCount() {
        // 1.12 has no occupied foot half and FutureMC's hive is empty or full, but nothing places those; the
        // cauldron item is not the lava cauldron.
        for (Item item : List.of(Items.RED_BED, Items.WHITE_BED, Items.BEEHIVE, Items.CAULDRON)) {
            assertEquals(Mark.OK, mark(item), BuiltInRegistries.ITEM.getKey(item).toString());
        }
    }

    @Test
    void blockStateComponentNarrowsTheStates() {
        assertEquals(Mark.OK, mark(Items.LEVER, Map.of("face", "wall")));
        assertEquals(Mark.PARTIAL, mark(Items.LEVER, Map.of("face", "floor")));
        assertEquals(Mark.OK, mark(Items.LEVER, Map.of("face", "floor", "facing", "north")));
        assertEquals(Mark.NONE, mark(Items.LEVER, Map.of("face", "floor", "facing", "south")));
        assertEquals(Mark.NONE, mark(Items.STONE_BUTTON, Map.of("face", "ceiling", "facing", "west")));
        // A property the block lacks and a value the property lacks are ignored, as when the item is placed.
        assertEquals(Mark.PARTIAL, mark(Items.LEVER, Map.of("variation", "3", "face", "sideways")));
        assertEquals(Mark.OK, mark(Items.STONE, Map.of("variation", "3")));
        assertEquals(Mark.NONE, mark(Items.CHERRY_PLANKS, Map.of("variation", "3")));
    }
}
