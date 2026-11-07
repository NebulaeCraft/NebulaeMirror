package net.kuina.nebulaemirror.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Generic mirror block whose 1.12 counterpart had a TileEntity; its NBT is kept verbatim in a LegacyBlockEntity. */
public class MirrorEntityBlock extends MirrorBlock implements EntityBlock {
    public MirrorEntityBlock(Properties properties, MirrorBlockSupport support) {
        super(properties, support);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LegacyBlockEntity(pos, state);
    }
}
