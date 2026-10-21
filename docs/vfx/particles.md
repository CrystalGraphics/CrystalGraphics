# Particles

An **emitter** (`CgVfxEmitter`) describes one kind of particle: how many, where they are born, how they move, how
long they live. It is a definition, shared and never changed by playing. An **emitter instance**
(`CgVfxEmitterInstance`) is one of them running inside an effect, with its own clock. This is Niagara's split between
an emitter asset and the emitter running in a component.

Writing emitters and their modules is still [`../VFX.md`](../VFX.md) §11 and §12; this page grows as the engine's
particle work lands.

## Renderers: how an emitter's particles draw

An emitter's `renderers:` say how its particles draw, Niagara's renderer properties: each a kind, a `material` asset,
and what feeds the material. One emitter may hold several, each drawing the same particles.

```yaml
# assets/mymod/vfx/emitters/sparks.yaml
renderers:
  - id: r1
    kind: sprite
    material: mymod:materials/fx/sparks       # a material asset (materials.md)
    alignment: velocity                       # stands along its motion
    bind: { _Color: user.ember }              # read per draw from the system's user parameter
    values: { _SoftDistance: 0.25 }           # this renderer's own constant
    sort_order_hint: 1                        # drawn after hints below it
```

```java
CgMaterialDefinition sparks = CgMaterialDefinition.builder("mymod:materials/fx/sparks",
        "crystalgraphics:shaders/vfx/particle/sprite.shader").blend(CgBlendMode.ADDITIVE).build();
CgVfxEmitter emitter = CgVfxEmitter.builder("sparks")
        .renderer(CgVfxRenderers.sprite("r1").material(sparks).alignment("velocity")
                .bind("_Color", "user.ember").value("_SoftDistance", 0.25).sortOrderHint(1).build())
        .build();
```

| Kind | Draws | Its emitter |
|---|---|---|
| `sprite` | a quad a particle: `alignment` (`unaligned`, `velocity`), `facing` (`camera`, `camera_plane`, `camera_position`), `sort` (`none`, `view_depth`), `sub_image` (a grid flipbook) | quads |
| `mesh` | one mesh a particle (`meshes: [{ mesh: <path> }]`), the unit sphere with none | `MESHES` |
| `ribbon` | one strip through an instance's particles, newest first | quads |
| `trails` | a strip behind each particle; its `points` are the ring its emitter keeps (`trail(points)`) | quads |
| `arc` | a stroke round the source | `ARCS` |
| `sequence` | a clip's parts at each particle, drawn by the clip's prefab | a clip |

- **Binds read user parameters alone** (`user.<name>`, or `{ bind: user.<name>, scale: s }`): a row a draw, so a hundred
  effects of one emitter stay one draw. A particle's own attributes are read in its material
  (`cg_dynamic_parameter`, `CG_PARTICLE_COLOR`), so `particles.*` fails at load.
- **Values on a property the material reads per draw** are constants of the row; **keywords and other values** make a
  child material, shared by every renderer with the same overrides.
- **What is not drawn yet fails at load by name**, rather than drawing as something else: the `system`, `anim_trail`,
  `beam` and `light` kinds, a material named by a user parameter, and fields such as a custom sprite facing, a
  pivot off the middle, a rect-table `sub_image`, a mesh prefab or `locked_axis`, a ribbon's `shape: tube`.
- **Order** is the hint, then the look's order; a renderer whose material has a Distortion pass bends what draws before
  it, and every other draws after its effect's own bend.

## When particles spawn: emitter time

An emitter runs in **loops**, as a Niagara emitter does. Its **state** (`CgVfxEmitterState`, Niagara's Emitter
State module) says how long a loop lasts, how many run, and how long to wait before each. Within each loop its
**spawn modules** fire: a rate, bursts, and spawns by distance. The spawn types live in `vfx.particle.spawn`.

| Niagara | Here | What it is |
|---|---|---|
| Emitter State | `state(CgVfxEmitterState...)` | its loops |
| Spawn Rate | `rate(perSecond)`, `rate(perSecond, curve)` | so many a second while each loop runs |
| Spawn Burst Instantaneous | `burst(time, count)`, `burst(CgVfxSpawnBurst...)` | so many at once, at a time into each loop |
| Spawn Per Unit | `ratePerBlock(n)` | so many for each block its source moves |

### The default loops for ever

An emitter that says nothing loops for ever, a loop a second (`CgVfxEmitterState.DEFAULT_DURATION`), as Niagara's
default does. A rate then simply runs until the instance is stopped. **Bursts fire again every loop**, so
an emitter meant to burst once says so:

