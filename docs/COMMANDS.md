# Commands — the guide

CrystalGraphics lets your mod add chat commands once, and they run on every Minecraft version and loader it supports.
On 1.13.2 and later a command is Brigadier's own, put into the dispatcher Minecraft already runs, so the client's
command tree, its completions with tooltips and its colouring are Minecraft's, not ours. You never subscribe to a
loader's registration event, never spell a source's methods per version, and never write a tab completer.

It comes in three parts:

- **Contributions**: commands you write once against Brigadier, over a source that is plain facts.
- **Running and completing a line yourself**: the same commands for a console, a script or a keybind.
- **`/cg` and `/cgc`**: CrystalGraphics' own commands for the engine's variables, a reload, and a bug report's paste.

Read sections 1 to 3 first: they give you the words and the one picture everything else follows. After that, jump to
what you need.

## If you have written a command mod before

You know the routine. Here is "a command with a completed argument that only operators run", the usual way, and then here.

**The usual way.** One registration hook per loader and era, a different source type, and a different way to say "is
an operator":

```java
// Forge 1.16.5-1.21.5: @SubscribeEvent RegisterCommandsEvent; 1.21.6+: RegisterCommandsEvent.BUS.addListener(...)
// NeoForge 1.20.2+:    NeoForge.EVENT_BUS, RegisterCommandsEvent
// Fabric 1.14.4-1.19.1: CommandRegistrationCallback v1 (dispatcher, dedicated); 1.19.2+: v2 (dispatcher, context, selection)
// Forge 1.13.2-1.15.2: no event; take the dispatcher from the server at about-to-start
dispatcher.register(Commands.literal("mymod")
    .requires(src -> src.hasPermission(2))                 // 1.21.11: src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
    .then(Commands.argument("kit", StringArgumentType.word())
        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(kits, b))
        .executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("ok"), false);   // 1.19.4 and before: no supplier;
            return 1;                                                            // 1.19 and before: new TextComponent
        })));
// Fabric's client commands: a different source type again, and none at all on 1.14.4-1.15.2
// 1.8.9-1.12.2, 1.7.10: no Brigadier; an ICommand, getCommandName or getName, processCommand or execute, ...
```

**Here.**

```java
final class KitCommands implements CgCommands.Contribution {
    @Override public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("mymod")
            .requires(s -> view.apply(s).level() >= 2)
            .then(RequiredArgumentBuilder.<S, String>argument("kit", word())
                .suggests(CgCommandArgs.choice(() -> kits.ids(), id -> kits.title(id)))
                .executes(ctx -> {
                    view.apply(ctx.getSource()).reply("ok");
                    return 1;
                })));
    }
}
CgCommands.server(new KitCommands());      // once, at init
```

