# Clips: markers, root motion and additive clips

A **clip** is one animation: a run, a punch, a flinch. Besides its keys, a clip can carry:

- **sync markers**: named moments (`foot_l`, `foot_r`) that clips of one group line up on, so a walk and a run blend
  with their feet in step;
- **root motion**: the clip moves the character, rather than stepping out of its own collision;
- **an additive base**: the clip is stored as differences, so it layers over whatever else plays (a flinch, a breath);
- **its notify sequence**: the sounds, effects and events that play with it.

These are Unreal's `UAnimSequence` settings, with the same names.

## In Java

```java
CgSkeletalMesh hero = CgMeshLoader.skeletal("mymod:models/hero.glb");

// Sync markers: the feet of a run
CgAnimSequence run = hero.sequence("run").with(CgClipSettings.NONE.withMarkers(List.of(
        new CgSyncMarker("foot_l", 0.10f), new CgSyncMarker("foot_r", 0.43f))));

// Root motion: the lunge carries the character; the root bone stays at its rest while it plays
CgAnimSequence lunge = hero.sequence("lunge").with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));

// An additive flinch, as differences from the skeleton's rest pose
CgAnimSequence flinch = hero.sequence("flinch").with(CgClipSettings.NONE.withAdditive(CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0)));
```

Reading them each frame:

```java
// What a lunge moved this frame, in the mesh's space: hand it to whatever moves the entity
Matrix4f step = lunge.extractRootMotion(hero.skeleton(), time, dt, false, scratch);
lunge.sample(pose, time, true);                      // the pose, its root held at its lock

// A flinch at 60% over whatever the body is doing
flinch.sampleAdditive(delta, base, flinchTime);
body.accumulateAdditive(delta, 0.6f);

// The markers a run passed this frame
run.markersPassed(previousTime, time, dt, (name, deltaWhenPassed) -> footstep(name));
```

## In data

A clip's settings live in a `clip` asset at `clips/<the clip's id path>.yaml`. A clip with no file has none.

```yaml
# assets/mymod/clips/models/hero/lunge.yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
```

```yaml
# assets/mymod/clips/models/hero/run.yaml
_v: 1
sync_markers:
  - { name: foot_l, at: 0.10 }
  - { name: foot_r, at: 0.43 }
```

```yaml
# assets/mymod/clips/models/hero/flinch.yaml
_v: 1
additive: { type: local_space, base: ref_pose }
```

| Key | What | Unreal |
|---|---|---|
| `sync_markers` | `[{ name, at }]`, `at` in seconds | `AuthoredSyncMarkers` |
| `root_motion` | `enabled`, `root_bone` (default bone 0), `lock` (`ref_pose`, `anim_first_frame`, `zero`), `force_root_lock` | `bEnableRootMotion`, `RootMotionRootLock`, `bForceRootLock` |
| `additive` | `type` (`none`, `local_space`, `mesh_space`), `base` (`ref_pose`, `frame`, `clip_frame`), `base_clip`, `base_frame` | `AdditiveAnimType`, `RefPoseType`, `RefPoseSeq`, `RefFrameIndex` |
| `notifies` | the notify sequence, when not the one under the clip's own id | — |

## From Blockbench (GeckoLib)

Write `sync=<name>;` in a key of the animation's `timeline`:

```json
"timeline": { "0.1": "sync=foot_l;", "0.43": "sync=foot_r;" }
```

It becomes a sync marker on the clip and never an event. The timeline's other instructions are its notify sequence
(`notify=`, `begin=`/`end=`), or your mod's own events (`hit;`).

## From Blender (glTF)

An animation's `extras.markers` holds its markers, by frame at the clip's rate or by time in seconds:

```json
"extras": { "markers": [ { "name": "sync=foot_l", "frame": 3 }, { "name": "sync=foot_r", "time": 0.43 } ] }
```

## What goes wrong

- **A sync marker past the clip's end** fails at load, naming the clip and the marker.
- **`additive: { type: mesh_space }`** fails at load: mesh-space additives are an aim offset's, which nothing plays yet.
- **`base: clip_frame` without `base_clip`**, or with a clip that does not resolve, fails at load.
- **A glTF marker other than `sync=`** (`notify=`, `begin=`, an event) fails at load, naming it: glTF clips do not
  have a notify sequence yet.
- **Root motion with nothing extracting it**: the root plays as keyed unless `force_root_lock` is set. Sample with
  `sample(pose, t, true)` where you extract it.
