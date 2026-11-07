package net.kuina.nebulaemirror.sync;

import com.mojang.serialization.Codec;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.mixin.ChunkMapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies one converted chunk to a chunk the server has in memory (main thread only), touching only what differs.
 * Blocks are written straight into the sections, the way {@code LevelChunk#setBlockState} does it but without
 * {@code onRemove}/{@code onPlace}, neighbour updates or drops: a sync must not drop chest contents, make sand
 * fall or start redstone. Afterwards the connection shapes (fences, stairs, walls, panes...) of the queued
 * positions and of every neighbour of a changed block are recomputed, as UpgradeData does for a freshly
 * imported chunk. Chunks the server does not hold are not handled here: the sync replaces them on disk.
 */
public final class LiveChunkPatcher {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());
    private static final Heightmap.Types[] HEIGHTMAPS = {Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE};

    /** -Dnebulaemirror.sync.debug=true logs every live change (diagnosing states that flip on each run). */
    private static final boolean DEBUG = Boolean.getBoolean("nebulaemirror.sync.debug");

    /** How the server holds a chunk right now. */
    public enum Residency { ABSENT, LOADED, BUSY }

    private LiveChunkPatcher() {
    }

    /**
     * ABSENT: no holder at all, so a disk write cannot be overtaken by a later save of an in-memory copy (any
     * later load reads through the same IO worker and sees the write). LOADED: a full chunk to patch.
     * BUSY: held but not full (loading, or waiting to unload).
     */
    public static Residency residency(ServerLevel level, ChunkPos pos) {
        ChunkMapAccessor map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        long key = pos.toLong();
        if (map.nebulaemirror$updatingChunk(key) == null && map.nebulaemirror$visibleChunk(key) == null
                && !map.nebulaemirror$pendingUnloads().containsKey(key)) {
            return Residency.ABSENT;
        }
        return level.getChunkSource().getChunkNow(pos.x, pos.z) != null ? Residency.LOADED : Residency.BUSY;
    }

    /** Per-chunk outcome. */
    public record Result(int blocks, int blockEntities, int shapes) {
        public boolean changed() {
            return blocks + blockEntities + shapes > 0;
        }
    }

    /**
     * Makes {@code chunk} match the converted 1.21 chunk NBT {@code target}, leaving excluded positions alone.
     */
    public static Result apply(ServerLevel level, LevelChunk chunk, CompoundTag target, SyncGuard guard) {
        HolderLookup.Provider registries = level.registryAccess();
        ChunkPos cp = chunk.getPos();
        boolean checkGuard = guard.coverage(cp) != SyncGuard.Coverage.NONE;

        Map<Integer, PalettedContainer<BlockState>> sections = new HashMap<>();
        for (Tag t : target.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            if (s.contains("block_states", Tag.TAG_COMPOUND)) {
                int sy = s.getByte("Y");
                PalettedContainer<BlockState> states = BLOCK_STATES.parse(NbtOps.INSTANCE, s.getCompound("block_states"))
                        .getOrThrow(msg -> new IllegalStateException("chunk " + cp + " section " + sy + ": " + msg));
                sections.put(sy, states);
            }
        }
        Map<BlockPos, CompoundTag> targetBe = new HashMap<>();
        for (Tag t : target.getList("block_entities", Tag.TAG_COMPOUND)) {
            CompoundTag be = (CompoundTag) t;
            targetBe.put(new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z")), be);
        }
        Set<BlockPos> shapeQueue = upgradeIndices(chunk, target);
        Map<Long, LevelChunk> chunks = new HashMap<>();
        chunks.put(cp.toLong(), chunk);

        int blocks = 0, bes = 0, shapes = 0;
        Set<BlockPos> changed = new LinkedHashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int si = 0; si < chunk.getSectionsCount(); si++) {
            int sy = chunk.getSectionYFromSectionIndex(si);
            PalettedContainer<BlockState> want = sections.get(sy);
            LevelChunkSection live = chunk.getSection(si);
            if (want == null && live.hasOnlyAir()) {
                continue;
            }
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
                BlockState w = want == null ? air : want.get(x, y, z);
                BlockState cur = live.getBlockState(x, y, z);
                if (w == cur) {
                    continue;
                }
                m.set(cp.getMinBlockX() + x, (sy << 4) + y, cp.getMinBlockZ() + z);
                if (checkGuard && guard.excluded(m)) {
                    continue;
                }
                if (w.is(cur.getBlock())) {
                    // Same block: the converted state carries default connections (DFU only queues some of
                    // them) and DFU's own leaf distances; compare what it settles to, not the raw state.
                    w = keepRuntimeState(w, cur);
                    if (w == cur) {
                        continue;
                    }
                    if (neighboursLoaded(level, m, chunks)) {
                        BlockState shaped = Block.updateFromNeighbourShapes(w, level, m);
                        if (!shaped.isAir()) {
                            w = keepRuntimeState(shaped, cur);
                        }
                        if (w == cur) {
                            continue;
                        }
                    } else if (shapeQueue.contains(m)) {
                        continue; // cannot tell; keep the live connections
                    }
                }
                if (DEBUG) {
                    NebulaeMirror.LOG.info("Sync debug {}: {} -> {}{}", m, cur, w, shapeQueue.contains(m) ? " (queued)" : "");
                }
                if (setRaw(level, chunk, m, w)) {
                    blocks++;
                    changed.add(m.immutable());
                }
            }
        }

        // Block entities: create, replace or remove so that each one matches the converted data.
        Set<BlockPos> bePositions = new HashSet<>(targetBe.keySet());
        bePositions.addAll(chunk.getBlockEntities().keySet());
        for (BlockPos pos : bePositions) {
            if (checkGuard && guard.excluded(pos)) {
                continue;
            }
            BlockState state = chunk.getBlockState(pos);
            BlockEntity current = chunk.getBlockEntities().get(pos);
            if (!state.hasBlockEntity()) {
                if (current != null) {
                    chunk.removeBlockEntity(pos);
                    bes++;
                }
                continue;
            }
            CompoundTag want = targetBe.get(pos);
            BlockEntity fresh;
            if (want == null) {
                if (current != null && current.getType().isValid(state)) {
                    continue;
                }
                fresh = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
            } else {
                fresh = BlockEntity.loadStatic(pos, state, want, registries);
                if (fresh != null && current != null && current.getType() == fresh.getType()
                        && current.saveWithoutMetadata(registries).equals(fresh.saveWithoutMetadata(registries))) {
                    continue;
                }
            }
            if (fresh == null) {
                continue;
            }
            if (current != null) {
                chunk.removeBlockEntity(pos);
            }
            chunk.addAndRegisterBlockEntity(fresh);
            chunk.setUnsaved(true);
            level.getChunkSource().blockChanged(pos);
            bes++;
        }

        // Connection shapes: queued positions plus all neighbours of changed blocks (also across chunk borders).
        Set<BlockPos> reshape = new LinkedHashSet<>(shapeQueue);
        for (BlockPos p : changed) {
            for (Direction d : Direction.values()) {
                reshape.add(p.relative(d));
            }
        }
        for (BlockPos p : reshape) {
            if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight()
                    || (!guard.isEmpty() && guard.excluded(p)) || !neighboursLoaded(level, p, chunks)) {
                continue;
            }
            LevelChunk c = chunkAt(level, p, chunks);
            BlockState cur = c.getBlockState(p);
            if (cur.isAir()) {
                continue;
            }
            BlockState shaped = Block.updateFromNeighbourShapes(cur, level, p);
            // Never let a shape update delete a block (1.12 allowed some placements 1.21 would break).
            if (DEBUG && shaped != cur) {
                NebulaeMirror.LOG.info("Sync debug shape {}: {} -> {}", p, cur, shaped);
            }
            if (shaped != cur && !shaped.isAir() && setRaw(level, c, p, shaped)) {
                shapes++;
            }
        }
        return new Result(blocks, bes, shapes);
    }

    /**
     * Properties the running game owns rather than the 1.12 data: a leaf's distance (DFU guesses it per chunk,
     * the game recomputes it with ticks). For the same block the live value is kept.
     */
    private static BlockState keepRuntimeState(BlockState want, BlockState cur) {
        if (want.hasProperty(LeavesBlock.DISTANCE) && cur.hasProperty(LeavesBlock.DISTANCE)) {
            return want.setValue(LeavesBlock.DISTANCE, cur.getValue(LeavesBlock.DISTANCE));
        }
        return want;
    }

    /** Positions the converter queued in UpgradeData (keys are section indices from the bottom section). */
    private static Set<BlockPos> upgradeIndices(LevelChunk chunk, CompoundTag target) {
        Set<BlockPos> out = new HashSet<>();
        CompoundTag indices = target.getCompound("UpgradeData").getCompound("Indices");
        ChunkPos cp = chunk.getPos();
        for (String key : indices.getAllKeys()) {
            int si;
            try {
                si = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            if (si < 0 || si >= chunk.getSectionsCount()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int i : indices.getIntArray(key)) {
                out.add(new BlockPos(cp.getMinBlockX() + (i & 15), baseY + (i >> 8 & 15), cp.getMinBlockZ() + (i >> 4 & 15)));
            }
        }
        return out;
    }

    private static LevelChunk chunkAt(ServerLevel level, BlockPos p, Map<Long, LevelChunk> cache) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        long key = ChunkPos.asLong(cx, cz);
        LevelChunk c = cache.get(key);
        if (c == null && !cache.containsKey(key)) {
            c = level.getChunkSource().getChunkNow(cx, cz);
            cache.put(key, c);
        }
        return c;
    }

    /** Shape updates read the 3x3 chunks around a block; never trigger loads for that. */
    private static boolean neighboursLoaded(ServerLevel level, BlockPos p, Map<Long, LevelChunk> cache) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (chunkAt(level, p.offset(dx, 0, dz), cache) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code LevelChunk#setBlockState} minus block callbacks: section, height maps, light, block entity
     * removal, POI, save flag and client sync. Returns false if nothing changed.
     */
    static boolean setRaw(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state) {
        int y = pos.getY();
        int si = chunk.getSectionIndex(y);
        if (si < 0 || si >= chunk.getSectionsCount()) {
            return false;
        }
        LevelChunkSection section = chunk.getSection(si);
        boolean wasAir = section.hasOnlyAir();
        if (wasAir && state.isAir()) {
            return false;
        }
        int x = pos.getX() & 15, ly = y & 15, z = pos.getZ() & 15;
        BlockState old = section.setBlockState(x, ly, z, state);
        if (old == state) {
            return false;
        }
        for (Heightmap.Types t : HEIGHTMAPS) {
            chunk.getOrCreateHeightmapUnprimed(t).update(x, y, z, state);
        }
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        boolean nowAir = section.hasOnlyAir();
        if (wasAir != nowAir) {
            light.updateSectionStatus(pos, nowAir);
        }
        if (LightEngine.hasDifferentLightProperties(chunk, pos, old, state)) {
            chunk.getSkyLightSources().update(chunk, x, y, z);
            light.checkBlock(pos);
        }
        if (old.hasBlockEntity() && (!state.is(old.getBlock()) || !state.hasBlockEntity())) {
            chunk.removeBlockEntity(pos);
        } else if (state.hasBlockEntity()) {
            BlockEntity be = chunk.getBlockEntities().get(pos);
            if (be != null) {
                be.setBlockState(state);
            }
        }
        level.onBlockStateChange(pos, old, state);
        chunk.setUnsaved(true);
        level.getChunkSource().blockChanged(pos);
        return true;
    }

    static void logFailure(ChunkPos pos, Exception e) {
        NebulaeMirror.LOG.warn("Sync: could not patch live chunk {}: {}", pos, e.toString());
    }
}
