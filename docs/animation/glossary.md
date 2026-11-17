# Glossary

Each word in plain terms, and where it is taught.

| Word | Means | Taught in |
|---|---|---|
| **Actor** | what draws a Minecraft entity with your model, like a costume the mob wears | [`guides/actors.md`](guides/actors.md) |
| **Additive clip** | a clip that stores only a change ("lean back"), added on top of another pose | [`guides/additive-and-aim-offsets.md`](guides/additive-and-aim-offsets.md) |
| **Aim offset** | additive poses for each direction, so the upper body leans where the character looks | [`guides/additive-and-aim-offsets.md`](guides/additive-and-aim-offsets.md) |
| **Anim instance** | one character's running copy of a graph: its own clip times and variable values | [`guides/graphs-and-variables.md`](guides/graphs-and-variables.md) |
| **AnimDynamics** | a chain of bones simulated as little boxes, for hair and tails that swing | [`guides/springs-hair-and-cloth.md`](guides/springs-hair-and-cloth.md) |
| **Blend space** | clips laid on a line or map (by speed, direction) and mixed by where the character is | [`guides/blend-spaces.md`](guides/blend-spaces.md) |
| **Bone** | one joint of a skeleton; a clip moves bones | [`authoring/naming-conventions.md`](authoring/naming-conventions.md) |
| **Cached pose** | a pose worked out once and read in several places | [`guides/layers-and-masks.md`](guides/layers-and-masks.md) |
| **Clip** | one recorded movement: a run, a punch | [`guides/clips.md`](guides/clips.md) |
| **Conduit** | a state with no pose: a crossroads for transitions | [`guides/state-machines.md`](guides/state-machines.md) |
| **Crossfade** | fading one pose into the next over a moment | [`guides/transitions-and-blending.md`](guides/transitions-and-blending.md) |
| **Distance matching** | a start or stop clip timed by ground covered, not by the clock | [`guides/locomotion.md`](guides/locomotion.md) |
| **Event** | a note on a move's timeline for your game code: "the hit window opens" | [`guides/notifies-and-events.md`](guides/notifies-and-events.md) |
| **Graph** | the flowchart that picks and mixes clips each frame by variables | [`guides/graphs-and-variables.md`](guides/graphs-and-variables.md) |
| **Humanoid template** | the engine's ready-made graph for any humanoid: give it your clips | [`guides/vanilla-models.md`](guides/vanilla-models.md) |
| **IK** | working backwards from where a hand or foot must go to bend the limb to reach it | [`guides/ik-and-look-at.md`](guides/ik-and-look-at.md) |
| **Inertialization** | switching poses at once but letting each bone ease into the new one, so nothing pops | [`guides/transitions-and-blending.md`](guides/transitions-and-blending.md) |
| **Item layer** | a held item choosing the arms' animation: a sword stance, a bow draw | [`guides/held-items-and-item-layers.md`](guides/held-items-and-item-layers.md) |
| **Layer** | a second pose over part of the body | [`guides/layers-and-masks.md`](guides/layers-and-masks.md) |
| **Linked layer** | a part of a graph swapped at runtime by another graph | [`guides/layers-and-masks.md`](guides/layers-and-masks.md) |
| **Mask** | which bones a layer covers | [`guides/layers-and-masks.md`](guides/layers-and-masks.md) |
| **Montage** | a one-off move played over the graph on a slot | [`guides/montages-and-slots.md`](guides/montages-and-slots.md) |
| **Motion warping** | stretching a move's travel so it lands on its target | [`guides/motion-warping.md`](guides/motion-warping.md) |
| **Move lock** | holding an entity's own movement while a move plays | [`guides/reactions-and-locks.md`](guides/reactions-and-locks.md) |
| **Notify** | a sticky note on a clip's timeline: play a sound, an effect | [`guides/notifies-and-events.md`](guides/notifies-and-events.md) |
| **Orientation warping** | turning the hips so a forward run goes sideways, chest still ahead | [`guides/locomotion.md`](guides/locomotion.md) |
| **Pose** | where every bone is at one moment | [`concepts/the-frame.md`](concepts/the-frame.md) |
| **Prefab** | a set of parts (meshes, effects) an actor draws | [`../vfx/prefabs.md`](../vfx/prefabs.md) |
| **Reaction** | a reflex: an entity event plays a move, with no code | [`guides/reactions-and-locks.md`](guides/reactions-and-locks.md) |
| **Retargeting** | playing one skeleton's clips on another of a different shape | [`guides/retargeting.md`](guides/retargeting.md) |
| **Rig** | which bones of a skeleton are its arms, legs and spine | [`guides/retargeting.md`](guides/retargeting.md) |
| **Root motion** | the clip itself saying how far the body travels | [`guides/root-motion.md`](guides/root-motion.md) |
| **Section** | a named chapter of a move (wind-up, strike) that play can jump between | [`guides/montages-and-slots.md`](guides/montages-and-slots.md) |
| **Sequence** | a timeline of tracks: animations, effects, events; a montage is one | [`../vfx/sequences.md`](../vfx/sequences.md) |
| **Skeleton** | a model's bones and how they hang from each other | [`authoring/naming-conventions.md`](authoring/naming-conventions.md) |
| **Slot** | the place in a graph where montages may play | [`guides/montages-and-slots.md`](guides/montages-and-slots.md) |
| **Socket** | a named spot on a bone where something attaches: an item, an effect | [`guides/held-items-and-item-layers.md`](guides/held-items-and-item-layers.md) |
| **Spring bone** | a bone hung on a spring, lagging and settling | [`guides/springs-hair-and-cloth.md`](guides/springs-hair-and-cloth.md) |
| **State machine** | rooms (states) joined by doors (transitions) with rules on them | [`guides/state-machines.md`](guides/state-machines.md) |
| **Stride warping** | longer or shorter steps to match the real speed | [`guides/locomotion.md`](guides/locomotion.md) |
| **Sync group** | clips that march in step so feet never tangle | [`guides/sync-groups.md`](guides/sync-groups.md) |
| **Sync marker** | a flag on a clip saying where a foot lands | [`guides/sync-groups.md`](guides/sync-groups.md) |
| **Transition** | a door between two states, with its rule and blend | [`guides/state-machines.md`](guides/state-machines.md) |
| **Variable** | a fact a graph reads: speed, on the ground, a tag | [`guides/graphs-and-variables.md`](guides/graphs-and-variables.md) |
| **Weight** | how much of a pose shows, 0 to 1 | [`concepts/weights-and-relevance.md`](concepts/weights-and-relevance.md) |

The entity words (property, tag, handle, owner, prediction) are [`../entities/glossary.md`](../entities/glossary.md).
