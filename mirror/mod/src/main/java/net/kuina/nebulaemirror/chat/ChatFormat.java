package net.kuina.nebulaemirror.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Chat sync templates (docs/chat.md): {player} and {message} placeholders and & codes: &0-9a-f colours, &k-o
 * styles, &r reset, &#RRGGBB colours. Codes apply to the template only; the player name and message are inserted
 * as literal text in the current style, so "&c" typed by a player stays "&c".
 */
public final class ChatFormat {
    private ChatFormat() {
    }

    /** Receives the template piece by piece. */
    private interface Sink {
        void text(String s);

        /** A colour code: legacy colours also reset the styles. Null hex for a named colour. */
        void color(ChatFormatting named, Integer rgb);

        /** &k-&o, or &r (RESET). */
        void style(ChatFormatting f);
    }

    private static void walk(String template, String player, String message, Sink sink) {
        StringBuilder text = new StringBuilder();
        int n = template.length();
        for (int i = 0; i < n; i++) {
            char ch = template.charAt(i);
            if (ch == '&' && i + 1 < n) {
                char code = Character.toLowerCase(template.charAt(i + 1));
                if (code == '#' && i + 8 <= n && template.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    flush(text, sink);
                    sink.color(null, Integer.parseInt(template.substring(i + 2, i + 8), 16));
                    i += 7;
                    continue;
                }
                ChatFormatting f = ChatFormatting.getByCode(code);
                if (f != null) {
                    flush(text, sink);
                    if (f.isColor()) {
                        sink.color(f, null);
                    } else {
                        sink.style(f);
                    }
                    i++;
                    continue;
                }
            } else if (ch == '{') {
                String value = null;
                int len = 0;
                if (template.startsWith("{player}", i)) {
                    value = player;
                    len = 8;
                } else if (template.startsWith("{message}", i)) {
                    value = message;
                    len = 9;
                }
                if (len > 0) {
                    flush(text, sink);
                    String v = clean(value);
                    if (!v.isEmpty()) {
                        sink.text(v);
                    }
                    i += len - 1;
                    continue;
                }
            }
            text.append(ch);
        }
        flush(text, sink);
    }

    private static void flush(StringBuilder text, Sink sink) {
        if (!text.isEmpty()) {
            sink.text(text.toString());
            text.setLength(0);
        }
    }

    /** Player text never carries formatting codes of its own. */
    private static String clean(String s) {
        return s == null ? "" : s.replace("§", "");
    }

    /** For display on the mirror. */
    public static MutableComponent toComponent(String template, String player, String message) {
        MutableComponent root = Component.empty();
        Style[] style = {Style.EMPTY};
        walk(template, player, message, new Sink() {
            @Override
            public void text(String s) {
                root.append(Component.literal(s).withStyle(style[0]));
            }

            @Override
            public void color(ChatFormatting named, Integer rgb) {
                style[0] = Style.EMPTY.withColor(named != null ? TextColor.fromLegacyFormat(named) : TextColor.fromRgb(rgb));
            }

            @Override
            public void style(ChatFormatting f) {
                style[0] = f == ChatFormatting.RESET ? Style.EMPTY : style[0].applyFormat(f);
            }
        });
        return root;
    }

    /** For the 1.12 origin: § codes (hex colours are dropped, 1.12 has none). */
    public static String toLegacy(String template, String player, String message) {
        StringBuilder out = new StringBuilder();
        walk(template, player, message, new Sink() {
            @Override
            public void text(String s) {
                out.append(s);
            }

            @Override
            public void color(ChatFormatting named, Integer rgb) {
                // A hex colour still resets the styles, like a named one; §r is the closest 1.12 has.
                out.append('§').append(named != null ? named.getChar() : 'r');
            }

            @Override
            public void style(ChatFormatting f) {
                out.append('§').append(f.getChar());
            }
        });
        return out.toString();
    }
}
