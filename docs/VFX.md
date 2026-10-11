# VFX — effects, particles on the GPU, and modules of your own

The VFX engine (`com.crystalgraphics.vfx`) plays effects in a world: an effect ticks on a fixed clock, draws through
`CgWorldRenderer`, and simulates its particles on the GPU, wherever the context runs kernels at any tier. This doc is
for writing effects and particle behaviour. Where each class lives is `vfx/CLAUDE.md`, which loads itself with any
VFX file; plan `vfx-gpu` holds the design and its measurements.

## Playing an effect

A mod plays through its **owner**, a handle on the client world's system, which the engine updates, draws and clears
when the world goes (logout, disconnect, a dimension change):

```java
static final CgVfxOwner VFX = CgVfx.owner("mymod");

CgEnergyWave wave = VFX.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
wave.aim(dx, dy, dz).target(tx, ty, tz);

VFX.play(new Aura(Aura.LOOK, x, y, z).follow(entityId));         // rides an entity; stops when it goes
VFX.play(new Storm(Storm.LOOK, x, y, z).startAt(12f));           // joins 12 s into its life, particles prewarmed

VFX.stop();     // this mod's effects, gracefully: emission ends, what is in flight plays out
VFX.clear();    // this mod's effects at once, particles and all
```

Registered by id, an effect can be played by the spawn command, the network and tools:

```java
VFX.register("hit_spark", "Hit spark", "Impacts", HitSpark.LOOK,
        (look, x, y, z, tx, ty, tz) -> new HitSpark(look, x, y, z));   // as "mymod:hit_spark"
VFX.play("hit_spark", x, y, z, tx, ty, tz);
```

A preview viewport, a test or a harness scene keeps a system of its own, and updates and draws it itself:

```java
CgVfxSystem vfx = new CgVfxSystem();
CgVfxOwner lab = CgVfx.owner("mymod", vfx);
lab.play(effect);
// every frame, before the world stages record (a CgWorldRenderer.onFrame listener):
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

- **Play from any thread.** Into the world system an effect starts at the next world frame; on a dedicated server,
  or before a client's first world, nothing plays.
- **An effect plays once**: playing it again, or into a second system, throws.
- **An effect that throws is killed and logged under its owner**; the rest play on. Its owner's ticks and draws are
  timed as `vfx.effect.tick/<namespace>` and `vfx.effect.submit/<namespace>` and counted as `vfx.effects/<namespace>`.
- **`follow(entity, point)`** places the effect at an attachment point (`CgEntityAttachments`) each frame, at the
  frame's partial tick; when the entity goes, the effect stops. What it launches stays in the world:
  - **Particles** spawn where the effect is now and stay where they spawned. An emitter marked `.local()` rides the
    effect instead, as an aura's sparkles orbit the player (Unity's simulation space, Niagara's Local Space).
  - **What the effect draws itself** moves with it, as an aura's mesh should. An effect that throws drawn parts into
    the world (a beam, a projectile) overrides `followed(x, y, z)` to move only its source: `CgEnergyWave`'s muzzle
    rides the hands while its beam, impact and blast stay put.
- **`startAt(seconds)`** before its first tick: the effect ticks exactly up to there (at most `MAX_START_SECONDS`;
  beyond, its age jumps). Its GPU particles simulate the last `PREWARM_SECONDS` in at most 16 coarse steps, while
  the other effects hold still. Shakes and moments are silent until it reaches its age.
- **Ending**: an effect's `stop()` ends its emission and it dies once what is in flight has played out; `kill()` ends
  it and its particles at once. An owner's `stop()` and `clear()` do each to all its own effects; a system's `stop()`
  to all of them, and `clear()` drops everything, its clock included, keeping its meshes and warmed programs.
- `prepare(look)` warms a look's kernels, meshes and programs before it plays; `warmed()` says when all have built.
  A look played cold compiles on its first frame.
- `timeScale(s)` on a system slows or speeds every effect in it (the demo's time switch); 1 by default.
- The player's settings reach every effect through the system (density, quality tier, pause, tick rate); an effect
  never reads them.

## Tuning a look live

A tool shows a look's numbers and colours as variables (`docs/DEBUG_FLAGS.md`) while it holds a `CgVfxTuning` open.
A change reaches the look, every effect playing it at its next tick, and every effect played after:

```java
CgVfxTuning tuning = CgVfxTuning.open("vfx.crystalgraphics:kamehameha", CgEnergyWave.kamehameha());
CgVars.get("vfx.crystalgraphics:kamehameha.radius").setText(CgVar.Source.CONSOLE, "0.9");
CgVars.get("vfx.crystalgraphics:kamehameha.core.g").setText(CgVar.Source.CONSOLE, "0.8");   // colours: r, g, b, a
tuning.close();   // the variables go; the look keeps what was set for the run
```

- Curves and shakes are not variables. A tuned value wins over one an effect set for itself.
- One tuning a prefix at a time; clearing a variable puts back the value it was opened with.

## Particles: an emitter, an effect, a layer

An **emitter** is what one kind of particle is: when and how many spawn, how each starts, its modules, and its size
and opacity over life. It is immutable and shared; each effect playing it makes an instance.

```java
static final CgVfxSchema SCHEMA = new CgVfxSchema();
static final CgVfxParam EMBER = SCHEMA.color("ember", 1.2f, 0.35f, 0.05f, 1f);
static final CgVfxParam FLAME = SCHEMA.color("flame", 1.4f, 1.1f, 0.5f, 1f);

