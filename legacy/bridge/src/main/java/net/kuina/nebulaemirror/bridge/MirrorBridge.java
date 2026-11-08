package net.kuina.nebulaemirror.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;

/**
 * Server-side half of the NebulaeMirror write-back: applies patches made by the 1.21.1 mirror
 * ({@code /nebulaemirror zone diff}) to the overworld, with an undo file per job and rollback.
 * Zones the mirror checks out are locked here ({@link LockGuard}, {@link WorldEditLock}); the mirror talks to
 * {@link BridgeHttp}. See docs/bridge.md.
 */
@Mod(modid = MirrorBridge.MODID, name = "NebulaeMirror Bridge", version = MirrorBridge.VERSION,
        serverSideOnly = true, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public final class MirrorBridge {
    public static final String MODID = "mirrorbridge";
    public static final String VERSION = "0.2.0";
    public static final Logger LOG = LogManager.getLogger("MirrorBridge");

    /** Permission nodes (Forge PermissionAPI; SpongeForge hands them to LuckPerms). Default: operators. */
    public static final String PERM_APPLY = "nebulaemirror.bridge.apply";
    public static final String PERM_ROLLBACK = "nebulaemirror.bridge.rollback";
    public static final String PERM_STATUS = "nebulaemirror.bridge.status";
    public static final String PERM_LOCK = "nebulaemirror.bridge.lock";
    /** Edit inside locked zones anyway (nobody has it by default, operators included). */
    public static final String PERM_BYPASS = "nebulaemirror.bridge.bypasslock";

    static volatile ZoneLocks locks;
    private static BridgeHttp http;

    static BridgeStore store;
    static volatile Job current;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        PermissionAPI.registerNode(PERM_APPLY, DefaultPermissionLevel.OP, "Apply a mirror write-back patch");
        PermissionAPI.registerNode(PERM_ROLLBACK, DefaultPermissionLevel.OP, "Roll back a write-back job");
        PermissionAPI.registerNode(PERM_STATUS, DefaultPermissionLevel.OP, "See write-back jobs");
        PermissionAPI.registerNode(PERM_LOCK, DefaultPermissionLevel.OP, "Release zones locked by the mirror");
        PermissionAPI.registerNode(PERM_BYPASS, DefaultPermissionLevel.NONE, "Edit inside zones locked by the mirror");
        MinecraftForge.EVENT_BUS.register(new LockGuard());
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        if (Loader.isModLoaded("worldedit")) {
            try {
                WorldEditLock.register();
            } catch (Throwable t) {
                LOG.error("Could not mask WorldEdit edits in locked zones: {}", t.toString());
            }
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        File worldDir = DimensionManager.getCurrentSaveRootDirectory();
        store = new BridgeStore(worldDir);
        store.markInterrupted();
        locks = new ZoneLocks(store.root);
        e.registerServerCommand(new BridgeCommand());
        LOG.info("MirrorBridge {}: patches in {}; {} locked zone(s){}", VERSION, store.inbox, locks.all().size(),
                locks.all().isEmpty() ? "" : " " + locks.all());
        if (BridgeConfig.token.isEmpty()) {
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            BridgeConfig.token = sb.toString();
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
            LOG.info("Generated the mirror token in config/{}.cfg", MODID);
        }
        if (BridgeConfig.httpEnabled) {
            http = new BridgeHttp(e.getServer());
            try {
                http.start();
            } catch (IOException ex) {
                LOG.error("Cannot start the HTTP API on {}:{}: {}", BridgeConfig.httpHost, BridgeConfig.httpPort, ex.toString());
                http = null;
            }
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent e) {
        if (http != null) {
            http.stop();
            http = null;
        }
        Job j = current;
        if (j != null && j.running()) {
            LOG.warn("Server stopping during {}", j.progress());
            store.writeReport(j); // still check/apply: marked interrupted on the next start
        }
        current = null;
    }

    /** Starts a job from a patch file (copied into the job directory). Returns null when started. */
    /** {@code consumeSource}: delete the source file (an inbox patch) once the job is done. */
    static String start(String kind, File patchFile, boolean consumeSource, boolean force) {
        Job running = current;
        if (running != null && running.running()) {
            return "busy: " + running.progress();
        }
        try {
            Patch patch = Patch.read(patchFile);
            File dir = store.newJob(patch.zone.isEmpty() ? kind : patch.zone);
            BridgeStore.copy(patchFile, new File(dir, "patch.nbt"));
            Job job = new Job(dir.getName(), kind, patchFile.getName(), force, patch, dir);
            job.consume = consumeSource ? patchFile : null;
            store.writeReport(job);
            current = job;
            LOG.info("Job {} ({}) started: {} entries from {}{}", job.id, kind, patch.entries.size(), patchFile.getName(),
                    force ? " (force)" : "");
            return null;
        } catch (IOException | RuntimeException ex) {
            return "cannot read " + patchFile.getName() + ": " + ex;
        }
    }

    private long lastReport;

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        Job j = current;
        if (e.phase != TickEvent.Phase.END || j == null || !j.running()) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        WorldServer world = server.getWorld(0);
        j.tick(world, System.nanoTime() + BridgeConfig.tickBudgetMillis * 1_000_000L);
        long now = System.currentTimeMillis();
        if (!j.running()) {
            if (j.phase == Job.Phase.DONE && j.consume != null && !j.consume.delete()) {
                LOG.warn("Cannot remove {} from the inbox", j.consume);
            }
            if (j.phase == Job.Phase.DONE && j.kind.equals("submit")) {
                if (locks.unlock(j.patch.zone)) {
                    LOG.info("Zone {} unlocked: its submit is done", j.patch.zone);
                }
                try {
                    // On disk now, for the mirror's round-trip pull (does nothing while saving is off).
                    world.saveAllChunks(true, null);
                } catch (net.minecraft.world.MinecraftException ex) {
                    LOG.warn("Saving after job {} failed: {}", j.id, ex.toString());
                }
            }
            store.writeReport(j);
            BridgeCommand.notifyDone(server, j);
        } else if (now - lastReport > 5000) {
            lastReport = now;
            store.writeReport(j);
        }
    }
}
