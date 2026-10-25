# Assets: the files an effect is made of

Every effect, emitter, material, sequence and camera shake is an **asset**: one YAML file in your mod's resources,
with a kind given by its folder. This page covers the file itself: what it holds, how a child inherits from its
parent, how the engine writes it back, and how Java code reads and edits one. Unreal readers: an asset is a
`UObject` package, a kind its `UClass`, and a child asset an instance whose properties override its archetype's.

## A file

```yaml
# assets/mymod/vfx/systems/kamehameha/charge.yaml  →  the id mymod:kamehameha/charge
_v: 1
delay: 0.5
emitters:
  - { id: h1, name: core, emitter: mymod:kamehameha/core }
  - { id: h2, name: sparks, emitter: mymod:kamehameha/sparks }
```

- **The id is the path.** `assets/<namespace>/<kind folder>/<path>.yaml` is `<namespace>:<path>`.
- **`_v` comes first**: the kind's file version. An older file is upgraded on read, one step a version.
- **Then `parent` and `units`, if any; then the kind's keys in their declared order; `editor` and `remove` last.**
- **`editor`** holds what only tools read: layout, notes, an import's provenance. The engine never reads it, and the
  cooked form drops it. A comment in a file becomes a note there (below).

### The kinds

| Kind | Folder | | Kind | Folder |
|---|---|---|---|---|
| `system` | `vfx/systems` | | `prefab` | `prefabs` |
| `emitter` | `vfx/emitters` | | `skeleton` | `skeletons` |
| `module` | `vfx/modules` | | `springs` | `springs` |
| `effect_type` | `vfx/effect_types` | | `rig` | `rigs` |
| `ending` | `vfx/endings` | | `retargeter` | `retargeters` |
| `material` | `materials` | | `sequence` | `sequences` |
| `texture` | `textures` | | `camera_rig` | `cameras/rigs` |
| `mesh` | `models` | | `shake` | `cameras/shakes` |
| `parameters` | `parameters` | | `field` | `fields` |
| `curve` | `curves` | | `cue` | `cues` |

### Values

A number is a constant, or a map that names how it varies, so any number can become a range or a curve without its
key changing:

```yaml
size: 1.5                                   # a constant
size: { range: [1.0, 2.0] }                 # rolled per particle
size: { curve: mymod:fade_out }             # over the particle's life
size: { bind: user.charge, scale: 2.0 }     # read from a parameter
tint: { argb: 0xFF1858FF, intensity: 1.6 }  # a colour, HDR by its intensity
```

A key that takes no form (a name, a flag, a list of ids) takes only itself.

### Units

An asset's numbers are in its `units:` block, or its namespace's `units.yaml`, so a file exported from another
engine keeps that engine's numbers. The engine converts once, on load. A child's units are its parent's.

```yaml
units: { length: cm, axes: unreal, angles: degrees, rotation: unreal_rotator, time: { seconds: true, frames: 30 } }
```

## Inheritance

A file with `parent:` is a child. It writes only what differs from its parent, and everything else comes through:

```yaml
# mymod:kamehameha/charge_big
_v: 1
parent: mymod:kamehameha/charge
delay: 1.0                                                    # overrides the parent's 0.5
emitters:
  - { id: h2, name: big_sparks }                              # overrides one key of the parent's h2
  - { id: r7, after: h1, name: ring, emitter: mymod:ring }    # adds an emitter after the parent's h1
remove: [h3]                                                   # drops the parent's h3
```

- **Maps merge by key**: a child's key replaces its parent's, and a nested map merges the same way.
- **Lists of elements merge by `id`**: a child's element with a parent's id overrides it key by key, a new id is
  added at the end or where `after:` / `before:` puts it, `remove: [id]` drops one, and `enabled: false` keeps it
  but switches it off.
- **Any other list replaces whole**, and so does a value form: a child's `{ range: [...] }` replaces the parent's
  curve, never merges into it.
