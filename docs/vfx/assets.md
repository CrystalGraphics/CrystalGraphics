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

Every resolved value knows where it came from (`CgOrigin`): `DEFAULT`, `INHERITED`, `OVERRIDDEN`, `ADDED`,
`DISABLED` or `REMOVED`. That is what an editor greys out, and what "reset to parent" clears.

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
```

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
