# gl/render — Batch Render Layer System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

The layer-based batch rendering system for CrystalGraphics. This package owns
the render layer abstraction, the CPU→GPU batch pump, and the buffer source
that orchestrates ordered layer flushing.

This is the batching architecture (Phases 3–5 of the Mk.III plan). It is the
sole batch submission path for UI and text rendering.

## Ownership Model

```
CgBufferSource (per-context owned, NOT singleton)
├── owns ordered CgLayer[] array (painter's order)
├── owns Map<CgLayer.Key, CgLayer> for typed lookup
├── begin(projection) / flushAll() / end() lifecycle
├── get(Key<T>) → T typed layer access
└── delete() disposes all owned layers

CgLayer (interface)
├── begin(projection) / flush() / end() / isDirty() / delete()
├── CgRenderLayer (fixed-texture)
│   ├── owns one CgAbstractRenderer (single field — no dual-field workaround)
│   ├── owns one CgRenderState
│   └── flush: apply state → renderer.flush() → clear state
└── CgDynamicTextureRenderLayer (texture changes mid-frame)
    ├── owns one CgBatchRenderer
    ├── owns one CgRenderState (swappable via setRenderState)
    ├── setTexture(id) — auto-flushes on change
    └── flush: apply state with overrideTextureId → renderer.flush() → clear state

CgLayer.Key<T> (typed key, @Desugar record)
├── String name — identity via name equality
└── type parameter T ensures type-safe layer lookup

CgBatchRenderer (CPU→GPU pump, quads only) extends CgAbstractRenderer
├── owns CgStagingBuffer (CPU float[])
├── owns CgVertexWriter (fluent consumer)
├── borrows CgVertexArrayBinding from CgVertexArrayRegistry (shared VBO/VAO)
├── borrows CgQuadIndexBuffer (shared IBO)
├── IMMEDIATE path (layers):
│   └── flush(): VBO upload, VAO rebind, IBO bind, glDrawElements
│       MUST NOT bind shader/texture/blend/depth/cull — that's the layer's job
├── UPLOAD-ONCE / DRAW-MANY path (V3.1 draw-list):
│   ├── begin(): reset staging, open recording phase
│   ├── uploadPendingVertices(): upload staging once, lock recording
│   ├── drawUploadedRange(vtxStart, vtxCount): replay one vertex span
│   ├── finishUploadedDraws(): release replay state
│   └── end(): close batch, reset for next frame
└── delete(): no-op (CPU staging only; shared GPU resources owned by registry)

CgInstanceRenderer (instanced draw for one static mesh)
├── owns CgStagingBuffer (CPU float[], instance data only)
├── owns CgInstanceWriter
├── borrows CgInstanceVertexArrayBinding from CgVertexArrayRegistry (via getOrCreateMeshInstanced)
├── borrows CgInstanceVertexBuffer from CgVertexBufferRegistry (via getOrCreateInstanced)
├── flush(): upload instance data → bind VAO → rebind instance pointers → draw → afterSubmit → unbind
│   draw path: glDrawElementsInstanced (if mesh has IBO) or glDrawArraysInstanced
│   MUST NOT bind shader/texture/blend/depth/cull — that's the layer's job
└── delete(): no-op (CPU staging only; GPU resources owned by registries)

CgQuadInstanceRenderer (convenience wrapper for quad instancing)
├── delegates ALL logic to CgInstanceRenderer
├── caches shared unit quad CgMesh in CgMeshRegistry under
│   "crystalgraphics:builtin/quad/<quadFormat.toString()>" (toString, not hashCode — avoids collisions)
├── instance() → delegate.instance()
├── flush() → delegate.flush(); delegate.end(); delegate.begin()
│   (re-begins delegate mid-cycle so instance() can be called again after flush)
│   delegateReBegun flag tracks this; onBegin() cleans up the dangling re-begun state
└── thin wrapper only — no duplicated upload/draw logic
```

## Ownership Boundaries (Critical)

- **Shared VBO/VAO**: Owned by `CgVertexArrayRegistry` / `CgVertexArrayBinding` in `gl/vertex/`.
  The batch renderer borrows these via `getOrCreate(format)` — never creates or deletes them.
- **Shared instance VBO**: Owned by `CgVertexBufferRegistry` in `gl/vertex/`.
  `CgInstanceRenderer` borrows via `getOrCreateInstanced(layout)`.
