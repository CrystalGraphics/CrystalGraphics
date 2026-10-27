# api/mesh — meshes as data

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md) · plan: `plan/crystalgraphics/mesh-rewrite.md`

## What This Package Is

Geometry on the CPU, with no GPU anywhere: a `CgMesh` holds vertices in one `CgVertexFormat`, indices, submeshes,
bounds and a revision. `render/mesh/CgMeshStore` keeps the GPU copy up to date when it draws, so any thread may build
or edit one.

## Type Map

| Type | Role |
|------|------|
| `CgMesh` | The mesh: `build(format[, usage], body)`, `edit(body)` (replaces the contents), `writeVertices(first, bytes)` / `writeIndices(first, ints)` (overwrite part), `edit(context, body)` for an edit every frame, `submesh(i, firstIndex, count)`, `submesh(i, int[4])` for a draw, `setBounds(box)` / `autoBounds()` / `pad(r)`, `bounds(out)` to read them, `release()`. Readers take `changesSince(revision, changes)` and `readVertices` / `readIndices` |
| `CgMesh.quads(n)`, `CgMesh.vertices(n, topology)` | Shared meshes with no vertex data (`CgVertexFormat.NONE`, `#type none`): a shader places each vertex from `CG_VERTEX_ID`. Drawn with ranges and stated bounds (`CgWorldRenderer.Draw.indices`, `.bounds`) |
| `CgMesh.Usage` | `STATIC`, `DYNAMIC`, `FRAME`, `GPU_ONLY`. The store places the first, second and last in its pools, a new range per edit and the old freed once its frames retire; a `GPU_ONLY` mesh then drops its CPU copy (`dropCpuCopy`): reads and partial writes throw, a whole edit writes a new copy. A `FRAME` mesh is written straight from its CPU copy into a page of the frame ring each frame it draws, aligned to its stride; a full page chains another and none is ever grown (`render/mesh/CgMeshRing`): no range, no release, forgotten after a frame without it (`-Dcrystalgraphics.mesh.frameRing=false` puts it in the pools). The GPU reads the ring from memory the CPU writes, more slowly than a slab, so a mesh drawn many times a frame stays `DYNAMIC`. A mesh of another usage edited in 60 frames running is counted (`mesh.edited-every-frame`) and named once in the log (`[cg-mesh]`; `-Dcrystalgraphics.mesh.editStacks=true` adds where the edit was made). `reserve(vertices, indices)` sizes any mesh's storage so its edits allocate nothing |
| `CgMeshWriter` | What `build` and `edit` hand their body: a vertex's attributes in any order, `end()` naming any missing; semantic setters for one the format lacks do nothing; `set`/`setInt` by attribute index; `bone(index, weight)` for a skinned vertex, resolved at `end()` as Unreal's `FBoneWeights` (merged by bone, quantised, heaviest first, the rounding carried so the four sum to 255); `triangle`/`quad`/`line`/`index`; `submesh()` |
| `CgSkeletalMesh` | Unreal's `USkeletalMesh`: a `SKINNED` or `SKINNED_WIDE` mesh, its `api.animation.CgSkeleton`, the inverse binds (the mesh's, not the skeleton's), a material per submesh, `CgMorphTargets`, the sequences that came with it. `builder(mesh, skeleton)` binds at the rest pose unless `inverseBind(bone, m)` says otherwise |
| `CgCookedMesh` | `.cgmesh`: the vertices as the format lays them out (one copy at load through `CgMeshWriter.copyVertices`), indices, submeshes and materials, and a skinned mesh's skeleton, binds and morph targets. Little-endian, versioned with the engine |
| `CgSubmesh` | A part drawn on its own: first index, index count, first vertex, vertex count. Its indices count from its first vertex |
| `CgMeshChanges` | A reader's reused holder: the revision now, `all`, and the vertex and index ranges touched since the revision it last read |
| `CgMeshLoader` | Files: `load(path)` and `model(path)` into `SPATIAL` (glTF's `COLOR_0` and `TEXCOORD_1` to `_3` included), or `load(path, format)` and `model(path, format)` -- OBJ, glTF, GLB by extension, every material group or primitive a submesh with the material it names (`Model.material(i)`). Cached per path and format, and shared; `read(stream, extension, format)` gives an uncached mesh of the caller's own. `skeletal(path)` a `CgSkeletalMesh` (glTF, GLB, or `.cgmesh` with its `.cganim` beside), `sequences(path)` a file's clips alone. `CgObjLoader` and `CgGltfLoader` are its package-private readers; `CgGltfLoader` follows Unreal's Interchange glTF importer (skins appended by joint name, clips baked at the usual frame rate their length lands on, its names for unnamed objects) and cites it |
| `CgMeshLods` | One shape at falling detail, Unity's LODGroup: `builder().level(mesh, screenHeight)...build()`, a level held while a draw covers at least that fraction of the screen's height, nothing drawn below the last; `of(mesh)` one mesh at every height, `reach()` the finest level's farthest corner from its origin. `CgWorldRenderer.draw(lods, material)` picks per draw. `CgMeshShapes.sphereLods()` is a shared one: 128 sectors down to 8, each held until the next coarser silhouette strays half a pixel at 1080 lines |
| `CgMeshShapes` | Shapes two ways: shared (one mesh per format and size, refusing edits) and writer forms that compose with anything else in a mesh |
| `CgProceduralMesh` | Effect meshes from a few numbers, ported from Effekseer: `revolve(profile)` (crescents, domes, cones, swirls) and `ribbons(profile)`, over `sphere`/`cone`/`cylinder`/`spline` profiles, deformed by tilt, wave and curl (`CgMeshNoise`, Effekseer's curl noise). SPATIAL, smooth normals, a new mesh per `build()` |
| `CgMeshTopology` | Triangles, strips, lines, points; each backend maps it to its own mode |

## Rules

- **No GL, no device** in any type here.
- **Hot paths do not allocate**: a mesh reuses its writer and swaps arrays with it on every `edit`; a reader reuses
  its `CgMeshChanges`.
- **What a vertex is not given, `end()` fills where it can**: colour white, a UV set the set before it (Unreal's null
  colour stream and repeated UV channel); anything else missing throws, naming it.
- **A shape writes position, UV, normal and white colour**: one shape serves every format made of those. A loaded
  file the same, colour from glTF's `COLOR_0` where it has one.
- **`de.javagl:obj` and `jgltf-model` are `compileOnly`**: a game must carry them for the loader to run (the harness
  and core's tests do).
- `CgMeshShapesTest` pins every shape to its bytes (counts and CRC-32s): the float formats' rows are the deleted GL-era builder's, `SPATIAL`'s vertex bytes pinned again when it was packed.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
