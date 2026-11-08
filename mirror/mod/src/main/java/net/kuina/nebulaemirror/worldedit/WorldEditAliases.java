package net.kuina.nebulaemirror.worldedit;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

/**
 * FAWE-style short WorldEdit commands (//s → //set, //r → //replace, //c → //copy, ...) on the mirror's
 * WorldEdit; the list is config/{@value AliasConfig#FILE} (docs/worldedit-aliases.md). Registered in a phase
 * after the default one, where WorldEdit registers its commands, so targets exist and real commands win.
 * Permissions, zone limits and history are WorldEdit's own: the alias just runs the target command.
 */
public final class WorldEditAliases {
    private static final ResourceLocation PHASE = NebulaeMirror.id("worldedit_aliases");

    private WorldEditAliases() {
    }

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("worldedit")) {
            return;
        }
        CommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        CommandRegistrationCallback.EVENT.register(PHASE, (dispatcher, registry, env) -> {
            AliasConfig config;
            try {
                config = AliasConfig.load(FabricLoader.getInstance().getConfigDir());
            } catch (IOException e) {
                NebulaeMirror.LOG.error("WorldEdit aliases: {}; using the defaults", e.getMessage());
                config = new AliasConfig();
            }
            if (!config.enabled) {
                return;
            }
            CommandAliases.Result r = CommandAliases.install(dispatcher, config.aliases);
            NebulaeMirror.LOG.info("WorldEdit aliases: {}", r.registered());
            if (!r.skipped().isEmpty()) {
                NebulaeMirror.LOG.warn("WorldEdit aliases skipped: {}", r.skipped());
            }
        });
    }
}
