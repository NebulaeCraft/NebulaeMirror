package net.kuina.nebulaemirror.dumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.LightUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content-addressed store of baked quad lists: {@code models/<h0h1>/<sha1>.json}.
 * Identical geometry (very common across facing/subtype variants of the same model) is written once.
 */
final class ModelStore {
    private final File root;
    private final Set<String> written = new HashSet<>();
    final Set<String> sprites = new TreeSet<>();
    int quadCount;

    ModelStore(File root) {
        this.root = root;
    }

    int size() {
        return written.size();
    }

    /** Builder for the quads of one (state, render layer). */
    final class Builder {
        private final JsonArray quads = new JsonArray();
        final Set<Integer> tints = new TreeSet<>();

        void add(EnumFacing cull, BakedQuad quad) {
            quads.add(serialize(cull, quad, tints));
        }

        boolean isEmpty() {
            return quads.size() == 0;
        }

        /** @return the content hash, or null if there were no quads. */
        String store() throws IOException {
            if (isEmpty()) {
                return null;
            }
            JsonObject model = new JsonObject();
            model.add("quads", quads);
            String text = Json.COMPACT.toJson(model);
            String hash = sha1(text);
            if (written.add(hash)) {
                File f = new File(root, hash.substring(0, 2) + "/" + hash + ".json");
                f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                quadCount += quads.size();
            }
            return hash;
        }
    }

    Builder builder() {
        return new Builder();
    }

    private JsonObject serialize(EnumFacing cull, BakedQuad quad, Set<Integer> tints) {
        JsonObject q = new JsonObject();
        if (cull != null) {
            q.addProperty("cull", cull.getName());
        }
        if (quad.getFace() != null) {
            q.addProperty("face", quad.getFace().getName());
        }
        if (quad.hasTintIndex()) {
            q.addProperty("tint", quad.getTintIndex());
            tints.add(quad.getTintIndex());
        }
        TextureAtlasSprite sprite = quad.getSprite();
        String spriteName = sprite == null ? "missingno" : sprite.getIconName();
        q.addProperty("sprite", spriteName);
        sprites.add(spriteName);
        if (!quad.shouldApplyDiffuseLighting()) {
            q.addProperty("shade", false);
        }

        VertexFormat fmt = quad.getFormat();
        int pos = -1, uv = -1, light = -1, color = -1;
        for (int e = 0; e < fmt.getElementCount(); e++) {
            VertexFormatElement el = fmt.getElement(e);
            switch (el.getUsage()) {
                case POSITION: pos = e; break;
                case COLOR: color = e; break;
                case UV:
                    if (el.getIndex() == 0) uv = e;
                    else if (el.getIndex() == 1) light = e;
                    break;
                default: break;
            }
        }

        int[] data = quad.getVertexData();
        float[] buf = new float[4];
        JsonArray verts = new JsonArray();
        JsonArray colors = new JsonArray();
        boolean allWhite = true;
        int maxBlock = 0, maxSky = 0;
        for (int v = 0; v < 4; v++) {
            JsonArray vert = new JsonArray();
            if (pos >= 0) {
                LightUtil.unpack(data, buf, fmt, v, pos);
                vert.add(Json.round(buf[0], 5));
                vert.add(Json.round(buf[1], 5));
                vert.add(Json.round(buf[2], 5));
            }
            if (uv >= 0) {
                LightUtil.unpack(data, buf, fmt, v, uv);
                // Store sprite-relative UVs in 0..16, like model JSON, so atlas layout doesn't matter.
                float u = sprite == null ? buf[0] * 16 : sprite.getUnInterpolatedU(buf[0]);
                float w = sprite == null ? buf[1] * 16 : sprite.getUnInterpolatedV(buf[1]);
                vert.add(Json.round(u, 4));
                vert.add(Json.round(w, 4));
            }
            verts.add(vert);
            if (color >= 0) {
                buf[3] = 1;
                LightUtil.unpack(data, buf, fmt, v, color);
                int argb = (clamp(buf[3]) << 24) | (clamp(buf[0]) << 16) | (clamp(buf[1]) << 8) | clamp(buf[2]);
                colors.add(argb);
                allWhite &= argb == 0xFFFFFFFF;
            }
            if (light >= 0) {
                LightUtil.unpack(data, buf, fmt, v, light);
                // LightUtil normalises SHORT attributes against 32767.
                maxBlock = Math.max(maxBlock, Math.round(buf[0] * 32767));
                maxSky = Math.max(maxSky, Math.round(buf[1] * 32767));
            }
        }
        q.add("v", verts);
        if (!allWhite) {
            q.add("color", colors);
        }
        if (maxBlock > 0 || maxSky > 0) {
            JsonArray l = new JsonArray();
            l.add(maxBlock);
            l.add(maxSky);
            q.add("light", l);
            if (maxBlock >= 240) {
                q.addProperty("emissive", true);
            }
        }
        return q;
    }

    private static int clamp(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean anyQuads(List<BakedQuad> quads) {
        return quads != null && !quads.isEmpty();
    }
}
