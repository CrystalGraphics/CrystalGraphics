# Locomotion: feet that keep up with the character

A run clip is drawn at one speed, facing one way. A game moves its character at many speeds, in every direction, on
potions and slopes, and turns it on the spot. Played as it is, the clip's feet slide. These tools fit the clip to the
movement instead:

| Tool | What it fixes |
|---|---|
| Root motion from the feet | a clip drawn in place gets the travel its planted feet imply |
| Root motion from everything | every playing clip's travel, at its weight, for the nodes below to read |
| Stride warping | longer or shorter strides for a faster or slower character |
| Orientation warping | a forward run turned to go sideways or diagonally, the chest still ahead |
| Turn in place | the body holds where it stands while the entity turns, then swings round |
| Distance matching | a start or stop played by ground covered, not by time |

Distances are metres; angles are degrees about the skeleton's +y, from its facing (+z), positive toward +x. The nodes
are graph nodes ([graphs.md](graphs.md)), each shown in Java and in an `anim_graph` file.

## Clips that travel

Warping reads a clip's **root motion**: how far its root moves. A captured clip has it. A clip drawn in place (an anime
run, most hand-keyed loops) does not, so give it root motion from its feet once, at load:

```java
CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(rawRun, skeleton, true, "root", "foot_l", "foot_r");
float speed = CgClipModifiers.speed(run, skeleton);     // metres a second it covers at rate 1
```

A step lasts while one foot is the lowest. The root covers what that foot sweeps backward, at an even pace over the
step. Pass `true` for a loop, so the step across its seam counts as one step.

Loops of different speeds blend in step only if their feet are marked alike. Mark each foot where it passes the root
going forward:

```java
run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_l", "foot_l", true);
run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_r", "foot_r", true);
```

A blend space of marked loops then syncs by those markers ([graphs.md](graphs.md) § *Sync groups and markers*).

## Root motion from everything

By default only a root-motion montage moves the character. Locomotion warping needs every player's motion, blended by
weight:

```java
anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);

// each frame, after the pose: what the clips moved, as the warping nodes left it
if (anim.montages().consumeRootMotion(1f, step)) mover.move(step);
```

In this mode every player holds its clip's root in place, and the motion goes to the mover instead. A blended
mode is not deterministic over the network: a move that must replay the same on every client keeps
`FROM_MONTAGES_ONLY`.

## Stride warping

The node scales each IK foot's stride along the direction of travel to match the speed the character really moves
at. It pulls the pelvis toward the feet so the legs still reach, and turns each thigh toward its foot. A leg IK after
it bends the knees.

```java
CgAnimVariable<Float> speed = CgAnimVariable.floating("speed"), rate = CgAnimVariable.floating("rate", 1f);

CgAnimNode runs = CgBlendSpacePlayerNode.of(RUNS, speed).playRate(rate);
CgAnimNode strided = CgStrideWarpingNode.of(runs, "pelvis", "ik_foot_root")
        .foot("ik_foot_l", "foot_l", "thigh_l").foot("ik_foot_r", "foot_r", "thigh_r")
        .locomotionSpeed(speed)                 // the scale is this over the clips' own speed
        .scaleClamp(0.5f, 1.5f);                // past these the loop plays faster or slower instead
CgAnimNode legs = CgLegIkNode.of(strided).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
```

```yaml
strided:
  kind: stride_warping
  source: runs
  pelvis: pelvis
  ik_foot_root: ik_foot_root
  feet:
    - { ik_foot_bone: ik_foot_l, fk_foot_bone: foot_l, thigh_bone: thigh_l }
    - { ik_foot_bone: ik_foot_r, fk_foot_bone: foot_r, thigh_bone: thigh_r }
  locomotion_speed: speed
  scale_clamp: [0.5, 1.5]
```

A stride can only stretch so far before the leg cannot reach. So clamp the scale, and let the play rate cover the
rest:

```java
float own = clamp(v, slowestLoop, fastestLoop);       // the blend's own speed at v
float stride = clamp(v / own, 0.5f, 1.5f);
anim.set(speed, v).set(rate, v / (own * stride));
```

In `GRAPH` mode, the default, the node reads the clips' root motion and replaces it with the warped motion, so the
character then moves at `speed`. `manual(STRIDE)` takes the scale outright instead, along `strideDirection` (+z).

A skeleton without IK foot bones gets them as virtual bones, each following its foot:

```java
mesh = mesh.withVirtualBones(List.of(new CgVirtualBone("ik_foot_root", "root", "root"),
        new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"), new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
```

## Orientation warping

One forward loop covers every direction. The node turns the root and the IK feet toward where the character moves,
and turns the spine back so the chest still faces ahead:

```java
CgAnimNode turned = CgOrientationWarpingNode.of(strided, "ik_foot_root")
        .spine("spine_01", "spine_02", "spine_03")
        .ikFeet("ik_foot_l", "ik_foot_r")
        .locomotionAngle(MOVE_ANGLE);           // where it moves, degrees off its facing
```

