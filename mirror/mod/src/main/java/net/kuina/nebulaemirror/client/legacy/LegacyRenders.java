package net.kuina.nebulaemirror.client.legacy;

import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.kuina.nebulaemirror.legacy.ChiselsBlob;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Builds the client-side render object of a legacy block entity from its "Legacy" + "Render" tags. */
public final class LegacyRenders {
    private LegacyRenders() {
    }

    public static String stats() {
        return ChiselsRender.stats() + "; " + ArchitectureRender.stats();
    }

    public static Object create(LegacyBlockEntity be) {
        CompoundTag render = be.render();
        CompoundTag te = be.legacy().getCompound("TileEntity");
        try {
            return switch (render.getString("Kind")) {
                case "cb" -> chiselsAndBits(te, render);
                case "ac" -> architectureCraft(te, render);
                default -> null;
            };
        } catch (Exception e) {
            NebulaeMirror.LOG.debug("No legacy render for {}: {}", be.getBlockPos(), e.toString());
            return null;
        }
    }

    private static Object architectureCraft(CompoundTag te, CompoundTag render) {
        if (!render.contains("Base", Tag.TAG_COMPOUND)) {
            return null;
        }
        BlockState base = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), render.getCompound("Base"));
        BlockState second = render.contains("Second", Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), render.getCompound("Second")) : null;
        return new ArchitectureRender(te.getInt("Shape"), te.getByte("side"), te.getByte("turn"),
                te.getByte("offsetX"), base, second);
    }

    private static Object chiselsAndBits(CompoundTag te, CompoundTag render) throws Exception {
        ChiselsBlob blob = ChiselsBlob.decode(te.getByteArray("X"));
        if (blob == null) {
            return null;
        }
        Map<Integer, BlockState> byStateId = new HashMap<>();
        ListTag states = render.getList("States", Tag.TAG_COMPOUND);
        for (int i = 0; i < states.size(); i++) {
            CompoundTag e = states.getCompound(i);
            BlockState s = e.contains("State", Tag.TAG_COMPOUND)
                    ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), e.getCompound("State"))
                    : NebulaeMirror.UNKNOWN.defaultBlockState();
            byStateId.put(e.getInt("Id"), s);
        }
        BlockState[] palette = new BlockState[blob.palette.length];
        for (int i = 0; i < palette.length; i++) {
            int id = blob.palette[i];
            palette[i] = id == 0 ? Blocks.AIR.defaultBlockState()
                    : byStateId.getOrDefault(id, NebulaeMirror.UNKNOWN.defaultBlockState());
        }
        return new ChiselsRender(blob.voxels, palette);
    }
}
