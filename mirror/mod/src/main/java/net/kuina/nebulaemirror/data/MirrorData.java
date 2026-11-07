package net.kuina.nebulaemirror.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The generator's mirror/blocks.json (bundled at /nebulaemirror/mirror/), parsed into plain records.
 * Schema: see docs/generator.md.
 */
public final class MirrorData {
    public static final int SCHEMA = 1;
    private static final String ROOT = "/nebulaemirror/mirror/";

    public record Box(double x1, double y1, double z1, double x2, double y2, double z2) {
    }

    public record PropDef(String name, String kind, List<String> values, Integer min, Integer max,
                          boolean archetype, boolean ignored, boolean derived, boolean stored) {
    }

    /** Physical data of one blockstate variant; shape/collision index into {@link #shapes} (-1 = none given). */
    public record StateInfo(int light, boolean opaque, boolean fullCube, int shape, int collision,
                            boolean noCollision, boolean writable, Map<Integer, Integer> tints) {
    }

    public record Tint(String kind, int color) {
    }

    public record ItemDef(int meta, String key, Map<String, String> names, Map<String, String> state, String tab) {
    }

    public record BlockDef(String id, String source, String archetype, List<PropDef> properties,
                           Map<String, String> defaultState, String material, String sound, String renderLayer,
                           boolean emissive, String offset, boolean legacyBlockEntity, Map<Integer, Tint> tints,
                           Map<String, StateInfo> states, List<ItemDef> items, Map<String, String> names) {

        public String namespace() {
            return id.substring(0, id.indexOf(':'));
        }

        /** Properties that take part in variant keys (archetype-ignored ones such as waterlogged excluded). */
        public List<PropDef> keyedProperties() {
            List<PropDef> out = new ArrayList<>();
            for (PropDef p : properties) {
                if (!p.ignored()) {
                    out.add(p);
                }
            }
            return out;
        }

        /** Extra (non-archetype) properties the mirror block has to add itself. */
        public List<PropDef> extraProperties() {
            List<PropDef> out = new ArrayList<>();
            for (PropDef p : properties) {
                if (!p.archetype()) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    public final List<Box> shapes;
    public final List<BlockDef> blocks;
    public final Map<String, String> itemMeshes;

    private MirrorData(List<Box> shapes, List<BlockDef> blocks, Map<String, String> itemMeshes) {
        this.shapes = shapes;
        this.blocks = blocks;
        this.itemMeshes = itemMeshes;
    }

    public static MirrorData load() {
        JsonObject root = read("blocks.json").getAsJsonObject();
        int schema = root.get("schema").getAsInt();
        if (schema != SCHEMA) {
            throw new IllegalStateException("blocks.json schema " + schema + ", mod expects " + SCHEMA
                    + " — regenerate with the matching generator");
        }
        List<Box> shapes = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("shapes")) {
            JsonArray a = e.getAsJsonArray();
            shapes.add(new Box(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                    a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble()));
        }
        List<BlockDef> blocks = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("blocks")) {
            blocks.add(block(e.getAsJsonObject()));
        }
        Map<String, String> itemMeshes = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : read("item_meshes.json").getAsJsonObject().entrySet()) {
            itemMeshes.put(e.getKey(), e.getValue().getAsString());
        }
        return new MirrorData(Collections.unmodifiableList(shapes), Collections.unmodifiableList(blocks), itemMeshes);
    }

    private static BlockDef block(JsonObject o) {
        List<PropDef> props = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("properties")) {
            JsonObject p = e.getAsJsonObject();
            List<String> values = new ArrayList<>();
            for (JsonElement v : p.getAsJsonArray("values")) {
                values.add(v.getAsString());
            }
            props.add(new PropDef(p.get("name").getAsString(), p.get("kind").getAsString(), values,
                    p.has("min") ? p.get("min").getAsInt() : null, p.has("max") ? p.get("max").getAsInt() : null,
                    bool(p, "archetype"), bool(p, "ignored"), bool(p, "derived"), bool(p, "stored")));
        }
        Map<String, StateInfo> states = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("states").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            boolean noCollision = s.has("collision") && s.get("collision").isJsonNull();
            states.put(e.getKey(), new StateInfo(
                    s.has("light") ? s.get("light").getAsInt() : 0,
                    bool(s, "opaque"), bool(s, "fullCube"),
                    s.has("shape") ? s.get("shape").getAsInt() : -1,
                    s.has("collision") && !noCollision ? s.get("collision").getAsInt() : -1,
                    noCollision,
                    !s.has("writable") || s.get("writable").getAsBoolean(),
                    intMap(s, "tints")));
        }
        Map<Integer, Tint> tints = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tints").entrySet()) {
            JsonObject t = e.getValue().getAsJsonObject();
            tints.put(Integer.parseInt(e.getKey()), new Tint(t.get("kind").getAsString(),
                    t.has("color") ? t.get("color").getAsInt() : -1));
        }
        List<ItemDef> items = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("items")) {
            JsonObject i = e.getAsJsonObject();
            items.add(new ItemDef(i.get("meta").getAsInt(), i.get("key").getAsString(), strings(i, "names"),
                    i.has("state") ? strings(i, "state") : null, i.has("tab") ? i.get("tab").getAsString() : null));
        }
        return new BlockDef(o.get("id").getAsString(), o.get("source").getAsString(), o.get("archetype").getAsString(),
                props, strings(o, "default"), o.get("material").getAsString(), o.get("sound").getAsString(),
                o.get("renderLayer").getAsString(), bool(o, "emissive"),
                o.has("offset") && !o.get("offset").isJsonNull() ? o.get("offset").getAsString() : null,
                bool(o, "legacyBlockEntity"), tints, states, items, strings(o, "names"));
    }

    private static Map<Integer, Integer> intMap(JsonObject o, String key) {
        if (!o.has(key)) {
            return Map.of();
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
            out.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
        }
        return out;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static Map<String, String> strings(JsonObject o, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o.has(key) && o.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
                out.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
            }
        }
        return out;
    }

    private static JsonElement read(String name) {
        try (InputStream in = MirrorData.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("missing " + ROOT + name + " in the mod jar (generator output not bundled?)");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + ROOT + name, e);
        }
    }

    /** Variant key exactly as the generator writes it: keyed props sorted by name, "k=v" joined by ','. */
    public static String variantKey(Map<String, String> props) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