- **An element's id is permanent.** Renaming one breaks every child that overrides it; change its `name` instead.

Every resolved value knows where it came from (`CgOrigin`): `DEFAULT`, `INHERITED`, `MODIFIED` (inherited, with
something inside it changed), `OVERRIDDEN`, `ADDED`, `DISABLED` or `REMOVED`. That is what an editor greys out, and
what "reset to parent" clears.

## How a file is written

Every writer the engine has goes through one set of rules, so the same tree always gives the same bytes and a diff
shows only what changed:

- keys in declared order, then keys the kind leaves open (a section's channels, your own `user` values) in the
  order they were read;
- a value on one line; a list of elements one element per line; anything else on one line when it fits in 120
  columns, else as a block;
- floats in their shortest form that reads back the same (`0.1`, `1.0e-05`), colours as `0xAARRGGBB`;
- a string unquoted when it is a plain name or id, else JSON-quoted.

A comment is kept as a note: on a line of its own at the top, it becomes the asset's `editor.note`; at an element's
first line, that element's `editor.note`; anywhere else, a note on the key it sits beside, in `editor.notes`. Write a
note there directly and the writer puts it back where it came from.

```java
Object tree = CgAssetYaml.read(text);                    // comments moved into editor
String bytes = CgAssetYaml.write(tree, CgParticleKinds.SYSTEM);
String same = CgAssetYaml.canonical("vfx/systems/charge.yaml", text);   // what the engine would write
```

The cooked form, what a built mod ships, is the same tree in binary without its `editor` blocks:

```java
byte[] cooked = CgAssetBinary.cook((Map<?, ?>) tree);
Map<Object, Object> back = CgAssetBinary.read(cooked);
```

## Reading and editing an asset in Java

`CgAssetData` is a file resolved over its parent. Its `delta()` is always exactly the file, so opening and saving an
asset you did not change writes nothing new.

```java
CgAssetKinds.register();
CgAssetData charge = CgAssetData.of("mymod:kamehameha/charge", CgParticleKinds.SYSTEM, chargeTree, null);
CgAssetData big = CgAssetData.of("mymod:kamehameha/charge_big", CgParticleKinds.SYSTEM, bigTree, charge);

big.resolved();                                // the merged tree: what plays
big.origin("emitters", "h2", "name");          // OVERRIDDEN
big.from("delay");                             // the asset the value came from
CgAssetYaml.write(big.delta(), CgParticleKinds.SYSTEM);   // the child's own file
```

An edit names a property by its path: the asset's id, then each key, with `[id]` for an element.

```java
CgPropertyPath name = CgPropertyPath.parse("mymod:kamehameha/charge_big /emitters[h2]/name");
CgEdit undo = big.apply(CgEdit.set(name, "glow"));   // returns its inverse
big.apply(undo);                                     // back to the same bytes

big.apply(CgEdit.reset(name));                       // drop the override: inherited again
big.apply(CgEdit.remove(CgPropertyPath.of(big.id()).key("emitters").item("h3")));   // writes remove: [h3]
big.apply(CgEdit.insertAfter(CgPropertyPath.of(big.id()).key("emitters"), ring, "h1"));
big.apply(CgEdit.duplicate(CgPropertyPath.of(big.id()).key("emitters").item("h1")));   // a copy before h1, fresh ids
big.apply(CgEdit.clear(CgPropertyPath.of(big.id()).key("emitters")));                 // every emitter dropped
```

A duplicate is Unreal's: the copy goes where the original was and the original moves down one, and the copy and
every element inside it get new ids, so nothing that names the original ends up naming the copy.

A list of edits is one transaction: one change event, and one list of inverses to undo it whole. An edit that fails
leaves the asset as it was.

```java
List<CgEdit> undo = big.apply(Arrays.asList(CgEdit.set(name, "a"), CgEdit.disable(sparks)));
Runnable stop = big.onChanged(e -> {
    if (e.change() == CgChange.STRUCTURAL) rebuild();   // a renderer or module swapped: rebuild what plays it
    else refreshValues();                               // a value changed: update the values only
});
big.apply(undo);
stop.run();
```

- Moving or editing an element of the parent from a child writes an override in the child; the parent's file is
  never touched.
- Every edit re-resolves the asset; a child of it re-resolves when you rebuild its `CgAssetData` over the new parent.

## A system, as a file and in Java

One system, both ways. The file:

```yaml
# assets/mymod/vfx/systems/puff.yaml  →  mymod:puff
_v: 1
user:
  radius: { type: float, quantity: length, default: 0.8 }
  tint: { type: colour, default: { argb: 0xFF2659FF, intensity: 1.6 } }
emitters:
  - id: puff
    emitter:
      properties: { capacity: 40 }
      emitter_update:
        - { id: s0, module: crystalgraphics:emitter_state, inputs: { loop: once, duration: 1 } }
        - { id: s1, module: crystalgraphics:spawn_burst, inputs: { time: 0, count: 20 } }
```

The same system in code:

```java
static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:puff");
static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.8f);
static final CgVfxParam.Color TINT = SCHEMA.color("tint", 0xFF2659FF, 1.6f);
static final CgVfxEmitter PUFF_EMITTER = CgVfxEmitter.builder("puff").capacity(40)
        .state(CgVfxEmitterState.once(1f)).burst(0f, 20).build();
static final CgVfxSystemDefinition PUFF = CgVfxSystemDefinition.of("mymod:puff",
        CgVfxLook.builder(SCHEMA).with(CgVfxKit.of("mymod:puff").emitter(PUFF_EMITTER).build()).build());
```

Each turns into the other:

```java
Map<String, Object> tree = CgVfxSystemAssets.write(PUFF);                        // code → file
String yaml = CgAssetYaml.write(tree, CgParticleKinds.SYSTEM);
CgVfxSystemDefinition back = CgVfxSystemAssets.compile(                          // file → code
        CgAssetData.of("mymod:puff", CgParticleKinds.SYSTEM, tree, null), (kind, ref) -> null);
```

- **The schema is the system's `user` block**: a parameter on a schema named as the system is written; another
  schema's is not. A system whose `effect` has a contract writes only what differs from the contract's look, plus
  the names the contract requires.
- **Emitters are written inline.** A file may name one by reference instead (`emitter: mymod:puff/core`); written
  back, it is inline.
- Curves, palettes and shakes are not `user` parameters yet: a schema's are kept in code and left out of the file.

### What an emitter file carries

Every builder field is written through the format's modules (`CgVfxEmitterAssets`), so an emitter compiled from its
file alone is the one that wrote it:

| Builder | File |
|---|---|
| `capacity`, `local`, `optional` | `properties`: `capacity`, `local_space`, `scalability: [{ tiers: [low], scale_spawn_count: true, spawn_count_scale: 0.5 }]` |
| `state`, `rate` (and its scale curve), `burst`, `ratePerBlock`, `maxStep` | `emitter_update`: `emitter_state`, `spawn_rate { rate, scale: { curve, by: loop } }`, `spawn_burst`, `spawn_per_unit` |
| `life`, `heat`, `size`, `color`, `scale` | `initialize_particle`: `lifetime`, `heat`, `sprite_size`, `color`, `mesh_scale`. A size's skew is `sprite_size` as a curve by a random draw (below) |
| `shape` and its placement, `pointing`, `at`, `mesh`, `entity` | `shape_location`: the shape's own inputs, `surface_only` (with `surface_thickness` for a shell or a ring), `edge_only`, `hemisphere`, `arc`, `ordered`, `rotation` (a quaternion), `offset`. A sweep is the radius as a curve by loop (below) |
| `outward`, `inward`, `direction`, `speed`, `inherit` | `add_velocity`: `mode` (`outward`, `inward`, `in_cone` with `cone_axis`, `cone_angle` and `inner_cone`, Unreal's apex angles) |
| `size(curve)`, `scaleOverLife`, `colorOverLife`, `opacity`, `intensityOverLife` | `scale_sprite_size`, `scale_mesh_size`, `scale_color { scale, alpha, intensity }`, curves by age |
| `spin`, `rasterize` | `sprite_rotation_rate` (its rate `crystalgraphics:random_sign` of a range), `grid_rasterize { density }` |
| each `module` | its file id: `curl_noise`, `acceleration`, `collision` (by `against`: `shape`, `world`, `depth`, `entities`), `kill_volume`, `point_attractor`, `orbit_axis`, the rest by their own |

- **Curves are `FRichCurve`'s keys**: a linear segment linear; a quad or cubic easing a cubic with its tangents,
  exactly; any other easing 32 cubic pieces at its own slope.
- **The renderer, trail, clip, events and bindings are not in the file yet**: they come from the code-built emitter
  of the same id (`compiler(templates)`), else the builder's defaults.
- An input the format declares and the engine does not take yet reads at its default alone, and fails by name otherwise.
- **A module's input takes the format's forms** (`core.md` § Values), built per particle in the Step kernel each step: a
  range rolled per particle (per channel, locked), a pick, a curve or an array by `age`, `particles.age`, `speed`,
  `random`, `id` or `loop` (the loop's seconds), a function (`crystalgraphics:random_range`, `crystalgraphics:add`)
  whose arguments are forms themselves, and `{ bind: particles.age }`. Its rolls are keyed by its element's `id` and the
  input's name, never by place.

  ```yaml
  particle_update:
    - { id: push, module: crystalgraphics:acceleration, inputs: { acceleration: { range: [[0, 1, 0], [0, 4, 0]] } } }
    - { id: drag, module: crystalgraphics:drag, inputs: { drag: { curve: { keys: [[0, 0.5], [1, 3, linear]] }, by: age } } }
    - { id: gust, module: crystalgraphics:acceleration, inputs: { acceleration: { fn: crystalgraphics:random_range,
        inputs: { min: [0, 0, 0], max: { array: [[1, 0, 0], [0, 2, 0]], by: loop } } } } }
  ```

  Refused by name: a roll per loop or instance (no module input declares one), a lookup by `length` (a trail's), and a
  binding to anything but `particles.age` (a user parameter's is P5's binding, through its system).
- **A spawn value takes them too**: `initialize_particle`'s `lifetime` and `sprite_size`, `add_velocity`'s `speed`, and
  `shape_location`'s radius, each built per spawn in any of those forms whose lookup a newborn has: `random`, `id` or
  `loop` (the loop's seconds as it spawns); a constant or a range is the spawn row's two ends. A radius in a form moves each birth out by its value
  less the shape's radius. The builder's `size(min, max, skew)` and `sweep(speed)` write two such forms.

  ```yaml
  particle_spawn:
    - { id: init, module: crystalgraphics:initialize_particle, inputs: { lifetime: { pick: [1.5, 3] } } }
    - { id: shape, module: crystalgraphics:shape_location, inputs: { shape: sphere, surface_only: true,
        sphere_radius: { curve: { keys: [[0, 9, cubic, break, 0, 33.5], [1.6, 35.8, cubic, break, 0, 0]] }, by: loop } } }
  ```

  A lifetime in a form is booked at its longest, so its emitter finishes no earlier than its last particle.

## Playing an asset, and changing it while it plays

A system played from its ref follows the asset: a newer definition reaches every instance playing it.

```java
static final CgAssetRef<CgVfxSystemDefinition> CHARGE = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");

CgLoadHandle loading = CgVfxAssets.instance().load(List.of(CHARGE), CgLoadPriority.MOVE);   // ahead of the move
if (loading.ready()) VFX.play(new CgVfxSystemInstance<>(CHARGE, x, y, z));                    // follows CHARGE

VFX.play(new CgVfxSystemInstance<>(CHARGE.get(), x, y, z));   // the definition alone: never changes while it plays
```

What makes a newer definition: a file saved in a dev run (its source folder is watched), an editor's edit, or
`CgVfxAssets.instance().saved("mymod:vfx/systems/kamehameha/charge.yaml")` from a tool of your own. A saved file is
compared with the one it replaces and turned into the edits an editor would make, so both reach what plays the same way.

| The change | A playing instance |
|---|---|
| A value: a rate, a colour, a module's input | takes it at its next update, its particles and emitters kept |
| Structure: a module, emitter or renderer added or removed, a capacity | plays again from its start up to its age, its emitters and behaviour made anew |

- **A material reaches every system drawing with it**: saving a material's file recompiles the systems that use it,
  and each playing one takes its new values in place.
- **Values set on the instance keep** through either: Unreal keeps a component's user parameters through a reinit.
- **A value that changes what an emitter is** (its capacity, its life, whether it is local) restarts it too.
- **A file that no longer reads, resolves or validates** keeps the last good definition and logs why; save it fixed and
  it takes effect.
- A restart makes the behaviour anew: hold the instance, not `instance.behaviour()`, across a save.

### Spawning from a pool

A system played often (a hit spark, a muzzle flash, a footstep's dust) is spawned rather than made: its system keeps
a pool of idle instances per ref or definition, takes one, and takes it back once it completes and its particles are
gone, as Unreal's component pool does. Playing it again then allocates nothing.

```java
CgVfxSystem vfx = CgVfx.world();
vfx.spawn(SPARK, hitX, hitY, hitZ).ground(floorY);   // taken from SPARK's pool, or made when none is idle
vfx.spawn(SPARK.get(), x, y, z);                     // a definition's pool: never follows the asset
```

```yaml
# assets/mymod/vfx/systems/spark.yaml
pool_prime_size: 4     # made when the asset is ready, before its first play: 0 by default, and the engine makes 1
max_pool_size: 8       # idle ones kept; one past it is dropped: 32 by default
```

```java
CgVfxSystemDefinition.of("mymod:spark", LOOK).withPool(4, 8);   // the same, in code
vfx.pool(SPARK).prime(4);                                         // by hand: what a READY asset does
```

- **Hold nothing of a spawned instance past its completion**: the next spawn may take it again, its age, values,
  seed, emitters and behaviour started over.
- A spawned instance is a fresh one in every other way: set its ground, follow and values after `spawn`.
- `spawn` and `pool` are the render thread's; a pool itself may be primed or taken from on any thread.

### Fixed bounds

A system's bounds fixed about its origin, Unreal's fixed bounds: what a material reading the system's bounds reads
([materials](materials.md), `CG_OBJECT_POSITION`, `CG_OBJECT_RADIUS`).

```yaml
fixed_bounds: [[-400, -400, -400], [400, 400, 400]]   # min and max corners in the namespace's units: 4 blocks in centimetres
```

```java
CgVfxSystemDefinition.of("mymod:orb", LOOK).withFixedBounds(new CgVfxSystemDefinition.FixedBounds(-4, -4, -4, 4, 4, 4));
```

- Absent, its bounds follow its particles, and a material reading them refuses the system when it loads.

### Macro UVs

Where a material's particle macro UVs centre and how far they reach, Cascade's `MacroUVPosition` and
`MacroUVRadius`: what `CG_PARTICLE_MACRO_UV` places on screen ([materials](materials.md)). A renderer may carry its own,
Cascade's required module with its override on.

```yaml
macro_uv: { position: [0, 0, 50], radius: 300 }    # in the namespace's units: half a block up, three blocks
renderers:
  - { id: r1, kind: sprite, material: mymod:materials/aura, macro_uv: { radius: 120 } }
```

```java
CgVfxSystemDefinition aura = CgVfxSystemDefinition.of("mymod:aura", LOOK).withMacroUv(new CgVfxMacroUv(0f, 0.5f, 0f, 3f));
CgVfxRenderers.sprite("r1").material(AURA).macroUv(new CgVfxMacroUv(0f, 0f, 0f, 1.2f)).build();
```

- Absent, it is Cascade's default: the system's origin and 200 cm (2 blocks). A field left out of a map is the
  default's.
- A renderer's override takes its own radius, and its position as a vector turned with the system, so it stands by the
  world's origin: Cascade's own rule. The centre stays the system's.

## Checking an asset

An asset is checked whole, with what it inherits, as Unreal's data validation checks an object: every key one its
type declares, every required key present, every value of its declared shape, enum and form, every element id
well-formed and used once. A type adds its own checks (a module's inputs are its module's, a spawnable is one kind).

```java
CgValidation v = big.validate(CgValidation.Usecase.LOAD);
for (CgIssue i : v.issues()) System.out.println(i);
// ERROR mymod:kamehameha/charge_big /emitters[h2]/emitter/properties/simulation: 'cpu' is not one of [gpu, analytic] (enum)
if (!v.valid()) refuse(big);
```

- **An error refuses the asset at load** and fails a cook; a warning only reports. Each issue has a stable `code`
  (`unknown_key`, `required`, `shape`, `enum`, `form`, `id`, `duplicate_id`, `ref`, `member`, `unsupported`) and the
  path of the node it is about, which an editor selects.
- **`unsupported` entries** (what an importer could not map) warn once and are skipped.

Your type checks its own values with a validator, reporting under the path it is given:

```java
CgTypes.element("mymod:renderer.arc")
        .property("min_width", CgValueType.FLOAT)
        .property("max_width", CgValueType.FLOAT)
        .validator((value, at, into) -> {
            Object min = value.get("min_width"), max = value.get("max_width");
            if (min instanceof Number && max instanceof Number && ((Number) min).doubleValue() > ((Number) max).doubleValue()) {
                into.error(at.key("min_width"), "width_order", "min_width is above max_width");
            }
        })
        .onRegister(t -> CgParticleKinds.RENDERERS.member("mymod:arc", t))
        .register();
```

To hold assets of any kind, yours or another mod's, to a rule of your own, register a `CgAssetValidator`, as Unreal's
editor validators do. It runs only on an asset whose own checks passed, for the use cases it accepts:

```java
CgAssetValidator.register(new CgAssetValidator() {
    public boolean canValidate(CgValidation.Usecase usecase) { return usecase == CgValidation.Usecase.COOK; }
    public boolean canValidate(CgAssetData asset) { return asset.kind() == CgParticleKinds.SYSTEM; }
    public void validate(CgAssetData asset, CgValidation into) {
        Object emitters = asset.resolved().get("emitters");
        if (emitters instanceof List && ((List<?>) emitters).size() > 32) {
            into.warning(CgPropertyPath.of(asset.id()).key("emitters"), "many_emitters", "more than 32 emitters");
        }
    }
});
```

## Your own types

Kinds and their structs are declared once, in Java, and everything (reading, writing, inheritance, the editor)
works from the declaration. A mod adds a member to a family the engine opens to it: a renderer, a field node, a
camera node, a retarget op or a shake pattern.

```java
CgTypes.element("mymod:renderer.arc")
        .property("subdivisions", CgValueType.INT).defaultValue(8)
        .property("width", CgValueType.FLOAT).quantity(CgQuantity.LENGTH)
        .onRegister(t -> CgParticleKinds.RENDERERS.member("mymod:arc", t))
        .register();
```

- Register before `CgTypes.freeze()`, which runs at the end of init.
- Declaration order is file order. An element gets `id` first and `editor` last on its own.
- A family's members are told apart by its key (`kind: mymod:arc` here), which the family writes after the `id`:
  a member never declares it. A mod's member names its namespace.
