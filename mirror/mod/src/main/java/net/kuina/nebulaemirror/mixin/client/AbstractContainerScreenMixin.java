package net.kuina.nebulaemirror.mixin.client;

import net.kuina.nebulaemirror.client.SlotMarks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every container screen (creative inventory, player inventory, chests, ...) draws its slots here. Filling the
 * slot first puts the colour over the screen's background and under the item, its count and the hover highlight.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "renderSlot", at = @At("HEAD"))
    private void nebulaemirror$markSlot(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
        int color = SlotMarks.color(slot.getItem());
        if (color != 0) {
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }
}
