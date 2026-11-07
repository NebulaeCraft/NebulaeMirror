package net.kuina.nebulaemirror.importer;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Any 1.12 (id, meta) of the source world -> a 1.21 block state tag {Name, Properties}.
 * Modded ids go through forward.json; vanilla ids through Minecraft's own 1.12 flattening table and the
 * block-state data fixes after it. Used for blocks embedded in TileEntity data (C&B bits, ArchitectureCraft bases).
 */
public final class StateResolver {
    private static final int FLATTENED_VERSION = 1466; // schema of ChunkPalettedStorageFix / BlockStateData

    private final ForwardTable table;
    private final DataFixer fixer = DataFixers.getDataFixer();
    private final AtomicReferenceArray<CompoundTag> cache = new AtomicReferenceArray<>(4096 * 16);

    public StateResolver(ForwardTable table) {
        this.table = table;
    }

    /** @return the 1.21 state, or null if unmapped (renderers then show a placeholder). Callers must not mutate it. */
    public CompoundTag resolve(int id, int meta) {
        if (id < 0 || id >= 4096 || meta < 0 || meta > 15) {
            return null;
        }
        int key = id << 4 | meta;
        CompoundTag c = cache.get(key);
        if (c == null) {
            c = compute(id, meta);
            cache.compareAndSet(key, null, c == null ? new CompoundTag() : c);
            c = cache.get(key);
        }
        return c.isEmpty() ? null : c;
    }

    /** 1.12 "Block.getStateId": id | meta << 12 (Chisels&Bits palette entries). */
    public CompoundTag resolveStateId(int stateId) {
        return resolve(stateId & 0xFFF, (stateId >> 12) & 0xF);
    }

    public CompoundTag resolve(String name, int meta) {
        Integer id = table.id(name);
        return id == null ? null : resolve(id, meta);
    }

    private CompoundTag compute(int id, int meta) {
        if (id == 0) {
            CompoundTag air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            return air;
        }
        if (table.isModded(id)) {
            ForwardTable.Target t = table.target(id, meta);
            return t == null ? null : t.palette();
        }
        Dynamic<?> flat = BlockStateData.getTag(id << 4 | meta);
        Tag tag = flat.convert(NbtOps.INSTANCE).getValue();
        Dynamic<Tag> upgraded = fixer.update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag),
                FLATTENED_VERSION, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return upgraded.getValue() instanceof CompoundTag ct ? ct : null;
    }
}
