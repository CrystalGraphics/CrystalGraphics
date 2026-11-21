# Motion warping: a move that lands on its target

**What you will have:** a lunge that lands at arm's length from its target whether the target stands nearer or
further than the clip was drawn, turns to face it on the way, and still catches it when it sidesteps.

## In plain words

A lunge is drawn once, travelling a fixed distance in a fixed direction. In a fight the enemy stands somewhere else
every time. **Motion warping** stretches, shrinks and bends the lunge while it plays so it ends right on the target,
like a long jumper adjusting their last steps to hit the board. The timing stays as the animator drew it: where the
move was slow it stays slow, where it was fast it stays fast.

Three pieces make it work:

| Piece | In plain words |
|---|---|
| A **warp window** on the clip | the stretch of the move that may be bent: "from 0.2 s to 0.9 s, aim at `opponent`" |
| A **target** on the character | where `opponent` is now, set by your gameplay |
| The **montage** that plays the clip | plays the move and carries the character by its bent travel |

## The code: a zombie's lunge onto the player

The clip's window, `assets/mymod/clips/models/zombie/lunge.yaml`:

```yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
warp_windows:
  - { target: opponent, start: 0.0, end: 0.6 }     # the first 0.6 s ends on "opponent"
```

On the server, when the zombie attacks, aim it and play the move:

```java
CgEntityActor zombie = CgEntityActors.serverActor(zombieId);
zombie.animation().motionWarping()
        .target("opponent", player.getX(), player.getY(), player.getZ(), playerYawRadians)
        .standOff("opponent", 0.9f);                 // stop 0.9 m short: arm's length
zombie.play("mymod:moves/zombie_lunge");             // carried by its root motion, seen by everyone near
```

The actor names its `root_motion` body, so the bent travel moves the zombie entity itself
([`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md)).

## Variations

### On a character of your own

```java
CgAnimInstance anim = component.anim();
anim.motionWarping().target("opponent", x, y, z, yaw);
sequencer.play(LUNGE, Map.of("caster", fighter));

Matrix4f step = new Matrix4f();                                       // each frame, after the pose
if (anim.montages().consumeRootMotion(1f, step)) mover.move(step);    // the bent step
```

### A window in Java

```java
CgAnimSequence rush = dive.with(dive.settings().withWarpWindows(List.of(
        CgWarpWindow.of("opponent", 0.0f, 0.6f))));
```

### Turning to face the target as it closes

```java
CgWarpWindow.of("opponent", 0.2f, 0.9f)
        .facing(true)                                                              // face its position
        .rotation(CgWarpWindow.Rotation.SLERP_WITH_CLAMPED_RATE, 1f, 720f);        // at most 720° a second
```

| Window key (file) | Builder | Default | Does |
|---|---|---|---|
| `translation` | `translation(on, ignoreHeight)` | true | bend the travel toward the target |
| `ignore_height` | `translation(on, ignoreHeight)` | true | keep the clip's own height: a dive still dives |
| `rotation` | `noRotation()` | true | bend the turn toward the target's |
| `facing` | `facing(true)` | false | face the target's position instead of copying its yaw |
| `method` | `rotation(method, time, rate)` | `slerp` | `slerp` over the time left; `slerp_with_clamped_rate` never faster than `max_rotation_rate`; `constant_rate` at that rate; `scale` the clip's own turn scaled to end on it |
| `rotation_time` | `rotation(method, time, rate)` | 1 | how much of the window's remaining time the turn takes |
| `max_rotation_rate` | `rotation(method, time, rate)` | 0 | degrees a second, for the clamped methods |
| `max_speed_ratio` | `maxSpeedRatio(r)` | 0 (none) | the bent step at most `r` times the clip's own |
| `yaw_offset` | `yawOffset(deg)` | 0 | degrees added to the target turn: land behind and turn round |

### A target that moves

Set the target again while the window is open and the move re-aims from where the character is now: a sidestepping
target is caught. `removeTarget("opponent")` lets the window play the clip's own travel.

### A clip whose travel is on the pelvis

Many attack clips move the pelvis and leave the root still. Move the travel onto the root once, at load; the pose
does not change and root motion is turned on:

```java
CgAnimSequence dive = CgClipModifiers.encodeRootBone(rawDive, skeleton, "root", "pelvis");
```

## How the bend works

Each frame inside the window, the engine knows this frame's step of the clip, the rest of the window's travel, and
where the target is. Along the line of the remaining travel it scales the step by how far the target is against how
far the clip would go; sideways and up it shears the step by the angle between them. Summed over the window, the
steps end exactly on the target, and the path keeps the clip's shape: a curve stays a curve, a pause stays a pause. A
window whose clip does not travel at all is pulled to the target in a straight line.

## What goes wrong

- **Nothing bends**: only the root-motion montage is warped. A clip played by a graph's sequence player is not.
- **It stops short of the window's end**: the window must lie inside the clip, and the montage must play past its end.
- **It lands at the world's origin**: the character's placement is unknown. A component or an actor's body knows it;
  an instance of your own needs `anim.placedBy(...)`.
- **It looks like a teleport**: a warp has a believable range. Bending a 2.4 m lunge onto 4.2 m peaks at 22 m/s
  against the clip's own 16; onto 5.5 m, 29. Past about one and a half times a move's own reach, dash in first.
- **It lands, then drifts**: the clip's own motion after the window plays as drawn. Measure where a move lands as its
  window closes.

## Where next

- Root motion itself: [`root-motion.md`](root-motion.md).
- On a Minecraft entity: [`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md).
- Feet that fit any speed and direction: [`locomotion.md`](locomotion.md).
