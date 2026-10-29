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
CgMaterialDefinition sparks = CgMaterialDefinition.of("mymod:materials/fx/sparks",
        "crystalgraphics:shaders/vfx/particle/sprite.shader").blend(CgBlendMode.ADDITIVE).build();
CgVfxEmitter emitter = CgVfxEmitter.builder("sparks")
        .renderer(CgVfxRenderers.sprite("r1").material(sparks).alignment("velocity")
                .bind("_Color", "user.ember").value("_SoftDistance", 0.25).sortOrderHint(1).build())
        .build();
```

| Kind | Draws | Its emitter |
|---|---|---|
| `sprite` | a quad a particle: `alignment` (`unaligned`, `velocity`), `facing` (`camera`, `camera_plane`, `camera_position`), `sort` (`none`, `view_depth`), `sub_image` (a grid flipbook) | quads |
| `mesh` | one mesh a particle (`meshes: [{ mesh: <path>, rotation, scale }]`), the unit sphere with none; `facing`, `locked_axis`, `facing_up`, `roll_about`, `pre_rotation` ([below](#mesh-particles-that-face-something)); or a prefab a particle (`meshes: { prefab: <id> }`), its parts at rest, turned by the particle's mesh orientation and sized by its size and scale | `MESHES`; a prefab's, a clip |
| `ribbon` | one strip through an instance's particles, newest first; with a `trail`, Cascade's trail ([below](#ribbons-and-trails-strips-through-particles)) | quads |
| `anim_trail` | a sheet swept by an edge between two points the effect moves ([below](#cascades-anim-trail)) | quads |
| `arc` | a stroke round the source | `ARCS` |
| `sequence` | a clip's parts at each particle, drawn by the clip's prefab | a clip |

- **Binds read user parameters alone** (`user.<name>`, or `{ bind: user.<name>, scale: s }`): a row a draw, so a hundred
  effects of one emitter stay one draw. A particle's own attributes are read in its material
  (`cg_dynamic_parameter`, `CG_PARTICLE_COLOR`), so `particles.*` fails at load.
- **Values on a property the material reads per draw** are constants of the row; **keywords and other values** make a
  child material, shared by every renderer with the same overrides.
- **What is not drawn yet fails at load by name**, rather than drawing as something else: the `system` kind, a
  material named by a user parameter, and fields such as a `camera_distance_blend` facing, a pivot off the middle, a
  rect-table `sub_image`, a mesh's `pivot_offset`, a prefab with any other mesh field (`facing`, `sort`, overrides, a
  material of its own), a ribbon's `shape: custom` or its `max_ribbons`.
- **Its renderers decide its draw layout**: a mesh renderer lays its particles out as meshes (a prefab's and a
  `sequence` renderer's as a clip), an arc renderer as arcs, every other kind as quads, and an emitter whose renderers
  need two layouts fails at build.
- **`max_draw_count: n` draws the first `n` of its particles in draw order and simulates them all** (Cascade's
  `MaxDrawCount`); 0 draws none. They are counted before culling, so the ones off screen still use up the count. One
  emitter's particles draw from one list, so its renderers name one count or none, and two different counts fail at
  build. Cascade's GPU emitters draw every particle, so an emitter spawning as `cascade_gpu` with a count fails at
  build too.

  ```yaml
  renderers:
    - { id: r1, kind: sprite, material: mymod:materials/spark, max_draw_count: 500 }
  ```

  ```java
  CgVfxRenderers.sprite("r1").material(SPARK).maxDrawCount(500).build();
  ```
### Mesh particles that face something

A mesh renderer turns each mesh toward the camera, along its velocity, or about a locked axis, as Niagara's mesh
renderer does, with three of Cascade's options Niagara lacks:

```yaml
- { id: r1, kind: mesh, meshes: [{ mesh: mymod:models/ring.glb }], facing: camera_position }      # x at the eye, z up
- { id: r2, kind: mesh, meshes: [{ mesh: mymod:models/shard.glb }], facing: velocity, facing_up: camera, roll_about: camera }
- { id: r3, kind: mesh, meshes: [{ mesh: mymod:models/disc.glb }], facing: camera_position, facing_up: none, roll_about: facing,
    pre_rotation: { range: [[0, 0, 0], [0, 0, 6.2832]], per_channel: true } }
```

```java
CgVfxRenderers.mesh("r1").material(RING).field("meshes", List.of(Map.of("mesh", "mymod:models/ring.glb")))
        .field("facing", "camera_position").field("roll_about", "facing").build();
```

| Field | Means |
|---|---|
| `facing` | `default` (the particle's own turn), `velocity`, `camera_position`, `camera_plane`: where the mesh's x axis points |
| `locked_axis` | `{ axis, space }`: z held on the axis, x as near the facing as it can turn (`space: local` turns it with its effect) |
| `facing_up` | absent, Niagara's up (world up; the camera's for `camera_plane`); `world`; `camera` (z turned toward the camera); `none` (x turned there by the shortest arc) |
| `roll_about` | the particle's sprite rotation rolls the mesh about the direction it faces (`facing`) or the one to the camera (`camera`) |
| `pre_rotation` | a rotation, or a range rolled per particle, applied to the mesh before anything else, before its scale |
| `meshes[].rotation`, `scale` | the mesh's own turn and size, inside the particle's |

- **A facing mesh ignores the particle's tumble**: only a mesh orientation module turns it besides its facing.
- **A locked axis turns only a facing mesh**; under `default` it does nothing, as in Niagara.
- **All of an emitter's mesh renderers must face alike**: its particles are placed once for all of them, and renderers
  that differ fail at load.

### A mesh's turn at birth

A spawn module turns each mesh particle as it is born, Niagara's Initial Mesh Orientation, by one of three modes:

```yaml
particle_spawn:
  - { id: t1, module: crystalgraphics:initial_mesh_orientation, inputs: { mode: random } }               # any way at all
  - { id: t2, module: crystalgraphics:initial_mesh_orientation,
      inputs: { mode: none, rotation: { range: [[0, 0, 0], [0, 0, 6.28319]], per_channel: true } } }   # a roll of yaw
  - { id: t3, module: crystalgraphics:initial_mesh_orientation,
      inputs: { mode: orient_to_vector, orientation_vector: { bind: particles.velocity }, mesh_axis: [1, 0, 0] } }
