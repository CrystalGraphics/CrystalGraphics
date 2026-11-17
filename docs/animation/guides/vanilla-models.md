# Humanoids and Minecraft's own models

**What you will have:** two shortcuts for humanoid characters.

## In plain words

Two shortcuts. The **humanoid template** is a ready-made graph that already knows when to walk, swim or sleep: you
only hand it your clips, like a colouring book whose lines are already drawn. **Posing a vanilla model** plays any
humanoid clip on Minecraft's own blocky zombie or player, moving its six blocks instead of drawing a new model.

- **The humanoid template**: a whole graph for any humanoid body (stand, walk, run, sneak, fall, swim, crawl, glide,
  ride, sleep, die and swing) that you give your clips. No graph of your own.
- **Minecraft's own model, posed**: a vanilla zombie, skeleton or player playing any humanoid clip, retargeted onto its
  six blocky parts, still drawn by the game with its armour and items.

## The humanoid template: `crystalgraphics:humanoid`

```yaml
# assets/mymod/anim_graphs/knight.yaml
_v: 1
parent: crystalgraphics:humanoid
nodes:
  idle:       { clip: mymod:knight/idle }
  walk:       { clip: mymod:knight/walk }
  run:        { clip: mymod:knight/run }
  sneak_idle: { clip: mymod:knight/crouch }
  sneak_walk: { clip: mymod:knight/crouch_walk }
  fall:       { clip: mymod:knight/fall }
  swim:       { clip: mymod:knight/swim }
  crawl:      { clip: mymod:knight/crawl }
  glide:      { clip: mymod:knight/glide }
  ride:       { clip: mymod:knight/sit }
  sleep:      { clip: mymod:knight/sleep }
  dead:       { clip: mymod:knight/death }
  swing:      { clip: mymod:knight/swing }      # held at Minecraft's own swing progress, over the upper body
```

```yaml
# assets/mymod/actors/knight.yaml
_v: 1
binds: { entities: [ mymod:knight ] }
prefab: mymod:knight
anim_graph: mymod:knight
```

The knight now moves through Minecraft's whole set of states, fed by the entity, on every version.

### Which state plays

The first row that holds, top to bottom:

| Node | Plays while | Loops |
|---|---|---|
| `dead` | `entity.dead` | no |
| `sleep` | `entity.sleeping` | yes |
| `ride` | `entity.riding` | yes |
| `glide` | `entity.gliding` | yes |
| `swim` | `entity.swimming` | yes |
| `crawl` | `entity.crawling` | yes |
| `fall` | `entity.airborne` | yes |
| `sneak_walk` | `entity.sneaking` and `entity.speed` over 0.3 | yes |
| `sneak_idle` | `entity.sneaking` | yes |
| `run` | `entity.sprinting` and `entity.speed` over 0.3 | yes |
| `walk` | `entity.speed` over 0.3 blocks a second | yes |
| `idle` | otherwise | yes |

### What sits over the states

1. An `upper_body` linked layer, empty until an actor's `item_layers` fill it with a weapon's arms
   ([`held-items-and-item-layers.md`](held-items-and-item-layers.md)).
2. An `UpperBody` slot, for montages of the arms alone.
3. The swing: held at `entity.swing` (0 to 1) from the `spine_01` branch while `entity.swinging`.
4. `DefaultSlot` over everything, for whole-body moves and reactions.

The head turns where the entity looks: `head` is turned by `entity.head_yaw` and `entity.head_pitch`, as Minecraft
turns its model's head away from its body.

### Changing more than clips

A child graph overrides any node of the template by name, field by field ([`graphs-and-variables.md`](graphs-and-variables.md)
§ *Child graphs*): a different blend for `run`, a mask for a body without `spine_01`.

## Minecraft's own model, posed

A zombie, skeleton or player drawn by the game is Minecraft's humanoid: `CgVanillaHumanoid.SKELETON`, six parts pivoted
where the game pivots them. Retarget a clip onto it and play it on one entity:

```java
// a client: the zombie the player looks at throws your fighter's punch
CgSkeleton fighter = fighterMesh.skeleton();
CgRig fighterRig = CgRigs.detect(fighter, CgRigAdapters.CRYSTALGRAPHICS);
CgRetargetProcessor toZombie = new CgRetargetProcessor(
        CgRetargeter.auto(fighter, fighterRig, CgVanillaHumanoid.SKELETON, CgVanillaHumanoid.rig()),
        fighter, CgVanillaHumanoid.SKELETON);
CgVanillaPoses.play(zombieId).play(CgRetargetBake.cached(toZombie, punch), false);
...
CgVanillaPoses.stop(zombieId);                  // its own animation again
```

- **`play(id)`** answers the entity's anim instance, made on first use: play a clip, run a graph, fire a montage on it,
  as on any body.
- **The game still draws the model**, its armour and what it holds. The engine sets the parts right after the game's
  own model setup, so a zombie's own arm code is overridden, armour follows, and a player's sleeves and trousers go
  with the limbs.
- **`/cgc anim debug`** shows a posed vanilla model as it shows an actor's body ([`debugging.md`](debugging.md)).

## What goes wrong

- **The template bound as it is fails**, naming one of its clips (`crystalgraphics:humanoid/<node>`): they are
  placeholders. Give every node a clip.
- **The swing's mask names `spine_01`**, the standard humanoid's. A body with other bone names overrides the swing's
  layers in its child graph.
- **A vanilla limb is one rigid cube.** It points where the source's hand or foot is; it cannot bend at the elbow.
- **Forge 1.13.2 and 1.14.3** load no mixins: there `play` logs once that the entity is drawn unposed.

## Where next

- Retargeting in depth: [`retargeting.md`](retargeting.md).
- Your own model in place of the vanilla one: [`actors.md`](actors.md).
