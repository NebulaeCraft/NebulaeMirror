package net.kuina.nebulaemirror.dumper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Client-only tool mod. Exports everything the 1.21.1 mirror needs to reproduce each block's look.
 *
 * <p>Triggers:
 * <ul>
 *   <li>In a world: {@code /mirrordump [mods=a,b] [vanillaModels] [noVanillaTextures] [maxStates=N]}</li>
 *   <li>Headless-ish: start the client with {@code -Dmirrordump.auto=true} (optionally
 *       {@code -Dmirrordump.exit=true}); the dump runs when the main menu first opens.</li>
 * </ul>
 */
@Mod(modid = MirrorDumper.MODID, name = "NebulaeMirror Dumper", version = MirrorDumper.VERSION,
        clientSideOnly = true, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public class MirrorDumper {
    public static final String MODID = "mirrordumper";
    public static final String VERSION = "0.1.0";
    /** Bump when the output layout changes; the generator checks it. */
    public static final int SCHEMA = 1;
    public static final Logger LOG = LogManager.getLogger("MirrorDumper");

    private boolean autoRan;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new DumpCommand());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (autoRan || !(event.getGui() instanceof GuiMainMenu) || !Boolean.getBoolean("mirrordump.auto")) {
            return;
        }
        autoRan = true;
        try {
            File out = new Dumper(DumpOptions.fromSystemProperties()).run();
            LOG.info("Auto dump finished: {}", out);
        } catch (Throwable t) {
            LOG.error("Auto dump failed", t);
        }
        if (Boolean.getBoolean("mirrordump.exit")) {
            Minecraft.getMinecraft().shutdown();
        }
    }
}
