package net.kuina.nebulaemirror.mixin.client;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A tab caches its icon on first use; clearing it makes the icon generator run again. */
@Mixin(CreativeModeTab.class)
public interface CreativeModeTabAccessor {
    @Accessor("iconItemStack")
    void nebulaemirror$setIconItemStack(ItemStack stack);
}
