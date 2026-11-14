package com.crystalgraphics.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.world.actor.CgEntityActor;
import com.crystalgraphics.world.actor.CgEntityActors;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/**
 * {@code /cgc actor <name>} turns the client's own player into a named actor, {@code /cgc actor off} gives the host's
 * model back. Client-only: the model is drawn here and nothing is sent.
 *
 * <pre>{@code
 * CgActorCommands.register("goku", GokuActor::new);     // once, at load
 * // in game: /cgc actor goku      /cgc actor off
 * }</pre>
 *
 * <ul>
 *   <li>The binding follows the player across respawns and level changes ({@link CgEntityActors#bindLocalPlayer});
 *       naming another actor replaces it.</li>
 *   <li>Other players are never affected.</li>
 * </ul>
 */
public final class CgActorCommands implements CgCommands.Contribution {

    private static final Map<String, Supplier<? extends CgEntityActor>> ACTORS = new ConcurrentHashMap<>();
    private static CgEntityActors.Registration bound;

    /** {@code factory}'s actor answers to {@code /cgc actor <name>}. */
    public static void register(String name, Supplier<? extends CgEntityActor> factory) {
        ACTORS.put(name, factory);
    }

    @Override
    public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("cgc")
                .then(LiteralArgumentBuilder.<S>literal("actor")
                        .then(LiteralArgumentBuilder.<S>literal("off").executes(ctx -> off(view.apply(ctx.getSource()))))
                        .then(LiteralArgumentBuilder.<S>literal("none").executes(ctx -> off(view.apply(ctx.getSource()))))
                        .then(RequiredArgumentBuilder.<S, String>argument("name", word())
                                .suggests((ctx, b) -> {
                                    for (String n : new TreeMap<>(ACTORS).keySet()) {
                                        if (n.startsWith(b.getRemaining().toLowerCase())) b.suggest(n);
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> on(view.apply(ctx.getSource()), getString(ctx, "name"))))));
    }

    private static synchronized int on(CgCommandSource source, String name) {
        Supplier<? extends CgEntityActor> factory = ACTORS.get(name);
        if (factory == null) {
            source.fail("No actor '" + name + "'; known: " + String.join(", ", new TreeMap<>(ACTORS).keySet()));
            return 0;
        }
        if (bound != null) bound.close();
        bound = CgEntityActors.bindLocalPlayer(factory);
        source.reply("You are " + name);
        return 1;
    }

    private static synchronized int off(CgCommandSource source) {
        if (bound == null) {
            source.fail("No actor bound");
            return 0;
        }
        bound.close();
        bound = null;
        source.reply("Your own model again");
        return 1;
    }
}
