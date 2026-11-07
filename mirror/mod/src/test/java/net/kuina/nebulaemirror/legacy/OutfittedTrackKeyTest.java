package net.kuina.nebulaemirror.legacy;

import com.google.gson.JsonParser;
import net.kuina.nebulaemirror.client.legacy.OutfittedTrack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TileEntity NBT below is copied from the 1.12 world (work/world12-full); expected states follow Railcraft 12.1. */
class OutfittedTrackKeyTest {
    private static CompoundTag te(String id, String rail, String kit) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("rail", rail);
        t.putString("kit", kit);
        return t;
    }

    private static CompoundTag switchTe(String rail, String kit, boolean mirrored, boolean switched) {
        CompoundTag t = te("railcraft:track.outfitted.ticking", rail, kit);
        t.putBoolean("Direction", mirrored);
        t.putBoolean("Switched", switched);
        t.putByte("sprung", (byte) 0);
        t.putByte("locked", (byte) 0);
        return t;
    }

    @Test
    void turnoutCombinesSwitchedReversedMirrored() {
        // (-497, 67, -234), meta 9: Direction 1, Reversed 1, Switched 0
        CompoundTag t = switchTe("railcraft_reinforced", "railcraft_turnout", true, false);
        t.putBoolean("Reversed", true);
        String shape = OutfittedTrackKey.shapeFromMeta(9);
        assertEquals("east_west", shape);
        assertEquals("railcraft_reinforced|railcraft_turnout|east_west|6", OutfittedTrackKey.key(t, shape));

        // (-419, 80, -6564), meta 8: Switched 1, not locked -> visually switched
        CompoundTag s = switchTe("railcraft_reinforced", "railcraft_turnout", false, true);
        s.putBoolean("Reversed", false);
        assertEquals("railcraft_reinforced|railcraft_turnout|north_south|1",
                OutfittedTrackKey.key(s, OutfittedTrackKey.shapeFromMeta(8)));
        // A locked switch shows unswitched whatever the switch device says.
        s.putByte("locked", (byte) 3);
        assertEquals(0, OutfittedTrackKey.renderState("railcraft_turnout", s, "north_south"));
        // Sprung counts as switched.
        CompoundTag sprung = switchTe("railcraft_iron", "railcraft_turnout", false, false);
        sprung.putByte("sprung", (byte) 5);
        assertEquals(1, OutfittedTrackKey.renderState("railcraft_turnout", sprung, "north_south"));
    }

    @Test
    void wyeDependsOnRailShape() {
        // (-489, 68, -359), meta 8: iron wye, Direction 1, Switched 0
        CompoundTag t = switchTe("railcraft_iron", "railcraft_wye", true, false);
        assertEquals("railcraft_iron|railcraft_wye|north_south|3", OutfittedTrackKey.key(t, "north_south"));
        assertEquals(1, OutfittedTrackKey.renderState("railcraft_wye", t, "east_west"));
        CompoundTag plain = switchTe("railcraft_reinforced", "railcraft_wye", false, false);
        assertEquals(0, OutfittedTrackKey.renderState("railcraft_wye", plain, "north_south"));
        assertEquals(2, OutfittedTrackKey.renderState("railcraft_wye", plain, "east_west"));
    }

    @Test
    void poweredAndReversibleKits() {
        // (-3057, 65, 599), meta 2: powered high-speed booster on a slope
        CompoundTag booster = te("railcraft:track.outfitted", "railcraft_high_speed", "railcraft_booster");
        booster.putBoolean("powered", true);
        assertEquals("railcraft_high_speed|railcraft_booster|ascending_east|1",
                OutfittedTrackKey.key(booster, OutfittedTrackKey.shapeFromMeta(2)));
        // (-227, 65, -7882), meta 0: buffer stop, direction 1
        CompoundTag buffer = te("railcraft:track.outfitted", "railcraft_iron", "railcraft_buffer");
        buffer.putBoolean("direction", true);
        assertEquals("railcraft_iron|railcraft_buffer|north_south|1", OutfittedTrackKey.key(buffer, "north_south"));
        // (-3011, 102, 599), meta 1: powered + reversed transition -> 1 + 2
        CompoundTag transition = te("railcraft:track.outfitted", "railcraft_high_speed", "railcraft_transition");
        transition.putBoolean("powered", true);
        transition.putBoolean("reversed", true);
        assertEquals(3, OutfittedTrackKey.renderState("railcraft_transition", transition, "east_west"));
        // control: powered XOR reversed
        CompoundTag control = te("railcraft:track.outfitted", "railcraft_iron", "railcraft_control");
        control.putBoolean("powered", true);
        control.putBoolean("reversed", true);
        assertEquals(0, OutfittedTrackKey.renderState("railcraft_control", control, "north_south"));
        // one way: powered +1, direction +2
        CompoundTag oneWay = te("railcraft:track.outfitted", "railcraft_electric", "railcraft_one_way");
        oneWay.putBoolean("powered", true);
        oneWay.putBoolean("direction", true);
        assertEquals(3, OutfittedTrackKey.renderState("railcraft_one_way", oneWay, "east_west"));
        assertEquals(0, OutfittedTrackKey.renderState("railcraft_junction",
                te("railcraft:track.outfitted", "railcraft_reinforced", "railcraft_junction"), "east_west"));
    }

    @Test
    void defaultsAndNonTracks() {
        CompoundTag bare = new CompoundTag();
        bare.putString("id", "railcraft:track.outfitted");
        assertEquals("railcraft_iron|railcraft_missing|north_south|0", OutfittedTrackKey.key(bare, "north_south"));
        CompoundTag other = te("nebulaecraft:roadmark_text", "railcraft_iron", "railcraft_buffer");
        assertNull(OutfittedTrackKey.key(other, "north_south"));
        assertNull(OutfittedTrackKey.shapeFromMeta(6));
        assertEquals("ascending_south", OutfittedTrackKey.shapeFromMeta(8 | 5));
    }

    @Test
    void generatorTableResolvesKeys() {
        String json = "{\"schema\":1,\"block\":\"railcraft:track_outfitted\",\"variants\":{"
                + "\"railcraft_iron|railcraft_buffer|north_south|1\":\"nebulaemirror:mesh/ab12\","
                + "\"railcraft_reinforced|railcraft_turnout|east_west|6\":\"nebulaemirror:mesh/cd34\"}}";
        Map<String, ResourceLocation> table = OutfittedTrack.parseTable(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(2, table.size());
        CompoundTag buffer = te("railcraft:track.outfitted", "railcraft_iron", "railcraft_buffer");
        buffer.putBoolean("direction", true);
        assertEquals(ResourceLocation.parse("nebulaemirror:mesh/ab12"),
                table.get(OutfittedTrackKey.key(buffer, "north_south")));
    }
}
