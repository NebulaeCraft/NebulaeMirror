package net.kuina.nebulaemirror.client.legacy;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Decoded render data of one legacy block entity; emits quads during chunk meshing (worker threads). */
public interface LegacyRender {
    /** @return true if the static block model must not be drawn as well. */
    boolean emit(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter);
}
