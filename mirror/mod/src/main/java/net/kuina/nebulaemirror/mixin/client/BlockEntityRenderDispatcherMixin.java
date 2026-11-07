package net.kuina.nebulaemirror.mixin.client;

import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * All legacy block entities share one type, but only the text-carrying ones (roadmark_text, MoarSigns) have a
 * per-frame renderer. Hiding the renderer from the others keeps the ~3.7 million C&B / ArchitectureCraft block
 * entities out of the chunk's block-entity render lists (vanilla and Sodium both ask getRenderer when meshing).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "getRenderer", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void nebulaemirror$onlyTextLegacy(E blockEntity,
                                                                      CallbackInfoReturnable<BlockEntityRenderer<E>> cir) {
        if (blockEntity instanceof LegacyBlockEntity legacy && !legacy.hasTextRenderer()) {
            cir.setReturnValue(null);
        }
    }
}
