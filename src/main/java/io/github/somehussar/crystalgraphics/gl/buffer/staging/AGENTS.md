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
| `CgStagingBuffer` | Growable `float[]` with write cursor. Pure data — no GL, no semantics. Growth factor: 1.5×. Implements `CgVertexOutput`. Also supports random-access write: `reserveAndZero(int floatCount)` pre-zeros a slot range and returns the start index; `setFloatAt(int absIndex, float v)` writes a float at an absolute index without advancing the cursor; `setIntBitsAt(int absIndex, int bits)` writes raw int bits (reinterpreted as float via `Float.intBitsToFloat`) at an absolute index — used by integer field writes in `CgBufferWriter`. Used by `CgBufferWriter` in format-aware mode. |
| `CgBufferWriter` | General-purpose staged float writer for UBOs/SSBOs/TBOs (non-vertex payloads). **Always format-aware** — requires a `CgBufferFormat` at construction (no positional/null-format mode). Named writes: `mat4("field", m)`, `vec4("field", ...)`, `mat3("field", m)` (48 bytes, vec4-padded), `vec3("field", ...)`, `vec2("field", ...)`, `float_("field", v)`, `int_("field", int)`, `uint("field", int)`, `bool_("field", bool)`, `ivec2("field", int, int)`, `ivec3("field", int, int, int)`, `ivec4("field", int, int, int, int)`, `uvec2("field", int, int)`, `uvec3("field", int, int, int)`, `uvec4("field", int, int, int, int)`, `uint64("field", long)`, `int64("field", long)`. `reset()` returns `this`. `beginRecord()` calls `reserveAndZero(format.getFloatCount())` and records `recordStartIdx`. `endRecord()` is a no-op (record pre-zeroed). |
| `CgVertexWriter` | V1 format-aware `CgVertexConsumer` implementation. Routes fluent calls (vertex/uv/color/normal) to a `CgVertexOutput` based on format attribute semantics. Static factory `forBuffer(ByteBuffer, CgVertexFormat)` enables direct ByteBuffer writes for mesh builders. |
| `CgInstanceWriter` | Per-instance data writer backed by `CgStagingBuffer`. Fluent API: `mat4(...).color(...).putVec4(...).endInstance()`. `beginInstance()`/`endInstance()` validate stride in DEBUG mode. `mat3()` here is tight 9-float (vertex attribute packing — not std140). |
| `CgColorPacking` | Utility for packing RGBA components into ABGR int. `packAbgr(r,g,b,a)` is endian-aware. |
| `CgVertexOutput` | Package-private write target interface. Two methods: `putFloat(float)` and `putIntBits(int)`. Implemented by `CgStagingBuffer` and `CgStagingByteBuffer`. |
| `CgStagingByteBuffer` | Package-private `CgVertexOutput` backed by a direct `ByteBuffer`. Used by `CgVertexWriter.forBuffer()`. |

## mat3 Naming Convention

| Method | Bytes | Use case |
|--------|-------|----------|
| `CgBufferWriter.mat3(String, Matrix3f)` | 48 bytes (3 × vec4-aligned cols) | std140/std430 UBO/SSBO named field — correct |
| `CgInstanceWriter.mat3(Matrix3f)` | 36 bytes (tight col-major) | Vertex attribute instance data — correct for that use |

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
  │  putFloat() / putIntBits()
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
staging.putIntBits(abgr);  // Float.intBitsToFloat(abgr)
```

This matches the GPU layout when the attribute is `GL_UNSIGNED_BYTE × 4`
with normalization enabled (4 bytes = 1 float slot in the staging array).

## Design Rules

- **No GL calls** — these types are pure CPU. GL upload is `CgBatchRenderer`'s job.
- **No allocation in hot path** — `putFloat()` / `putIntBits()` are
  array writes. Growth only happens at vertex boundaries.
- **Format declaration order for writes** — `endVertex()` iterates the
  format's attribute array, not the API call order. This ensures the
  staging layout matches the interleaved vertex format.
