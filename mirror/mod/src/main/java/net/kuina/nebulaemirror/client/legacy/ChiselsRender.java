package net.kuina.nebulaemirror.client.legacy;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Chisels&Bits block: 16³ voxels, each a 1.21 block state. Meshed once (greedy, per face direction and depth
 * layer), with each state's own per-face sprite and tint, then reused for every chunk rebuild.
 */
public final class ChiselsRender implements LegacyRender {
    private static final Direction[] FACES = Direction.values();
    /** Diagnostics (self-test / tour): meshes built and quads in them. */
    static final java.util.concurrent.atomic.AtomicLong MESHES = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong QUADS = new java.util.concurrent.atomic.AtomicLong();

    public static String stats() {
        return MESHES.get() + " C&B meshes, " + QUADS.get() + " quads";
    }

    private final short[] voxels;
    private final BlockState[] palette;
    private volatile Mesh mesh;

    ChiselsRender(short[] voxels, BlockState[] palette) {
        this.voxels = voxels;
        this.palette = palette;
    }

    @Override
    public boolean emit(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter) {
        Mesh m = mesh;
        if (m == null) {
            synchronized (this) {
                if (mesh == null) {
                    mesh = build(level, pos);
                }
                m = mesh;
            }
        }
        m.outputTo(emitter);
        return true;
    }

    /** Voxel coordinates of face-local (u, v, depth) for {@link QuadEmitter#square}'s conventions. */
    private static int voxel(Direction f, int u, int v, int d) {
        return switch (f) {
            case DOWN -> u | d << 4 | v << 8;
            case UP -> u | (15 - d) << 4 | (15 - v) << 8;
            case WEST -> d | v << 4 | u << 8;
            case EAST -> (15 - d) | v << 4 | (15 - u) << 8;
            case NORTH -> (15 - u) | v << 4 | d << 8;
            case SOUTH -> u | v << 4 | (15 - d) << 8;
        };
    }

    private Mesh build(BlockAndTintGetter level, BlockPos pos) {
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        MeshBuilder builder = renderer.meshBuilder();
        QuadEmitter e = builder.getEmitter();
        int n = palette.length;
        boolean[] air = new boolean[n];
        boolean[] seeThrough = new boolean[n];
        RenderMaterial[] material = new RenderMaterial[n];
        TextureAtlasSprite[][] sprite = new TextureAtlasSprite[n][6];
        int[][] color = new int[n][6];
        Minecraft mc = Minecraft.getInstance();
        RandomSource random = RandomSource.create(42);
        for (int i = 0; i < n; i++) {
            BlockState s = palette[i];
            air[i] = s.isAir();
            if (air[i]) {
                continue;
            }
            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(s);
            seeThrough[i] = layer != RenderType.solid() || !s.canOcclude();
            material[i] = renderer.materialFinder().blendMode(BlendMode.fromRenderLayer(layer)).find();
            BakedModel model = mc.getBlockRenderer().getBlockModel(s);
            for (Direction f : FACES) {
                BakedQuad q = faceQuad(model, s, f, random);
                sprite[i][f.get3DDataValue()] = q != null ? q.getSprite() : model.getParticleIcon();
                int c = -1;
                if (q != null && q.isTinted()) {
                    c = 0xFF000000 | mc.getBlockColors().getColor(s, level, pos, q.getTintIndex());
                }
                color[i][f.get3DDataValue()] = c;
            }
        }

        int[] mask = new int[256];
        for (Direction f : FACES) {
            int fi = f.get3DDataValue();
            for (int d = 0; d < 16; d++) {
                boolean any = false;
                for (int v = 0; v < 16; v++) {
                    for (int u = 0; u < 16; u++) {
                        int id = voxels[voxel(f, u, v, d)];
                        int m = -1;
                        if (!air[id]) {
                            if (d == 0) {
                                m = id;
                            } else {
                                int nb = voxels[voxel(f, u, v, d - 1)];
                                if (air[nb] || (nb != id && seeThrough[nb])) {
                                    m = id;
                                }
                            }
                        }
                        mask[u | v << 4] = m;
                        any |= m >= 0;
                    }
                }
                if (!any) {
                    continue;
                }
                // greedy rectangles of equal state
                for (int v = 0; v < 16; v++) {
                    for (int u = 0; u < 16; ) {
                        int id = mask[u | v << 4];
                        if (id < 0) {
                            u++;
                            continue;
                        }
                        int w = 1;
                        while (u + w < 16 && mask[(u + w) | v << 4] == id) {
                            w++;
                        }
                        int h = 1;
                        grow:
                        while (v + h < 16) {
                            for (int k = 0; k < w; k++) {
                                if (mask[(u + k) | (v + h) << 4] != id) {
                                    break grow;
                                }
                            }
                            h++;
                        }
                        for (int dv = 0; dv < h; dv++) {
                            for (int k = 0; k < w; k++) {
                                mask[(u + k) | (v + dv) << 4] = -1;
                            }
                        }
                        e.square(f, u / 16f, v / 16f, (u + w) / 16f, (v + h) / 16f, d / 16f);
                        e.spriteBake(sprite[id][fi], MutableQuadView.BAKE_LOCK_UV);
                        int c = color[id][fi];
                        e.color(c, c, c, c);
                        e.material(material[id]);
                        e.emit();
                        QUADS.incrementAndGet();
                        u += w;
                    }
                }
            }
        }
        MESHES.incrementAndGet();
        return builder.build();
    }

    private static BakedQuad faceQuad(BakedModel model, BlockState state, Direction f, RandomSource random) {
        List<BakedQuad> quads = model.getQuads(state, f, random);
        if (!quads.isEmpty()) {
            return quads.get(0);
        }
        for (BakedQuad q : model.getQuads(state, null, random)) {
            if (q.getDirection() == f) {
                return q;
            }
        }
        return null;
    }
}
