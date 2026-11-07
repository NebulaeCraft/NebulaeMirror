package net.kuina.nebulaemirror.client.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.kuina.nebulaemirror.client.legacy.ArchitectureRender.ident;
import static net.kuina.nebulaemirror.client.legacy.ArchitectureRender.mul;
import static net.kuina.nebulaemirror.client.legacy.ArchitectureRender.rotY;
import static net.kuina.nebulaemirror.client.legacy.ArchitectureRender.rotZ;

/**
 * ArchitectureCraft 1.12 window shapes (WINDOW_FRAME 30, WINDOW_CORNER 31, WINDOW_MULLION 32). Ported from
 * client.render.shape.RenderWindow, common.shape.WindowShapeKinds and ShapeKind.Window (MIT, see THIRD_PARTY_NOTICES).
 * <p>A window draws a frame edge only on the sides where no matching window continues, so its geometry depends on
 * its neighbours; those are read from their render data while the chunk is meshed. The frame flags are packed into
 * 16 bits ({@code frame[i][j]} at bit {@code i * 4 + j}) and are part of the mesh cache key.
 */
final class ArchitectureWindows {
    private enum FrameKind { NONE, PLAIN, CORNER }

    private record Kind(Direction[] frameSides, FrameKind[] frameKinds, Direction[] frameOrientations,
                        double[][][] frameTrans) {
    }

    private record Models(String centre, String[] centreEnd, String[] side, String[] end0, String[] end1,
                          String glass, String[] glassEdge) {
    }

    private static final Direction D = Direction.DOWN, U = Direction.UP, N = Direction.NORTH, S = Direction.SOUTH,
            W = Direction.WEST, E = Direction.EAST;
    private static final FrameKind NO = FrameKind.NONE, PL = FrameKind.PLAIN, CO = FrameKind.CORNER;

    // Frame data is indexed by local face ordinal (DOWN, UP, NORTH, SOUTH, WEST, EAST), as EnumFacing in 1.12.
    private static final Kind PLAIN = new Kind(
            new Direction[]{D, E, U, W},
            new FrameKind[]{PL, PL, NO, NO, PL, PL},
            new Direction[]{E, E, null, null, U, U},
            new double[][][]{ident(), rotZ(90), rotZ(180), rotZ(270)});
    private static final Kind CORNER = new Kind(
            new Direction[]{D, S, U, W},
            new FrameKind[]{CO, CO, NO, PL, PL, NO},
            new Direction[]{E, E, null, U, U, null},
            new double[][][]{ident(), mul(rotY(-90), rotZ(90)), mul(rotY(-90), rotZ(180)), rotZ(270)});

    private static final Models FRAME_MODELS = new Models(null, null, four("frame_side"), four("frame_end0"),
            four("frame_end1"), "glass", four("glass_edge"));
    private static final Models CORNER_MODELS = new Models("corner_centre",
            new String[]{"corner_centre_end0", null, "corner_centre_end2", null},
            new String[]{"corner_topbot", "frame_side", "corner_topbot", "frame_side"},
            four("frame_end0"),
            new String[]{"corner_topbot_end1", "frame_end1", "corner_topbot_end1", "frame_end1"},
            "corner_glass",
            new String[]{"corner_glass_edge", "glass_edge", "corner_glass_edge", "glass_edge"});
    private static final Models MULLION_MODELS = new Models("mullion_centre",
            new String[]{"mullion_centre_end0", null, "mullion_centre_end2", null},
            new String[]{"mullion_topbot", "frame_side", "mullion_topbot", "frame_side"},
            four("frame_end0"), four("frame_end1"), "glass",
            new String[]{"mullion_glass_edge", "glass_edge", "mullion_glass_edge", "glass_edge"});

    private record PolyKey(int shape, int flags, boolean glass) {
    }

    private static final Map<PolyKey, List<ArchitectureRoofs.Poly>> POLYS = new ConcurrentHashMap<>();

    private ArchitectureWindows() {
    }

    private static String[] four(String name) {
        return new String[]{name, name, name, name};
    }

    private static Kind kind(int shape) {
        return switch (shape) {
            case 30, 32 -> PLAIN;
            case 31 -> CORNER;
            default -> null;
        };
    }

    private static Models models(int shape) {
        return switch (shape) {
            case 30 -> FRAME_MODELS;
            case 31 -> CORNER_MODELS;
            case 32 -> MULLION_MODELS;
            default -> null;
        };
    }

