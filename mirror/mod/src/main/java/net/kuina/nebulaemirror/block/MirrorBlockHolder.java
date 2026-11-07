package net.kuina.nebulaemirror.block;

import net.minecraft.world.level.block.state.BlockState;

/** Implemented by every block the mirror registers from blocks.json. */
public interface MirrorBlockHolder {
    MirrorBlockSupport mirrorSupport();

    /** Exposes the protected Block#registerDefaultState to the registrar. */
    void mirrorSetDefault(BlockState state);
}
