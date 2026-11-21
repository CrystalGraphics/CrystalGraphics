# Tutorial 7: a Minecraft mob that moves like your character

**What you will have at the end:** every zombie in your world drawn as your own knight model. It walks, runs, falls,
swims and dies with your clips, swings its arm in time with its attacks, flinches when you hit it, and holds the sword
you give it. No Java: six small files.

It runs on every Minecraft version and loader the engine supports.

You need a rigged, animated character exported from Blender as glTF: [tutorial 5](5-your-blender-character.md) walks
through that.

## 1. The model

Export `knight.glb` with these bones and actions:

| Bones the template looks for | Why |
|---|---|
| `spine_01` | the swing plays from it upward |
| `head` | turned where the zombie looks |
| `right_hand_item`, `left_hand_item` | a small bone under each hand where an item's grip sits: its items are drawn there |

| Actions (clips) | Played while |
|---|---|
| `idle`, `walk`, `run` | standing, walking, running |
| `crouch`, `crouch_walk` | sneaking |
| `fall`, `swim`, `crawl`, `glide`, `sit`, `sleep`, `death` | the zombie is doing that |
| `swing` | an arm swing, 0 to 1 across the clip |
| `flinch` | being hit |

A zombie never glides or sleeps, but the template asks for every clip: reuse `idle` where you have nothing better.

Put it at `src/main/resources/assets/mymod/models/knight.glb`, and its texture at
`assets/mymod/textures/knight.png`.

## 2. Its look: a material

```yaml
# assets/mymod/materials/knight.yaml
_v: 1
shader: crystalgraphics:shaders/materials/entity.shader
textures:
  _MainTex: { texture: mymod:textures/knight.png, sampler: { filter: nearest, wrap: clamp } }
```

`entity.shader` lights it as Minecraft lights its own mobs: the two fixed lights, the lightmap, the fog. Pixel art
wants `nearest`.

## 3. What it draws: a prefab

```yaml
# assets/mymod/prefabs/knight.yaml
_v: 1
nodes:
  - { id: n1, name: body, skeletal: { mesh: mymod:models/knight.glb }, materials: [ mymod:knight ] }
```

## 4. How it moves: a graph

The engine's humanoid template already knows when to walk, run, fall or swim. Give it your clips:

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
  swing:      { clip: mymod:knight/swing }
```

A clip id's last part (`idle`) is found among the body's own actions.

## 5. Its flinch: a sequence

```yaml
# assets/mymod/sequences/knight_flinch.yaml
_v: 1
tick_resolution: 60000
display_rate: 30
range: [0, 24000]                       # 0.4 s
roles:
  - { id: b1, name: body, kind: caster }
tracks:
  - id: t1
    kind: animation
    binding: { role: body }
    sections:
      - { id: s1, range: [0, 24000], clip: mymod:knight/flinch }
```

## 6. Who wears it: the actor

```yaml
# assets/mymod/actors/knight.yaml
_v: 1
binds: { entities: [ minecraft:zombie ] }
prefab: mymod:knight
anim_graph: mymod:knight
reactions:
  - { event: hurt, sequence: mymod:knight_flinch }
```

## 7. Try it

1. Start the game and a world.
2. `/summon minecraft:zombie ~ ~ ~3`. It stands up as your knight and walks toward you.
3. Hit it: it flinches. Run: it runs after you. Lead it into water: it swims.
4. Give it a sword (1.17 and later): `/item replace entity @e[type=minecraft:zombie,limit=1,sort=nearest] weapon.mainhand with minecraft:iron_sword`.
   The sword is in its right hand, and swings with its arm.
5. Look at it and type `/cgc anim debug`: its state, its variables and its clips this frame, top right.

## What goes wrong

| You see | Because |
|---|---|
| The vanilla zombie | the log names the file and the cause: a prefab, graph or sequence id with a typo, or a clip missing |
| `crystalgraphics:humanoid/<node>` named in an error | a template node got no clip. Every node in step 4 needs one |
| The knight stands, but never swings | the model has no `spine_01`: rename the bone, or override the swing's mask ([`../guides/vanilla-models.md`](../guides/vanilla-models.md)) |
| No sword in its hand | no `right_hand_item` bone, or Minecraft below 1.15 on modern Forge or Fabric |
| It faces backwards | the model faces -z. Turn it in Blender to face +z, or rotate the prefab node by 180° |
| The zombie is drawn twice | Forge 1.13.2 or 1.14.3: no mixins there, so the actor draws beside the vanilla model |

## Where next

- A sword stance when it holds one: `item_layers` ([`../guides/held-items-and-item-layers.md`](../guides/held-items-and-item-layers.md)).
- A landing that holds it in place, a hit that knocks it back: [`../guides/reactions-and-locks.md`](../guides/reactions-and-locks.md).
- Its rage, a stun, a charge: [`../../entities/README.md`](../../entities/README.md).
- Your own graph instead of the template: [`../guides/graphs-and-variables.md`](../guides/graphs-and-variables.md).
