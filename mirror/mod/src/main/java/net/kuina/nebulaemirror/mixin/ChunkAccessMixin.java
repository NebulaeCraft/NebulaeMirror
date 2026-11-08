package net.kuina.nebulaemirror.mixin;

import net.kuina.nebulaemirror.writeback.MirrorEdits;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chunks marked for saving changed (block entities, direct section writers such as Axiom); see MirrorEdits. */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
    @Inject(method = "setUnsaved", at = @At("HEAD"))
    private void nebulaemirror$unsaved(boolean unsaved, CallbackInfo ci) {
        if (unsaved) {
            MirrorEdits.changed((ChunkAccess) (Object) this);
        }
    }

    /** Light becoming correct (chunk load, relight) is not an edit. */
    @Inject(method = "setLightCorrect", at = @At("HEAD"))
    private void nebulaemirror$lightStart(boolean correct, CallbackInfo ci) {
        MirrorEdits.enterQuiet();
    }

    @Inject(method = "setLightCorrect", at = @At("RETURN"))
    private void nebulaemirror$lightEnd(boolean correct, CallbackInfo ci) {
        MirrorEdits.exitQuiet();
    }
}
