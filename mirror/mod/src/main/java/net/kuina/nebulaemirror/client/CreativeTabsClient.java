package net.kuina.nebulaemirror.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.mixin.client.CreativeModeTabAccessor;
import net.kuina.nebulaemirror.mixin.client.CreativeModeTabsAccessor;
import net.kuina.nebulaemirror.tabs.CreativeTabConfig;
import net.kuina.nebulaemirror.tabs.CreativeTabSettings;
import net.minecraft.world.item.CreativeModeTab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Loads config/{@value CreativeTabConfig#FILE} at start and again whenever the file changes (checked once a
 * second); the next time the creative inventory opens it shows the new tabs and icons. A broken file keeps the
 * settings in effect and is reported in the log.
 */
final class CreativeTabsClient {
    private static Path file;
    private static FileTime loaded;
    private static int ticks;

    private CreativeTabsClient() {
    }

    static void install() {
        file = FabricLoader.getInstance().getConfigDir().resolve(CreativeTabConfig.FILE);
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 == 0 && !modified().equals(loaded)) {
                load();
                invalidate();
            }
        });
    }

    private static void load() {
        try {
            CreativeTabConfig c = CreativeTabConfig.load(file, NebulaeMirror.TABS.keySet());
            List<String> problems = CreativeTabSettings.apply(c);
            long hidden = c.creativeTabs.values().stream().filter(t -> !t.show).count();
            NebulaeMirror.LOG.info("Creative tabs from {}: {} hidden, {} custom icon(s)", file.getFileName(), hidden,
                    c.creativeTabs.values().stream().filter(t -> !t.icon.isBlank()).count() - problems.size());
            problems.forEach(p -> NebulaeMirror.LOG.warn("Creative tabs: ignored {}", p));
        } catch (IOException | RuntimeException e) {
            NebulaeMirror.LOG.error("Creative tabs: cannot read {} ({}); keeping the current settings", file,
                    e.getMessage());
        }
        loaded = modified();
    }

    private static FileTime modified() {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static void invalidate() {
        for (CreativeModeTab tab : NebulaeMirror.TABS.values()) {
            ((CreativeModeTabAccessor) tab).nebulaemirror$setIconItemStack(null);
        }
        CreativeModeTabsAccessor.nebulaemirror$setCachedParameters(null);
    }
}