- **Shared IBO**: `CgQuadIndexBuffer` singleton in `gl/buffer/`. Borrowed, never owned.
- **Shader/Texture state**: Owned by `CgRenderState` (in `api/state/`), applied by the layer,
  not by the batch renderer. All renderers' `flush()` methods are state-blind.

## Buffer Source Ownership

`CgBufferSource` is per-context owned, not a global singleton:

```
UIContainer
  └─ CgUiRenderContext
       └─ CgBufferSource (owns layers for UI)

WorldOverlayRenderer
  └─ CgBufferSource (owns layers for world overlays)
```

Multiple buffer sources can coexist. Each owns its layers independently.

## File Map

| File | Role |
|------|------|
| `CgLayer.java` | Interface + `Key<T>` record for typed layer identification |
| `CgRenderLayer.java` | Fixed-texture layer: state bracket around flush. Accepts any `CgAbstractRenderer`. `vertex()` and `staging()` cast to `CgBatchRenderer` — only valid when using that renderer. |
| `CgDynamicTextureRenderLayer.java` | Dynamic-texture layer: auto-flush on texture change |
| `CgBatchRenderer.java` | CPU→GPU pump: staging → VBO upload → draw. State-blind. Extends `CgAbstractRenderer`. Supports both immediate `flush()` and upload-once/draw-many lifecycle. |
| `CgBufferSource.java` | Ordered layer collection with dirty-aware flush |
| `CgInstanceRenderer.java` | Instanced draw for one static `CgMesh`. State-blind. Zero-instance flush is a no-op. Owns CPU instance staging only; GPU resources borrowed from registries. Extends `CgAbstractRenderer`. |
| `CgQuadInstanceRenderer.java` | Convenience instanced renderer for quads. Delegates to `CgInstanceRenderer`. Caches shared unit quad mesh in `CgMeshRegistry`. |
| `CgAbstractRenderer.java` | Abstract base for all batch renderers. Provides shared `begun` field + final `begin()`/`end()`/`isDirty()` + overridable `onBegin()`/`onEnd()`/`hasPendingWork()` hooks. Extended by `CgBatchRenderer`, `CgInstanceRenderer`, `CgQuadInstanceRenderer`. |

## Deleted Classes (Migration Note)

- `CgMeshBatchRenderer` — deleted; replaced by `CgInstanceRenderer` (static mesh + instances)
- `IBatchRenderer` — planned interface, never created; superseded by `CgAbstractRenderer` class hierarchy. `CgRenderLayer` accepts `CgAbstractRenderer` directly.
- Note: `CgInstanceRenderer` is the current active class for instanced mesh rendering; `CgQuadInstanceRenderer` wraps it for the unit-quad case

## Key Design Decisions

- **Layers own state, renderer owns upload** — all renderer `flush()` methods never
  touch GL state beyond VBO/VAO/IBO. Shader, texture, blend, depth, and cull
  are the layer's responsibility via `CgRenderState.apply()/clear()`.
- **Two batch renderer lifecycles** — The immediate `flush()` path is for
  layer-based non-UI uses. The `uploadPendingVertices()` / `drawUploadedRange()`
  / `finishUploadedDraws()` path is for CrystalGUI's draw-list replay. Both
  share the same staging buffer, VBO, and VAO — they are mutually exclusive
  per frame (never mix immediate and replay in one begin/end cycle).
- **VAO bound before pointer rebind** — `glVertexAttribPointer` writes into the
  currently bound VAO. The batch renderer binds the VAO first, then rebinds
  pointers. Getting this order wrong silently corrupts the default VAO.
- **Painter's order is registration order** — `CgBufferSource.Builder.layer()`
  order determines flush order. No auto-sorting.

## Upload-Once / Draw-Many Lifecycle (V3.1)

CrystalGUI's draw-list system uses `CgBatchRenderer` in a different lifecycle
than the traditional layer `flush()` path:

```
begin()                        // reset staging, open recording
  → vertex() calls             // record geometry
uploadPendingVertices()        // upload once, lock staging
  → drawUploadedRange(s, c)    // replay vertex spans (multiple calls)
finishUploadedDraws()          // release replay state
end()                          // close, reset for next frame
```

Guard conditions:
- `vertex()` throws `IllegalStateException` if `uploadedForReplay` is true
- `flush()` throws `IllegalStateException` if `uploadedForReplay` is true
- `drawUploadedRange()` throws if not in replay mode
- `finishUploadedDraws()` throws if not in replay mode
