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
    enum Phase { CHECK, APPLY, VERIFY, DONE, REFUSED, FAILED, CANCELLED, INTERRUPTED }

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
    int verifyMismatches;
    final List<String> mismatches = new ArrayList<>();
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
        return phase == Phase.CHECK || phase == Phase.APPLY || phase == Phase.VERIFY;
    }

    /** Works until {@code deadline} (System.nanoTime). */
    void tick(WorldServer world, long deadline) {
        try {
            if (phase == Phase.CHECK) {
                check(world, deadline);
            } else if (phase == Phase.APPLY) {
                apply(world, deadline);
            } else if (phase == Phase.VERIFY) {
                verify(world, deadline);
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
                        conflicts.add(e.x + " " + e.y + " " + e.z + "：签出时为 " + e.expectName + ":" + e.expectMeta
                                + "，原服现在是 " + curName + ":" + curMeta);
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
            refuse("本服务器没有注册这些方块：" + unknownNames);
        } else if (invalid > 0) {
            refuse(invalid + " 个条目超出 y 0..255");
        } else if (conflictCount > 0 && !force) {
            refuse("签出后原服上有 " + conflictCount + " 个方块被改动（用 force 覆盖）");
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
            cursor = 0;
            phase = Phase.VERIFY;
        }
    }

    /** Reads every entry back: block, meta and (where the patch had one) a TileEntity of the same id. */
    @SuppressWarnings("deprecation")
    private void verify(WorldServer world, long deadline) {
        List<Patch.Entry> entries = patch.entries;
        while (cursor < entries.size() && System.nanoTime() < deadline) {
            Patch.Entry e = entries.get(cursor);
            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            IBlockState cur = world.getBlockState(pos);
            String curName = String.valueOf(Block.REGISTRY.getNameForObject(cur.getBlock()));
            int want = states[cursor].getBlock().getMetaFromState(states[cursor]);
            String problem = null;
            if (!curName.equals(e.name) || cur.getBlock().getMetaFromState(cur) != want) {
                problem = "实际为 " + curName + ":" + cur.getBlock().getMetaFromState(cur) + "，应为 " + e.name + ":" + want;
            } else if (e.tileEntity != null) {
                TileEntity te = world.getTileEntity(pos);
                String id = e.tileEntity.getString("id");
                NBTTagCompound now = te == null ? null : te.writeToNBT(new NBTTagCompound());
                if (now == null || !id.equals(now.getString("id"))) {
                    problem = "没有 " + id + " TileEntity";
                }
            }
            if (problem != null) {
                verifyMismatches++;
                if (mismatches.size() < SAMPLES) {
                    mismatches.add(e.x + " " + e.y + " " + e.z + " " + problem);
                }
            }
            cursor++;
        }
        if (cursor >= entries.size()) {
            phase = Phase.DONE;
            finished = System.currentTimeMillis();
            MirrorBridge.LOG.info("Job {} done: {} changed, {} already as patched, {} TileEntity failures, {} not as "
                    + "patched on read-back, in {} ms", id, changed, unchanged, tileEntityFailures, verifyMismatches,
                    finished - started);
        }
    }

    void cancel() {
        if (running()) {
            phase = Phase.CANCELLED;
            finished = System.currentTimeMillis();
        }
    }

    String progress() {
        return kindLabel() + "任务 " + id + "（" + patch.zone + "）：" + phaseLabel() + " " + cursor + "/"
                + patch.entries.size();
    }

    /** Chinese name of the job kind (apply / rollback / submit) for in-game messages. */
    String kindLabel() {
        return kindLabel(kind);
    }

    static String kindLabel(String kind) {
        switch (kind) {
            case "apply":
                return "应用";
            case "rollback":
                return "回滚";
            case "submit":
                return "提交";
            default:
                return kind;
        }
    }

    private String phaseLabel() {
        return phaseLabel(phase.name().toLowerCase());
    }

    /** Chinese name of a phase as stored in job.json. */
    static String phaseLabel(String phase) {
        switch (phase) {
            case "check":
                return "检查中";
            case "apply":
                return "写入中";
            case "verify":
                return "校验中";
            case "done":
                return "已完成";
            case "refused":
                return "被拒绝";
            case "failed":
                return "失败";
            case "cancelled":
                return "已取消";
            case "interrupted":
                return "被中断";
            default:
                return phase;
        }
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
        o.addProperty("verifyMismatches", verifyMismatches);
        JsonArray mm = new JsonArray();
        mismatches.forEach(mm::add);
        o.add("mismatchSamples", mm);
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
                return kindLabel() + "任务 " + id + " 已完成：修改了 " + changed + " 个方块，" + unchanged + " 个本来就和补丁一致"
                        + (tileEntityFailures > 0 ? "，" + tileEntityFailures + " 个 TileEntity 无法创建" : "")
                        + (conflictCount > 0 ? "（覆盖了 " + conflictCount + " 处冲突）" : "")
                        + (verifyMismatches > 0 ? "；回读时有 " + verifyMismatches + " 个与补丁不一致："
                        + String.join("；", mismatches.subList(0, Math.min(3, mismatches.size()))) : "")
                        + "；撤销请用 /mirrorbridge rollback " + id;
            case REFUSED:
                return kindLabel() + "任务 " + id + " 被拒绝：" + error + (conflicts.isEmpty() ? "" : "\n  " + String.join("\n  ",
                        conflicts.subList(0, Math.min(5, conflicts.size()))));
            case CANCELLED:
                return kindLabel() + "任务 " + id + " 在第 " + cursor + " 个条目后取消"
                        + (new File(dir, "undo.nbt").isFile() ? "；撤销请用 /mirrorbridge rollback " + id : "");
            default:
                return progress() + (error != null ? "：" + error : "");
        }
    }
}