static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").renderer(CgVfxEmitter.Renderer.QUADS)
        .capacity(1200).rate(120f, 0f, 4f)              // 120 a second for the first 4 seconds
        .shape(1.2f).launch(-1f, -0.7f, 1f).speed(0.5f, 2f).life(2.5f, 3f)
        .size(0.08f, 0.14f, 1f).heat(1f)
        .module(new CgVfxModule.Gravity(9.8f))
        .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f))   // bounces off the world's blocks
        .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
        .layer("sparks")
        .build();

// The look draws the emitter's slot with a shared particle shader, coloured by two of its parameters.
static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).emitter(SPARKS)
        .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/particle/spark.shader").slot("sparks")
                .colors(EMBER, FLAME).build())
        .build();
```

An **effect** holds the instances, ticks them, and hands them to the frame:

```java
final class Sparks extends CgVfxEffect {
    private final CgVfxEmitterInstance sparks;

    Sparks(double x, double y, double z) {
        super(LOOK, x, y, z);
        sparks = new CgVfxEmitterInstance(SPARKS, seed);
        sparks.start(0f, 7f, 0f);                        // where it emits, relative to the effect
    }

    @Override protected void tick(float dt) {
        tick(sparks, dt);                                // steps it: on the GPU, from its first step
        if (sparks.finished() && age > 1f) die();
    }

