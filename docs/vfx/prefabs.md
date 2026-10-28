# Prefabs: models made of parts

A prefab is a model built from parts that move and fade together: the core of a Rasen Shuriken, its rings and blades,
a lens of nested balls. It is a tree of nodes, each with a parent, a rest transform, an opacity and the meshes it
draws, each with a material. Unreal calls it an actor and its component tree; CC2 a clump. An effect plays one; its
look draws it.

## Building one

```java
// A material asset: one definition is one material, however many parts draw with it.
static final CgMaterialDefinition LENS = CgMaterialDefinition.of("mymod:materials/lens",
        "mymod:shaders/vfx/lens.shader").build();
static final CgMaterialDefinition GLOW = CgMaterialDefinition.of("mymod:materials/lens_glow",
        "crystalgraphics:shaders/emission_only.shader").build();

static final CgVfxPrefab LENS_CORE = CgVfxPrefab.builder("mymod:lens_core")
        .node("ball03")
        .node("ball02").parent("ball03").at(0f, 1f, 0f).part(LENS, "mymod:models/core/ball02.glb")
        .node("ball01").parent("ball02").scale(0.5f).opacity(0.5f)
            .part(LENS, "mymod:models/core/ball01.glb")
            .part(GLOW, "mymod:models/core/ball01.glb")            // a second mesh on the same node
        .build();

static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(LENS_CORE.kit()).build();
```

| Call | Sets |
|---|---|
| `node(name)` | a new node, a root at the origin unless told otherwise; the calls after it set it |
| `parent(name)` | its parent, a node named before it |
| `at(x, y, z)`, `rotation(x, y, z, w)`, `scale(...)` | its rest transform in its parent's space; the rotation a quaternion |
| `opacity(a)` | its own opacity, 0 to 1 |
| `part(material, mesh)` | a mesh it draws with a material: a `CgMesh`, `CgMeshLods`, or a file or provided mesh's id loaded at its first draw; `part(material)` draws the unit sphere |

## As a file

A prefab is a `prefabs/` asset, compiled by `CgVfxPrefabAssets` (which `CgVfx.install` registers) and written back by
it, so a prefab built in Java and its file are one model. On a client it is `READY` once `CgVfxPrefabPreparer` (also
registered by `CgVfx.install`) has read its parts' meshes on a worker, uploaded their textures and prepared its look,
so a sequence spawning it reads, uploads and compiles nothing; a preview of its own registers one over its system
(`assets.preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(() -> mySystem))`):

```yaml
# assets/mymod/prefabs/core.yaml
_v: 1
nodes:
  - { id: n1, name: ball03 }
  - id: n2
    name: ball02
    parent: ball03
    location: [0, 1, 0]
    values: { _Glare: 0.2 }
    meshes: [ { mesh: mymod:models/core/ball02.glb, material: mymod:lens } ]
```

```java
Map<String, Object> tree = CgVfxPrefabAssets.write(CORE);   // what CgAssetYaml writes as its file
CgVfxPrefab core = CgVfxAssets.<CgVfxPrefab>ref(CgBodyKinds.PREFAB, "mymod:core").get();
```

- **A node's `values`** are material values of its own over each of its parts' materials: it draws a material derived
  with them (`value(property, ...)` in Java), shared by every node with the same values.
- **Its units convert**: a location and a scale through its file's axes, a rotation in its convention to a quaternion.
- **A mesh slot is kept as written**: several `materials`, an `overlay`, `cast_shadow` compile, and are refused only
  where a prefab would draw them (`requireDrawable`), naming the node.

A sequence spawns one (`spawnables: - { prefab: mymod:core }`, `CgVfxPrefabSpawner`) and keys its nodes: their
transforms, opacity and material values. A material value a sequence keys is a parameter of a variant made for it,
starting at the node's value, else its material's, else its shader's default:

```java
CgVfxPrefab keyed = CORE.keyed(Map.of("ball01", List.of("_U0_LocX")));
CgVfxPrefabEffect effect = VFX.play(new CgVfxPrefabEffect(keyed, x, y, z));   // its own look, the prefab's
effect.set(keyed.param(keyed.node("ball01"), "_U0_LocX"), -0.5f);
```

## Playing one

```java
public final class Shuriken extends CgVfxEffect<Shuriken> {
    private final CgVfxPrefabInstance core = new CgVfxPrefabInstance(LENS_CORE);
    private final int ball01 = LENS_CORE.node("ball01");
    private final Matrix4f spin = new Matrix4f();

    @Override protected void tick(float dt) {
        core.place(spin.rotationY(age * 6f));                 // its roots, from the effect's origin
        core.opacity(ball01, 1f - age / 2f);                  // fades ball01's parts; its children keep their own
    }

    @Override protected void submit(CgVfxFrame frame) {
        core.submit(this, frame);
    }
}
```

