# Saving: keeping an entity's data

**What you will have:** properties that survive a chunk unloading, the server restarting, a player leaving and coming
back, and, if you choose, a player's death.

## In plain words

Without saving, a value disappears when the chunk unloads or the server stops. A **persisted** field is written down
with the entity, like a name written in a notebook the entity carries, so it is still there next time.

## The code

```java
public static final CgNetField<Integer> RAGE = CgNetField.ofInt("rage").persisted();    // kept
public static final CgNetField<Integer> TAUNTS = CgNetField.ofInt("taunts");             // this session's

public static final CgNetType<CgNetObject> FURY = CgNetType.define("mymod:fury").fields(RAGE, TAUNTS)
        .onEntities(type -> type.equals("minecraft:zombie"))
        .build();
```

```java
// the server
FURY.of(zombie).set(RAGE, 3).set(TAUNTS, 2);
// walk away until the chunk unloads, come back: rage is 3, taunts 0
```

In a file, `persisted: true` on the field, or on the whole type:

```yaml
# assets/mymod/net_types/fury.yaml
_v: 1
fields:
  rage:   { kind: int, persisted: true }
  taunts: { kind: int }
entities: [ minecraft:zombie ]
```

## Where it is kept

| On | Kept in | Written | Read back |
|---|---|---|---|
| any entity but a player | the entity's own saved data, under the key `crystalgraphics` | as the game saves the entity: a chunk unloading, an autosave, the server stopping | as the entity loads |
| a player | their own file, `crystalgraphics/players/<uuid>.jsonc` in the world | as they leave, with the world's save, as the server stops | as they join |

So an entity's data travels with it through chunk unloads, and is gone when the entity is.

## Variations

### The whole type

```java
CgNetType.define("mymod:form").fields(LEVEL, COLOUR).persisted()...       // every field it lists
```

### Through a player's death

A player's objects end when they die, as a new player entity replaces them. Keep one through death with
`copyOnDeath()`:

```java
CgNetType.define("mymod:ki").fields(CURRENT).onEntities(type -> type.equals("minecraft:player"))
        .copyOnDeath().build();
```

### A player who is not online

The server reads and writes an offline player's saved objects by their profile id:

```java
CgNetObject ki = Ki.TYPE.of(profileId);           // from their file
ki.set(Ki.CURRENT, 100f);                          // theirs as they join
```

### Your own saved form

By default each saved field is written under its own name. A type that should save otherwise (a compact form, the
keys an older version of your mod wrote) gives a `CgNetSaveForm` with `savedAs(form)`, which reads and writes a
`CgStateMap` in its place ([`../../SERIALIZATION.md`](../../SERIALIZATION.md)).

## What goes wrong

- **A `local()` field cannot be saved.** In a file, `persisted: true` with `sync: local` fails at start-up, naming the
  field.
- **A type declared after an entity loaded** never read that entity's saved data. Declare at start-up.
- **A field that no longer reads** (you changed its kind) is logged and starts from its fallback. Rename the field
  rather than reuse a name for another kind.
- **Saving costs nothing per change.** However often a saved field changes, it is written once, at the entity's save.
  Forge 1.13.2 and 1.14.3 are the exception: they write at the tick a change happens.
- **Tags are not saved.** Keep the source of a tag in a saved field and add the tag back when the entity loads.

## Where next

- Every rule of saving, for objects that are not on entities: [`../../NETWORKING.md`](../../NETWORKING.md) §13.
- Properties in general: [`properties.md`](properties.md).
