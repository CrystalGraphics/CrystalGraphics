# Sequences

## A sequence's time

A sequence counts in ticks at its `tick_resolution` (24,000 a second is common) and shows frames at its `display_rate`.
Each update evaluates the span from where the last one stopped to where this one does, so a key inside a long frame is
still crossed, once, as Unreal's Sequencer does.

```java
CgSequenceDefinition definition = ...;                                   // tick_resolution 24000, display_rate 30
CgSequencePlaybackPosition clock = CgSequencePlaybackPosition.of(definition);
CgSequenceEvaluationRange range = clock.playToSeconds(age);              // each update: seconds since it started
range.sweep(hits, (tick, name) -> fire(name));                           // each event key in the span, once
```

A seek, or a player joining late, jumps instead: it lands on one point and crosses nothing.

```java
CgSequenceEvaluationRange at = clock.jumpToSeconds(1.5);
hits.place(CgFrameTime.frame(at.current()));                             // keys before it count as played
```

- **Times are `CgFrameTime` longs**: a whole frame and a sub-frame, compared as longs. Keep the `CgFrameRate` beside one
  and convert with `CgFrameRate.convert`.
- **The same ticks fire at any frame rate**: 30, 60 or 144 frames a second, or one 200 ms frame, cross the same keys.
- **`evaluation: frame_locked`** steps whole display frames; without it, sub-frames are kept.
- A rate is a whole number or an NTSC one (`29.97`, `23.976`); any other fails at load, naming the sequence.

## A compiled sequence

A sequence file loads once into a `CgSequenceDefinition` that every player of it shares: its tracks and sections, each
channel's keys as flat columns, every binding resolved, and an **evaluation field** saying what is active at each tick.

```yaml
# mymod:sequences/beam.yaml
_v: 1
tick_resolution: 60000
display_rate: 30
range: [0, 90000]
composite_sections:
  - { id: q1, name: charge, range: [0, 30000], next: q2 }
  - { id: q2, name: loop, range: [30000, 90000], next: q2 }
roles:
  - { id: b1, name: caster, kind: caster, seed: true }
spawnables:
  - { id: s1, name: ball, system: mymod:systems/ball, at: { role: caster, socket: hand_r } }
tracks:
  - id: t1
    kind: system
    binding: { spawnable: s1 }
    sections: [ { id: x1, range: [6000, 90000], keys: [[6000, activate], [84000, deactivate]] } ]
  - id: t2
    kind: visibility
    binding: { role: caster }
    sections:
      - { id: x2, range: [0, 60000], visible: [[0, true]] }
      - { id: x3, range: [30000, 90000], overlap_priority: 1, visible: [[30000, false]] }
```

```java
CgSequenceDefinition beam = ...;                                     // from the pipeline, or CgSequenceDefinition.compile
int caster = beam.role("caster");                                    // roles and sections by name: the contract
CgSequenceDefinition.CompositeSection loop = beam.compositeSection("loop");   // its range, and next: itself

CgSequenceField field = beam.field();
int segment = field.segmentAt(45000);
for (int k = 0; k < field.entryCount(segment); k++) {
    int e = field.entry(segment, k);
    CgSequenceTrack track = beam.tracks().get(field.track(e));      // t1 (the whole track), t2's x3
}
CgSequenceChannel visible = beam.tracks().get(1).sections().get(1).channel("visible");
boolean shown = visible.bool(45000);                                 // false
```

- **Overlapping sections resolve as Unreal's do.** In one row, where two sections overlap, the higher
  `overlap_priority` plays alone, unless the kind blends (`transform`, `parameter`, `material`, `global`, `animation`,
  a float `property`…), whose sections all play. Another `row` plays beside it. A `material` or `subsequence` track
  plays every section; a `system` track plays over all time, its keys read as play crosses them.
- **A range is `[from, to)` in ticks**; `null` leaves an end open.
- **Channels are typed by their kind**: `visible` and `spawned` hold bools, a system's `keys` are `activate`,
  `deactivate`, `trigger`, `kill`, an event's keys carry `{ event, args }`; any other channel is a curve, unless its keys
  hold bools or strings. A held channel holds its key at or before the time, and its first key before that.