```java
// A one-off: 120 sparks at the start, never again
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE)
        .capacity(200).burst(0f, 120).speed(2f, 5f).life(0.4f, 0.9f).build();

// An aura: 60 a second until the instance is stopped (the default state)
static final CgVfxEmitter AURA = CgVfxEmitter.builder("aura").capacity(400).rate(60f)
        .shape(CgShell.of(0.6, 0.9)).speed(0.2f, 0.5f).life(1f, 1.5f).build();
```

### One loop of a set length

```java
// 400 a second for 1.6 s, then nothing: the instance finishes once the last of them dies
static final CgVfxEmitter SMOKE = CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f))
        .capacity(800).rate(400f).life(0.5f, 1f).build();

// The same, starting 0.15 s after the instance does
static final CgVfxEmitter LATE = SMOKE.toBuilder().state(CgVfxEmitterState.once(1.6f).delay(0.15f)).build();
```

`CgVfxEmitterState.ONCE` is one loop that never ends: its bursts fire once at their times, and a rate runs until
stopped. `once(seconds)` ends the loop after that long: its rate stops, and a burst timed after it never fires.

### Loops that renew

```java
// Kakarot's beam core: one mesh particle renewed every 2.9 s for as long as the beam plays
static final CgVfxEmitter CORE = CgVfxEmitter.builder("core").state(CgVfxEmitterState.infinite(2.9f))
        .renderer(CgVfxEmitter.Renderer.MESHES).capacity(4).burst(0f, 1).life(2.9f, 2.9f).build();

// Three puffs half a second apart, then done
static final CgVfxEmitter PUFFS = CgVfxEmitter.builder("puffs").state(CgVfxEmitterState.loops(3, 0.5f))
        .capacity(60).burst(0f, 15).life(0.4f, 0.6f).build();

// A crackle at uneven intervals: a wait of 0.2 to 0.8 s before each one-second loop, rolled again each loop
static final CgVfxEmitter CRACKLE = CgVfxEmitter.builder("crackle")
        .state(CgVfxEmitterState.infinite(1f).delay(0.2f, 0.8f).recalculateDelayEachLoop())
        .capacity(40).burst(0f, 8).life(0.1f, 0.2f).build();

// Loops of 0.8 to 1.4 s, each its own length
CgVfxEmitterState.infinite(0.8f, 1.4f).recalculateDurationEachLoop()

// Waits half a second, once, then loops every second
CgVfxEmitterState.infinite(1f).delay(0.5f).delayFirstLoopOnly()
```

A duration or a delay given as a range is rolled once, when the instance starts, and kept for every loop, as
Niagara's are; `recalculateDurationEachLoop()` and `recalculateDelayEachLoop()` roll it again for each.

### A rate that changes, and bursts that vary

A rate's curve runs over each loop's seconds and multiplies the rate. Two keys at one time make a step:

```java
// Thinning out over the loop
.state(CgVfxEmitterState.once(1.6f))
.rate(400f, CgKeyframes.start(0f, 1f).to(1.6f, 0f, CgEasings.OUT_QUAD).build())

// Nothing for the first half second of each loop, then 120 a second
.rate(120f, CgKeyframes.start(0.5f, 0f).to(0.5f, 1f, CgEasings.LINEAR).build())
```

A burst can roll its count each loop, and fire in only some loops:

```java
.burst(CgVfxSpawnBurst.at(0f, 8, 16))                    // 8 to 16, each loop
.burst(CgVfxSpawnBurst.at(0.1f, 1).probability(0.3f))    // one, in three loops of ten
```

### Easy to get wrong

- **A burst emitter without `state(...)` re-fires every loop.** Give a one-off `CgVfxEmitterState.ONCE`.
- **A delay delays everything in the loop**, its bursts too: a burst at 0 fires after the delay.
- **Every roll comes from the instance's seed**: a delay, a burst's count, a probability. Every client playing the
  same instance spawns the same.
- **An effect's seed is drawn from the engine's random stream** unless `CgVfxEffect.seeded(...)` or a cue gave it one.
  A capture that must repeat run to run turns on `-Dcrystalgraphics.fixedSeed=true` (or `CgRandom.fixedSeed(true)` at
  startup; the harness does), which seeds that stream from a constant, as Unreal's `-FixedSeed` does.
- **Loops of no time are refused**: a looping state needs a duration or a delay, or `build()` throws.
- **`sweep(speed)` needs a sphere, shell, cylinder or ring and a loop of finite length**: its front runs over each
  loop, the radius as a curve by the loop's seconds, its longest loop's length.

