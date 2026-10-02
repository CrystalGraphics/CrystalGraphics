# api/mesh — meshes as data

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md) · plan: `plan/crystalgraphics/mesh-rewrite.md`

## What This Package Is

Geometry on the CPU, with no GPU anywhere: a `CgMesh` holds vertices in one `CgVertexFormat`, indices, submeshes,
bounds and a revision. A renderer keeps the GPU copy up to date when it draws (the mesh store, M3), so any thread
may build or edit one.

**Mid-rewrite.** Every draw comes from `render/mesh/CgMeshStore` since M3; `CgMeshData` and `gl/mesh/CgMesh` (now a
holder of a `CgMesh`) stay until M5 moves their callers and deletes them.

## Type Map

| Type | Role |
|------|------|
| `CgMesh` | The mesh: `build(format[, usage], body)`, `edit(body)` (replaces the contents), `writeVertices(first, bytes)` / `writeIndices(first, ints)` (overwrite part), `edit(context, body)` for an edit every frame, `submesh(i, firstIndex, count)`, `submesh(i, int[4])` for a draw, `bounds(...)` / `pad(r)`, `release()`. Readers take `changesSince(revision, changes)` and `readVertices` / `readIndices` |
| `CgMesh.Usage` | `STATIC`, `DYNAMIC`, `FRAME` (the frame ring), `GPU_ONLY` (CPU copy dropped after upload) |
| `CgMeshWriter` | What `build` and `edit` hand their body: a vertex's attributes in any order, `end()` naming any missing; semantic setters for one the format lacks do nothing; `set`/`setInt` by attribute index; `triangle`/`quad`/`line`/`index`; `submesh()` |
| `CgSubmesh` | A part drawn on its own: first index, index count, first vertex, vertex count. Its indices count from its first vertex |
| `CgMeshChanges` | A reader's reused holder: the revision now, `all`, and the vertex and index ranges touched since the revision it last read |
| `CgMeshShapes` | Shapes two ways: shared (one mesh per format and size, refusing edits) and writer forms that compose with anything else in a mesh |
| `CgMeshTopology` | Triangles, strips, lines, points. Still carries GL modes until M5 |
| `CgMeshData` | The old CPU holder `gl/mesh/CgMesh.upload` takes. Goes in M5 |
| `CgMeshSource` | What `CgChunkBuilder.draw` and `CgWorldRenderer.draw` take, so both mesh classes reach them. Goes in M5 |

## Rules

- **No GL, no device** in any type here except `CgMeshTopology`'s GL constant, which M5 moves to the backends.
- **Hot paths do not allocate**: a mesh reuses its writer and swaps arrays with it on every `edit`; a reader reuses
  its `CgMeshChanges`.
- **A shape writes position, UV, normal and white colour**: one shape serves every format made of those.
- `CgMeshShapesTest` holds every shape byte-identical to `gl/mesh/CgMeshBuilder` until that class is deleted.
