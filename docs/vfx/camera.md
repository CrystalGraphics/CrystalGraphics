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

## Shakes

A shake is a file under `cameras/shakes/`: a pattern, and where it is felt. It works like Unreal's camera shakes:

```yaml
# assets/mymod/cameras/shakes/rumble.yaml
_v: 1
scale: 1
single_instance: false
pattern:
  kind: perlin                    # or wave, sample_hold, trauma, composite
  duration: 0.6
  blend_in: 0.05
  blend_out: 0.2
  location: { z: { amplitude: 0.05, frequency: 12 } }
  rotation: { pitch: { amplitude: 1.5, frequency: 9 }, roll: { amplitude: 0.8, frequency: 7 } }
play: { inner: 4, outer: 24, falloff: 1, space: camera_local }
```

```java
CgCameraShakeDefinition rumble = (CgCameraShakeDefinition) load.definition();
CgShakePlay play = rumble.play(x, y, z, 1f, seed);   // felt from this frame
play.stop(false);                                     // blends out; an endless shake needs this
```

Patterns:

| `kind` | Does |
|---|---|
| `perlin` | smooth noise on each axis |
| `wave` | a sine on each axis; `initial_offset: zero` starts it at 0 rather than a random phase |
| `sample_hold` | every `interval`, each axis jumps to a new value within its amplitude and holds it |
| `trauma` | `CgCameraShake`'s trauma, punch, tremor and kick, felt as it arrives |
| `composite` | its `children` added together; it lasts as long as the longest |

Play spaces:

- `camera_local`: `x` forward, `y` right, `z` up from the camera, and the turn in the camera's own frame.
- `world`: along the world's axes, in the asset's units. The turn is about the world's axes, so a world pitch is a
  roll for a camera looking along `y`.

Things to know:

- Within `inner` of the point it is felt in full. From there it fades to nothing at `outer`, raised to `falloff`.
  An `outer` of 0 is felt everywhere.
- A `single_instance` shake that is already playing restarts where it is asked again, rather than playing twice.
- The player's screen-effects setting scales every shake.
- `sequence` patterns and the `user_defined` space fail at load: they are not played yet.

Your mod's own pattern implements `CgShakePattern`, and its source implements `CgShakeSource`:

```java
CgShakePlay play = new CgShakePlay(mySource, myPattern, new CgCameraShakeState(0.1f, 0.3f, 1.5f));
play.start(x, y, z, 1f);
```

## Holding the camera

While anything above the host's camera moves the view (a context with a rig, a modifier, a sequence's camera), the
composed pose is handed to the game every frame after `SHAKE`, and the camera is **yours alone** until it lets go:

- the game renders from your pose (position, turn, field of view), its culling and sound with it;
- the player's mouse look, movement and every key binding are locked out, and the hotbar cannot scroll;
- the first-person hands are not drawn, and the view is first person;
- nothing is targeted: no block outline, no block or entity under the crosshair;
- a pause keeps the camera held, frozen on its last pose until the game resumes;
- every other mod's camera changes are overridden: yours are written last;
- Escape and any open screen keep working.

The frame nothing moves the view, the camera is given back exactly as it was: the player's view, perspective, HUD and
the keys still held. If your mod stops updating the camera system (a crash, a stalled play), the game takes it back
by itself within a quarter of a second.

What else the viewer sees is the play's, as Unreal's playback settings: both off unless the caller asks.

```java
CgSequencePlayer move = sequencer.play(ultimate, roles);                         // a move in battle: HUD and player stay
CgSequencePlayer cut = sequencer.play(intro, roles)
        .playbackSettings(new CgSequencePlaybackSettings(true, false));        // a story cut: the HUD hidden
if (cut.playbackSettings().hideHud()) myHud.hide();                            // your mod's own HUD follows the flag
```

A context or modifier of your own asks the same with flags:

```java
cameras.push(new CgCameraContext("mymod:intro").director(director).flags(CgHostCamera.HIDE_HUD));
```

- `hide_hud` hides the game's HUD alone. Your mod's HUD is yours to hide, from the same flag.
- `hide_player` needs a host that can hide the player (`CgCamera.capabilities()` has `HIDES_PLAYER`); elsewhere the
  log says so once and the player stays drawn.
- A rig's `requires` names what it cannot play without (`rotation`, `roll`, `fov`, `position`, `lock`), and its
  `on_missing` what a host without them gets: `refuse` (the host keeps its camera), `drop_roll` (played level),
  `offset_only` (the host's own position, your turn and field of view).
- The game only has the world around the player, as far as its render distance. A held position stays horizontally
  within that distance, less one chunk, of the player's view. A rig that flies further is held at the edge, and the
  log says so once. Keep a shot within the render distance of the player it frames.

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
- Holding the camera locks the player's input on the client only. Refusing a locked player's moves on the server,
  or keeping them safe during a move, is your gameplay's to decide.
- Controller mods that read raw input rather than key bindings are not locked.
- On Minecraft 1.7.10, vanilla's own particles face the player's look rather than the held camera's.
