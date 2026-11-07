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
 */
final class Tour {
    private record Stop(String name, double x, double y, double z, float yaw, float pitch) {
    }

    private static final int SETTLE_TICKS = 200;
    private static List<Stop> stops;
    private static int index = -1;
    private static int wait;

    private Tour() {
    }

    static void install(Path file) {
        try {
            stops = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 6 && !line.startsWith("#")) {
                    stops.add(new Stop(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Float.parseFloat(p[4]), Float.parseFloat(p[5])));
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
        if (index >= 0) {
            Stop s = stops.get(index);
            Screenshot.grab(mc.gameDirectory, s.name() + ".png", mc.getMainRenderTarget(),
                    msg -> NebulaeMirror.LOG.info("Tour {}: {}", s.name(), msg.getString()));
        }
        index++;
        if (index >= stops.size()) {
            mc.stop();
            return;
        }
        Stop s = stops.get(index);
        mc.options.hideGui = true;
        mc.getSingleplayerServer().execute(() -> {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
            sp.setGameMode(GameType.SPECTATOR);
            sp.teleportTo(mc.getSingleplayerServer().overworld(), s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch());
        });
        wait = SETTLE_TICKS;
    }
}
