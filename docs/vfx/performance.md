# Performance: what a particle frame costs

Every GPU figure here is an RTX 4070 SUPER on Vulkan, Vulkan validation off. How to take your own:
[`../PROFILING.md`](../PROFILING.md).

## Pools: the fixed cost a frame

Particles step and draw in **pools**, one for each **shape**: the kinds of modules an emitter stacks, how each of its
inputs is given (a constant, a range, a curve by age, a bound user parameter, ...), its renderers' layout, its data
interfaces, its events and its feed tier. Every instance of every emitter of one shape shares that pool's few
dispatches, however many effects play it. Numbers never make a shape: two emitters differing only in their values
share a pool.

| A live pool costs | Each frame |
|---|---|
| GPU | about 15.5 µs |
| render thread | about 36 µs: recording, building the frame graph, executing its two dispatches, a step and a sort key |

- **An empty pool costs nothing.** What counts is the shapes alive at once: a move of 40 shapes pays about 0.6 ms of
  GPU and 1.4 ms of render thread while all 40 play.
- **What every pool shares is done once**, as Niagara does it for its emitters: one upload of every pool's rows, every
  pool's count in one buffer cleared by one dispatch, one placement of every pool's draw order. On compute the step
  writes what the draws read as it keeps each particle (`crystalgraphics.vfx.fuseLook=false` keeps that a stage of its
  own, as it is below compute).
- **The particles' own work comes on top**: `vfx-blasts` steps 360,000 particles in about 2.9 ms.

## Shapes coalesce

Once the loaded systems take more shapes than the pool budget (48), the engine raises inputs so that emitters
differing only in how they give them share a pool. A raise never changes a particle: it gives the same value in a more
general form.

| Given | Raised to, beside |
|---|---|
| a constant | a range with equal ends; a pick of one; a flat curve; a lane holding it |
| a range rolled once for all its channels, a curve by one lookup | `random_range` between two curves by that lookup |
| nothing (the module or spawn value left out) | what the emitter's step reads without it: no velocity, no orbit, its shape's own offset and radius, a start colour where its two start colours agree |

```yaml
# gust: pushed by a constant
particle_update:
  - { id: push, module: crystalgraphics:acceleration, inputs: { acceleration: [0, 9.8, 0] } }
```

```yaml
# squall: pushed by a range. Once raised, gust's constant is a range of equal ends, and both share one pool.
particle_update:
  - { id: push, module: crystalgraphics:acceleration, inputs: { acceleration: { range: [[0, 8, 0], [0, 12, 0]] } } }
```

```java
// The same in Java: a system prepared before it plays takes part, as every asset readied for play is.
VFX.prepare(GUST_LOOK, SQUALL_LOOK);
CgVfxShapeCoalescing.loaded().shapes();   // how many shapes the loaded systems take now
```

- **Prepare before playing.** A system joins when it is prepared (`VFX.prepare`, or its asset readied for play). One
  played unprepared keeps its own shape, and one prepared later can move an earlier system into a new pool, which
  then compiles its kernel.
- **What never coalesces**: a word bound to a user parameter (its value is each instance's), two classes nothing
  covers (a per-channel range beside a curve, a pick beside a range), different widths (an rgb colour beside an rgba
  one), a mesh's turn and a light left out, and different module stacks.
- **To keep pools few**: give an input across your emitters in the same form where you can, and keep module stacks
  alike. A flat value written as a one-key curve costs a pool nothing beside curves.
- `-Dcrystalgraphics.vfx.poolBudget=0` raises every group that saves a pool, whatever the count: how the form gates
  check that a raise draws what was given.

## Scalability: what is too far, too many or out of view

Every look has a `CgVfxScalability`, `DEFAULT` unless it names one: when its effects are culled, drawn lower or put to
sleep. Distance is from the camera to what the effect drew, not its origin, so a long beam stays while any of it is near.

```java
public static final CgVfxScalability IMPACTS = CgVfxScalability.builder("mymod:impacts")
        .maxDistance(64).maxInstances(24).whenCulled(CgVfxScalability.Reaction.KILL).build();

CgVfxLook.builder(SCHEMA).with(KIT).scalability(IMPACTS).build();
VFX.play(new Aura(LOOK, x, y, z)).scalability(CgVfxScalability.HERO);   // one effect: the player's own
```

| Call | Means |
|---|---|
| `maxDistance(blocks)` | culled once the camera is farther than that from every part of it |
| `maxInstances(n)`, `maxPerLook(n)` | at most `n` of every look sharing this object, or of one look; the nearest kept, the local player's always |
| `unseen(seconds)` | culled once nothing of it has been in view that long |
| `lodDistance(blocks)` | a quality tier lower past it (renderers of a higher `quality` drop, half the particles), two past twice it (a quarter) |
| `whenCulled(SLEEP \| STOP \| KILL)` | asleep (the default): no ticks, no draws, its particles freed, and when wanted again it plays on from its true age, as `startAt` starts one; `STOP` ends it its own way; `KILL` at once |

| Preset | For |
|---|---|
| `DEFAULT` | most effects: gone past 160 blocks, a tier lower past 48 |
| `AMBIENT` | embers, mist, spray: past 64 blocks, or two seconds out of view; 64 at most |
| `IMPACT` | hits, sparks, puffs: 48 at most, past 96 blocks, killed rather than kept asleep |
| `FAR` | a blast on the horizon, a storm: to 512 blocks |
| `HERO` | never culled, always full detail |

- It is decided once an update against the world's latest camera, and when an effect is played: one past its distance
  then never starts, or starts asleep.
- A particle's bounds are a sphere round its source it cannot leave (its emitter's speed, life and pull), as Niagara gives
  a GPU emitter fixed bounds: no readback, nothing late.
