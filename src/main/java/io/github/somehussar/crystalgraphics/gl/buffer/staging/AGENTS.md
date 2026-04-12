# gl/buffer/staging — CPU Vertex Staging

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)
> Parent guide: [`gl/buffer/AGENTS.md`](../AGENTS.md)

## What This Package Is

CPU-side vertex staging for the batch render layer system. Contains the
raw staging buffer and the format-aware vertex writer that transforms
fluent API calls into interleaved float data.

These types sit between the public `CgVertexConsumer` API and the GPU
upload path in `CgBatchRenderer`. They have no GL dependencies and no
awareness of shaders, textures, or render state.

## Type Map

| Type | Role |
|------|------|
| `CgStagingBuffer` | Growable `float[]` with write cursor. Pure data — no GL, no semantics. Owns the raw float array. Growth factor: 1.5×. |
| `CgVertexWriter` | V1 format-aware `CgVertexConsumer` implementation. Routes fluent calls (vertex/uv/color/normal) to staging buffer positions based on format attribute semantics. |

## Data Flow

```
Caller code (UI element, text renderer, etc.)
  │
  ▼
CgVertexWriter (implements CgVertexConsumer)
  │  fluent: vertex(x,y).uv(u,v).color(r,g,b,a).endVertex()
  │  collects values into local fields
  │  endVertex() writes to staging in FORMAT DECLARATION ORDER
  ▼
CgStagingBuffer (float[] + cursor)
  │  putFloat() / putColorPacked()
  ▼
CgBatchRenderer.flush()
  │  reads rawData()/rawCursor()
  │  uploads to GPU via CgStreamBuffer.map()/commit()
  ▼
GPU draw
```

## CgVertexWriter V1 Constraints

The V1 writer supports a fixed set of semantics at index 0 only:

| Semantic | Index | Components | Notes |
|----------|-------|------------|-------|
| POSITION | 0 | 2 or 3 | Required. 2D or 3D. |
| UV | 0 | 2 | Optional. |
| COLOR | 0 | 4 bytes | Optional. Packed as ABGR via `Float.intBitsToFloat()`. |
| NORMAL | 0 | 3 | Optional. |

- Formats with GENERIC semantic or any semanticIndex > 0 are rejected at
  construction time.
- Attributes can appear in any order in the format — the writer adapts.

## Step Machine (Debug Mode)

When `DEBUG == true` (compile-time constant), a step counter enforces
call ordering: vertex → uv → color → normal → endVertex. Steps for
absent attributes are auto-skipped. Out-of-order calls throw
`IllegalStateException`.

## Color Packing Convention

Colors are stored as ABGR-packed integers reinterpreted as floats:
```java
int abgr = ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
staging.putColorPacked(abgr);  // Float.intBitsToFloat(abgr)
```

This matches the GPU layout when the attribute is `GL_UNSIGNED_BYTE × 4`
with normalization enabled (4 bytes = 1 float slot in the staging array).

## Design Rules

- **No GL calls** — these types are pure CPU. GL upload is `CgBatchRenderer`'s job.
- **No allocation in hot path** — `putFloat()` / `putColorPacked()` are
  array writes. Growth only happens at vertex boundaries.
- **Format declaration order for writes** — `endVertex()` iterates the
  format's attribute array, not the API call order. This ensures the
  staging layout matches the interleaved vertex format.
