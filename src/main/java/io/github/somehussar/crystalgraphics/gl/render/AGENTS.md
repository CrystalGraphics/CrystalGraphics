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
│   ├── owns one CgBatchRenderer
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

CgBatchRenderer (CPU→GPU pump)
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
│   HARD CONTRACT: after uploadPendingVertices(), no more vertex recording
│   or staging growth is allowed — attempts throw IllegalStateException
└── delete(): no-op (CPU staging only; shared GPU resources owned by registry)
```

## Ownership Boundaries (Critical)

- **Shared VBO/VAO**: Owned by `CgVertexArrayRegistry` / `CgVertexArrayBinding` in `gl/vertex/`.
  The batch renderer borrows these via `getOrCreate(format)` — never creates or deletes them.
- **Shared IBO**: `CgQuadIndexBuffer` singleton in `gl/buffer/`. Borrowed, never owned.
- **Shader/Texture state**: Owned by `CgRenderState` (in `api/state/`), applied by the layer,
  not by the batch renderer. `CgBatchRenderer.flush()` is state-blind.

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
| `CgRenderLayer.java` | Fixed-texture layer: state bracket around flush |
| `CgDynamicTextureRenderLayer.java` | Dynamic-texture layer: auto-flush on texture change |
| `CgBatchRenderer.java` | CPU→GPU pump: staging → VBO upload → draw. State-blind. Supports both immediate `flush()` and upload-once/draw-many lifecycle. |
| `CgBufferSource.java` | Ordered layer collection with dirty-aware flush |

## Key Design Decisions

- **Layers own state, renderer owns upload** — `CgBatchRenderer.flush()` never
  touches GL state beyond VBO/VAO/IBO. Shader, texture, blend, depth, and cull
  are the layer's responsibility via `CgRenderState.apply()/clear()`.
- **Two batch renderer lifecycles** — The immediate `flush()` path is for
  layer-based non-UI uses. The `uploadPendingVertices()` / `drawUploadedRange()`
  / `finishUploadedDraws()` path is for CrystalGUI's draw-list replay. Both
  share the same staging buffer, VBO, and VAO — they are mutually exclusive
  per frame (never mix immediate and replay in one begin/end cycle).
- **Upload-once/draw-many hard contract** — after `uploadPendingVertices()`, no
  more vertex recording or staging growth is allowed. The staging buffer is
  locked until `finishUploadedDraws()` releases it.
- **VAO bound before pointer rebind** — `glVertexAttribPointer` writes into the
  currently bound VAO. The batch renderer binds the VAO first, then rebinds
  pointers. Getting this order wrong silently corrupts the default VAO.
- **Painter's order is registration order** — `CgBufferSource.Builder.layer()`
  order determines flush order. No auto-sorting.
- **Layer keys use name equality** — two `CgLayer.Key` instances with the same
  name address the same slot. This allows cross-module key matching (Cg text
  key registered from CgUi code).

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

### Internal replay state fields

When `uploadPendingVertices()` is called, the renderer stores:
- `uploadedForReplay` — boolean flag that recording is closed
- `uploadedFloatCount` — float count at upload time
- `uploadedDataOffset` — byte offset from stream-buffer commit
- `uploadedVertexCount` — vertex count at upload time

These are used by `drawUploadedRange()` to compute correct byte offsets and
by `finishUploadedDraws()` to know that cleanup is needed.

### Guard conditions

- `vertex()` throws `IllegalStateException` if `uploadedForReplay` is true
- `flush()` throws `IllegalStateException` if `uploadedForReplay` is true
- `drawUploadedRange()` throws if not in replay mode
- `finishUploadedDraws()` throws if not in replay mode

### Compatibility with existing layer path

The immediate `flush()` path is unaffected. Layers continue to use:
`begin()` → `vertex()` → `flush()` → `end()`.

The two lifecycles share the same batch renderer instance but are mutually
exclusive per frame. CrystalGUI's `CgUiBatchSlots` creates dedicated
`CgBatchRenderer` instances for the draw-list path.
