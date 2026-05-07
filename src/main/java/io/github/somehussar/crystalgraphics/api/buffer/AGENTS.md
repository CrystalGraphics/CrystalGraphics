# api/buffer — GPU Buffer Format Descriptors

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Typed format descriptors for GPU buffer layouts (UBOs and SSBOs). The buffer-domain
complement to `api/vertex/` (`CgVertexFormat`, `CgAttribType`). No GL calls — pure data.

`CgGpuType` and `CgBufferFormat` play the same role for shader buffer fields that
`CgAttribType` and `CgVertexFormat` play for vertex attributes. **Do not conflate them:**
`CgAttribType` operates at the GL primitive level (FLOAT, UNSIGNED_BYTE); `CgGpuType`
operates at the GLSL compound-type level (mat4, vec4, mat3).

## Type Map

| Type | Role |
|------|------|
| `CgGpuType` | Enum of GLSL compound field types. Each entry carries: `floatComponents` (logical floats), `dataBytes` (raw data size), `alignedBytes` (bytes in std140/std430 slot including padding), `alignment` (byte alignment requirement), `glslName`. `getFloatCount()` = `alignedBytes / 4`. Values are spec-derived from OpenGL 4.5 §7.6.2.2. |
| `CgBufferField` | Immutable value object for a single named field: `name`, `type (CgGpuType)`, `byteOffset`, `floatOffset` (derived). Package-private constructor — only `CgBufferFormat.Builder` creates these. Value equality on (name, type, byteOffset). |
| `CgBufferFormat` | Immutable buffer layout descriptor. Tracks an ordered array of `CgBufferField`, total `stride`, and `MemoryLayout`. Builder auto-computes field byte offsets with correct inter-field alignment padding. Value-equal by (fields, stride, memoryLayout) — `debugName` excluded. `getFloatCount()` = stride/4. `getField(String)` throws `IllegalArgumentException` on miss. |
| `CgObjectBuffer` | Marker/capability interface for all GPU-resident shader-accessible buffer objects (SSBO, TBO, UBO). `bind()`, `unbind()`, `delete()`, `isDeleted()`, `getGlBufferId()`. |

## std140/std430 Alignment Table

| CgGpuType | dataBytes | alignedBytes | alignment | Notes                                                                        |
|-----------|-----------|--------------|-----------|------------------------------------------------------------------------------|
| FLOAT     | 4         | 4            | 4         |                                                                              |
| VEC2      | 8         | 8            | 8         |                                                                              |
| VEC3      | 12        | 16           | 16        | ⚠ 16-byte aligned; old Intel drivers bug with VEC3 in UBOs — prefer VEC4+w=0 |
| VEC4      | 16        | 16           | 16        |                                                                              |
| MAT3      | 36        | 48           | 16        | 3 × 16-byte vec4-aligned columns. SAME in std140 AND std430.                 |
| MAT4      | 64        | 64           | 16        |                                                                              |
| INT       | 4         | 4            | 4         | v1: no named write method (deferred to v2)                                   |
| UINT      | 4         | 4            | 4         | v1: no named write method                                                    |
| BOOL      | 4         | 4            | 4         | GPU bool is always 32 bits. v1: no named write method                        |

## Builder Pattern

```java
CgBufferFormat objectFmt = CgBufferFormat
    .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
    .mat4("modelMatrix")   // floats 0–15
    .mat4("normalMatrix")  // floats 16–31
    .vec4("custom0")       // floats 32–35
    .vec4("custom1")       // floats 36–39
    .vec4("custom2")       // floats 40–43
    .vec4("custom3")       // floats 44–47
    .build();              // stride = 192 bytes = 48 floats

CgBufferField f = objectFmt.getField("modelMatrix");
// f.floatOffset() == 0
```

**MemoryLayout rule of thumb**: STD140 → UBOs; STD430 → SSBOs. For the field types in v1
both layouts produce identical offsets and strides — the only practical difference is
arrays-of-scalars, deferred to v2.

## Pre-built Format Constants

Engine-owned canonical formats live on `CgMaterialPipeline`:

| Constant | Layout | Fields | Floats |
|----------|--------|--------|--------|
| `CgMaterialPipeline.OBJECT_FORMAT` | STD430 | mat4 model, mat4 normal, vec4×4 custom | 48 |
| `CgMaterialPipeline.FRAME_BLOCK_FORMAT` | STD140 | mat4 view, mat4 proj, vec4 time, vec2 resolution | 38 |

## How Format-Aware Writing Works

1. Build a `CgBufferFormat` once (or use a pre-built constant).
2. Create `CgBufferWriter(staging, format)` — format-aware constructor.
3. `writer.beginRecord()` → calls `staging.reserveAndZero(floatCount)`, records `recordStartIdx`.
4. Named writes scatter values: `writer.mat4("modelMatrix", m)` computes
   `absIndex = recordStartIdx + field.floatOffset` and calls `staging.setFloatAt(absIndex, v)`.
5. Unwritten fields are already zero from step 3.
6. `buffer.endRecord()` finalizes + advances write head.

Positional write methods (`mat4(Matrix4f)`, `vec4(float,...)`) throw `IllegalStateException`
when a format is attached — named-only mode enforces clean field-offset discipline.
