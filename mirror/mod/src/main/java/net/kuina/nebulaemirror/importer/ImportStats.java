package net.kuina.nebulaemirror.importer;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters for one import run, written to nebulaemirror-import.json. */
public final class ImportStats {
    public final AtomicLong regions = new AtomicLong();
    public final AtomicLong chunks = new AtomicLong();
    public final AtomicLong failedChunks = new AtomicLong();
    public final AtomicLong moddedBlocks = new AtomicLong();
    public final AtomicLong unknownBlocks = new AtomicLong();
    public final AtomicLong legacyBlockEntities = new AtomicLong();
    public final AtomicLong droppedTileEntities = new AtomicLong();
    public final AtomicLong droppedEntities = new AtomicLong();
    public final AtomicLong shapeUpdates = new AtomicLong();
    public final AtomicLong aliasMetas = new AtomicLong();
    public final Map<String, AtomicLong> unknownByName = new ConcurrentHashMap<>();
    public final Map<String, String> errors = new ConcurrentHashMap<>();

    void unknown(String key) {
        unknownBlocks.incrementAndGet();
        unknownByName.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    void error(String where, Throwable t) {
        failedChunks.incrementAndGet();
        if (errors.size() < 200) {
            errors.put(where, t.toString());
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("regions", regions.get());
        o.addProperty("chunks", chunks.get());
        o.addProperty("failedChunks", failedChunks.get());
        o.addProperty("moddedBlocks", moddedBlocks.get());
        o.addProperty("unknownBlocks", unknownBlocks.get());
        o.addProperty("legacyBlockEntities", legacyBlockEntities.get());
        o.addProperty("droppedTileEntities", droppedTileEntities.get());
        o.addProperty("droppedEntities", droppedEntities.get());
        o.addProperty("shapeUpdates", shapeUpdates.get());
        JsonObject u = new JsonObject();
        new TreeMap<>(unknownByName).forEach((k, v) -> u.addProperty(k, v.get()));
        o.add("unknownByName", u);
        JsonObject e = new JsonObject();
        new TreeMap<>(errors).forEach(e::addProperty);
        o.add("errors", e);
        return o;
    }
}
