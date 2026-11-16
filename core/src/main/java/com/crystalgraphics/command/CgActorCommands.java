package com.crystalgraphics.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.world.actor.CgActorFiles;
import com.crystalgraphics.world.actor.CgEntityActor;
import com.crystalgraphics.world.actor.CgEntityActors;
import com.crystalgraphics.world.actor.kit.CgKitActor;
import com.crystalgraphics.world.actor.kit.CgKitActorFile;
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
 *   <li>Every actor file that binds nothing ({@link CgActorFiles}) answers to its id's last part: {@code mymod:hero}
 *       to {@code /cgc actor hero}; it wins over a kit character of that name.</li>
 *   <li>A dev install adds characters from kit files with no code: {@link CgKitActorFile}.</li>
 * </ul>
 */
public final class CgActorCommands implements CgCommands.Contribution {

    private static final Map<String, Supplier<? extends CgEntityActor>> ACTORS = new ConcurrentHashMap<>();
    private static CgEntityActors.Registration bound;

    /**
     * Every name the command takes, each binding the local player: those registered, the actor files that bind nothing,
     * and the characters of {@link CgKitActorFile}'s file.
     */
    private static Map<String, Supplier<CgEntityActors.Registration>> actors() {
        Map<String, Supplier<CgEntityActors.Registration>> all = new TreeMap<>();
        ACTORS.forEach((name, factory) -> all.put(name, () -> CgEntityActors.bindLocalPlayer(factory)));
        for (CgActorFiles.Definition d : CgActorFiles.declared().values()) {
            if (d.localPlayer() || !d.entities().isEmpty()) continue;
            all.putIfAbsent(d.id().substring(d.id().indexOf(':') + 1).toLowerCase(Locale.ROOT), () -> CgActorFiles.bindLocalPlayer(d.id()));
        }
        for (Map.Entry<String, CgKitActor.Spec> e : CgKitActorFile.read().entrySet()) {
            Supplier<CgKitActor> factory = e.getValue().factory();
            all.putIfAbsent(e.getKey(), () -> CgEntityActors.bindLocalPlayer(factory));
        }
        return all;
    }

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
                                    for (String n : actors().keySet()) {
                                        if (n.startsWith(b.getRemaining().toLowerCase())) b.suggest(n);
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> on(view.apply(ctx.getSource()), getString(ctx, "name"))))));
    }

    private static synchronized int on(CgCommandSource source, String name) {
        Map<String, Supplier<CgEntityActors.Registration>> all = actors();
        Supplier<CgEntityActors.Registration> binding = all.get(name);
        if (binding == null) {
            source.fail("No actor '" + name + "'; known: " + String.join(", ", all.keySet()));
            return 0;
        }
        if (bound != null) bound.close();
        bound = null;
        try {
            bound = binding.get();
        } catch (IllegalStateException e) {
            // another owner binds the player (the animation demo's root motion station): its actor stays
            source.fail("Your player already wears an actor bound elsewhere; leave what bound it first");
            return 0;
        }
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
