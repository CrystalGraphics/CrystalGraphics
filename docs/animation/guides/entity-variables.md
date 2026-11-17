# Entity variables: a graph fed by its entity

**What you will have:** a graph whose variables (speed, on the ground, sneaking, a held bow, your mod's ki, a
`stunned` tag) are set from its entity every frame, on both sides, with no `onUpdate` and no code.

## In plain words

A graph decides what to play from **variables**, facts like speed. Normally your code fills them in every frame. On
an entity, the engine already knows the facts (how fast it moves, whether it is on the ground, what it holds), so you
only say which fact goes into which variable, like labelling the wires from a sensor to a dashboard.

## The code

```yaml
# assets/mymod/anim_graphs/hero.yaml
_v: 1
variables:
  speed:    { type: float }
  grounded: { type: bool, default: true }
  draw:     { type: float }
  ki:       { type: float }
  stunned:  { type: bool }
entity:
  speed: entity.speed                 # the graph's variable: the property feeding it
  grounded: entity.grounded
  draw: entity.use_time               # seconds into drawing the bow
  ki: mymod:ki#current                # your mod's own property
tags:
  stunned: { tag: mymod:state.stunned }
```

Any actor whose body runs this graph ([`actors.md`](actors.md)) sets these five variables from its entity every frame,
before the graph runs. A state machine's rules then read `speed`, `grounded` and `stunned` like any variable.

In Java:

```java
CgAnimGraph.builder("mymod:hero", mesh.skeleton())
        .variables(SPEED, GROUNDED, DRAW, KI, STUNNED)
        .entity(SPEED, CgEntityProperties.SPEED)
        .entity(GROUNDED, CgEntityProperties.GROUNDED)
        .entity(DRAW, CgEntityProperties.USE_TIME)
        .entity(KI, Ki.TYPE, Ki.CURRENT)
        .tag(STUNNED, CgTagRegistry.global().require("mymod:state.stunned"))
        ...
```

## What can feed a variable

| Name | Is | Where it comes from |
|---|---|---|
| `entity.<field>` | what the entity is doing: `entity.speed`, `entity.sneaking`, `entity.swing` | the engine, each frame, on each side ([every field](../../entities/reference/motion.md)) |
| `<type>#<field>` | a number or bool field of your mod's property type: `mymod:ki#current` | your server ([properties](../../entities/guides/properties.md)) |
| a `tags:` entry | a tag the entity owns: a bool for owned, an int or float for its count | your server or the player's prediction ([tags](../../entities/guides/tags.md)) |

The most used `entity.` names:

| Group | Names |
|---|---|
| Motion | `speed`, `vertical_speed`, `move_direction` (degrees, 90 left), `acceleration`, `turn_rate` |
| Intent | `input` (0 to 1), `input_direction`, `sprint_held`, `jump_held` |
| Ground and air | `grounded`, `airborne`, `air_time`, `ground_distance`, `land_height` |
| Stance | `sneaking`, `sprinting`, `swimming`, `crawling`, `swim_amount`, `gliding`, `riding`, `sleeping` |
| Life and look | `dead`, `hurt_time`, `head_yaw`, `head_pitch` |
| Hands | `main_arm`, `off_arm`, `using`, `use_time`, `use_length`, `swinging`, `swing` (0 to 1) |
| Events, true for their frame | `jumped`, `landed`, `hurt`, `swing_started`, `use_started`, `use_stopped`, `mounted`, `dismounted`, `died` |

## Variations

### Gait on intent, not speed

A run that slows on a slope should not drop to a walk. Key the gait on what the player means:

```yaml
entity:
  input: entity.input                 # 0 to 1: the keys or stick
  sprint: entity.sprint_held
```

### Tags as weights

A tag read into a float blends:

```yaml
variables:
  stunned: { type: float }
tags:
  stunned: { tag: mymod:state.stunned, as: bool }     # 0 or 1, a blend's alpha
```

Without `as`, a float or int reads the tag's count (`mymod:buff.rage` with 3 stacks reads 3).

### Without an actor

Any anim instance can be fed from an entity in code: set the variables in `onUpdate` from the properties yourself.

```java
anim.onUpdate((a, dt) -> {
    CgNetObject m = CgEntityProperties.MOTION.of(entityId);
    a.set(SPEED, m.get(CgEntityProperties.SPEED));
});
```

## What goes wrong

- **Each binding fails at load, naming it**: a name no type or field has, a field that is no number or bool, a type
  not on entities, a variable the graph does not declare, a tag no one registered, or one variable bound both to a
  property and to a tag.
- **A bool or an int takes only its own kind**; a float takes any (a bool as 0 or 1).
- **The graph's own name is what rules read.** A rule reads `speed`, never `entity.speed`, so the same graph runs fed
  by an entity or by `onUpdate`.
- **A mod's type is declared before the graph loads.** Until the entity has an object of it, a field reads its
  `fallback`.
- **An event variable is true for one frame.** A rule on `landed` must fire that frame: use it for a transition, not
  a state that should last.

## Where next

- Play a montage on an event without a graph rule: [`reactions-and-locks.md`](reactions-and-locks.md).
- A whole humanoid graph already bound to these: [`vanilla-models.md`](vanilla-models.md).
- State machines and rules: [`state-machines.md`](state-machines.md).
