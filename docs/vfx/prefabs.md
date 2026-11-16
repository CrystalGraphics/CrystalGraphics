# Prefabs: models made of parts

A prefab is a model built from parts that move and fade together: a lantern and its flame, the core of a Rasen
Shuriken with its rings and blades, a lens of nested balls, a wyvern with its armour and the fire in its jaw. It is a
tree of nodes, each with a parent, a rest transform, an opacity and the meshes it draws, each with its materials; a node
may instead be a skeletal mesh, or play an effect. Unreal calls it a Blueprint's component tree; CC2 a clump.

A prefab is an asset, `CgPrefab`, and two things play it:

- **A scene spawns it as components** (`scene.spawn(prefab)`): a root a node, a mesh component a part. What an actor or
  a level places.
- **A VFX effect plays it through its look** (`CgVfxPrefabEffect`): its parts drawn with the effect, keyed by a
  sequence, sorted with the play's systems. What a move's sequence spawns.

## Building one

```java
// A material asset: one definition is one material, however many parts draw with it.
static final CgMaterialDefinition IRON = CgSurfaceMaterial.of("mymod:iron", "mymod:textures/lantern/iron.png").definition();
static final CgMaterialDefinition FLAME = CgSurfaceMaterial.of("mymod:flame", "mymod:textures/lantern/flame.png")
        .blend(CgBlendMode.ADDITIVE).shading(CgShadingModels.UNLIT).definition();

static final CgPrefab LANTERN = CgPrefab.builder("mymod:lantern")
        .node("frame").part(IRON, "mymod:models/lantern/frame.glb")
        .node("flame").parent("frame").at(0f, 0.25f, 0f).scale(0.5f).value("_Emissive", 2f)
            .part(FLAME, "mymod:models/lantern/flame.glb")
            .part(IRON, "mymod:models/lantern/wick.glb")           // a second mesh on the same node
        .build();
```

| Call | Sets |
|---|---|
| `node(name)` | a new node, a root at the origin unless told otherwise; the calls after it set it |
| `parent(name)` | its parent, a node named before it |
| `at(x, y, z)`, `rotation(x, y, z, w)`, `scale(...)` | its rest transform in its parent's space; the rotation a quaternion |
| `opacity(a)` | its own opacity, 0 to 1 |
| `value(property, ...)` | a material value of its own over each of its parts' materials |
| `part(material, mesh)` | a mesh it draws with a material: a `CgMesh`, `CgMeshLods`, or a file's path loaded at its first draw; `part(material)` draws the unit sphere, a VFX look's alone |
| `skeletal(mesh, materials)` | the node is a skeletal mesh: a `CgSkeletalMesh` or a file's path, its materials one a section |
| `play(clip)`, `followPose(leader)` | a skeletal node's clip at the spawn, or a leader node it is drawn in the pose of |
| `parent(name, socket)` | its parent a skeletal node, riding that socket or bone |
| `effect(system, auto)` | the node plays a VFX system following it: at the spawn if `auto`, else when told |

### Skeletal meshes and effects

A character's model is a skeletal mesh with what rides it: armour in its pose, a weapon on its hand, a flame in its jaw.
In a prefab, each is a node:

```java
static final CgPrefab WYVERN = CgPrefab.builder("mymod:wyvern")
        .node("body").skeletal("mymod:geo/wyvern.glb", List.of(SKIN)).play("idle")
        .node("armour").skeletal("mymod:geo/wyvern_armour.glb", List.of(STEEL)).followPose("body")
        .node("saddle").parent("body", "spine_02").part(LEATHER, "mymod:geo/saddle.glb")
        .node("breath").parent("body", "jaw").effect("mymod:vfx/fire_breath", false)
        .build();
```

- **A skeletal node is a `CgSkeletalMeshComponent`** when spawned, playing its clip; a clip its mesh lacks is refused at
  `spawn()`, naming it. `followPose` names a skeletal node before it, `parent(name, socket)` one too.
- **An effect node plays through `CgPrefabEffects.HOST`**, which `CgVfx.install` fills: the system plays in the world's
  VFX, following the node, and stops when the instance is removed. Without VFX installed it warns once and plays nothing.
- **A VFX look draws neither**: a prefab with a skeletal or effect node is spawned in a scene, and an effect playing it
  throws, naming the node.

## As a file

A prefab is a `prefabs/` asset, compiled by `CgPrefabAssets` (which `CgVfx.install` registers) and written back by it,
so a prefab built in Java and its file are one model. On a client it is `READY` once `CgVfxPrefabPreparer` (also
registered by `CgVfx.install`) has read its parts' meshes on a worker, uploaded their textures and precached its VFX
look, so a sequence spawning it reads, uploads and compiles nothing; a preview of its own registers one over its system
(`assets.preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(() -> mySystem))`):

```yaml
# assets/mymod/prefabs/lantern.yaml
_v: 1
nodes:
  - { id: n1, name: frame, meshes: [ { mesh: mymod:models/lantern/frame.glb, material: mymod:iron } ] }
  - id: n2
    name: flame
    parent: frame
    location: [0, 0.25, 0]
    values: { _Emissive: 2 }
    meshes: [ { mesh: mymod:models/lantern/flame.glb, material: mymod:flame, cast_shadow: false } ]
```

A skeletal node names its mesh and its materials; a parent's socket follows a `#`:

```yaml
nodes:
  - { id: n1, name: body, skeletal: { mesh: mymod:geo/wyvern.glb, play: idle }, materials: [ mymod:wyvern_skin ] }
  - { id: n2, name: armour, skeletal: { mesh: mymod:geo/wyvern_armour.glb, follow_pose: body }, materials: [ mymod:steel ] }
  - { id: n3, name: breath, parent: "body#jaw", effect: { effect: mymod:vfx/fire_breath, auto: false } }
```

