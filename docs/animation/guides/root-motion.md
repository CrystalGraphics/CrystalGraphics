# Root motion

**What you will have:** moves whose travel is authored in the clip (a lunge, a roll, a dash) moving the character by
exactly what the animation shows, instead of the code guessing a speed.

## In plain words

Normally the code moves the character across the ground and the clip only moves the arms and legs on the spot, like
a person on a treadmill. With **root motion**, the clip itself says how far the body travels: a lunge drawn moving two
blocks forward moves the character exactly two blocks. The animation's feet and the real movement can never
disagree, so nothing slides.

## The code

Mark the clip ([`clips.md`](clips.md)):

```yaml
# assets/mymod/clips/models/hero/lunge.yaml
_v: 1
root_motion: { enabled: true, lock: ref_pose }
```

Play it as a montage. On an entity's actor, the server's montage carries the entity for you
([`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md)):

```java
CgEntityActors.serverActor(heroId).play("mymod:moves/lunge");
```

On a character of your own, take the step after the frame's pose and move it yourself:

```java
Matrix4f step = new Matrix4f();
if (hero.anim().montages().consumeRootMotion(1f, step)) mover.move(step);
```

## How it works

- **The root bone's motion is taken out of the pose** and handed to whatever moves the character, so the mesh stays on
  its capsule and the capsule travels.
- **`lock`** says where the root is held while its motion is taken: `ref_pose` (its rest), `anim_first_frame`, or
  `zero`.
- **One montage moves the character at a time** (`CgRootMotionMode.FROM_MONTAGES_ONLY`, the default): a root-motion
  montage played stops the one moving it.
- **`FROM_EVERYTHING`** adds every playing clip's motion at its weight: what locomotion warping reads, so a walk's
  stride drives the speed ([`locomotion.md`](locomotion.md)).

## Variations

### Bent to a target

Motion warping bends a montage's travel so it lands on a target nearer or further than authored:
[`motion-warping.md`](motion-warping.md).

### Root motion, but not from this montage

```yaml
# on the montage's animation sections
root_motion: false
```

## What goes wrong

- **The character slides back to where it started**: nothing consumes the motion, so the root plays as keyed. On an
  actor, name its `root_motion` body.
- **It moves twice as far**: both the clip's root and your code move the character. Let one do it.
- **A looping locomotion clip with root motion** does nothing under `FROM_MONTAGES_ONLY`: only montages move the
  character.

## Where next

- Moving a Minecraft entity by it: [`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md).
- Landing on a target: [`motion-warping.md`](motion-warping.md).
