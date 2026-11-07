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
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Carries 1.12 data the mirror cannot interpret, untouched, so write-back restores it exactly:
 * "Legacy" = {Name, Meta, TileEntity?} (original registry name, meta and TileEntity NBT).
 * "Render" is derived at import (1.12 ids translated to 1.21 states) and only feeds client renderers.
 * Both are synced to clients; renderers read them through Fabric's block-entity render data.
 */
public class LegacyBlockEntity extends BlockEntity {
    public static final String TAG = "Legacy";
    public static final String RENDER_TAG = "Render";

    /** Client hooks, installed by the client initializer (kept as plain functions so this class stays common). */
    public static volatile Function<LegacyBlockEntity, Object> renderFactory = be -> null;
    public static volatile Consumer<LegacyBlockEntity> clientChanged = be -> { };

    private CompoundTag legacy = new CompoundTag();
    private CompoundTag render = new CompoundTag();
    private volatile Object renderData;
    private volatile boolean renderDataBuilt;

    public LegacyBlockEntity(BlockPos pos, BlockState state) {
        super(NebulaeMirror.LEGACY_BLOCK_ENTITY, pos, state);
    }

    public CompoundTag legacy() {
        return legacy;
    }

    public CompoundTag render() {
        return render;
    }

    public void setLegacy(CompoundTag tag) {
        legacy = tag.copy();
        invalidateRender();
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG, legacy.copy());
        if (!render.isEmpty()) {
            tag.put(RENDER_TAG, render.copy());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        legacy = tag.getCompound(TAG).copy();
        render = tag.getCompound(RENDER_TAG).copy();
        invalidateRender();
        if (level != null && level.isClientSide) {
            clientChanged.accept(this);
        }
    }

    private void invalidateRender() {
        renderDataBuilt = false;
        renderData = null;
    }

    /** Fabric block-view render data: a decoded, immutable view built once per data change (client only). */
    @Nullable
    @Override
    public Object getRenderData() {
        if (!renderDataBuilt) {
            renderData = renderFactory.apply(this);
            renderDataBuilt = true;
        }
        return renderData;
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