- **Values arrive in the host's units**: a translation's three channels in metres, permuted through the asset's axes.
  A rotation keeps its own angles, in radians, with `units()`' convention.
- **What fails at load**, naming the sequence and the path: a binding naming no role, locator or spawnable; a
  composite section's `next` naming no section; keys out of tick order; a condition on a section of an `rpg` event
  track (a server never reads a condition, so it would never hear that event); subsequences nested more than four deep.
- A muted track, an inactive section and a section an event starts (`start: { event }`) are never in the field.

## Playing a sequence

A world's `CgSequencer` plays every sequence in it. What a sequence moves is given to it by role: anything that
implements `CgSequenceTarget`, a set of named slots it writes each frame.

```java
CgSequencer sequencer = world.add(new CgSequencer());          // ticks in CgTickGroup.SEQUENCES

final class Camera implements CgSequenceTarget {
    float fov = 70;
    @Override public int slot(String name) { return name.equals("fov") ? 0 : -1; }
    @Override public void set(int slot, float[] values, int mask) { fov = values[0]; }
    @Override public void get(int slot, float[] out) { out[0] = fov; }   // before any sequence: what a restore puts back
}

CgSequencePlayer player = sequencer.play(beam, Map.of("caster", goku, "target", vegeta));
player.rate(0.5f);                                               // half speed
float x = player.locator(0).translation()[0];                    // the sequence's own points, after evaluation
player.stop();
```

- **Slots are named by the track**: a transform writes `translation`, `rotation` and `scale`; visibility `visible`; a
  `parameter` or `property` track its `parameter` or `property` (`user.Size`, `fov`); a material track or a global track
  each parameter by name (`scalars: { BG_Brightness: ... }` writes `BG_Brightness`). A global track writes its parameter
  collection itself.
- **Binding happens at `play`**, and as each spawnable is made for what waits on it: a slot a target lacks throws
  there, naming both, never in the frame.
- **`mask` says which components a frame writes**: a section keying only `x` leaves `y` and `z` as they are.
- **Sections writing one slot blend**, across rows and across every playing sequence, as Unreal's blenders do. Absolute
  sections average by weight; a weight short of 1 ramps from the slot's value before the sequence (`get`). Relative
  sections add to that value; `additive` adds on top; `additive_from_base` adds what its channel moved since its
  section began; `override` replaces what came before it, lerped by its weight. A section's weight is its ease (in and
  out, through its functions, `cubic_in_out` unless named) times its `weight` channel.
- **When a section ends**, `completion: restore_state` (and an absent completion, Unreal's default for a sequence)
  puts the slot back to its value before the first sequence animated it. That waits until the last section animating
  it has ended: two sequences keying one global restore it only when both are done. `keep_state` leaves the last value.
- **A role the sequence names and `play` is not given** fails, unless the role is `absent: skip`.
- **An `animation` track plays clips on a role that is a `CgSequenceSkeletal`**: each section's `clip` on the role's
  instance's montage slot (`CgAnimInstance.slot`), at the clip time its `start_offset` (ticks), `play_rate` and `loop`
  map its time to, weighted by its ease and `weight`, every frame play is inside it. The role resolves a clip id to
  one of its clips; one it lacks, or a role that is not skeletal, fails the play. Additive sections and
  `interpolation: step` fail too: not played yet.

```java
final class Fighter implements CgSequenceRole, CgSequenceSkeletal {
    @Override public CgAnimInstance instance() { return body; }
    @Override public CgAnimSequence clip(String id) { return clips.get(id.substring(id.lastIndexOf('/') + 1)); }
    ...
}
sequencer.play(kamehameha, Map.of("caster", goku));   // the charge montage's clips on Goku, its systems on his hand
```
- **A role's outline colour** (the material contract's `outline`, a `material` track keying `colours.outline` on the
  role) is a `CgRoleOutline` the role answers the slot through: a keyed colour sets the role's slot on its own materials
  (`CgAnimInstance.scalar`) and writes the colour into the outline post's slot; `restore_state` puts the materials' own
  back.

