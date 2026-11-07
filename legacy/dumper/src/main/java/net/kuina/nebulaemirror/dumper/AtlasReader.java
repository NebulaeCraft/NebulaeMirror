package net.kuina.nebulaemirror.dumper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;

/**
 * Reads one sprite's pixels back from the block atlas (mip level 0) through a temporary framebuffer,
 * so only the sprite's rectangle is transferred — the atlas itself can be 16k² in a big modpack.
 * Animated sprites only yield the frame currently uploaded.
 */
final class AtlasReader {
    private int fbo = -1;
    private boolean failed;

    boolean read(TextureAtlasSprite sprite, File dest) {
        if (failed || !OpenGlHelper.isFramebufferEnabled()) {
            return false;
        }
        try {
            if (fbo < 0) {
                fbo = OpenGlHelper.glGenFramebuffers();
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
                OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId(), 0);
                if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                    failed = true;
                    close();
                    return false;
                }
            } else {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
            }
            int w = sprite.getIconWidth();
            int h = sprite.getIconHeight();
            IntBuffer buf = BufferUtils.createIntBuffer(w * h);
            GL11.glReadPixels(sprite.getOriginX(), sprite.getOriginY(), w, h, GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            int[] px = new int[w * h];
            buf.get(px);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            dest.getParentFile().mkdirs();
            ImageIO.write(img, "png", dest);
            return true;
        } catch (Throwable t) {
            MirrorDumper.LOG.warn("Atlas readback failed for {}: {}", sprite.getIconName(), t.toString());
            return false;
        } finally {
            restore();
        }
    }

    private static void restore() {
        Minecraft mc = Minecraft.getMinecraft();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER,
                mc.getFramebuffer() == null ? 0 : mc.getFramebuffer().framebufferObject);
    }

    void close() {
        if (fbo >= 0) {
            restore();
            OpenGlHelper.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
    }
}
