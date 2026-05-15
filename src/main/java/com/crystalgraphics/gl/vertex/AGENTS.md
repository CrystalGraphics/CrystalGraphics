# gl/vertex — VAO & Vertex Input System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

VAO (Vertex Array Object) backend and shared vertex input binding system.
Owns VAO creation, attribute pointer configuration, and the registries that manage
all stream VBOs and all VAOs (non-instanced and instanced).

This package is the bridge between the public vertex format API (`api/vertex/`)
and the GL buffer streaming layer (`gl/buffer/`). It should never contain
buffer allocation logic — that belongs in `gl/buffer`.

## Registry Consolidation

After the registry consolidation, only two registry singletons remain:

- **`CgVertexArrayRegistry`** — owns ALL VAOs (non-instanced + instanced)
- **`CgVertexBufferRegistry`** — owns ALL streaming VBOs (base + instance)

Future VAO/VBO lifecycle additions belong in these two registries, not new singletons.

## Ownership Model

```
CgVertexBufferRegistry (singleton)
├── base cache: HashMap<CgVertexFormat, CgVertexBuffer> (value equality)
├── instance cache: HashMap<CgInstanceFormat, CgInstanceVertexBuffer> (value equality)
├── getOrCreate(format) → CgVertexBuffer
├── getOrCreateInstanced(layout) → CgInstanceVertexBuffer
└── deleteAll() — step 3 of 4-step teardown (ALL stream VBOs: base + instance)

CgVertexBuffer
├── owns one CgStreamBuffer (base VBO for one vertex format)
├── create(format) — capacity: 4096 quads × stride
└── delete() — deletes VBO only (no VAO)

CgInstanceVertexBuffer
├── owns one CgStreamBuffer (instance VBO for one instance layout)
├── create(layout) — capacity: 256 instances × layout stride
└── delete() — deletes VBO only (no VAO)

CgVertexArrayRegistry (singleton)
├── non-instanced: HashMap<CgVertexFormat, CgVertexArrayBinding> (value equality)
├── streaming instanced: HashMap<InstancedStreamKey, CgInstanceVertexArrayBinding>
│   where InstancedStreamKey is a value-equal composite of (CgVertexFormat, CgInstanceFormat)
├── mesh instanced: HashMap<InstancedMeshKey, CgInstanceVertexArrayBinding>
│   where InstancedMeshKey is identity-based CgMesh + value-equal CgInstanceFormat
├── getOrCreate(format) → CgVertexArrayBinding
│   fetches CgVertexBuffer from CgVertexBufferRegistry
│   creates and configures a non-instanced VAO
├── getOrCreateInstanced(format, layout) — validates attribute slots, fetches VBOs,
│   creates instanced VAO — no CgVertexArrayBinding created — zero wasted non-instanced VAOs
├── getOrCreateMeshInstanced(mesh, layout) — validates attribute slots, creates instanced VAO
├── invalidateMeshBindings(mesh) — removes + deletes stale instanced VAOs for deleted mesh
└── deleteAll() — deletes instanced VAOs FIRST, then non-instanced VAOs (step 1 of 4-step teardown)

CgVertexArrayBinding
├── owns one CgVertexArray (non-instanced VAO)
├── borrows CgVertexBuffer (VBO NOT owned here)
├── getStreamBuffer() — delegation to CgVertexBuffer
├── getFormat() — delegation to CgVertexBuffer
├── rebindPointersIfNeeded(dataOffset) — lazy fast-path offset update
└── delete() — deletes VAO only; does NOT touch the stream buffer

CgInstanceVertexArrayBinding
├── owns one instanced VAO id
├── borrows CgVertexBuffer (streaming path) or mesh VBO (mesh path)
├── borrows CgInstanceVertexBuffer
├── createStreaming(CgVertexBuffer, CgInstanceVertexBuffer)
├── createMeshInstanced(CgMesh, CgInstanceVertexBuffer)
├── rebindBasePointersIfNeeded(offset)
├── rebindInstancePointersIfNeeded(offset)
└── delete() — deletes VAO only; does NOT touch any VBOs

CgVertexArray
├── wraps a single GL VAO id
├── core GL30 / ARB_vertex_array_object fallback (lazy one-shot detection)
├── configure(format) — sets up all attribute pointers from CgVertexFormat
├── reconfigureWithOffset(format, dataOffset) — re-issues pointers at new offset
└── static bind()/delete() helpers for raw VAO ids
```