| Key | Means |
|---|---|
| `skeletal: { mesh, play, follow_pose }` | a skeletal node; `materials:` beside it lists its materials, one a section |
| `parent: "node#socket"` | riding a skeletal node's socket or bone |
| `effect: { effect, auto }` | an effect node; `auto` is true unless written |

```java
Map<String, Object> tree = CgPrefabAssets.write(LANTERN);   // what CgAssetYaml writes as its file
CgPrefab lantern = CgVfxAssets.<CgPrefab>ref(CgBodyKinds.PREFAB, "mymod:lantern").get();
```

- **A node's `values`** are material values of its own over each of its parts' materials: it draws its materials
  derived with them (`drawnMaterials(part)`), shared by every node with the same values.
- **Its units convert**: a location and a scale through its file's axes, a rotation in its convention to a quaternion.
- **A mesh slot is kept as written**: several `materials` (one a section of its mesh), `cast_shadow`, an `overlay`
  compile, and are refused only by what plays the prefab and does not draw them, naming the node.

## Spawning one in a scene

```java
CgPrefabInstance lantern = scene.spawn(LANTERN).at(x, y, z).rotation(facing).spawn();
lantern.node("flame").scale(0.6f);               // a node is a CgSceneRoot: what rides it follows
lantern.mesh("flame").customDepth(1);            // a part is a CgMeshComponent like any other
lantern.mesh("flame", 1);                        // the node's second part
lantern.remove();                                // every component it made
```

| Call | Does |
|---|---|
| `scene.spawn(prefab)` | the instance, unspawned: place it with `at`, `rotation`, `scale`, or `attach(parent, socket)` to ride another |
| `spawn()` | a `CgSceneRoot` a node at its rest under its parent's, a `CgMeshComponent` a part riding its node |
| `root()`, `node(name)` | its own root, and a node's: a `CgSceneRoot`, or a skeletal node's `CgSkeletalMeshComponent` |
| `skeletal(name)` | a skeletal node's component; null for any other node |
| `effect(name)` | an effect node's play: `play()`, `stop()`, `playing()`; null for any other node |
| `mesh(name)`, `mesh(name, k)` | a node's parts, in its file's order; null where it has none |
| `remove()` | its effects stopped, then every component it made, its root last |

```java
CgPrefabInstance wyvern = scene.spawn(WYVERN).at(x, y, z).spawn();
wyvern.skeletal("body").anim().play(ROAR);
wyvern.effect("breath").play();                  // follows the jaw until stopped or removed
```

- **A part draws its materials** with its node's values over them, one a section of its mesh.
- **A node below an opacity of 0.001** spawns its parts hidden; a part's `cast_shadow` is its `castsShadow`.
- **Refused at `spawn()`, naming the node**: a part drawing the unit sphere, and any other mesh-slot setting.
- `--mode=scene-prefab` is the gate: a node riding its parent, and every form of the base surface material.

## Playing one as an effect

A sequence spawns one (`spawnables: - { prefab: mymod:core }`, `CgVfxPrefabSpawner`) and keys its nodes: their
transforms, opacity and material values. What a VFX look draws a prefab with is its `CgVfxPrefabLook`: a slot
renderer a material, on slots of its own. A material value a sequence keys is a parameter of a variant made for it,
starting at the node's value, else its material's, else its shader's default:

```java
CgVfxPrefabEffect flame = VFX.play(new CgVfxPrefabEffect(LANTERN, x, y, z).follow(hand));  // its own look
flame.pose().opacity(LANTERN.node("flame"), 0.5f);                                      // a node, as it stands

CgVfxPrefabLook keyed = CgVfxPrefabLook.of(LANTERN).keyed(Map.of("flame", List.of("_Emissive")));
CgVfxPrefabEffect effect = VFX.play(new CgVfxPrefabEffect(keyed, x, y, z));
effect.set((CgVfxParam.Scalar) keyed.param(LANTERN.node("flame"), "_Emissive"), 4f);

CgVfxLook mine = CgVfxLook.builder(SCHEMA).with(CgVfxPrefabLook.of(LANTERN).kit()).build();   // inside a look of yours
```

| `CgPrefabPose` call | Does |
|---|---|
| `place(matrix)` | where its roots stand, from the effect's origin |
| `location`, `rotation`, `scale`, `opacity(node, ...)` | a node's transform or opacity from now on, by its index (`prefab.node(name)`) |
| `reset()` | every node back at its rest |
| `evaluate()` | each node's matrix, parents first; the effect's draw calls it |
| `world(node)`, `opacity(node)` | a node's matrix from its place's origin, and its own opacity |

## Things to know

