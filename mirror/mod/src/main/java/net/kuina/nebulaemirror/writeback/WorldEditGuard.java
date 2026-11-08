package net.kuina.nebulaemirror.writeback;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * WorldEdit edits by players skip blocks outside their checked-out zones (an extent around every edit session,
 * the way WorldGuard does it). Only loaded when WorldEdit is installed; see {@link EditGuard}.
 */
final class WorldEditGuard {
    private static volatile MinecraftServer server;

    private WorldEditGuard() {
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        WorldEdit.getInstance().getEventBus().register(new WorldEditGuard());
        NebulaeMirror.LOG.info("WorldEdit edits are limited to checked-out zones");
    }

    @Subscribe
    public void onEditSession(EditSessionEvent e) {
        if (e.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        Actor actor = e.getActor();
        World world = e.getWorld();
        MinecraftServer s = server;
        if (!(actor instanceof Player) || world == null || s == null) {
            return; // console, command blocks: allowed (and re-synced if outside zones)
        }
        String id = world.id();
        if (!id.equals("minecraft:overworld") && !id.endsWith("_minecraft:overworld")) {
            return;
        }
        ServerPlayer p = s.getPlayerList().getPlayer(actor.getUniqueId());
        if (p == null) {
            return;
        }
        List<BoundingBox> boxes = EditGuard.editableBoxes(p);
        if (boxes != null) {
            e.setExtent(new ZoneExtent(e.getExtent(), List.copyOf(boxes), actor));
        }
    }

    /** printRaw: the only Actor print method without a text-component overload (that library is not on our classpath). */
    private static void printError(Actor actor, String msg) {
        actor.printRaw("\u00a7c" + msg);
    }

    private static final class ZoneExtent extends AbstractDelegateExtent {
        private final List<BoundingBox> boxes;
        private final Actor actor;
        private boolean told;

        ZoneExtent(Extent extent, List<BoundingBox> boxes, Actor actor) {
            super(extent);
            this.boxes = boxes;
            this.actor = actor;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 p, T block) throws WorldEditException {
            for (BoundingBox b : boxes) {
                if (b.isInside(p.x(), p.y(), p.z())) {
                    return super.setBlock(p, block);
                }
            }
            if (!told) {
                told = true;
                // Plain-text variant: the text component classes are in a library we do not compile against.
                printError(actor, "你签出的区域之外的方块不会被修改（请先签出区域：/nebulaemirror zone checkout）");
            }
            return false;
        }
    }
}
