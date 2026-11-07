package net.kuina.nebulaemirror.client.legacy;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural roof shapes of ArchitectureCraft 1.12 (client.render.shape.RenderRoof, MIT), for the shapes this
 * world uses: ROOF_TILE (0), ROOF_OUTER_CORNER (1), ROOF_INNER_CORNER (2), ROOF_RIDGE (3).
 * Neighbour-dependent ridge/valley connections are not reproduced (the world has no valley shapes).
 * Coordinates are 0..1 block space as in RenderRoof#vertex; "slope" faces use texture slot 2 (secondary, model UVs),
 * outer faces slot 1 (base, projected).
 */
final class ArchitectureRoofs {
    /** One polygon: 3 or 4 vertices {x, y, z, u, v}, a face normal and a texture slot. */
    record Poly(int slot, double[] normal, double[][] vertices) {
    }

    private static final double S = 0.707;
    private static final double[] POS_X = {1, 0, 0}, NEG_X = {-1, 0, 0}, POS_Z = {0, 0, 1}, NEG_Z = {0, 0, -1},
            DOWN = {0, -1, 0};
    private static final double[] SLOPE_POS_X = {S, S, 0}, SLOPE_POS_Z = {0, S, S}, SLOPE_NEG_Z = {0, S, -S};

    private ArchitectureRoofs() {
    }

    /** @return polygons for the shape, or null if this roof shape is not supported. */
    static List<Poly> polys(int shape) {
        List<Poly> out = new ArrayList<>();
        switch (shape) {
            case 0 -> { // renderSlope (no valley behind)
                slope(out, SLOPE_NEG_Z, v(1, 1, 1, 0, 0), v(1, 0, 0, 0, 1), v(0, 0, 0, 1, 1), v(0, 1, 1, 1, 0));
                leftTriangle(out);
                rightTriangle(out);
                bottomQuad(out);
                outer(out, POS_Z, v(0, 1, 1, 0, 0), v(0, 0, 1, 0, 1), v(1, 0, 1, 1, 1), v(1, 1, 1, 1, 0)); // backQuad
            }
            case 1 -> { // renderOuterCorner
                slope(out, SLOPE_NEG_Z, v(0, 1, 1, 1, 0), v(1, 0, 0, 0, 1), v(0, 0, 0, 1, 1));
                slope(out, SLOPE_POS_X, v(0, 1, 1, 0, 0), v(1, 0, 1, 0, 1), v(1, 0, 0, 1, 1));
                outer(out, POS_Z, v(0, 1, 1, 0, 0), v(0, 0, 1, 0, 1), v(1, 0, 1, 1, 1));
                rightTriangle(out);
                bottomQuad(out);
            }
            case 2 -> { // renderInnerCorner, with both valleys terminated (no valley neighbours)
                slope(out, SLOPE_POS_X, v(0, 1, 0, 1, 0), v(0.5, 0.5, 0.5, 0.5, 0.5), v(1, 0, 0, 1, 1));
                slope(out, SLOPE_NEG_Z, v(1, 1, 1, 0, 0), v(1, 0, 0, 0, 1), v(0.5, 0.5, 0.5, 0.5, 0.5));
                outer(out, NEG_Z, v(0, 1, 0, 1, 0), v(1, 0, 0, 0, 1), v(0, 0, 0, 1, 1));
                leftTriangle(out);
                bottomQuad(out);
                // terminateValleyBack
                slope(out, SLOPE_NEG_Z, v(1, 1, 1, 0, 0), v(0.5, 0.5, 0.5, 0.5, 0.5), v(0, 1, 1, 1, 0));
                outer(out, POS_Z, v(0, 1, 1, 0, 0), v(0, 0, 1, 0, 1), v(1, 0, 1, 1, 1), v(1, 1, 1, 1, 0));
                // terminateValleyRight
                slope(out, SLOPE_POS_X, v(0, 1, 1, 0, 0), v(0.5, 0.5, 0.5, 0.5, 0.5), v(0, 1, 0, 1, 0));
                outer(out, NEG_X, v(0, 1, 0, 0, 0), v(0, 0, 0, 0, 1), v(0, 0, 1, 1, 1), v(0, 1, 1, 1, 0));
            }
            case 3 -> { // renderRidge, unconnected ends
                slope(out, SLOPE_NEG_Z, v(1, 0.5, 0.5, 0, 0.5), v(1, 0, 0, 0, 1), v(0, 0, 0, 1, 1), v(0, 0.5, 0.5, 1, 0.5));
                slope(out, SLOPE_POS_Z, v(0, 0.5, 0.5, 0, 0.5), v(0, 0, 1, 0, 1), v(1, 0, 1, 1, 1), v(1, 0.5, 0.5, 1, 0.5));
                outer(out, POS_X, v(1, 0.5, 0.5, 0.5, 0.5), v(1, 0, 1, 0, 1), v(1, 0, 0, 1, 1));
                outer(out, NEG_X, v(0, 0.5, 0.5, 0.5, 0.5), v(0, 0, 0, 0, 1), v(0, 0, 1, 1, 1));
                bottomQuad(out);
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private static double[] v(double x, double y, double z, double u, double vv) {
        return new double[]{x, y, z, u, vv};
    }

    private static void slope(List<Poly> out, double[] n, double[]... vs) {
        out.add(new Poly(2, n, vs));
    }

    private static void outer(List<Poly> out, double[] n, double[]... vs) {
        out.add(new Poly(1, n, vs));
    }

    private static void leftTriangle(List<Poly> out) {
        outer(out, POS_X, v(1, 1, 1, 0, 0), v(1, 0, 1, 0, 1), v(1, 0, 0, 1, 1));
    }

    private static void rightTriangle(List<Poly> out) {
        outer(out, NEG_X, v(0, 1, 1, 1, 0), v(0, 0, 0, 0, 1), v(0, 0, 1, 1, 1));
    }

    private static void bottomQuad(List<Poly> out) {
        outer(out, DOWN, v(0, 0, 1, 0, 0), v(0, 0, 0, 0, 1), v(1, 0, 0, 1, 1), v(1, 0, 1, 1, 0));
    }
}
