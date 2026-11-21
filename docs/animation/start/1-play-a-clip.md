# Tutorial 1: play a clip

**What you will have at the end:** every pig in your world drawn as your own model, playing one looping clip. Two
files and no Java. Then the same in Java, for a model of your own placed anywhere.

You need a skinned model with at least one animation, exported as glTF binary (`.glb`): from Blender
([tutorial 5](5-your-blender-character.md)) or any tool that writes glTF.

## 1. The model and its look

Put the model at `src/main/resources/assets/mymod/models/spirit.glb` and its texture at
`assets/mymod/textures/spirit.png`. Its look is a material in Minecraft's own entity lighting:

```yaml
# assets/mymod/materials/spirit.yaml
_v: 1
shader: crystalgraphics:shaders/materials/entity.shader
textures:
  _MainTex: { texture: mymod:textures/spirit.png, sampler: { filter: nearest, wrap: clamp } }
```

## 2. A prefab that plays the clip

```yaml
# assets/mymod/prefabs/spirit.yaml
_v: 1
nodes:
  - { id: n1, name: body, skeletal: { mesh: mymod:models/spirit.glb, play: float }, materials: [ mymod:spirit ] }
```

`play: float` loops the model's animation named `float`.

## 3. On every pig

```yaml
# assets/mymod/actors/spirit.yaml
_v: 1
binds: { entities: [ minecraft:pig ] }
prefab: mymod:spirit
```

## 4. Try it

Start a world and `/summon minecraft:pig ~ ~ ~3`. The pig is your spirit, floating in place as it wanders: its
actor follows the pig, and the clip loops.

## The same in Java

A model you place yourself, with no entity, in the client's scene:

```java
CgSkeletalMesh spirit = CgMeshLoader.skeletal("mymod:models/spirit.glb");
CgSkeletalMeshComponent body = CgScene.client().skeletal(spirit)
        .materials(slot -> CgMaterial.of("mymod:spirit"))
        .at(x, y, z)
        .spawn();

body.anim().play(spirit.sequence("float"));              // looping, from its start
body.anim().play(spirit.sequence("bow"), false);          // or once: it holds its last frame
body.anim().rate(0.5f);                                   // half speed
...
body.remove();
```

## What goes wrong

| You see | Because |
|---|---|
| The vanilla pig, and an error naming `actors/spirit.yaml` | a prefab, material or mesh id with a typo; the log names which |
| The model, standing still | the animation's name is not `float`: names are as Blender exported them |
| The model facing backwards | it faces -z. Turn it to face +z before export |
| `a slot with no material` | the mesh has more material slots than `materials:` lists: list one per slot |

## Where next

[Tutorial 2](2-a-character-that-runs-and-jumps.md): a graph that picks the clip by what the character is doing.