## 4-Step Teardown Order (CRITICAL)

VAOs must be deleted before VBOs. The single call site is
`CgGraphicsLifecycle.destroyContext()` (in `gl/lifecycle/`), which executes:

```java
// 1. ALL VAOs — instanced first (inside deleteAll), then non-instanced
CgVertexArrayRegistry.get().deleteAll();
// 2. Static mesh VBOs + IBOs + per-mesh VAOs
CgMeshRegistry.get().deleteAll();
// 3. ALL stream VBOs (base + instance streams)
CgVertexBufferRegistry.get().deleteAll();
// 4. Shared quad IBO
CgQuadIndexBuffer.freeAll();
```

## Key Design Decisions

- **VBO ownership separated from VAO ownership** — `CgVertexBuffer` owns the VBO;
  `CgVertexArrayBinding` owns the VAO. This prevents a wasted non-instanced VAO
  when the instanced path only needs the VBO.
- **No CgVertexArrayBinding in the instanced path** — `CgVertexArrayRegistry.getOrCreateInstanced()`
  fetches `CgVertexBuffer` directly from `CgVertexBufferRegistry`. The non-instanced
  VAO is never created as a side effect of instanced rendering.
- **One VBO per format** — all consumers sharing a `CgVertexFormat` share the
  same `CgVertexBuffer` (and therefore the same VBO) via `CgVertexBufferRegistry`.
  Format equality is by content (attribute list + stride), not object identity.
- **One VBO per layout** — all consumers sharing a `CgInstanceFormat` share the
  same `CgInstanceVertexBuffer` via `CgVertexBufferRegistry.getOrCreateInstance()`.
- **VBO must be bound before VAO configure** — `glVertexAttribPointer`
  captures the currently-bound `GL_ARRAY_BUFFER` into VAO state. The
  registry enforces this: `streamBuffer.bind()` → `vertexArray.configure()` →
  `streamBuffer.unbind()` → `vertexArray.unbind()`.
- **Offset rebinding is lazy** — `rebindPointersIfNeeded()` tracks the
  current data offset and skips re-issuing pointers when unchanged. This
  matters for orphan/subdata paths where commit always returns offset 0.
  The sync ring path returns varying offsets per slot.
- **Instanced VAOs deleted before non-instanced** — `CgVertexArrayRegistry.deleteAll()`
  deletes streaming/mesh instanced VAOs first (they reference both base and instance VBOs),
  then non-instanced VAOs (they reference only base VBOs). This ensures no VBO is deleted
  while still referenced by a live VAO.

## Lifecycle Rules

1. **Non-instanced creation**: Always through `CgVertexArrayRegistry.get().getOrCreate(format)`.
   Never construct `CgVertexArrayBinding` or `CgVertexArray` directly from outside this package.
2. **Instanced streaming creation**: Through `CgVertexArrayRegistry.get().getOrCreateInstanced(format, layout)`.
3. **Instanced mesh creation**: Through `CgVertexArrayRegistry.get().getOrCreateMeshInstanced(mesh, layout)`.
4. **Per-frame usage (non-instanced)**: Call `binding.getStreamBuffer().map(size)`, write
   vertex data, call `commit(usedBytes)` to get the data offset, then call
   `binding.rebindPointersIfNeeded(dataOffset)` before the draw call.
