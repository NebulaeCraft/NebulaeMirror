package net.kuina.nebulaemirror.client.legacy;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ArchitectureCraft 1.12 shape block, drawn from its TileEntity data. Ported from ArchitectureCraft's
 * ShapeRenderDispatch / ShapeKind.Model / OBJSONModel / RenderTargetBase / Matrix3 (MIT, see THIRD_PARTY_NOTICES).
 * <p>The OBJSON model is block-centred; it is offset along local X by offsetX (banisters), rotated by
 * sideRotations[side] · turnRotations[turn], then moved to the block centre. Texture slots: 0 base (model UVs),
 * 1 base projected from the face direction, 2/3 the same for the secondary material (defaults to the base).
 * Meshes are shared between all blocks with the same shape, orientation and materials.
 */
final class ArchitectureRender implements LegacyRender {
    static final AtomicLong MESHES = new AtomicLong();

    private record Key(int shape, int side, int turn, int offsetX, BlockState base, @Nullable BlockState second,
                       int baseColor, int secondColor) {
    }

    private static final Map<Key, Mesh> CACHE = new ConcurrentHashMap<>();
    private static final Map<ArchitectureShapes.Model, List<ArchitectureRoofs.Poly>> MODEL_POLYS = new ConcurrentHashMap<>();
    private static final Map<Integer, Optional<List<ArchitectureRoofs.Poly>>> ROOFS = new ConcurrentHashMap<>();

    private static final double[][][] SIDE = {
            ident(),
            rotX(180),
            rotX(90),
            mul(rotX(-90), rotY(180)),
            mul(rotZ(-90), rotY(90)),
            mul(rotZ(90), rotY(-90)),
    };
    private static final double[][][] TURN = {rotY(0), rotY(90), rotY(180), rotY(270)};

    private final int shape;
    private final int side;
    private final int turn;
    private final int offsetX;
    private final BlockState base;
    @Nullable
    private final BlockState second;

    ArchitectureRender(int shape, int side, int turn, int offsetX, BlockState base, @Nullable BlockState second) {
        this.shape = shape;
        this.side = Math.floorMod(side, 6);
        this.turn = Math.floorMod(turn, 4);
        this.offsetX = offsetX;
        this.base = base;
        this.second = second;
    }

    static String stats() {
        return MESHES.get() + " ArchitectureCraft meshes (" + CACHE.size() + " cached)";
    }

