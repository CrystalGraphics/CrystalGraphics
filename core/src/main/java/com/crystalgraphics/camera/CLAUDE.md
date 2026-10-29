# camera — a viewer's camera, as Unreal's Gameplay Cameras

Contexts, directors, rigs, blend stacks and modifiers, composed into one pose a frame. **Using it:
`docs/vfx/camera.md`.** Shakes are `vfx.camera`'s (`CgShakeRuntime`), summed into the host's offset; the host's slot
is `platform.world.CgHostCamera`.

```java
CgCameraSystem cameras = CgWorld.client().add(new CgCameraSystem());
cameras.push(new CgCameraContext("mymod:move").director(CgCameraDirector.single(new CgCameraRig(definition))));
```

## Where things go

| Package | Owns |
|---|---|
| `camera` | `CgCameraSystem` (`FCameraSystemEvaluator`: the context stack, the director, both stacks), `CgCameraContext`, `CgCameraDirector` (single, priority), `CgCameraRig` (a rig running: its parameters in host units, its nodes), `CgCameraNode` (a node's evaluator: the SPI), `CgCameraBlendStack` (the transient stack), `CgCameraBlend` (blend kinds and a running blend), `CgCameraModifier` (the persistent stack), `CgCameraPose` |
| `camera.node` | `CgCameraNodes`: the node kinds by name, each following its Unreal node evaluator |
| `asset.camera` | `CgCameraRigDefinition`: the rig file compiled, its node kinds checked against `CgCameraKinds.NODES` |
| `sequence.camera` | `CgSequenceCameras` (installs them), `CgCineCamera` and `CgRigCamera` (the `camera` spawnable, cine or rig), `CgCameraCutEvaluator` (`camera_cut`) and `CgCameraAnimationEvaluator` (`camera_animation`) |

## Rules

- **Unreal decides behaviour**: each node, blend and stack step cites the Gameplay Cameras source it follows. A pose
  blends as `FCameraPose::LerpAll`, its turn as `FMath::Lerp<FRotator>`, the shortest way on each axis.
- **The pose's turn is Unreal's through the host's axes**: `+x` is forward and `+y` up, so a rig written in Unreal's
  terms means the same here. `CgCameraPose.rotator` reads a turn back as Unreal's pitch, yaw and roll.
- **Nothing plays as something else**: a node kind, blend kind, field or condition not played fails at load or build,
  naming it.
- **The frame path allocates nothing**: nodes, the stacks and modifiers keep their own scratch.
- **The viewer's clock**: blends and modifiers run on the frame's dt, never a character's.
