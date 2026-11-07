package net.kuina.nebulaemirror.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/** Archetype 'wall': vanilla WallBlock behaviour (1.21 recomputes neighbour-derived properties) plus extra 1.12 properties. */
public class MirrorWallBlock extends WallBlock implements MirrorBlockHolder {
    private final MirrorBlockSupport support;

    public MirrorWallBlock(Properties properties, MirrorBlockSupport support) {
        super(properties);
        this.support = support;
    }

    @Override
    public MirrorBlockSupport mirrorSupport() {
        return support;
    }

    @Override
    public void mirrorSetDefault(BlockState state) {
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MirrorProps.PENDING.get().toArray(new Property<?>[0]));
    }
}