    /** RenderWindow#getFrameFlags, against the neighbours' render data. */
    static int frameFlags(ArchitectureRender self, BlockAndTintGetter level, BlockPos pos) {
        Kind kind = kind(self.shapeId());
        if (kind == null) {
            return 0;
        }
        boolean[][] frame = new boolean[4][4];
        Direction[] gdir = new Direction[4];
        for (int i = 0; i < 4; i++) {
            gdir[i] = self.globalFace(kind.frameSides[i]);
        }
        for (int i = 0; i < 4; i++) {
            BlockPos npos = connectedWindow(kind, self, level, pos, gdir[i]);
            if (npos == null) {
                frame[i][1] = true;
                continue;
            }
            ArchitectureRender nte = (ArchitectureRender) level.getBlockEntityRenderData(npos);
            int j = (i - 1) & 3;
            int k = (i + 1) & 3;
            if (connectedWindow(kind, nte, level, npos, gdir[j]) == null) {
                frame[j][2] = true;
            }
            if (connectedWindow(kind, nte, level, npos, gdir[k]) == null) {
                frame[k][0] = true;
            }
            if (nte.hasSecond()) {
                frame[i][3] = true;
            }
        }
        int flags = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (frame[i][j]) {
                    flags |= 1 << (i * 4 + j);
                }
            }
        }
        return flags;
    }

    private static boolean flag(int flags, int i, int j) {
        return (flags & 1 << (i * 4 + j)) != 0;
    }

    /**
     * ShapeKind.Window#getConnectedWindowGlobal: position of the window continuing te's frame, or null. As in
     * RenderWindow, {@code thisKind} is always the kind of the block being drawn, also when te is its neighbour.
     */
    private static BlockPos connectedWindow(Kind thisKind, ArchitectureRender te, BlockAndTintGetter level,
                                            BlockPos pos, Direction globalDir) {
        Direction thisLocal = te.localFace(globalDir);
        FrameKind thisFrame = thisKind.frameKinds[thisLocal.ordinal()];
        if (thisFrame == FrameKind.NONE || !te.connectionEnabled(globalDir)) {
            return null;
        }
        BlockPos npos = pos.relative(globalDir);
        if (!(level.getBlockEntityRenderData(npos) instanceof ArchitectureRender nte)
                || !nte.connectionEnabled(globalDir.getOpposite())) {
            return null;
        }
        Kind otherKind = kind(nte.shapeId());
        if (otherKind == null) {
            return null;
        }
        Direction otherLocal = nte.localFace(globalDir.getOpposite());
        FrameKind otherFrame = otherKind.frameKinds[otherLocal.ordinal()];
        if (otherFrame != thisFrame) {
            return null;
        }
        Direction o1 = te.globalFace(thisKind.frameOrientations[thisLocal.ordinal()]);
        Direction o2 = nte.globalFace(otherKind.frameOrientations[otherLocal.ordinal()]);
        boolean match = thisFrame == FrameKind.PLAIN ? o1.getAxis() == o2.getAxis() : o1 == o2;
        return match ? npos : null;
    }

    /** RenderWindow#renderWindow as polygons in the shape's local frame (block space, before side/turn). */
    static List<ArchitectureRoofs.Poly> polys(int shape, int flags, boolean glass) {
        Kind kind = kind(shape);
        Models m = models(shape);
        if (kind == null || m == null) {
            return null;
        }
        return POLYS.computeIfAbsent(new PolyKey(shape, flags, glass), key -> {
            List<ArchitectureRoofs.Poly> out = new ArrayList<>();
            double[][] id = ident();
            add(out, m.centre, id);
            for (int i = 0; i < 4; i++) {
                int j = (i - 1) & 3;
                int k = (i + 1) & 3;
                double[][] ts = kind.frameTrans[i];
                if (flag(flags, i, 1)) {
                    add(out, m.side[i], ts);
                } else if (m.centreEnd != null) {
                    add(out, m.centreEnd[i], id);
                }
                if (flag(flags, i, 1) && !flag(flags, j, 1) || flag(flags, i, 0) && flag(flags, j, 2)) {
                    add(out, m.end0[i], ts);
                }
                if (flag(flags, i, 1) && !flag(flags, k, 1) || flag(flags, i, 2) && flag(flags, k, 0)) {
                    add(out, m.end1[i], ts);
                }
                if (glass && !flag(flags, i, 1) && !flag(flags, i, 3)) {
                    add(out, m.glassEdge[i], ts);
                }
            }
            if (glass) {
                add(out, m.glass, id);
            }
            return out;
        });
    }

    private static void add(List<ArchitectureRoofs.Poly> out, String model, double[][] t) {
        if (model == null) {
            return;
        }
        for (ArchitectureRoofs.Poly p : ArchitectureRender.modelPolys(ArchitectureShapes.model("window_" + model))) {
            double[][] vs = new double[p.vertices().length][];
            for (int i = 0; i < vs.length; i++) {
                double[] c = p.vertices()[i];
                double[] g = ArchitectureRender.apply(t, c[0] - 0.5, c[1] - 0.5, c[2] - 0.5);
                vs[i] = new double[]{g[0] + 0.5, g[1] + 0.5, g[2] + 0.5, c[3], c[4]};
            }
            double[] n = ArchitectureRender.apply(t, p.normal()[0], p.normal()[1], p.normal()[2]);
            out.add(new ArchitectureRoofs.Poly(p.slot(), n, vs));
        }
    }
}