```

```java
float turn = (float) (2 * Math.PI);
int[] perChannel = {0, 1, 2};
// random: every axis rolled over a whole turn
new CgVfxMeshRotation(new float[3], new float[] {turn, turn, turn}, perChannel, new float[3], new float[3], new int[3], false);
// toward its velocity at birth: Unreal's +X along it
new CgVfxMeshRotation(new float[3], new float[3], new int[3], new float[3], new float[3], new int[3], false, List.of(),
        new float[] {1f, 0f, 0f});
```

| Mode | Turns it by |
|---|---|
| `none` | its `rotation`: a rotator (roll, pitch, yaw) or a range of them |
| `random` | every axis rolled over a whole turn; its `rotation` is unread |
| `orient_to_vector` | its `rotation`, plus the turn that brings `mesh_axis` (+X unless given) onto `orientation_vector` |

- **`orientation_vector` is a constant or `{ bind: particles.velocity }`**, the velocity it is born with. Any other
  bind fails at load, by name.
- **Toward its velocity, its `rotation` is a constant or a range**: one stored per particle in another form fails at
  load.
- A particle born at rest has no velocity to turn toward and keeps its `rotation` alone.

- **A ribbon's tessellation** is Niagara's: `{ mode: disabled }` one quad between two particles, `{ mode: custom,
  factor: n }` n of them, `automatic` (or none) the engine's own.
- **Order** is the hint, then the look's order; a renderer whose material has a Distortion pass bends what draws before
  it, and every other draws after its effect's own bend.

## Ribbons and trails: strips through particles

A `ribbon` renderer draws a strip through an emitter's particles. Without a `trail` it is Niagara's ribbon: one strip
an instance through its particles, newest first, cut finer where it bends. With a `trail` it is Cascade's ribbon trail:
the emitter spawns its particles along its source's path and the strip runs through them. An `anim_trail` renderer is
Cascade's anim trail, a sheet swept by an edge between two points the effect moves: a sword's hilt and tip.

### Niagara's ribbon

```yaml
renderers:
  - { id: r1, kind: ribbon, material: mymod:materials/stream }                       # through every particle, by age
  - { id: r2, kind: ribbon, material: mymod:materials/stream, shape: tube, width_segments: 2,
      uv0: { distribution: tiled_from_start_over_ribbon_length, tiling_length: 2.0 },
      tessellation: { mode: custom, factor: 8, tension: 0.3 } }
```

```java
CgVfxRenderers.ribbon("r1").material(STREAM).build();
CgVfxRenderers.ribbon("r2").material(STREAM).field("shape", "tube")
        .field("uv0", Map.of("distribution", "tiled_from_start_over_ribbon_length", "tiling_length", 2.0)).build();
```

| Field | Means |
|---|---|
| `shape` | `plane`, `multi_plane` (two crossed), `tube` (three sides) |
| `width_segments` | cuts across each plane |
| `uv0`, `uv1` | `distribution` (`scaled_uniformly`, `scaled_using_ribbon_segment_length`, `tiled_over_ribbon_length`, `tiled_from_start_over_ribbon_length`), `leading_edge` and `trailing_edge` (`locked`, `smooth_transition`), `tiling_length`, `scale`, `offset` |
| `tessellation` | `mode` (`automatic`, `custom` with `factor` and `angle`, `disabled`), `tension` |
| `link_order_by_id`, `max_link_gap` | link by spawn, newest first, breaking where two are more than the gap apart |

### Cascade's ribbon trail

An emitter with a `trail` spawns as Cascade's trail emitter does: a particle each `unit` its source moves
(`spawn_per_unit`), laid on a curve from where the source was to where it is, the trail's width each particle's size
either side.

```yaml
emitter:
  emitter_update:
    - { id: u1, module: crystalgraphics:spawn_per_unit, inputs: { per_unit: 1, unit: 0.25 } }
renderers:
  - { id: r1, kind: ribbon, material: mymod:materials/streak,
      trail: { max_particles: 40, render_axis: camera_up, tessellation: { distance: 0.1 }, tiling_distance: 2.0 } }
```

```java
CgVfxRibbonRendererProperties streak = (CgVfxRibbonRendererProperties) CgVfxRenderers.ribbon("r1").material(STREAK)
        .field("trail", Map.of("max_particles", 40, "tessellation", Map.of("distance", 0.1))).build();
CgVfxEmitter.builder("streak").ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.25f)).renderer(streak).build();
```

| `trail` field | Means |
|---|---|
| `max_trails`, `max_particles` | trails an emitter keeps, particles a trail keeps (its oldest go first) |
| `render_axis` | `camera_up` (the strip faces the eye), `source_up` (the effect's up), `world_up` |
| `tessellation` | `distance` (a point each this much), `tangent_scale`, `max`, `tangent_interp` |
| `tangents` | `recalculate_previous` (the chain before is laid again beside the next), `every_frame` |
| `tiling_distance` | the second UV tiles once each this many blocks |

### Trails behind another emitter's particles

A `ribbon_source` with `method: particle` gives each trail one of another emitter's particles to follow, picked at
random, and another once it dies: smoke behind each flying rock.

```yaml
emitters:
  - id: rocks
    emitter: { ... }
  - id: smoke
    emitter:
      emitter_update:
        - { id: u1, module: crystalgraphics:spawn_per_unit, inputs: { per_unit: 1, unit: 0.5 } }
      particle_spawn:
        - { id: s1, module: crystalgraphics:ribbon_source, inputs: { method: particle, name: rocks } }
    renderers:
      - { id: r1, kind: ribbon, material: mymod:materials/smoke, trail: { max_trails: 8, max_particles: 30 } }
