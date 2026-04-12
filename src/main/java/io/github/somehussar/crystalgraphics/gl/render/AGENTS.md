# gl/render — Batch Render Layer System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

The layer-based batch rendering system for CrystalGraphics. This package owns
the render layer abstraction, the CPU→GPU batch pump, and the buffer source
that orchestrates ordered layer flushing.

This is the new batching architecture (Phases 3–5 of the Mk.III plan). It
replaces the old pass-owned batch model for UI and text rendering while
coexisting with the legacy `CgQuadBatcher` during the transition period.

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
├── flush(): VBO upload, VAO rebind, IBO bind, glDrawElements
│   MUST NOT bind shader/texture/blend/depth/cull — that's the layer's job
└── delete(): no-op (CPU staging only; shared GPU resources owned by registry)
```

## Legacy Types (Transitional)

| Type | Status | Notes |
|------|--------|-------|
| `CgQuadBatcher` | Transitional | Old batch model; used by `CgTextRenderer` pre-migration. Owns shader/texture binding (unlike new layers). |
| `CgBufferVertexConsumer` | Transitional | Old vertex consumer for `CgQuadBatcher`. Will be retired after text migration. |

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
| `CgBatchRenderer.java` | CPU→GPU pump: staging → VBO upload → draw. State-blind. |
| `CgBufferSource.java` | Ordered layer collection with dirty-aware flush |
| `CgBufferVertexConsumer.java` | (Legacy) float[] vertex consumer for CgQuadBatcher |
| `CgQuadBatcher.java` | (Legacy) transitional batch model |

## Key Design Decisions

- **Layers own state, renderer owns upload** — `CgBatchRenderer.flush()` never
  touches GL state beyond VBO/VAO/IBO. Shader, texture, blend, depth, and cull
  are the layer's responsibility via `CgRenderState.apply()/clear()`.
- **VAO bound before pointer rebind** — `glVertexAttribPointer` writes into the
  currently bound VAO. The batch renderer binds the VAO first, then rebinds
  pointers. Getting this order wrong silently corrupts the default VAO.
- **Painter's order is registration order** — `CgBufferSource.Builder.layer()`
  order determines flush order. No auto-sorting.
- **Layer keys use name equality** — two `CgLayer.Key` instances with the same
  name address the same slot. This allows cross-module key matching (Cg text
  key registered from CgUi code).
