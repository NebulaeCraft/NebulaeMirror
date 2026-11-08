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
 * /mirrorbridge apply <file> [force] | rollback <job> [force] | status | jobs | inbox | locks | unlock <zone> | cancel
 * <p>
 * apply: a patch from the inbox. rollback: restores what a job replaced (its undo file). Both refuse when
 * blocks changed at the origin since (Expect mismatch) unless "force" is given; unknown block names always
 * refuse. Permission nodes: nebulaemirror.bridge.apply / .rollback / .status (default: operators).
 */
public final class BridgeCommand extends CommandBase {
    private static final String USAGE = "/mirrorbridge apply <文件> [force] | rollback <任务> [force] | status | jobs | inbox | locks | unlock <区域> | cancel";
    private static UUID notifyPlayer;

    @Override
    public String getName() {
        return "mirrorbridge";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // checked per sub-command through the permission nodes
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return !(sender instanceof EntityPlayer) || allowed(sender, MirrorBridge.PERM_STATUS)
                || allowed(sender, MirrorBridge.PERM_APPLY) || allowed(sender, MirrorBridge.PERM_ROLLBACK)
                || allowed(sender, MirrorBridge.PERM_LOCK);
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
                    throw new CommandException("任务 " + args[1] + " 没有撤销文件（它没有改动过任何方块）");
                }
                started(sender, MirrorBridge.start("rollback", undo, false, force));
                break;
            }
            case "cancel": {
                need(sender, MirrorBridge.PERM_APPLY, args, 1);
                Job j = MirrorBridge.current;
                if (j == null || !j.running()) {
                    throw new CommandException("当前没有正在执行的任务");
                }
                j.cancel();
                store.writeReport(j);
                say(sender, j.summary());
                break;
            }
            case "status": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                Job j = MirrorBridge.current;
                say(sender, j == null ? "服务器启动以来还没有任务" : j.running() ? j.progress() : j.summary());
                break;
            }
            case "jobs": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                List<File> dirs = store.jobDirs();
                StringBuilder sb = new StringBuilder("共 " + dirs.size() + " 个任务，最新的在前：");
                for (File d : dirs.subList(0, Math.min(10, dirs.size()))) {
                    JsonObject o = BridgeStore.readReport(d);
                    sb.append("\n  ").append(d.getName());
                    if (o != null) {
                        sb.append("：").append(Job.kindLabel(o.get("kind").getAsString())).append(' ')
                                .append(Job.phaseLabel(o.get("phase").getAsString()))
                                .append("，已修改 ").append(o.get("changed").getAsInt()).append('/').append(o.get("entries").getAsInt())
                                .append(o.get("undo").getAsBoolean() ? "，保留了撤销文件" : "");
                    }
                }
                say(sender, sb.toString());
                break;
            }
            case "locks": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                List<ZoneLocks.Lock> all = MirrorBridge.locks.all();
                StringBuilder sb = new StringBuilder(all.isEmpty() ? "没有锁定的区域" : "共 " + all.size() + " 个锁定的区域：");
                for (ZoneLocks.Lock l : all) {
                    sb.append("\n  ").append(l);
                }
                say(sender, sb.toString());
                break;
            }
            case "unlock": {
                need(sender, MirrorBridge.PERM_LOCK, args, 2);
                say(sender, MirrorBridge.locks.unlock(args[1]) ? "区域 " + args[1] + " 已解锁（镜像服上它仍是签出状态，"
                        + "请在镜像服放弃或提交）" : "区域 " + args[1] + " 没有锁定");
                break;
            }
            case "inbox": {
                need(sender, MirrorBridge.PERM_STATUS, args, 1);
                String[] names = store.inbox.list();
                say(sender, names == null || names.length == 0 ? "收件箱 " + store.inbox + " 是空的"
                        : "收件箱 " + store.inbox + "：" + String.join(", ", names));
                break;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    private static void need(ICommandSender sender, String node, String[] args, int minArgs) throws CommandException {
        if (!allowed(sender, node)) {
            throw new CommandException("缺少权限 " + node);
        }
        if (args.length < minArgs) {
            throw new WrongUsageException(USAGE);
        }
    }

    private static void started(ICommandSender sender, String why) throws CommandException {
        if (why != null) {
            throw new CommandException(why);
        }
        notifyPlayer = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).getUniqueID() : null;
        say(sender, "已开始 " + MirrorBridge.current.progress() + "；进度见 /mirrorbridge status");
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
            return getListOfStringsMatchingLastWord(args, "apply", "rollback", "status", "jobs", "inbox", "locks", "unlock", "cancel");
        }
        if (args.length == 2 && args[0].equals("unlock")) {
            List<String> zones = new ArrayList<>();
            MirrorBridge.locks.all().forEach(l -> zones.add(l.zone));
            return getListOfStringsMatchingLastWord(args, zones);
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
