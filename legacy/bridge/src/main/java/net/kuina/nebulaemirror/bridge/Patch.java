package net.kuina.nebulaemirror.bridge;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A write-back patch as the mirror's {@code /nebulaemirror zone diff} writes it (gzip NBT, Version 1):
 * {Zone, Min, Max, Entries: [{Pos, Name, Meta, Expect?: {Name, Meta}, TileEntity?}]}.
 * Undo files use the same format: the origin's previous content, with Expect = what the job wrote.
 */
public final class Patch {
    public static final int VERSION = 1;

    public static final class Entry {
        public final int x, y, z;
        public final String name;
        public final int meta;
        /** What the origin is expected to hold before this entry is applied; null = no check. */
        public final String expectName;
        public final int expectMeta;
        public final NBTTagCompound tileEntity;

        public Entry(int x, int y, int z, String name, int meta, String expectName, int expectMeta, NBTTagCompound te) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.meta = meta;
            this.expectName = expectName;
            this.expectMeta = expectMeta;
            this.tileEntity = te;
        }

        NBTTagCompound toNbt() {
            NBTTagCompound t = new NBTTagCompound();
            t.setIntArray("Pos", new int[]{x, y, z});
            t.setString("Name", name);
            t.setByte("Meta", (byte) meta);
            if (expectName != null) {
                NBTTagCompound ex = new NBTTagCompound();
                ex.setString("Name", expectName);
                ex.setByte("Meta", (byte) expectMeta);
                t.setTag("Expect", ex);
            }
            if (tileEntity != null) {
                t.setTag("TileEntity", tileEntity);
            }
            return t;
        }
    }

    public final String zone;
    public final int[] min;
    public final int[] max;
    public final List<Entry> entries;

    public Patch(String zone, int[] min, int[] max, List<Entry> entries) {
        this.zone = zone;
        this.min = min;
        this.max = max;
        this.entries = entries;
    }

    public static Patch read(File file) throws IOException {
        NBTTagCompound root;
        try (InputStream in = new FileInputStream(file)) {
            root = CompressedStreamTools.readCompressed(in);
        }
        if (root.getInteger("Version") != VERSION) {
            throw new IOException(file.getName() + ": patch version " + root.getInteger("Version") + ", expected " + VERSION);
        }
        NBTTagList list = root.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
        List<Entry> entries = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            int[] p = t.getIntArray("Pos");
            if (p.length != 3) {
                throw new IOException(file.getName() + ": entry " + i + " has no position");
            }
            NBTTagCompound ex = t.hasKey("Expect", Constants.NBT.TAG_COMPOUND) ? t.getCompoundTag("Expect") : null;
            NBTTagCompound te = t.hasKey("TileEntity", Constants.NBT.TAG_COMPOUND) ? t.getCompoundTag("TileEntity") : null;
            entries.add(new Entry(p[0], p[1], p[2], t.getString("Name"), t.getByte("Meta") & 15,
                    ex == null ? null : ex.getString("Name"), ex == null ? 0 : ex.getByte("Meta") & 15, te));
        }
        return new Patch(root.getString("Zone"), root.getIntArray("Min"), root.getIntArray("Max"), entries);
    }

    public void write(File file, String kind) throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("Version", VERSION);
        root.setString("Zone", zone);
        root.setString("Kind", kind);
        root.setLong("Created", System.currentTimeMillis());
        root.setIntArray("Min", min);
        root.setIntArray("Max", max);
        NBTTagList list = new NBTTagList();
        for (Entry e : entries) {
            list.appendTag(e.toNbt());
        }
        root.setTag("Entries", list);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            CompressedStreamTools.writeCompressed(root, out);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot move " + tmp + " to " + file);
        }
    }
}