- **A node's opacity is its own** (as an Unreal component's is its material's): a parent at 0 leaves its
  children drawn. A game whose opacity passes down (CC2's) has it baked into each node at import. In an effect it reaches
  the shader as `FX_PRESENCE`, so a material fades only if it multiplies by it, as every engine shader does.
- **In a look, a material is one slot renderer however many parts use it**, on a slot of the prefab's own, so a prefab
  of hundreds of nodes and a few materials is a few draws: a look draws at most 256. A look draws one material a part;
  a part of several, or one setting `cast_shadow`, is spawned in a scene instead.
- **Its materials read no user parameter of yours** in an effect: a part's colour is its material's, its node's values,
  and the values a keyed look binds.
- **Transforms multiply as matrices, as glTF's do**: a rotated child under a non-uniformly scaled parent shears, as it
  did in the game it came from.
- Evaluating a pose is CPU matrix work, about 8 microseconds for 458 nodes; nothing allocates once it is made.
- Names are unique and a parent comes before its children; the builder throws otherwise.
- `--mode=vfx-prefab` is the effect's gate: 458 nodes, a subtree cut and a chain dimmed, played at `HERO` scalability
  (its pixel-unit scene would otherwise be culled by distance) once its look's kernels are compiled.

## The base surface material

A model with no shader of its own draws in the engine's base surface material, `CgSurfaceMaterial`: a texture with
Unreal's base properties over it, a blend mode, a shading model, two-sided and an emissive scale. A Minecraft render type
is these properties, never a name in the engine:

```java
static final CgMaterial SKIN = CgSurfaceMaterial.of("mymod:dragon_skin", "mymod:textures/entity/dragon.png").build();
static final CgMaterial CLOTH = CgSurfaceMaterial.of("mymod:dragon_cloth", "mymod:textures/entity/cloth.png")
        .blend(CgBlendMode.ALPHA).twoSided().build();
static final CgMaterial EYES = CgSurfaceMaterial.of("mymod:dragon_eyes", "mymod:textures/entity/eyes.png")
        .shading(CgShadingModels.UNLIT).emissive(2f).build();
static final CgMaterial GOLD = CgSurfaceMaterial.of("mymod:gold").tint(0xFFFFC030).build();           // no texture
static final CgMaterial CRACK = CgSurfaceMaterial.decal("mymod:crack", "mymod:textures/crack.png").build();   // a decal's
```

| Minecraft render type | Properties |
|---|---|
| `entitySolid` | `of(id, texture)`: opaque, lit by the world |
| `entityCutout`, `entityCutoutNoCull` | `.masked(0.1f)`, and `.twoSided()` for no-cull |
| `entityTranslucent` | `.blend(CgBlendMode.ALPHA)` |
| `eyes`, `entityTranslucentEmissive` | `.blend(CgBlendMode.ADDITIVE).shading(CgShadingModels.UNLIT)` |
| `entityDecal` | `CgSurfaceMaterial.decal(id, texture)`, on a `CgDecalComponent` |
| `outline` (glowing) | custom depth and the engine's outline (below) |

- **The colour** is the texture times the vertex colour and `tint`; its alpha is the opacity a blend mode or `masked`
  reads. The texture is sampled nearest and clamped, as an entity's is; `sampling(filter, address)` for a model's own.
- **Under `UNLIT` the emissive is the colour drawn**, at 1 unless `emissive` says otherwise; lit, it is a glow over the
  lit colour, 0 unless set.
- `definition()` is the same material as a definition, for a prefab's part or a kit.

# Skinned meshes: models with bones

A skinned mesh is geometry whose vertices follow the bones of a skeleton: a character, a beast, a tail. Each vertex
names up to four bones and how much it follows each; the skeleton names the bones and holds their rest pose; clips
move the bones over time. Unreal calls them a skeletal mesh, a skeleton and anim sequences.

## Loading one

From a glTF or GLB, the format every engine exports:

```java
CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:models/kurama.glb");
CgSkeleton bones = kurama.skeleton();
int pelvis = bones.indexOf("pelvis");                 // -1 if it has no bone by that name
kurama.mesh();                                        // the geometry: a submesh per glTF primitive
kurama.material(0);                                   // the material its first submesh names
CgAnimSequence attack = kurama.sequence("atk010");    // a clip that came in the same file
```

Clips of their own, in a file of clips alone (an animation exported without its mesh):

```java
List<CgAnimSequence> moves = CgMeshLoader.sequences("mymod:anims/moves.glb");
```

From a GeckoLib model, the Bedrock files Blockbench writes. The geometry bakes into a skinned mesh, each cube
following its bone, and each animation becomes a keyed clip of that model that plays exactly as GeckoLib plays it:

```java
CgGeckoModel saiyan = CgGeckoModel.load("rpgcore:geo/model.geo.json");      // CgMeshLoader.skeletal reads it too
CgSkeletalMesh body = saiyan.mesh();                                        // its locators are its sockets
for (CgGeckoAnimation a : saiyan.animations("rpgcore:animations/core/styles/default/idle.animation.json")) {
    CgAnimSequence clip = a.sequence();                                     // keyed: every easing as GeckoLib's
    CgGeckoAnimation.Loop loop = a.loop();                                  // PLAY_ONCE, LOOP, HOLD_ON_LAST_FRAME
}
```

- **A clip is its model's**: GeckoLib adds a clip's rotations to each bone's rest angles, so convert it for the model
  it plays on. A bone the model lacks is skipped, as GeckoLib skips it.
- **What fails at load**, naming the file, the animation, the bone and the channel: an easing GeckoLib does not name,
  a molang value (a constant only), keys out of time order, a bone whose parent is not in the file, a poly mesh.
- **The files are read as they are, never rewritten**: put them under `geo/` and `animations/` as GeckoLib does. Their
  markers are the clip's notifies ([`sequences.md`](sequences.md) § *A GeckoLib clip's markers*).

| Type | What it is | Unreal |
|---|---|---|
| `CgSkeletalMesh` | the geometry, its skeleton, the bind pose tying them, its materials, morph targets and clips | `USkeletalMesh` |
| `CgSkeleton` | bones by name, each with a parent and a rest pose; one root, parents before children | `FReferenceSkeleton` |
| `CgAnimSequence` | each animated bone's translation, rotation and scale at evenly spaced frames, and float curves; or, keyed, each channel a curve with keys at their own times (a GeckoLib clip) | `UAnimSequence` |
| `CgGeckoModel`, `CgGeckoAnimation` | a GeckoLib model baked into a skinned mesh, and its clips converted for it | none |
| `CgMorphTargets` | named per-vertex offsets mixed in by weight | `UMorphTarget` |

## Building one in Java

```java
CgSkeleton arm = CgSkeleton.builder()
        .bone("shoulder", null, new Vector3f(0, 1.4f, 0))
        .bone("elbow", "shoulder", new Vector3f(0, -0.3f, 0))
        .build();

CgMesh sleeve = CgMesh.build(CgVertexFormat.SKINNED, m -> {
    int a = m.vertex().position(0, 1.4f, 0).normal(1, 0, 0).uv(0, 0).bone(0, 1f).end();
    int b = m.vertex().position(0, 1.1f, 0).normal(1, 0, 0).uv(0, 1).bone(0, 0.5f).bone(1, 0.5f).end();
    int c = m.vertex().position(0, 0.8f, 0).normal(1, 0, 0).uv(1, 1).bone(1, 1f).end();
    m.triangle(a, b, c);
});

CgAnimSequence wave = CgAnimSequence.builder("wave", 30, 31)       // 31 keys at 30 a second: one second
        .track("elbow", new float[]{0, -0.3f, 0}, elbowRotations, new float[]{1, 1, 1})
        .build();

CgSkeletalMesh mesh = CgSkeletalMesh.builder(sleeve, arm).material(0, "mymod:sleeve").sequence(wave).build();
```