- **Over the CPU's budget the presets shrink**: their distances and caps scale down by the budget's usage, to a
  quarter at most ([below](#the-cpus-budget)). Counters `vfx.culled`, `vfx.asleep`.
- **Particles never thin by GPU time**, as Niagara's budget is the game thread's: an effect spawns what it asks, at
  the player's density, on every device. Their GPU cost is the quality tier's and scalability's to bound.
- Asleep past `MAX_SLEEP_SECONDS` (120) an effect is killed, so a one-off culled for good never lingers.
- A server's copy of an authoritative effect is never culled.

## The CPU's budget

Every effect's CPU work is measured against a budget, as Unreal's FX budget is, and each scalability says what it does
as the budget fills. Two lines are kept: **frame**, the frame thread's effects work (the systems' updates and submits,
the pools', Range's, grids' and fluids' recording, and their passes as they execute), and **concurrent**, that and the
workers' booking together.

| Tier | Frame | Concurrent |
|---|---|---|
| Low | 2 ms | 3 ms |
| Medium | 3 ms | 4.5 ms |
| High | 5 ms | 7 ms |
| Ultra | 6 ms | 8 ms |

```java
CgVfxBudget budget = CgVfxBudget.get();
float usage = budget.worstAdjustedUsage();                   // 0 at rest, 1 at the budget, at most 2
float ms = budget.time(CgVfxBudget.Line.FRAME);              // the frame line's milliseconds, averaged

public static final CgVfxScalability AMBIENT_FX = CgVfxScalability.builder("mymod:ambient")
        .maxDistance(64).maxInstances(32)
        .scaleDistanceByBudget(CgVfxLinearRamp.DEFAULT)      // 1 up to half the budget, a half at all of it
        .scaleInstancesByBudget(new CgVfxLinearRamp(1f, 1f, 2f, 0.25f))
        .cullByBudget(1.5f)                                   // gone at half again over it
        .build();
```

| Call | Means |
|---|---|
| `scaleDistanceByBudget(ramp)` | `maxDistance` times the ramp at the usage |
| `scaleInstancesByBudget(ramp)`, `scalePerLookByBudget(ramp)` | `maxInstances` and `maxPerLook` times the ramp, truncated |
| `cullByBudget(usage)` | culled, by its `whenCulled` reaction, while the usage is at it or past it |

- **The usage is each line's time averaged over 60 frames (those before the first counting 0), over its budget**; the adjusted usage the scalabilities
  read rises at once and falls by 0.1 a second, at most 2, so what the budget culled does not flick back on the next
  frame. One long frame (a hitch) lifts it for some seconds.
- **The presets** (`DEFAULT`, `AMBIENT`, `IMPACT`, `FAR`) scale their distances and caps by `(1, 1, 2, 0.25)`: nothing
  until over budget, a quarter at the adjusted most. None culls by the budget; `HERO` never scales. A scalability of
  your own scales nothing unless it says so, as Niagara's effect types.
- **The budget never lowers a tier**: `lodDistance` stays where it is set.
- **The local player's effects are kept** whatever the budget.
- **Two ultimates at once fit High**: about 96 pools stepping take 2.3 to 4.2 ms of the frame line on an RTX 4070
  SUPER's machine.
- `crystalgraphics.vfx.budget.frameMs` and `.concurrentMs` set a budget whatever the tier;
  `crystalgraphics.vfx.budget.debug.frameTime` and `.concurrentTime` stand in for the measured time, to see what the
  scalabilities do under load. Counters `vfx.budget.frame-us`, `vfx.budget.concurrent-us`, `vfx.budget.usage-percent`.
- **Work of your own** outside an effect's tick and submit is charged too: a stage recorder through
  `CgVfxBudget.charged(recorder)`, its passes with `charged(CgVfxBudget.FRAME)`, work on workers into
  `CgVfxBudget.WORKERS`.
