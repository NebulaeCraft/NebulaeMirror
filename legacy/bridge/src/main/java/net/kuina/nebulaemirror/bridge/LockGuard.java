package net.kuina.nebulaemirror.bridge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.server.permission.PermissionAPI;

/**
 * Keeps locked zones unchanged at the origin: players cannot break, place, use buckets on or interact with
 * blocks there (doors, levers, chests, ...), entities cannot place blocks, explosions spare them and fluids do
 * not flow in. WorldEdit/FAWE edits are masked separately ({@link WorldEditLock}). Whatever still slips through
 * (a piston, a growing tree) is caught when the patch is applied: its Expect no longer matches.
 * Holders of {@value MirrorBridge#PERM_BYPASS} are not stopped (nobody has it by default).
 */
public final class LockGuard {
    private static ZoneLocks.Lock locked(World world, BlockPos pos) {
        ZoneLocks locks = MirrorBridge.locks;
        if (locks == null || world.isRemote || world.provider.getDimension() != 0) {
            return null;
        }
        return locks.at(pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean stopped(EntityPlayer player, World world, BlockPos pos) {
        ZoneLocks.Lock l = locked(world, pos);
        if (l == null || (player != null && PermissionAPI.hasPermission(player, MirrorBridge.PERM_BYPASS))) {
            return false;
        }
        if (player != null) {
            player.sendStatusMessage(new TextComponentString(TextFormatting.RED + "Zone " + l.zone
                    + " is being edited in the mirror (" + l.owner + ")"), true);
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent e) {
        if (stopped(e.getPlayer(), e.getWorld(), e.getPos())) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        EntityPlayer p = e.getEntity() instanceof EntityPlayer ? (EntityPlayer) e.getEntity() : null;
        if (stopped(p, e.getWorld(), e.getPos())) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent.RightClickBlock e) {
        if (stopped(e.getEntityPlayer(), e.getWorld(), e.getPos())
                || (e.getFace() != null && stopped(e.getEntityPlayer(), e.getWorld(), e.getPos().offset(e.getFace())))) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBucket(FillBucketEvent e) {
        RayTraceResult t = e.getTarget();
        if (t != null && t.typeOfHit == RayTraceResult.Type.BLOCK
                && (stopped(e.getEntityPlayer(), e.getWorld(), t.getBlockPos())
                || stopped(e.getEntityPlayer(), e.getWorld(), t.getBlockPos().offset(t.sideHit)))) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onExplosion(ExplosionEvent.Detonate e) {
        e.getAffectedBlocks().removeIf(p -> locked(e.getWorld(), p) != null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onFluid(BlockEvent.FluidPlaceBlockEvent e) {
        if (locked(e.getWorld(), e.getPos()) != null) {
            e.setCanceled(true);
        }
    }
}
