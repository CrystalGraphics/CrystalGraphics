package com.crystalgraphics.platform.command;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Who runs a command, as facts: what a command, a permission check and a reply need of a source, whatever the host
 * calls it ({@code CommandSourceStack}, {@code ICommandSender}, a console line). A host builds one over its own
 * source; a tool, a script or a test makes one with {@link #of}.
 *
 * <pre>{@code
 * // inside a command, over the host's source S
 * CgCommandSource src = view.apply(ctx.getSource());
 * if (src.kind() == CgCommandSource.Kind.PLAYER) greet(src.id());
 * src.reply("set " + name);
 *
 * // a console, a script or a test
 * List<String> out = new ArrayList<>();
 * CgCommandSource console = CgCommandSource.of(CgCommandSource.Kind.CONSOLE, "console", 4, out::add);
 * CgCommands.run(CgCommands.Side.SERVER, console, "cg var list");
 * }</pre>
 *
 * <ul>
 *   <li>{@link #level()} is the vanilla operator level, 0 to 4, whatever the version spells it as.</li>
 *   <li>{@link #host()} is the host's own source, for the one call a command cannot make without it; null for a source
 *       made by {@link #of}.</li>
 * </ul>
 */
public interface CgCommandSource {

    /** What is running the command. */
    enum Kind {
        PLAYER, CONSOLE, COMMAND_BLOCK, RCON, ENTITY,
        /** The client's own chat line: a client command. */
        CLIENT,
        /** A UI: a command palette or a console panel. */
        UI,
        /** A remote tool; the variables it sets are {@code CgVar.Source.REMOTE}. */
        REMOTE
    }

    Kind kind();

    /** A name for a message and a log: a player's name, {@code "console"}. */
    String name();

    /** A player's profile id; null for any other source. */
    UUID id();

    /** The vanilla operator level, 0 to 4. */
    int level();

    /** Tells the source something went right. */
    void reply(String text);

    /** Tells the source it failed, as a command's error. */
    void fail(String text);

    /** The host's own source ({@code CommandSourceStack}, {@code ICommandSender}); null where there is none. */
    <V> V host();

    /** A source over {@code reply}, which takes both its messages and its failures. */
    static CgCommandSource of(Kind kind, String name, int level, Consumer<String> reply) {
        return of(kind, name, level, reply, reply);
    }

    /** A source over one sink for messages and another for failures. */
    static CgCommandSource of(Kind kind, String name, int level, Consumer<String> reply, Consumer<String> fail) {
        return new CgCommandSource() {
            @Override public Kind kind() { return kind; }
            @Override public String name() { return name; }
            @Override public UUID id() { return null; }
            @Override public int level() { return level; }
            @Override public void reply(String text) { reply.accept(text); }
            @Override public void fail(String text) { fail.accept(text); }
            @Override public <V> V host() { return null; }
        };
    }
}
