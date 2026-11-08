package net.kuina.nebulaemirror.tabs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeTabConfigTest {
    @Test
    void firstStartListsEveryTab(@TempDir Path dir) throws IOException {
        Path f = dir.resolve(CreativeTabConfig.FILE);
        CreativeTabConfig c = CreativeTabConfig.load(f, List.of("nebulaecraft", "quark"));
        assertEquals(List.of("nebulaecraft", "quark"), List.copyOf(c.creativeTabs.keySet()));
        assertTrue(c.creativeTabs.get("quark").show);
        assertEquals("", c.creativeTabs.get("quark").icon);
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"nebulaecraft\": {"));
        assertTrue(text.contains("\"show\": true"));
    }

    @Test
    void userEntriesKeptAndNewTabsAppended(@TempDir Path dir) throws IOException {
        Path f = dir.resolve(CreativeTabConfig.FILE);
        Files.writeString(f, """
                {"creativeTabs": {
                  "quark": {"show": false},
                  "nebulaecraft": {"icon": "nebulaecraft:asphalt"},
                  "oldmod": {"show": false, "icon": ""}
                }}""", StandardCharsets.UTF_8);
        CreativeTabConfig c = CreativeTabConfig.load(f, List.of("nebulaecraft", "quark", "chisel"));
        assertFalse(c.creativeTabs.get("quark").show);
        assertEquals("", c.creativeTabs.get("quark").icon);
        assertTrue(c.creativeTabs.get("nebulaecraft").show);
        assertEquals("nebulaecraft:asphalt", c.creativeTabs.get("nebulaecraft").icon);
        assertEquals(List.of("quark", "nebulaecraft", "oldmod", "chisel"), List.copyOf(c.creativeTabs.keySet()));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("\"chisel\""));
        // complete now: loading again does not rewrite it
        String before = Files.readString(f, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1000));
        CreativeTabConfig.load(f, List.of("nebulaecraft", "quark", "chisel"));
        assertEquals(1000, Files.getLastModifiedTime(f).toMillis());
        assertEquals(before, Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void brokenFileIsAnErrorAndLeftAlone(@TempDir Path dir) throws IOException {
        Path f = dir.resolve(CreativeTabConfig.FILE);
        Files.writeString(f, "{\"creativeTabs\": {\"quark\": {\"show\": fals", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> CreativeTabConfig.load(f, List.of("quark")));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).endsWith("fals"));
    }

    @Test
    void iconSpecs() {
        assertNull(CreativeTabConfig.IconSpec.parse(""));
        assertNull(CreativeTabConfig.IconSpec.parse("  "));
        assertEquals(new CreativeTabConfig.IconSpec("nebulaecraft:asphalt", Map.of()),
                CreativeTabConfig.IconSpec.parse("nebulaecraft:asphalt"));
        assertEquals(new CreativeTabConfig.IconSpec("minecraft:stone", Map.of()),
                CreativeTabConfig.IconSpec.parse("stone"));
        assertEquals(new CreativeTabConfig.IconSpec("chisel:marble", Map.of("variation", "v3", "axis", "y")),
                CreativeTabConfig.IconSpec.parse("chisel:marble[variation=v3, axis=y]"));
        assertThrows(IllegalArgumentException.class, () -> CreativeTabConfig.IconSpec.parse("chisel:marble[variation"));
        assertThrows(IllegalArgumentException.class, () -> CreativeTabConfig.IconSpec.parse("chisel:marble[=v3]"));
        assertThrows(IllegalArgumentException.class, () -> CreativeTabConfig.IconSpec.parse("Chisel:Marble"));
    }
}
