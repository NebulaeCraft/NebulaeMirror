package net.kuina.nebulaemirror.client.legacy;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/** Wraps the static model of blocks that carry 1.12 TileEntity data; the data decides what is drawn. */
public final class LegacyModel extends ForwardingBakedModel {
    public LegacyModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos,
                               Supplier<RandomSource> random, RenderContext context) {
        Object data = level.getBlockEntityRenderData(pos);
        if (data instanceof LegacyRender r && r.emit(level, state, pos, context.getEmitter())) {
            return;
        }
        super.emitBlockQuads(level, state, pos, random, context);
    }
}
