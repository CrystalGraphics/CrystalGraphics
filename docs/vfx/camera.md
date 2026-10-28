# Cameras

Your mod moves the viewer's camera through one camera system. It works like Unreal's Gameplay Cameras: each viewer
has a **stack of contexts**, with the host's own camera at the bottom. The context on top picks a **rig** through its
director. Rigs **blend** in over the camera below them and back out. **Modifiers** are added on top of whatever the
rigs give.

## A camera system

```java
CgCameraSystem cameras = CgWorld.client().add(new CgCameraSystem());
cameras.host(pose -> {                          // the host's own camera, read each frame
    pose.location(eye.x, eye.y, eye.z).fov(hostFov);
    pose.rotation().set(hostTurn);
});

// after the frame's SHAKE group:
if (cameras.controls()) apply(cameras.pose()); // only when a rig or a modifier moved it
```

A pose (`CgCameraPose`) has a position in blocks, a turn and a horizontal field of view in degrees. Its turn takes
`+x` to where it looks and `+y` to its up.

## Rigs

A rig is a file under `cameras/rigs/`. It is a list of nodes run in order, each changing the pose the nodes before it
left:

```yaml
# assets/mymod/cameras/rigs/over_shoulder.yaml
_v: 1
parameters:
  distance: { type: float, quantity: length, default: 400 }
  fov:      { type: float, quantity: angle, default: 60 }
root:
  - { id: on,   node: attach_to, target: { role: caster, socket: head } }
  - { id: arm,  node: boom_arm, offset: [-400, 60, 20], additive_rotation: true }
  - { id: lens, node: field_of_view, fov: { bind: rig.fov } }
enter_transitions: [ { conditions: [], blend: { kind: smooth, time: 0.4 } } ]
exit_transitions:  [ { conditions: [], blend: { kind: linear, time: 0.3 }, to: host } ]
```

Play it by pushing a context whose director runs it:

```java
CgCameraRig rig = new CgCameraRig(definition).parameter("fov", 45);
CgCameraContext move = new CgCameraContext("mymod:ultimate")
        .director(CgCameraDirector.single(rig))
        .roles(role -> role.equals("caster") ? casterAttachment : null);
cameras.push(move);      // blends in by its enter transition
// ...
cameras.remove(move);    // blends back to the host by its exit transition
```

Several rigs by priority, the highest running:

```java
CgCameraDirector.Priority modes = CgCameraDirector.priority();
move.director(modes);
modes.add(aim, 10).add(follow, 0);   // aim runs
modes.remove(aim);                   // follow blends back in
```

### Nodes

| Node | Does |
|---|---|
| `attach_to` | puts the camera at a role (or its socket); `rotation: true` takes its turn too |
| `attach_to_group` | puts the camera between several roles by their weights |
| `attach_between` | puts the camera part of the way (`share`) from one role to another, the gap first clamped to `max_distance` (0 for none); its turn the first role's |
| `set_location` | sets the position: in the camera's space (an offset), the context's, or the world's |
| `set_rotation` | turns the camera: after its own turn, in the context's, or as given |
| `offset` | moves and turns it in the camera's space, a context's, or the world's |
| `boom_arm` | holds it at an offset along an arm from where it stands, turned by its own turn when `additive_rotation` |
| `field_of_view` | sets the FOV |
| `array` | groups children, run in order |

A node kind with no evaluator fails when its rig is built, naming it. Your mod adds a kind of its own like this:

```java
CgCameraNodes.register("lift", (node, rig) -> {
    CgCameraRig.Value height = rig.value(node, "height");
    return (run, pose) -> pose.location().y += height.number();
});
```

### Blends

| `kind` | The factor over `time` |
|---|---|
| `pop` | a cut |
| `linear` | t / time |
| `smooth` | `type: smooth_step` (the default) or `smoother_step` |
| `easing` | one of Sequencer's easings: `sin_in_out`, `quad_in`, `cubic_out`, `expo_in_out`, `circ_in`... |

- The transition is the leaving rig's first exit, then the arriving rig's first enter; with neither, a cut.
- A blend that breaks off another part-way takes that share of its own time.
- The time may be `{ bind: rig.blend_in }`, read when the blend starts.
- `orbit`, `location_rotation` and transition conditions fail at load: they are not played yet.

## Modifiers

A modifier changes the pose after the rigs, every frame:

```java
cameras.add(new CgCameraModifier() {
    float age;
    public void apply(CgCameraPose pose, float dt) { age += dt; pose.fov(pose.fov() + 10 * (1 - age / 0.3f)); }
    public boolean done() { return age >= 0.3f; }
});
```

## A sequence's camera and its post

A sequence that has a camera (a `camera_animation` or `camera_cut` track) makes its unbound `post` spawnables that
camera's post settings. They blend in a viewer's view only at the weight that view goes through the camera, so a
viewer the move does not involve keeps its colours:

```java
CgPostVolumeSpawner.install(sequencer, cameras);
cameras.involved(play -> involvesMe(play));          // the viewers a play's camera is for; all, until set
float w = cameras.weightOf(play);                    // 0 to 1: how much of my view is that play's camera
```

## A camera animation in a sequence

A `camera_animation` track plays another sequence's camera on the viewer's camera, for its section. It is an
offset from that camera, as Unreal's camera animations play in camera space:

```yaml
# the move's sequence
tracks:
  - id: k1
    kind: camera_animation
    sections:
      - { id: s1, range: [0, 259939], animation: mymod:cameras/cut_in }
```

```yaml
# assets/mymod/sequences/cameras/cut_in.yaml: a camera and its keys
_v: 1
tick_resolution: 60000
display_rate: 30
range: [0, 260000]
spawnables:
  - { id: c1, name: camera, camera: { cine: { fov: 20 } } }
tracks:
  - { id: t1, kind: transform, binding: { camera: c1 }, sections: [ { id: a, range: [0, 260000], translation: [ ... ], rotation: [ ... ] } ] }
  - { id: t2, kind: property, property: fov, binding: { camera: c1 }, sections: [ { id: b, range: [0, 260000], value: { keys: [ ... ] } } ] }
```

```java
// a client, once: the kind, the camera spawner, and where camera sequences come from
CgCameraAnimationEvaluator.install(sequencer, cameras, id -> loadedSequence(id));
```

- The camera's moves count from its first key. At its first key the viewer's camera is where it was. After that it
  moves and turns in the camera's own space, and its FOV changes by the keys' change.
- The camera sequence must be loaded when its section starts, or the play fails, naming it.
- It ends with its section.

Bound to a role, the animation is a camera of its own placed at that role, rather than an offset on yours. A
cut-in framed on its caster is written like this:

```yaml
  - id: k1
    kind: camera_animation
    binding: { role: caster, node: Mesh }      # the caster's prefab node: the keys are in its space
    sections:
      - { id: s1, range: [0, 259939], animation: mymod:cameras/cut_in }
```

- The camera is the node's world transform times the camera's keys, and its FOV is the keys' own.
- It cuts in at its section's start and back to the camera below at its end.

## Things to know

- Everything runs on the viewer's clock, never a character's: hitstop does not stop a blend.
- Rigs run in `CAMERA` and modifiers in `SHAKE`; the pose is ready after `SHAKE`.
- The camera system is a client world's. A server schedules nothing for it.
