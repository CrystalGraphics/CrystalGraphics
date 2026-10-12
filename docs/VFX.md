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
VFX.enabled(false);   // a dev key: ends them and plays nothing until enabled again
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
  - **Any point on a model, whatever animates it**: a mod declares its own
    (`CgEntityAttachments.declare("mymod:right_palm", RIGHT_HAND)`) and marks it where the model is drawn, one call a
    frame: `mark(id, PALM, poseStack.last().pose(), x, y, z)` from a render layer over a vanilla model (playerAnimator,
    Emotecraft), `mark(id, PALM, bone.getWorldPosition())` from GeckoLib. Or it registers a `Provider` that answers
    when asked. A mark beats a provider, which beats the declared fallback's proportions; built-in points can be
    marked too, which moves every effect already following them.
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
| `CollideEntities` | The boxes of the 64 living entities nearest the camera, on screen or not; struck only on entering, so a particle born in its caster flies out |
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

## Collisions

What an effect or its particles strike reaches one listener type, `CgVfxCollisionListener`, at whichever scope fits:
the effect's, its owner's, or every effect's. Each hears a `CgVfxCollision`: its source (`EFFECT`, `PARTICLE`), what was
struck (`BLOCK`, `ENTITY`, `GROUND`, `SCENE`, `SHAPE`, `TARGET`, `OTHER`), the entity, where, the normal and the velocity.

```java
// One effect's: a wave that finds its own target reports what it strikes
CgEnergyWave wave = VFX.play(new CgEnergyWave(look, x, y, z).follow(player, HANDS_FORWARD).collide());
wave.onCollide(c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) damage(c.entity()); });

// A mod's effects', particles included: scorch marks wherever anything lands on a block
CgVfxCollision.Registration scorch = VFX.onCollide(c -> {
    if (c.hit() == CgVfxCollision.Hit.BLOCK) decals.scorch(c.x(), c.y(), c.z(), c.nx(), c.ny(), c.nz());
});

// Particles report only from emitters that ask to
static final CgVfxEmitter DEBRIS = CgVfxEmitter.builder("debris")
        .module(new CgVfxModule.CollideWorld(0.3f, 0.4f, 0.4f))
        .module(new CgVfxModule.CollideEntities(0.3f, 0.4f, 0.4f, 0.5f))
        .reportCollisions(32)                      // onCollision().readback(32)
        ...
```

- Heard on the render thread: the effect's listeners, then its owner's, then `CgVfx.onCollide`'s. The collision is
  valid only during the call.
- An effect reports a contact as it starts and again when what it strikes changes, never every tick it holds. An effect
  of your own reports through `collided(...)` from its collision test.
- An effect with more than one part that strikes names each (`part()`), and an area one says how far it reaches
  (`radius()`). `CgEnergyWave` reports `PART_BEAM` as its head reaches what it hits, then `PART_BLAST` once as the
  explosion bursts, on what the beam last struck, with the blast's radius: damage the contact, the blast, or both.
- A particle's collisions arrive a few frames after the GPU step, and may arrive after its effect has died. The
  `reportCollisions` cap is per step and shared by every effect playing that emitter. A landing an emitter reports
  (`onLanding().readback(n)`) is heard as `GROUND`.
- The CPU path reports nothing; it will be deleted.

## Effects over the network: cues

The server decides what happens and each client draws it. A **cue** (`vfx.net.CgVfxCue`, server-safe) names an effect
for the network: the server sends it with a point, an aim, an entity to follow, a seed and parameters, and each client
builds the effect it was declared with. It follows Unreal's gameplay cues: a one-shot is unreliable and unseen by
late joiners, a lasting cue is replicated state.

```java
// common, at init, both sides: the cue and what it plays
public static final CgVfxCue<CgVfxParams> HIT = VFX.cue("hit_spark", HitSpark.LOOK,
        (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z()));
public static final CgVfxCue<CgVfxParams> AURA = VFX.cue("aura", Aura.LOOK,
        (look, cue) -> new Aura(look, cue.x(), cue.y(), cue.z()));

// server: a one-shot, to the players tracking the target
HIT.at(level, x, y, z).toward(dx, dy, dz).follow(target, MyMod.BODY)
        .params(CgVfxParams.of().set("level", 2f).color("ki", 0xFF40A0FF)).send();

// server: a lasting one on an entity, which a player coming into view starts at its age
CgVfxCue.Playing<CgVfxParams> aura = AURA.on(player, CgEntityAttachments.CENTRE).start();
aura.params(CgVfxParams.of().set("level", 3f));   // every viewer, at the tick's end
aura.stop();                                      // or kill(): at once

// client: shown now, and to everyone else if the server allows it
HIT.at(x, y, z).params(p).predict();
HIT.fromClients((player, play) -> withinReach(player, play));         // server, at init
```

- **Declare on both sides at init**, before anyone joins. Where what a cue plays names client-only classes, declare it
  alone in common (`VFX.cue("x")`) and say what it plays on the client (`VFX.register(cue, look, factory)`).
