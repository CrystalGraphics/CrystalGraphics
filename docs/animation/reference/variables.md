# Reference: variables

A graph's variables, and everything that can feed one. How to use them:
[`../guides/graphs-and-variables.md`](../guides/graphs-and-variables.md) and
[`../guides/entity-variables.md`](../guides/entity-variables.md).

## A graph's own variables

| File `type` | Java | Holds | Default unless given |
|---|---|---|---|
| `float` | `CgAnimVariable.floating(name[, default])` | a number | 0 |
| `bool` | `CgAnimVariable.bool(name[, default])` | true or false | false |
| `int` | `CgAnimVariable.integer(name[, default])` | a whole number | 0 |

```yaml
variables:
  speed: { type: float }
  grounded: { type: bool, default: true }
```

## What can feed one, with no code

| Block | Name form | From | Every name |
|---|---|---|---|
| `entity:` | `entity.<field>` | what the entity is doing, worked out by the engine | [`../../entities/reference/motion.md`](../../entities/reference/motion.md) |
| `entity:` | `<type>#<field>` | a number or bool field of your mod's property type | your type ([`../../entities/guides/properties.md`](../../entities/guides/properties.md)) |
| `tags:` | `{ tag: <id>[, as: bool] }` | a tag the entity owns: owned for a bool, its count for an int or float | your tags ([`../../entities/guides/tags.md`](../../entities/guides/tags.md)) |

| Variable type | Takes |
|---|---|
| `float` | any number or bool (a bool as 0 or 1) |
| `int` | an int field, or a tag's count |
| `bool` | a bool field, or whether a tag is owned |

## Rules over variables

`{ variable, op, value }` with `op` one of `<`, `<=`, `>`, `>=`, `==`, `!=`; combined with `all`, `any`, `not`.
Every rule kind: [`../guides/state-machines.md`](../guides/state-machines.md) § *Rules*.
