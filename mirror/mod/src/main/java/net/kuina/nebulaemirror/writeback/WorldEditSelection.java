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
            return new Result(null, null, "你还没有 WorldEdit 选区：请用 //wand 或 //pos1 //pos2 选好区域");
        }
        String id = world.id();
        // Fabric WorldEdit names worlds "<level name>_<dimension>", e.g. "world_minecraft:overworld".
        if (!id.equals("minecraft:overworld") && !id.endsWith("_minecraft:overworld")) {
            return new Result(null, null, "你的 WorldEdit 选区在 " + id + "，区域只支持主世界");
        }
        Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException e) {
            return new Result(null, null, "你的 WorldEdit 选区不完整：请设置两个角点");
        }
        BlockVector3 a = region.getMinimumPoint();
        BlockVector3 b = region.getMaximumPoint();
        String note = region instanceof CuboidRegion ? ""
                : "（" + shape(region) + "选区，已改用其外接长方体）";
        return new Result(new BoundingBox(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()), note, null);
    }

    private static String shape(Region region) {
        String s = region.getClass().getSimpleName().replace("Region", "").toLowerCase();
        return switch (s) {
            case "ellipsoid" -> "球体/椭球";
            case "cylinder" -> "圆柱";
            case "polygonal2d" -> "多边形";
            case "convexpolyhedral" -> "凸多面体";
            default -> s + " ";
        };
    }
}
