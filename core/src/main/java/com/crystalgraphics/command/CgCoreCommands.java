package com.crystalgraphics.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.crystalgraphics.platform.command.CgCommandArgs;
import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

/**
 * CrystalGraphics' own commands, {@code /cg} on the server and {@code /cgc} on the client: the engine's variables, a reload, and one paste for a bug
 * report. Registered once by the engine's init; a mod adds its own root beside it. What each does is
 * {@link CgCoreActions}; a host with no Brigadier gets {@link CgPlainCommands}.
 *
 * <pre>
 * /cg var list [prefix]        every variable under a prefix, with its value and source
 * /cg var get &lt;id&gt;            one variable
 * /cg var set &lt;id&gt; &lt;value&gt;    sets it as the console (operator level 2 on a server)
 * /cg var reset &lt;id&gt;          drops the console's value, so the next source down shows
 * /cg reload                   F3+T's asset reload
 * /cg info                     loader, shader pack, mods
 * </pre>
 *
 * <p>Reading is open to everyone; changing anything needs operator level 2. A read-only variable refuses a set.</p>
 */
public final class CgCoreCommands implements CgCommands.Contribution {

    private final String root;

    /**
     * @param root the command's name: {@code cg} on the server, {@code cgc} on the client, where a client command named
     *             as the server's would take the line before it reached the server
     */
    public CgCoreCommands(String root) {
        this.root = root;
    }

    @Override
    public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal(root)
                .then(LiteralArgumentBuilder.<S>literal("var")
                        .then(LiteralArgumentBuilder.<S>literal("list")
                                .executes(ctx -> CgCoreActions.list(view.apply(ctx.getSource()), ""))
                                .then(RequiredArgumentBuilder.<S, String>argument("prefix", word())
                                        .executes(ctx -> CgCoreActions.list(view.apply(ctx.getSource()), getString(ctx, "prefix")))))
                        .then(LiteralArgumentBuilder.<S>literal("get")
                                .then(RequiredArgumentBuilder.<S, String>argument("id", word()).suggests(CgCommandArgs.varNames())
                                        .executes(ctx -> CgCoreActions.get(view.apply(ctx.getSource()), getString(ctx, "id")))))
                        .then(LiteralArgumentBuilder.<S>literal("set")
                                .requires(s -> view.apply(s).level() >= CgCoreActions.CHANGE_LEVEL)
                                .then(RequiredArgumentBuilder.<S, String>argument("id", word()).suggests(CgCommandArgs.varNames())
                                        .then(RequiredArgumentBuilder.<S, String>argument("value", greedyString())
                                                .suggests(CgCoreCommands.<S>values())
                                                .executes(ctx -> CgCoreActions.set(view.apply(ctx.getSource()), getString(ctx, "id"),
                                                        getString(ctx, "value"))))))
                        .then(LiteralArgumentBuilder.<S>literal("reset")
                                .requires(s -> view.apply(s).level() >= CgCoreActions.CHANGE_LEVEL)
                                .then(RequiredArgumentBuilder.<S, String>argument("id", word()).suggests(CgCommandArgs.varNames())
                                        .executes(ctx -> CgCoreActions.reset(view.apply(ctx.getSource()), getString(ctx, "id"))))))
                .then(LiteralArgumentBuilder.<S>literal("reload")
                        .requires(s -> view.apply(s).level() >= CgCoreActions.CHANGE_LEVEL)
                        .executes(ctx -> CgCoreActions.reload(view.apply(ctx.getSource()))))
                .then(LiteralArgumentBuilder.<S>literal("info")
                        .executes(ctx -> CgCoreActions.info(view.apply(ctx.getSource())))));
    }

    /** The values a variable takes: both of a switch, every option of a choice, else what it holds now. */
    private static <S> SuggestionProvider<S> values() {
        return (ctx, builder) -> {
            CgVar<?> v = CgVars.get(getString(ctx, "id"));
            if (v != null) {
                String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                List<String> options = new ArrayList<>();
                if (v instanceof CgVar.Bool) {
                    options.add("true");
                    options.add("false");
                } else if (v instanceof CgVar.Choice) {
                    for (Enum<?> option : ((CgVar.Choice<?>) v).options()) options.add(option.name().toLowerCase(Locale.ROOT));
                } else {
                    options.add(v.text());
                }
                for (String option : options) {
                    if (CgCommandArgs.matches(typed, option)) builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }
}
