package net.kuina.nebulaemirror.tabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client config/{@value #FILE}: per source mod, whether its creative tab is shown and which item is its icon.
 * Written on first start with every mirror tab; tabs that appear later (new generator output) are appended, and
 * entries for tabs that no longer exist are kept. See docs/creative-tabs.md.
 */
public final class CreativeTabConfig {
    public static final String FILE = "nebulaemirror-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Keyed by the source mod's namespace (the tab "Mirror: Nebulaecraft" is "nebulaecraft"). */
    public Map<String, Tab> creativeTabs = new LinkedHashMap<>();

    public static final class Tab {
        /** false: the tab is hidden; its items stay in the creative search. */
        public boolean show = true;
        /** "namespace:item" or "namespace:item[property=value,...]"; empty: the tab's first item. */
        public String icon = "";

        public Tab() {
        }

        public Tab(boolean show, String icon) {
            this.show = show;
            this.icon = icon;
        }
    }

    /** Item id plus block state properties of an icon entry. */
    public record IconSpec(String id, Map<String, String> state) {
        /** Null for an empty entry; throws IllegalArgumentException for a malformed one. */
        public static IconSpec parse(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return null;
            }
            Map<String, String> state = new LinkedHashMap<>();
            int open = s.indexOf('[');
            String id = s;
            if (open >= 0) {
                if (!s.endsWith("]")) {
                    throw new IllegalArgumentException("missing ']'");
                }
                id = s.substring(0, open).trim();
                String body = s.substring(open + 1, s.length() - 1).trim();
                if (!body.isEmpty()) {
                    for (String pair : body.split(",")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0 || eq == pair.length() - 1) {
                            throw new IllegalArgumentException("expected property=value, got '" + pair.trim() + "'");
                        }
                        state.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
            }
            if (!id.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid item id '" + id + "'");
            }
            return new IconSpec(id.contains(":") ? id : "minecraft:" + id, Map.copyOf(state));
        }
    }

    /** Reads the file, adds the given tabs that are missing, and writes it back if anything was added. */
    public static CreativeTabConfig load(Path file, Collection<String> tabs) throws IOException {
        CreativeTabConfig c;
        boolean write;
        if (Files.isRegularFile(file)) {
            try {
                c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CreativeTabConfig.class);
            } catch (JsonParseException e) {
                throw new IOException(file + ": " + e.getMessage(), e);
            }
            if (c == null) {
                throw new IOException(file + " is empty");
            }
            write = false;
        } else {
            c = new CreativeTabConfig();
            write = true;
        }
        if (c.creativeTabs == null) {
            c.creativeTabs = new LinkedHashMap<>();
            write = true;
        }
        c.creativeTabs.entrySet().removeIf(e -> e.getKey() == null);
        for (Map.Entry<String, Tab> e : c.creativeTabs.entrySet()) {
            if (e.getValue() == null) {
                e.setValue(new Tab());
                write = true;
            } else if (e.getValue().icon == null) {
                e.getValue().icon = "";
                write = true;
            }
        }
        for (String ns : tabs) {
            if (!c.creativeTabs.containsKey(ns)) {
                c.creativeTabs.put(ns, new Tab());
                write = true;
            }
        }
        if (write) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, GSON.toJson(c), StandardCharsets.UTF_8);
        }
        return c;
    }
}
