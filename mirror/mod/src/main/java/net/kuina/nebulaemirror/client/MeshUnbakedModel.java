package net.kuina.nebulaemirror.client;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Bakes a generator mesh through the Fabric Renderer API (per-quad blend mode, emissive, diffuse, AO). */
public final class MeshUnbakedModel implements UnbakedModel {
    static final ResourceLocation BLOCK_PARENT = ResourceLocation.withDefaultNamespace("block/block");
    private static final ResourceLocation MISSING = ResourceLocation.fromNamespaceAndPath("nebulaemirror", "block/missing");

    private final MeshData mesh;

    public MeshUnbakedModel(MeshData mesh) {
        this.mesh = mesh;
    }

    @Override
    public Collection<ResourceLocation> getDependencies() {
        return List.of(BLOCK_PARENT);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) {
        resolver.apply(BLOCK_PARENT).resolveParents(resolver);
    }

    @Nullable
    @Override
    public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state) {
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        MeshBuilder builder = renderer.meshBuilder();
        QuadEmitter emitter = builder.getEmitter();
        MaterialFinder finder = renderer.materialFinder();
        for (MeshData.Quad q : mesh.quads()) {
            TextureAtlasSprite sprite = sprite(sprites, q.sprite());
            for (int i = 0; i < 4; i++) {
                float[] v = q.vertices()[i];
                emitter.pos(i, v[0], v[1], v[2]);
                emitter.uv(i, sprite.getU(v[3] / 16f), sprite.getV(v[4] / 16f));
                emitter.color(i, q.color() == null ? -1 : q.color()[i]);
            }
            emitter.cullFace(q.cull() == null ? null : Direction.byName(q.cull()));
            if (q.face() != null) {
                emitter.nominalFace(Direction.byName(q.face()));
            }
            emitter.colorIndex(q.tint());
            emitter.material(material(finder, q));
            emitter.emit();
        }
        TextureAtlasSprite particle = sprite(sprites, mesh.particle());
        ItemTransforms transforms = ItemTransforms.NO_TRANSFORMS;
        UnbakedModel parent = baker.getModel(BLOCK_PARENT);
        if (parent instanceof BlockModel bm) {
            transforms = bm.getTransforms();
        }
        return new MeshBakedModel(builder.build(), mesh.ao(), particle, transforms);
    }

    private static RenderMaterial material(MaterialFinder finder, MeshData.Quad q) {
        finder.clear();
        finder.blendMode(switch (q.layer()) {
            case "translucent" -> BlendMode.TRANSLUCENT;
            case "cutout" -> BlendMode.CUTOUT;
            case "cutout_mipped" -> BlendMode.CUTOUT_MIPPED;
            default -> BlendMode.SOLID;
        });
        if (q.emissive()) {
            // NebulaeCraft 2.26 glow faces: full-bright, no shading, no AO.
            finder.emissive(true).disableDiffuse(true).ambientOcclusion(TriState.FALSE);
        } else if (!q.shade()) {
            finder.disableDiffuse(true);
        }
        return finder.find();
    }

    private static TextureAtlasSprite sprite(Function<Material, TextureAtlasSprite> sprites, String id) {
        ResourceLocation rl = id == null ? MISSING : ResourceLocation.tryParse(id);
        return sprites.apply(new Material(TextureAtlas.LOCATION_BLOCKS, rl == null ? MISSING : rl));
    }
}
