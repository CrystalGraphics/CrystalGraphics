package com.crystalgraphics.platform.command;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Commands on every version, as Brigadier's own: a mod contributes commands once, and each host puts them into the
 * dispatcher Minecraft already runs, so the client's command tree, its suggestions, colouring and usage are
 * Minecraft's. A host with no Brigadier (1.7.10 and 1.8.9-1.12.2) bundles it and adapts each root command to its own
 * command class.
 *
 * <p>A contribution is generic over the source {@code S} of the dispatcher it is put into, so the same code builds
 * into Minecraft's {@code CommandDispatcher<CommandSourceStack>} on 26.2 and into a console's
 * {@code CommandDispatcher<CgCommandSource>}. It reads its source through a view.</p>
 *
 * <pre>{@code
 * // once, at init: a class, since a generic method cannot be a lambda
 * final class VarCommands implements CgCommands.Contribution {
 *     @Override public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
 *         dispatcher.register(LiteralArgumentBuilder.<S>literal("cgvar")
 *             .requires(s -> view.apply(s).level() >= 2)
 *             .then(RequiredArgumentBuilder.<S, String>argument("name", word()).suggests(CgCommandArgs.varNames())
 *                 .executes(ctx -> {
 *                     view.apply(ctx.getSource()).reply(CgVars.get(getString(ctx, "name")).text());
 *                     return 1;
 *                 })));
 *     }
 * }
 * CgCommands.client(new VarCommands());                       // a client command; server(...) for the server's
 *
 * // a host, from the loader's registration event
 * CgCommands.install(CgCommands.Side.SERVER, event.getDispatcher(), CommandsModern::view);
 *
 * // a console, a script, a keybind, a remote tool
 * CgCommands.run(CgCommands.Side.CLIENT, source, "cgvar crystalgraphics.vfx.bookBatch");
 * CgCommands.suggest(CgCommands.Side.CLIENT, source, "cgvar cry", 9).thenAccept(show);
 * }</pre>
 *
 * <ul>
 *   <li>Name the source type on the first builder, {@code LiteralArgumentBuilder.<S>literal(...)} and
 *       {@code RequiredArgumentBuilder.<S, String>argument(...)}: chained, javac cannot infer it and takes {@code Object}.</li>
 *   <li>Register at {@code init}: a modern host builds the contributions again at every registration event (each server
 *       start and {@code /reload} hands it a fresh dispatcher).</li>
 *   <li>Argument types are Brigadier's own and the vanilla ones. A custom {@code ArgumentType} is refused by a client
 *       without the mod; use {@link CgCommandArgs} suggestions on a {@code word} or {@code string}.</li>
 *   <li>The calls used are in every Brigadier from 1.0.14 to 1.3.x: not {@code CommandContext.getNodes},
 *       {@code getCompletionSuggestions(parse, cursor)} or {@code SuggestionsBuilder.getRemainingLowerCase}.</li>
 * </ul>
 */
public final class CgCommands {

    /** Where a command runs: the server's thread (dedicated or integrated) or the client's. */
    public enum Side { SERVER, CLIENT }