```

```java
CgVfxEmitter rocks = CgVfxEmitter.builder("rocks").publishes(true).rate(4f).speed(6f, 9f).build();
CgVfxEmitter smoke = CgVfxEmitter.builder("smoke").ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.5f))
        .spawnFrom(CgVfxSpawnFrom.trailing("rocks", CgVfxSpawnFrom.Selection.RANDOM)).renderer(smokeTrail).build();
```

A trail behind every particle is `selection: sequential`, `max_trails` as many as the source keeps alive: each trail
takes the next particle no other trail follows, as Cascade's sequential selection does.

```yaml
- { id: s1, module: crystalgraphics:ribbon_source, inputs: { method: particle, name: sparks, selection: sequential } }
```

```java
CgVfxEmitter.builder("streaks").ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.08f)).life(0.15f, 0.15f).capacity(256 * 24)
        .spawnFrom(CgVfxSpawnFrom.trailing("sparks", CgVfxSpawnFrom.Selection.SEQUENTIAL))
        .renderer(CgVfxRenderers.ribbon("r1").material(GLOW)
                .field("trail", Map.of("max_trails", 256, "max_particles", 24)).build()).build();
```

- **The source is named as Cascade names it**: by an emitter's name, or by its handle.
- **It spawns by its sources' moves alone**: a rate or a burst on it fails at load.
- **Its trails are worked out on the GPU**, where the particles they follow are. A trail whose particle dies starts
  again on another.
- **`random` may set two trails on one particle**, as Cascade's does; `sequential` never does, and its source may keep
  at most 4,096 particles. An emitter keeps at most 1,024 trails.
- **Thousands of fast sparks** are cheaper as Niagara draws them: a sprite stretched along its velocity
  (`alignment: velocity`), or an `arc` renderer's `ray`, rather than a trail each.

### Cascade's anim trail

```yaml
user:
  hilt: { type: position, default: [0, 0, 0] }
  tip: { type: position, default: [0, 1, 0] }
emitters:
  - id: blade
    renderers:
      - { id: r1, kind: anim_trail, material: mymod:materials/swoosh, edge: [user.hilt, user.tip],
          tessellation: { distance: 0.1, angle: 10 }, width: { curve: { keys: [[0, 1], [1, 0]] }, by: length } }
```

```java
CgVfxRenderers.animTrail("r1", "hilt", "tip").material(SWOOSH)
        .field("tessellation", Map.of("distance", 0.1, "angle", 10.0)).build();
// each tick, from the effect's origin:
effect.values().set(HILT, hiltX, hiltY, hiltZ);
effect.values().set(TIP, tipX, tipY, tipZ);
```

| Field | Means |
|---|---|
| `edge` | the two `position` user parameters it samples, once a tick |
| `width_mode` | `centre`, `first`, `second`: where the edge lies about its two points |
| `tessellation` | `distance`, `angle` (degrees), `width`: a point more each step of each; `max` points a segment |
| `width`, `colour` | a value, or up to four keys `by: length`, newest 0 to oldest 1 |
| `life` | samples it keeps, 0 for as long as each lives |
| `stop` | `dead_on_deactivate`, or `{ retract: n, fade: { alpha, width } }`: stopped, it loses n samples a tick while it keeps sampling, fading |

### The trail looks

`shaders/vfx/trail/` holds ribbon materials for every kind of strip above. Each reads its life from its particles, across
from V, and blocks along from UV1, so its renderer tiles UV1 once a block: a Cascade trail's `tiling_distance: 1`, an
anim trail's `tiling_distance: 1`, a Niagara ribbon's `uv1: { distribution: tiled_over_ribbon_length, tiling_length: 1 }`.
Its emitter's size over life tapers it.

| Shader | Draws |
|---|---|
| `glow` | a ki streak: a white-hot core (`_Core`) to its colour (`_Color`) at soft edges, energy flowing back, its tail eaten away; `_Wobble` shakes it into lightning |
| `bands` | anime cel bands (`_Bands`: core, middle, edge), each narrowing toward the tail, which breaks into brushed strands; `_Ink` an outline |
| `swoosh` | an anim trail's sheet from base to tip: a hot leading edge (`_Hot`), streaks along the swing, faded edge-on |
| `haze` | the air bent behind it: a Distortion pass only |
| `smoke` | lit smoke billowing wider and drifting up as it ages; `_Glow` a fire's hot head |
| `scorch` | burnt ground with embers cooling in its cracks: an anim trail along the ground, its edge either side of the path |

`lib/vfx/fx_strip.glsl` has what they read (`fx_strip`) and the glow they share (`fx_strip_glow`), for a look of your own.

### Easy to get wrong

- **A trail samples its edge or source as each tick ends**: a thing moved between ticks shows only where a tick finds it.
- **An edge's points are from the effect's origin**, as every point user parameter is.
- **A Cascade trail's width is its particles' size either side**, twice a Niagara ribbon's of the same size.
- **An anim trail's width or colour keeps four keys at most**; more fail at load.

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
        .renderer(CgVfxRenderers.mesh("core").material(CORE_MATERIAL).build())
        .capacity(4).burst(0f, 1).life(2.9f, 2.9f).build();

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

In a file, a rate can also follow the effect's own parameter, or be drawn each step between two curves:

```yaml
emitter_update:
  - id: rate
    module: crystalgraphics:spawn_rate
    inputs:
      rate: { bind: user.SpawnOff, default: 20, mode: direct }   # the effect's SpawnOff, read each step
      scale: { fn: crystalgraphics:random_range, inputs: { min: { curve: <falling>, by: loop }, max: { curve: <rising>, by: loop } } }
  - id: trail
    module: crystalgraphics:spawn_per_unit
    inputs: { per_unit: { curve: { samples: { range: [0, 0.5], values: [1, 0] } }, by: loop } }   # fewer a block as the loop runs