5. **Per-frame usage (instanced)**: See `CgInstanceRenderer` / `CgQuadInstanceRenderer` in `gl/render/`.
6. **Cleanup**: Follow the 4-step teardown order above.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/vertex/` | Provides `CgVertexFormat`, `CgVertexAttribute`, `CgAttribType`, `CgInstanceFormat` — the format descriptors this package consumes |
| `gl/buffer/` | Provides `CgStreamBuffer` — the VBO streaming layer that stream classes own |
| `gl/mesh/` | `CgMesh` is a key for `CgVertexArrayRegistry.getOrCreateMeshInstanced()` |
| `api/` | `CgCapabilities` drives the VAO core/ARB waterfall detection |

## File Map

| File | Role |
|------|------|
| `CgVertexArray.java` | VAO wrapper: create, bind, configure, delete. Core GL30 / ARB fallback. `create()` and `createRawVaoId()` guard on `isVaoSupported()` (not just GL30), so ARB-only hardware works correctly. Static `useCore` cache; `resetCoreCache()` called on context recreation. |
| `CgVertexArrayBinding.java` | Non-instanced VAO binding per format. Borrows `CgVertexBuffer` (VBO not owned). Tracks `currentDataOffset` for lazy rebinding. `getStreamBuffer()` delegates to stream buffer for backward compat. |
| `CgVertexArrayRegistry.java` | Singleton managing ALL VAOs. Non-instanced: `CgVertexFormat` → `CgVertexArrayBinding`. Streaming instanced: `InstancedStreamKey(CgVertexFormat, CgInstanceFormat)` → `CgInstanceVertexArrayBinding` (value-equal composite key). Mesh instanced: `InstancedMeshKey(CgMesh identity, CgInstanceFormat)` → `CgInstanceVertexArrayBinding`. `deleteAll()` deletes instanced VAOs first, then non-instanced. `invalidateMeshBindings(mesh)` removes stale VAOs on mesh delete. |
| `CgVertexBuffer.java` | Owns the base stream VBO for one vertex format. No VAO. `create(format)` factory. `delete()` frees only the VBO. |
| `CgVertexBufferRegistry.java` | Singleton managing ALL stream VBOs. `getOrCreate(format)` → `CgVertexBuffer`. `getOrCreateInstanced(layout)` → `CgInstanceVertexBuffer`. `deleteAll()` frees base + instance streams. |
| `CgInstanceVertexBuffer.java` | Owns the instance stream VBO for one instance layout. No VAO. `create(layout)` factory. `delete()` frees only the VBO. |
| `CgInstanceVertexArrayBinding.java` | Instanced VAO binding. Owns one VAO id. Borrows `CgVertexBuffer` (streaming path) or mesh VBO (mesh path) + `CgInstanceVertexBuffer`. Two factories: `createStreaming(CgVertexBuffer, CgInstanceVertexBuffer)` and `createMeshInstanced(CgMesh, CgInstanceVertexBuffer)`. Also hosts absorbed instancing support statics: `isSupported()`, `requireSupported()`, `validateAttributeSlots()`, `vertexAttribDivisor()`, `resetCoreCache()`. `delete()` frees VAO only. |

## Instancing Architecture

### Key invariants

- **Instanced binding never touches the non-instanced VAO** — `CgVertexArrayRegistry.getOrCreateInstanced()`
  creates a completely new VAO using `CgVertexBuffer` (VBO only). No `CgVertexArrayBinding`
  is consulted in the instanced path.
- **Attribute slot layout**: base slots `0..baseCount-1` (divisor=0), instance slots
  `baseCount..baseCount+instanceCount-1` (divisor=1).
- **Only divisor=1 in v1** — `CgInstanceVertexArrayBinding.vertexAttribDivisor(slot, 1)` dispatches
  to GL33 or ARB_instanced_arrays path.
- **Slot validation before VAO creation** — `CgInstanceVertexArrayBinding.validateAttributeSlots(base, layout)`
  checks `base.getAttributeCount() + layout.getAttributeCount() <= GL_MAX_VERTEX_ATTRIBS`.
  Wired into `CgVertexArrayRegistry.getOrCreateInstanced()` and `getOrCreateMeshInstanced()`.
