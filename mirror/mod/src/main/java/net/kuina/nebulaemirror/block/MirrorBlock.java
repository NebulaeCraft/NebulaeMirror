package net.kuina.nebulaemirror.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Generic mirror block: the 1.12 block's properties verbatim, per-state shapes from the dump.
 * Direction and axis properties take part in rotate/mirror so WorldEdit/Axiom transforms work.
 */
public class MirrorBlock extends Block implements MirrorBlockHolder {
    private final MirrorBlockSupport support;

    public MirrorBlock(Properties properties, MirrorBlockSupport support) {
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
        builder.add(MirrorProps.PENDING.get().toArray(new Property<?>[0]));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return support.shape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return support.collision(state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState();
        for (Property<?> p : state.getProperties()) {
            if (p instanceof DirectionProperty dp) {
                // 1.12 mods (MCreator in particular) face the block towards the player.
                Direction d = dp.getPossibleValues().contains(Direction.UP)
                        ? ctx.getNearestLookingDirection().getOpposite()
                        : ctx.getHorizontalDirection().getOpposite();
                if (dp.getPossibleValues().contains(d)) {
                    state = state.setValue(dp, d);
                }
            } else if (p instanceof EnumProperty<?> ep && ep.getValueClass() == Direction.Axis.class) {
                state = MirrorProps.with(state, ep, ctx.getClickedFace().getAxis().getName());
            }
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return transform(state, rotation, null);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return transform(state, null, mirror);
    }

    static BlockState transform(BlockState state, Rotation rotation, Mirror mirror) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof DirectionProperty dp) {
                Direction d = state.getValue(dp);
                Direction n = rotation != null ? rotation.rotate(d) : mirror.mirror(d);
                if (dp.getPossibleValues().contains(n)) {
                    state = state.setValue(dp, n);
                }
            } else if (p instanceof EnumProperty<?> ep && ep.getValueClass() == Direction.Axis.class
                    && rotation != null && (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)) {
                String axis = MirrorProps.valueName(state, ep);
                state = MirrorProps.with(state, ep, axis.equals("x") ? "z" : axis.equals("z") ? "x" : axis);
            }
        }
        return state;
    }
}
