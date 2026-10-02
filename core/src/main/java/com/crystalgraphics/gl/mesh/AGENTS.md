# gl/mesh — Static GPU Mesh System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Static GPU mesh construction, loading, and storage. Unlike the streaming batch path
(which owns transient per-frame VBOs via `CgStreamBuffer`), this package manages
immutable geometry that is uploaded once and drawn many times (`GL_STATIC_DRAW`).

## Type Map

| Type | Role |
|------|------|
| `CgMeshBuilder` | Procedural mesh factory. All vertex packing via `CgVertexWriter.forBuffer()`. Five static methods: `unitCube`, `quad2D`, `plane`, `uvSphere`, `icosahedron`. Semantic presence (UV/COLOR/NORMAL) checked per-format to respect the step machine. Index type: u16 if vertexCount ≤ 65535, else u32. |
| `CgObjLoader` | Loads OBJ files via `de.javagl:obj`. Uses `ObjUtils.convertToRenderable()` for triangulation + single-indexing. Packs vertices via `CgVertexWriter.forBuffer()`. `load(InputStream, format)` → one `CgMeshData`; `loadAll(...)` → list (currently single-group). |
| `CgGltfLoader` | Loads glTF/GLB files via `de.javagl:jgltf-model`. Rejects skinned meshes (JOINTS_0 / WEIGHTS_0). Extracts POSITION, TEXCOORD_0, NORMAL accessors. Packs via `CgVertexWriter.forBuffer()`. `loadFirstPrimitive(stream, format)` and `loadPrimitive(stream, meshIdx, primIdx, format)`. |
| `CgMeshLoader` | Unified facade. `load(resourcePath, format)` auto-detects `.obj` vs `.gltf`/`.glb` by extension. `loadObj()` / `loadGltf()` for explicit dispatch. |
| `CgMesh` | Since mesh rewrite M3, a holder of an `api/mesh/CgMesh` (`mesh()`, through `CgMeshSource`): what the graph and the world renderer draw, from `render/mesh/CgMeshStore`'s pools. `upload(...)` reads the buffers at once, on any thread. `drawDirect()`/`drawInstanced(n)` draw immediately from GL objects of its own, made at the first such draw. `delete()` releases the pooled copy and those objects. Goes in M5 |

## Key Design Rules

- **No `ByteBuffer.putFloat()` for vertex data** in builders or loaders — all vertex
  packing goes through `CgVertexWriter.forBuffer()`. Index buffer `putShort()`/`putInt()`
  is allowed (index data is geometric, not semantic vertex data).
- **Semantic detection before writer calls** — each builder/loader checks which
  semantics (UV, COLOR, NORMAL) the format contains before calling the corresponding
  writer methods, because the step machine in `CgVertexWriter` throws on out-of-order
  calls for absent semantics.
- **IBO binding order** — the IBO is bound while the VAO is bound, and the VAO is unbound
  before the IBO is unbound. Inverting this order writes null into the VAO's element array
  buffer slot.
- **An indexed draw behind a VAO binds its index buffer again first**: LWJGL 2 checks the
  offset against the element binding it saw bound, never the VAO's, and throws before the
  driver. `CgMesh`'s immediate draws and `CgMeshStore.draw` both do.

## Index Type Policy

| Condition | Index type |
|-----------|-----------|
| vertexCount ≤ 65535 | `GL_UNSIGNED_SHORT` (2 bytes per index) |
| vertexCount > 65535 | `GL_UNSIGNED_INT` (4 bytes per index) |

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/mesh/` | Produces and consumes `CgMeshData` and `CgMeshTopology` |
| `api/vertex/` | `CgVertexFormat` lays out the vertices and drives the VAO's attribute loop |
| `gl/buffer/staging/` | `CgVertexWriter.forBuffer()` is used for all vertex packing |
| `gl/vertex/` | `CgVertexArray.createRawVaoId()` / `bind()` / `deleteRaw()` used by `CgMesh` |