```

- A bound rate with no `remap` has no most: the emitter's `capacity` holds it.
- A rate drawn between two ends and a scale drawn between two curves are drawn apart, each step.

### Easy to get wrong

- **A burst emitter without `state(...)` re-fires every loop.** Give a one-off `CgVfxEmitterState.ONCE`.
- **A delay delays everything in the loop**, its bursts too: a burst at 0 fires after the delay.
- **Every roll comes from the instance's seed**: a delay, a burst's count, a probability. Every client playing the
  same instance spawns the same.
- **Each roll is keyed by its element's `id` and the input's name** (`core.md` § Values), never by its place: a burst's
  count by that burst's `id`, a lifetime by its `initialize_particle` entry's. Reordering a file's entries changes
  nothing drawn; renaming one changes that element's rolls alone. An emitter made in code takes the writer's names
  (`init`, `shape`, `velocity`, `state`, `burst_<n>`).
- **An effect's seed is drawn from the engine's random stream** unless `CgVfxEffect.seeded(...)` or a cue gave it one.
  A capture that must repeat run to run turns on `-Dcrystalgraphics.fixedSeed=true` (or `CgRandom.fixedSeed(true)` at
  startup; the harness does), which seeds that stream from a constant, as Unreal's `-FixedSeed` does.
- **A loop moves on at most once a step**, as Unreal steps an emitter's loop once a tick: a loop shorter than a step
  lasts a step, and a loop of no length (a duration rolled at 0) never ends, its bursts fired once.
- **`sweep(speed)` needs a sphere, shell, cylinder or ring and a loop of finite length**: its front runs over each
  loop, the radius as a curve by the loop's seconds, its longest loop's length.

### An emitter from Unreal 4: Cascade's spawning and emitter time

An emitter imported from Cascade keeps how its game spawned and looped.

```yaml
properties: { capacity: 500, spawning: cascade_legacy, legacy_emitter_time: true }
```

```java
CgVfxEmitter.builder("sparks").capacity(500).spawning(CgVfxSpawning.CASCADE_LEGACY).legacyEmitterTime()...
```

- **`spawning`** is how a tick's new particles are timed and placed along the emitter's move:
  - absent (`CgVfxSpawning.INTERPOLATED`): bursts are born at the tick's start where the source was; rate spawns are
    spread across the tick and along the source's move, each stepped with the forces for the rest of the tick;
  - `cascade_legacy`, Cascade's `bUseLegacySpawningBehavior`:
    - every rate spawn of a tick is born at the tick's shared start time and placed that far along its velocity;
    - a burst is placed by its share of the tick;
    - spawn `i` of `n` is placed `(i + 1) / n` along the source's move, once it moved past a centimetre;
    - in the tick it spawns, each is moved by its velocity once more;
  - `cascade_gpu`, Cascade's GPU sprites: as absent, except that burst spawn `i` of `n` is placed `i / n` along the
    move, the first where the source was. A Cascade GPU emitter spawns this way whatever its legacy flag says.
- **`legacy_emitter_time`** is Cascade's `bUseLegacyEmitterTime`: a loop's seconds are the instance's age modulo its
  duration plus its delay. Without it, the loop's age carries its remainder over each loop (Niagara's looped age). The
  two differ only where a duration or delay is rolled again each loop, or where only the first loop waits its delay:
  Cascade's period still holds that delay.

## How each particle looks: colour and size per axis

An emitter gives each particle a colour and a size on each axis, which **multiply what its renderers draw**, as
Niagara's Initialize Particle and its Scale modules do. A renderer's bound `_Color` stays the look's palette; the
emitter's colour tints it per particle and over its life.

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
static final CgVfxEmitter STREAK = CgVfxEmitter.builder("streak")
        .renderer(CgVfxRenderers.mesh("streak").material(STREAK_MATERIAL).build())
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

### Size by speed

Cascade's Size Scale By Speed stretches a particle as it moves faster: x and y each times
`clamp(speed * scale, 1, max)`, speed in blocks a second. A size over life after it multiplies it; one before it is
dropped, as Cascade's sets the size outright.

```java
CgVfxEmitter.builder("streaks").sizeBySpeed(0f, 0.5f, 1f, 3f)   // up to three times as tall, from 2 blocks a second
```

```yaml
- { module: crystalgraphics:scale_size_by_speed, inputs: { speed_scale: [0, 0.5], max_scale: [1, 3] } }
```

## Values a particle carries for its material

A module can give each particle values only its draws read, as Niagara's DynamicMaterialParameter, SubImageIndex and
CameraOffset attributes are: four dynamic parameters a material reads with `cg_dynamic_parameter`, the frame of its
sheet, and how far toward the eye it draws. Each channel is a value over life, or a range rolled once at birth or again
every step.

```java
static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers")
        // dynamic parameter 0: x fades in over life, y each ember's own between 0.1 and 0.2
        .drawn(CgVfxDrawnChannel.DYNAMIC_0, 0, CgVfxDrawnChannel.over(
                CgKeyframes.start(0f, 0f).to(1f, 1f, CgEasings.LINEAR).build()))
        .drawn(CgVfxDrawnChannel.DYNAMIC_0, 1, CgVfxDrawnChannel.spawn(0.1f, 0.2f))
        // frame 0 to 15 of its sprite's sheet over life; the renderer's sub_image is { size: [4, 4], mode: direct }
        .drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.SUB_IMAGE, CgVfxDrawnChannel.over(
                CgKeyframes.start(0f, 0f).to(1f, 15f, CgEasings.LINEAR).build()))
        .build();
