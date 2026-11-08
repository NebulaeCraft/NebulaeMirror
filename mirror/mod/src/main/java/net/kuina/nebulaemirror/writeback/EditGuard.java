package net.kuina.nebulaemirror.writeback;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.kuina.nebulaemirror.MirrorPermissions;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.sync.SyncService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outside checked-out zones the origin is authoritative, so players may not change the mirror there: breaking,
 * placing, using items on blocks (empty-handed interaction such as opening doors stays possible) and buckets are
 * refused, and WorldEdit edits skip those blocks ({@link WorldEditGuard}). A player may edit inside zones they
 * checked out (any zone with {@value MirrorPermissions#ZONE_OTHERS}); {@value MirrorPermissions#EDIT_ANYWHERE}
 * lifts the restriction. Whatever still changes outside zones (commands, Axiom, water, fire) is re-applied from
 * the origin at the next sync ({@link MirrorEdits}). Off with "protectOutsideZones": false in the sync config.
 */
public final class EditGuard {
    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    private EditGuard() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> allowed(player, world, pos));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player.getItemInHand(hand).isEmpty()) {
                return InteractionResult.PASS; // doors, levers, chests: reverted by the next sync if they change state
            }
            BlockPos p = hit.getBlockPos();
            return allowed(player, world, p) && allowed(player, world, p.relative(hit.getDirection()))
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            var stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BucketItem) && !(stack.getItem() instanceof SolidBucketItem)) {
                return InteractionResultHolder.pass(stack);
            }
            HitResult hit = player.pick(player.blockInteractionRange(), 1.0f, true);
            if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                    && (!allowed(player, world, b.getBlockPos()) || !allowed(player, world, b.getBlockPos().relative(b.getDirection())))) {
                return InteractionResultHolder.fail(stack);
            }
            return InteractionResultHolder.pass(stack);
        });
        if (FabricLoader.getInstance().isModLoaded("worldedit")) {
            try {
                WorldEditGuard.register();
            } catch (Throwable t) {
                NebulaeMirror.LOG.error("Could not restrict WorldEdit edits to checked-out zones: {}", t.toString());
            }
        }
    }

    static boolean protecting() {
        SyncService s = SyncService.get();
        return s != null && s.config().protectOutsideZones;
    }

    /** Null: the player may edit anywhere; otherwise the boxes they may edit in (possibly none). */
    static List<BoundingBox> editableBoxes(ServerPlayer player) {
        if (!protecting() || MirrorPermissions.check(player, MirrorPermissions.EDIT_ANYWHERE, false)) {
            return null;
        }
        ZoneManager m = ZoneManager.get();
        List<BoundingBox> out = new ArrayList<>();
        if (m != null) {
            boolean others = MirrorPermissions.check(player, MirrorPermissions.ZONE_OTHERS, 3);
            for (ZoneManager.Zone z : m.zones()) {
                if (others || player.getStringUUID().equals(z.ownerUuid)) {
                    out.add(z.box());
                }
            }
        }
        return out;
    }

    private static boolean allowed(Player player, Level world, BlockPos pos) {
        if (world.isClientSide() || world.dimension() != Level.OVERWORLD || !(player instanceof ServerPlayer sp)) {
            return true;
        }
        List<BoundingBox> boxes = editableBoxes(sp);
        if (boxes == null) {
            return true;
        }
        for (BoundingBox b : boxes) {
            if (b.isInside(pos)) {
                return true;
            }
        }
        tell(sp);
        return false;
    }

    static void tell(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(p.getUUID());
        if (last == null || now - last > 3000) {
            LAST_MESSAGE.put(p.getUUID(), now);
            p.displayClientMessage(Component.literal("Outside your checked-out zones the mirror follows the origin: "
                    + "check out a zone first (/nebulaemirror zone checkout)").withStyle(ChatFormatting.RED), true);
        }
    }
}
