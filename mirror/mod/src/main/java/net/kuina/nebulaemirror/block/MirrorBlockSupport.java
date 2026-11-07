package net.kuina.nebulaemirror.block;

import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-block lookup of generator state data (shapes, light, writability). */
public final class MirrorBlockSupport {
    public final MirrorData.BlockDef def;
    public final Set<String> keyed = new HashSet<>();
    private final Map<BlockState, MirrorData.StateInfo> info = new IdentityHashMap<>();
    private final Map<BlockState, VoxelShape> shapes = new IdentityHashMap<>();
    private final Map<BlockState, VoxelShape> collisions = new IdentityHashMap<>();

    public MirrorBlockSupport(MirrorData.BlockDef def) {
        this.def = def;
        for (MirrorData.PropDef p : def.keyedProperties()) {
            keyed.add(p.name());
        }
    }

    /** Called once the block's state definition exists. */
    public void index(Block block, List<MirrorData.Box> boxes) {
        Map<Integer, VoxelShape> cache = new HashMap<>();
        for (BlockState s : block.getStateDefinition().getPossibleStates()) {
            MirrorData.StateInfo si = lookup(s);
            info.put(s, si);
            if (si == null) {
                continue;
            }
            shapes.put(s, shape(si.shape(), si.fullCube(), boxes, cache));
            collisions.put(s, si.noCollision() ? Shapes.empty() : si.collision() >= 0
                    ? shape(si.collision(), false, boxes, cache) : shapes.get(s));
        }
    }

    public MirrorData.StateInfo lookup(BlockState s) {
        MirrorData.StateInfo si = info.get(s);
        return si != null ? si : def.states().get(MirrorProps.key(s, keyed));
    }

    public VoxelShape shape(BlockState s) {
        return shapes.getOrDefault(s, Shapes.block());
    }

    public VoxelShape collision(BlockState s) {
        return collisions.getOrDefault(s, Shapes.block());
    }

    public boolean writable(BlockState s) {
        MirrorData.StateInfo si = lookup(s);
        return si != null && si.writable();
    }

    private static VoxelShape shape(int index, boolean full, List<MirrorData.Box> boxes, Map<Integer, VoxelShape> cache) {
        if (index < 0) {
            return Shapes.block();
        }
        return cache.computeIfAbsent(index, i -> {
            MirrorData.Box b = boxes.get(i);
            if (b.x2() - b.x1() <= 0 || b.y2() - b.y1() <= 0 || b.z2() - b.z1() <= 0) {
                return Shapes.empty();
            }
            // 1.12 allowed boxes slightly outside the block (Blockbench exports); 1.21 shapes are clamped.
            return Shapes.box(clamp(b.x1()), clamp(b.y1()), clamp(b.z1()), clamp(b.x2()), clamp(b.y2()), clamp(b.z2()));
        });
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