## How each particle looks: colour and size per axis

An emitter gives each particle a colour and a size on each axis, which **multiply what its layers draw**, as Niagara's
Initialize Particle and its Scale modules do. A layer's `_Color` stays the look's palette; the emitter's colour tints
it per particle and over its life.

| Niagara | Here | What it is |
|---|---|---|
| Initialize Particle colour | `color(argb[, intensity])`, `color(a, b)` | its start colour; between two, each channel its own roll |
| Scale Color | `colorOverLife(r, g, b)`, `intensityOverLife(curve)`, `opacity(curve)` | its colour, its HDR intensity and its alpha over life |
| Initialize Particle sprite size, mesh scale | `size(min, max)` times `scale(x, y, z)` or `scale(min xyz, max xyz)` | its start size, then each axis's share of it |
| Scale Sprite Size, Scale Mesh Size | `size(curve)`, `scaleOverLife(x, y, z)` | all axes over life, then each one |

```java
// Sparks from gold to red-orange, a white flash at birth fading to their colour, cooling as they die
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE)
        .capacity(300).burst(0f, 200).speed(4f, 9f).life(0.5f, 1f).size(0.04f, 0.08f)
        .color(0xFFFFD27A, 0xFFFF5A1F)
        .intensityOverLife(CgKeyframes.start(0f, 5f).to(0.15f, 1f, CgEasings.OUT_QUAD).build())
        .colorOverLife(CgKeyframes.constant(1f),
                CgKeyframes.start(0f, 1f).to(1f, 0.4f, CgEasings.LINEAR).build(),
                CgKeyframes.start(0f, 1f).to(1f, 0.2f, CgEasings.LINEAR).build())
        .build();

// A beam's streak: one mesh particle six times as long as it is wide, stretching to nine as it lives
static final CgVfxEmitter STREAK = CgVfxEmitter.builder("streak").renderer(CgVfxEmitter.Renderer.MESHES)
        .state(CgVfxEmitterState.ONCE).capacity(4).burst(0f, 1).life(1f, 1f).size(0.5f, 0.5f).scale(1f, 1f, 6f)
        .scaleOverLife(CgKeyframes.constant(1f), CgKeyframes.constant(1f),
                CgKeyframes.start(0f, 1f).to(1f, 1.5f, CgEasings.OUT_QUAD).build())
        .build();

// Rain: quads twice as tall as wide, each a little different
CgVfxEmitter.builder("rain").size(0.03f, 0.05f).scale(0.8f, 2f, 1f, 1.2f, 3f, 1f)
```

- **A quad takes x across and y up**; a mesh takes all three in its own axes, before it turns. Strokes (`ray`, `arc`)
  take x as their width.
- **White and 1 change nothing**: an emitter that says none of this draws as it always has.
- **A range between two rolls each channel and each axis on its own**, as Niagara's non-uniform ranges: two colours'
  in-betweens mix channels, so `color(0xFFFF0000, 0xFF0000FF)` gives purples, reds, blues and black. For one roll that
  keeps proportions, give `size(min, max)` the range and `scale` one value.
- **Colours are `0xAARRGGBB`** as shaders author them; an intensity past 1 blooms. The start colour's alpha multiplies
  the opacity.
- **A shader of your own** reads `CG_PARTICLE_COLOR(i)` and `CG_PARTICLE_SCALE(i)` on a quad, or `CG_OBJECT_CUSTOM2.rgb`
  on a mesh particle, and multiplies them in: a shader that ignores them draws every particle the same.
- **The grids' rasterize keeps the uniform size**: a particle splats a ball whatever its axes.

## Turning an emitter: its effect's orientation

An emitter spawns through its effect's **orientation**, as a Niagara emitter spawns through its component's transform:
its shape, the shape's offset (`at`) and the way its particles launch all turn with it. What turns an effect:

| What | How |
|---|---|
| The point it follows | `follow(entity, point)`: the point's rotation each frame, as Unreal's socket transform |
| Its own turn | `orient(quaternion)`, after the point's: `orientation()` is the two together |

```java
// A charge held in the right hand: its spray points along the arm wherever the hand turns
VFX.play(new Charge(LOOK, x, y, z).follow(playerId, CgEntityAttachments.RIGHT_HAND));

// An effect facing a way of its own, and one spinning
VFX.play(new Fan(LOOK, x, y, z).orient(new Quaternionf().rotationY(yaw)));
fan.orient(spin.rotateY(2f * dt));                     // in its tick: a quarter turn every 0.8 s
```

