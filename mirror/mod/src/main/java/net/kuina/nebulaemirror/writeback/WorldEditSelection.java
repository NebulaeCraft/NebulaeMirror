package net.kuina.nebulaemirror.writeback;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A player's current WorldEdit selection as a zone box. Uses only WorldEdit's core API, so it works with the
 * Fabric WorldEdit on the mirror. Only load this class after checking that WorldEdit is installed
 * ({@link ZoneCommands}); it references WorldEdit types.
 */
final class WorldEditSelection {
    /** box, or null with {@code error} set; {@code note} describes an adjusted selection. */
    record Result(BoundingBox box, String note, String error) {
    }

    private WorldEditSelection() {
    }

    /** {@code name}: the command source's name (a player's name; the console's WorldEdit session otherwise). */
    static Result of(String name) {
        return read(name);
    }

    private static Result read(String name) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().findByName(name);
        World world = session == null ? null : session.getSelectionWorld();
        if (world == null) {
            return new Result(null, null, "you have no WorldEdit selection: select the zone with //wand or //pos1 //pos2");
        }
        String id = world.id();
        // Fabric WorldEdit names worlds "<level name>_<dimension>", e.g. "world_minecraft:overworld".
        if (!id.equals("minecraft:overworld") && !id.endsWith("_minecraft:overworld")) {
            return new Result(null, null, "your WorldEdit selection is in " + id + "; zones are overworld only");
        }
        Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException e) {
            return new Result(null, null, "your WorldEdit selection is incomplete: set both corners");
        }
        BlockVector3 a = region.getMinimumPoint();
        BlockVector3 b = region.getMaximumPoint();
        String note = region instanceof CuboidRegion ? ""
                : " (" + region.getClass().getSimpleName().replace("Region", "").toLowerCase()
                + " selection: its bounding box is used)";
        return new Result(new BoundingBox(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()), note, null);
    }
}
