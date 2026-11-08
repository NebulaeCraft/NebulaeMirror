package net.kuina.nebulaemirror;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Permission nodes of the /nebulaemirror commands (fabric-permissions-api, i.e. LuckPerms-Fabric when installed).
 * Without a permissions mod each node falls back to the operator level given here.
 */
public final class MirrorPermissions {
    public static final String STATS = "nebulaemirror.stats";
    public static final String SYNC_STATUS = "nebulaemirror.sync.status";
    /** sync now / apply / refresh / cancel */
    public static final String SYNC_RUN = "nebulaemirror.sync.run";
    /** sync baseline / reload */
    public static final String SYNC_ADMIN = "nebulaemirror.sync.admin";
    public static final String ZONE_CHECKOUT = "nebulaemirror.zone.checkout";
    /** zone list / info / lint / diff */
    public static final String ZONE_VIEW = "nebulaemirror.zone.view";
    public static final String ZONE_SUBMIT = "nebulaemirror.zone.submit";
    public static final String ZONE_ABANDON = "nebulaemirror.zone.abandon";
    /** lint, diff, submit or abandon zones someone else checked out (and edit inside them) */
    public static final String ZONE_OTHERS = "nebulaemirror.zone.others";
    /** Change blocks outside one's checked-out zones (they are re-synced from the origin anyway). Nobody by default. */
    public static final String EDIT_ANYWHERE = "nebulaemirror.edit.anywhere";

    private MirrorPermissions() {
    }

    public static Predicate<CommandSourceStack> require(String node, int defaultLevel) {
        return Permissions.require(node, defaultLevel);
    }

    public static boolean check(CommandSourceStack src, String node, int defaultLevel) {
        return Permissions.check(src, node, defaultLevel);
    }

    public static boolean check(net.minecraft.world.entity.Entity entity, String node, int defaultLevel) {
        return Permissions.check(entity, node, defaultLevel);
    }

    public static boolean check(net.minecraft.world.entity.Entity entity, String node, boolean defaultValue) {
        return Permissions.check(entity, node, defaultValue);
    }
}
