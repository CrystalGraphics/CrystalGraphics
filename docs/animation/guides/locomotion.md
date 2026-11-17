# Locomotion: feet that never slide

**What you will have:** a character whose feet stay planted at every speed, from a crawl to a sprint on a speed
potion, running sideways and backwards with its chest still ahead, turning on the spot without skating, and stopping
exactly where it means to.

## In plain words

A run clip is recorded at one speed, going straight ahead. The game moves the character at any speed, in any
direction, and turns it on the spot. Played as recorded, the feet slide over the ground as if on ice. The tools here
bend the clip, as it plays, to fit how the character really moves:

| Tool | In plain words |
|---|---|
| **Stride warping** | longer steps when the character goes faster than the clip, shorter when slower |
| **Orientation warping** | the hips and legs turn toward where the character goes, the chest keeps facing ahead: one forward run covers every direction |
| **Turn in place** | the feet stay planted while the body turns, then step round to catch up, as a person turns on the spot |
| **Distance matching** | a start or stop clip timed by how much ground is covered, so the last step lands where the character stops |
| **Root motion from the feet** | for a clip drawn in place: work out how far it would travel from where its feet push |

Distances are metres, angles degrees, measured from the character's facing (+Z), positive to its left.

## The code: a run that fits any speed and direction

The usual chain, on a Minecraft entity's actor, as a file:

```yaml
# assets/mymod/anim_graphs/runner.yaml
_v: 1
variables:
  speed:      { type: float }
  move_angle: { type: float }
  stride:     { type: float, default: 1 }
entity:
  speed: entity.speed                     # blocks a second
  move_angle: entity.move_direction       # degrees off its facing, left positive
output: out
nodes:
  out:     { kind: leg_ik, source: turned, legs: [ { ik_foot_bone: ik_foot_l, fk_foot_bone: foot_l }, { ik_foot_bone: ik_foot_r, fk_foot_bone: foot_r } ] }
  turned:  { kind: orientation_warping, source: strided, mode: manual, orientation_angle: move_angle,
             ik_foot_root: ik_foot_root, spine_bones: [spine_01, spine_02, spine_03], ik_foot_bones: [ik_foot_l, ik_foot_r] }
  strided: { kind: stride_warping, source: runs, mode: manual, stride_scale: stride, scale_clamp: [0.5, 1.5],
             pelvis: pelvis, ik_foot_root: ik_foot_root,
             feet: [ { ik_foot_bone: ik_foot_l, fk_foot_bone: foot_l, thigh_bone: thigh_l },
                     { ik_foot_bone: ik_foot_r, fk_foot_bone: foot_r, thigh_bone: thigh_r } ] }
  runs:    { kind: blend_space_player, blend_space: mymod:runner_runs, x: speed }
```

The stride is how much faster the entity goes than the clip it plays. The actor sets it each frame:

```java
@Override protected void tick(CgEntity runner, float dt) {
    float speed = runner.properties(CgEntityProperties.MOTION).get(CgEntityProperties.SPEED);
    float own = Math.max(1.5f, Math.min(speed, 6f));        // the blend space's own speed at this input
    body.anim().set(STRIDE, speed / own);                     // 1 inside the blend space's range, more past it
}
```

**Manual mode** is right on a Minecraft entity: the game moves the entity, and the nodes only bend the pose to match.
The order matters: stride before orientation, both before leg IK.

## Each tool

### Stride warping

Scales each foot's step along the direction of travel, pulls the pelvis down so the legs still reach, and turns each
thigh toward its foot. Leg IK after it bends the knees.

```java
CgAnimNode strided = CgStrideWarpingNode.of(runs, "pelvis", "ik_foot_root")
        .foot("ik_foot_l", "foot_l", "thigh_l").foot("ik_foot_r", "foot_r", "thigh_r")
        .manual(STRIDE)                         // the scale, set by the game
        .scaleClamp(0.5f, 1.5f);                // past these, play the loop faster or slower instead
CgAnimNode legs = CgLegIkNode.of(strided).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
```

A step can stretch only so far before the leg cannot reach. Clamp the scale, and let the play rate cover the rest:

```java
float own = clamp(v, slowestLoop, fastestLoop);       // the blend's own speed at v
float stride = clamp(v / own, 0.5f, 1.5f);
anim.set(SPEED, v).set(STRIDE, stride).set(RATE, v / (own * stride));   // RATE: the blend space's playRate
```

**Graph mode**, for a character the animation moves rather than the game: `.locomotionSpeed(SPEED)` instead of
`manual`, and the instance in `CgRootMotionMode.FROM_EVERYTHING`. The node then reads the clips' own travel and
replaces it with the warped one, so the character moves at `speed` ([`root-motion.md`](root-motion.md)).

### Orientation warping

Turns the root and the feet toward where the character goes, and turns the spine back so the chest still faces
ahead.

```java
CgAnimNode turned = CgOrientationWarpingNode.of(strided, "ik_foot_root")
        .spine("spine_01", "spine_02", "spine_03")
        .ikFeet("ik_foot_l", "ik_foot_r")
        .manual(MOVE_ANGLE);                    // degrees off its facing
```

- **The root takes half the turn** (`distribution`), the spine turns that much back, shared down the chain, and the
  feet take the rest.
