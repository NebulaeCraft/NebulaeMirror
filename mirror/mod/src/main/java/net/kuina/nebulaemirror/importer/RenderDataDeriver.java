package net.kuina.nebulaemirror.importer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

/**
 * Derived, client-facing render data stored next to the verbatim 1.12 data in a legacy block entity ("Render").
 * It only translates world-specific numeric ids into 1.21 states; it is never written back to 1.12 and can be
 * regenerated at any time.
 * <ul>
 *   <li>Chisels&Bits: {Kind:"cb", States:[{Id:int, State:{Name,Properties}}...]} for the blob's palette</li>
 *   <li>ArchitectureCraft shape: {Kind:"ac", Base:{...}, Second:{...}?}</li>
 * </ul>
 */
public final class RenderDataDeriver {
    public static final String CB_TE = "minecraft:mod.chiselsandbits.tileentitychiseled";
    public static final String AC_TE = "architecturecraft:shape";

    private RenderDataDeriver() {
    }

    public static CompoundTag derive(CompoundTag te, StateResolver resolver) {
        if (te == null) {
            return null;
        }
        String id = te.getString("id");
        try {
            if (CB_TE.equals(id)) {
                return chiselsAndBits(te, resolver);
            }
            if (AC_TE.equals(id)) {
                return architectureCraft(te, resolver);
            }
        } catch (IOException | RuntimeException e) {
            return null; // undecodable: the block keeps its static model
        }
        return null;
    }

    private static CompoundTag chiselsAndBits(CompoundTag te, StateResolver resolver) throws IOException {
        int[] palette = cbPalette(te.getByteArray("X"));
        if (palette == null) {
            return null;
        }
        ListTag states = new ListTag();
        for (int stateId : palette) {
            if (stateId == 0) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putInt("Id", stateId);
            CompoundTag s = resolver.resolveStateId(stateId);
            if (s != null) {
                e.put("State", s.copy());
            }
            states.add(e);
        }
        CompoundTag out = new CompoundTag();
        out.putString("Kind", "cb");
        out.put("States", states);
        return out;
    }

    private static CompoundTag architectureCraft(CompoundTag te, StateResolver resolver) {
        CompoundTag out = new CompoundTag();
        out.putString("Kind", "ac");
        CompoundTag base = resolver.resolve(te.getString("BaseName"), te.getInt("BaseData"));
        if (base != null) {
            out.put("Base", base.copy());
        }
        String second = te.getString("Name2");
        if (!second.isEmpty() && !"minecraft:air".equals(second)) {
            CompoundTag s = resolver.resolve(second, te.getInt("Data2"));
            if (s != null) {
                out.put("Second", s.copy());
            }
        }
        return out;
    }

    /** Palette (1.12 state ids) of a Chisels&Bits 1.12 compact blob (version 0); null for other versions. */
    static int[] cbPalette(byte[] blob) throws IOException {
        if (blob == null || blob.length == 0) {
            return null;
        }
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(blob))) {
            if (readVarInt(in) != 0) {
                return null;
            }
            int n = readVarInt(in);
            if (n <= 0 || n > 4096) {
                return null;
            }
            int[] palette = new int[n];
            for (int i = 0; i < n; i++) {
                palette[i] = readVarInt(in);
            }
            return palette;
        }
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("truncated varint");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