    /** Commands to put into a dispatcher. A class, not a lambda: its method is generic over the source. */
    public interface Contribution {
        /**
         * Registers this contribution's commands into {@code dispatcher}, reading each source through {@code view}.
         * Called again for every dispatcher a host builds, so it must not keep state.
         */
        <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view);
    }

    /** What a host answers beside registering. One class per era: {@code CommandsModern}, {@code CommandsLegacy}. */
    public interface Host {
        /** Sends a player their command tree again, after a permission or a registration changed. */
        void refresh(Object player);

        /** Whether this host can run commands on {@code side}; the client's is absent where the loader has no hook. */
        default boolean supports(Side side) {
            return true;
        }

        Host NONE = new Host() {
            @Override public void refresh(Object player) { }
            @Override public boolean supports(Side side) { return false; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:commands", Host.NONE);

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final List<Contribution> SERVER = new CopyOnWriteArrayList<>();
    private static final List<Contribution> CLIENT = new CopyOnWriteArrayList<>();
    private static volatile CommandDispatcher<CgCommandSource> standaloneServer, standaloneClient;

    private CgCommands() {
    }

    /** Adds commands the server runs. */
    public static void server(Contribution c) {
        SERVER.add(c);
        standaloneServer = null;
    }

    /** Adds commands the client runs, never sent to a server. */
    public static void client(Contribution c) {
        CLIENT.add(c);
        standaloneClient = null;
    }

    /**
     * A host's registration: puts every contribution of {@code side} into {@code dispatcher}, the one the loader's event
     * handed it, with {@code view} turning its source into a {@link CgCommandSource}. Throws, naming the contribution,
     * when one fails to build, so a bad command is not found by its absence.
     */
    public static <S> void install(Side side, CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        for (Contribution c : contributions(side)) {
            try {
                c.build(dispatcher, view);
            } catch (RuntimeException e) {
                throw new IllegalStateException("command contribution " + c.getClass().getName() + " failed to build", e);
            }
        }
    }

    /**
     * Runs {@code line}, with or without its leading slash, as {@code source} on {@code side}'s commands. Returns the
     * command's result, 0 when it failed; the failure's message goes to {@link CgCommandSource#fail}.
     */
    public static int run(Side side, CgCommandSource source, String line) {
        String text = line.startsWith("/") ? line.substring(1) : line;
        try {
            return dispatcher(side).execute(text, source);
        } catch (CommandSyntaxException e) {
            source.fail(e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            LOGGER.error("[commands] '{}' failed", text, e);
            source.fail("failed: " + e);
            return 0;
        }
    }

    /**
     * The completions of {@code line} up to {@code cursor}, as the dispatcher gives them: each with its range, and a
     * tooltip where the provider gave one, and without a literal the source may not run. Never null; a line no command
     * starts is an empty list.
     */
    public static CompletableFuture<Suggestions> suggest(Side side, CgCommandSource source, String line, int cursor) {
        int end = Math.max(0, Math.min(cursor, line.length()));
        String head = line.startsWith("/") ? line.substring(1, Math.max(1, end)) : line.substring(0, end);
        CommandDispatcher<CgCommandSource> d = dispatcher(side);
        ParseResults<CgCommandSource> parse = d.parse(head, source);
        return d.getCompletionSuggestions(parse).thenApply(all -> runnable(d, source, head, all));
    }

    /** Sends the player their command tree again. */
    public static void refresh(Object player) {
        host().refresh(player);
    }

    /** Whether the host runs commands on {@code side}. */
    public static boolean supports(Side side) {
        return host().supports(side);
    }

    /**
     * Drops what {@code source} may not run. Brigadier lists a literal whatever its requirement says (Minecraft hides it by
     * never sending the node), and the call that finds the node's parent is not in every Brigadier version, so each
     * suggestion is applied to the line and kept when the line still parses cleanly for this source.
     */
    private static Suggestions runnable(CommandDispatcher<CgCommandSource> d, CgCommandSource source, String head,
                                        Suggestions all) {
        List<Suggestion> kept = new ArrayList<>(all.getList().size());
        for (Suggestion s : all.getList()) {
            ParseResults<CgCommandSource> p = d.parse(s.apply(head), source);
            if (p.getExceptions().isEmpty() && !p.getReader().canRead()) kept.add(s);
        }
        return kept.size() == all.getList().size() ? all : Suggestions.create(head, kept);
    }

    private static List<Contribution> contributions(Side side) {
        return side == Side.SERVER ? SERVER : CLIENT;
    }

    /** The commands of a side over {@link CgCommandSource} itself: what {@link #run} and {@link #suggest} use. */
    private static CommandDispatcher<CgCommandSource> dispatcher(Side side) {
        CommandDispatcher<CgCommandSource> d = side == Side.SERVER ? standaloneServer : standaloneClient;
        if (d != null) return d;
        d = new CommandDispatcher<>();
        install(side, d, Function.identity());
        if (side == Side.SERVER) standaloneServer = d; else standaloneClient = d;
        return d;
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