    @Override
    public boolean emit(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter) {
        ArchitectureShapes.Shape s = ArchitectureShapes.shape(shape);
        if (s == null || base.isAir()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        List<ArchitectureRoofs.Poly> polys = switch (s.kind()) {
            case MODEL -> modelPolys(ArchitectureShapes.model(s.model()));
            case ROOF -> ROOFS.computeIfAbsent(shape, id -> Optional.ofNullable(ArchitectureRoofs.polys(id))).orElse(null);
            default -> null;
        };
        if (polys == null) {
            // Window / cladding / smart roof generators are not ported: show the material as a full block.
            emitBlockModel(base, emitter);
            return true;
        }
        int baseColor = mc.getBlockColors().getColor(base, level, pos, 0);
        int secondColor = second == null ? baseColor : mc.getBlockColors().getColor(second, level, pos, 0);
        Key key = new Key(shape, side, turn, offsetX, base, second, baseColor, secondColor);
        Mesh mesh = CACHE.computeIfAbsent(key, k -> build(polys, k));
        mesh.outputTo(emitter);
        return true;
    }

    private static void emitBlockModel(BlockState state, QuadEmitter emitter) {
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        RenderMaterial material = renderer.materialFinder()
                .blendMode(BlendMode.fromRenderLayer(ItemBlockRenderTypes.getChunkRenderType(state))).find();
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create(42);
        for (Direction d : new Direction[]{null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST}) {
            for (BakedQuad q : model.getQuads(state, d, random)) {
                emitter.fromVanilla(q, material, d);
                emitter.emit();
            }
        }
    }

    /**
     * OBJSON model -> polygons (one per triangle, block-space 0..1 positions, per-vertex normals folded into the
     * face normal of the first vertex as ArchitectureCraft's RenderTargetBase does for projection).
     */
    private static List<ArchitectureRoofs.Poly> modelPolys(ArchitectureShapes.Model model) {
        return MODEL_POLYS.computeIfAbsent(model, m -> {
            List<ArchitectureRoofs.Poly> out = new ArrayList<>();
            if (m.faces == null) {
                return out;
            }
            for (ArchitectureShapes.Face face : m.faces) {
                for (int[] tri : face.triangles) {
                    double[][] vs = new double[3][];
                    for (int i = 0; i < 3; i++) {
                        double[] c = face.vertices[tri[i]];
                        vs[i] = new double[]{c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, c[6], c[7]};
                    }
                    double[] c0 = face.vertices[tri[0]];
                    out.add(new ArchitectureRoofs.Poly(face.texture, new double[]{c0[3], c0[4], c0[5]}, vs));
                }
            }
            return out;
        });
    }

    private static Mesh build(List<ArchitectureRoofs.Poly> polys, Key k) {
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        MeshBuilder builder = renderer.meshBuilder();
        QuadEmitter e = builder.getEmitter();
        Minecraft mc = Minecraft.getInstance();
        BlockState secondState = k.second() != null && !k.second().isAir()
                && ItemBlockRenderTypes.getChunkRenderType(k.second()) == net.minecraft.client.renderer.RenderType.solid()
                ? k.second() : k.base();
        TextureAtlasSprite baseSprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(k.base());
        TextureAtlasSprite secondSprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(secondState);
        RenderMaterial baseMat = renderer.materialFinder()
                .blendMode(BlendMode.fromRenderLayer(ItemBlockRenderTypes.getChunkRenderType(k.base()))).find();
        RenderMaterial secondMat = renderer.materialFinder()
                .blendMode(BlendMode.fromRenderLayer(ItemBlockRenderTypes.getChunkRenderType(secondState))).find();
        double[][] rot = mul(SIDE[k.side()], TURN[k.turn()]);
        double dx = k.offsetX() / 16.0;
        for (ArchitectureRoofs.Poly poly : polys) {
            boolean secondary = poly.slot() > 1;
            boolean projected = (poly.slot() & 1) == 1;
            TextureAtlasSprite sprite = secondary ? secondSprite : baseSprite;
            int color = secondary ? k.secondColor() : k.baseColor();
            int argb = color == -1 ? -1 : 0xFF000000 | color;
            double[] nn = apply(rot, poly.normal()[0], poly.normal()[1], poly.normal()[2]);
            Direction nominal = facing((float) nn[0], (float) nn[1], (float) nn[2]);
            int count = poly.vertices().length;
            float[][] p = new float[count][];
            for (int i = 0; i < count; i++) {
                double[] c = poly.vertices()[i];
                double[] g = apply(rot, c[0] - 0.5 + dx, c[1] - 0.5, c[2] - 0.5);
                p[i] = new float[]{(float) (g[0] + 0.5), (float) (g[1] + 0.5), (float) (g[2] + 0.5)};
            }
            for (int i = 0; i < 4; i++) {
                int j = Math.min(i, count - 1); // triangles become quads with the last vertex repeated
                double[] c = poly.vertices()[j];
                float u, v;
                if (projected) {
                    float[] uv = project(p[j], nominal);
                    u = uv[0];
                    v = uv[1];
                } else {
                    u = (float) c[3];
                    v = (float) c[4];
                }
                e.pos(i, p[j][0], p[j][1], p[j][2]);
                e.normal(i, (float) nn[0], (float) nn[1], (float) nn[2]);
                e.uv(i, sprite.getU(u), sprite.getV(v));
                e.color(i, argb);
            }
            e.nominalFace(nominal);
            e.cullFace(onBoundary(p, nominal) ? nominal : null);
            e.material(secondary ? secondMat : baseMat);
            e.emit();
        }
        MESHES.incrementAndGet();
        return builder.build();
    }

    /** RenderTargetBase#addProjectedVertex: block-local position projected onto the face's plane. */
    private static float[] project(float[] p, Direction face) {
        float x = p[0], y = p[1], z = p[2];
        return switch (face) {
            case DOWN -> new float[]{x, 1 - z};
            case UP -> new float[]{x, z};
            case NORTH -> new float[]{1 - x, 1 - y};
            case SOUTH -> new float[]{x, 1 - y};
            case EAST -> new float[]{1 - z, 1 - y};
            case WEST -> new float[]{z, 1 - y};
        };
    }

    private static boolean onBoundary(float[][] p, Direction face) {
        float target = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
        int axis = face.getAxis().ordinal();
        for (float[] v : p) {
            if (Math.abs(v[axis] - target) > 1e-4f) {
                return false;
            }
        }
        return true;
    }

    private static Direction facing(float x, float y, float z) {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ay >= ax && ay >= az) {
            return y > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return x > 0 ? Direction.EAST : Direction.WEST;
        }
        return z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ---- Matrix3 (row-major, as ArchitectureCraft) ----

    private static double[][] ident() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] rot(double deg, int i, int j) {
        double a = Math.toRadians(deg);
        double s = Math.round(Math.sin(a) * 1e9) / 1e9;
        double c = Math.round(Math.cos(a) * 1e9) / 1e9;
        double[][] r = ident();
        r[i][i] = c;
        r[i][j] = -s;
        r[j][i] = s;
        r[j][j] = c;
        return r;
    }

    private static double[][] rotX(double deg) {
        return rot(deg, 1, 2);
    }

    private static double[][] rotY(double deg) {
        return rot(deg, 2, 0);
    }

    private static double[][] rotZ(double deg) {
        return rot(deg, 0, 1);
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    private static double[] apply(double[][] m, double x, double y, double z) {
        return new double[]{
                x * m[0][0] + y * m[0][1] + z * m[0][2],
                x * m[1][0] + y * m[1][1] + z * m[1][2],
                x * m[2][0] + y * m[2][1] + z * m[2][2]};
    }
}
