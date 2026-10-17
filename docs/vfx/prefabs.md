# Prefabs: models made of parts

A prefab is a model built from parts that move and fade together: the core of a Rasen Shuriken, its rings and blades,
a lens of nested balls. It is a tree of nodes, each with a parent, a rest transform, an opacity and the meshes it
draws, each with a material. Unreal calls it an actor and its component tree; CC2 a clump. An effect plays one; its
look draws it.

## Building one

```java
// A material: a layer's shader and settings, with no slot. One builder object is one material.
static final CgVfxLayer.Builder LENS = CgVfxLayer.of("mymod:shaders/vfx/lens.shader").bind("_Color", CORE);
static final CgVfxLayer.Builder GLOW = CgVfxLayer.of("crystalgraphics:shaders/emission_only.shader");

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
| `part(material, mesh)` | a mesh it draws with a material: a `CgMesh`, `CgMeshLods` or a path loaded at its first draw; `part(material)` draws the material's own mesh, else the unit sphere |

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
- **A material is one layer however many parts use it.** The kit holds a layer per distinct material builder, so a
  prefab of hundreds of nodes and a few materials is a few layers: a look draws at most 256.
- **Transforms multiply as matrices, as glTF's do**: a rotated child under a non-uniformly scaled parent shears, as it
  did in the game it came from.
- Evaluating is CPU matrix work, about 8 microseconds for 458 nodes; nothing allocates once the instance is made.
  Each node's part is a draw, and draws of one material batch.
- Names are unique and a parent comes before its children; the builder throws otherwise.
- `--mode=vfx-prefab` is the gate: 458 nodes, a subtree cut and a chain dimmed.
