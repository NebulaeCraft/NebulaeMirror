package net.kuina.nebulaemirror.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applying one patch to the overworld, a slice per server tick:
 * <ol>
 *   <li>CHECK: every name must be registered here; every entry's {@code Expect} must match what the origin
 *       holds now (otherwise someone changed the area since checkout). The current content of every entry is
 *       recorded as the undo patch. Unknown names always refuse the job; conflicts refuse it unless forced.</li>
 *   <li>The undo file is written to disk before the first block changes.</li>
 *   <li>APPLY: blocks and TileEntities through {@link QuietSetter}.</li>
 * </ol>
 * {@code /mirrorbridge rollback <id>} applies the undo file as a new job (whose Expect is what this job wrote).
 */
final class Job {
    enum Phase { CHECK, APPLY, DONE, REFUSED, FAILED, CANCELLED, INTERRUPTED }

    private static final int SAMPLES = 20;

    final String id;
    final String kind;
    final String source;
    final boolean force;
    final Patch patch;
    final File dir;
    final long started = System.currentTimeMillis();
    volatile Phase phase = Phase.CHECK;
    private int cursor;
    private final List<Patch.Entry> undo = new ArrayList<>();
    private final IBlockState[] states;
    final Map<String, Integer> unknownNames = new LinkedHashMap<>();
    final List<String> conflicts = new ArrayList<>();
    int conflictCount;
    int invalid;
    int changed;
    int unchanged;
    int tileEntityFailures;
    String error;
    long finished;
    /** Inbox file removed once the job is done (a refused patch stays there for a forced retry). */
    File consume;

    Job(String id, String kind, String source, boolean force, Patch patch, File dir) {
        this.id = id;
        this.kind = kind;
        this.source = source;
        this.force = force;
        this.patch = patch;
        this.dir = dir;
        this.states = new IBlockState[patch.entries.size()];
    }

    boolean running() {
        return phase == Phase.CHECK || phase == Phase.APPLY;
    }

    /** Works until {@code deadline} (System.nanoTime). */
    void tick(WorldServer world, long deadline) {
        try {
            if (phase == Phase.CHECK) {
                check(world, deadline);
            } else if (phase == Phase.APPLY) {
                apply(world, deadline);
            }
        } catch (Exception e) {
            phase = Phase.FAILED;
            error = e.toString();
            finished = System.currentTimeMillis();
            MirrorBridge.LOG.error("Job {} failed at entry {}", id, cursor, e);
        }
    }

    @SuppressWarnings("deprecation")
    private void check(WorldServer world, long deadline) throws IOException {
        List<Patch.Entry> entries = patch.entries;
        while (cursor < entries.size() && System.nanoTime() < deadline) {
            Patch.Entry e = entries.get(cursor);
            ResourceLocation rl = new ResourceLocation(e.name);
            if (e.y < 0 || e.y > 255) {
                invalid++;
            } else if (!Block.REGISTRY.containsKey(rl)) {
                unknownNames.merge(e.name, 1, Integer::sum);
            } else {
                states[cursor] = Block.REGISTRY.getObject(rl).getStateFromMeta(e.meta);
                BlockPos pos = new BlockPos(e.x, e.y, e.z);
                IBlockState cur = world.getBlockState(pos);
                String curName = String.valueOf(Block.REGISTRY.getNameForObject(cur.getBlock()));
                int curMeta = cur.getBlock().getMetaFromState(cur);
                boolean alreadyTarget = e.name.equals(curName) && e.meta == curMeta; // e.g. rolling back a partial job
                if (e.expectName != null && !alreadyTarget && (!e.expectName.equals(curName) || e.expectMeta != curMeta)) {
                    conflictCount++;
                    if (conflicts.size() < SAMPLES) {
                        conflicts.add(e.x + " " + e.y + " " + e.z + ": expected " + e.expectName + ":" + e.expectMeta
                                + ", origin has " + curName + ":" + curMeta);
                    }
                }
                NBTTagCompound curTe = null;
                if (cur.getBlock().hasTileEntity(cur)) {
                    TileEntity te = world.getTileEntity(pos);
                    if (te != null) {
                        curTe = te.writeToNBT(new NBTTagCompound());
                    }
                }
                undo.add(new Patch.Entry(e.x, e.y, e.z, curName, curMeta, e.name, e.meta, curTe));
            }
            cursor++;
        }
        if (cursor < entries.size()) {
            return;
        }
        if (!unknownNames.isEmpty()) {
            refuse("blocks not registered on this server: " + unknownNames);
        } else if (invalid > 0) {
            refuse(invalid + " entries outside y 0..255");
        } else if (conflictCount > 0 && !force) {
            refuse(conflictCount + " blocks changed at the origin since checkout (use force to overwrite them)");
        } else {
            new Patch(patch.zone, patch.min, patch.max, undo).write(new File(dir, "undo.nbt"), "undo of " + id);
            cursor = 0;
            phase = Phase.APPLY;
            MirrorBridge.LOG.info("Job {}: checked {} entries ({} conflicts{}), undo saved; applying", id,
                    entries.size(), conflictCount, conflictCount > 0 ? ", forced" : "");
        }
    }

