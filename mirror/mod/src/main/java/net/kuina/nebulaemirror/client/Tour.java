package net.kuina.nebulaemirror.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * -Dnebulaemirror.tour=<file>: in a singleplayer world, visit each "name x y z yaw pitch" line as a spectator,
 * wait for chunks to render, save screenshots/<name>.png, then quit. Used to eyeball imported worlds.
 * Two more kinds of line: "creative name page" opens the creative inventory on that tab page (0: vanilla tabs)
 * and screenshots it; "file name source target" copies source over target (relative to the game directory),
 * e.g. to change a config while the game runs. "cmd name command..." runs a server command as the player
 * (e.g. "cmd checkout nm zone checkout t1 0 60 0 16 80 16").
 */
final class Tour {
    private record Stop(String name, double x, double y, double z, float yaw, float pitch, int page,
                        String copyFrom, String copyTo, String command) {
        boolean screenshot() {
            return copyFrom == null && command == null;
        }
    }

    private static final int SETTLE_TICKS = 200;
    private static List<Stop> stops;
    private static int index = -1;
    private static int wait;
    private static Runnable then;

    private Tour() {
    }

    static void install(Path file) {
        try {
            stops = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                String[] p = line.trim().split("\\s+");
                if (line.startsWith("#")) {
                    continue;
                }
                if (p.length >= 3 && p[0].equals("cmd")) {
                    stops.add(new Stop(p[1], 0, 0, 0, 0, 0, -1, null, null,
                            String.join(" ", List.of(p).subList(2, p.length))));
                } else if (p.length == 6) {
                    stops.add(new Stop(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Float.parseFloat(p[4]), Float.parseFloat(p[5]), -1, null, null, null));
                } else if (p.length == 3 && p[0].equals("creative")) {
                    stops.add(new Stop(p[1], 0, 0, 0, 0, 0, Integer.parseInt(p[2]), null, null, null));
                } else if (p.length == 4 && p[0].equals("file")) {
                    stops.add(new Stop(p[1], 0, 0, 0, 0, 0, -1, p[2], p[3], null));
                }
            }
        } catch (IOException e) {
            NebulaeMirror.LOG.error("Cannot read tour file {}", file, e);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(Tour::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.getOverlay() != null) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (then != null) {
            Runnable r = then;
            then = null;
            r.run();
            return;
        }
        if (index >= 0 && stops.get(index).screenshot()) {
            Stop s = stops.get(index);
            Screenshot.grab(mc.gameDirectory, s.name() + ".png", mc.getMainRenderTarget(),
                    msg -> NebulaeMirror.LOG.info("Tour {}: {}", s.name(), msg.getString()));
        }
        index++;
        if (index >= stops.size()) {
            NebulaeMirror.LOG.info("Tour finished: {}", net.kuina.nebulaemirror.client.legacy.LegacyRenders.stats());
            mc.stop();
            return;
        }
        Stop s = stops.get(index);
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        if (s.command() != null) {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4), s.command());
            });
            NebulaeMirror.LOG.info("Tour {}: ran /{}", s.name(), s.command());
            wait = 40;
            return;
        }
        if (s.copyFrom() != null) {
            try {
                Files.copy(mc.gameDirectory.toPath().resolve(s.copyFrom()), mc.gameDirectory.toPath().resolve(s.copyTo()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                NebulaeMirror.LOG.info("Tour {}: copied {} to {}", s.name(), s.copyFrom(), s.copyTo());
            } catch (IOException e) {
                NebulaeMirror.LOG.error("Tour {}: copy failed", s.name(), e);
            }
            wait = 60; // file watchers poll once a second
            return;
        }
        if (s.page() >= 0) {
            mc.options.hideGui = false;
            mc.getSingleplayerServer().execute(() ->
                    mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).setGameMode(GameType.CREATIVE));
            wait = 20;
            then = () -> {
                net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen =
                        new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player,
                                mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get());
                mc.setScreen(screen);
                var pages = (net.fabricmc.fabric.api.client.itemgroup.v1.FabricCreativeInventoryScreen) screen;
                pages.switchToPage(s.page()); // false when already there
                boolean ok = pages.getCurrentPage() == s.page();
                List<String> tabs = new ArrayList<>();
                for (var tab : pages.getItemGroupsOnPage(s.page())) {
                    var icon = tab.getIconItem();
                    var state = icon.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
                    tabs.add(tab.getDisplayName().getString() + " = "
                            + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(icon.getItem())
                            + (state == null || state.isEmpty() ? "" : state.properties().toString()));
                }
                NebulaeMirror.LOG.info("Tour {}: creative page {}{} of {}, {} items in search: {}", s.name(), s.page(),
                        ok ? "" : " (missing)", pages.getPageCount(),
                        net.minecraft.world.item.CreativeModeTabs.searchTab().getDisplayItems().size(), tabs);
                wait = 40;
            };
            return;
        }
        mc.options.hideGui = true;
        mc.getSingleplayerServer().execute(() -> {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
            sp.setGameMode(GameType.SPECTATOR);
            sp.teleportTo(mc.getSingleplayerServer().overworld(), s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch());
        });
        wait = SETTLE_TICKS;
    }
}
