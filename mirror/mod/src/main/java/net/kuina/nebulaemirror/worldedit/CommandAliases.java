package net.kuina.nebulaemirror.worldedit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Aliases that re-dispatch a rewritten command line. WorldEdit's Brigadier nodes ignore the parse tree and hand
 * {@code context.getInput()} to its own dispatcher, so a Brigadier redirect would reach WorldEdit as the unknown
 * command "//s"; instead each alias runs "target + typed arguments" through the dispatcher, and asks the target for
 * tab completions with the text shifted back into place. Independent of the source type so it can be unit tested.
 */
final class CommandAliases {
    private CommandAliases() {
    }

    record Result(List<String> registered, List<String> skipped) {
    }

    /**
     * Registers every alias whose target command exists and whose own name is still free. Keys and values are as
     * typed in chat ("//s" → "//set air"); one leading slash is the chat prefix and is dropped.
     */
    static <S> Result install(CommandDispatcher<S> dispatcher, Map<String, String> aliases) {
        List<String> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            String alias = stripChatSlash(e.getKey());
            String target = e.getValue() == null ? "" : stripChatSlash(e.getValue().trim());
            String why = null;
            CommandNode<S> targetNode = null;
            if (alias.isEmpty() || alias.chars().anyMatch(Character::isWhitespace)) {
                why = "invalid alias";
            } else if (target.isEmpty()) {
                why = "no target";
            } else if (dispatcher.getRoot().getChild(alias) != null) {
                why = "already a command";
            } else {
                targetNode = dispatcher.getRoot().getChild(firstWord(target));
                if (targetNode == null) {
                    why = "unknown target " + e.getValue();
                }
            }
            if (why != null) {
                skipped.add(e.getKey() + " (" + why + ")");
                continue;
            }
            CommandNode<S> node = targetNode;
            dispatcher.register(LiteralArgumentBuilder.<S>literal(alias)
                    .requires(node::canUse)
                    .executes(ctx -> run(dispatcher, ctx.getSource(), target, ""))
                    .then(RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
                            .suggests((ctx, b) -> suggest(dispatcher, ctx.getSource(), target, b))
                            .executes(ctx -> run(dispatcher, ctx.getSource(), target,
                                    StringArgumentType.getString(ctx, "args")))));
            registered.add(e.getKey() + " -> " + e.getValue().trim());
        }
        return new Result(registered, skipped);
    }

    static String rewrite(String target, String args) {
        return args.isEmpty() ? target : target + " " + args;
    }

    private static <S> int run(CommandDispatcher<S> d, S source, String target, String args)
            throws CommandSyntaxException {
        return d.execute(rewrite(target, args), source);
    }

    private static <S> CompletableFuture<Suggestions> suggest(CommandDispatcher<S> d, S source, String target,
                                                              SuggestionsBuilder builder) {
        String prefix = target + " ";
        ParseResults<S> parse = d.parse(prefix + builder.getRemaining(), source);
        int shift = builder.getStart() - prefix.length();
        return d.getCompletionSuggestions(parse).thenApply(s -> shift(builder.getInput(), s, prefix.length(), shift));
    }

    /** Moves suggestions on the rewritten line back onto the typed one; ones that touch the target's name are dropped. */
    static Suggestions shift(String input, Suggestions s, int argsStart, int shift) {
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion x : s.getList()) {
            StringRange r = x.getRange();
            if (r.getStart() < argsStart) {
                continue;
            }
            out.add(new Suggestion(new StringRange(r.getStart() + shift, r.getEnd() + shift), x.getText(), x.getTooltip()));
        }
        return Suggestions.create(input, out);
    }

    private static String stripChatSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static String firstWord(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }
}
