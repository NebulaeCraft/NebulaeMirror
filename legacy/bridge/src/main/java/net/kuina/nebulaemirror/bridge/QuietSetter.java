package net.kuina.nebulaemirror.bridge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes blocks straight into the chunk sections, without the callbacks {@code World/Chunk.setBlockState} runs:
 * no {@code breakBlock} (a replaced chest does not drop its contents, a removed log does not make leaves decay),
 * no {@code onBlockAdded} (sand does not fall, TNT is not primed, water does not start flowing), no neighbour
 * updates and no Sponge/FAWE events. What those callbacks do that is still needed is done here: old TileEntity
 * removed, new one created from the patch, height map and light fixed per batch ({@link #finish}),
 * chunk marked for saving, and clients sent the change.
 */
final class QuietSetter {
    private final WorldServer world;
    private final Set<Chunk> chunks = new LinkedHashSet<>();
    private final List<BlockPos> relight = new ArrayList<>();
    int tileEntityFailures;

    QuietSetter(WorldServer world) {
        this.world = world;
    }

    /** Returns false if nothing changed. {@code te} must already carry x/y/z. */
    boolean set(BlockPos pos, IBlockState state, NBTTagCompound te) {
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        IBlockState old = chunk.getBlockState(pos);
        boolean changed = old != state;
        if (old.getBlock().hasTileEntity(old) && (changed || te != null)) {
            world.removeTileEntity(pos); // before the block goes, so nothing reacts to (or drops) its inventory
        }
        if (changed) {
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            int sy = pos.getY() >> 4;
            ExtendedBlockStorage section = sections[sy];
            if (section == Chunk.NULL_BLOCK_STORAGE) {
                if (state.getBlock() == Blocks.AIR) {
                    return te != null;
                }
                section = new ExtendedBlockStorage(sy << 4, world.provider.hasSkyLight());
                sections[sy] = section;
            }
            section.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
            if (old.getLightOpacity(world, pos) != state.getLightOpacity(world, pos)
                    || old.getLightValue(world, pos) != state.getLightValue(world, pos)) {
                relight.add(pos);
            }
        }
        if (te != null) {
            TileEntity created = TileEntity.create(world, te);
            if (created != null && state.getBlock().hasTileEntity(state)) {
                world.setTileEntity(pos, created);
            } else {
                tileEntityFailures++;
            }
        }
        if (changed || te != null) {
            chunk.markDirty();
            chunks.add(chunk);
            world.getPlayerChunkMap().markBlockForUpdate(pos);
            return true;
        }
        return false;
    }

    /** Height maps and light for everything set since the last call (once per tick slice). */
    void finish() {
        for (Chunk c : chunks) {
            c.generateSkylightMap();
        }
        for (BlockPos p : relight) {
            world.checkLight(p);
        }
        chunks.clear();
        relight.clear();
    }
}
