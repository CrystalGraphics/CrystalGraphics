# Additive clips and aim offsets

**What you will have:** a breath and a flinch layered over whatever the body does, and a character whose spine, arms
and head turn toward where it aims while its legs keep running.

## In plain words

An **additive** clip stores only a change, not a whole pose: "lean back a bit", "flinch". You add it on top of
whatever the character is doing, like putting a sticker on a photo. An **aim offset** is a set of those changes, one
for each direction (up, down, left, right), so the upper body leans toward where the character looks while the legs
keep running.

## The code

The clip, marked additive ([`clips.md`](clips.md)):

```yaml
# assets/mymod/clips/models/hero/flinch.yaml
_v: 1
additive: { type: local_space, base: ref_pose }
```

Added over the locomotion, by a weight:

```java
CgApplyAdditiveNode.of(locomotion, CgSequencePlayerNode.of(FLINCH), FLINCH_WEIGHT);
```

```yaml
nodes:
  out:    { kind: apply_additive, base: loco, additive: flinch, alpha: flinch_weight }
  flinch: { kind: sequence_player, clip: mymod:hero/flinch }
```

## Variations

### Aim offsets

Each sample is the character aimed one way (up, down, left, right) made with `CgAdditive.meshSpace`. The one at the
look's angle is added over whatever plays, in the mesh's space, so the spine, arms and head turn together whatever the
legs do.

```java
static final CgBlendSpace AIM = CgBlendSpace.twoD("mymod:hero_aim", "yaw", -90, 90, "pitch", -90, 90)
        .sample(AIM_CENTRE, 0, 0).sample(AIM_LEFT, -90, 0).sample(AIM_RIGHT, 90, 0).sample(AIM_UP, 0, 90).sample(AIM_DOWN, 0, -90)
        .build();

CgAimOffsetNode.of(locomotion, AIM, YAW, PITCH).alpha(AIMING);    // only while aiming
```

```yaml
nodes:
  aim: { kind: aim_offset, base: loco, blend_space: mymod:hero_aim, x: yaw, y: pitch, alpha: aiming }
```

On an entity, feed `yaw` and `pitch` from `entity.head_yaw` and `entity.head_pitch`
([`entity-variables.md`](entity-variables.md)).

### Bases

| `base` | The differences are from |
|---|---|
| `ref_pose` | the skeleton's rest pose |
| `frame` | one frame of the clip itself, `base_frame` |
| `clip_frame` | one frame of another clip, `base_clip` and `base_frame` |

## What goes wrong

- **An aim offset's clips must be `mesh_space`** additives, or the graph fails at bind, naming the blend space.
- **A blend space of mixed additive kinds** fails when it is built.
- **An additive clip played on its own** (a sequence player straight to the output) shows only differences: a
  crumpled pose. Always add it over a base.

## Where next

- Blend spaces in full: [`blend-spaces.md`](blend-spaces.md).
- Part of the body only: [`layers-and-masks.md`](layers-and-masks.md).
