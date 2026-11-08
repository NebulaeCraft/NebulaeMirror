package net.kuina.nebulaemirror.mixin.client;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clearing the cached parameters makes the next creative screen rebuild every tab (and Fabric re-paginate). */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {
    @Accessor("CACHED_PARAMETERS")
    static void nebulaemirror$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError();
    }
}
