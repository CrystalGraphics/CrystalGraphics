package com.crystalgraphics.command;

import java.util.function.Function;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.world.actor.CgAnimDebugOverlay;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/**
 * {@code /cgc anim debug} shows the animation of the entity the player looks at over the HUD, and hides it again
 * ({@link CgAnimDebugOverlay}): Unreal's {@code showdebug animation}. Client-only: nothing is sent.
 */
public final class CgAnimDebugCommands implements CgCommands.Contribution {

    @Override
    public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("cgc")
                .then(LiteralArgumentBuilder.<S>literal("anim")
                        .then(LiteralArgumentBuilder.<S>literal("debug").executes(ctx -> {
                            boolean shown = CgAnimDebugOverlay.toggle();
                            view.apply(ctx.getSource()).reply(shown ? "Animation debug on: look at an entity" : "Animation debug off");
                            return 1;
                        }))));
    }
}
