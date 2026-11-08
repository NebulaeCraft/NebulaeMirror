package net.kuina.nebulaemirror.bridge;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.Subscribe;

/**
 * Masks WorldEdit / FastAsyncWorldEdit edits so they skip locked zones (same mechanism WorldGuard uses: an
 * extent wrapped around every edit session). Only loaded when WorldEdit is installed.
 * <p>
 * FAWE ignores extents from plugins it does not know: add
 * {@code net.kuina.nebulaemirror.bridge.WorldEditLock$LockedExtent} to {@code extent.allowed-plugins} in
 * FAWE's config.yml (FAWE logs "Potentially unsafe extent blocked" otherwise).
 */
final class WorldEditLock {
    private WorldEditLock() {
    }

    static void register() {
        WorldEdit.getInstance().getEventBus().register(new WorldEditLock());
        MirrorBridge.LOG.info("WorldEdit edits skip locked zones");
    }

    @Subscribe
    public void onEditSession(EditSessionEvent e) {
        if (e.getStage() != EditSession.Stage.BEFORE_HISTORY && e.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        if (!overworld(e.getWorld())) {
            return;
        }
        e.setExtent(new LockedExtent(e.getExtent()));
    }

    /** WorldEdit-Forge's world wraps the Minecraft world; when that cannot be read, assume the overworld. */
    private static boolean overworld(Object weWorld) {
        if (weWorld == null) {
            return true;
        }
        try {
            Object mc = weWorld.getClass().getMethod("getWorld").invoke(weWorld);
            if (mc instanceof net.minecraft.world.World) {
                return ((net.minecraft.world.World) mc).provider.getDimension() == 0;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // FAWE wrapper or another implementation: be conservative
        }
        return true;
    }

    public static final class LockedExtent extends AbstractDelegateExtent {
        LockedExtent(Extent extent) {
            super(extent);
        }

        private static boolean locked(int x, int y, int z) {
            ZoneLocks l = MirrorBridge.locks;
            return l != null && l.at(x, y, z) != null;
        }

        @Override
        public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
            if (locked(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return false;
            }
            return super.setBlock(location, block);
        }

        /** FAWE's AbstractDelegateExtent has this fast-path overload; this overrides it there. */
        public boolean setBlock(int x, int y, int z, BaseBlock block) throws WorldEditException {
            if (locked(x, y, z)) {
                return false;
            }
            return super.setBlock(new Vector(x, y, z), block);
        }
    }
}
