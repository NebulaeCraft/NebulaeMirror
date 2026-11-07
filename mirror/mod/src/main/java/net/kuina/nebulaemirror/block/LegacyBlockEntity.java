package net.kuina.nebulaemirror.block;

import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Carries 1.12 data the mirror cannot interpret, untouched, so write-back restores it exactly:
 * the original TileEntity NBT (Chisels&Bits, ArchitectureCraft, ...) and, for unknown blocks, the original
 * registry name and meta. Synced to clients so P1 renderers can draw from it.
 */
public class LegacyBlockEntity extends BlockEntity {
    public static final String TAG = "Legacy";
    private CompoundTag legacy = new CompoundTag();

    public LegacyBlockEntity(BlockPos pos, BlockState state) {
        super(NebulaeMirror.LEGACY_BLOCK_ENTITY, pos, state);
    }

    public CompoundTag legacy() {
        return legacy;
    }

    public void setLegacy(CompoundTag tag) {
        legacy = tag.copy();
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG, legacy.copy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        legacy = tag.getCompound(TAG).copy();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
