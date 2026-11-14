package net.kuina.nebulaemirror;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /nm short form: a redirect to the /nebulaemirror tree. */
class MirrorCommandsAliasTest {
    /** Source: the player's operator level. */
    private CommandDispatcher<Integer> d;
    private LiteralCommandNode<Integer> root;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void setUp() {
        d = new CommandDispatcher<>();
        root = d.register(LiteralArgumentBuilder.<Integer>literal(MirrorCommands.ROOT)
                .then(LiteralArgumentBuilder.<Integer>literal("sync")
                        .then(LiteralArgumentBuilder.<Integer>literal("status").requires(l -> l >= 2).executes(ctx -> {
                            ran.add("sync status");
                            return 7;
                        })))
                .then(LiteralArgumentBuilder.<Integer>literal("zone")
                        .then(LiteralArgumentBuilder.<Integer>literal("lint")
                                .then(RequiredArgumentBuilder.<Integer, String>argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> b.suggest("station").suggest("depot").buildFuture())
                                        .executes(ctx -> {
                                            ran.add("zone lint " + StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        })))));
    }

    private List<String> complete(String line, int level) {
        return d.getCompletionSuggestions(d.parse(line, level)).join().getList().stream()
                .map(Suggestion::getText).sorted().toList();
    }

    @Test
    void shortFormRunsTheSameSubCommands() throws CommandSyntaxException {
        assertTrue(MirrorCommands.alias(d, MirrorCommands.SHORT, root));
        assertEquals(7, d.execute("nm sync status", 2));
        assertEquals(1, d.execute("nm zone lint depot", 0));
        assertEquals(7, d.execute("nebulaemirror sync status", 2));
        assertEquals(List.of("sync status", "zone lint depot", "sync status"), ran);
    }

    @Test
    void shortFormKeepsPermissionChecks() {
        MirrorCommands.alias(d, MirrorCommands.SHORT, root);
        assertThrows(CommandSyntaxException.class, () -> d.execute("nm sync status", 0));
        assertTrue(ran.isEmpty());
    }

    @Test
    void shortFormCompletesLikeTheLongOne() {
        MirrorCommands.alias(d, MirrorCommands.SHORT, root);
        assertEquals(complete("nebulaemirror ", 2), complete("nm ", 2));
        assertEquals(List.of("sync", "zone"), complete("nm ", 2));
        assertEquals(List.of("depot", "station"), complete("nm zone lint ", 0));
        assertEquals(complete("nebulaemirror sync ", 2), complete("nm sync ", 2));
    }

    @Test
    void takenNameIsLeftAlone() throws CommandSyntaxException {
        d.register(LiteralArgumentBuilder.<Integer>literal("nm").executes(ctx -> 42));
        assertFalse(MirrorCommands.alias(d, MirrorCommands.SHORT, root));
        assertEquals(42, d.execute("nm", 0));
        assertThrows(CommandSyntaxException.class, () -> d.execute("nm sync status", 2));
    }
}
