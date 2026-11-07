package net.kuina.nebulaemirror.client.legacy;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * NebulaeCraft 2.26 roadmark_text, drawn from its TileEntity ("RoadmarkText", "RoadmarkColor"). Ported from
 * RenderRoadmarkText: the text is rasterised with the bundled road-marking fonts into a 180x390 texture that is
 * stretched over a flat 1.75 (two or more characters) or 1.25 (one character) by 1.875 block quad, 0.01 above the
 * block's floor, rotated by the block's facing. Textures are shared by every block with the same text and colour.
 */
final class RoadmarkText {
    private static final int TEX_W = 180;
    private static final int TEX_H = 390;
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static Font font;
    private static Font fontEn;
    private static boolean fontsLoaded;

    final String text;
    final int color;

    RoadmarkText(String text, int color) {
        this.text = text;
        this.color = color;
    }

    static String stats() {
        return TEXTURES.size() + " roadmark textures";
    }

    /** Render thread only. */
    void render(BlockState state, PoseStack pose, MultiBufferSource buffers, int light) {
        if (text.isEmpty()) {
            return;
        }
        ResourceLocation tex = texture();
        if (tex == null) {
            return;
        }
        Direction facing = facing(state);
        float angle = switch (facing) {
            case NORTH -> 180;
            case EAST -> 90;
            case WEST -> -90;
            default -> 0;
        };
        pose.pushPose();
        pose.translate(0.5, 0.01, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        float w = text.length() >= 2 ? 0.875f : 0.625f;
        float h = 0.9375f;
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        vc.addVertex(m, -w, 0, -h).setColor(-1).setUv(0, 0).setLight(light);
        vc.addVertex(m, -w, 0, h).setColor(-1).setUv(0, 1).setLight(light);
        vc.addVertex(m, w, 0, h).setColor(-1).setUv(1, 1).setLight(light);
        vc.addVertex(m, w, 0, -h).setColor(-1).setUv(1, 0).setLight(light);
        pose.popPose();
    }

    static Direction facing(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if ("facing".equals(p.getName()) && state.getValue(p) instanceof Direction d) {
                return d;
            }
        }
        return Direction.NORTH;
    }

    private ResourceLocation texture() {
        String key = color + "|" + text;
        ResourceLocation tex = TEXTURES.get(key);
        if (tex == null && !TEXTURES.containsKey(key)) {
            try {
                NativeImage image = rasterise(text, color);
                tex = NebulaeMirror.id("roadmark/" + Integer.toHexString(key.hashCode()) + "_" + TEXTURES.size());
                Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(image));
            } catch (Exception | LinkageError e) {
                NebulaeMirror.LOG.warn("Cannot draw roadmark text '{}': {}", text, e.toString());
                tex = null;
            }
            TEXTURES.put(key, tex);
        }
        return tex;
    }

    /** RenderRoadmarkText#generateTexture: the text's advance fills the width, its ink bounds the height. */
    private static NativeImage rasterise(String text, int color) {
        loadFonts();
        BufferedImage image = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(color));
        Font en = fontEn != null ? fontEn.deriveFont(100f) : null;
        Font cn = font.deriveFont(100f);
        // Per character: the Latin road font first, the full font for anything it lacks.
        AttributedString as = new AttributedString(text);
        for (int i = 0; i < text.length(); i++) {
            Font use = en != null && en.canDisplay(text.charAt(i)) ? en : cn;
            as.addAttribute(TextAttribute.FONT, use, i, i + 1);
        }
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout layout = new TextLayout(as.getIterator(), frc);
        Rectangle2D visual = layout.getBounds();
        double advance = layout.getAdvance();
        if (advance > 0 && visual.getHeight() > 0) {
            g.scale(TEX_W / advance, TEX_H / visual.getHeight());
            layout.draw(g, 0f, (float) -visual.getY());
        }
        g.dispose();
        NativeImage out = new NativeImage(TEX_W, TEX_H, false);
        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                int argb = image.getRGB(x, y);
                // NativeImage pixels are ABGR.
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                out.setPixelRGBA(x, y, abgr);
            }
        }
        return out;
    }

    private static void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;
        font = loadFont("roadmark/roadmark.ttf");
        fontEn = loadFont("roadmark/roadmark_en.ttf");
        if (font == null) {
            font = new Font("SansSerif", Font.BOLD, 100);
        }
    }

    private static Font loadFont(String path) {
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(NebulaeMirror.id(path));
        if (res.isEmpty()) {
            NebulaeMirror.LOG.warn("Missing roadmark font {} (run the generator with the NebulaeCraft source)", path);
            return null;
        }
        try (InputStream in = res.get().open()) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            NebulaeMirror.LOG.warn("Bad roadmark font {}: {}", path, e.toString());
            return null;
        }
    }
}