## A skinned mesh in the scene

A `CgSkeletalMeshComponent` is a skinned mesh placed in a `CgScene`, Unreal's `USkeletalMeshComponent`: where it
stands, its slots' materials, its overlay, which bones show. Spawned, it is posed and drawn every frame: nothing to
submit. What it plays is its `CgAnimInstance` (`anim()`), Unreal's `UAnimInstance`.

```java
CgSkeletalMeshComponent kurama = CgScene.client().skeletal(mesh)
        .materials(slot -> CgMaterial.of(kitMaterial(slot)))       // a material a slot, in slot order
        .at(x, y, z).rotation(facing).scale(2f)
        .spawn();
kurama.anim().play(mesh.sequence("idle"));
kurama.at(x + 1, y, z);                                             // moved
kurama.remove();
```

- **A slot with no material throws** at its draw, naming it: Unreal draws its default material there, and this engine
  has none.
- **Its bounds are its pose's**: each bone's reach (the farthest bind-pose vertex weighted most to it), found once a
  mesh, around the posed bones. A pose reaching far past the bind pose is never culled, and nothing is padded by hand.
  `bounds(box)` states its own instead.
- The client scene poses it with `CgAnimation` in `POSE` and places it, and what rides it, in `ATTACH`.

## Playing clips

What a component plays is its anim instance: one clip, or an anim graph fed by variables, with montages and sequences
on its slots. **The animation guide is [`../animation/guides/graphs.md`](../animation/guides/graphs.md).**

```java
CgAnimInstance anim = kurama.anim();
anim.play(mesh.sequence("idle"));                // one clip, looping
anim.graph(HERO).onUpdate((a, dt) -> a.set(SPEED, speed));   // or a graph
anim.slot("DefaultSlot").play(roar, 0.4f, 1f);   // this frame: a montage's clip over it
```

- **A clip binds to the skeleton by bone name ignoring case**, so Goku's `Root` track moves his `root`.
- **A clip's material curves** set the component's material parameters each frame (`scalar`, below), and put a
  parameter back when no clip keys it any more: Unreal's curves applied after evaluation.
- About 9 µs to pose a 228-bone puppet; past `crystalgraphics.animation.parallelAt` (8) instances, `POSE` splits them
  across workers.
- `--mode=skinned-play` shows them: Goku and Kurama, Tab to pick, N/B clips, P pause, `,` `.` a frame, U/I rate,
  K a Kamehameha charging on its right hand, X its cut.
- **While the game is paused** every instance moves 0 s and is still posed, so it still draws. A new level or
  dimension clears the client scene.

## Bone writes, mirroring and hiding

