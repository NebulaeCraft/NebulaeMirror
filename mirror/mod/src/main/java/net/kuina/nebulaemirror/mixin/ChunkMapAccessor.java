package net.kuina.nebulaemirror.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the sync tell whether the server holds a chunk in memory in any form (loading, loaded or unloading). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> nebulaemirror$pendingUnloads();

    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder nebulaemirror$updatingChunk(long pos);

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder nebulaemirror$visibleChunk(long pos);
}