    @Override protected void submit(CgVfxFrame frame) {
        frame.particles(this, sparks);                   // drawn through every layer on its slot
    }
}
```

- **Call `tick(instance, dt)`, never `instance.tick`**: the effect's own `tick` schedules a GPU-stepped instance, the
  instance's would step it on the CPU.
- **Capacity bounds the live particles**; a spawn into a full instance is dropped. Size it to the burst plus what
  is still alive.
- **One instance an emitter an effect**; a variant of a definition is `SPARKS.toBuilder()...build()`.
- **The same burst at another size** is `SPARKS.scaled(s)`: shape, speed, size and each module's lengths and forces
  times `s`, timing unchanged, children too. `CgEnergyWave` scales its blast and wake by `RADIUS` over
  `AUTHORED_RADIUS`, so a thinner wave bursts smaller. A module kind scales only if it overrides `scaled`; so far only
  the kinds the explosion kit uses do.
- A source that travels or a rate that follows the effect: `moveTo(x, y, z)` moves the source without restarting,
  `rateScale(s)` multiplies the rate, and `stop()` ends a rate open until then (`rate(n, 0f, Float.POSITIVE_INFINITY)`),
  which never finishes otherwise. `CgEnergyWave`'s `WAKE` uses all three.
- `.optional()` marks detail: halved at the Low tier. Density thins every emitter.
- `shape(radius)` starts particles anywhere within a ball round the source; `shape(inner, outer)` on a shell, as smoke
  rolling out from the edge of a dome rather than through it. `sweep(speed)` runs that shell outward over the rate's
  span, easing to a stop, so particles are born on a moving front, as dust a shock front lifts (a rate and no bursts).
- **Each look draws as one multi-draw**, so two looks order as wholes, not particle by particle. A solid look's shader
  writes depth where it covers (`"Depth" = "Clip"`); a soft one solid in its middle writes a depth prepass there
  (`"Depth" = "Prepass"`, `billow`); only a look soft throughout relies on blending alone. A Clip look draws first in
  its effect (`docs/SHADERS.md` § *Depth from a transparent material*).
- The shared looks in `shaders/vfx/particle/` (`spark`, `speck`, `dust`, `arc`, `sprite`, `sprite_glow`) read the
  particle records; their parameters are `vfx/CLAUDE.md`'s.

## Modules

What moves a particle is its module stack, run in order each step. Forces come first and the solver integrates them;
a kind marked after the solver (collisions, kills) sees where the particle went.

| Module | Does |
|---|---|
| `Gravity`, `Force` | A constant pull down; a constant push in any direction |
| `Drag`, `Damping`, `LimitSpeed` | Linear and quadratic drag; velocity decaying by a rate; a speed cap |
| `Wind`, `Turbulence`, `Buoyancy`, `Updraft` | The system's gusting air; curl noise; hot particles rising as they cool; a column of rising air |
| `Attract`, `Orbit`, `Vortex`, `Conform` | Pull toward a `Volume`; circling an axis; a vortex's tangential and radial pull; settling onto a sphere's shell |
| `VectorField` | A 3D field (`CgVfxField`) in a box, setting velocity |
| `Spin` | Spin rate decaying |
| `Ground` | Landing on the effect's ground: restitution, friction, rest |
| `Current` | Dust in a gravity current along the ground: a fast front rides up into a rolling head, stalled dust lofts |
| `Collide` | A sphere, box, plane or container (`Volume`): bounce, friction, kill on contact |
| `CollideWorld` | The world's blocks, from the voxel window round the camera |
| `CollideDepth` | The scene's depth: mobs and players on screen, what the voxel window does not hold |
| `Kill` | Dies inside (or outside) a `Volume` |

```java
.module(new CgVfxModule.Attract(CgVfxModule.Volume.sphere(2f).at(0f, 3f, 0f), 12f, 1f, 0f, 0f, 1f, 0f))
.module(new CgVfxModule.Collide(CgVfxModule.Volume.sphere(1.8f).at(0f, -5.2f, 0f), 0.6f, 0.05f, 0.3f))
.module(new CgVfxModule.Kill(CgVfxModule.Volume.box(4f, 1f, 4f).at(0f, -2f, 0f), true))
```

## Events

An event fires per particle (landing, death, an age, each collision, every period) and spawns children of another
emitter where it fired, reports its rows to the CPU, or both.

```java
// Each bounce: a flash at the contact and four chips off the surface
.event(CgVfxEvent.onCollision().spawn(FLASH, 1))
.event(CgVfxEvent.onCollision().spawn(CHIPS, 4).inherit(0.3f))   // children take 30% of the parent's velocity

