package net.kuina.nebulaemirror.mixin;

import net.kuina.nebulaemirror.writeback.MirrorEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block change of a full chunk (it sets the unsaved field directly, so setUnsaved does not see it). */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void nebulaemirror$changed(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            MirrorEdits.changed((LevelChunk) (Object) this);
        }
    }

    /** UpgradeData and post-processing when a chunk becomes ticking are the mirror's own fix-ups, not edits. */
    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void nebulaemirror$postStart(CallbackInfo ci) {
        MirrorEdits.enterQuiet();
    }

    @Inject(method = "postProcessGeneration", at = @At("RETURN"))
    private void nebulaemirror$postEnd(CallbackInfo ci) {
        MirrorEdits.exitQuiet();
    }
}