What code moves after the graph, a clip played on the other side, and bones not drawn. In the order a frame applies
them: the graph (its layers are the guide's), then each bone write, then hiding.

```java
CgAnimInstance anim = role.anim();

// Code after the graph: Unreal's Transform (Modify) Bone
CgBoneWrite look = anim.modify("head");
look.rotation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, yawPitch);   // each frame
CgBoneWrite drop = anim.modify("model");
drop.translation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, 0f, -0.125f, 0f).alpha(crouch);

// A clip authored for the right side, played on the left
CgMirrorTable mirror = CgMirrorTable.byName(mesh.skeleton(), CgMirrorTable.Axis.X).self("body").build();
anim.slot("DefaultSlot").play(swing, phase, 1f, mirror);

// Bones not drawn: the component's; their skinning collapses, never their pose
role.hideBone("head", CgBoneHide.BONE);              // the head's own vertices; a hat on a child bone stays
role.hideBone("left_arm", CgBoneHide.BRANCH);        // the arm and all below it, as Unreal's HideBoneByName
role.hideBoneFromOwner("head", CgBoneHide.BRANCH);   // the player does not see their own head
```

- **A bone write** applies scale, then rotation, then translation, each ignored, replaced or added to (an added
  rotation is `q * current`) in its own space: `COMPONENT` (the mesh's), `PARENT` (the bone's own local transform) or
  `BONE` (relative to where the bone is). Writes apply in the order made; a write in another space sees earlier writes.
  `alpha` blends it over the pose. A whole pose composed elsewhere is written bone by bone with `REPLACE` in `PARENT`.
- **Mirroring** is Unreal's mirror data table: pairs by name (`r_`/`l_`, `_l`/`_r`, `right`/`left`, ...), centre
  bones (`spine`, `root`, `pelvis`, `neck`, `head` ...) onto themselves, and anything else left as it is, so name your
  other centre bones with `self(name)`. A slot entry takes a table.
- **Hiding** keeps sockets and the bones below moving. `hideBoneFromOwner` costs a second palette only while a bone is
  hidden from the owner; the owner's view reads it with `palette(true)`.

### On a dedicated server

The server poses instances each game tick, for their bones and sockets, and draws nothing: no component, no scene.

```java
CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh));   // CgMeshLoader.skeletal reads headless
body.play(mesh.sequence("swing"), false);
Matrix4f hand = body.socket(body.socket("hand_r"), new Matrix4f());          // this tick's pose, in the mesh's space
```

## Sockets: attaching to a posed bone

A socket is a named place on a bone (Unreal's `USkeletalMeshSocket`), and every bone is a socket by its own name. What
rides one is placed there in `ATTACH`, after `POSE`, so it never lags the mesh.

```java
CgSkeletalMesh goku = CgMeshLoader.skeletal(path)
        .withSockets(List.of(CgSocket.of("right_hand", "hand_r", new Vector3f(0, -0.08f, 0.02f))));
CgSkeletalMeshComponent body = CgScene.client().skeletal(goku).materials(skins::get).at(x, y, z).spawn();

VFX.play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), x, y, z).follow(body.attachment("right_hand")));
CgMeshComponent staff = CgScene.client().mesh(staffMesh, wood).attach(body, "hand_r").spawn();
```

- **Found as Unreal finds one**: the mesh's socket of that name, else the bone of that name, ignoring case; a name
  that is neither stands where the component does (an attachment), or is refused (`attach`).
- **Where it is**: the component's placement × bone × socket, Unreal's `GetSocketTransform`.
- **An effect following a socket** takes its origin and turn each frame before it ticks; its named sockets
  (`socket_location` spawning) are the component's; it stops once the component is removed.
- **Late attach**: after the effects tick and before they draw (`LATE_ATTACH`), every followed point is read again, an
  entity's freshest mark included, so what draws at a point sits on it as it is drawn (Unreal's late update).
- **Any primitive's own place** is `attachment()`: an effect follows a lantern's root or a static mesh as it does a
  socket, and stops once it is removed.
- A loaded file carries no sockets: they come from the skeleton asset (`withSockets`) or `CgSkeletalMesh.Builder.socket`.

### Attaching one primitive to another

Hair, a tail, a hat or a staff is its own primitive hung from a body's bone: attach it to the body's socket (Unreal's
`AttachToComponent`). Any primitive attaches, to any primitive; only a skeletal one has sockets. While attached, `at`,
`rotation` and `scale` are the offset, turn and scale in the socket's space.

```java
CgSkeletalMeshComponent hair = scene.skeletal(hairMesh).materials(hairSkins::get).attach(body, "head").spawn();
hair.anim().modify("strand_0").rotation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, sway);   // its own bones

CgMeshComponent hat = scene.mesh(hatMesh, felt).attach(body, "head")
        .at(0, 0.45, 0).rotation(new Quaternionf().rotateY((float) Math.PI)).scale(1.1f)   // up the head, turned round
        .spawn();
hat.detach();                                                                           // stays where it last stood
```

- **A name the body has neither as a socket nor a bone is refused**, rather than placing the part at the body's feet;
  `attach(body, null)` rides where the body stands.
- **Attachments chain** (a ribbon on the hat on the head), parents always placed first. Attaching into a loop is
  refused. Removing a parent leaves what rode it where it stands.
- **Hiding the body's bone does not hide what hangs from it**: hide the part itself, for instance from its owner in
  first person with `hair.hideBoneFromOwner("root", CgBoneHide.BRANCH)`.

### Sharing a pose

A second mesh on the same skeleton drawn in a leader's pose, bone by name (Unreal's leader pose): armour, an outline
shell. It does not pose itself, and rides the leader's origin unless attached elsewhere.

```java
CgSkeletalMeshComponent armour = scene.skeletal(armourMesh).materials(steel::get).followPose(goku).spawn();
```

- A bone the leader lacks stays in its bind pose. `anim()` answers the leader's instance.

## Materials, overlay and CustomDepth

Each submesh draws in its slot's material, as Unreal's skinned mesh component draws its sections with
`GetMaterial(slot)`. Parameters are set through each slot's own instance, Unreal's dynamic material instance:

```java
goku.material(0, hair).material(1, skin);       // or materials(slot -> ...)
goku.scalar("OutlineColorType", 1f);            // on every slot whose material declares it
goku.vector("_Tint", 1f, 0.5f, 0.2f, 1f);
goku.resetScalar("OutlineColorType");           // back to each material's own value
goku.dynamicMaterial(4).set1f("_Glow", 2f);     // slot 4's own instance, made the first time
```

- **A slot's own instance is made the first time a parameter its material declares is set** (Unreal's
  `SetScalarParameterValueOnMaterials`); a name no slot declares makes none. A slot set to a `CgMaterialInstance` is
  set as it is.
- The shared material keeps its own values; `material(slot, m)` drops the slot's instance.

An overlay and CustomDepth, Unreal's `OverlayMaterial` and `bRenderCustomDepth`:

```java
goku.overlay(outline);                  // every slot again in it; overlay(slot, m) for one slot's own
goku.overlayMaxDistance(40f);           // beyond 40 blocks the overlay is not drawn (0: as far as the mesh)
goku.customDepth(1, 0xFF);              // into the view's custom depth with stencil 1, even behind a wall
```

- A slot's overlay replaces the mesh's for that slot; a slot with neither draws none.
- CustomDepth is tested against itself, not the scene: a post material compares it with the scene's depth to outline
  what is hidden (`render/scene/renderer/CLAUDE.md`, *CustomDepth*).

