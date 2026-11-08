package net.kuina.nebulaemirror.bridge;

import com.google.gson.JsonObject;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.server.permission.PermissionAPI;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /mirrorbridge apply <file> [force] | rollback <job> [force] | status | jobs | inbox | cancel
 * <p>
 * apply: a patch from the inbox. rollback: restores what a job replaced (its undo file). Both refuse when
 * blocks changed at the origin since (Expect mismatch) unless "force" is given; unknown block names always
 * refuse. Permission nodes: nebulaemirror.bridge.apply / .rollback / .status (default: operators).
 */
public final class BridgeCommand extends CommandBase {
    private static UUID notifyPlayer;

    @Override
    public String getName() {
        return "mirrorbridge";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mirrorbridge apply <file> [force] | rollback <job> [force] | status | jobs | inbox | cancel";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // checked per sub-command through the permission nodes
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return !(sender instanceof EntityPlayer) || allowed(sender, MirrorBridge.PERM_STATUS)
                || allowed(sender, MirrorBridge.PERM_APPLY) || allowed(sender, MirrorBridge.PERM_ROLLBACK);
    }

    private static boolean allowed(ICommandSender sender, String node) {
        return !(sender instanceof EntityPlayer) || PermissionAPI.hasPermission((EntityPlayer) sender, node);
    }

    private static void say(ICommandSender sender, String msg) {
        for (String line : msg.split("\n")) {
            sender.sendMessage(new TextComponentString(line));
        }
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        BridgeStore store = MirrorBridge.store;
        boolean force = args.length > 2 && args[2].equals("force");
        switch (args[0]) {
            case "apply": {
                need(sender, MirrorBridge.PERM_APPLY, args, 2);
                File f;
                try {
                    f = store.inboxFile(args[1]);
                } catch (java.io.IOException e) {
                    throw new CommandException(e.getMessage());
                }
                started(sender, MirrorBridge.start("apply", f, true, force));
                break;
            }
            case "rollback": {
                need(sender, MirrorBridge.PERM_ROLLBACK, args, 2);
                File undo = new File(store.job(args[1]), "undo.nbt");
                if (!undo.isFile()) {
                    throw new CommandException("job " + args[1] + " has no undo file (it never changed anything)");
                }
                started(sender, MirrorBridge.start("rollback", undo, false, force));
                break;
            }
            case "cancel": {
                need(sender, MirrorBridge.PERM_APPLY, args, 1);
                Job j = MirrorBridge.current;
                if (j == null || !j.running()) {
                    throw new CommandException("no job is running");
                }
                j.cancel();
                store.writeReport(j);
                say(sender, j.summary());
                break;
            }
            case "status": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                Job j = MirrorBridge.current;
                say(sender, j == null ? "No job since the server started" : j.running() ? j.progress() : j.summary());
                break;
            }
            case "jobs": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                List<File> dirs = store.jobDirs();
                StringBuilder sb = new StringBuilder(dirs.size() + " job(s), newest first:");
                for (File d : dirs.subList(0, Math.min(10, dirs.size()))) {
                    JsonObject o = BridgeStore.readReport(d);
                    sb.append("\n  ").append(d.getName());
                    if (o != null) {
                        sb.append(": ").append(o.get("kind").getAsString()).append(' ').append(o.get("phase").getAsString())
                                .append(", ").append(o.get("changed").getAsInt()).append('/').append(o.get("entries").getAsInt())
                                .append(" changed").append(o.get("undo").getAsBoolean() ? ", undo kept" : "");
                    }
                }
                say(sender, sb.toString());
                break;
            }
            case "inbox": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                String[] names = store.inbox.list();
                say(sender, names == null || names.length == 0 ? "Inbox " + store.inbox + " is empty"
                        : "Inbox " + store.inbox + ": " + String.join(", ", names));
                break;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    private static void need(ICommandSender sender, String node, String[] args, int minArgs) throws CommandException {
        if (!allowed(sender, node)) {
            throw new CommandException("missing permission " + node);
        }
        if (args.length < minArgs) {
            throw new WrongUsageException("/mirrorbridge apply <file> [force] | rollback <job> [force] | status | jobs | inbox | cancel");
        }
    }

    private static void started(ICommandSender sender, String why) throws CommandException {
        if (why != null) {
            throw new CommandException(why);
        }
        notifyPlayer = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).getUniqueID() : null;
        say(sender, "Started " + MirrorBridge.current.progress() + "; /mirrorbridge status");
    }

    static void notifyDone(MinecraftServer server, Job j) {
        String msg = j.summary();
        MirrorBridge.LOG.info(msg);
        if (notifyPlayer != null) {
            EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(notifyPlayer);
            if (p != null) {
                say(p, msg);
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "apply", "rollback", "status", "jobs", "inbox", "cancel");
        }
        if (args.length == 2 && args[0].equals("apply")) {
            String[] names = MirrorBridge.store.inbox.list();
            return getListOfStringsMatchingLastWord(args, names == null ? Collections.emptyList() : Arrays.asList(names));
        }
        if (args.length == 2 && args[0].equals("rollback")) {
            List<String> ids = new ArrayList<>();
            MirrorBridge.store.jobDirs().forEach(d -> ids.add(d.getName()));
            return getListOfStringsMatchingLastWord(args, ids);
        }
        if (args.length == 3) {
            return getListOfStringsMatchingLastWord(args, "force");
        }
        return Collections.emptyList();
    }
}
