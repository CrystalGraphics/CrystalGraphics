# api/buffer — buffers and their layouts

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Typed format descriptors for GPU buffer layouts (UBOs and SSBOs). The buffer-domain
complement to `api/vertex/` (`CgVertexFormat`, `CgAttribType`). No GL calls — pure data.

`CgGpuType` and `CgBufferLayout` play the same role for shader buffer fields that
`CgAttribType` and `CgVertexFormat` play for vertex attributes. **Do not conflate them:**
`CgAttribType` operates at the GL primitive level (FLOAT, UNSIGNED_BYTE); `CgGpuType`
operates at the GLSL compound-type level (mat4, vec4, mat3).

## The consumer API

```java
static final CgBufferLayout SPARK = CgBufferLayout.std430("Spark").vec4("positionLife").vec4("velocitySeed").build();

// rewritten every frame: this frame's memory, a graph buffer
CgGraphBuffer sparks = rec.upload("mymod.sparks", SPARK, n, records -> {
    for (Spark s : live) records.next().vec4("positionLife", s.x, s.y, s.z, s.life).vec4("velocitySeed", s.vx, s.vy, s.vz, s.seed);
});
// kept across frames: written when it changes, any thread
CgBuffer heights = CgBuffers.create("mymod.heights", HEIGHT, cells);
heights.write(first, count, records -> ...);

material.buffer("SPARKS", sparks);                   // the shader's Buffers { SPARKS ("Sparks", Spark, readonly) }
pass.dispatch(kernel, cells).bind("HEIGHTS", heights);
```

- A `CgBuffer` bound by a material or a kernel is checked against the shader's struct (`CgBufferDecl.check`): one
  stride, each field at the shader's offset with its type; a mismatch throws naming both.
- `CgBufferRecords.next()` is a zeroed record written by field name; unwritten records are zero.
- `rec.upload` reuses its records and handle frame to frame (no allocation once warm); `CgBuffer` keeps a CPU copy and
  uploads it whole on each write, landed before the next frame (`CgDeferral`).

## Type Map

| Type | Role |
|------|------|
| `CgGpuType` | Enum of GLSL compound field types. Each entry carries: `floatComponents` (logical floats), `dataBytes` (raw data size), `alignedBytes` (bytes in std140/std430 slot including padding), `alignment` (byte alignment requirement), `glslName`. `getFloatCount()` = `alignedBytes / 4`. Values are spec-derived from OpenGL 4.5 §7.6.2.2. |
| `CgBufferField` | Immutable value object for a single named field: `name`, `type (CgGpuType)`, `byteOffset`, `floatOffset` (derived). Package-private constructor — only `CgBufferLayout.Builder` creates these. Value equality on (name, type, byteOffset). |
| `CgBufferLayout` | Immutable buffer layout descriptor. Tracks an ordered array of `CgBufferField`, total `stride`, and `MemoryLayout`. Builder auto-computes field byte offsets with correct inter-field alignment padding. Value-equal by (fields, stride, memoryLayout) — `glslName` excluded. `getFloatCount()` = stride/4. `getField(String)` throws `IllegalArgumentException` on miss. |
| `CgBuffer`, `CgBuffers` | A buffer of yours kept across frames: `CgBuffers.create(name, layout, count)`, `write(first, count, records -> ...)`, `release()`, `graphBuffer()` (an import the graph orders). A `CgBufferHandle`. |
| `CgBufferRecords` | What a write's lambda fills: `next()` per record. Made by `CgBuffer` and `CgRecording.upload`. |

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
| IVEC2     | 8         | 8            | 8         | 2-component signed int vector. |
| IVEC3     | 12        | 16           | 16        | 3-component signed int vector.                        |
| IVEC4     | 16        | 16           | 16        | 4-component signed int vector.                        |
| UVEC2     | 8         | 8            | 8         | 2-component unsigned int. Use for bindless handles (ARB_bindless_texture). |
| UVEC3     | 12        | 16           | 16        | 3-component unsigned int.                             |
| UVEC4     | 16        | 16           | 16        | 4-component unsigned int.                             |
| UINT64    | 8         | 8            | 8         | 64-bit unsigned int. Requires ARB_gpu_shader_int64.   |
| INT64     | 8         | 8            | 8         | 64-bit signed int. Requires ARB_gpu_shader_int64.     |

### Why sampler types are absent

`sampler2D`, `sampler2DArray`, etc. are opaque GLSL types — illegal inside buffer blocks per
GLSL §4.1.7. They are intentionally absent from `CgGpuType` and `CgBufferLayout`. For
bindless texture handles use `UVEC2` (2 × uint32 = one 64-bit GL handle) or `UINT64`
(single uint64_t, requires ARB_gpu_shader_int64).

## Builder Pattern

```java
CgBufferLayout objectFmt = CgBufferLayout
    .builder("cg_object", CgBufferLayout.MemoryLayout.STD430)
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

The engine's own formats live beside what fills them, in `render/draw`:

| Constant | Layout | Fields | Floats |
|----------|--------|--------|--------|
| `CgInstanceKind.OBJECT.format()` | STD430 | mat4 model, mat4 normal, vec4×4 custom | 48 |
| `CgPassConstants.FORMAT` | STD140 | mat4 view, mat4 proj, vec4 time, vec2 resolution, vec4 camera position, vec4 depth params, vec4 world origin | 52 |

## How Format-Aware Writing Works

1. Build a `CgBufferLayout` once (or use a pre-built constant).
2. Create `CgBufferWriter(staging, format)` — format-aware constructor.
3. `writer.beginRecord()` → calls `staging.reserveAndZero(floatCount)`, records `recordStartIdx`.
4. Named writes scatter values: `writer.mat4("modelMatrix", m)` computes
   `absIndex = recordStartIdx + field.floatOffset` and calls `staging.setFloatAt(absIndex, v)`.
5. Unwritten fields are already zero from step 3.
6. `buffer.endRecord()` finalizes + advances write head.

Positional write methods (`mat4(Matrix4f)`, `vec4(float,...)`) throw `IllegalStateException`
when a format is attached — named-only mode enforces clean field-offset discipline.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
