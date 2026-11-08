package net.kuina.nebulaemirror.mixin;

import net.kuina.nebulaemirror.writeback.MirrorEdits;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Light propagation marks chunks unsaved; that is not an edit. */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void nebulaemirror$lightStart(LightLayer layer, int section, CallbackInfo ci) {
        MirrorEdits.enterQuiet();
    }

    @Inject(method = "sectionLightChanged", at = @At("RETURN"))
    private void nebulaemirror$lightEnd(LightLayer layer, int section, CallbackInfo ci) {
        MirrorEdits.exitQuiet();
    }
}
