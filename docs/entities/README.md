# Entities

Give any of Minecraft's entities data of your own, read what it is doing, and change its gameplay state, with one API
on every Minecraft version and loader.

By the end of this page you will have zombies with a rage meter of their own: kept by the server, seen by every
player near them, and saved with the zombie when its chunk unloads.

## Five minutes: a rage meter on every zombie

```java
public final class Fury {
    public static final CgNetField<Integer> RAGE = CgNetField.ofInt("rage").persisted();

    public static final CgNetType TYPE = CgNetType.define("mymod:fury").fields(RAGE)
            .onEntities(type -> type.equals("minecraft:zombie"))
            .build();

    /** Called once at start-up: loading the class declares the constants above. */
    public static void declare() { }
}
```

Call `Fury.declare()` once at start-up ([below](#where-your-code-runs)), and every zombie has a `mymod:fury` object
from then on. Now use it from your gameplay code:

```java
// the server, say when a player hits one
CgNetObject fury = Fury.TYPE.of(zombie);
fury.set(Fury.RAGE, fury.get(Fury.RAGE) + 1);

// any client: what its server last sent
CgNetObject seen = Fury.TYPE.find(zombie);              // null until it arrives
int rage = seen == null ? 0 : seen.get(Fury.RAGE);
```

The same type as a file, with no Java at all:

```yaml
# assets/mymod/net_types/fury.yaml           -> the type mymod:fury
_v: 1
fields:
  rage: { kind: int, persisted: true }
entities: [ minecraft:zombie ]
```

That is the whole idea: **an entity's properties are a networked type on entities**. The server writes them, every
client that sees the entity is sent them, a player who arrives later is sent them as they stand, and `persisted` keeps
them through a chunk unload and a restart.

## The picture

```
           server                                         each client that sees the zombie
  ┌─────────────────────────┐                        ┌──────────────────────────────┐
  │ Fury.TYPE.of(zombie)    │   its fields, sent     │ Fury.TYPE.find(zombie)       │
  │   rage = 3   (saved) ───┼───────────────────────▶│   rage = 3   (read only)     │
  │                         │                        │                              │
  │ MOTION.of(zombie)       │   never sent: each     │ MOTION.of(zombieId)          │
  │   speed, grounded, ...  │   side works it out    │   speed, grounded, ...       │
  └─────────────────────────┘                        └──────────────────────────────┘
```

Two kinds of property sit on every entity:

| | What it is | Who writes it |
|---|---|---|
| **Yours** | a type you declare: mana, a form, a charge, a rage | the server; the owner's client only where you allow it |
| **The engine's** (`CgEntityProperties.MOTION`) | what the entity is doing this frame: speed, ground, swimming, hands, a jump, a hit | each side, for itself, from the game |

## Where to go

| You want to | Read |
|---|---|
| Build something that works, start to finish | [`start/1-charge-your-ki.md`](start/1-charge-your-ki.md) |
| Put data of your own on an entity, in Java or a file | [`guides/properties.md`](guides/properties.md) |
| Know what an entity is doing: speed, a jump, a swing, a hit | [`guides/what-it-is-doing.md`](guides/what-it-is-doing.md) |
| Mark an entity as stunned, flying or charging | [`guides/tags.md`](guides/tags.md) |
| Change an entity's tags and play its cues from one handle, predicted on the player's own client | [`guides/the-gameplay-handle.md`](guides/the-gameplay-handle.md) |
| Keep data through a chunk unload, a death or a restart | [`guides/saving.md`](guides/saving.md) |
| Push, launch, teleport or hold an entity still | [`guides/moving-an-entity.md`](guides/moving-an-entity.md) |
| See every field of what an entity is doing | [`reference/motion.md`](reference/motion.md) |
| See every key of a `net_type` file | [`reference/net-type-file.md`](reference/net-type-file.md) |
| Understand who decides what, and why a client cannot just set a value | [`concepts/sides-and-authority.md`](concepts/sides-and-authority.md) |
| Look up a word | [`glossary.md`](glossary.md) |

Animating an entity (a body, a graph fed by its properties, reactions to its hits) is
[`../animation/`](../animation/README.md). Networking in general (messages, objects in the world, commands) is
[`../NETWORKING.md`](../NETWORKING.md).

## Where your code runs

Declarations go in a class your mod loads at start-up, on both sides. The engine finds your lifecycle listeners through
one file in your jar ([`../ENGINE_API.md`](../ENGINE_API.md) § *Hearing the engine's lifecycle*):

```
# src/main/resources/META-INF/crystalgraphics/listeners
common com.example.mymod.MyModLifecycle
client com.example.mymod.client.MyModClient
```

```java
public final class MyModLifecycle implements CgLifecycleListener {
    @Override public void onPreInit() {
        Fury.declare();                   // every zombie from now on has its object
    }
}
```

- **Declare before any entity enters.** A type declared later only reaches entities that enter after it.
- **A `net_type` file needs no listener**: every file under `assets/<mod>/net_types/` is compiled as the game starts.
- **Client-only code** (a key, a screen) goes in the `client` listener: a dedicated server never loads it.
