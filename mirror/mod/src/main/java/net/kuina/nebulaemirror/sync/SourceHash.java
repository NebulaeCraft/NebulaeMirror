package net.kuina.nebulaemirror.sync;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hash of what a 1.12 chunk looks like: block ids/metas, TileEntities and biomes. Everything a 1.12 server rewrites
 * on every save without a visible change (LastUpdate, InhabitedTime, light, height map, entities, scheduled ticks)
 * is left out, so a chunk that was only re-saved hashes the same and is skipped. Compound keys are hashed in sorted
 * order and TileEntities in position order, so the value does not depend on how the 1.12 server ordered them.
 * Never 0 (0 means "nothing recorded" in {@link SyncState}).
 */
public final class SourceHash {
    private SourceHash() {
    }

    public static long of(CompoundTag chunk) {
        CompoundTag level = chunk.getCompound("Level");
        Hasher h = Hashing.murmur3_128().newHasher();
        List<CompoundTag> sections = new ArrayList<>();
        for (Tag t : level.getList("Sections", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            if (!isEmptySection(s)) {
                sections.add(s);
            }
        }
        sections.sort(Comparator.comparingInt(s -> s.getByte("Y")));
        h.putInt(sections.size());
        for (CompoundTag s : sections) {
            h.putByte(s.getByte("Y"));
            putBytes(h, s.getByteArray("Blocks"));
            putBytes(h, s.getByteArray("Data"));
            putBytes(h, s.contains("Add", Tag.TAG_BYTE_ARRAY) ? s.getByteArray("Add") : new byte[0]);
        }
        List<CompoundTag> tes = new ArrayList<>();
        for (Tag t : level.getList("TileEntities", Tag.TAG_COMPOUND)) {
            tes.add((CompoundTag) t);
        }
        tes.sort(Comparator.<CompoundTag>comparingInt(t -> t.getInt("y")).thenComparingInt(t -> t.getInt("z"))
                .thenComparingInt(t -> t.getInt("x")));
        h.putInt(tes.size());
        for (CompoundTag te : tes) {
            putTag(h, te);
        }
        Tag biomes = level.get("Biomes");
        if (biomes != null) {
            putTag(h, biomes);
        } else {
            h.putByte((byte) 0);
        }
        long v = h.hash().asLong();
        return v == 0 ? 1 : v;
    }

    /** A section that holds only air (1.12 normally drops those, but an all-zero one means the same as none). */
    private static boolean isEmptySection(CompoundTag s) {
        for (byte b : s.getByteArray("Blocks")) {
            if (b != 0) {
                return false;
            }
        }
        if (s.contains("Add", Tag.TAG_BYTE_ARRAY)) {
            for (byte b : s.getByteArray("Add")) {
                if (b != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void putBytes(Hasher h, byte[] b) {
        h.putInt(b.length);
        h.putBytes(b);
    }

    /** Canonical hash of any tag: type, then content; compound keys sorted. */
    static void putTag(Hasher h, Tag tag) {
        h.putByte(tag.getId());
        switch (tag) {
            case CompoundTag c -> {
                List<String> keys = new ArrayList<>(c.getAllKeys());
                keys.sort(null);
                h.putInt(keys.size());
                for (String k : keys) {
                    putString(h, k);
                    putTag(h, c.get(k));
                }
            }
            case ByteArrayTag a -> putBytes(h, a.getAsByteArray());
            case IntArrayTag a -> {
                int[] v = a.getAsIntArray();
                h.putInt(v.length);
                for (int i : v) {
                    h.putInt(i);
                }
            }
            case LongArrayTag a -> {
                long[] v = a.getAsLongArray();
                h.putInt(v.length);
                for (long l : v) {
                    h.putLong(l);
                }
            }
            case CollectionTag<?> list -> {
                h.putByte(list.getElementType());
                h.putInt(list.size());
                for (Tag t : list) {
                    putTag(h, t);
                }
            }
            case StringTag s -> putString(h, s.getAsString());
            case NumericTag n -> {
                switch (tag.getId()) {
                    case Tag.TAG_FLOAT -> h.putInt(Float.floatToIntBits(n.getAsFloat()));
                    case Tag.TAG_DOUBLE -> h.putLong(Double.doubleToLongBits(n.getAsDouble()));
                    default -> h.putLong(n.getAsLong());
                }
            }
            default -> putString(h, tag.toString());
        }
    }

    private static void putString(Hasher h, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        putBytes(h, b);
    }
}
