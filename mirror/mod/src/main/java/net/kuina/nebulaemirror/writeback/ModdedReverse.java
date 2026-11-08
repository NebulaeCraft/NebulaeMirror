package net.kuina.nebulaemirror.writeback;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The generator's reverse.json: mirror blocks and override targets (FutureMC/Quark onto 1.21 vanilla) ->
 * 1.12 (name, meta). Only properties 1.12 stores in meta are in the key ({@code keys}).
 */
public final class ModdedReverse {
    private record Entry(List<String> keys, Map<String, LegacyBlock> map) {
    }

    private final Map<String, Entry> blocks = new HashMap<>();

    public static ModdedReverse load() {
        try (InputStream in = ModdedReverse.class.getResourceAsStream("/nebulaemirror/mirror/reverse.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /nebulaemirror/mirror/reverse.json in the mod jar");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(r).getAsJsonObject());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ModdedReverse parse(JsonObject root) {
        ModdedReverse m = new ModdedReverse();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("blocks").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            List<String> keys = new ArrayList<>();
            for (JsonElement k : o.getAsJsonArray("keys")) {
                keys.add(k.getAsString());
            }
            Map<String, LegacyBlock> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> me : o.getAsJsonObject("map").entrySet()) {
                JsonArray a = me.getValue().getAsJsonArray();
                map.put(me.getKey(), new LegacyBlock(a.get(0).getAsString(), a.get(1).getAsInt()));
            }
            m.blocks.put(e.getKey(), new Entry(keys, map));
        }
        return m;
    }

    public boolean covers(BlockState s) {
        return blocks.containsKey(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
    }

    /** The 1.12 block for this state, or null (not a mapped block, or a state 1.12 cannot store). */
    public LegacyBlock reverse(BlockState s) {
        Entry e = blocks.get(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        if (e == null) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (String k : e.keys()) {
            Property<?> p = s.getBlock().getStateDefinition().getProperty(k);
            if (p == null) {
                return null;
            }
            if (!key.isEmpty()) {
                key.append(',');
            }
            key.append(k).append('=').append(value(s, p));
        }
        return e.map().get(key.toString());
    }

    private static <T extends Comparable<T>> String value(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    public int size() {
        return blocks.size();
    }
}
