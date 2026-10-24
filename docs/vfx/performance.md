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
