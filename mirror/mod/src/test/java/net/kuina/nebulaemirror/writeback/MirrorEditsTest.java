package net.kuina.nebulaemirror.writeback;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorEditsTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        // MirrorEdits refers to Level, whose static setup needs the registries.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @SuppressWarnings("unchecked")
    private static void mark(MirrorEdits m, int x, int z) throws Exception {
        Field f = MirrorEdits.class.getDeclaredField("edited");
        f.setAccessible(true);
        Field s = MirrorEdits.class.getDeclaredField("seq");
        s.setAccessible(true);
        ((Map<Long, Long>) f.get(m)).put(ChunkPos.asLong(x, z),
                ((java.util.concurrent.atomic.AtomicLong) s.get(m)).incrementAndGet());
        Field d = MirrorEdits.class.getDeclaredField("dirty");
        d.setAccessible(true);
        d.setBoolean(m, true);
    }

    @Test
    void snapshotForgetAndPersist(@TempDir Path dir) throws Exception {
        MirrorEdits.start(dir);
        MirrorEdits m = MirrorEdits.get();
        mark(m, 1, 1);
        mark(m, 5, -3);
        mark(m, 100, 100);
        assertEquals(2, m.snapshot(new int[]{0, -5, 10, 5}).size(), "range filter");
        Map<Long, Long> snap = m.snapshot(null);
        mark(m, 5, -3); // edited again while the sync ran: must stay
        m.forget(snap);
        assertEquals(1, m.size());
        assertTrue(m.snapshot(null).containsKey(ChunkPos.asLong(5, -3)));
        MirrorEdits.stop();
        MirrorEdits.start(dir);
        assertEquals(1, MirrorEdits.get().size(), "kept across restarts");
        MirrorEdits.stop();
    }

    @Test
    void quietNests() {
        MirrorEdits.enterQuiet();
        MirrorEdits.quietly(() -> { });
        MirrorEdits.exitQuiet();
        MirrorEdits.exitQuiet(); // extra exits do not go negative
        MirrorEdits.quietly(() -> { });
    }
}
