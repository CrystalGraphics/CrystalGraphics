# gl/vertex — VAO & Vertex Input System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

VAO (Vertex Array Object) backend and shared vertex input binding system.
Owns VAO creation, attribute pointer configuration, and the per-format
registry that pairs each `CgVertexFormat` with its VAO + stream buffer.

This package is the bridge between the public vertex format API (`api/vertex/`)
and the GL buffer streaming layer (`gl/buffer/`). It should never contain
buffer allocation logic — that belongs in `gl/buffer`.

## Ownership Model

```
CgVertexArrayRegistry (singleton, INSTANCE field)
├── keyed by CgVertexFormat (equals/hashCode on content, not identity)
├── creates and caches CgVertexArrayBinding per format
├── getOrCreate() — lazy creation with correct VBO→VAO binding order
└── deleteAll() — tears down all bindings (cleanup on context destroy)

CgVertexArrayBinding
├── owns one CgVertexArray (VAO)
├── owns one CgStreamBuffer (VBO, from gl/buffer)
├── rebindPointersIfNeeded(dataOffset) — fast-path offset update after commit
└── delete() releases both VAO and VBO

CgVertexArray
├── wraps a single GL VAO id
├── core GL30 / ARB_vertex_array_object fallback (lazy one-shot detection)
├── configure(format) — sets up all attribute pointers from CgVertexFormat
├── reconfigureWithOffset(format, dataOffset) — re-issues pointers at new offset
└── static bind()/delete() helpers for raw VAO ids
```

## Key Design Decisions

- **One binding per format** — all consumers sharing a `CgVertexFormat`
  share the same VAO + stream buffer via the registry. Format equality is
  by content (attribute list + stride), not object identity.
- **VBO must be bound before VAO configure** — `glVertexAttribPointer`
  captures the currently-bound `GL_ARRAY_BUFFER` into VAO state. The
  registry enforces this: `streamBuffer.bind()` → `vertexArray.configure()` →
  `streamBuffer.unbind()` → `vertexArray.unbind()`.
- **Offset rebinding is lazy** — `rebindPointersIfNeeded()` tracks the
  current data offset and skips re-issuing pointers when unchanged. This
  matters for orphan/subdata paths where commit always returns offset 0.
  The sync ring path returns varying offsets per slot.
- **reconfigureWithOffset skips glEnableVertexAttribArray** — attribute
  arrays are already enabled and stored in VAO state from the initial
  `configure()`. The fast path only re-issues `glVertexAttribPointer`.
- **Core/ARB waterfall** — `CgVertexArray` lazy-detects GL30 vs
  `ARB_vertex_array_object` via `CgCapabilities.detect().isVaoSupported()`
  and `GLContext.getCapabilities().OpenGL30`. Result is cached in a static
  `Boolean` field (one-shot, never re-evaluated).

## Lifecycle Rules

1. **Creation**: Always through `CgVertexArrayRegistry.get().getOrCreate(format)`.
   Never construct `CgVertexArrayBinding` or `CgVertexArray` directly from
   outside this package.
2. **Per-frame usage**: Call `binding.getStreamBuffer().map(size)`, write
   vertex data, call `commit(usedBytes)` to get the data offset, then call
   `binding.rebindPointersIfNeeded(dataOffset)` before the draw call.
3. **Cleanup**: Call `CgVertexArrayRegistry.get().deleteAll()` on context
   shutdown. This deletes all VAOs and VBOs.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/vertex/` | Provides `CgVertexFormat`, `CgVertexAttribute`, `CgAttribType` — the format descriptors this package consumes |
| `gl/buffer/` | Provides `CgStreamBuffer` — the VBO streaming layer that each binding owns |
| `gl/pass/` | Render passes use bindings from the registry to draw batched geometry |
| `api/` | `CgCapabilities` drives the VAO core/ARB waterfall detection |

## File Map

| File | Role |
|------|------|
| `CgVertexArray.java` | VAO wrapper: create, bind, configure, delete. Core GL30 / ARB fallback. Static `useCore` cache. |
| `CgVertexArrayBinding.java` | Pairs a VAO + stream buffer for one vertex format. Tracks `currentDataOffset` for lazy rebinding. |
| `CgVertexArrayRegistry.java` | Singleton registry: `CgVertexFormat` → `CgVertexArrayBinding` cache. Default initial capacity: 4096 quads per format. |
