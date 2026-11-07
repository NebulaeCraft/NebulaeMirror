package net.kuina.nebulaemirror.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Placeholder for a 1.12 (name, meta) the mirror has no mapping for (new or removed blocks, undecodable metas).
 * The LegacyBlockEntity holds {Name, Meta, TileEntity?}; write-back restores exactly that.
 */
public class UnknownBlock extends Block implements EntityBlock {
    public UnknownBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LegacyBlockEntity(pos, state);
    }
}