```

```yaml
particle_update:
  - module: crystalgraphics:dynamic_material_parameters
    inputs:
      dynamic_0:
        - { value: { curve: { keys: [[0, 0, linear], [1, 1, linear]] }, by: age } }
        - { value: { range: [0.1, 0.2] }, spawn_only: true }
        - { value: 0 }
        - { value: 0 }
  - { module: crystalgraphics:sub_uv_animation, inputs: { mode: direct, frame: { curve: { keys: [[0, 0, linear], [1, 15, linear]] }, by: age } } }
  - { module: crystalgraphics:camera_offset, inputs: { offset: -0.2 } }
```

| Module | Sets | Read by |
|---|---|---|
| `dynamic_material_parameters` | `dynamic_0` to `dynamic_3`, four channels each | `cg_dynamic_parameter(p, i, fallback)` in the material |
| `sub_uv_animation`, `mode: direct` | the frame of its renderer's `sub_image` | the sprite factory, which truncates it unless the sheet blends |
| `sub_uv_animation`, `mode: loop` | its renderer's flipbook loop at `frame_rate`, each particle its own start at `start_frame: 0` | the sprite factory |
| `camera_offset` | blocks toward the eye, sprites and meshes alike | the factories |

- **A range rolls every step unless `spawn_only`**, as Cascade's distributions roll on every update: a flicker, not a
  value per particle. `spawn_only` holds a curve at its value at birth.
- **A constant camera offset or one bound to a user parameter is its renderers'** (`_cg_CameraOffset`); only one that
  differs per particle is carried by each.
- **Each slot carried costs the pool a second drawn element a particle**, and only emitters carrying the same slots
  share a pool.

## Moving slower than it is pushed: velocity scale

Cascade's Velocity Over Lifetime scales how fast a particle moves while its velocity keeps what forces give it, so a
particle slowed to a crawl by a curve speeds up again as the curve rises; `absolute` moves it at the vector itself.

```java
.module(new CgVfxModule.ScaleVelocity(0.5f, 0.5f, 0.5f, false))
```

```yaml
- { module: crystalgraphics:scale_velocity, inputs: { scale: { curves: [...], by: age } } }
```

- Collisions, alignment and stretch read the velocity, not the scaled motion.

## Drawn off its place: orbit

`orbit` draws each particle away from where it is: an offset, turned by a rotation plus a rotation rate times its age.
The particle itself stays put, so collisions and forces see its place; only the draw moves. All three are taken at
birth, in any form.

```java
CgVfxEmitter.builder("halo").rate(40f).life(2f, 2f)
        .orbit(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, (float) Math.PI)   // half a metre out, half a turn a second round up
        .build();
```

```yaml
particle_update:
  - id: o1
    module: crystalgraphics:orbit
    inputs:
      offset: { range: [[0, 0, 40], [0, 0, 60]], per_channel: true }   # centimetres, Unreal's axes
      rotation: [0, 0, 0]
      rotation_rate: { value: [0, 0, 0.5], unit: turns }               # half a turn a second
```

- **Rotations are Unreal's roll, pitch and yaw**, about its x, y and z, as Cascade turns its orbit.
- **One orbit an emitter.** A second, a `scale` chain, or options taking its values during update fail at load.
- **A particle a collision freezes** cannot also orbit yet: Cascade holds a frozen orbit still, which is not built.

## What a collision does: bounce, damping, a count and its end

A collider (`collision`, against the world, the screen's depth, entities or a shape) bounces what strikes it. Its
response is Cascade's when it says so: a damping per axis instead of the bounce, a delay before it collides, a number of
collisions and what the last one does.

```java
// sparks: halved sideways, a quarter upward at each bounce, lying where the third lands
.module(new CgVfxModule.CollideWorld(0f, 0f, 0f).response(CgVfxModule.CollisionResponse.NONE
        .damping(0.5f, 0.25f, 0.5f)
        .limit(3, CgVfxModule.CollisionCompletion.FREEZE)))

// debris: its own damping each, colliding after a fifth of its life, gone after one to three bounces
.module(new CgVfxModule.CollideWorld(0f, 0f, 0f).response(CgVfxModule.CollisionResponse.NONE
        .damping(0.3f, 0.1f, 0.3f, 0.6f, 0.4f, 0.6f, false)
        .delay(0.2f).rotation(0.5f, 0.5f, 0.5f)
        .limit(1f, 3f, CgVfxModule.CollisionCompletion.KILL)))
```

```yaml
- module: crystalgraphics:collision
  inputs:
    against: world
    damping: { range: [[0.3, 0.3, 0.1], [0.6, 0.6, 0.4]] }   # the asset's axes: z up here
    delay: 0.2
    rotation_restitution: [0.5, 0.5, 0.5]
    max_collisions: { range: [1, 3] }
    on_complete: kill
