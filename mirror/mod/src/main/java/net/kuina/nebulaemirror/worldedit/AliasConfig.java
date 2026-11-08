package net.kuina.nebulaemirror.worldedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/nebulaemirror-we-aliases.json: short WorldEdit commands, written as players type them
 * ("//s": "//set"). The target may carry fixed arguments ("//0": "//set air"); whatever the player types after
 * the alias is appended. Created with {@link #defaults()} on first start; read again on /reload.
 */
public final class AliasConfig {
    public static final String FILE = "nebulaemirror-we-aliases.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public boolean enabled = true;
    public Map<String, String> aliases = defaults();

    /**
     * //s, //r, //c as on the origin's FAWE setup, plus FAWE's own aliases that WorldEdit 7.3 lacks
     * (//cp, //p, //pa, //mirror, //mv, //1, //2; //0 stands in for FAWE's //air).
     */
    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("//s", "//set");
        m.put("//r", "//replace");
        m.put("//c", "//copy");
        m.put("//cp", "//copy");
        m.put("//p", "//paste");
        m.put("//pa", "//paste");
        m.put("//mirror", "//flip");
        m.put("//mv", "//move");
        m.put("//1", "//pos1");
        m.put("//2", "//pos2");
        m.put("//0", "//set air");
        return m;
    }

    public static AliasConfig load(Path configDir) throws IOException {
        Path f = configDir.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            AliasConfig c = new AliasConfig();
            Files.createDirectories(configDir);
            Files.writeString(f, GSON.toJson(c), StandardCharsets.UTF_8);
            return c;
        }
        try {
            AliasConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AliasConfig.class);
            if (c == null) {
                throw new IOException(f + " is empty");
            }
            if (c.aliases == null) {
                c.aliases = new LinkedHashMap<>();
            }
            return c;
        } catch (JsonParseException e) {
            throw new IOException(f + ": " + e.getMessage(), e);
        }
    }
}
