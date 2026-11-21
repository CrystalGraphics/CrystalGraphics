# Clips: markers, root motion and additive clips

**What you will have:** a run whose feet line up with the walk it blends with, a lunge that carries the character,
and a flinch that layers over whatever the body is doing, each set on the clip once, in a file or in Java.

## In plain words

A **clip** is one recorded movement, like one flip book. Some clips carry notes: **sync markers** say where the feet
land; **root motion** says the clip itself moves the character across the ground (a lunge); an **additive** clip is
only a change to add on top of another pose.

A **clip** is one animation: a run, a punch, a flinch. Besides its keys, a clip can carry:

- **sync markers**: named moments (`foot_l`, `foot_r`) that clips of one group line up on;
- **root motion**: the clip moves the character, rather than stepping out of its own collision;
- **an additive base**: the clip is stored as differences, so it layers over whatever else plays;
- **its notifies**: the sounds, effects and gameplay events that play with it
  ([`notifies-and-events.md`](notifies-and-events.md)).

## The code

A clip's settings live in a `clip` asset at `clips/<the clip's id path>.yaml`. A clip with no file has none.

```yaml
# assets/mymod/clips/models/hero/run.yaml
_v: 1
sync_markers:
  - { name: foot_l, at: 0.10 }
  - { name: foot_r, at: 0.43 }
```

```yaml
# assets/mymod/clips/models/hero/lunge.yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
```

```yaml
# assets/mymod/clips/models/hero/flinch.yaml
_v: 1
additive: { type: local_space, base: ref_pose }
```

The same in Java:

```java
CgSkeletalMesh hero = CgMeshLoader.skeletal("mymod:models/hero.glb");

CgAnimSequence run = hero.sequence("run").with(CgClipSettings.NONE.withMarkers(List.of(
        new CgSyncMarker("foot_l", 0.10f), new CgSyncMarker("foot_r", 0.43f))));
CgAnimSequence lunge = hero.sequence("lunge").with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
CgAnimSequence flinch = hero.sequence("flinch").with(CgClipSettings.NONE.withAdditive(CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0)));
```

| Key | Means |
|---|---|
| `sync_markers` | `[{ name, at }]`, `at` in seconds |
| `root_motion` | `enabled`, `root_bone` (bone 0 unless said), `lock` (`ref_pose`, `anim_first_frame`, `zero`), `force_root_lock` |
| `additive` | `type` (`none`, `local_space`, `mesh_space`), `base` (`ref_pose`, `frame`, `clip_frame`), `base_clip`, `base_frame` |
| `notifies` | the clip's notify sequence, when not the one under the clip's own id |

## Variations

### Markers from Blockbench (GeckoLib)

Write `sync=<name>;` in a key of the animation's `timeline`:

```json
"timeline": { "0.1": "sync=foot_l;", "0.43": "sync=foot_r;" }
```

It becomes a sync marker and never an event. The timeline's other instructions are the clip's notifies.

### Markers from Blender (glTF)

An animation's `extras.markers` holds them, by frame at the clip's rate or by time in seconds:

```json
"extras": { "markers": [ { "name": "sync=foot_l", "frame": 3 }, { "name": "sync=foot_r", "time": 0.43 } ] }
```

### Reading a clip by hand

For a tool, or a character with no graph:

```java
Matrix4f step = lunge.extractRootMotion(hero.skeleton(), time, dt, false, scratch);   // what it moved this frame
lunge.sample(pose, time, true);                      // the pose, its root held at its lock

flinch.sampleAdditive(delta, base, flinchTime);      // a flinch at 60% over whatever the body does
body.accumulateAdditive(delta, 0.6f);

run.markersPassed(previousTime, time, dt, (name, deltaWhenPassed) -> footstep(name));
```

In a graph, all of this is done for you: the players sync, montages carry root motion, and an apply-additive node
adds the flinch ([`additive-and-aim-offsets.md`](additive-and-aim-offsets.md)).

## What goes wrong

- **A sync marker past the clip's end** fails at load, naming the clip and the marker.
- **`base: clip_frame` without `base_clip`**, or with a clip that does not resolve, fails at load.
- **`additive: { type: mesh_space }`** is an aim offset's sample: only an aim offset plays it.
- **A glTF marker other than `sync=`** (`notify=`, `begin=`, an event) fails at load, naming it: glTF clips carry
  no notify sequence of their own yet. Write the notifies as a sequence and name it with the clip asset's `notifies`.
- **Root motion with nothing extracting it**: the root plays as keyed unless `force_root_lock` is set.

## Where next

- A clip's sounds, effects and hit windows: [`notifies-and-events.md`](notifies-and-events.md).
- Root motion's modes: [`root-motion.md`](root-motion.md).
- Feet in step across a blend: [`sync-groups.md`](sync-groups.md).