```

| Field | Means |
|---|---|
| `damping` | the whole velocity mirrored about the surface and scaled per axis, rolled per particle between a range's ends (one roll for all axes, or per axis with `per_channel`, `locked` sharing them); set, `restitution` and `friction` are unread |
| `restitution`, `friction` | without damping: the speed into the surface bounced back at `restitution`, the slide kept less `friction` |
| `delay` | the share of its life, 0 to 1, before a particle collides |
| `rotation_restitution` | each bounce scales its rotation rates: a sprite's spin by x, a mesh's turn per axis; a range rolls per particle as `damping`'s does |
| `max_collisions` | contacts until `on_complete`, rolled and rounded per particle; absent, it bounces for ever |
| `on_complete` | `kill`; `freeze` (still and unturned where it struck); `halt` (carries on, colliding no more); `freeze_translation`, `freeze_rotation`, `freeze_movement` |
| `only_vertical_counts`, `vertical_fudge` | only a hit whose normal's vertical plus the fudge reaches 1 spends the count; the others still bounce and fire events |
| `radius_scale` | a world collider tests the particle's size over this ahead of it along its motion (3.5) |

- **With a count, every contact counts**, sliding included, and each fires a collision event; the one that spends the
  count runs `on_complete` instead of bouncing.
- **Every end stops its collisions**: a frozen or halted particle passes through what it meets after.
- **A count below 1 is 1.**
- **A frozen particle still ages and dies**, and its colour keeps playing over its life.
- **Damping or freezing the turn of a mesh whose rotation rate changes over life** fails at load: not built yet.

## Events: one emitter's particles spawning another's

An event fires where a particle is born, dies, collides or each tick, and spawns another emitter's particles there.
In Java, put the event on the emitter that fires it:

```java
CgVfxEmitter chips = CgVfxEmitter.builder("chips").state(CgVfxEmitterState.ONCE).life(0.4f, 0.8f).build();
CgVfxEmitter debris = CgVfxEmitter.builder("debris").rate(20f)
        .module(new CgVfxModule.CollideWorld(0.3f, 0.4f, 0.1f))
        .event(CgVfxEvent.onCollision().spawn(chips, 3, 5).inherit(0.3f))   // 3 to 5 chips at each hit
        .build();
```

In a system file, a handler sits on an emitter and names the handle that fires and the one that spawns:

```yaml
emitters:
  - id: h1                       # debris
    emitter: { ... }
  - id: h2                       # chips
    emitter:
      events:
        - { id: e1, trigger: collision, source: h1, spawn: { emitter: h2, count: { range: [3, 5] }, inherit_velocity: [0.3, 0.3, 0.3] } }
```

Or by name, as Cascade's receivers work: the firing emitter raises a named event with `generate_event`, and a handler
with that name and no `source` takes it from every handle that raises it. With no `spawn.emitter` it spawns its own
emitter's particles.

```yaml
emitters:
  - id: h1                       # rocks: raise "shatter" as each one dies
    emitter:
      particle_update:
        - { id: g, module: crystalgraphics:generate_event, inputs: { type: death, name: shatter } }
  - id: h2                       # shards: spawn 5 to 15 wherever a rock shatters
    emitter:
      events:
        - { id: e1, trigger: death, name: shatter, spawn: { count: { range: [5, 15] } } }
```

- **Names match exactly.** A handler named `shatter` ignores unnamed events, and a name nothing raises spawns nothing.
- **A handler with a `source` but no name** takes that handle's events of its trigger, raised or not.
- **`first_time_only`** on a collision `generate_event` fires once a particle, at its first contact.
- **A spawn that comes back to its own source** fails at load, naming the loop.

### A count that changes over the loop

A count may be a curve by the loop's seconds, as Cascade reads its receiver's count at the emitter's time: rounded to
the nearest, and read once a frame for each playing instance.

```yaml
events:
  - { id: e1, trigger: collision, source: h1, spawn: { count: { curve: { keys: [[0, 4], [1, 0, linear]] }, by: loop } } }
```

```java
.event(CgVfxEvent.onCollision().spawnOver(CHIPS, CgKeyframes.start(0f, 4f).to(1f, 0f, CgEasings.LINEAR).build()))
```

- Every firing of one frame spawns the same count; a firing past the curve's last key reads its last value.

### Killing the receiver's particles

`kill: true` ends every particle of the handler's own emitter when its source raises the event, and spawns nothing:
Cascade's Kill Particles receiver. A light that goes out when the dummy it marks dies:

```yaml
emitters:
  - id: h1                       # the dummy: raise "gone" as it dies
    emitter:
      particle_update:
        - { id: g, module: crystalgraphics:generate_event, inputs: { type: death, name: gone } }
  - id: h2                       # the light
    emitter:
      events:
        - { id: e1, trigger: death, name: gone, kill: true, stop_spawning: true }
```

```java
// in Java: the definition names it, the source's event reading its rows back
new CgVfxSystemDefinition("mymod:flash", look, null, List.of(dummy, light), 0, 32,
        List.of(new CgVfxSystemDefinition.Kill(0, 0, 1)));
light.killParticles();   // or by hand, on a playing instance
```

- **It lands a frame or two late**: the source's event reaches the CPU from the GPU's rows, then the receiver's next
  step ends its particles.
- **The receiver keeps spawning** unless `stop_spawning` stops it, Cascade's Stop Spawning (`Kill(..., true)` in
  Java). Its events' children live on in their own slots.

## Born on another emitter's particles

An emitter can take its new particles' places from another emitter's live particles, as Cascade's Emitter Initial
Location does: sparks shed by flying rocks, embers along a ribbon of fire. Each new particle is born where one of the
source's particles is this step, picked at random or in turn, and may take a share of its velocity and spin.

```yaml
emitters:
  - id: rocks
    emitter: { ... }
  - id: sparks
    emitter:
      particle_spawn:
        - { id: s1, module: crystalgraphics:spawn_from_emitter, inputs: { source: rocks, selection: random, inherit_velocity: 0.5 } }