One class for every version that has Brigadier; the host registers it, and the source answers for itself.

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **One registration per loader and era** | three loaders, five spellings, a missing event on 1.13.2-1.15.2 | `CgCommands.server(...)`; each host forwards its own event |
| **The source type** | `CommandSourceStack`, Fabric's client source, an `ICommandSender` | `CgCommandSource`: kind, name, id, level, `reply`, `fail` |
| **Operator level** | `hasPermission(int)`, then a `PermissionSet` from 1.21.11 | `source.level()`, 0 to 4, on every version |
| **Sending a message** | `sendSuccess` with and without a supplier, `Component.literal` or `TextComponent` | `source.reply(text)` and `source.fail(text)` |
| **Completion** | a provider per argument, `SharedSuggestionProvider`, matching by hand | `CgCommandArgs.choice`, `varNames`: matches by word start, with tooltips |
| **Testing a command** | boot a server | `CgCommands.run` over a `CgCommandSource.of(...)` |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [The picture: a contribution, a host, a source](#3-the-picture-a-contribution-a-host-a-source)
4. [Writing a contribution](#4-writing-a-contribution)
5. [Arguments and completion](#5-arguments-and-completion)
6. [Running and completing a line yourself](#6-running-and-completing-a-line-yourself)
7. [CrystalGraphics' own commands](#7-crystalgraphics-own-commands)
8. [Versions without Brigadier](#8-versions-without-brigadier)
9. [Where each version registers](#9-where-each-version-registers)
10. [Testing](#10-testing)
11. [What is easy to get wrong](#11-what-is-easy-to-get-wrong)

---

## 1. The words you need

| Word | Means |
|---|---|
| **Contribution** | A class of commands: `CgCommands.Contribution`, with one method that registers them into a dispatcher |
| **Side** | `SERVER` (dedicated or integrated, on the server's thread) or `CLIENT` (typed in chat, never sent to a server) |
| **Source** | Who ran a command, as `CgCommandSource`: facts, not a Minecraft type |
| **Kind** | What a source is: `PLAYER`, `CONSOLE`, `COMMAND_BLOCK`, `RCON`, `ENTITY`, `CLIENT`, `UI`, `REMOTE` |
| **Level** | The vanilla operator level, 0 to 4, whatever the version calls it |
| **View** | The function from the dispatcher's own source type to a `CgCommandSource`, handed to `build` |
| **Host** | What a version supplies: registers the contributions into Minecraft's dispatcher |

## 2. Quick start

A command that tells the caller a variable's value, completing its name as they type:

```java
final class VarCommands implements CgCommands.Contribution {
    @Override public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("mymod")
            .then(RequiredArgumentBuilder.<S, String>argument("name", word()).suggests(CgCommandArgs.varNames())
                .executes(ctx -> {
                    view.apply(ctx.getSource()).reply(CgVars.get(getString(ctx, "name")).text());
                    return 1;
                })));
    }
}

// in your mod's init, before the loader's registration event:
CgCommands.server(new VarCommands());     // a command the server runs
CgCommands.client(new VarCommands());     // or one the client runs
```

Type `/mymod cry` and Minecraft's chat offers `crystalgraphics.vfx.bookBatch` with its description, type, range, value
and source as the tooltip.

## 3. The picture: a contribution, a host, a source

```
your init:  CgCommands.server(contribution)          ← remembered
loader:     RegisterCommandsEvent / CommandRegistrationCallback / ...  ← each host forwards it
host:       CgCommands.install(SERVER, minecraftDispatcher, view)      ← contribution.build(...) runs
chat:       the client sees Minecraft's own tree, completions and colours
a console:  CgCommands.run(side, source, "mymod ...")                  ← the same contributions, a standalone dispatcher
```

A contribution is generic over the dispatcher's source type `S`: on 26.2 it builds into Minecraft's
`CommandDispatcher<CommandSourceStack>`, in a console into a `CommandDispatcher<CgCommandSource>`. It reads the source
only through the view, so it is the same code in both.

**A modern host builds every contribution again for each dispatcher it is handed** (each server start, each `/reload`).
So a contribution keeps no state and registers nothing outside `build`.

## 4. Writing a contribution

A contribution is a class, not a lambda: its method is generic, and Java has no generic lambdas.

```java
final class ModCommands implements CgCommands.Contribution {
    @Override public <S> void build(CommandDispatcher<S> dispatcher, Function<S, CgCommandSource> view) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("mymod")
            .requires(s -> view.apply(s).level() >= 2)                       // hidden from anyone below level 2
            .then(LiteralArgumentBuilder.<S>literal("reload").executes(ctx -> {
                CgCommandSource who = view.apply(ctx.getSource());
                if (!reload()) { who.fail("could not reload"); return 0; }
                who.reply("reloaded");
                return 1;
            })));
    }
}
```

- **Name the source type on each builder**: `LiteralArgumentBuilder.<S>literal(...)` and
  `RequiredArgumentBuilder.<S, String>argument(...)`. Chained without it, javac infers `Object`.
- **`requires` and the client**: Minecraft hides a literal by never sending the node to a player who fails `requires`,
  so the client's tree shows only what they may run.
- **A failure** is `source.fail(text)` and a return of 0; an uncaught exception is logged and reported, never a crash.
- **One root, many contributions**: two contributions may register the same root literal; Brigadier merges them.

### A source

```java
CgCommandSource who = view.apply(ctx.getSource());
who.kind();      // PLAYER, CONSOLE, CLIENT, ...
who.name();      // "Steve", "Server"
who.id();        // the profile id, null when it is not a player
who.level();     // 0 to 4
who.reply("..."); who.fail("...");
CommandSourceStack stack = who.host();   // the host's own source, for the one call you cannot make without it; null from CgCommandSource.of
```

## 5. Arguments and completion

Use Brigadier's own and Minecraft's vanilla argument types (`word`, `string`, `greedyString`, `integer`, `bool`) and
add a suggestion provider. **Do not register a custom `ArgumentType`**: a client without your mod refuses the whole
command tree, and the registry that holds it only exists from 1.19.2.

```java
argument("kit", word()).suggests(CgCommandArgs.choice(() -> kits.ids()));                 // a list, read at each keystroke
argument("kit", word()).suggests(CgCommandArgs.choice(() -> kits.ids(), id -> kits.title(id)));   // each with a tooltip
argument("name", word()).suggests(CgCommandArgs.varNames());                              // every CgVar, with its value and source
```

- **A match is a word start**: the candidate starts with what was typed, or a word of it does. A word begins after
  `. _ / :` and at a capital after a lower-case letter or digit, so `batch` finds `bookBatch` and `skip` finds
  `crystalgraphics.vfx.skip`.
- **A provider is asked on the server as the player types**, so it reads a list it already holds and never blocks.
- **A suggestion does not validate**: the command does, with `CgCommandArgs.one(value, values, "kit")`, which fails with
  *Unknown kit 'x'*.

## 6. Running and completing a line yourself

The same contributions, over a dispatcher of `CgCommandSource`, for a console, a script, a keybind or a remote tool:

```java
CgCommandSource me = CgCommandSource.of(CgCommandSource.Kind.CLIENT, "me", 4, System.out::println, System.err::println);
CgCommands.run(CgCommands.Side.CLIENT, me, "mymod reload");               // a leading / is fine; returns the result, 0 on failure
CgCommands.suggest(CgCommands.Side.CLIENT, me, "mymod re", 8)             // the completions chat would give, with tooltips
    .thenAccept(suggestions -> show(suggestions.getList()));
```

`suggest` leaves out a literal the source may not run: Brigadier itself lists every literal whatever its `requires` says.
A failure goes to `source.fail`; `run` never throws for a bad line.

## 7. CrystalGraphics' own commands

`/cg` on the server and `/cgc` on the client. The client's has its own name, or it would take the line before a remote
server saw it. Reading is open to everyone; changing anything needs operator level 2.

| Command | Does |
|---|---|
| `var list [prefix]` | every variable under a prefix, with its value and the source that set it |
| `var get <id>` | one variable, with its description, type and range |
| `var set <id> <value>` | sets it as the console; refused for a read-only variable or a value out of range |
| `var reset <id>` | drops the console's value, so the next source down shows |
| `reload` | the asset reload that F3+T runs |
| `info` | the loader and its version, the shader pack, the number of mods |

`var set` completes both of a switch and the options of a choice. What each does is `CgCoreActions`, shared by the
Brigadier tree (`CgCoreCommands`) and the plain words (`CgPlainCommands`), so the two cannot drift.

## 8. Versions without Brigadier

1.7.10 and Forge 1.8.9-1.12.2 have no Brigadier, and CrystalGraphics ships none for them. There `/cg` and `/cgc` are one
`ICommand` each over `CgPlainCommands`, which splits nothing itself: the host hands it the arguments.

- **Same actions, same operator levels**; completion is by prefix, without tooltips.
- **Your contributions are not run there.** `CgBrigadier.present()` says whether Brigadier is on the classpath; ask it
  before touching `CgCommands`, a class whose signatures name Brigadier. A mod that wants a command on those versions
  registers its own `ICommand`.

```java
if (CgBrigadier.present()) CgCommands.server(new ModCommands());
```

## 9. Where each version registers

| Version | Server | Client |
|---|---|---|
| Forge 1.16.5-26.3 | `RegisterCommandsEvent` (its own `BUS` from 1.21.6) | `RegisterClientCommandsEvent`, 1.18.2+ |
| Forge 1.13.2-1.15.2 | the server's dispatcher at about-to-start | none |
| NeoForge 1.20.2-26.3 | `RegisterCommandsEvent` | `RegisterClientCommandsEvent` |
| Fabric 1.14.4-26.3 | `CommandRegistrationCallback`, v2 from 1.19.2 | `ClientCommandManager` 1.16.5-1.18.2, `ClientCommandRegistrationCallback` from 1.19.2, none before |
| Forge 1.8.9-1.12.2, 1.7.10 | `registerServerCommand`, one `ICommand` | `ClientCommandHandler` |

`CgCommands.supports(side)` says whether the running host has a hook for a side.

## 10. Testing

A contribution runs in a plain unit test, with no game:

```java
@Test public void reloadNeedsLevelTwo() {
    CgCommands.server(new ModCommands());
    List<String> failed = new ArrayList<>();
    CgCommandSource guest = CgCommandSource.of(CgCommandSource.Kind.PLAYER, "guest", 0, s -> { }, failed::add);
    assertEquals(0, CgCommands.run(CgCommands.Side.SERVER, guest, "mymod reload"));
    assertFalse(failed.isEmpty());
}
```

Brigadier comes with `platform` and `core` in their published metadata (not bundled in the Minecraft jar, as for JOML), so
a test, the harness or a console resolves it on its own; Minecraft's own is used in game. Compile against 1.0.14.

## 11. What is easy to get wrong

- **Register at init**, before the loader's registration event; a contribution added later misses the dispatcher.
- **Name the source type** on every builder, or javac infers `Object`.
- **Use only the Brigadier calls every version has.** Not `CommandContext.getNodes`, `getCompletionSuggestions(parse,
  cursor)` or `SuggestionsBuilder.getRemainingLowerCase`: they are missing from the Brigadier some versions ship.
- **A client command is never sent to a server**, and a client command with a server command's name takes the line:
  give it a name of its own, as `/cgc` does.
- **`level()` is the vanilla operator level.** There is no finer permission yet; the permissions service is planned.
- **`host()` is the host's own object**; casting it ties that call site to one version.