| Call | Does |
|---|---|
| `place(matrix)` | where its roots stand, from the effect's origin |
| `location`, `rotation`, `scale`, `opacity(node, ...)` | a node's transform or opacity from now on, by its index (`prefab.node(name)`) |
| `reset()` | every node back at its rest |
| `evaluate()` | each node's matrix and presence, parents first; `submit` calls it |
| `world(node)`, `presence(node)` | a node's matrix from the effect's origin, and its own opacity |
| `submit(effect, frame)` | evaluates, then draws each node's parts at its presence |

## Things to know

- **A node's opacity is its own** (as an Unreal component's is its material's): a parent at 0 leaves its
  children drawn. A game whose opacity passes down (CC2's) has it baked into each node at import. It reaches the shader as `FX_PRESENCE`, so a material fades only if it multiplies by it, as every engine
  shader does.
- **A material is one slot renderer however many parts use it.** The kit holds a slot renderer per distinct material,
  on a slot of the prefab's own, so a prefab of hundreds of nodes and a few materials is a few draws: a look draws at
  most 256.
- **Its materials read no user parameter of yours**: a part's colour is its material's, its node's values, and the
  values a `keyed` variant binds.
- **Transforms multiply as matrices, as glTF's do**: a rotated child under a non-uniformly scaled parent shears, as it
  did in the game it came from.
- Evaluating is CPU matrix work, about 8 microseconds for 458 nodes; nothing allocates once the instance is made.
  Each node's part is a draw, and draws of one material batch.
- Names are unique and a parent comes before its children; the builder throws otherwise.
- `--mode=vfx-prefab` is the gate: 458 nodes, a subtree cut and a chain dimmed.

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

| Type | What it is | Unreal |
|---|---|---|
| `CgSkeletalMesh` | the geometry, its skeleton, the bind pose tying them, its materials, morph targets and clips | `USkeletalMesh` |
| `CgSkeleton` | bones by name, each with a parent and a rest pose; one root, parents before children | `FReferenceSkeleton` |
| `CgAnimSequence` | each animated bone's translation, rotation and scale at evenly spaced frames, and float curves | `UAnimSequence` |
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

## Playing clips

A `CgAnimInstance` is a skinned mesh posed by the engine: clips placed as sections on a timeline of its own,
evaluated every frame in `POSE` on workers by `CgAnimation`, and its palette written for the draw.

```java
CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));
kurama.play(CgAnimSection.of(mesh.sequence("idle")).until(Double.POSITIVE_INFINITY).build());   // loops for ever
kurama.play(CgAnimSection.of(mesh.sequence("roar")).at(2.0).easeIn(0.25f).easeOut(0.25f).build());

// SUBMIT, the same frame: POSE has written this frame's palette
world.draw(mesh.mesh(), skin).bones(kurama.palette()).at(x, y, z).submit();

kurama.seek(3.25);              // the pose playing reaches at 3.25 s, exactly
kurama.playing(false);          // held; kurama.rate(0.5f) plays at half speed
CgAnimation.client().remove(kurama);
```

| Section setting | Does (Unreal's skeletal animation section) |
|---|---|
| `at(t)`, `until(t)` | when it plays on the instance's timeline; past the clip's length it loops, else it holds its last frame |
| `playRate(r)` | clip seconds a timeline second; negative plays from the end |
| `startOffset`, `endOffset`, `firstLoopOffset` | clip seconds cut from each loop's start and end, and how far into the clip the first loop starts |
| `reverse()`, `loop()` | played backwards; looped however long it lasts |
| `weight(w)`, `easeIn(s)`, `easeOut(s)` | its weight, rising and falling over those seconds along Cubic InOut (`CgEasings.IN_OUT_CUBIC`), or the `CgEasing` given |

- **Sections cross-fade by their weights, normalised**: one easing in alone plays at full weight, as Sequencer's does;
  only two overlapping blend. Where none plays, the rest pose.
- **A clip binds to the skeleton by bone name ignoring case**, so Goku's `Root` track moves his `root`.
- **A pose is a pure function of the instance's time**: a seek, a replay or a late joiner lands on the same pose,
  bit for bit.
- **A montage slot plays over its sections for one pose**: `slot(clip, position, weight)`, set every frame it should
  hold and cleared by the next evaluation, Unreal's `DefaultSlot`. Its sections keep `1 -` the slot's total weight;
  past 1 the slot's entries are normalised and its sections are gone. A sequence's `animation` track sets it on its
  role (`sequences.md` § *Playing a sequence*).

```java
goku.slot(roar, 0.4f, 1f);      // this pose: roar at 0.4 s over whatever his sections play
```
- By hand, without `CgAnimation`: `instance.advance(dt).evaluate()`, then `instance.skinning()` into
  `CgBonePalettes.write`. Lower down, `clip.sample(pose, time)` into a `CgPose`, `pose.blend(...)`, `pose.resolve()`.
- About 9 µs to pose a 228-bone puppet; past `crystalgraphics.animation.parallelAt` (8) instances, `POSE` splits them
  across workers.
- `--mode=skinned-play` shows them: Goku and Kurama, Tab to pick, N/B clips, P pause, `,` `.` a frame, I/O rate,
  K a Kamehameha charging on its right hand.

## Sockets: attaching to a posed bone

A socket is a named place on a bone (Unreal's `USkeletalMeshSocket`), and every bone is a socket by its own name. An
effect following one rides the bone as `POSE` posed it that frame, so it never lags the mesh.

```java
CgSkeletalMesh goku = CgMeshLoader.skeletal(path)
        .withSockets(List.of(CgSocket.of("right_hand", "hand_r", new Vector3f(0, -0.08f, 0.02f))));
CgAnimInstance body = CgAnimation.client().add(new CgAnimInstance(goku)).place(x, y, z, turnAndScale);

VFX.play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), x, y, z).follow(body, "right_hand"));   // or "hand_r"
world.draw(body, skin).pad(2f).submit();                                                         // SUBMIT

int hand = body.socket("right_hand");          // looked up once
body.socket(hand, point, turn);                // this frame's, after POSE: absolute place and turn
```

- **Found as Unreal finds one**: the mesh's socket of that name, else the bone of that name, ignoring case; a name
  that is neither stands where the instance does.
- **Where it is**: `place(x, y, z, transform) × bone × socket`, the instance's placement being Unreal's
  component-to-world. `world.draw(instance, material)` draws it there.
- **An effect following a socket** takes its origin and turn each frame before it ticks; its named sockets
  (`socket_location` spawning) are the instance's; it stops once the instance is removed from `CgAnimation`.
- **Late attach**: after the effects tick and before they draw (`LATE_ATTACH`), every followed point is read again, an
  entity's freshest mark included, so what draws at a point sits on it as it is drawn (Unreal's late update).
- A loaded file carries no sockets: they come from the skeleton asset (`withSockets`) or `CgSkeletalMesh.Builder.socket`.

## Drawing one

A posed instance draws itself, each submesh in its slot's material, as Unreal's skinned mesh component draws its
sections with `GetMaterial(slot)`:

```java
for (int s = 0; s < goku.materialSlots(); s++) goku.material(s, CgMaterial.of(kitMaterial(s)));   // its prefab's, in slot order
world.draw(goku);                       // SUBMIT: every slot, skinned by this frame's palette, where it stands
world.draw(kurama, skin).submit();      // or the whole mesh in one material
goku.boundsScale(1.5f);                 // Unreal's BoundsScale: culled by its bind pose's bounds grown about their centre
```

- **A slot with no material throws**, naming it: Unreal draws its default material there, and this engine has none.
- Its bounds are the bind pose's, grown by `boundsScale` (1 unless set): a pose reaching far past them needs more.

An overlay and CustomDepth, Unreal's `OverlayMaterial` and `bRenderCustomDepth`:

```java
goku.overlay(outline);                  // every slot again in it; overlay(slot, m) for one slot's own
goku.overlayMaxDistance(40f);           // beyond 40 blocks the overlay is not drawn (0: as far as the mesh)
goku.customDepth(1, 0xFF);              // into CgFrameKeys.CUSTOM_DEPTH with stencil 1, even behind a wall
world.draw(goku);
```

- A slot's overlay replaces the mesh's for that slot; a slot with neither draws none.
- CustomDepth is tested against itself, not the scene: a post material compares it with the scene's depth to outline
  what is hidden (`render/world/CLAUDE.md`, *CustomDepth*).

By hand, each frame, write the pose's skinning matrices, one a bone in the skeleton's order, then draw the mesh naming
where they start. Any `#type spatial` material skins it with no code of its own: the mesh's format picks the material's skinned
variant (Unreal's GPU skin vertex factory), in every pass the material draws.

```java
Matrix4f[] skinning = ...;                                    // pose(bone) * inverseBind(bone), every bone
int base = CgBonePalettes.write(skinning, kurama.skeleton().boneCount());
world.draw(kurama.mesh(), skin).bones(base).at(x, y, z).submit();

CgMesh posed = CgSkinning.skin(kurama.mesh(), skinning);      // the same pose on the CPU, as a static mesh
```

- **A base is this frame's**: write the palette and submit the draw in the same frame, from the client world's
  schedule as any world draw.
- **A skinned mesh must name its bones**, and only a skinned one may: either mistake throws at `submit()`.
- **It is culled by its bind pose's bounds**: a pose reaching past them needs `.bounds(...)` or `.pad(r)`.
- In the shader, `cg_Position` and `cg_Normal` are the skinned vertex: position by the weighted sum of its bones' 3×4
  transforms, normal by the same matrix, renormalised. Then the draw's transform, as for any mesh.
- About 0.1 ns a vertex a pass (RTX 4070 SUPER, Vulkan): a 30,000-vertex character costs microseconds.
- `--mode=skinned-mesh` is the gate (the GPU's skinning against `CgSkinning`'s); `--mode=skinned-bench` measures it.

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