```java
outline = new CgRoleOutline(goku.instance(), battleOutline, 1, "OutlineColorType", "TypeColor1");
@Override public int slot(String name) { return outline.slot(name); }   // set and get the same
```
- **At its end a player holds its last frame** (`finished()`) until stopped; what its ended sections restored stays
  restored.
- **A kind of your own** is a `CgSequenceEvaluator`, registered on the sequencer: it binds each section once, then
  evaluates its kind's active sections across every player each frame.

```java
sequencer.register(new CgSequenceEvaluator() {
    @Override public String kind() { return "morph"; }
    @Override public void bind(CgSequencePlayer p, CgSequenceTrack t, CgSequenceSection s) { p.data(t, s, new Weights(s)); }
    @Override public void evaluate(CgSequencePlayer p, CgSequenceTrack t, CgSequenceSection s, float tick) {
        ((Weights) p.data(t, s)).sample(tick);
    }
});
```

## What a sequence spawns

A sequence's `spawnables` are objects it makes and owns: a system, a prefab, a post volume. Each kind is made by a
spawner registered on the sequencer by its kind, as Unreal's spawn register makes a spawnable's object.

```java
CgVfxSystemSpawner systems = new CgVfxSystemSpawner(CgVfx.world());
sequencer.spawner("system", systems);
CgVfxPrefabSpawner.install(sequencer, systems);       // its prefabs sort with its systems, one group a play
CgPostVolumeSpawner.install(sequencer);
sequencer.spawner("light", (player, spawnable, at) -> new MyLight(spawnable.tree(), at));   // a kind of your own
```

```yaml
locators:
  - { id: k1, name: palms, parent: { role: caster, socket: aim }, location: [0, 0, 0.6] }
spawnables:
  - { id: s1, name: charge, at: { locator: k1 }, system: novamod:nova_cannon/charge }
  - { id: s2, name: shock, at: { role: caster, socket: root }, prefab: nsuns4_kit:1efcmn/1efc_shock08 }
  - { id: s3, name: ring, at: { spawnable: s2, node: "1efc_shock08" }, system: nsuns4_kit:1efcmn/ring }
  - { id: s4, name: flash, post: { bloom: 1.6, exposure: { flash: 1.5 } }, priority: 10, unbound: true }
tracks:
  - id: t1
    kind: system
    binding: { spawnable: s1 }
    sections:
      - { id: t1a, range: [0, 44], keys: [[0, activate], [42, deactivate]] }
  - { id: t2, kind: property, binding: { spawnable: s2, node: "1efc_shock08" }, property: opacity, sections: [ ... ] }
  - { id: t3, kind: property, binding: { spawnable: s4 }, property: weight, sections: [ ... ] }
```

- **When**: a spawnable with a `spawned` track lives where its track says true; one without lives for the whole play.
  Each frame spawns before it binds and evaluates, so a spawnable's tracks act in the frame it is made. The play's end
  ends everything it made.
- **Where**: `at` places it at a locator, a role's socket, or a node of another spawnable, read every frame unless its
  `follow` is false. A locator's own place is its parent's composed with its transform, keyed or at rest.
- **A `system` track** drives a spawned system as Unreal does. A section with `keys` follows its last key at or before
  the frame (`activate`, `deactivate`; a `trigger` fires only inside the frame), deactivates when played backwards, and
  as play leaves it puts back whether the system was active before (unless it keeps its state). A section without keys
  activates as play crosses its start and keeps it active, deactivates or ends it past its end (`end`), and with
  `age: desired_age` holds the system at the sequence's time since its start. An activation after a deactivation plays
  a new instance.
- **A `system` track naming a `handle`** drives that one emitter of the system, by its handle's name: its activate
  starts that emitter alone (and the system, if it is not playing), its deactivate stops that emitter's spawning. Every
  emitter a track drives so waits, stopped, until its own track activates it; the others play with the system. A handle
  the system lacks fails at play, naming the track, and a `desired_age` on one emitter is not built yet:

  ```yaml
  tracks:
    - { id: t1, kind: system, binding: { spawnable: s1 }, handle: e01, sections: [ { id: a, range: [0, 25], keys: [[1, activate], [13, deactivate]] } ] }
    - { id: t2, kind: system, binding: { spawnable: s1 }, handle: e03, sections: [ { id: b, range: [0, 25], keys: [[13, activate], [24, deactivate]] } ] }
  ```
