package com.crystalgraphics.platform.command;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The command seam: a contribution built into a dispatcher through a view, run and completed over a source, with the
 * suggestions an argument gives and the tooltips the variables carry. No host: the standalone dispatcher is the host.
 */
public class CgCommandsTest {

    private static final CgVar.Int BATCH = CgVar.integer("crystalgraphics.test.commands.bookBatch", 256, 1, 4096,
            "Effects a worker books in one task.");

    /** Registered once for the class: the contributions are global, as a mod's are. */
    private static final List<String> KITS = Arrays.asList("rpgcore", "kakarot_kit", "spzero");

    static {
        CgCommands.server(new Contribution());
    }

    /** {@code cg echo <word>}, {@code cg kit <kit>} and {@code cg var <name>}, written over any source. */
    private static final class Contribution implements CgCommands.Contribution {
        @Override
        public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
            dispatcher.register(LiteralArgumentBuilder.<S>literal("cg")
                    .then(LiteralArgumentBuilder.<S>literal("echo").then(RequiredArgumentBuilder.<S, String>argument("text", word()).executes(ctx -> {
                        view.apply(ctx.getSource()).reply(getString(ctx, "text"));
                        return 7;
                    })))
                    .then(LiteralArgumentBuilder.<S>literal("kit").then(RequiredArgumentBuilder.<S, String>argument("kit", word())
                            .suggests(CgCommandArgs.choice(() -> KITS, id -> "kit " + id))
                            .executes(ctx -> {
                                view.apply(ctx.getSource()).reply(CgCommandArgs.one(getString(ctx, "kit"), KITS, "kit"));
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<S>literal("var").then(RequiredArgumentBuilder.<S, String>argument("name", word()).suggests(CgCommandArgs.varNames())))
                    .then(LiteralArgumentBuilder.<S>literal("op").requires(s -> view.apply(s).level() >= 2).executes(ctx -> 1)));
        }
    }

    private static CgCommandSource source(int level, List<String> out) {
        return CgCommandSource.of(CgCommandSource.Kind.CONSOLE, "console", level, out::add);
    }

    private static List<String> texts(Suggestions s) {
        return s.getList().stream().map(Suggestion::getText).collect(Collectors.toList());
    }

    @Test
    public void runsALineWithOrWithoutItsSlash() {
        List<String> out = new ArrayList<>();
        assertEquals(7, CgCommands.run(CgCommands.Side.SERVER, source(0, out), "cg echo hello"));
        assertEquals(7, CgCommands.run(CgCommands.Side.SERVER, source(0, out), "/cg echo again"));
        assertEquals(Arrays.asList("hello", "again"), out);
    }

    @Test
    public void aFailureGoesToTheSourceAndReturnsZero() {
        List<String> out = new ArrayList<>();
        assertEquals(0, CgCommands.run(CgCommands.Side.SERVER, source(0, out), "cg nope"));
        assertEquals(0, CgCommands.run(CgCommands.Side.SERVER, source(0, out), "cg kit nosuch"));
        assertEquals(2, out.size());
        assertTrue(out.get(1), out.get(1).contains("Unknown kit 'nosuch'"));
    }

    @Test
    public void requiresIsReadThroughTheView() {
        List<String> out = new ArrayList<>();
        assertEquals(0, CgCommands.run(CgCommands.Side.SERVER, source(0, out), "cg op"));
        assertEquals(1, CgCommands.run(CgCommands.Side.SERVER, source(2, out), "cg op"));
    }

    @Test
    public void aSideRunsOnlyItsOwnCommands() {
        List<String> out = new ArrayList<>();
        assertEquals(0, CgCommands.run(CgCommands.Side.CLIENT, source(4, out), "cg echo x"));
        assertEquals(1, out.size());
    }

    @Test
    public void aLiteralTheSourceMayNotRunIsNotSuggested() throws Exception {
        List<String> low = texts(CgCommands.suggest(CgCommands.Side.SERVER, source(0, new ArrayList<>()), "cg ", 3).get());
        List<String> op = texts(CgCommands.suggest(CgCommands.Side.SERVER, source(2, new ArrayList<>()), "cg ", 3).get());
        assertFalse(low.contains("op"));
        assertTrue(op.contains("op"));
    }

    @Test
    public void suggestsLiteralsAndChoicesWithTooltips() throws Exception {
        CgCommandSource s = source(0, new ArrayList<>());
        assertEquals(Arrays.asList("echo", "kit", "var"), texts(CgCommands.suggest(CgCommands.Side.SERVER, s, "cg ", 3).get()));

        Suggestions kits = CgCommands.suggest(CgCommands.Side.SERVER, s, "cg kit ", 7).get();
        assertEquals(Arrays.asList("kakarot_kit", "rpgcore", "spzero"), texts(kits));
        assertEquals("kit rpgcore", kits.getList().stream()
                .filter(x -> x.getText().equals("rpgcore")).findFirst().get().getTooltip().getString());
    }

    @Test
    public void suggestionsReplaceTheTypedWordAndMatchAtAWordStart() throws Exception {
        CgCommandSource s = source(0, new ArrayList<>());
        Suggestions typed = CgCommands.suggest(CgCommands.Side.SERVER, s, "cg kit kit", 10).get();
        assertEquals(Arrays.asList("kakarot_kit"), texts(typed));
        assertEquals(7, typed.getRange().getStart());
        assertEquals(10, typed.getRange().getEnd());
    }

    @Test
    public void aCursorInTheMiddleCompletesWhatIsBeforeIt() throws Exception {
        CgCommandSource s = source(0, new ArrayList<>());
        Suggestions at = CgCommands.suggest(CgCommands.Side.SERVER, s, "cg kit rp trailing", 9).get();
        assertEquals(Arrays.asList("rpgcore"), texts(at));
    }

    @Test
    public void variablesCompleteWithTheirValueAndSource() throws Exception {
        CgCommandSource s = source(0, new ArrayList<>());
        Suggestions defaults = CgCommands.suggest(CgCommands.Side.SERVER, s, "cg var batch", 12).get();
        assertEquals(Arrays.asList(BATCH.id()), texts(defaults));
        String tip = defaults.getList().get(0).getTooltip().getString();
        assertTrue(tip, tip.startsWith("Effects a worker books in one task.\nint "));
        assertTrue(tip, tip.endsWith("= 256 (default)"));

        BATCH.set(CgVar.Source.CONSOLE, 64);
        String set = CgCommands.suggest(CgCommands.Side.SERVER, s, "cg var batch", 12).get()
                .getList().get(0).getTooltip().getString();
        assertTrue(set, set.endsWith("= 64 (console)"));
        BATCH.clear(CgVar.Source.CONSOLE);
        assertTrue(CgVars.get(BATCH.id()) == BATCH);
    }

    @Test
    public void matchingIsAWordStartAfterASeparator() {
        assertTrue(CgCommandArgs.matches("", "anything"));
        assertTrue(CgCommandArgs.matches("skip", "crystalgraphics.vfx.skip"));
        assertTrue(CgCommandArgs.matches("book", "crystalgraphics.vfx.book_batch"));
        assertTrue(CgCommandArgs.matches("batch", "book_batch"));
        assertTrue(CgCommandArgs.matches("batch", "crystalgraphics.vfx.bookBatch"));
        assertFalse(CgCommandArgs.matches("atch", "book_batch"));
        assertFalse(CgCommandArgs.matches("zzz", "book_batch"));
    }

    @Test
    public void installPutsTheContributionsIntoAnyDispatcher() throws Exception {
        CommandDispatcher<String> host = new CommandDispatcher<>();
        List<String> out = new ArrayList<>();
        CgCommands.install(CgCommands.Side.SERVER, host,
                s -> CgCommandSource.of(CgCommandSource.Kind.PLAYER, s, 0, out::add));
        assertEquals(7, host.execute("cg echo hi", "Steve"));
        assertEquals(Arrays.asList("hi"), out);
    }
}
