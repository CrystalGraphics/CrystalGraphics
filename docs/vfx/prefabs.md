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

## Playing one

```java
public final class Shuriken extends CgVfxEffect<Shuriken> {
    private final CgVfxPrefabInstance core = new CgVfxPrefabInstance(LENS_CORE);
    private final int ball01 = LENS_CORE.node("ball01");
    private final Matrix4f spin = new Matrix4f();

    @Override protected void tick(float dt) {
        core.place(spin.rotationY(age * 6f));                 // its roots, from the effect's origin
        core.opacity(ball01, 1f - age / 2f);                  // fades ball01 and everything under it
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
| `world(node)`, `presence(node)` | a node's matrix from the effect's origin, and its opacity times its ancestors' |
| `submit(effect, frame)` | evaluates, then draws each node's parts at its presence |

## Things to know

- **A node's opacity multiplies down its subtree**: half a parent's and half its own draws a quarter, and 0 hides the
  subtree. It reaches the shader as `FX_PRESENCE`, so a material fades only if it multiplies by it, as every engine
  shader does.
- **A material is one slot renderer however many parts use it.** The kit holds a slot renderer per distinct material,
  on a slot of the prefab's own, so a prefab of hundreds of nodes and a few materials is a few draws: a look draws at
  most 256.
- **Its materials read no user parameter**: a part's colour is its material's, and its node's opacity fades it.
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
- Playing clips, posing, drawing skinned and sockets on bones are their own sections as they arrive.
