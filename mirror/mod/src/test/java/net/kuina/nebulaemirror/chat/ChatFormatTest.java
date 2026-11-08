package net.kuina.nebulaemirror.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFormatTest {
    private static final String ORIGIN = "&7[来自镜像] &f{player} &7: &f{message}";

    @Test
    void legacyForOrigin() {
        assertEquals("§7[来自镜像] §fSteve §7: §fhello",
                ChatFormat.toLegacy(ORIGIN, "Steve", "hello"));
    }

    @Test
    void playerTextStaysLiteral() {
        assertEquals("§fa &c b {player}", ChatFormat.toLegacy("&f{message}", "Steve", "a &c b {player}"));
        assertEquals("§fx", ChatFormat.toLegacy("&f{message}", "Steve", "§x"));
        Component c = ChatFormat.toComponent("&f{message}", "Steve", "&c red?");
        assertEquals("&c red?", c.getString());
    }

    @Test
    void emptyAndMissingValues() {
        assertEquals("Steve joined", ChatFormat.toLegacy("{player} joined", "Steve", null));
        assertEquals("", ChatFormat.toLegacy("", "Steve", "x"));
        assertEquals("& {other} &z", ChatFormat.toLegacy("& {other} &z", "Steve", "x"));
    }

    @Test
    void stylesAndReset() {
        assertEquals("§l§cA§rB", ChatFormat.toLegacy("&L&CA&rB", "", ""));
        List<Style> styles = styles(ChatFormat.toComponent("&c&lA&rB&#00ff80C", "", ""));
        assertEquals(3, styles.size());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), styles.get(0).getColor());
        assertTrue(styles.get(0).isBold());
        assertEquals(Style.EMPTY, styles.get(1));
        assertEquals(TextColor.fromRgb(0x00ff80), styles.get(2).getColor());
        assertFalse(styles.get(2).isBold());
    }

    @Test
    void hexDowngradedForOrigin() {
        assertEquals("§rC", ChatFormat.toLegacy("&#00ff80C", "", ""));
    }

    @Test
    void componentMatchesText() {
        Component c = ChatFormat.toComponent("&7[来自源] &f{player} &7: &f{message}", "Alex", "hi");
        assertEquals("[来自源] Alex : hi", c.getString());
        List<Style> styles = styles(c);
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), styles.get(0).getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE), styles.get(1).getColor());
    }

    private static List<Style> styles(Component c) {
        List<Style> out = new ArrayList<>();
        for (Component sib : c.getSiblings()) {
            out.add(sib.getStyle());
        }
        return out;
    }
}