- **It eases toward the target** at `interp(speed, maxDegrees)`.
- **Past 90 degrees it turns the other way**, playing the clip as a backward run (`flipBeyond`). A character with one
  forward loop for every direction sets `flipBeyond(0)`: at a 90-degree strafe the clip's own drift tips the target
  either side of 90, and the legs would flip from frame to frame.

Off an entity, the angle from a facing `yaw` and movement `(mx, mz)`:

```java
float lx = mx * cos(yaw) - mz * sin(yaw), lz = mx * sin(yaw) + mz * cos(yaw);
anim.set(MOVE_ANGLE, (float) Math.toDegrees(Math.atan2(lx, lz)));
```

### Turn in place

`CgOffsetRootBoneNode` keeps the skeleton's root where it stands while the entity turns or moves under it, then
eases it back: the feet stay put and the body swings round after.

```java
CgAnimNode standing = CgOffsetRootBoneNode.of(idle)
        .rotation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.25f, 100f)   // half-life 0.25 s, never 100 degrees behind
        .translation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.1f, 0.5f);
```

```yaml
standing: { kind: offset_root_bone, source: idle, rotation_half_life: 0.25, max_rotation_error: 100, max_translation_error: 0.5 }
```

| Mode | The held root |
|---|---|
| `INTERPOLATE` | held, then eased back over its half-life: the usual choice |
| `ACCUMULATE` | moved only by the clips' own travel |
| `HOLD` | follows the entity, keeping how far behind it is |
| `RELEASE` | follows the entity and eases back |
| `HOLD_AND_CONSUME` | as `HOLD`, and moved by the clips' travel too |
| `HOLD_SHRINK_AND_CONSUME` | as `HOLD_AND_CONSUME`, taking the clips' travel only where it closes the gap (a turn clip) |

It reads where the character stands from its component, which an actor's body has. A jump past `teleportDistance`
(3 m) carries the offset along rather than easing across it.

### Distance matching

A start or stop clip played by distance lands its steps where the feet land. Give the clip a distance curve at load,
then play it with a sequence evaluator whose time is found each frame:

```java
CgAnimSequence stop = CgClipModifiers.distanceCurve(
        CgClipModifiers.rootMotionFromFeet(rawStop, skeleton, false, "root", "foot_l", "foot_r"),
        skeleton, CgClipModifiers.DISTANCE, true);    // true: it stops at its end

CgSequenceEvaluatorNode.of(stop).matchToTarget(TO_STOP);                    // placed by the distance still to go
CgSequenceEvaluatorNode.of(start).advanceByDistance(TRAVELLED, 0.75f, 1.25f);   // advanced by ground covered, rate kept within
```

```yaml
stopping: { kind: sequence_evaluator, clip: mymod:runner/run_stop, mode: match_to_target, distance: to_stop }
starting: { kind: sequence_evaluator, clip: mymod:runner/run_start, mode: advance_by_distance, distance: travelled, play_rate_clamp: [0.75, 1.25] }
```

How far there is still to go, from velocity and braking:

```java
Vector3f stopAt = CgMovementPrediction.stopLocation(velocity, friction, deceleration, new Vector3f());
anim.set(TO_STOP, stopAt.length());
Vector3f pivot = CgMovementPrediction.pivotLocation(acceleration, velocity, friction, new Vector3f());
```

`CgDistanceMatching` has the sums on their own: `timeAtDistance`, `timeAfterDistanceTravelled`,
`playRateToMatchSpeed`.

### Root motion from the feet

A clip drawn in place (a hand-keyed loop) has no travel of its own. Work it out once, at load, from where its planted
feet push, and mark its feet so loops of different speeds blend in step:

```java
CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(rawRun, skeleton, true, "root", "foot_l", "foot_r");   // true: a loop
float speed = CgClipModifiers.speed(run, skeleton);     // metres a second it covers at rate 1
run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_l", "foot_l", true);
run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_r", "foot_r", true);
```

A step lasts while one foot is the lowest; the root covers what that foot sweeps backward, at an even pace over the
step. `speed` is what a blend space's sample of it should sit at.

## What goes wrong

- **Feet still slide at high speed**: the stride is clamped at 1.5 and the play rate is not raised for the rest.
- **The legs flip back and forth while strafing**: a single forward loop with the default `flipBeyond`. Set it to 0.
- **Nothing warps in graph mode**: the instance is not in `FROM_EVERYTHING`, so the graph reads no travel. On a
  Minecraft entity, use manual mode instead.
- **The entity is moved twice**: graph mode and `FROM_EVERYTHING` on an actor that names a `root_motion` body. The game
  already moves the entity; use manual mode.
- **A stop overshoots**: its clip has no distance curve (that fails when the graph is bound, naming it), or `to_stop`
  is not set each frame.
- **No IK foot bones**: give the skeleton virtual ones ([`ik-and-look-at.md`](ik-and-look-at.md) § *Feet on the ground*).
- **Leg IK before the warping**: the knees bend to the old feet. Leg IK goes last.

## Where next

- The blend space the loops come from: [`blend-spaces.md`](blend-spaces.md).
- Feet on stairs and slabs: [`ik-and-look-at.md`](ik-and-look-at.md).
- A move landing on a target: [`motion-warping.md`](motion-warping.md).
