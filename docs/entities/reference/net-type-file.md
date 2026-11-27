# Reference: the `net_type` file

A property type declared as data. `assets/<namespace>/net_types/<path>.yaml` declares the type `<namespace>:<path>`,
compiled as the game starts, on both sides, before any entity enters or anyone connects. The guide is
[`../guides/properties.md`](../guides/properties.md).

```yaml
# assets/mymod/net_types/ki.yaml                     -> the type mymod:ki
_v: 1
persisted: false                                       # true: every field saved
fields:
  current:  { kind: float, fallback: 0.0, sync: all, persisted: true }
  regen:    { kind: float, sync: owner }
  glow:     { kind: float, sync: local }
  colour:   { kind: int, initial_only: true }
  aim:      { kind: float, predicted: true }
events:
  overcharged: { payload: float }
  calmed: {}
entities: players
```

## Top level

| Key | Required | Is |
|---|---|---|
| `_v` | yes | the format's version: `1` |
| `fields` | no | the type's fields, by name |
| `events` | no | the type's events, by name |
| `entities` | yes | who has one: a list of entity type ids (`[ minecraft:zombie, mymod:wyvern ]`), `players`, or `all` |
| `persisted` | no | `true` saves every field ([`../guides/saving.md`](../guides/saving.md)); default `false` |

## A field

| Key | Default | Is |
|---|---|---|
| `kind` | — | `bool`, `int`, `long`, `float`, `double` or `string` |
| `fallback` | `false`, `0`, `""` | its value until set; a number for the number kinds |
| `sync` | `all` | who is sent it: `all` (every client that sees the entity), `owner` (the player it sits on), `local` (nobody: each side's own) |
| `initial_only` | `false` | sent once, as a client starts seeing it |
| `predicted` | `false` | its owner may set it on their own client first |
| `persisted` | `false` | saved with the entity, or in a player's file |

## An event

| Key | Default | Is |
|---|---|---|
| `payload` | none | what it carries: one of the field kinds |

## In Java

The file's type is found by its id, on either side:

```java
CgNetType<?> ki = CgNetType.named("mymod:ki");
@SuppressWarnings("unchecked")
CgNetField<Float> current = (CgNetField<Float>) ki.field("current");
```

A Java declaration of the same shape is the same type:

```java
CgNetType.define("mymod:ki").fields(
        CgNetField.ofFloat("current").fallback(0f).persisted(),
        CgNetField.ofFloat("regen").ownerOnly(),
        CgNetField.ofFloat("glow").local())
    .events(CgNetEvent.of("overcharged", CgCodecs.FLOAT), CgNetEvent.of("calmed"))
    .onEntities(type -> type.equals("minecraft:player"))
    .build();
```

## Fails at start-up, naming the file and the key

- `entities` missing, or not a list, `players` or `all`;
- a `fallback` that is not a number for a number kind;
- `persisted: true` on a `sync: local` field;
- a file and a Java declaration of one id with different shapes;
- a reload that changes a type's fields, events or entities (restart instead).
