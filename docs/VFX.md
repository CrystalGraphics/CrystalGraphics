# VFX — effects, particles on the GPU, and modules of your own

The VFX engine (`com.crystalgraphics.vfx`) plays effects in a world: an effect ticks on a fixed clock, draws through
`CgWorldRenderer`, and simulates its particles on the GPU, wherever the context runs kernels at any tier. This doc is
for writing effects and particle behaviour. Where each class lives is `vfx/CLAUDE.md`, which loads itself with any
VFX file; plan `vfx-gpu` holds the design and its measurements.

## Playing an effect

```java
CgVfxSystem vfx = new CgVfxSystem();
CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
wave.aim(dx, dy, dz).target(tx, ty, tz);

// every frame, before the world stages record (a CgWorldRenderer.onFrame listener):
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

- `vfx.prepare(look)` warms a look's kernels, meshes and programs before it plays; `vfx.warmed()` says when all have
  built. A look played cold compiles on its first frame.
- `vfx.clear()` ends every effect and gives back its GPU slots, keeping what it built for the next.
- The player's settings reach every effect through the system (density, quality tier, pause, tick rate); an effect
  never reads them.

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
- `.optional()` marks detail: halved at the Low tier. Density thins every emitter.
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

Trace channel `crystalgraphics.vfx`: `vfx.tick`, `vfx.emitters.workers`, `vfx.effect.submit`, and the GPU zones
`vfx.pool.step` and `vfx.pool.range`; counters `vfx.gpu.slots-stepped`, `vfx.draws.particle-gpu`
(`docs/PROFILING.md`).
