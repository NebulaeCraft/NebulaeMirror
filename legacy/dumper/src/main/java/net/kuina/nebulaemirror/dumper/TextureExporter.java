package net.kuina.nebulaemirror.dumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies the original PNG (+ .mcmeta) of every sprite the baked quads use, at source resolution,
 * following CTM mcmeta "textures" references so connected-texture sheets come along.
 */
final class TextureExporter {
    private final File root;
    private final boolean vanilla;
    int copied;
    int fromAtlas;
    int skipped;
    private final AtlasReader atlas = new AtlasReader();
    final JsonArray missing = new JsonArray();

    TextureExporter(File root, boolean vanilla) {
        this.root = root;
        this.vanilla = vanilla;
    }

    JsonObject export(Set<String> spriteNames) {
        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        JsonObject index = new JsonObject();
        Deque<String> queue = new ArrayDeque<>(spriteNames);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name) || "missingno".equals(name)) {
                continue;
            }
            ResourceLocation rl = new ResourceLocation(name);
            JsonObject info = new JsonObject();
            TextureAtlasSprite sprite = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(name);
            if (sprite != null && !"missingno".equals(sprite.getIconName())) {
                info.addProperty("width", sprite.getIconWidth());
                info.addProperty("height", sprite.getIconHeight());
                info.addProperty("frames", sprite.getFrameCount());
            }
            if ("minecraft".equals(rl.getResourceDomain()) && !vanilla) {
                info.addProperty("status", "vanilla-skipped");
                index.add(name, info);
                skipped++;
                continue;
            }
            String base = "textures/" + rl.getResourcePath();
            File png = new File(root, rl.getResourceDomain() + "/" + rl.getResourcePath() + ".png");
            if (copy(rm, new ResourceLocation(rl.getResourceDomain(), base + ".png"), png)) {
                info.addProperty("status", "copied");
                copied++;
                File meta = new File(root, rl.getResourceDomain() + "/" + rl.getResourcePath() + ".png.mcmeta");
                if (copy(rm, new ResourceLocation(rl.getResourceDomain(), base + ".png.mcmeta"), meta)) {
                    info.addProperty("mcmeta", true);
                    for (String extra : ctmTextures(meta)) {
                        queue.add(extra);
                    }
                }
            } else if (sprite != null && !"missingno".equals(sprite.getIconName()) && atlas.read(sprite, png)) {
                // Generated at runtime (e.g. Railcraft splits sheets into *_0.._N): copy the uploaded pixels.
                info.addProperty("status", "atlas");
                fromAtlas++;
            } else {
                info.addProperty("status", "missing");
                missing.add(name);
            }
            index.add(name, info);
        }
        atlas.close();
        return index;
    }

    private static boolean copy(IResourceManager rm, ResourceLocation rl, File dest) {
        try (IResource res = rm.getResource(rl); InputStream in = res.getInputStream()) {
            dest.getParentFile().mkdirs();
            Files.write(dest.toPath(), IOUtils.toByteArray(in));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** CTM mcmeta: {"ctm": {"textures": ["chisel:blocks/x-ctm", ...]}} */
    private static Set<String> ctmTextures(File mcmeta) {
        Set<String> out = new HashSet<>();
        try {
            JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(mcmeta.toPath()), StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("ctm")) {
                JsonObject ctm = root.getAsJsonObject().getAsJsonObject("ctm");
                if (ctm.has("textures")) {
                    for (JsonElement t : ctm.getAsJsonArray("textures")) {
                        out.add(t.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Not JSON we understand; the raw file is still copied.
        }
        return out;
    }
}
