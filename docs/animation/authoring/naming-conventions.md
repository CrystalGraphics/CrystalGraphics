# Naming conventions: bones, clips and markers

**What you will have:** a model the engine understands on sight: other characters' clips retarget onto it with no rig
file, the humanoid template's masks find its spine, its hands hold items, and its clips sync by their feet.

## In plain words

The engine finds a model's arms, legs and hands by their names, the way you find a friend's house by its street
name. Use the names below and everything that reads a body (retargeting, IK, held items, the humanoid template) works
with no setup. Use other names and you write a rig file to translate them.

## Bones

| Body part | Name | Needed by |
|---|---|---|
| The root, on the floor, parent of all | `root` | root motion, foot placement |
| Hips | `pelvis` | retargeting, the humanoid template |
| Spine, bottom to top | `spine_01`, `spine_02`, `spine_03` | retargeting; `spine_01` the humanoid template's swing |
| Neck, head | `neck_01`, `head` | retargeting; `head` turned where the entity looks |
| Collarbones | `clavicle_l`, `clavicle_r` | retargeting (optional) |
| Arms | `upperarm_l`, `lowerarm_l`, `hand_l` (and `_r`) | retargeting, IK |
| Legs | `thigh_l`, `calf_l`, `foot_l`, `ball_l` (and `_r`) | retargeting, IK, foot placement |
| Item grips | `right_hand_item`, `left_hand_item`: a small bone under each hand | held items |
| IK feet (optional) | `ik_foot_root`, `ik_foot_l`, `ik_foot_r` | foot placement; added as virtual bones if missing |

`_l` is the character's own left. The model faces +Z.

A GeckoLib model's humanoid names (`body`, `head`, `left_arm`, `right_leg`) and Minecraft's own model are read too:
[`../guides/retargeting.md`](../guides/retargeting.md) § *Default rig*.

## Clips

- **Name a clip for what it is**, lower case with underscores: `idle`, `walk`, `run`, `jump`, `land`, `punch_1`.
- **A clip's id** is its file's path then its name: `mymod:hero/run` for the action `run` in `models/hero.glb` played
  by an actor; `mymod:hero/melee/swing` for the animation `swing` in `animations/hero/melee.animation.json`.
- **The humanoid template's** clip names: [`../guides/vanilla-models.md`](../guides/vanilla-models.md).

## Markers

| Marker | Where | Means |
|---|---|---|
| `foot_l`, `foot_r` | a sync marker | the frame each foot lands |
| `sync=foot_l;` | a Blockbench timeline key | the same, from GeckoLib |
| `notify=<sequence>;`, `begin=` … `end=` | a Blockbench timeline key | a notify sequence ([`../guides/notifies-and-events.md`](../guides/notifies-and-events.md)) |

## What goes wrong

- **A bone named `Hand_R` or `hand.R`** is not `hand_r`: rename on export.
- **Two bones of one name** fail the load.
- **No `root` on the floor**: root motion and foot placement measure from the wrong place.

## Where next

- Before you ship a model: [`checklist.md`](checklist.md).
- A model with other names: [`../guides/retargeting.md`](../guides/retargeting.md) § *A rig of your own*.
