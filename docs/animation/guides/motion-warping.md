# Motion warping: a move that lands where its target is

**What you will have:** a lunge that lands on its target whether the target stands nearer or further than the clip
was drawn, ending at arm's length and facing it.

## In plain words

A lunge is drawn once, travelling a fixed distance, but in a fight the enemy stands nearer or further every time.
**Motion warping** stretches or shrinks the lunge while it plays so it ends right on the target, like a long jumper
adjusting their last steps to hit the board. The timing still looks as the animator drew it.


A rush, a dive or a grab is drawn once, covering a fixed distance in a fixed direction. In a game the opponent stands
somewhere else every time. Motion warping bends the move's root motion while it plays, so it ends on the target. The
clip's pacing stays as drawn: where it was slow it stays slow, and where it was fast it stays fast.

| Piece | What it is |
|---|---|
| A **warp window** on the clip | a span of the clip whose root motion is bent toward a named target |
| The character's **motion warping** | where each named target stands now, set by the game |
| The **montage** that plays the clip | its root motion is warped inside the window, then moves the character as root motion always does |

Distances are metres in the world; a yaw is radians about +y from facing +z, positive toward +x.

## 1. A clip that travels

Warping bends root motion, so the clip must have some ([clips.md](clips.md) § root motion). Many attack clips keep
their travel on the pelvis and leave the root still. Move it onto the root once, at load:

```java
CgAnimSequence dive = CgClipModifiers.encodeRootBone(rawDive, skeleton, "root", "pelvis");
```

Each frame the root goes horizontally where the pelvis is, keeping its own height and turn. The root's children are
placed back where they were, so the pose does not change. Root motion is turned on for you.

## 2. A window on the clip

In Java:

```java
CgAnimSequence rush = dive.with(dive.settings().withWarpWindows(List.of(
        CgWarpWindow.of("opponent", 0.0f, 1.0f))));   // its first second ends on "opponent"
```

As data, in the clip's settings file (`clips/<clip id>.yaml`):

```yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
warp_windows:
  - { target: opponent, start: 0.0, end: 1.0 }
```

Put the window over the part of the move that travels. Anything after it plays as drawn, from wherever the window
left the character.

## 3. Tell it where the target is

The game sets the target before the window opens, and may move it while the window plays:

```java
CgAnimInstance anim = component.anim();
anim.motionWarping().target("opponent", enemy.x, enemy.y, enemy.z, enemyYaw);
```

Then play the move as a sequence's animation section on the character (a montage), and move the character by its root
motion each frame:

```java
Matrix4f step = new Matrix4f();
if (anim.montages().consumeRootMotion(1f, step)) mover.move(step);   // the warped step
```

A strike should end at arm's length, not inside its opponent. Give the target a **stand-off**: the move stops that
far short of it, on the line from the target to the character, recomputed each frame:

```java
anim.motionWarping().target("opponent", x, y, z, yaw).standOff("opponent", 0.9f);   // where the fist meets them
```

A target set again while its window is open is aimed at again from where the character stands. A sidestepping
opponent is caught. `removeTarget("opponent")` makes its windows play the clip's own motion.

## Choices on a window

| Field (data) | Builder | Default | What it does |
|---|---|---|---|
| `translation` | `translation(on, ignoreHeight)` | true | bend the travel toward the target |
| `ignore_height` | `translation(on, ignoreHeight)` | true | leave height as the clip has it: a dive still dives |
| `rotation` | `noRotation()` | true | bend the turn toward the target's |
| `facing` | `facing(true)` | false | turn to face the target's position instead of copying its yaw |
| `method` | `rotation(method, time, rate)` | `slerp` | `slerp` over the time left; `slerp_with_clamped_rate` the same, never faster than `max_rotation_rate`; `constant_rate` at that rate; `scale` the clip's own turn scaled to end on it |
| `rotation_time` | `rotation(method, time, rate)` | 1 | how much of the window's remaining time the turn takes |
| `max_rotation_rate` | `rotation(method, time, rate)` | 0 | degrees a second, for the clamped methods |
| `max_speed_ratio` | `maxSpeedRatio(r)` | 0 (none) | the warped step at most `r` times the clip's own |
| `yaw_offset` | `yawOffset(deg)` | 0 | degrees added to the target turn: land behind and turn round |

A rush that turns to face its opponent as it closes, at most 720 degrees a second:

```java
CgWarpWindow.of("opponent", 0.2f, 0.9f).facing(true).rotation(CgWarpWindow.Rotation.SLERP_WITH_CLAMPED_RATE, 1f, 720f)
```

## How the bend works

Each frame, inside the window, the engine knows three things in the character's own space: this frame's step of the
clip, the rest of the window's motion, and where the target is. It takes a frame along the rest of the motion. Along
that line it scales the step by how far the target is against how far the clip would go. Sideways and up, it shears
the step by the angles between them. Summed over the window, the steps end exactly on the target, and the path keeps
the clip's shape: a curve stays a curve, a pause stays a pause. A window whose clip does not travel at all is pulled to
the target in a straight line instead.

## Easy to get wrong

- **Only the root-motion montage is warped.** A clip on a graph's sequence player is not.
- **The window must be inside the clip**, and its montage must play past its end. A section that stops at the clip's
  last frame never plays that frame's step.
- **Placement comes from `placedBy`.** Warping reads where the character stands through the instance's `placedBy`, at
  the moment it bends each step. Without it, the character is taken to stand at the origin.
- **A warp has a believable range.** Bending a 2.4 m lunge onto 4.2 m peaks at 22 m/s against the clip's 16; onto
  5.5 m, 29, which reads as a teleport. Past about one and a half times a move's own reach, dash in first.
- **The clip's own pause after the window is its own.** Measure where a move lands as its window closes.
- Warping allocates nothing once warm.

The animation demo's **Motion warping** station plays a lunging strike in two lanes, warped and as drawn.

## Where next

- On a Minecraft entity, from the server: [`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md).
- Root motion itself: [`root-motion.md`](root-motion.md).