// Each landing reported to the CPU, a few frames later: a sound, a decal, a gameplay hook
.event(CgVfxEvent.onLanding().readback(64))
vfx.onEvents((definition, event, rows) -> {
    for (int i = 0; i < rows.count(); i++) sounds.play(HISS, rows.x(i), rows.y(i), rows.z(i));
});
```

- **One level deep**: a child's events may report rows but not spawn children.
- **Add every child to the look** (`.emitter(FLASH)` and a layer on its slot), or it simulates and draws nothing.
- At most `CgVfxEvent.MAX_EVENTS` events an emitter and `MAX_CHILDREN` children an event. A collision fires at most
  `COLLISION_FIRINGS` times a particle.
- Rows come a frame or two after their step: steer by what arrived, never wait for it.

## A module of your own

A module is a record implementing `CgVfxModule` and a GLSL function, `fx_<kind>`, which the generated Step kernel
calls. Its numbers are written once a definition; nothing recompiles when they change.

```java
record Swirl(float strength) implements CgVfxModule {
    public String gpuKind() { return "mymod_swirl"; }
    public boolean afterSolve() { return false; }
    public void writeParams(CgVfxWords out) { out.vec4(strength, 0f, 0f, 0f); }
    public String gpuSource() {                          // or a file: shaders/lib/vfx/sim/fx_mymod_swirl.glsl
        return """
                void fx_mymod_swirl(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
                    f.accel += cross(vec3(0.0, 1.0, 0.0), p.position) * m.x;
                }
                """;
    }
}
```

The variations, each with an example in `CgVfxGpuModule`'s javadoc:

| Needs | Declares | Its function then takes |
|---|---|---|
| more numbers | `paramVectors()` | `vec4 m0, m1, ...` |
| a value per instance each step (a moving centre, in doubles) | `instanceLanes()`, `writeInstance` | one argument per lane, after the numbers |
| the world after the solver (floor, blocks, light) | `afterSolve()` true, `worldInputs()` | the world values, after the lanes; `FxWorld` for the window |
| a texture (a field, a heightfield) | `textures()` | a `sampler2D` or `sampler3D` each, after the world |
| an impact that fires `onCollision` | after the solver; calls `fx_hit(p, n)` on a punctual impact | — |

- `fx_types.glsl` has the structs (`FxParticle`, `FxForces`, `FxStep`). A kind kills a particle with `p.life = p.age`.
- **Prefix a kind with the mod's name**: two kinds of one name with different source throw.
- **Answer the arrays as constants**: they are asked every step.
- Write exactly the vec4s declared, or the pool throws naming the kind.

## Where it runs

Every particle is simulated on the GPU: compute at G43 and on Vulkan, lowered to draws at G40 and G33
(`docs/SHADERS.md` § *Every tier*), stepped at 60 Hz while effects tick at 120. Draw a particle at
`frame.particleAlpha()`, never `frame.alpha()`.

The Java simulation (`-Dcrystalgraphics.vfx.sim=cpu`, the harness's V) remains only as the "before" of a comparison
and will be deleted: build nothing on it. It lacks what only the GPU has: `CollideDepth`, the voxel window's octants
under `CollideWorld` and the floor, and Range's lighting from the window.

## Checking and measuring

| Gate | Holds |
|---|---|
| `--mode=vfx-sim-equivalence` | the Step kernels' noise and hashes against Java |
| `--mode=vfx-events` | events: every row heard once, every child where Java puts it |
| `--mode=vfx-collide` | collisions and rates off a ground and a wall, firings numbered |
| `--mode=vfx-inputs` | vector fields, heightfields, the depth pyramid |
| `--mode=vfx-world` | the voxel window, its distance field and Range's light |
| `--mode=vfx-range` | drawing by slot, and size over speed |

Each must pass at every forced tier (`-Dcrystalgraphics.compute.tier=G43|G40|G33`) and on `--device=vulkan`. Scenes
to look at: `vfx-modules` (a station per module), `vfx-blasts` (360,000 particles), `vfx-spheres-stress`.

Trace channel `crystalgraphics.vfx`: `vfx.tick`, `vfx.emitters.workers`, `vfx.effect.submit` (per owner,
`vfx.effect.tick/<namespace>` and `vfx.effect.submit/<namespace>`), and the GPU zones `vfx.pool.step` and
`vfx.pool.range`; counters `vfx.gpu.slots-stepped`, `vfx.draws.particle-gpu`, `vfx.effects/<namespace>`
(`docs/PROFILING.md`).
