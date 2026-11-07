package net.kuina.nebulaemirror.client.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Per-frame renderer for the few legacy block entities whose look is text (roadmark_text, MoarSigns); everything
 * else is meshed with the chunk and never reaches this renderer (see BlockEntityRenderDispatcherMixin).
 */
public final class LegacyTextRenderer implements BlockEntityRenderer<LegacyBlockEntity> {
    public LegacyTextRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(LegacyBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
                       int overlay) {
        Object data = be.getRenderData();
        if (data instanceof RoadmarkText r) {
            r.render(be.getBlockState(), pose, buffers, light);
        } else if (data instanceof MoarSign s) {
            s.render(pose, buffers, light, overlay);
        }
    }

    /** Roadmarks are larger than their block and were drawn regardless of the block's bounds in 1.12. */
    @Override
    public boolean shouldRenderOffScreen(LegacyBlockEntity be) {
        return be.getRenderData() instanceof RoadmarkText;
    }

    /** TileEntityRoadmarkText#getMaxRenderDistanceSquared = 224^2; signs keep the vanilla 64. */
    @Override
    public int getViewDistance() {
        return 224;
    }
}
