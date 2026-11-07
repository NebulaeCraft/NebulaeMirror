package net.kuina.nebulaemirror.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** One generator mesh (assets/nebulaemirror/meshes/<hash>.json): baked 1.12 quads in block space. */
public record MeshData(boolean ao, String particle, List<Quad> quads) {

    /** u/v are sprite-relative 0..16, like model JSON; color is ARGB per vertex (null = white). */
    public record Quad(String layer, String cull, String face, int tint, String sprite, boolean shade,
                       boolean emissive, float[][] vertices, int[] color) {
    }

    public static MeshData parse(JsonObject o) {
        List<Quad> quads = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("quads")) {
            JsonObject q = e.getAsJsonObject();
            JsonArray vs = q.getAsJsonArray("v");
            float[][] verts = new float[4][5];
            for (int i = 0; i < 4; i++) {
                JsonArray v = vs.get(i).getAsJsonArray();
                for (int j = 0; j < 5 && j < v.size(); j++) {
                    verts[i][j] = v.get(j).getAsFloat();
                }
            }
            int[] color = null;
            if (q.has("color")) {
                JsonArray c = q.getAsJsonArray("color");
                color = new int[4];
                for (int i = 0; i < 4; i++) {
                    color[i] = (int) c.get(i).getAsLong();
                }
            }
            quads.add(new Quad(
                    q.has("layer") ? q.get("layer").getAsString() : "solid",
                    q.has("cull") ? q.get("cull").getAsString() : null,
                    q.has("face") ? q.get("face").getAsString() : null,
                    q.has("tint") ? q.get("tint").getAsInt() : -1,
                    q.get("sprite").getAsString(),
                    !q.has("shade") || q.get("shade").getAsBoolean(),
                    q.has("emissive") && q.get("emissive").getAsBoolean(),
                    verts, color));
        }
        return new MeshData(!o.has("ao") || o.get("ao").getAsBoolean(),
                o.has("particle") ? o.get("particle").getAsString() : null, quads);
    }
}
