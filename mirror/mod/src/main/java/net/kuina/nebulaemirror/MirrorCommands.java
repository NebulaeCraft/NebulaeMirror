package net.kuina.nebulaemirror;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.kuina.nebulaemirror.sync.SyncCommands;
import net.kuina.nebulaemirror.writeback.ZoneCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * /nebulaemirror stats <chunkX1> <chunkZ1> <chunkX2> <chunkZ2>
 * Loads the chunk range and tallies blocks per namespace and per block id (non-air), writing
 * nebulaemirror-stats.json into the world directory — used to verify an import against the 1.12 scan.
 * /nebulaemirror sync ...: see {@link SyncCommands}; /nebulaemirror zone ...: see {@link ZoneCommands}.
 * /nebulaemirror chat status: chat sync with the origin (docs/chat.md; configured with the sync config, applied by
 * sync reload).
 */
public final class MirrorCommands {
    private MirrorCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                Commands.literal("nebulaemirror")
                        .then(SyncCommands.tree())
                        .then(ZoneCommands.tree())
                        .then(Commands.literal("chat").then(Commands.literal("status")
                                .requires(MirrorPermissions.require(MirrorPermissions.SYNC_STATUS, 2))
                                .executes(ctx -> {
                                    String m = net.kuina.nebulaemirror.chat.ChatSync.status();
                                    ctx.getSource().sendSuccess(() -> Component.literal(m), false);
                                    return 1;
                                })))
                        .then(Commands.literal("stats").requires(MirrorPermissions.require(MirrorPermissions.STATS, 2))
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(ctx -> stats(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                                        IntegerArgumentType.getInteger(ctx, "z1"),
                                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                                        IntegerArgumentType.getInteger(ctx, "z2"))))))))));
    }

    private static int stats(CommandSourceStack src, int x1, int z1, int x2, int z2) {
        ServerLevel level = src.getServer().overworld();
        Map<String, Long> byNamespace = new TreeMap<>();
        Map<String, Long> byBlock = new HashMap<>();
        long chunks = 0, legacyBe = 0, nonAir = 0;
        for (int cx = Math.min(x1, x2); cx <= Math.max(x1, x2); cx++) {
            for (int cz = Math.min(z1, z2); cz <= Math.max(z1, z2); cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                chunks++;
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section.hasOnlyAir()) {
                        continue;
                    }
                    for (int i = 0; i < 4096; i++) {
                        BlockState s = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                        if (s.isAir()) {
                            continue;
                        }
                        nonAir++;
                        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                        byNamespace.merge(id.substring(0, id.indexOf(':')), 1L, Long::sum);
                        byBlock.merge(id, 1L, Long::sum);
                    }
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof LegacyBlockEntity) {
                        legacyBe++;
                    }
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("chunks", chunks);
        out.addProperty("nonAir", nonAir);
        out.addProperty("legacyBlockEntities", legacyBe);
        JsonObject ns = new JsonObject();
        byNamespace.forEach(ns::addProperty);
        out.add("byNamespace", ns);
        JsonObject blocks = new JsonObject();
        new TreeMap<>(byBlock).forEach(blocks::addProperty);
        out.add("byBlock", blocks);
        Path file = src.getServer().getWorldPath(LevelResource.ROOT).resolve("nebulaemirror-stats.json");
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            src.sendFailure(Component.literal(e.toString()));
        }
        long finalNonAir = nonAir;
        long finalChunks = chunks;
        src.sendSuccess(() -> Component.literal("NebulaeMirror stats: " + finalChunks + " chunks, " + finalNonAir
                + " non-air blocks " + byNamespace + " -> " + file), false);
        return (int) Math.min(Integer.MAX_VALUE, nonAir);
    }
}