```

In Java the reader names its source's emitter, and the source is built to publish its particles:

```java
CgVfxEmitter rocks = CgVfxEmitter.builder("rocks").rate(8f).speed(4f, 6f).publishes(true).build();
CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").rate(120f).life(0.3f, 0.5f)
        .spawnFrom(CgVfxSpawnFrom.of("rocks", CgVfxSpawnFrom.Selection.RANDOM).inherit(0.5f, 0f))
        .build();
CgVfxLook look = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(rocks).emitter(sparks).build();
```

| Input | Means |
|---|---|
| `source` | the handle, or in Java the emitter's name, of the same system |
| `selection` | `random`, or `sequential`: each new particle the next of the source's in turn |
| `inherit_velocity`, `inherit_rotation` | the share of the source particle's velocity and spin it takes |
| `follow` | `true`: each particle stays on its source particle every step ([below](#a-particle-that-follows-another)) |

- **The place replaces its shape's**, unless a `shape_location` comes after it in the stack: then the shape's offset
  is added to it.
- **A source with no live particle** leaves each new one where its own shape put it.
- **`sequential` walks the source's particles in the order the GPU wrote them**, which changes as they die, so which
  one comes next is not fixed from run to run.
### A particle that follows another

A follower stays on its source particle for its whole life, as Cascade's Emitter Direct Location does: a glow riding a
dummy point, a flash on each bolt. Its first particle takes the source's first, its second the second, and every step it
copies that particle's place, velocity, age and life. So its curves over life run on the source's clock, and it dies
with it.

```yaml
- { id: s1, module: crystalgraphics:spawn_from_emitter, inputs: { source: core, follow: true } }
```

```java
CgVfxEmitter glow = CgVfxEmitter.builder("glow").burst(0f, 1)
        .spawnFrom(CgVfxSpawnFrom.following("core")).build();
```

- **It takes the whole of the source's velocity**: `inherit_velocity` or `inherit_rotation` with `follow` fails at load,
  and `selection` means nothing.
- **Its own lifetime is overridden** while its source lives. A follower whose source is gone keeps its own state and
  ages on from there.
- **Match their spawns**: follower n is source particle n by spawn. A follower spawned past the source's count has
  nothing to follow.

- **A reader steps after its source**, so a chain of readers is at most two deep. A source that is no emitter of the
  system, the reader itself, a deeper chain or a loop fails at load, naming it. In Java, a source not built with
  `publishes(true)` fails the same way.
- `follow` (a particle that keeps moving with its source) is refused at load.

## Beams: a strip from a source to a target

A beam emitter draws each particle as a strip from its source to its target: lightning, a tether, a laser. Its
particles are placed by four modules (Cascade's beam modules) and drawn by a `beam` renderer (Cascade's TypeDataBeam2).
A beam's ends are its modules', never its motion.

```java
static final CgMaterialDefinition BOLT = CgMaterialDefinition.of("mymod:materials/bolt",
        "mymod:shaders/lightning.shader").usage(CgMaterialUsage.RIBBONS).build();

static final CgVfxEmitter LIGHTNING = CgVfxEmitter.builder("lightning").capacity(6).rate(20f).life(0.15f, 0.25f)
        .size(0.08f, 0.12f)
        .module(new CgVfxModule.BeamSource(0f, 0f, 0f, false, 1f, 0f, 0f,
                CgVfxModule.BeamSource.Tangent.DIRECT, 0.25f, true, true, true))       // from the effect's origin
        .module(new CgVfxModule.BeamTarget(8f, 0f, 0f, false, -1f, 0f, 0f,
                CgVfxModule.BeamSource.Tangent.USER_SET, 0.25f, true, true, true, 0.1f, 0f))   // 8 blocks along +X
        .module(new CgVfxModule.BeamNoise(12, 4, 0.06f, false, -0.4f, -0.4f, -0.1f, 0.4f, 0.4f, 0.1f, true, false,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 2, 1f, 1f, 0f, 0f, 0f, 1f, false, 2))       // jagged, re-picked 16 times a second
        .renderer(CgVfxRenderers.beam("bolt").material(BOLT).field("sheets", 2).field("max_beam_count", 6).build())
        .build();
```

```yaml
# the same emitter as a file
properties: { capacity: 6 }
particle_spawn:
  - { id: source, module: crystalgraphics:beam_source, inputs: { tangent_method: direct, strength: 25, lock: true, lock_tangent: true } }
  - { id: target, module: crystalgraphics:beam_target,
      inputs: { point: [800, 0, 0], tangent: [-1, 0, 0], tangent_method: user_set, strength: 25, lock: true, lock_radius: 10 } }
particle_update:
  - { id: noise, module: crystalgraphics:beam_noise, inputs: { enabled_low_frequency: true, frequency: 12, frequency_low: 4,
      range: { range: [[-40, -40, -10], [40, 40, 10]], per_channel: true }, lock_time: 0.06, tessellation: 2,
      speed: [0, 0, 0], lock_radius: 0, range_scale: 1, tangent_strength: 0 } }
renderers:
  - { id: bolt, kind: beam, material: mymod:materials/bolt, sheets: 2, max_beam_count: 6 }