    private void refuse(String why) {
        phase = Phase.REFUSED;
        error = why;
        finished = System.currentTimeMillis();
        MirrorBridge.LOG.warn("Job {} refused: {}", id, why);
    }

    private void apply(WorldServer world, long deadline) {
        QuietSetter setter = new QuietSetter(world);
        List<Patch.Entry> entries = patch.entries;
        try {
            while (cursor < entries.size() && System.nanoTime() < deadline) {
                Patch.Entry e = entries.get(cursor);
                IBlockState s = states[cursor];
                if (setter.set(new BlockPos(e.x, e.y, e.z), s, e.tileEntity)) {
                    changed++;
                } else {
                    unchanged++;
                }
                cursor++;
            }
        } finally {
            setter.finish();
            tileEntityFailures += setter.tileEntityFailures;
        }
        if (cursor >= entries.size()) {
            phase = Phase.DONE;
            finished = System.currentTimeMillis();
            MirrorBridge.LOG.info("Job {} done: {} changed, {} already as patched, {} TileEntity failures in {} ms",
                    id, changed, unchanged, tileEntityFailures, finished - started);
        }
    }

    void cancel() {
        if (running()) {
            phase = Phase.CANCELLED;
            finished = System.currentTimeMillis();
        }
    }

    String progress() {
        return kind + " " + id + " (" + patch.zone + "): " + phase.name().toLowerCase() + " " + cursor + "/"
                + patch.entries.size();
    }

    JsonObject report() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.addProperty("source", source);
        o.addProperty("zone", patch.zone);
        o.addProperty("phase", phase.name().toLowerCase());
        o.addProperty("force", force);
        o.addProperty("entries", patch.entries.size());
        o.addProperty("progress", cursor);
        o.addProperty("changed", changed);
        o.addProperty("unchanged", unchanged);
        o.addProperty("tileEntityFailures", tileEntityFailures);
        o.addProperty("conflicts", conflictCount);
        JsonArray c = new JsonArray();
        conflicts.forEach(c::add);
        o.add("conflictSamples", c);
        JsonObject u = new JsonObject();
        unknownNames.forEach(u::addProperty);
        o.add("unknownNames", u);
        o.addProperty("undo", new File(dir, "undo.nbt").isFile());
        o.addProperty("started", started);
        o.addProperty("finished", finished);
        if (error != null) {
            o.addProperty("error", error);
        }
        return o;
    }

    String summary() {
        switch (phase) {
            case DONE:
                return kind + " " + id + " done: " + changed + " blocks changed, " + unchanged + " already as patched"
                        + (tileEntityFailures > 0 ? ", " + tileEntityFailures + " TileEntities could not be created" : "")
                        + (conflictCount > 0 ? " (" + conflictCount + " conflicts overwritten)" : "")
                        + "; undo with /mirrorbridge rollback " + id;
            case REFUSED:
                return kind + " " + id + " refused: " + error + (conflicts.isEmpty() ? "" : "\n  " + String.join("\n  ",
                        conflicts.subList(0, Math.min(5, conflicts.size()))));
            case CANCELLED:
                return kind + " " + id + " cancelled after " + cursor + " entries"
                        + (new File(dir, "undo.nbt").isFile() ? "; undo with /mirrorbridge rollback " + id : "");
            default:
                return progress() + (error != null ? ": " + error : "");
        }
    }
}
