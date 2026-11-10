package com.crystalgraphics.platform.command;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Arguments that complete: a Brigadier {@code word} or {@code string} with a {@link SuggestionProvider}, never a custom
 * {@code ArgumentType}. A client without the mod joins a server with these commands, because a provider travels as
 * Minecraft's {@code ask_server} suggestions and a custom type is refused; and it needs no registry, which only the
 * versions from 1.19.2 have.
 *
 * <pre>{@code
 * dispatcher.register(literal("cgvar")
 *     .then(argument("name", word()).suggests(CgCommandArgs.varNames())                      // every variable, with its value
 *         .executes(ctx -> show(CgCommandArgs.one(getString(ctx, "name"), names(), "variable")))));
 *
 * argument("kit", word()).suggests(CgCommandArgs.choice(() -> kits.ids()));                  // a list read at each keystroke
 * argument("kit", word()).suggests(CgCommandArgs.choice(() -> kits.ids(), id -> kits.title(id)));   // with a tooltip each
 * }</pre>
 *
 * <ul>
 *   <li>A provider is asked on the server as the player types: it reads a list it already holds, and never blocks.</li>
 *   <li>A match is the candidate's start, or the start of any word in it (after {@code . _ / :}, or at a capital), so
 *       {@code bookBatch} is found by {@code batch} and {@code crystalgraphics.vfx.skip} by {@code skip}.</li>
 *   <li>The choice does not validate: the command does, with {@link #one}.</li>
 * </ul>
 */
public final class CgCommandArgs {

    private CgCommandArgs() {
    }

    /** Suggests each of {@code values} the typed text matches. */
    public static <S> SuggestionProvider<S> choice(Supplier<? extends Collection<String>> values) {
        return choice(values, null);
    }

    /** As {@link #choice(Supplier)}, each with a tooltip; {@code tooltip} may return null for none. */
    public static <S> SuggestionProvider<S> choice(Supplier<? extends Collection<String>> values,
                                                   Function<String, String> tooltip) {
        return (ctx, builder) -> suggest(builder, values.get(), tooltip);
    }

    /**
     * Every {@link CgVars variable}, its tooltip the description, the type and range, the value now and the source
     * that set it.
     */
    public static <S> SuggestionProvider<S> varNames() {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (CgVar<?> v : CgVars.all()) {
                if (!matches(typed, v.id())) continue;
                builder.suggest(v.id(), new LiteralMessage(describe(v)));
            }
            return builder.buildFuture();
        };
    }

    /** The value if it is one of {@code values}, else a command error naming {@code what}. */
    public static String one(String value, Collection<String> values, String what) throws CommandSyntaxException {
        if (values.contains(value)) return value;
        throw new SimpleCommandExceptionType(new LiteralMessage("Unknown " + what + " '" + value + "'")).create();
    }

    /**
     * Whether {@code candidate} matches {@code typed} (lower case): the candidate starts with it, or a word of the
     * candidate does. A word begins after {@code . _ / :}, and at a capital after a lower-case letter or digit.
     */
    public static boolean matches(String typed, String candidate) {
        if (typed.isEmpty()) return true;
        int n = typed.length();
        for (int i = 0; i + n <= candidate.length(); i++) {
            if (i > 0) {
                char prev = candidate.charAt(i - 1), ch = candidate.charAt(i);
                boolean separator = prev == '.' || prev == '_' || prev == '/' || prev == ':';
                boolean camel = Character.isUpperCase(ch) && (Character.isLowerCase(prev) || Character.isDigit(prev));
                if (!separator && !camel) continue;
            }
            if (candidate.regionMatches(true, i, typed, 0, n)) return true;
        }
        return false;
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            SuggestionsBuilder builder, Collection<String> values, Function<String, String> tooltip) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (!matches(typed, value)) continue;
            String tip = tooltip == null ? null : tooltip.apply(value);
            if (tip == null) builder.suggest(value); else builder.suggest(value, new LiteralMessage(tip));
        }
        return builder.buildFuture();
    }

    private static String describe(CgVar<?> v) {
        StringBuilder out = new StringBuilder(v.description()).append("\n").append(v.type());
        if (!v.range().isEmpty()) out.append(' ').append(v.range());
        out.append(" = ").append(v.text());
        CgVar.Source source = v.source();
        out.append(source == null ? " (default)" : " (" + source.name().toLowerCase(Locale.ROOT) + ")");
        return out.toString();
    }
}
