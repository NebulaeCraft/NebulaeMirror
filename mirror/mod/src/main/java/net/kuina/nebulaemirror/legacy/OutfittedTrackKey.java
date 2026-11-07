package net.kuina.nebulaemirror.legacy;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Which baked Railcraft 12 outfitted-track model a TileEntity selects: {@code <rail>|<kit>|<shape>|<state>}, the key
 * of the generator's {@code assets/nebulaemirror/legacy/railcraft_tracks.json}.
 *
 * <p>{@code rail} / {@code kit} are the TileEntity's own NBT strings ({@code TileTrackOutfitted}); the shape is the
 * 1.12 rail shape name; the state is a port of each kit's {@code ITrackKitInstance#getRenderState()} (Railcraft
 * 12.1, {@code mods.railcraft.common.blocks.tracks.outfitted.kits}) computed from the kit's NBT fields, as the 1.12
 * server would after loading the chunk. Kits whose state depends on more than plain NBT flags (locking, detector,
 * coupler, locomotive, throttle: none in the world) fall back to state 0.
 */
public final class OutfittedTrackKey {
    public static final Set<String> TILE_ENTITIES = Set.of("railcraft:track.outfitted", "railcraft:track.outfitted.ticking");
    /** 1.12 {@code BlockRailBase.EnumRailDirection} by metadata; outfitted tracks only allow the straight ones (0..5). */
    private static final String[] SHAPES = {"north_south", "east_west", "ascending_east", "ascending_west",
            "ascending_north", "ascending_south"};

    private OutfittedTrackKey() {
    }

    /** 1.12 shape name from the block's legacy meta ({@code meta & 7}); null for non-straight values. */
    @Nullable
    public static String shapeFromMeta(int meta) {
        int i = meta & 7;
        return i < SHAPES.length ? SHAPES[i] : null;
    }

    /** @return the variant key, or null if {@code te} is not an outfitted track. */
    @Nullable
    public static String key(CompoundTag te, String shape) {
        if (!TILE_ENTITIES.contains(te.getString("id")) || shape == null) {
            return null;
        }
        String rail = te.contains("rail") ? te.getString("rail") : "railcraft_iron";
        String kit = te.contains("kit") ? te.getString("kit") : "railcraft_missing";
        return rail + "|" + kit + "|" + shape + "|" + renderState(kit, te, shape);
    }

    public static int renderState(String kit, CompoundTag te, String shape) {
        boolean powered = te.getBoolean("powered");
        return switch (kit) {
            // TrackBufferStop: "direction" = reversed
            case "railcraft_buffer" -> te.getBoolean("direction") ? 1 : 0;
            // TrackKitControl
            case "railcraft_control" -> powered ^ te.getBoolean("reversed") ? 1 : 0;
            // TrackKitSpeedTransition
            case "railcraft_transition" -> (te.getBoolean("reversed") ? 1 : 0) + (powered ? 2 : 0);
            // TrackKitOneWay: "direction" = reversed
            case "railcraft_one_way" -> (powered ? 1 : 0) + (te.getBoolean("direction") ? 2 : 0);
            // TrackKitDisembark
            case "railcraft_disembarking" -> (te.getBoolean("mirrored") ? 1 : 0) + (powered ? 2 : 0);
            // TrackKitGated: gate open = powered || open
            case "railcraft_gated" -> (te.getBoolean("reversed") ? 1 : 0) | (powered || te.getBoolean("open") ? 2 : 0)
                    | (te.getBoolean("oneWay") ? 4 : 0);
            // TrackKitSwitchTurnout: switched +1, reversed +2, mirrored ("Direction") +4
            case "railcraft_turnout" -> (switched(te) ? 1 : 0) + (te.getBoolean("Reversed") ? 2 : 0)
                    + (te.getBoolean("Direction") ? 4 : 0);
            // TrackKitSwitchWye: the raw rail direction is the block's shape
            case "railcraft_wye" -> {
                boolean mirrored = te.getBoolean("Direction");
                yield (mirrored != switched(te) ? 1 : 0) + (mirrored != "east_west".equals(shape) ? 2 : 0);
            }
            // TrackKitPowered default (booster, activator, launcher, priming, embarking, whistle, messenger),
            // TrackKitDumping, TrackKitRouting
            case "railcraft_booster", "railcraft_activator", "railcraft_launcher", "railcraft_priming",
                 "railcraft_embarking", "railcraft_whistle", "railcraft_messenger", "railcraft_dumping",
                 "railcraft_routing" -> powered ? 1 : 0;
            // junction, missing, and the kits not ported (see class doc)
            default -> 0;
        };
    }

    /** TrackKitSwitch#isVisuallySwitched on the host: {@code !locked && (shouldSwitch || sprung)}. */
    private static boolean switched(CompoundTag te) {
        return te.getByte("locked") == 0 && (te.getBoolean("Switched") || te.getByte("sprung") > 0);
    }
}
