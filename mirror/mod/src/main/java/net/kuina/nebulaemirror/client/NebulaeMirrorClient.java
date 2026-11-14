package net.kuina.nebulaemirror.client;

import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.kuina.nebulaemirror.client.legacy.LegacyModel;
import net.kuina.nebulaemirror.client.legacy.LegacyRenders;
import net.kuina.nebulaemirror.client.legacy.OutfittedTrack;
import net.kuina.nebulaemirror.block.MirrorBlockHolder;
import net.kuina.nebulaemirror.block.MirrorBlockSupport;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class NebulaeMirrorClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        for (Map.Entry<Block, MirrorData.BlockDef> e : NebulaeMirror.BLOCKS.entrySet()) {
            Block block = e.getKey();
            MirrorData.BlockDef def = e.getValue();
            BlockRenderLayerMap.INSTANCE.putBlock(block, switch (def.renderLayer()) {
                case "translucent" -> RenderType.translucent();
                case "cutout" -> RenderType.cutout();
                case "cutout_mipped" -> RenderType.cutoutMipped();
                default -> RenderType.solid();
            });
            if (!def.tints().isEmpty()) {
                MirrorBlockSupport support = ((MirrorBlockHolder) block).mirrorSupport();
                BlockColor color = (state, level, pos, tint) -> {
                    MirrorData.Tint t = def.tints().get(tint);
                    if (t == null) {
                        return -1;
                    }
                    return switch (t.kind()) {
                        case "state" -> stateTint(support, state, tint, t.color());
                        case "grass" -> level != null && pos != null ? BiomeColors.getAverageGrassColor(level, pos)
                                : GrassColor.getDefaultColor();
                        case "foliage" -> level != null && pos != null ? BiomeColors.getAverageFoliageColor(level, pos)
                                : FoliageColor.getDefaultColor();
                        default -> t.color();
                    };
                };
                ColorProviderRegistry.BLOCK.register(color, block);
                // 1.21 item tints are ARGB; an alpha of 0 would make the icon invisible.
                ColorProviderRegistry.ITEM.register((stack, tint) -> {
                    MirrorData.Tint t = def.tints().get(tint);
                    if (t == null) {
                        return -1;
                    }
                    int rgb = switch (t.kind()) {
                        case "state" -> {
                            BlockItemStateProperties bs = stack.getOrDefault(DataComponents.BLOCK_STATE,
                                    BlockItemStateProperties.EMPTY);
                            yield stateTint(support, bs.apply(block.defaultBlockState()), tint, t.color());
                        }
                        case "grass" -> GrassColor.getDefaultColor();
                        case "foliage" -> FoliageColor.getDefaultColor();
                        default -> t.color();
                    };
                    return 0xFF000000 | rgb;
                }, block);
            }
        }

        // Blocks carrying 1.12 TileEntity data (C&B, ArchitectureCraft, ...) draw from that data when they can.
        LegacyBlockEntity.renderFactory = LegacyRenders::create;
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(NebulaeMirror.LEGACY_BLOCK_ENTITY,
                net.kuina.nebulaemirror.client.legacy.LegacyTextRenderer::new);
        LegacyBlockEntity.clientChanged = be -> {
            if (be.getLevel() != null) {
                be.getLevel().sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 8);
            }
        };
        java.util.Set<ResourceLocation> legacyBlocks = new java.util.HashSet<>();
        for (Map.Entry<Block, MirrorData.BlockDef> e : NebulaeMirror.BLOCKS.entrySet()) {
            if (e.getValue().legacyBlockEntity()) {
                legacyBlocks.add(ResourceLocation.parse(e.getValue().id()));
            }
        }

        PreparableModelLoadingPlugin.register(NebulaeMirrorClient::loadAssets, (assets, ctx) -> {
            Map<ResourceLocation, MeshData> meshes = assets.meshes();
            // Railcraft outfitted-track kit meshes are not referenced by any blockstate: bake them as extra models.
            OutfittedTrack.setTable(assets.tracks());
            ctx.addModels(new java.util.HashSet<>(assets.tracks().values()));
            ctx.modifyModelAfterBake().register((model, context) -> {
                var top = context.topLevelId();
                if (model != null && top != null && !"inventory".equals(top.variant()) && legacyBlocks.contains(top.id())) {
                    return new LegacyModel(model);
                }
                return model;
            });
            Map<String, String> itemMeshes = NebulaeMirror.DATA.itemMeshes;
            ctx.resolveModel().register(context -> {
                ResourceLocation id = context.id();
                if (NebulaeMirror.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("mesh/")) {
                    MeshData m = meshes.get(id);
                    return m == null ? null : new MeshUnbakedModel(m);
                }
                if (id.getPath().startsWith("item/")) {
                    String itemId = id.getNamespace() + ":" + id.getPath().substring("item/".length());
                    String meshId = itemMeshes.get(itemId);
                    if (meshId != null) {
                        MeshData m = meshes.get(ResourceLocation.parse(meshId));
                        return m == null ? null : new MeshUnbakedModel(m);
                    }
                }
                return null;
            });
        });

        CreativeTabsClient.install();
        ZoneOutlineRenderer.install();
        if (Boolean.getBoolean("nebulaemirror.selftest")) {
            SelfTest.install();
        }
        String tour = System.getProperty("nebulaemirror.tour");
        if (tour != null && !tour.isBlank()) {
            Tour.install(java.nio.file.Path.of(tour));
        }
    }

    private static int stateTint(MirrorBlockSupport support, BlockState state, int tint, int fallback) {
        MirrorData.StateInfo si = support.lookup(state);
        Integer c = si == null ? null : si.tints().get(tint);
        return c == null ? fallback : c;
    }

    /** Generator assets the model loading plugin needs: meshes, and the outfitted-track key -> mesh table. */
    private record ClientAssets(Map<ResourceLocation, MeshData> meshes, Map<String, ResourceLocation> tracks) {
    }

    private static CompletableFuture<ClientAssets> loadAssets(ResourceManager manager,
                                                             java.util.concurrent.Executor executor) {
        return loadMeshes(manager, executor).thenApply(meshes -> {
            Map<String, ResourceLocation> tracks = Map.of();
            var res = manager.getResource(NebulaeMirror.id(OutfittedTrack.TABLE));
            if (res.isPresent()) {
                try (Reader r = res.get().openAsReader()) {
                    tracks = OutfittedTrack.parseTable(JsonParser.parseReader(r).getAsJsonObject());
                } catch (Exception ex) {
                    NebulaeMirror.LOG.error("Bad {}: {}", OutfittedTrack.TABLE, ex.toString());
                }
            }
            // Drop entries whose mesh is missing, so addModels never asks for a model nobody resolves.
            Map<String, ResourceLocation> known = new HashMap<>();
            tracks.forEach((k, id) -> {
                if (meshes.containsKey(id)) {
                    known.put(k, id);
                }
            });
            NebulaeMirror.LOG.info("Loaded {} outfitted track variants", known.size());
            return new ClientAssets(meshes, known);
        });
    }

    /** Parses every assets/nebulaemirror/meshes/*.json off-thread during resource reload. */
    private static CompletableFuture<Map<ResourceLocation, MeshData>> loadMeshes(ResourceManager manager,
                                                                               java.util.concurrent.Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            Map<ResourceLocation, MeshData> out = new HashMap<>();
            Map<ResourceLocation, Resource> found = manager.listResources("meshes",
                    rl -> NebulaeMirror.MOD_ID.equals(rl.getNamespace()) && rl.getPath().endsWith(".json"));
            for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
                String path = e.getKey().getPath();
                String hash = path.substring("meshes/".length(), path.length() - ".json".length());
                try (Reader r = e.getValue().openAsReader()) {
                    out.put(NebulaeMirror.id("mesh/" + hash), MeshData.parse(JsonParser.parseReader(r).getAsJsonObject()));
                } catch (Exception ex) {
                    NebulaeMirror.LOG.error("Bad mesh {}: {}", e.getKey(), ex.toString());
                }
            }
            NebulaeMirror.LOG.info("Loaded {} meshes", out.size());
            return out;
        }, executor);
    }
}