```yaml
turned:
  kind: orientation_warping
  source: strided
  ik_foot_root: ik_foot_root
  spine_bones: [spine_01, spine_02, spine_03]
  ik_foot_bones: [ik_foot_l, ik_foot_r]
  locomotion_angle: move_angle
```

The root takes `distribution` of the turn (half by default). The spine turns that much back, shared evenly down the
chain, and the IK foot root takes the rest. The node eases toward its target at `interp(speed, maxDegrees)`. A target
past 90 degrees turns the other way instead, taking the clip for a backward one (`flipBeyond`). A character with one
forward loop for every direction sets `flipBeyond(0)`: at a 90 degree strafe its clip's own drift tips the target
either side of 90, and the legs would flip from frame to frame. In `GRAPH` mode the node turns the root motion too,
so the character moves where its feet go.

The angle from the game, for a character facing `yaw` and moving along `(mx, mz)`:

```java
float lx = mx * cos(yaw) - mz * sin(yaw), lz = mx * sin(yaw) + mz * cos(yaw);
anim.set(MOVE_ANGLE, (float) Math.toDegrees(Math.atan2(lx, lz)));
```

## Turning in place

`CgOffsetRootBoneNode` keeps the skeleton's root where it stands in the world while the entity turns or moves under
it, then eases it back. The feet stay put, and the body swings round after:

```java
CgAnimNode standing = CgOffsetRootBoneNode.of(idle)
        .rotation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.25f, 100f)   // half-life 0.25 s, never 100 degrees behind
        .translation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.1f, 0.5f);
```

```yaml
standing:
  kind: offset_root_bone
  source: idle
  rotation_half_life: 0.25
  max_rotation_error: 100
  max_translation_error: 0.5
```

| Mode | The held root |
|---|---|
| `ACCUMULATE` | moves only by the clips' root motion |
| `INTERPOLATE` | as `ACCUMULATE`, then eased back over its half-life |
| `HOLD_AND_CONSUME` | follows the entity, keeping the offset, and moves by the clips' motion too |
| `HOLD_SHRINK_AND_CONSUME` | as `HOLD_AND_CONSUME`, taking the clips' motion only where it shrinks the offset (a turn clip) |
| `HOLD` | follows the entity, keeping the offset |
| `RELEASE` | follows the entity and eases back |

It reads where the instance stands (`placedBy`; a skeletal mesh component sets it). A jump past `teleportDistance`
(3 m) carries the offset along.

## Distance matching

A start, stop or pivot played by distance lands its steps where the character's feet land. Give the clip a distance
curve at load:

```java
CgAnimSequence stop = CgClipModifiers.distanceCurve(
        CgClipModifiers.rootMotionFromFeet(rawStop, skeleton, false, "root", "foot_l", "foot_r"),
        skeleton, CgClipModifiers.DISTANCE, true);    // true: it stops at its end
```

Then play it with a sequence evaluator, its time set each frame:

```java
// a stop: placed by the distance still to go
CgSequenceEvaluatorNode.of(stop).matchToTarget(TO_STOP);
// a start: advanced by the distance covered this frame, its rate kept within [0.75, 1.25]
CgSequenceEvaluatorNode.of(start).advanceByDistance(TRAVELLED, 0.75f, 1.25f);
// any clip at a time the game sets: seconds, or a share of the clip
CgSequenceEvaluatorNode.of(aim).explicitTime(AIM_TIME);
CgSequenceEvaluatorNode.of(swing).explicitTime(SWING).normalized();   // Minecraft's swing progress, 0 to 1
```

```yaml
stopping: { kind: sequence_evaluator, clip: mymod:hero/run_stop, mode: match_to_target, distance: to_stop }
starting: { kind: sequence_evaluator, clip: mymod:hero/run_start, mode: advance_by_distance, distance: travelled, play_rate_clamp: [0.75, 1.25] }
swinging: { kind: sequence_evaluator, clip: mymod:hero/swing, time: swing, normalized: true }
```

How far it still has to go, from its velocity and braking:

```java
Vector3f stopAt = CgMovementPrediction.stopLocation(velocity, friction, deceleration, new Vector3f());
anim.set(TO_STOP, stopAt.length());
Vector3f pivot = CgMovementPrediction.pivotLocation(acceleration, velocity, friction, new Vector3f());
```

`CgDistanceMatching` has the sums on their own: `timeAtDistance`, `timeAfterDistanceTravelled`,
`playRateToMatchSpeed`. A clip without its distance curve fails when the graph is bound, naming it.

## The order

```
blend space of loops ──► stride warping ──► orientation warping ──► leg IK ──► (foot placement) ──► output
idle ──► offset root bone ──► output
```

Stride warping comes before orientation warping, as both read and bend the root motion. Leg IK comes after both.
