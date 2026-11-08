package net.kuina.nebulaemirror.sync;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.kuina.nebulaemirror.MirrorPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /nebulaemirror sync status|now|apply|refresh x1 z1 x2 z2|baseline|cancel|reload
 * (also usable over the mirror's RCON, e.g. from an external scheduler).
 */
public final class SyncCommands {
    private SyncCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("sync")
                .then(Commands.literal("status").requires(MirrorPermissions.require(MirrorPermissions.SYNC_STATUS, 2)).executes(ctx -> reply(ctx, service(ctx) == null ? null : service(ctx).status())))
                .then(Commands.literal("now").requires(MirrorPermissions.require(MirrorPermissions.SYNC_RUN, 2)).executes(ctx -> start(ctx, SyncRun.Mode.PULL_AND_APPLY, null)))
                .then(Commands.literal("apply").requires(MirrorPermissions.require(MirrorPermissions.SYNC_RUN, 2)).executes(ctx -> start(ctx, SyncRun.Mode.APPLY, null)))
                .then(Commands.literal("baseline").requires(MirrorPermissions.require(MirrorPermissions.SYNC_ADMIN, 3)).executes(ctx -> start(ctx, SyncRun.Mode.BASELINE, null)))
                .then(Commands.literal("refresh").requires(MirrorPermissions.require(MirrorPermissions.SYNC_RUN, 2))
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(ctx -> start(ctx, SyncRun.Mode.REFRESH, new int[]{
                                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                                IntegerArgumentType.getInteger(ctx, "z2")})))))))
                .then(Commands.literal("cancel").requires(MirrorPermissions.require(MirrorPermissions.SYNC_RUN, 2)).executes(ctx -> reply(ctx, service(ctx) == null ? null : service(ctx).cancel())))
                .then(Commands.literal("reload").requires(MirrorPermissions.require(MirrorPermissions.SYNC_ADMIN, 3)).executes(ctx -> reply(ctx, service(ctx) == null ? null : service(ctx).loadConfig())));
    }

    private static SyncService service(CommandContext<CommandSourceStack> ctx) {
        return SyncService.get();
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String msg) {
        String m = msg == null ? "sync is not available until the server has started" : msg;
        ctx.getSource().sendSuccess(() -> Component.literal(m), false);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx, SyncRun.Mode mode, int[] force) {
        SyncService s = SyncService.get();
        if (s == null) {
            return reply(ctx, null);
        }
        CommandSourceStack src = ctx.getSource();
        String why = s.start(mode, force, false, outcome -> src.sendSuccess(() -> Component.literal(outcome.message()), true));
        if (why != null) {
            src.sendFailure(Component.literal("Not started: " + why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Sync " + mode.name().toLowerCase() + " started; see /nebulaemirror sync status"), true);
        return 1;
    }
}
