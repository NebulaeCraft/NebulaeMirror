package net.kuina.nebulaemirror.writeback;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.kuina.nebulaemirror.MirrorPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * /nebulaemirror zone checkout <name> [<from> <to>] | list | info <name> | lint <name> | diff <name>
 *                    | submit <name> [force] | abandon <name>
 * <p>
 * checkout without corners uses the player's WorldEdit selection (its bounding box for non-cuboid shapes).
 * Permission nodes: see {@link MirrorPermissions} (zone.checkout / view / submit / abandon; zone.others to act
 * on zones checked out by someone else).
 */
public final class ZoneCommands {
    private ZoneCommands() {
    }

    private static final SuggestionProvider<CommandSourceStack> ZONES = (ctx, builder) -> {
        ZoneManager m = ZoneManager.get();
        return SharedSuggestionProvider.suggest(m == null ? List.of() : m.zones().stream().map(z -> z.name).toList(), builder);
    };

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("zone")
                .then(Commands.literal("checkout").requires(perm(MirrorPermissions.ZONE_CHECKOUT))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ZoneCommands::checkoutSelection)
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(ZoneCommands::checkout)))))
                .then(Commands.literal("list").requires(perm(MirrorPermissions.ZONE_VIEW)).executes(ZoneCommands::list))
                .then(Commands.literal("info").requires(perm(MirrorPermissions.ZONE_VIEW))
                        .then(name().executes(ctx -> withZone(ctx, false, ZoneCommands::info))))
                .then(Commands.literal("lint").requires(perm(MirrorPermissions.ZONE_VIEW))
                        .then(name().executes(ctx -> withZone(ctx, true, (c, z) -> lint(c, z, false)))))
                .then(Commands.literal("diff").requires(perm(MirrorPermissions.ZONE_VIEW))
                        .then(name().executes(ctx -> withZone(ctx, true, (c, z) -> lint(c, z, true)))))
                .then(Commands.literal("submit").requires(perm(MirrorPermissions.ZONE_SUBMIT))
                        .then(name().executes(ctx -> withZone(ctx, true, (c, z) -> submit(c, z, false)))
                                .then(Commands.literal("force").executes(ctx -> withZone(ctx, true, (c, z) -> submit(c, z, true))))))
                .then(Commands.literal("abandon").requires(perm(MirrorPermissions.ZONE_ABANDON))
                        .then(name().executes(ctx -> withZone(ctx, true, ZoneCommands::abandon))));
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String node) {
        return MirrorPermissions.require(node, 2);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word()).suggests(ZONES);
    }

    private interface ZoneAction {
        int run(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone zone) throws Exception;
    }

    /** {@code ownerOnly}: the zone's owner, the console, or holders of zone.others. */
    private static int withZone(CommandContext<CommandSourceStack> ctx, boolean ownerOnly, ZoneAction action) {
        ZoneManager m = ZoneManager.get();
        if (m == null) {
            return fail(ctx, "服务器启动完成后才能使用区域");
        }
        String name = StringArgumentType.getString(ctx, "name");
        ZoneManager.Zone z = m.zone(name);
        if (z == null) {
            return fail(ctx, "没有已签出的区域 " + name);
        }
        if (ownerOnly && !mayTouch(ctx.getSource(), z)) {
            return fail(ctx, "区域 " + name + " 属于 " + z.owner + "（需要权限 " + MirrorPermissions.ZONE_OTHERS + "）");
        }
        try {
            return action.run(ctx, z);
        } catch (Exception e) {
            return fail(ctx, e.toString());
        }
    }

    private static boolean mayTouch(CommandSourceStack src, ZoneManager.Zone z) {
        ServerPlayer p = src.getPlayer();
        return p == null || z.ownerUuid == null || z.ownerUuid.equals(p.getStringUUID())
                || MirrorPermissions.check(src, MirrorPermissions.ZONE_OTHERS, 3);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), true);
    }

    private static int checkout(CommandContext<CommandSourceStack> ctx) {
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgument.getBlockPos(ctx, "to");
        return checkout(ctx, BoundingBox.fromCorners(a, b), "");
    }

    /** checkout <name> without corners: the player's WorldEdit selection. */
    private static int checkoutSelection(CommandContext<CommandSourceStack> ctx) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("worldedit")) {
            return fail(ctx, "本服务器没有安装 WorldEdit：请直接给出两个角点，"
                    + "zone checkout <名称> <起点> <终点>");
        }
        WorldEditSelection.Result sel = WorldEditSelection.of(ctx.getSource().getTextName());
        if (sel.error() != null) {
            return fail(ctx, sel.error());
        }
        return checkout(ctx, sel.box(), sel.note());
    }

    private static int checkout(CommandContext<CommandSourceStack> ctx, BoundingBox box, String note) {
        ZoneManager m = ZoneManager.get();
        if (m == null) {
            return fail(ctx, "服务器启动完成后才能使用区域");
        }
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        String name = StringArgumentType.getString(ctx, "name");
        String why = m.checkout(name, box, src.getTextName(), p == null ? null : p.getStringUUID(), msg -> say(src, msg));
        if (why != null) {
            return fail(ctx, why);
        }
        say(src, "正在签出 " + name + " [" + box.minX() + " " + box.minY() + " " + box.minZ() + " .. " + box.maxX()
                + " " + box.maxY() + " " + box.maxZ() + "]（" + ZoneSnapshot.volume(box) + " 个方块）" + note
                + (m.locksOrigin() ? "：先在原服锁定，再从原服刷新……"
                : "：先从原服刷新……"));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ZoneManager m = ZoneManager.get();
        if (m == null || m.zones().isEmpty()) {
            say(ctx.getSource(), "没有已签出的区域");
            return 0;
        }
        StringBuilder sb = new StringBuilder("共 " + m.zones().size() + " 个区域：");
        for (ZoneManager.Zone z : m.zones()) {
            sb.append("\n  ").append(z).append("，签出者 ").append(z.owner).append("，签出于 ").append(z.checkedOut)
                    .append(z.locked ? "，已在原服锁定" : "，原服未锁定");
        }
        say(ctx.getSource(), sb.toString());
        return m.zones().size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z) {
        say(ctx.getSource(), z + "，签出者 " + z.owner + "，签出于 " + z.checkedOut
                + (z.refreshed ? "" : "（没有从原服刷新）")
                + (z.locked ? "，已在原服锁定" : "，原服未锁定")
                + (z.lastJob != null ? "，上次提交：原服任务 " + z.lastJob : "")
                + "；共 " + ZoneSnapshot.volume(z.box()) + " 个方块");
        return 1;
    }

    private static int lint(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z, boolean writePatch) throws Exception {
        ZoneManager m = ZoneManager.get();
        long t0 = System.currentTimeMillis();
        PatchBuilder.Result r = m.diff(z);
        StringBuilder sb = new StringBuilder();
        sb.append("区域 ").append(z.name).append("：与签出时相比有 ").append(r.changedBlocks).append(" 个方块不同，")
                .append(r.entries.size()).append(" 个需要回写（其中 ").append(r.shapeOnly).append(" 个只是形状变化）；")
                .append(r.errorCount()).append(" 个错误，").append(r.warningCount()).append(" 个警告，用时 ")
                .append(System.currentTimeMillis() - t0).append(" 毫秒");
        append(sb, "错误", r.errors);
        append(sb, "警告", r.warnings);
        if (writePatch) {
            if (r.errorCount() > 0) {
                sb.append("\n未生成补丁：请先修正错误");
            } else {
                Path f = m.writePatch(z, r);
                sb.append("\n补丁（").append(r.entries.size()).append(" 条）：").append(f);
            }
        }
        sb.append("\n完整报告：").append(m.dir(z.name).resolve("lint.json"));
        say(ctx.getSource(), sb.toString());
        return r.errorCount() == 0 ? 1 : 0;
    }

    private static void append(StringBuilder sb, String level, Map<String, List<PatchBuilder.Problem>> problems) {
        problems.forEach((code, list) -> {
            sb.append("\n").append(level).append(" ").append(code).append("：");
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                PatchBuilder.Problem p = list.get(i);
                sb.append("\n  ").append(p.pos().getX()).append(' ').append(p.pos().getY()).append(' ')
                        .append(p.pos().getZ()).append(": ").append(p.detail());
            }
            if (list.size() > 5) {
                sb.append("\n  ...");
            }
        });
    }

    private static int submit(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z, boolean force) throws Exception {
        CommandSourceStack src = ctx.getSource();
        String why = ZoneManager.get().submit(z, force, msg -> say(src, msg));
        if (why != null) {
            return fail(ctx, why);
        }
        say(src, "正在把 " + z.name + " 发送到原服" + (force ? "（强制：覆盖原服上的改动）" : "") + "……");
        return 1;
    }

    private static int abandon(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z) throws Exception {
        CommandSourceStack src = ctx.getSource();
        String now = ZoneManager.get().abandon(z, msg -> say(src, msg));
        say(src, now != null ? now : "正在放弃 " + z.name + "：在原服解锁并恢复其内容……");
        return 1;
    }
}