**The engine's outline** (`CgOutline`) is a toon outline round what writes one stencil value: depth, normal and
material-ID edges, widths and thresholds by distance (the technique of Dragon Ball Z: Kakarot's battle outline):

```java
CgOutline outline = CgOutline.add(1);          // round stencil 1, at the engine's look
goku.customDepth(1);
CgOutline.type(1).set(0xFFFFB030, 2.5f);       // a material writing outline type 1 is drawn in this
outline.close();
```

- A look of your own is a material naming `crystalgraphics:shaders/post/outline.shader` with its values, added with
  `CgOutline.add(material)`; `OutlinedModels` and `UntypedModels` say which shading models it outlines and which never
  take a type colour.
- What a material writes for it: `CG_CUSTOM_DATA_OUT(vec4(colour, id))` and `CG_SHADING_MODEL_OUT(model, type)`.
- The type colours are the `crystalgraphics:outline` collection, keyed by a sequence's global track.

## A host's own vertex path

A host that must see the posed vertices, as a shader pack lights and fogs a Minecraft entity only when its renderer
writes them, skins on the CPU (Unreal's CPU skinning):

```java
for (int s = 0; s < body.materialSlots(); s++) {
    VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(textures[s]));
    body.cpuSkin(s, firstPerson, (x, y, z, u, v, argb, nx, ny, nz) -> out.addVertex(...));   // quads, after ATTACH
}
```

- Positions are relative to where it stands, turned and scaled by its placement. Its mesh must keep its CPU copy.

## Entity actors

An entity of a host's world is drawn by an actor, Unreal's `AActor` bound to it: components made when the entity
enters, changed each frame, removed with it. Its root stands at the entity's interpolated pose before animation poses,
so everything added rides it. Registered once by entity type, on every era and loader.

```java
public final class DragonActor extends CgEntityActor {
    private CgSkeletalMeshComponent body;

    @Override protected void onSpawn(CgEntity dragon) {
        body = add(scene().skeletal(DRAGON_MESH).materials(DRAGON_SKINS::get).customDepth(1));
        body.anim().play(IDLE);
    }

    @Override protected void tick(CgEntity dragon, float dt) {
        body.scalar("Glow", dragon.flag(CgEntityQuery.SPRINTING) ? 2f : 0f);
    }
}

CgEntityActors.register("mymod:dragon", DragonActor::new);                    // at load
CgEntityActors.register("mymod:golem", GolemActor::new).keepVanillaModel();  // drawn beside the vanilla model
```

An actor whose components are an asset names its prefab: spawned on its root before `onSpawn`, removed with it, so its
code keeps only behaviour (Unreal's actor Blueprint):

```java
CgEntityActors.register("mymod:wyvern", WyvernActor::new).prefab("mymod:wyvern");

@Override protected void onSpawn(CgEntity wyvern) {
    wings = prefab().mesh("wings");                 // a node's part
    jaw = prefab().node("jaw");                     // a node: turn it, and what rides it follows
}
```

- **`add`** attaches a component to the root unless it rides something already, spawns it, and removes it with the
  actor.
- **A registration's prefab loads behind the loading screen** (the load task `crystalgraphics:actor-prefabs`), with the
  systems its effect nodes play and the pipelines its parts and skeletal meshes draw with, so the first actor draws as
  it spawns; one named while the game runs loads at its first spawn. A prefab that fails to load fails the spawn: the
  entity is left to its host.
- **The root** stands at the entity's feet, turned by its body yaw (`-bodyYaw` about up): +z, a glTF mesh's front,
  faces the entity's facing. A component keeps the placement it was built with as its offset from the root, so
  `.at`, `.rotation` and `.transform` may come before or after `add`.
- **`tick`** runs in `PLAYS`, before `POSE`: change components, draw nothing. An actor that throws is removed and its
  entity left to the host.
- **The type** is the entity's registry key, `CgEntityQuery.type(id)`: `minecraft:zombie`, `mymod:wyvern`. Below 1.11
  it is Forge's string with the namespace split off (`minecraft:Zombie`, a mod's `mymod.wyvern` as `mymod:wyvern`); a
  player is `minecraft:player` on every era.
- **Only entities that enter after `register`** get an actor: register at load.
- **In Minecraft** the entity's own renderer is swapped, inside the dispatcher's draw of that entity, for one that
  draws everything but the model: the name tag (its renderer's own, a player's score line included) and leash, the
  shadow at its renderer's radius, the fire and the hitbox. Every era from 1.7.10 to 26.3 and every loader, except
  Forge 1.13.2 and 1.14.3, which load no mixins: there the actor draws beside the vanilla model.
- **An inventory's player preview** draws through the same dispatcher, so a player with an actor shows there as its
  name tag alone.
- **An actor leaves with its entity**: the frame the client's entity query no longer finds it, or sooner where the
  host has a leave event. `CgEntity.hostFrame()` is the frame the host's renderer last reached it: behind while the host
  culls it.
- **What a host does**: `CgEntityActors.entered(id, type)` when an entity joins the client's level, `left(id)` when it
  leaves, and `replacesModel(id)` in its entity renderer, once an entity a frame. A harness scene does the same for fake
  entities, served by a `CgEntityQuery` it installs with `CgLevels.single(...)`.
- **The gauntlet's `crystalgraphics:actors`** proves a host's binding in a running game: an actor spawned for a summoned
  target with a prefab on its root, following it, its model left to the actor, and gone with it.

### On the server too, and moved by its root motion

A registered type's actor spawns on the server as well, for each entity of the type in the server's levels, from the
same class. There its components live in `CgScene.server()`: never drawn, its skeletal meshes posed each tick for
their bones, sockets and root motion. `server()` tells the two copies apart where they must differ.

Name the body whose moves carry the entity, and its montages' root motion moves it, as Unreal's character is moved by
its mesh. The server's actor moves its entity each tick, swept against blocks as the entity's own movement is, then
turns it. On a client, only the local player's actor moves its entity, at once; any other actor follows its server's
position.

```java
public final class FighterActor extends CgEntityActor {
    private CgSkeletalMeshComponent body;

    @Override protected void onSpawn(CgEntity fighter) {
        body = rootMotion(add(scene().skeletal(FIGHTER).materials(SKINS::get)));
    }
}

// the server, as a move starts: its sequence's montage now carries the entity, onto its target at arm's length
CgEntityActor actor = CgEntityActors.serverActor(entityId);
body.anim().motionWarping().target("opponent", x, y, z, yaw).standOff("opponent", 0.9f);
sequencer.play(LUNGE, Map.of("caster", role));
```

- **The entity's own movement still runs**: its AI and its gravity. Hold its AI still while a move plays.
- **The travel is the body's**: its component's world transform puts the clip's motion in the world, so a body
  scaled or lifted under the root moves the entity by what is drawn.
- **What a host does on the server**: `CgEntityActors.enteredServer(entity, type)` when an entity joins a server level
  and `leftServer(entity)` where the loader has a leave event, both sides; and the entity's movement through
  `CgEntityMovement`, the entity's own members on every era (`move` places it unswept on Forge 1.13.2).
- **The rest of a move's motion** is `CgEntityMovement`'s too, on the side that owns the entity: `push` for a
  knockback or a launch, `velocity` and `gravity(false)` to hold a fighter in the air, `resetFall` after a dive,
  `teleport` for a vanish strike, `rotate` for yaw and pitch.

### What the entity is doing: its state

`CgEntity.state()` is what the engine derives of the entity each frame before `tick`, the same on every version and on
both sides, never sent and never saved: how fast it moves, whether it is on the ground, sneaking, sprinting, swimming, crawling,
gliding, riding or sleeping, its head against its body, each hand's item and arm pose, the item in use and the swing,
and what just happened.

```java
@Override protected void tick(CgEntity fighter, float dt) {
    CgEntityState s = fighter.state();
    body.anim().set(SPEED, s.speed());                                  // blocks a second along the ground
    body.anim().set(IN_AIR, !s.grounded());
    if (s.armPose(CgEntityState.MAIN) == CgEntityState.ARM_BOW) body.anim().set(DRAW, s.useSeconds());
}

@Override protected void onSpawn(CgEntity fighter) {                    // events: heard each time they happen
    fighter.on(CgEntityEvents.LANDED, (e, none) -> {
        if (e.state().landHeight() > 3) body.anim().montages().play(HARD_LANDING);
    });
    fighter.on(CgEntityEvents.SWING_STARTED, (e, hand) -> body.anim().montages().play(hand == CgHand.OFF ? JAB : SLASH));
}
```

Any entity, watched by code that is no actor, keeps a state of its own:

```java
CgEntityState state = new CgEntityState();                          // one per entity, kept while it lives
double[] pose = new double[CgEntityQuery.POSE_LENGTH];
// each frame
if (entities.pose(id, partialTick, pose)) state.update(entities, world, id, partialTick, dt, pose);
```

| `CgEntityEvents.` | Carries | When |
|---|---|---|
| `JUMPED` | nothing | it left the ground going up (walking off a ledge is not a jump) |
| `LANDED` | nothing | it touched the ground again; `landHeight()` is the fall from the highest point it reached |
| `HURT` | nothing | it was hit, a second hit before the first wore off included; `secondsSinceHurt()` counts from it |
| `SWING_STARTED` | the `CgHand` | an arm began a swing, or began it again mid-swing |
| `USE_STARTED`, `USE_STOPPED` | the `CgHand` | it began or stopped using an item: eating, drawing a bow, blocking |
| `MOUNTED`, `DISMOUNTED` | nothing | it got on or off something (`vehicle()`); changing mounts raises both |
| `DIED` | nothing | it died; `secondsDead()` counts from it |

- **An event lasts one frame**: a listener hears it once, after the state's refresh and before `tick`; a state is
  asked `fired(CgEntityEvents.LANDED)` in that frame. The first frame an entity is seen raises none, so one seen in
  mid-air does not jump.
- **What only your server knows** (an ability fired, a hit's damage) is a `CgNetEvent` on your own networked type,
  sent by your server ([`networking.md`](networking.md)).
- **The arm pose is Minecraft's own rule for a player's arms**, the newest version's, for every entity: a bow drawn, a
  shield up, a crossbow charging or held charged, a trident raised, a spyglass, a horn, a brush, a spear. A bow or a
  crossbow takes both arms, and the other hand then only holds its item.
- **What a version lacks reads as not happening**: no swimming pose before 1.13, no gliding and no off hand before
  1.10.2, no charged crossbow before 1.14.3. Items are the host's raw ids, the same item for the session.
- **The swim amount** eases 0 to 1 over 0.45 s into the swimming pose and back, as Minecraft's own does.
- **Its motion is smooth between Minecraft's 20 ticks a second**: `speed()`, `verticalSpeed()`, `moveDirection()`
  (degrees against the body's facing) and `acceleration()` follow the last two ticks' movement across the frame, as
  the drawn position does, so a 144 fps gait never steps. `turnRate()` (degrees a second, left positive) and
  `groundDistance()` (the feet over the first surface under them, 64 blocks down at most) are read each frame;
  `airSeconds()` counts from leaving the ground.
- **Intent is not speed.** `input()` (0 to 1) and `inputDirection()` (degrees against the facing, 90 to the left) are
  how hard and where it means to move: what a locomotion graph keys its gait on, since a slope or a shove changes its
  speed. They are the player's own keys or stick on its client, and on a 1.21.3+ server from the player's input packet
  (`inputFromPlayer()`), with `sprintHeld()` and `jumpHeld()`. Anywhere else they are read from its movement against its
  facing: 1 moving (0.3 sneaking, as Minecraft's input), 0 standing, the sprint key as whether it sprints.
- **What a host does**: `CgEntityQuery.facts(id, partialTick, out)`, the entity's facts as `CgEntityFacts` (bits, how
  far it moved over its tick, its vehicle, Minecraft's hurt and death countdowns, the swing, the use, each hand's item
  and use animation). Core turns them into the state; a host never decides an event.

## Drawing a palette yourself

By hand, each frame, write the pose's skinning matrices, one a bone in the skeleton's order, then draw the mesh naming
where they start. Any material on the `Mesh` factory skins it with no code of its own: the mesh's format picks the material's skinned
variant (Unreal's GPU skin vertex factory), in every pass the material draws.

```java
Matrix4f[] skinning = ...;                                    // pose(bone) * inverseBind(bone), every bone
int base = CgBonePalettes.write(skinning, kurama.skeleton().boneCount());
CgScene.client().once().mesh(kurama.mesh(), skin).bones(base).at(x, y, z).add();

CgMesh posed = CgSkinning.skin(kurama.mesh(), skinning);      // the same pose on the CPU, as a static mesh
```

- **A base is this frame's**: write the palette and submit the draw in the same frame, from the client world's
  schedule as any world draw.
- **A skinned mesh must name its bones**, and only a skinned one may: either mistake throws at `submit()`.
- **It is culled by its bind pose's bounds**: a pose reaching past them needs `.bounds(...)`; `CgSkinBounds.posed` makes
  them from the pose, as a component does.
- In the shader, `cg_Position` and `cg_Normal` are the skinned vertex: position by the weighted sum of its bones' 3×4
  transforms, normal by the same matrix, renormalised. Then the draw's transform, as for any mesh.
- About 0.1 ns a vertex a pass (RTX 4070 SUPER, Vulkan): a 30,000-vertex character costs microseconds.
- `--mode=skinned-mesh` is the gate (the GPU's skinning against `CgSkinning`'s); `--mode=skinned-bench` measures it.

### In Minecraft's entity look

A character drawn into Minecraft looks like the game's own entities with the engine's entity materials: vanilla's
`rendertype_entity_cutout` and `rendertype_eyes`, the same formula on every version.

```java
static final CgMaterialDefinition BODY = CgMaterialDefinition.of("mymod:materials/hero",
        "crystalgraphics:shaders/materials/entity.shader")
        .texture("_MainTex", "mymod:hero", CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP).build();   // a texture asset
static final CgMaterialDefinition EYES = CgMaterialDefinition.of("mymod:materials/hero_eyes",
        "crystalgraphics:shaders/materials/entity_emissive.shader")
        .texture("_MainTex", "mymod:hero_eyes", CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP).build();

hero.material(0, BODY).material(1, EYES);    // hero: a CgSkeletalMeshComponent, spawned; it draws itself
```

- **`materials/entity.shader`**: Minecraft's two fixed lights on the normal (`CG_ENTITY_LIGHT0`, `CG_ENTITY_LIGHT1`, the
  Nether's where the host says so), cutout below `_Cutoff` (0.1), the lightmap at the draw's light and the world's
  fog. Translucent or double-sided is its `CgMaterialState`.
- **`materials/entity_emissive.shader`**: the glowing layer, eyes and glowmasks: no shading and no lightmap, added over
  what is behind, faded by the fog.
- **Their parameters** ride on top: `_Tint` multiplies by its alpha, `_Flash` is the overlay mixed over the texture by
  its alpha (the hurt flash is red at 0.3), and `_RimColor`, `_RimStrength`, `_RimPower` add a rim. Code sets them on
  every slot that declares them; a sequence's `material` track keys them by these names through the role's
  `CgMaterialParameters`:

  ```java
  hero.vector("_Flash", 1f, 0f, 0f, 0.3f);      // the hurt flash
  hero.resetVector("_Flash");                    // gone
  ```

### Under a shader pack

A shader pack (Iris, Oculus) lights, fogs and shadows only what goes through Minecraft's own entity render types, and
it draws the shadow map by calling each entity's renderer a second time. So while a pack is on, a character is skinned
on the CPU and its vertices written into the render type, from inside the entity's renderer: the pack treats it as
any mob, in both passes.

```java
// MyHeroRenderer.render(entity, yaw, partialTick, poseStack, buffers, light), 1.20.1
hero.visible(!CgShaderPacks.packInUse());     // the engine's own skinned draw only without a pack
if (CgShaderPacks.packInUse()) {
    PoseStack.Pose pose = poseStack.last();
    int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f);          // the hurt flash, as vanilla's
    for (int s = 0; s < hero.materialSlots(); s++) {
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURES[s]));
        hero.cpuSkin(s, false, (x, y, z, u, v, argb, nx, ny, nz) -> out.vertex(pose.pose(), x, y, z)
                .color(argb).uv(u, v).overlayCoords(overlay).uv2(light).normal(pose.normal(), nx, ny, nz).endVertex());
    }
}
```

- **Positions are relative to where the instance stands**, turned and scaled by its placement: place it where the
  renderer's pose stack stands (the entity's interpolated position), and the stack adds the camera.
- **Each call writes quads**, as entity render types draw them; the pack fills in its tangents and texture centres
  per quad.
- `CgShaderPacks.renderingShadowPass()` says the renderer is being called for the shadow map: skip what should cast
  no shadow (a glow layer).
- Under a pack, the engine's entity material is not used: a tint is yours to multiply into `argb`, and the hurt flash
  is the overlay, as above.
- Its mesh must keep its CPU copy (never `dropCpuCopy()` it). The mesh is decoded once; each call allocates nothing.

## Cooked

A release ships meshes cooked: `.cgmesh` holds the vertices as the engine lays them out, read in one copy, and
`.cganim` beside it the clips as columns. `CgMeshLoader` reads them by extension, the same calls:

```java
CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:cooked/kurama.cgmesh");   // kurama.cganim beside it, if any
byte[] mesh = CgCookedMesh.write(kurama), clips = CgCookedAnimation.write(kurama.sequences());
```

## Things to know

- **Four bones a vertex.** A vertex naming more fails the load, naming it. Weights are stored to 1/255, heaviest
  first, summing to exactly 1: give them as they are, and a bone given twice keeps its larger weight.
- **Up to 256 bones** a mesh in `CgVertexFormat.SKINNED`; a larger skeleton's mesh is `SKINNED_WIDE`, with 16-bit
  indices. The loader picks; `CgVertexFormat.skinned(bones)` says which.
- **The skeleton is the skins' joints and every node above one**, so an armature's own transform (which often stands
  the model up) is a bone too. One root; two bones of one name fail the load.
- **A clip is baked to evenly spaced frames** at the first of the usual rates (30, 24, 23.976, 25, 29.97, 48, 50, 60,
  ...) its length lands on, as Unreal imports one. A channel that never moves keeps one key.
- **Tracks name their bones**, so a clip plays on any skeleton that has them. A bone a clip does not animate holds its
  rest pose.
- **Names are kept as written**: what a socket or a sequence names a bone by.
