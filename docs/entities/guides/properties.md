# Properties: your own data on an entity

**What you will have:** a value of your own (mana, a form, a gear, a charge) on every entity of the types you choose,
kept by the server, seen by every client near it, and readable by an animation graph with no code.

## In plain words

A **property** is a value you stick onto an entity: a mana bar, a rage meter, a form. The server holds the real
value, and everyone near the entity gets a copy, like a scoreboard everyone can read but only the referee can change.

## The code

```java
public final class Form {
    public static final CgNetField<Integer> LEVEL = CgNetField.ofInt("level");             // the server's, sent
    public static final CgNetField<Float> GLOW = CgNetField.ofFloat("glow").local();       // each side's own
    public static final CgNetEvent<Void> TRANSFORMED = CgNetEvent.of("transformed");

    public static final CgNetType TYPE = CgNetType.define("mymod:form").fields(LEVEL, GLOW).events(TRANSFORMED)
            .onEntities(type -> type.equals("minecraft:player"))
            .build();
}
```

Then, anywhere in your gameplay code:

```java
// the server: a player transforms
CgNetObject form = Form.TYPE.of(player);
form.set(Form.LEVEL, 2);
form.emit(Form.TRANSFORMED, null);                       // heard by every client that sees the player

// a client: read what the server sent
CgNetObject seen = Form.TYPE.find(otherPlayer);          // null until it arrives
if (seen != null && seen.get(Form.LEVEL) >= 2) { ... }

// a client: hear the moment
Form.TYPE.onEvent(Form.TRANSFORMED, (obj, none) -> flashAt(obj.entityId()));
```

The same type as a file, `assets/mymod/net_types/form.yaml`:

```yaml
_v: 1
fields:
  level: { kind: int }
  glow:  { kind: float, sync: local }
events:
  transformed: {}
entities: players
```

```java
CgNetType form = CgNetType.named("mymod:form");          // either side, once the game has started
```

Every key of the file: [`../reference/net-type-file.md`](../reference/net-type-file.md).

## How it behaves

- **One object per entity, made for you.** `onEntities` makes it as the entity enters a level, on each side, and ends
  it as the entity leaves. `of(entity)` answers it; nothing is created per call.
- **The server writes; clients read.** A client that calls `set` on a sent field throws. What a client wants changed it
  asks for with a command ([`../../NETWORKING.md`](../../NETWORKING.md) § *Commands*) or, for tags and cues, through
  the gameplay handle ([`the-gameplay-handle.md`](the-gameplay-handle.md)).
- **Whoever arrives later is sent it as it stands**: a player joining, or an entity coming into view.
- **An event is a moment, not state.** A client that was not watching never hears it. What a newcomer must know is a
  field.

### Finding an entity's object

| Call | Side | Answers |
|---|---|---|
| `TYPE.of(entity)` | server | its object (made if it is missing) |
| `TYPE.find(entity)` | either | its object, or null; on a client, null until the server's arrives |
| `TYPE.find(entityId)`, `TYPE.of(entityId)` | client | the same, by network id, allocating nothing |

`entity` is the loader's own entity object (`Zombie`, `ServerPlayer`, `EntityLiving`). The engine takes it as
`Object`, so the same line compiles on every version.

## Variations

### Values each side works out for itself: `local()`

A `local()` field is never sent and never saved. Each side writes its own. Use it for what follows from other state,
such as a glow that fades in on each client after a transform:

```java
Form.TYPE.onClient(obj -> new CgNetBehaviour() {
    @Override public void frame(CgNetObject form, float seconds) {
        float target = form.get(Form.LEVEL) >= 2 ? 1f : 0f;
        form.set(Form.GLOW, form.get(Form.GLOW) + (target - form.get(Form.GLOW)) * Math.min(1f, seconds * 4f));
    }
});
```

A type whose fields are all `local()` never touches the network: each side makes its own objects. The engine's
own `CgEntityProperties.MOTION` is one ([`what-it-is-doing.md`](what-it-is-doing.md)).

### Logic every tick: behaviours

```java
Form.TYPE.onServer(obj -> new Drain());       // the server: its tick(obj), every server tick
Form.TYPE.onClient(obj -> new Fade());        // each client: its frame(obj, seconds), every frame
```

`onServer` and `onClient` belong to the built type, so call them after `build()`, once, at start-up. A behaviour is
made per object. On the server it ticks before the tick's changes are sent, so what it sets leaves that same tick. On
a client it runs at the start of the frame, before actors and animation read the properties.

### Who is sent a field

| Java | File (`sync:`) | Sent to |
|---|---|---|
| (nothing) | `all` | every client that sees the entity |
| `.ownerOnly()` | `owner` | the player it sits on, alone: their cooldowns |
| `.local()` | `local` | nobody: each side's own |
| `.serverOnly()` | (Java only) | nobody: the server's alone, still saved |
| `.initialOnly()` | `initial_only: true` | once, as a client starts seeing it |
| `.predicted()` | `predicted: true` | everyone, and the owner may set it on their own client first |

### Only some entities

`onEntities` takes the entity type's id, the same on every version: `minecraft:zombie`, `mymod:wyvern`, and
`minecraft:player` for players.

```java
.onEntities(type -> type.startsWith("mymod:"))            // every entity of your mod
.onEntities(type -> true)                                 // every entity: keep it small
```

In a file, `entities:` is a list of ids, `players` or `all`.

### Read by an animation graph

A graph names a number or bool field as `<type>#<field>` and is fed it every frame, on both sides:

```yaml
# assets/mymod/anim_graphs/hero.yaml
entity:
  form: mymod:form#level
```

[`../../animation/guides/entity-variables.md`](../../animation/guides/entity-variables.md) has the rest.

### Saved with the entity

`.persisted()` on a field, or on the type for all of them, keeps it through chunk unloads, deaths and restarts:
[`saving.md`](saving.md).

## What goes wrong

- **Declared too late.** An entity that entered before `build()` never gets an object. Declare every type at
  start-up, in common code ([`../README.md`](../README.md) § *Where your code runs*).
- **`onServer` chained before `build()`** does not compile: it is the type's, not the builder's.
- **A client writing a sent field throws.** Ask the server instead.
- **`find` on a client answers null** for an entity whose object has not arrived yet: the first frames after it comes
  into view. Read it each time rather than holding the answer from the first frame.
- **`of(entityId)` on the server throws**: an id names no entity without its level. Use the entity.
- **A file and a Java declaration of one id must match.** Declare a type in one place; a reload that changes its
  fields, events or entities fails by name until a restart.

## Where next

- What the engine already knows about every entity: [`what-it-is-doing.md`](what-it-is-doing.md).
- Flags with counts that a player's client may ask for: [`tags.md`](tags.md).
- Saving: [`saving.md`](saving.md).
- Every rule a networked type has (ranges, owners, prediction): [`../../NETWORKING.md`](../../NETWORKING.md).
