package net.kuina.nebulaemirror.importer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoded "block_states" of one 1.21 chunk section: palette + 4096 indices (index = y << 8 | z << 4 | x).
 * On disk: bits = max(4, ceil(log2(palette))), 64 / bits values per long, no value spans two longs,
 * and no "data" at all for a single-entry palette.
 */
final class PalettedBlocks {
    private final List<CompoundTag> palette = new ArrayList<>();
    private final Map<CompoundTag, Integer> lookup = new HashMap<>();
    private final int[] values = new int[4096];

    PalettedBlocks(CompoundTag blockStates) {
        ListTag list = blockStates.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            palette.add(e);
            lookup.putIfAbsent(e, i);
        }
        if (palette.isEmpty()) {
            CompoundTag air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            palette.add(air);
            lookup.put(air, 0);
        }
        if (palette.size() > 1 && blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
            long[] data = blockStates.getLongArray("data");
            int bits = Math.max(4, Mth.ceillog2(palette.size()));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                int w = i / perLong;
                values[i] = w < data.length ? (int) ((data[w] >>> ((i % perLong) * bits)) & mask) : 0;
            }
        }
    }

    void set(int index, CompoundTag state) {
        Integer id = lookup.get(state);
        if (id == null) {
            id = palette.size();
            palette.add(state);
            lookup.put(state, id);
        }
        values[index] = id;
    }

    CompoundTag get(int index) {
        return palette.get(values[index]);
    }

    /** Re-encodes with only the palette entries still in use. */
    CompoundTag write() {
        int[] remap = new int[palette.size()];
        java.util.Arrays.fill(remap, -1);
        ListTag used = new ListTag();
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int v = values[i];
            if (remap[v] < 0) {
                remap[v] = used.size();
                used.add(palette.get(v));
            }
            out[i] = remap[v];
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", used);
        if (used.size() > 1) {
            int bits = Math.max(4, Mth.ceillog2(used.size()));
            int perLong = 64 / bits;
            long[] data = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) {
                data[i / perLong] |= ((long) out[i]) << ((i % perLong) * bits);
            }
            tag.put("data", new LongArrayTag(data));
        }
        return tag;
    }
}
