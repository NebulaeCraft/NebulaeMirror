package net.kuina.nebulaemirror.dumper;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.io.File;

final class DumpCommand extends CommandBase {
    @Override
    public String getName() {
        return "mirrordump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mirrordump [mods=a,b] [vanillaModels] [noVanillaTextures] [maxStates=N]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        DumpOptions options;
        try {
            options = DumpOptions.parse(args);
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage() + " — " + getUsage(sender));
        }
        sender.sendMessage(new TextComponentString("[MirrorDumper] 正在导出，游戏会卡住一段时间……"));
        try {
            Dumper dumper = new Dumper(options);
            File out = dumper.run();
            sender.sendMessage(new TextComponentString("[MirrorDumper] 完成：" + dumper.chatSummary() + " -> " + out));
        } catch (Throwable t) {
            MirrorDumper.LOG.error("Dump failed", t);
            sender.sendMessage(new TextComponentString("[MirrorDumper] 失败：" + t + "（详见日志）"));
        }
    }
}