- **A prefab's nodes** answer `translation`, `rotation`, `scale`, `rotationEulerDeg`, `opacity`, and each material value
  a track keys on them (`scalars: { _Glare: ... }`, a vector or colour by component: `colours: { _UV0Window: { r: ... } }`);
  a spawnable's `nodes` block sets their rest as it spawns.
- **A post volume's** `weight` starts at 1. `unbound` applies it everywhere; otherwise it stands at its `at`, full
  within `radius` and fading over `blend_radius`.
- **What fails at play**, naming it: a kind no spawner makes; a prefab part drawing what is not built yet (several
  materials, an overlay); an inline prefab; a fractional post priority. A dedicated server's sequencer spawns nothing.

## Events: out of a sequence and into it

An `event` track says when something happens, for whoever hears its side: **`rpg`** is the gameplay (a hit window,
super armour, a projectile's launch), **`ours`** the host's presentation (a caption, rumble). A section is an instant
at each of its keys, or a span over its range, begun and ended. Unreal's event track, with a montage's notify states
as spans.

```yaml
tracks:
  - id: t1
    kind: event
    side: rpg
    binding: { role: caster }
    sections:
      - { id: a, keys: [[1200, { event: mymod:release, args: { power: 3 } }], [2400, mymod:recover]] }
      - { id: b, range: [1200, 3000], event: mymod:hit_window, args: { damage: 40 } }
```

```java
sequencer.listen(CgSequenceEventListener.Side.RPG, (player, phase, event, args) -> {
    if (event.equals("mymod:hit_window")) hitWindow(player, phase == CgSequenceEventListener.Phase.BEGIN, (Map<?, ?>) args);
});
sequencer.listen(CgSequenceEventListener.Side.OURS, (player, phase, event, args) -> captions.show(event));
```

- **A key fires once**, in the frame whose range crosses it, however long the frame: a 200 ms frame over it fires it
  once, and the next frame not again. A jump fires nothing it skips. Backward play fires only a track with
  `fire_backwards: true`; `fire_forwards: false` silences forward play.
- **A span begins** in the frame play enters it, a jump landing inside it included, and **ends** as play leaves it or
  the play stops.
- **They are heard after the frame's tracks are applied**, in the order play crossed them: a listener sees the
  frame's values.
- **A dedicated server hears `rpg` alone**; a client hears both. An event track names its side, or it fails at play.
- `args` are the file's, untouched: what the gameplay hearing the name expects.

### Sections an event starts

The gameplay's event into a play starts every section waiting for it, its range counted from that tick:

```yaml
  - id: t2
    kind: property
    binding: { role: caster }
    property: opacity
    sections:
      - { id: c, start: { event: mymod:charge }, range: [0, 400], value: { keys: [[0, 0.0, linear], [400, 1.0, linear]] } }
  - { id: t3, kind: event, side: ours, binding: { role: caster }, sections: [
      { id: d, start: { event: mymod:charge }, range: [0, 500], keys: [[0, mymod:glow], [300, mymod:flash]] } ] }
```

```java
player.event("mymod:charge");        // both play from this frame's tick; a second call starts them over
CgEventTrigger run = player.trigger(track, section);   // its run count and seed, for randomness every replay agrees on
```

- **A second event restarts it**: its keys fire again from the start, its span ends and begins anew.
- **State kinds and `event` play one**; a section of another kind started by an event fails at play, naming it.
- A dedicated server's play ignores `event`, and a late joiner never starts one.

## Montage sections, jumps and seeking

A sequence's `composite_sections` are a montage's: named ranges play moves between. At a section's end play goes on
to its `next`, the rest of the frame played from that section's start, so a section naming itself loops until the
gameplay re-links it; a section with no `next` holds play at its end. Unreal's `FAnimMontageInstance`.

```yaml
composite_sections:
  - { id: q1, name: charge, range: [0, 40], next: q1 }     # loops while the key is held
  - { id: q2, name: fire, range: [40, 90], next: q3 }
  - { id: q3, name: recover, range: [90, 110] }
```

```java
player.nextSection("charge", "fire");   // the key let go: the loop runs out into fire at its end (SetNextSectionName)
player.jumpToSection("fire");           // or at once (JumpToSectionName): fires nothing it skips
String now = player.section();          // "fire"
CgSequencePlayer late = sequencer.play(definition, roles).seek(serverSeconds);   // a late joiner
```

| What | State tracks | Lasting (a system, a span event) | One-shots (event keys, a system's trigger, a notify) |
|---|---|---|---|
| playing | evaluated at the time | active while play is in their section | fired as play crosses them |
| `jumpTo`, `jumpToSection` | evaluated where it lands | kept where they span both times, else started or ended | none fire |
| `seek` | evaluated where it lands | started at their age in the section: a system by `startAt` | only those within `ONE_SHOT_WINDOW` (0.2 s) before it |

- **A frame longer than a looping section** plays each lap's keys, a pass a lap, up to eight a frame.
- `nextSection` and a jump are this play's alone; the file keeps its links.
- A section's `next` is followed forwards only; played backwards, play crosses sections as the timeline lays them.
- One emitter of a system (`handle`) a seek lands inside starts from its beginning, not its age.
- **A jump predicted for an input** goes back where it jumped from if its key is refused:
  `player.jumpToSection("fire", key)`. Over the network, `CgSequenceNet.jump` also asks the server
  ([networking](networking.md#a-sequence-over-the-network)).
- **On a dedicated server** a sequence plays its clock, sections and `rpg` events; tracks that draw (animation,
  systems, state) are never bound there, so a montage's gameplay events fire on the server at their tick.

## Notifies of your own

A notify marks a moment or a span on a move's timeline that does something only your mod knows (Unreal's `AnimNotify`
and `AnimNotifyState`). Register its kind once at init, its properties declared as data:

```java
static final CgType GLOW = CgTypes.global().declare("mymod:notify.glow", CgTypeKind.STRUCT)
        .property("strength", CgValueType.FLOAT).required().register();

CgNotifyKind.builder("mymod:glow").properties(GLOW).writes("tint")
        .behaviour(new CgNotifyKind.Behaviour() {
            @Override public void begin(CgNotifyContext c) {
                float k = ((Number) c.properties().get("strength")).floatValue();
                c.set(0, new float[] {k, k, k, 1}, 0b1111);      // "tint", held while the span lasts
            }
            @Override public void end(CgNotifyContext c) { }    // its tint is restored for it
        })
        .register();
```

```yaml
- id: t9
  kind: notify
  binding: { role: caster }
  sections:
    - { id: n1, range: [6000, 30000], kind: "mymod:glow", properties: { strength: 2 } }   # a span: begin, tick, end
    - { id: n2, at: 42000, kind: "mymod:puff", chance: 0.5 }                              # a trigger: notify, once
```

- **A span** begins as time enters it, ticks each frame inside, and ends as time leaves it, forwards, backwards or by
  a jump. What it `set` on its kind's declared slots is restored as it ends, whether by playing past, a stop or a
  seek back before it.
- **A trigger** (`at`) fires once as play crosses its tick; a jump over it fires nothing.
- **`chance`** draws from the player's seed, so every viewer of one play agrees; a span ignores it, as Unreal's
  notify states do. **`filter: { significance }`** fires only where the sequencer's tier is at least that.
- **Queued by default**: called after the frame's blend. **`branching_point: true`** calls it inside the evaluation.
- **On the client only**: a dedicated server's sequencer runs no behaviour. Gameplay's moments are `event` sections of
  side `rpg`.
- **A kind no one registered fails at load**, naming the section; so do properties its type does not accept.

# A clip on every particle

A particle may play a clip: a prefab ([`prefabs.md`](prefabs.md)) whose nodes move, fade and change their material's
numbers over frames, starting child emitters at clip times. A Rasen Shuriken's blades spin and its rings flash this way,
each particle its own copy at its own age; CC2's particle-played clips are these. Everything runs on the GPU: the clip
is baked once into a buffer, the parts are drawn from it at each particle's age, and its children are spawned from the
particles' steps. Unreal's equivalent is AnimToTexture (an animation baked into a texture, sampled per instance), with
Niagara's GPU emitters spawning from another's particles.

## A clip

```java
static final CgMaterialDefinition BLADE = CgMaterialDefinition.of("mymod:materials/blade",
        "crystalgraphics:shaders/vfx/sequence/part.shader").usage(CgMaterialUsage.MESH_PARTICLES)
        .texture("_MainTex", "mymod:textures/vfx/blade.png").build();

static final CgVfxPrefab SPINNER = CgVfxPrefab.builder("mymod:spinner")
        .node("hub")
        .node("blade").parent("hub").at(1.5f, 0f, 0f).part(BLADE, "mymod:models/blade.glb")
        .node("tip").parent("blade").at(0.8f, 0f, 0f)
        .build();

static final CgVfxNodeClip SPIN = CgVfxNodeClip.of(SPINNER, 60)                     // 60 frames, 30 a second
        .channel("hub", Channel.ROTATION_Y, Interp.LINEAR, 0, 0f, 60, 720f)         // (frame, value) pairs
        .channel("blade", Channel.OPACITY, Interp.LINEAR, 0, 1f, 45, 1f, 60, 0f)
        .material("blade", "_UV0Window.x", Interp.CONSTANT, 0, 0f, 15, 0.25f, 30, 0.5f) // a UV window stepping on
        .rest("blade", "_Glare", 0.6f)                                               // held all clip long
        .burst("tip", SPARKS, 20, 8)                                                 // 8 sparks at the tip on frame 20
        .stream("tip", SMOKE, 10, 50, 30f)                                           // 30 a clip second, frames 10 to 50
        .build();
```

| Call | Keys |
|---|---|
| `channel(node, channel, interp, keys...)` | a transform channel (`LOCATION_X` to `SCALE_Z`, rotations in degrees) or `OPACITY` |
| `material(node, slot, interp, keys...)` | one of the material's numbers by its slot (`CgVfxNodeClip.MATERIAL_SCALARS`): a scalar (`_Glare`), or a vector's component (`_UV0Window.x`) |
| `rest(node, scalar, value)` | a material number held for the whole clip |
| `burst(node, child, frame, count)` | `count` children of `child` born at the node on `frame` |
| `stream(node, child, from, until, perSecond)` | children born at the node from frame `from` to `until`, `perSecond` a clip second |

- **Rotations are Euler degrees, x then y then z intrinsic** (CC2's order, JOML's `rotateXYZ`). A channel no key sets is
  the node's rest; before its first key a channel holds that key's value, after its last the last's.
- **`LINEAR` keys interpolate, `CONSTANT` keys step** at each key's frame.
- **A material number no key sets is the material's own**: its value, which the clip leaves alone.

## A clip from a sequence file

A `sequence` renderer names a sequence each particle plays, and the engine bakes it into the clip: Niagara's component
renderer playing a sequence, compiled to GPU pieces. Each frame of the clip, 30 a second, is the sequence's own
evaluation of its tracks at that tick, so the particle moves, fades and keys its material as the sequencer would play
the sequence.

```yaml
# assets/mymod/sequences/spin.yaml
_v: 1
tick_resolution: 30
display_rate: 30
range: [0, 60]
spawnables:
  - { id: s1, name: spinner, prefab: mymod:spinner }
tracks:
  - { id: t1, kind: transform, binding: { spawnable: s1, node: hub }, sections: [ { id: a, range: [0, 60],
      rotation: { y: { keys: [[0, 0, linear], [60, 12.566, linear]] } } } ] }
  - { id: t2, kind: property, binding: { spawnable: s1, node: blade }, property: opacity, sections: [ { id: b,
      range: [0, 60], value: { keys: [[0, 1, linear], [45, 1, linear], [60, 0, linear]] } } ] }
  - { id: t3, kind: material, binding: { spawnable: s1, material: blade }, sections: [ { id: c, range: [0, 60],
      colours: { _UV0Window: { r: { keys: [[0, 0, constant], [15, 0.25, constant], [30, 0.5, constant]] }, b: { default: 1 } } } } ] }
```

```yaml
# in the emitter: each particle plays it, at its age times the rate
renderers:
  - { id: r1, kind: sequence, sequence: mymod:spin, play_rate: 1.0 }
```

```java
CgSequenceDefinition spin = (CgSequenceDefinition) deps.definition(CgSequenceKinds.SEQUENCE, "mymod:spin");   // in a compiler
CgVfxEmitter shurikens = CgVfxEmitter.builder("shurikens").sequence(CgVfxClipBake.bake("shurikens", spin))...build();
```

- **It holds one `prefab` spawnable**, and `transform`, `property` (opacity) and `material` tracks on its nodes or
  materials. Any other spawnable or track fails at load, naming the sequence and the track.
- **A material track bound to a material keys every node drawing it.** Each value it keys must have a clip slot
  (`MATERIAL_SCALARS`), or no shader could read it: one without fails at load, by name.
- **A rotation is the sequence's**, through its units (`rotation: { order: xyz, intrinsic: true }`, angles in its
  `angles`); a channel keys whole frames, so a curve between two of them is sampled, never reshaped.
- **A node a track binds must be its prefab's**: one the prefab lacks fails at load, naming both.

## Playing it on particles

```java
static final CgVfxEmitter SHURIKENS = CgVfxEmitter.builder("shurikens").sequence(SPIN)   // or sequence(SPIN, 0.5f)
        .state(CgVfxEmitterState.ONCE).capacity(4).burst(0f, 3).life(2f, 2f).size(1.5f, 1.5f)
        .shape(CgSphere.of(2)).speed(1f, 2f)
        .build();

static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("mymod:shurikens"))
        .with(CgVfxKit.of("mymod:shurikens").emitter(SHURIKENS).build(), SPINNER.kit())
        .build();

VFX.play(new CgVfxSystemInstance<>(LOOK, x, y, z));
```

- **The children draw through their own renderers**, given where they are made (`SPARKS` and `SMOKE` each with a
  `renderer(...)`), and are not listed in the look: a system instance starts every emitter its look lists.

- **The look draws the prefab's kit**: each part is drawn with its material on every particle, where its node is at the
  particle's age times the play rate, held at the clip's last frame.
- **A part is placed from its particle**: at the particle's position, turned by its mesh orientation
  (`initial_mesh_orientation`, `mesh_rotation_rate`) or else with its effect, sized by its size and scale over life as a
  mesh particle is, then by the clip's own channels.
- **A clip that spawns children takes neither turn nor size over life**: its children are born at the node where the
  particle stands at its birth size, so an emitter that turns or scales its particles and plays such a clip fails at
  build, by name.
- **A clip particle is a pure function of its seed and age**: its emitter takes no module reading the world (a floor,
  blocks, depth, entities, neighbours, gas, a grid), and `build()` throws naming one. Its children take any: sparks the
  clip throws can bounce.
- A clip's bursts and streams are events after the emitter's own, and count toward `CgVfxEvent.MAX_EVENTS`.

## Events at a node, and children's children

Any event may fire at a node of the emitter's clip rather than where the particle is: its place the node's at the
particle's age, its normal the node's +Y. A rate may fire only inside a window of the particle's age:

```java
.event(CgVfxEvent.onAge(1f).at(SPINNER.node("tip")).readback(4))                  // where the tip is at 1 s
.event(CgVfxEvent.every(0.05f).between(0.3f, 1.5f).spawn(SMOKE, 1).at(SPINNER.node("tip")))
```

Children may have children of their own, two levels in all: a spark the clip throws may burst into embers when it
dies, and the embers may report their deaths but spawn nothing.

```java
static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers").life(0.3f, 0.5f).build();
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").life(0.5f, 0.8f)
        .module(new CgVfxModule.Gravity(9.8f)).event(CgVfxEvent.onDeath().spawn(EMBERS, 3)).build();
```

- **An event at a node needs a node the clip bakes**: one with parts, or one a burst or stream starts at; the emitter
  throws otherwise. A node of a clip on any other renderer throws too.
- **A window is a rate's alone.** Its periods count from the window's start, never firing at it.
- A grandchild is keyed by `CgVfxEvent.grandchildKey`, its parent's whole id hashed in.

## Writing a part's material

`crystalgraphics:shaders/vfx/sequence/part.shader` is CC2's basic part: its texture through its first UV window,
tinted, faded by its node's opacity, alpha-tested by `_Clip`, `_Glare` of it glowing, premultiplied. A material of your
own draws on clip particles by declaring the `SEQUENCE` kind and placing its vertices by the node:

```glsl
#pragma cg_feature FX_ON_MESH                      // drawn by a prefab instance too
#pragma cg_feature FX_ON_SEQUENCE
#include "crystalgraphics:shaders/lib/vfx/fx_sequence.glsl"

Properties { _U0_LocX ("Where its UV window starts", float) = 0.0 }   // named as CC2 names them
Buffers { FX_CLIPS ("Baked clips", vec4, readonly) }

void vertex(out v2f o) {
#ifdef FX_ON_SEQUENCE
    vec4 world = FX_SEQUENCE_TO_WORLD * vec4(cg_Position, 1.0);     // the particle, then the node at its age
    o.opacity = FX_SEQUENCE_OPACITY;                                 // the node's own
    o.u = FX_SEQ_SCALAR(0, _U0_LocX);                                // the clip's, else the material's
#else
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
#endif
    ...
}
```

- **Declare `FX_ON_SEQUENCE`**: a material drawn on a clip particle without it throws at its first draw, since its part
  would sit at the particle whatever the clip said.
- **Declare `FX_CLIPS` in its `Buffers { }`**: the engine binds the baked clips to every material that does.
- `FX_SEQ_SCALAR(i, own)` takes the scalar's index in `MATERIAL_SCALARS` and the material's own value.
- `CG_OBJECT_CUSTOM1` is the particle's (life 0 to 1, seed, opacity, heat), as for a `MESHES` particle.

## Who sees it

A move plays on every client that can see it. Its world plays for everyone: systems, prefabs, sounds, and post
with a `radius`. What it does to a viewer's own screen applies only to the players it is about, its caster and its
target, so a remote player's ultimate never takes your camera or darkens your shaders. That covers its camera
(`camera_animation`), its unbound post, and its `global` and `environment` tracks.

Tell the sequencer who the viewer is:

```java
sequencer.viewer(target -> target instanceof Fighter f && f.entityId() == localPlayerId);

player.involved();   // true when its caster or its target is the viewer's character
```

- Unset, every play involves the viewer: right with one player, or in a preview.
- A role of kind `character` never involves the viewer.

A section can play on the caster's own screen alone, as Kakarot's lens effects do:

```yaml
- { id: s1, range: [0, 2000], condition: { kind: crystalgraphics:relation, role: caster, is: [self] } }
```

- `self` holds when the role is the viewer's character, or for everyone with no viewer set.
- `ally` and `enemy` fail at load: they are not played yet.

An effect's flash is the viewer's too. It fades on through its character's hitstop, even though the effect itself
holds. Weigh it by a timeline on the frame clock, never from `tick`:

```java
flash = openVolume(10, new CgPostSettings().flash(1.5f), 0, 0, 0).radius(24f);
flash.timeline(s -> curve(FLASH).at(Math.min(s / 0.6f, 1f)));
```

## Things to know

- **The clip is baked once**, on the CPU, the first time an emitter plays it: each baked node's matrix from the clip's
  root, its opacity and its material numbers, a vec4 a frame (`CgVfxClipAtlas`). A part reads two frames and mixes them;
  a row any `CONSTANT` channel feeds takes the earlier.
- **A matrix is mixed between frames**, as AnimToTexture's is, so a node turning more than about 30 degrees a frame
  shrinks a little between them. Key fast spins on more frames.
- Every part of every node is one instanced draw a material, culled on the GPU, its bounds grown by how far the clip
  carries it.
- `--mode=vfx-sequence` is the gate: the part where its node is, a node's event, children and grandchildren, at every
  tier and on Vulkan.
