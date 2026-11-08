package net.kuina.nebulaemirror.writeback;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * /nebulaemirror zone checkout <name> <from> <to> | list | info <name> | lint <name> | diff <name> | abandon <name>
 * <p>
 * P3 step 1 works on the mirror alone: checkout refreshes the area from the origin and snapshots it (and the
 * sync then leaves it alone), lint and diff compute what would be written back and write patch.nbt. Sending the
 * patch to the origin (bridge mod) is the next step.
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
                .then(Commands.literal("checkout")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(ZoneCommands::checkout)))))
                .then(Commands.literal("list").executes(ZoneCommands::list))
                .then(Commands.literal("info").then(name().executes(ctx -> withZone(ctx, ZoneCommands::info))))
                .then(Commands.literal("lint").then(name().executes(ctx -> withZone(ctx, (c, z) -> lint(c, z, false)))))
                .then(Commands.literal("diff").then(name().executes(ctx -> withZone(ctx, (c, z) -> lint(c, z, true)))))
                .then(Commands.literal("abandon").then(name().executes(ctx -> withZone(ctx, ZoneCommands::abandon))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word()).suggests(ZONES);
    }

    private interface ZoneAction {
        int run(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone zone) throws Exception;
    }

    private static int withZone(CommandContext<CommandSourceStack> ctx, ZoneAction action) {
        ZoneManager m = ZoneManager.get();
        if (m == null) {
            return fail(ctx, "zones are not available until the server has started");
        }
        String name = StringArgumentType.getString(ctx, "name");
        ZoneManager.Zone z = m.zone(name);
        if (z == null) {
            return fail(ctx, "no checked-out zone " + name);
        }
        try {
            return action.run(ctx, z);
        } catch (Exception e) {
            return fail(ctx, e.toString());
        }
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), true);
    }

    private static int checkout(CommandContext<CommandSourceStack> ctx) {
        ZoneManager m = ZoneManager.get();
        if (m == null) {
            return fail(ctx, "zones are not available until the server has started");
        }
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgument.getBlockPos(ctx, "to");
        BoundingBox box = BoundingBox.fromCorners(a, b);
        CommandSourceStack src = ctx.getSource();
        String why = m.checkout(StringArgumentType.getString(ctx, "name"), box, src.getTextName(), msg -> say(src, msg));
        if (why != null) {
            return fail(ctx, why);
        }
        say(src, "Checking out " + StringArgumentType.getString(ctx, "name") + " (" + ZoneSnapshot.volume(box)
                + " blocks): refreshing it from the origin first...");
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ZoneManager m = ZoneManager.get();
        if (m == null || m.zones().isEmpty()) {
            say(ctx.getSource(), "No zones checked out");
            return 0;
        }
        StringBuilder sb = new StringBuilder(m.zones().size() + " zone(s):");
        for (ZoneManager.Zone z : m.zones()) {
            sb.append("\n  ").append(z).append(" by ").append(z.owner).append(" since ").append(z.checkedOut);
        }
        say(ctx.getSource(), sb.toString());
        return m.zones().size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z) {
        say(ctx.getSource(), z + " by " + z.owner + ", checked out " + z.checkedOut
                + (z.refreshed ? "" : " (not refreshed from the origin)") + "; " + ZoneSnapshot.volume(z.box()) + " blocks");
        return 1;
    }

    private static int lint(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z, boolean writePatch) throws Exception {
        ZoneManager m = ZoneManager.get();
        long t0 = System.currentTimeMillis();
        PatchBuilder.Result r = m.diff(z);
        StringBuilder sb = new StringBuilder();
        sb.append("Zone ").append(z.name).append(": ").append(r.changedBlocks).append(" blocks differ from checkout, ")
                .append(r.entries.size()).append(" to write back (").append(r.shapeOnly).append(" only changed shape); ")
                .append(r.errorCount()).append(" errors, ").append(r.warningCount()).append(" warnings in ")
                .append(System.currentTimeMillis() - t0).append(" ms");
        append(sb, "ERROR", r.errors);
        append(sb, "warning", r.warnings);
        if (writePatch) {
            if (r.errorCount() > 0) {
                sb.append("\nNo patch written: fix the errors first");
            } else {
                Path f = m.writePatch(z, r);
                sb.append("\nPatch with ").append(r.entries.size()).append(" entries: ").append(f);
            }
        }
        sb.append("\nFull report: ").append(m.dir(z.name).resolve("lint.json"));
        say(ctx.getSource(), sb.toString());
        return r.errorCount() == 0 ? 1 : 0;
    }

    private static void append(StringBuilder sb, String level, Map<String, List<PatchBuilder.Problem>> problems) {
        problems.forEach((code, list) -> {
            sb.append("\n").append(level).append(" ").append(code).append(':');
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

    private static int abandon(CommandContext<CommandSourceStack> ctx, ZoneManager.Zone z) throws Exception {
        CommandSourceStack src = ctx.getSource();
        String now = ZoneManager.get().abandon(z, msg -> say(src, msg));
        say(src, now != null ? now : "Abandoning " + z.name + ": restoring the origin's content...");
        return 1;
    }
}
