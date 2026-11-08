package net.kuina.nebulaemirror.worldedit;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAliasesTest {
    /** Source: the player's operator level. */
    private CommandDispatcher<Integer> d;
    private final List<String> ran = new ArrayList<>();

    /** Like WorldEdit's CommandWrapper: literal + greedy "args", executor and suggestions read the raw input. */
    private void worldEdit(String name, int level) {
        Command<Integer> cmd = ctx -> {
            ran.add("/" + ctx.getInput());
            return 0;
        };
        d.register(LiteralArgumentBuilder.<Integer>literal(name).requires(l -> l >= level).executes(cmd)
                .then(RequiredArgumentBuilder.<Integer, String>argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            // suggests block names for the word being typed, on the raw input like WorldEdit
                            String in = b.getInput();
                            int start = in.lastIndexOf(' ') + 1;
                            List<Suggestion> l = new ArrayList<>();
                            for (String s : List.of("stone", "stone_bricks", "air")) {
                                if (s.startsWith(in.substring(start))) {
                                    l.add(new Suggestion(com.mojang.brigadier.context.StringRange.between(start, in.length()), s));
                                }
                            }
                            return java.util.concurrent.CompletableFuture.completedFuture(Suggestions.create(in, l));
                        })
                        .executes(cmd)));
    }

    @BeforeEach
    void setUp() {
        d = new CommandDispatcher<>();
        worldEdit("/set", 0);
        worldEdit("/replace", 0);
        worldEdit("/re", 0); // WorldEdit's own alias of //replace
        worldEdit("/copy", 2);
    }

    private CommandAliases.Result install(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return CommandAliases.install(d, m);
    }

    @Test
    void runsTargetWithTypedArguments() throws CommandSyntaxException {
        install("//s", "//set", "//r", "//replace", "//0", "//set air");
        d.execute("/s stone", 0);
        d.execute("/r stone,dirt 50%glass,50%air", 0);
        d.execute("/s", 0);
        d.execute("/0", 0);
        d.execute("/0 -m", 0);
        assertEquals(List.of("//set stone", "//replace stone,dirt 50%glass,50%air", "//set", "//set air",
                "//set air -m"), ran);
    }

    @Test
    void skipsTakenNamesAndMissingTargets() {
        CommandAliases.Result r = install("//re", "//set", "//p", "//paste", "//bad name", "//set", "//c", "//copy");
        assertEquals(List.of("//c -> //copy"), r.registered());
        assertEquals(3, r.skipped().size());
        assertNull(d.getRoot().getChild("/p"));
        assertNotNull(d.getRoot().getChild("/c"));
    }

    @Test
    void inheritsTargetPermission() {
        install("//c", "//copy");
        assertThrows(CommandSyntaxException.class, () -> d.execute("/c", 0));
        assertFalse(d.getRoot().getChild("/c").canUse(0));
        assertTrue(d.getRoot().getChild("/c").canUse(2));
    }

    @Test
    void suggestionsLandOnTypedText() {
        install("//r", "//replace");
        String typed = "/r dirt sto";
        Suggestions s = d.getCompletionSuggestions(d.parse(typed, 0)).join();
        assertEquals(List.of("stone", "stone_bricks"), s.getList().stream().map(Suggestion::getText).toList());
        for (Suggestion x : s.getList()) {
            assertEquals(typed.lastIndexOf(' ') + 1, x.getRange().getStart());
            assertEquals(typed.length(), x.getRange().getEnd());
        }
        assertEquals("/r dirt stone", s.getList().get(0).apply(typed));
    }

    @Test
    void configWrittenWithDefaults(@TempDir Path dir) throws Exception {
        AliasConfig c = AliasConfig.load(dir);
        assertTrue(c.enabled);
        assertEquals("//set", c.aliases.get("//s"));
        String text = Files.readString(dir.resolve(AliasConfig.FILE), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"//r\": \"//replace\""));
        Files.writeString(dir.resolve(AliasConfig.FILE), "{\"aliases\": {\"//h\": \"//hollow\"}}", StandardCharsets.UTF_8);
        c = AliasConfig.load(dir);
        assertEquals(Map.of("//h", "//hollow"), c.aliases);
        Files.writeString(dir.resolve(AliasConfig.FILE), "{not json", StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> AliasConfig.load(dir));
    }
}
