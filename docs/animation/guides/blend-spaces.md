# Blend spaces: one locomotion from many clips

**What you will have:** an idle, a jog and a sprint turned into one smooth locomotion by speed, and a strafing set
(forward, back, left, right) chosen by speed and direction, with the feet in step at every blend.

## In plain words

You have a walk clip and a run clip, but the character can move at any speed in between. A **blend space** lays the
clips out on a line (or a map): walk at 2, run at 6. At speed 4 it mixes them half and half, like mixing blue and
yellow paint to get green. With two inputs, speed and which way, the clips sit on a map, and where the character is on
it says how much of each to mix.

## The code

```java
static final CgBlendSpace RUN = CgBlendSpace.oneD("mymod:hero_run", "speed", 0, 8)
        .sample(IDLE, 0).sample(JOG, 4).sample(SPRINT, 8)
        .weightSpeed(5)                                       // weights move toward their targets 5 a second
        .build();

CgBlendSpacePlayerNode.of(RUN, SPEED);                        // in a graph, by a variable
```

The same as a file, `assets/mymod/blend_spaces/hero_run.yaml`, which a graph's node names by id:

```yaml
_v: 1
axes: [ { name: speed, min: 0, max: 8 } ]
samples:
  - { clip: mymod:hero/idle, at: [0] }
  - { clip: mymod:hero/jog, at: [4] }
  - { clip: mymod:hero/sprint, at: [8], rate_scale: 1.1 }
weight_speed: 5
```

```yaml
# in the graph
nodes:
  run: { kind: blend_space_player, blend_space: mymod:hero_run, x: speed }
```

On an entity, `speed` comes from `entity.speed` with no code ([`entity-variables.md`](entity-variables.md)).

## Variations

### Two axes: strafing

```java
static final CgBlendSpace STRAFE = CgBlendSpace.twoD("mymod:hero_strafe", "direction", -180, 180, "speed", 0, 6)
        .sample(IDLE, 0, 0).sample(FORWARD, 0, 6).sample(LEFT, -90, 6).sample(RIGHT, 90, 6)
        .sample(BACK, -180, 6).sample(BACK, 180, 6)
        .wrap(0)                                              // -180 and 180 are one direction
        .smoothing(1, 0.2f, CgBlendFilter.Type.SPRING_DAMPER) // the speed eases over 0.2 s
        .build();

CgBlendSpacePlayerNode.of(STRAFE, DIRECTION, SPEED);
```

Feed `direction` from `entity.move_direction` (degrees against the facing, 90 left).

### Faster than the fastest sample

```java
.scaleAxis(CgBlendSpace.ScaleAxis.X)     // past the axis's end, the clips play faster instead of clamping
```

A speed potion then speeds the sprint's legs rather than sliding its feet.

### An axis's own settings

In a file, each axis takes `min`, `max`, `grid` (4), `smoothing: { time, type, damping, max_speed }` and `wrap`. The
space takes `weight_speed`, `weight_ease` (true), `grid_interpolation` (false), `legacy_length` (false) and
`scale_axis`.

## How it blends

- **Its time is shared**: every sample sits at the same fraction of its own clip, so a jog and a sprint of different
  lengths stay in step. Where clips carry sync markers (`foot_l`, `foot_r`), the heaviest marked clip leads and the
  others follow its markers ([`sync-groups.md`](sync-groups.md)).
- **Weights ease** toward each frame's targets at `weightSpeed` a second; `weightEase(false)` moves them at a constant
  speed.
- **The lookup**: between the nearest samples on one axis, by triangles on two. `gridInterpolation(true)` uses a grid
  instead, and `legacyLength(true)` an older way of timing a blend: an importer of an older game's data sets both.

## What goes wrong

- **A sample outside its axes**, or clips of mixed additive kinds, fails when the blend space is built.
- **Past an axis's end** the input is clamped: the feet slide at speeds past the last sample unless `scaleAxis`.
- **A direction axis without `wrap`** blends back through forward when turning from -170 to 170.

## Where next

- Feet in step, and play rate by speed: [`sync-groups.md`](sync-groups.md).
- Feet planted at every speed and turn: [`locomotion.md`](locomotion.md).
- Aim offsets, a blend space of additive poses: [`additive-and-aim-offsets.md`](additive-and-aim-offsets.md).
