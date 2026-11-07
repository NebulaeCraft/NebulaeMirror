package net.kuina.nebulaemirror.dumper;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

/** A one-block world: {@link #center} at {@link #POS}, air everywhere else, fixed biome, full light. */
final class FakeWorld implements IBlockAccess {
    static final BlockPos POS = new BlockPos(0, 64, 0);

    IBlockState center = Blocks.AIR.getDefaultState();
    Biome biome = Biomes.PLAINS;

    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return 15 << 20 | 15 << 4;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return POS.equals(pos) ? center : Blocks.AIR.getDefaultState();
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return getBlockState(pos).getMaterial() == Material.AIR;
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return biome;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return false;
    }
}