- **Parameters reach the look by name**: each number and colour of a `CgVfxParams` is set onto the effect's look
  parameter of that name, so a factory reads only what is not one (`cue.params().get("level", 1f)`). A cue of its own
  type takes a codec (`VFX.cue("x", MyParams.CODEC)`).
- **Every client draws the same effect**: the seed is sent (`CgVfxEffect.seeded`, `seed()`), and an effect following
  an entity resolves the attachment point from the entity as drawn there. A followed cue jumps to that point;
  `.keepOffset()` keeps it where it was played, riding the point (`CgVfxEffect.followKeepingOffset`): a hit spark at
  the contact.
- **A cue nothing registers** is dropped on that client with one warning per id.
- **`on(entity, point)`** is `at(entity, its position).follow(entity, point)`: the entity's level, a point by id
  (`CgEntityAttachments`), a typo impossible.
- **A send leaves at once**, in order with the game's packets that tick: an effect arrives with the hit that caused it.
  It reaches the players tracking what it follows, else those within the cue's `range` (64), less `except(player)`.
- **A lasting cue** ends by `stop()`, `kill()`, its `lifetime`, or the entity it follows dying; `persist()` saves it
  with the world (never with `follow`). A change (`target`, `toward`, `params`, `moveTo`) reaches viewers at the tick's
  end; an effect implementing `CgVfxCue.Live` takes it itself, as `CgEnergyWave` does, else its parameters are set.
- **A prediction is refused** unless the cue allows it (`fromClients`), within a rate (20 at once, 10 a second) and
  within `range` of the player; `refused()` counts them. The predicting client is not sent its own back.
- **Cues are cosmetic** unless declared `authoritative()` (below). What a player must not miss (damage, a state) the game
  replicates; a cue shows it.

### Deciding on the server: an authority

Where gameplay hangs on the effect itself (a beam that damages what it reaches), the server runs it too. The effect
is the same class the clients play, run headless against the server's level (`CgVfxAuthority`): it decides where it
goes and what it strikes, the server hears its collisions, and it publishes what its viewers must draw. Unreal and
Unity Netcode run one actor on both sides the same way, its cosmetic parts off on the server.

```java
// common, at init: one declaration, one factory for both sides
public static final CgVfxCue<CgVfxParams> WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(),
        CgEnergyWave::fromCue).range(96).authoritative();

// server: drive its effect as a local one; what it changes reaches every viewer
CgVfxCue.Playing<CgVfxParams> wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
CgEnergyWave beam = wave.effect();
beam.hold().aim(look.x, look.y, look.z);   // key down: before its first tick
wave.onCollide(c -> {
    if (CgEnergyWave.PART_BEAM.equals(c.part()) && c.hit() == CgVfxCollision.Hit.ENTITY) stagger(c.entity());
    if (CgEnergyWave.PART_BLAST.equals(c.part())) explode(c.x(), c.y(), c.z(), c.radius());
});
beam.aim(look.x, look.y, look.z);          // each tick, as the caster turns
beam.fire();                               // key up
beam.stop();                               // ends the cue; the blast is still heard as it bursts
```

- **The wave**: built on the server by the same factory (`Play.authoritative()` tells it apart), it collides, testing
  its aim against the level each server tick, and publishes where the beam ends as the cue's target; every client
  homes on that point instead of testing its own world, so all of them draw the beam ending where the server says.
  The entity it ends on is published with it (`Play.struck()`), so a viewer's wave reports `ENTITY` with that id
  rather than `TARGET`; so are its aim, hold and release. A cue keeps a direction and a target together: `toward` is
  where it faces, `target` where it goes.
- **Driving it**: `Playing.effect()` is the server's effect, called as on a client; stopping it ends the cue. The
  `Playing`'s own `toward`, `params` and `stop` reach it too, as they reach a cosmetic cue's viewers.
- **An effect of your own**: implement `CgVfxCue.Authoritative` (`publish(cue)`: write into the cue whatever differs
  from what it says, return whether anything did) beside `CgVfxCue.Live` (take it on the client). Test the world
  through `raycastAll`, which reads the level's queries on the server, and guard anything else that is the client's
  with `authoritative()`. A play started before the tick's end is published before its viewers are told of it, so a
  factory reads `hasTarget()` rather than waiting for a change.
- **On the server it plays nothing**: shakes, post volumes, particles and moments do nothing, so an effect that ends
  when its particles finish ends itself there. Its collisions reach only its own listeners (`Playing.onCollide`), on
  the server thread, never `CgVfx.onCollide`'s or its owner's.
- It follows its entity at the attachment point's proportions (a server has no model to mark), and plays out for
  `LINGER` (10 s) at most after its cue ends. Lasting cues only, never persisted. `start()` throws where the server has
  nothing registered to build: the cue was declared alone, with its factory on the client.

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
| an impact that fires `onCollision` | after the solver; calls `fx_hit(p, n, FX_HIT_BLOCK)` (what was struck: `fx_types.glsl`'s `FX_HIT_*`; an entity's sets `p.hitEntity` too) on a punctual impact | — |
| the entities round the camera | `worldInputs()` of `ENTITIES` | `FxEntities`: `fx_entities_at.glsl`'s `fx_entities_enter` |

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
