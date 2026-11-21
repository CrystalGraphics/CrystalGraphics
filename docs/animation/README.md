# Animation

Characters that move: a skinned model playing clips, mixed every frame by a graph that reads what the character is
doing, with moves played over it, feet planted on the blocks, hair that swings, and another character's clips played
on your model. In Java or as files, on every Minecraft version and loader, on the server too.

## Five minutes: a zombie that walks with your model

Three files and your model, no Java:

```yaml
# assets/mymod/prefabs/knight.yaml: what it draws
_v: 1
nodes:
  - { id: n1, name: body, skeletal: { mesh: mymod:models/knight.glb }, materials: [ mymod:knight ] }
```

```yaml
# assets/mymod/anim_graphs/knight.yaml: how it moves, the engine's humanoid given your clips
_v: 1
parent: crystalgraphics:humanoid
nodes:
  idle: { clip: mymod:knight/idle }
  walk: { clip: mymod:knight/walk }
  run:  { clip: mymod:knight/run }
  # ... one clip a state: tutorial 7 lists them
```

```yaml
# assets/mymod/actors/knight.yaml: who wears it
_v: 1
binds: { entities: [ minecraft:zombie ] }
prefab: mymod:knight
anim_graph: mymod:knight
```

Every zombie is now your knight: it walks, runs, falls, swims and dies with your clips, fed by the zombie every frame.
[Tutorial 7](start/7-a-minecraft-mob-that-moves-like-your-character.md) builds it start to finish.

## The picture

```
            what the entity is doing                     your gameplay
  speed, grounded, swimming, a hit, its tags        a punch, a combo, a stun
                     │                                        │
                     ▼                                        ▼
   ┌─────────────── graph ───────────────┐         ┌──── montages ────┐
   │ variables ─► state machine ─► blend │ ─slot─▶ │ moves over it    │
   │              spaces, layers         │         │ (sequences)      │
   └─────────────────────────────────────┘         └──────────────────┘
                                   │
                                   ▼
               IK, look-at, springs ─► the pose ─► drawn, and its root motion moves the entity
```

| Word | Is |
|---|---|
| **Clip** | one animation: a run, a punch |
| **Graph** | what picks and mixes clips each frame, by variables |
| **Variable** | a value the graph reads: speed, on the ground, a tag |
| **Montage** | a move played over the graph on a slot: a sequence's animation |
| **Actor** | what draws a Minecraft entity with your model and graph |

Every word: [`glossary.md`](glossary.md).

## Where to go

### Start here: tutorials, in order

1. [Play a clip](start/1-play-a-clip.md): your model on a pig, playing one clip.
2. [A character that runs and jumps](start/2-a-character-that-runs-and-jumps.md): a graph, a state machine, a blend space.
3. [A punch with a hit window](start/3-a-punch-with-a-hit-window.md): a montage, its sections and gameplay events.
4. [Your Blockbench model](start/4-your-blockbench-model.md): GeckoLib files and their markers.
5. [Your Blender character](start/5-your-blender-character.md): glTF, actions, markers, root motion.
6. [Someone else's animations on your model](start/6-someone-elses-animations-on-your-model.md): rigs and retargeting.
7. [A Minecraft mob that moves like your character](start/7-a-minecraft-mob-that-moves-like-your-character.md): actors, no code.

### Guides: one task a page

| You want to | Read |
|---|---|
| Build a graph, set variables, share one graph among characters | [`guides/graphs-and-variables.md`](guides/graphs-and-variables.md) |
| Move between idle, run, fall and land | [`guides/state-machines.md`](guides/state-machines.md) |
| Choose how a change blends, or snap without popping | [`guides/transitions-and-blending.md`](guides/transitions-and-blending.md) |
| Blend clips by speed and direction | [`guides/blend-spaces.md`](guides/blend-spaces.md) |
| Keep feet in step across a blend | [`guides/sync-groups.md`](guides/sync-groups.md) |
| Animate part of the body, swap a layer at runtime | [`guides/layers-and-masks.md`](guides/layers-and-masks.md) |
| Layer a flinch, aim where the character looks | [`guides/additive-and-aim-offsets.md`](guides/additive-and-aim-offsets.md) |
| Mark a clip's feet, root motion, additive base | [`guides/clips.md`](guides/clips.md) |
| Play a move over the graph, with sections and combos | [`guides/montages-and-slots.md`](guides/montages-and-slots.md) |
| Fire sounds, effects and hit windows on a clip's frames | [`guides/notifies-and-events.md`](guides/notifies-and-events.md) |
| Let a clip move the character | [`guides/root-motion.md`](guides/root-motion.md) |
| Put feet on blocks, turn a head, reach a target | [`guides/ik-and-look-at.md`](guides/ik-and-look-at.md) |
| Make hair, tails and cloth swing | [`guides/springs-hair-and-cloth.md`](guides/springs-hair-and-cloth.md) |
| Keep feet from sliding at every speed and turn | [`guides/locomotion.md`](guides/locomotion.md) |
| Land a lunge on its target | [`guides/motion-warping.md`](guides/motion-warping.md) |
| Play one character's clips on another | [`guides/retargeting.md`](guides/retargeting.md) |
| Draw a Minecraft entity with your model | [`guides/actors.md`](guides/actors.md) |
| Feed a graph from its entity, with no code | [`guides/entity-variables.md`](guides/entity-variables.md) |
| Flinch on a hit, hold still during a landing | [`guides/reactions-and-locks.md`](guides/reactions-and-locks.md) |
| Hold items, change stance with a weapon | [`guides/held-items-and-item-layers.md`](guides/held-items-and-item-layers.md) |
| Use the built-in humanoid graph, or pose a vanilla mob | [`guides/vanilla-models.md`](guides/vanilla-models.md) |
| Move a Minecraft entity by its animation | [`guides/root-motion-moves-the-entity.md`](guides/root-motion-moves-the-entity.md) |
| Play moves on the server, in step for every player | [`guides/servers-and-networking.md`](guides/servers-and-networking.md) |
| See what a character plays, live | [`guides/debugging.md`](guides/debugging.md) |

### Authoring, reference, concepts

- **For artists**: [`authoring/naming-conventions.md`](authoring/naming-conventions.md) and the export
  [`authoring/checklist.md`](authoring/checklist.md).
- **Reference**: every file key ([`reference/assets.md`](reference/assets.md)), every node
  ([`reference/nodes.md`](reference/nodes.md)), every variable and what feeds it ([`reference/variables.md`](reference/variables.md)).
  What fails at load is each guide's *What goes wrong*.
- **How it works**: [`concepts/the-frame.md`](concepts/the-frame.md) and
  [`concepts/weights-and-relevance.md`](concepts/weights-and-relevance.md).

Giving an entity data of your own, tags and saved state is [`../entities/`](../entities/README.md).
