package net.kuina.nebulaemirror.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.kuina.nebulaemirror.writeback.ZoneOutlines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the boundary of every checked-out zone ({@link ZoneOutlines}), the way WorldEditCUI shows a selection:
 * the 12 edges of its box, cyan for the player's own zones and yellow for everyone else's. The parts behind
 * blocks are drawn faintly, so the boundary can be seen from inside buildings. The zones come from the server
 * and go away when it says so (submit, abandon) or on disconnect.
 */
final class ZoneOutlineRenderer {
    /** One edge of a zone's box; {@code own}: the zone is the player's own. */
    record Line(double x1, double y1, double z1, double x2, double y2, double z2, boolean own) {
    }

    /** RGB of the player's own zones (cyan) and of other players' (yellow). */
    private static final int OWN = 0x00FFFF, OTHERS = 0xFFFF00;
    /** Alpha in front of and behind blocks. */
    private static final int ALPHA = 235, ALPHA_HIDDEN = 70;
    private static final int GL_LEQUAL = 515, GL_GREATER = 516;

    private static volatile List<Line> lines = List.of();

    private ZoneOutlineRenderer() {
    }

    static void install() {
        ClientPlayNetworking.registerGlobalReceiver(ZoneOutlines.Payload.TYPE, (payload, ctx) -> {
            List<Line> out = new ArrayList<>();
            payload.zones().forEach(b -> addLines(b, out));
            lines = List.copyOf(out);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> lines = List.of());
        // With "Fabulous!" graphics the translucent layers are composited after this event, so the lines have
        // to go into the translucent target; otherwise they are drawn last, over the finished world.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
            if (ctx.advancedTranslucency() && visible(ctx)) {
                ctx.worldRenderer().getTranslucentTarget().bindWrite(false);
                try {
                    render(ctx);
                } finally {
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                }
            }
        });
        WorldRenderEvents.LAST.register(ctx -> {
            if (!ctx.advancedTranslucency() && visible(ctx)) {
                render(ctx);
            }
        });
    }

    private static boolean visible(WorldRenderContext ctx) {
        return !lines.isEmpty() && ctx.world() != null && ctx.world().dimension() == Level.OVERWORLD;
    }

    /** The 12 edges of the zone's box (world coordinates). */
    static void addLines(ZoneOutlines.Box b, List<Line> out) {
        // Block coordinates are inclusive: the box ends at the far side of the max block.
        double[] lo = {b.minX(), b.minY(), b.minZ()};
        double[] hi = {b.maxX() + 1.0, b.maxY() + 1.0, b.maxZ() + 1.0};
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            // The 4 edges along `axis`: one at each corner of the (u, v) rectangle.
            for (int corner = 0; corner < 4; corner++) {
                double[] from = new double[3];
                double[] to = new double[3];
                from[axis] = lo[axis];
                to[axis] = hi[axis];
                from[u] = to[u] = (corner & 1) == 0 ? lo[u] : hi[u];
                from[v] = to[v] = (corner & 2) == 0 ? lo[v] : hi[v];
                out.add(new Line(from[0], from[1], from[2], to[0], to[1], to[2], b.own()));
            }
        }
    }

    private static void render(WorldRenderContext ctx) {
        List<Line> all = lines;
        Vec3 cam = ctx.camera().getPosition();
        // The model-view matrix still holds the camera rotation here; positions are relative to the camera.
        RenderSystem.lineWidth(3f * Math.max(1f, Minecraft.getInstance().getWindow().getWidth() / 1920f));
        float fogStart = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE); // the boundary stays visible at any distance
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        try {
            // Behind blocks first (faint), then what is in plain view.
            RenderSystem.depthFunc(GL_GREATER);
            draw(all, cam, ALPHA_HIDDEN);
            RenderSystem.depthFunc(GL_LEQUAL);
            draw(all, cam, ALPHA);
        } finally {
            RenderSystem.depthFunc(GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.lineWidth(1f);
            RenderSystem.setShaderFogStart(fogStart);
        }
    }

    private static void draw(List<Line> all, Vec3 cam, int alpha) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (Line l : all) {
            float x1 = (float) (l.x1() - cam.x), y1 = (float) (l.y1() - cam.y), z1 = (float) (l.z1() - cam.z);
            float x2 = (float) (l.x2() - cam.x), y2 = (float) (l.y2() - cam.y), z2 = (float) (l.z2() - cam.z);
            // Lines are axis-aligned: the normal (the line's direction, for the lines shader) is a unit axis.
            float nx = Math.signum(x2 - x1), ny = Math.signum(y2 - y1), nz = Math.signum(z2 - z1);
            int rgb = l.own() ? OWN : OTHERS;
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            buf.addVertex(x1, y1, z1).setColor(r, g, b, alpha).setNormal(nx, ny, nz);
            buf.addVertex(x2, y2, z2).setColor(r, g, b, alpha).setNormal(nx, ny, nz);
        }
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }
}
