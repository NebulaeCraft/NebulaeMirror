package net.kuina.nebulaemirror.importer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * forward.json resolved against one 1.12 world's numeric id registry: a flat table from (id << 4 | meta) to the
 * 1.21 palette entry. Vanilla ids are left to Minecraft's DataFixer (entry null, {@link #isModded} false).
 */
public final class ForwardTable {
    public static final String UNKNOWN_ID = NebulaeMirror.MOD_ID + ":unknown";
    public static final String LEGACY_BE_ID = NebulaeMirror.MOD_ID + ":legacy";

    /** Resolved 1.21 state for one 1.12 (id, meta). */
    public record Target(CompoundTag palette, boolean legacyBlockEntity, boolean shapeUpdate, boolean unknown) {
    }

    private final Target[] byState = new Target[4096 * 16];
    private final boolean[] modded = new boolean[4096];
    private final String[] names = new String[4096];
    private final Map<String, Integer> idsByName = new HashMap<>();
    /** 1.12 biome id -> vanilla 1.12 biome id (modded biomes replaced, see generator biomes.json). */
    public final int[] biomeRemap = new int[256];
    public final Map<String, String> biomeNotes = new HashMap<>();

    public ForwardTable(LegacyLevel level) {
        JsonObject forward = read("forward.json").getAsJsonObject().getAsJsonObject("blocks");
        Set<String> legacyBe = new HashSet<>();
        for (MirrorData.BlockDef d : NebulaeMirror.DATA.blocks) {
            if (d.legacyBlockEntity()) {
                legacyBe.add(d.id());
            }
        }
        for (Map.Entry<Integer, String> e : level.blocks.entrySet()) {
            int id = e.getKey();
            String name = e.getValue();
            if (id < 0 || id >= 4096) {
                continue;
            }
            names[id] = name;
            idsByName.put(name, id);
            if (name.startsWith("minecraft:")) {
                continue;
            }
            modded[id] = true;
            JsonObject fw = forward.has(name) ? forward.getAsJsonObject(name) : null;
            for (int meta = 0; meta < 16; meta++) {
                byState[id << 4 | meta] = resolve(name, meta, fw, legacyBe);
            }
        }
        for (int i = 0; i < 256; i++) {
            biomeRemap[i] = i;
        }
        JsonObject biomes = read("biomes.json").getAsJsonObject().getAsJsonObject("biomes");
        for (Map.Entry<Integer, String> e : level.biomes.entrySet()) {
            String name = e.getValue();
            if (name.startsWith("minecraft:") || e.getKey() < 0 || e.getKey() > 255) {
                continue;
            }
            String to = biomes.has(name) ? biomes.get(name).getAsString() : "minecraft:plains";
            Integer vanillaId = level.biomeIds.get(to);
            biomeRemap[e.getKey()] = vanillaId != null ? vanillaId : 1;
            biomeNotes.put(name, to);
        }
    }

    private static Target resolve(String name, int meta, JsonObject fw, Set<String> legacyBe) {
        if (fw == null || !fw.getAsJsonObject("metas").has(String.valueOf(meta))) {
            return null; // unknown placeholder, created per position (it records name + meta)
        }
        String key = String.valueOf(meta);
        String target = fw.has("metaTargets") && fw.getAsJsonObject("metaTargets").has(key)
                ? fw.getAsJsonObject("metaTargets").get(key).getAsString() : fw.get("target").getAsString();
        CompoundTag palette = new CompoundTag();
        palette.putString("Name", target);
        JsonObject props = fw.getAsJsonObject("metas").getAsJsonObject(key);
        if (!props.isEmpty()) {
            CompoundTag p = new CompoundTag();
            for (Map.Entry<String, JsonElement> pe : props.entrySet()) {
                p.putString(pe.getKey(), pe.getValue().getAsString());
            }
            palette.put("Properties", p);
        }
        boolean shape = fw.has("shapeUpdate") && fw.get("shapeUpdate").getAsBoolean();
        return new Target(palette, legacyBe.contains(target), shape, false);
    }

    public boolean isModded(int id) {
        return id >= 0 && id < 4096 && (modded[id] || names[id] == null);
    }

    public boolean isAir(int id) {
        return id == 0;
    }

    /** Resolved target, or null if this (id, meta) has no mapping and must become an unknown placeholder. */
    public Target target(int id, int meta) {
        return byState[id << 4 | meta];
    }

    public Integer id(String name) {
        return idsByName.get(name);
    }

    public String name(int id) {
        String n = names[id];
        return n != null ? n : "?unregistered:" + id;
    }

    private static JsonElement read(String name) {
        try (InputStream in = ForwardTable.class.getResourceAsStream("/nebulaemirror/mirror/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing /nebulaemirror/mirror/" + name + " in the mod jar");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
