package net.kuina.nebulaemirror.client;

import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/** Emits the prebuilt mesh; vanilla-path quads (for renderers without FRAPI) are derived from the same mesh. */
public final class MeshBakedModel implements BakedModel {
    private final Mesh mesh;
    private final boolean ao;
    private final TextureAtlasSprite particle;
    private final ItemTransforms transforms;
    private List<BakedQuad>[] quadLists;

    MeshBakedModel(Mesh mesh, boolean ao, TextureAtlasSprite particle, ItemTransforms transforms) {
        this.mesh = mesh;
        this.ao = ao;
        this.particle = particle;
        this.transforms = transforms;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos,
                               Supplier<RandomSource> random, RenderContext context) {
        mesh.outputTo(context.getEmitter());
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> random, RenderContext context) {
        mesh.outputTo(context.getEmitter());
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
        if (quadLists == null) {
            quadLists = ModelHelper.toQuadLists(mesh);
        }
        return quadLists[ModelHelper.toFaceIndex(side)];
    }

    @Override
    public boolean useAmbientOcclusion() {
        return ao;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particle;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    /** The prebuilt mesh, for renderers that emit this model's quads into another block's mesh. */
    public Mesh mesh() {
        return mesh;
    }

    public int quadCount() {
        int n = 0;
        for (Direction d : Direction.values()) {
            n += getQuads(null, d, RandomSource.create()).size();
        }
        return n + getQuads(null, null, RandomSource.create()).size();
    }
}
