# api/mesh — CPU Mesh Data Types

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Pure CPU-side mesh data types. No GL dependencies. Produced by mesh builders and loaders
in `gl/mesh/`, consumed by `CgMesh.upload()` for GPU upload.

## Type Map

| Type | Role |
|------|------|
| `CgMeshTopology` | Enum mapping logical topology names to GL draw mode constants (`GL_TRIANGLES`, `GL_TRIANGLE_STRIP`, `GL_LINES`, `GL_LINE_STRIP`, `GL_POINTS`). Exposes `getGlMode()` for the raw GL int. |
| `CgMeshData` | Immutable CPU-side mesh holder: `CgVertexFormat`, `CgMeshTopology`, flipped `ByteBuffer vertexBuffer`, optional flipped `ByteBuffer indexBuffer`, and explicit `indexCount`. `getVertexCount()` derives count from `vertexBuffer.remaining() / format.getStride()`. |

## Design Rules

- **No GL calls** in any type in this package.
- **No LWJGL imports** in `CgMeshData` (pure Java).
- `CgMeshTopology` is the only type that imports from `org.lwjgl.opengl` — it is an enum whose sole purpose is to map topology names to GL constants.
- `indexCount` is stored explicitly in `CgMeshData` because the element byte width (u16 vs u32) is not determined at this stage. Deriving the count from `indexBuffer.remaining()` would require knowing the width.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/mesh/` | `CgMeshBuilder`, `CgObjLoader`, `CgGltfLoader` produce `CgMeshData`; `CgMesh.upload(CgMeshData)` consumes it |
| `api/vertex/` | `CgMeshData` holds a `CgVertexFormat` reference |
