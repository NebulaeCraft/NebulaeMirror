package net.kuina.nebulaemirror.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncConfigChatTest {
    @Test
    void partialChatSectionIsCompletedOnDisk(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(SyncConfig.FILE);
        Files.writeString(f, "{\"source\": \"/w\", \"chat\": {\"enabled\": true, \"shownOnMirror\": {\"chat\": \"X {message}\"}}}",
                StandardCharsets.UTF_8);
        SyncConfig c = SyncConfig.load(dir);
        assertTrue(c.chat.enabled);
        assertEquals("X {message}", c.chat.shownOnMirror.chat);
        assertTrue(c.chat.shownOnMirror.enabled);
        assertEquals("", c.chat.shownOnMirror.join); // given section: unset templates are empty, i.e. not synced
        assertTrue(c.chat.shownOnOrigin.chat.contains("{player}")); // missing section: defaults
        String written = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"shownOnOrigin\""));
        assertTrue(written.contains("\"leave\""));
        assertTrue(written.contains("\"source\": \"/w\""));
        String again = Files.readString(f, StandardCharsets.UTF_8);
        SyncConfig.load(dir);
        assertEquals(again, Files.readString(f, StandardCharsets.UTF_8)); // complete now: not rewritten
    }

    @Test
    void templates() {
        SyncConfig.Direction d = new SyncConfig.Chat().shownOnMirror;
        assertTrue(d.template("chat").contains("{message}"));
        d.join = "";
        assertNull(d.template("join"));
        assertNull(d.template("other"));
        d.enabled = false;
        assertNull(d.template("chat"));
        assertFalse(new SyncConfig().chat.enabled);
    }
}
