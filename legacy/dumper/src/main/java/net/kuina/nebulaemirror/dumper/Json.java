package net.kuina.nebulaemirror.dumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.util.math.AxisAlignedBB;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class Json {
    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    /** Fixed precision keeps model hashes stable across runs. */
    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        double r = Math.round(v * f) / f;
        return r == -0.0 ? 0.0 : r;
    }

    static JsonArray box(AxisAlignedBB b) {
        JsonArray a = new JsonArray();
        a.add(round(b.minX, 4));
        a.add(round(b.minY, 4));
        a.add(round(b.minZ, 4));
        a.add(round(b.maxX, 4));
        a.add(round(b.maxY, 4));
        a.add(round(b.maxZ, 4));
        return a;
    }

    static void write(File file, JsonElement json, boolean pretty) throws IOException {
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            (pretty ? PRETTY : COMPACT).toJson(json, w);
        }
    }
}