```

| Module | Does |
|---|---|
| `beam_source`, `beam_target` | where the beam starts and ends, from the effect's origin and turned with it (or `absolute` in the world); each end's tangent, the effect's forward (`direct`) or `tangent`, and its strength; each part resolved at birth and again each step unless `lock`ed |
| `beam_noise` | `frequency + 1` points along the beam, each offset by a pick in `range`, picked again each `lock_time` seconds (each step at 0); `smooth` heads for each new pick at `speed`; `tessellation` curves between points |
| `beam_modifier` | an end's position, tangent or strength added to or multiplied by, after it resolves |

| Renderer field | Does |
|---|---|
| `sheets` | strips about the beam, each turned π / sheets from the last, so it reads from every side |
| `max_beam_count` | the most beams alive at once: the emitter's capacity |
| `speed` | how fast the beam reaches its target, blocks a second; 0 arrives at once |
| `interpolation_points` | segments along the curve its tangents make, without noise; 0 a straight line |
| `texture_tile`, `texture_tile_distance` | how often its texture repeats along it, or one repeat a distance |
| `up_vector_step_size` | 1 turns the whole beam to the eye by its first segment, 0 each segment |

- **A beam's material is drawn as a ribbon's** (`usage: [ribbons]`): any particle program draws it, placed by the
  engine's strip factory; U runs along the beam, V across it.
- **Width is the particle's size**, times its x scale: `size` and `scale_sprite_size` shape it as for a sprite.
- **Speed, range scale and tangent strength roll each frame**, as Cascade reads them at its emitter's time: a range
  on one is a flicker shared by the emitter's beams, not a value a beam.
- **A beam keeps its count up, as Cascade's does**: while its loops run and fewer than `max_beam_count` live, one
  spawns each step that nothing else spawns, and with `always_on` it spawns back up to the count every step.
- **An `actor` end** is a point of the entity its effect follows, by the end's `name` (`CgEntityAttachments`), resolved
  each step its lock leaves open; where nothing answers it, the end is its `point`, as Cascade's with no actor set.
- **Refused at load, by name**: `beam_method: distance | branch`, `taper_method: full | partial`, an `emitter` or
  `particle` end, an `emitter` tangent, and the editor's lines.
- **A beam is culled whole**, within 256 blocks of its source: its ends are on the GPU alone.

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

## Force fields: a push every opted-in emitter feels

A force field is Chaos's Physics Field: a graph of nodes (falloffs, vectors, operators) whose root's value a target
applies to a particle. A system places fields; the emitters whose `physics_field` module reads a field's group feel
it. A field is presentation only: it never moves a character and a server runs none.

```yaml
# vfx/systems/rasengan.yaml
fields:
  - id: f1
    group: mymod.pull                    # who reads it: physics_field modules of this group
    orient: world                        # its vectors keep the world's axes; anchor (the default) turns them with it
    field:
      kind: graph
      target: linear_force               # added to each particle's acceleration
      root: out
      nodes:
        - { id: v, kind: radial_vector, magnitude: -5 }
        - { id: r, kind: radial_falloff, radius: 3, default: 0 }
        - { id: out, kind: operator, op: multiply, left: v, right: r }
```

The same graph built in Java, exactly what the file loads:

```java
CgFieldDefinition.Graph pull = CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
        .node("v", "radial_vector", "magnitude", -5)
        .node("r", "radial_falloff", "radius", 3, "default", 0)
        .node("out", "operator", "op", "multiply", "left", "v", "right", "r")
        .build();                                    // the root is the last node
pull.bounds();                                       // a box 3 blocks each way: where it acts
Map<String, Object> tree = pull.write();             // fields/rasengan_pull.yaml, or the inline graph
```

An emitter feels the fields of a group through `physics_field`, its scale a number or a range rolled per particle:

```yaml
particle_update:
  - { id: pulled, module: crystalgraphics:physics_field, inputs: { group: mymod.pull, target: linear_force, scale: { range: [0.5, 1] } } }
```

```java
.module(new CgVfxModule.PhysicsField(CgFieldWorld.group("mymod.pull"), CgFieldDefinition.Target.LINEAR_FORCE, 1f))
```

| Target | Applied |
|---|---|
| `linear_force` | added to the acceleration |
| `linear_velocity` | moves it by the value for the step, never kept in its velocity |
| `orbit` | turned about the value's direction through the field's place, its length a rate |
| `kill` | the particle dies where the value is above 0 |
| `speed_scale` | added, a rate per 30 fps frame, to a speed scale the particle keeps from 1, floored at 0, which multiplies how far its velocity and an `orbit` field move it |
| `angular_velocity` | added, radians per 30 fps frame, to a rotator the particle keeps: a mesh turns by all three angles on top of its own turn, a sprite by the y alone, as its screen angle |
| `scale` | added per axis, a rate per 30 fps frame, to a scale the particle keeps from 1: a mesh is scaled by it and not drawn while an axis is at or below 0; a sprite is untouched |
| `strain` | acts on the stage, never a particle: a `physics_field` naming it fails at load |

- **Where it acts is Chaos's**: a radial or box falloff with a default of 0 bounds its graph to its radius or box, a
  multiply or divide to the smaller of its sides, an add or subtract to the larger. A graph bounded by nothing acts
  everywhere, centred where it is placed; a placement's `radius` bounds it.
- **Its numbers are in your units** (`assets.md`): lengths convert as declared, and the root's value as its target
  measures it (an acceleration, a speed).
- **Fails at load, by path**: more than 16 nodes, a node reading one listed after it, an unknown kind or target, and a
  root whose value its target cannot take (a scalar for `linear_force`).
- **Each group evaluates its 16 nearest** fields that reach the camera, every frame (`CgFieldWorld`, Chaos's physics
  field manager), each where its bounds hold the particle. A module reading one group feels only that group's.
- **A field lives with its system**, at its origin and turned with it (`orient: anchor`). A placement whose `at` names
  a locator, a socket or a spawnable is not placed yet: playing it fails, naming it.
- **Not on the GPU yet**: a `noise` node fails as its field is placed.
- **What the GPU computes is the Java mirror's** (`CgFieldEval`, Chaos's formulas); `--mode=vfx-fields` holds every
  node, placement and target to it, and `vfx-modules`' `fields` station shows a pull, a push and a kill box beside a
  fountain none reaches.