A point's rotation comes from where the model was drawn: a matrix mark (`CgEntityAttachments.mark(entity, point,
poseStack.last().pose())`) carries its part's turn, so a hand turns as the arm is posed. Otherwise it is the body's
proportions: the look for the head, the eyes and the hands held forward, the body's yaw for the rest, `+Z` the way it
faces. `CgEntityAttachments.rotation(...)` answers it for code of your own.

### World or local: what already spawned does

```java
static final CgVfxEmitter SPRAY = CgVfxEmitter.builder("spray").capacity(1200).rate(300f)   // world (the default)
        .shape(CgCone.of(0.3, Math.toRadians(8))).speed(5f, 6f).life(1f, 1f).build();
static final CgVfxEmitter RING = CgVfxEmitter.builder("ring").local().state(CgVfxEmitterState.ONCE)
        .capacity(48).burst(0f, 48).shape(CgRing.of(1.6, 1.6, 0)).edge().ordered(48).life(4f, 4f).build();
```

- **A world emitter** (the default) spawns turned, and what it spawned keeps its course: a sprinkler's spray, sparks
  thrown off a turning blade.
- **A `local()` emitter's** particles turn with the effect, about its origin, as Niagara's Local Space: a ring of
  runes tilting with a shield, an aura round a spinning fighter.

The harness's `vfx-modules` shows both side by side at its fourth row's far end ("turning").

### Easy to get wrong

- **The source does not turn**: `start(x, y, z)` and `moveTo` place it where the effect says. Turn what is round it
  with the shape's `at(...)`, which does.
- **An event's children launch about the event's normal**, not the effect's orientation; their parent was turned.
- **Its own drawing is its code's**: `orient` turns its particles alone, never its tubes or meshes.

## Ending an emitter: let it finish, or kill it

An instance ends one of two ways, as a Niagara emitter does:

| Call | Spawning | Particles already alive |
|---|---|---|
| `instance.stop()` | ends now | live out their lives, unless the emitter's inactive response is `KILL` |
| `instance.kill()` | ends now | end in the next particle step, firing no event |

### Stopping

```java
public final class Aura extends CgVfxEffect<Aura> {
    static final CgVfxEmitter SPARKLES = CgVfxEmitter.builder("sparkles").capacity(400)
            .rate(60f)                                           // looping for ever: until stopped
            .shape(CgShell.of(0.6, 0.9)).speed(0.2f, 0.5f).life(1f, 1.5f).build();

    private final CgVfxEmitterInstance sparkles;
    private boolean ending;

    public Aura(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
        sparkles = new CgVfxEmitterInstance(look.emitter("sparkles"), seed);
        sparkles.start(0f, 1f, 0f);
    }

    public void end() {
        ending = true;
        sparkles.stop();                                     // no more sparkles; the ones in the air fade out
    }

    @Override protected void tick(float dt) {
        tick(sparkles, dt);
        if (ending && sparkles.finished()) die();            // once the last one has died
    }
}                                                            // drawn by the system: no submit needed
```

### Killing

`kill()` empties an emitter at once: the aura cut off when its owner is hit, Cascade's and Naruto Storm's `clear`.

```java
public void interrupt() {
    sparkles.kill();                                         // every sparkle gone at the next particle step
    die();                                                   // finished() is already true
}
```

A killed instance can play again: `start` gives it a fresh slot.

```java
sparkles.kill();
sparkles.start(0f, 1f, 0f);                                  // the old sparkles go; new ones spawn from the next tick
```

### The emitter decides: its inactive response

Some particles should never outlive their emitter: a beam's core vanishes with the beam. Say so in its state, and
`stop()` kills them, as does the effect no longer stepping the instance (it died, or stopped calling
`tick(instance, dt)`). This is the Inactive Response of Niagara's Emitter State module.

```java
static final CgVfxEmitter CORE = CgVfxEmitter.builder("core").capacity(64)
        .state(CgVfxEmitterState.DEFAULT.inactiveResponse(CgVfxInactiveResponse.KILL))   // COMPLETE unless given
        .rate(30f).life(0.4f, 0.4f).build();
```

### Easy to get wrong

- **`kill()` fires no event**: a killed particle spawns no death children and reports no row. Its event children's
  particles go with it.
- **`kill()` before `start()` does nothing.**
- **Spawns booked in the tick of a kill are dropped**, a `start()` in the same tick included: it spawns from the tick
  after.
- **`CgVfxEffect.kill()` is the whole effect**: every emitter of it, and the effect itself. `instance.kill()` is one
  emitter's particles, and the effect plays on.
