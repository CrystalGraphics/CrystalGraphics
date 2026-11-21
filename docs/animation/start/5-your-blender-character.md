# Tutorial 5: your Blender character

**What you will have at the end:** a character rigged and animated in Blender, exported once as glTF, playing every
action as a clip, with its feet marked so its walk and run blend in step and a lunge that carries it by its own root
motion.

## 1. Rig

- **Deform bones only** in the export: control bones and IK targets stay in Blender.
- **One root bone on the floor**, named `root`, the parent of everything. A lunge's travel is keyed on it.
- **Name bones the standard way** ([`../authoring/naming-conventions.md`](../authoring/naming-conventions.md)):
  `pelvis`, `spine_01`, `head`, `upperarm_l`, `hand_r`, `thigh_l`, `foot_r`. Other characters' clips then retarget onto
  yours with no rig file ([tutorial 6](6-someone-elses-animations-on-your-model.md)), and the humanoid template's
  masks find their bones.
- **Face +Z** (the glTF exporter's forward with +Y up): what Minecraft's entity faces.

## 2. Actions

One action a clip, named as your graph will name it: `idle`, `walk`, `run`, `lunge`. In the NLA editor, push each down
as its own track so the exporter writes each as an animation.

## 3. Mark the feet

Note the frame each foot lands on in the walk and the run, then mark them in a clip asset beside the model, in
seconds:

```yaml
# assets/mymod/clips/models/hero/walk.yaml
_v: 1
sync_markers: [ { name: foot_l, at: 0.0 }, { name: foot_r, at: 0.5 } ]
```

Blender's glTF exporter does not write timeline markers. A `.glb` whose animations carry `extras.markers`
(`{ "name": "sync=foot_l", "time": 0.0 }`, from an export script) is read the same way.

## 4. Export

*File → Export → glTF 2.0*: format **glTF Binary (.glb)**, *+Y Up* on, *Animation* on with *NLA strips*. Save it as `assets/mymod/models/hero.glb`.

```java
CgSkeletalMesh hero = CgMeshLoader.skeletal("mymod:models/hero.glb");    // every action a clip: hero.sequence("run")
```

## 5. The lunge's root motion

```yaml
# assets/mymod/clips/models/hero/lunge.yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
```

Played as a montage on an actor with `root_motion: body`, the lunge carries the entity by what the clip moves
([`../guides/root-motion-moves-the-entity.md`](../guides/root-motion-moves-the-entity.md)).

## 6. Try it

Use tutorial 2's graph and actor with this model. Walk into a run: the feet stay in step through the blend.

## What goes wrong

| You see | Because |
|---|---|
| A marker named in a load error | only `sync=` markers are read from glTF; a clip's sounds and effects go in a notify sequence ([`../guides/notifies-and-events.md`](../guides/notifies-and-events.md)) |
| A vertex "with more than four bones" | limit weights to 4 per vertex in Blender (*Weights → Limit Total*) |
| The model lies on its back | the armature's rotation was not applied: apply transforms before export |
| The lunge snaps back to its start | the clip asset's `root_motion` is missing, or nothing plays it as a montage |

## Where next

[Tutorial 6](6-someone-elses-animations-on-your-model.md): another character's clips on this model. Before
release, run through [`../authoring/checklist.md`](../authoring/checklist.md).
