# gl/buffer/shader — Shader Buffer Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)
> Parent guide: [`gl/buffer/AGENTS.md`](../AGENTS.md)

## What This Package Is

GPU-resident shader buffer implementations for the CrystalShader material pipeline.
These classes provide the CPU→GPU data path for per-object data (SSBO/TBO) and
per-frame uniform data (UBO) consumed by shader programs.

**Distinct from `gl/buffer/`** (vertex streaming VBOs). These buffers are read by
shaders via named blocks or texture samplers, not vertex attribute pointers.

## Class Hierarchy

```
CgShaderBuffer  (abstract — base for all shader buffer types)
├── CgShaderStorageBuffer   (SSBO: GL 4.3 core or ARB_shader_storage_buffer_object)
├── CgTextureBuffer         (TBO: GL 3.1 fallback, GL_TEXTURE_BUFFER)
└── CgUniformBuffer         (UBO: per-frame uniform data, GL_UNIFORM_BUFFER)

CgShaderBufferRegistry      (singleton registry — user-created buffers only)
```

All four types are `public`. Callers may hold concrete types for type-specific APIs
(`getTboTextureUnit()`, `bindBlock()`) without casts.

## Layered Design

```
┌──────────────────────────────────────────────────────────────┐
│  CgShaderBuffer (abstract base)                              │
│  Owns: CgStreamBuffer dataBuffer, CgBufferWriter writer,     │
│        write-session API, delete template, getGlBufferId()   │
├──────────────────────────────────────────────────────────────┤
│  CgStreamBuffer (transport — createForShaderBuffer)          │
│  Caps at MapAndOrphan (Tier B). Never MapAndSync (ring),     │
│  because ring slots return non-zero offsets incompatible     │
│  with glBindBufferBase and glTexBuffer (whole-buffer).       │
├──────────────────────────────────────────────────────────────┤
│  CgBufferWriter + CgStagingBuffer (CPU-side float staging)   │
  │  Format-aware mode: beginRecord() pre-zeros a record;        │
  │  named writes scatter values; buf.endRecord() finalizes.     │
  │  All buffers and UBOs use format-aware mode — format is      │
  │  mandatory on every CgShaderBuffer instance.                 │
└──────────────────────────────────────────────────────────────┘
```

## Why MapAndOrphan, Not MapAndSync

`CgStreamBuffer.createForShaderBuffer(target, bytes)` caps at Tier B (orphan map):

- **SSBO/UBO**: `glBindBufferBase` always reads at offset 0. The sync ring returns
  `slot * slotSize` from `commit()` — data at a non-zero offset would require
  `glBindBufferRange`. Deferred until that plumbing exists.
- **TBO**: `glTexBuffer` attaches to the whole buffer object. Sub-range binding
  requires `glTexBufferRange` (GL 4.3+), which we don't target.
- **Orphan map** always writes at offset 0 → `glBindBufferBase` and `glTexBuffer`
  read the correct data on all three paths.

## Type Map

| Type | Role |
|------|------|
| `CgShaderBuffer` | Abstract base + factories. Owns `CgStreamBuffer`, `CgBufferWriter`, write-session API (`beginWrite/endRecord/endWrite`), `delete()` template with `deleteGlResources()` hook, `getGlBufferId()`. Two factory variants: `create(CgBufferFormat, int bindingLocation)` (user-facing, enforces `>= USER_START`) and `createInternal(CgBufferFormat, int bindingLocation)` (engine-internal). **`CgBufferFormat` is mandatory** — all buffers carry a typed format. Buffer starts at capacity 1 and auto-grows on `beginWrite(N)`. Binding location is `final` after construction. |
| `CgShaderStorageBuffer` | SSBO backend. GL 4.3 core or `ARB_shader_storage_buffer_object`. Only owns `bindInternal/unbindInternal/getPath`. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture attached at construction. `deleteGlResources()` deletes the texture. |
| `CgUniformBuffer` | UBO for per-frame data. Single-constructor (`CgBufferFormat, String blockName, int bindingLocation`). Owns `BLOCK_NAME`, `BINDING_POINT`, `upload()`, `bindBlock()`. Uses unified `endRecord()` from parent — caller must call `endRecord()` before `upload()`. `upload()` does NOT call `endRecord()` internally. |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/TBO/UBO buffers. Two caches: `SsboKey(format, bindingPoint)` and `UboKey(format, bindingPoint, blockName)`. Enforces `bindingPoint >= CgBindingPoints.USER_START`. `deleteAll()` called by `CgGraphicsLifecycle.destroyContext()`. Engine-internal pipeline buffers bypass this registry. |

## Object Record ABI (must match `cg_env.glsl`)

Each per-object slot is **48 floats / 192 bytes** (`CgMaterialPipeline.OBJECT_FORMAT`):

```
floats  0–15 : mat4 modelMatrix   (column-major)
floats 16–31 : mat4 normalMatrix  (full mat4; shader reads upper-left 3×3 as mat3)
floats 32–35 : vec4 custom0
floats 36–39 : vec4 custom1
floats 40–43 : vec4 custom2
floats 44–47 : vec4 custom3
```

Use `CgMaterialPipeline.OBJECT_FORMAT.getFloatCount()` (== 48) — not a magic number.

## Binding Points

| Resource | Point | Constant |
|----------|-------|----------|
| SSBO / TBO (per-object) | 0 | `CgBindingPoints.OBJECT_DATA` |
| UBO (per-frame)         | 1 | `CgBindingPoints.FRAME_DATA` / `CgUniformBuffer.BINDING_POINT` |
| User buffers            | ≥ 10 | `CgBindingPoints.USER_START` |

## Lifecycle

**SSBO/TBO (per-object data) — format-aware mode**
```java
buffer.beginWrite(N);
for (int i = 0; i < N; i++) {
    buffer.writer()
          .beginRecord()
          .mat4("modelMatrix", model)
          .mat4("normalMatrix", normal);
    // custom0-3 auto-zeroed
    buffer.endRecord();  // finalizes record + advances write head
}
buffer.endWrite();  // uploads staged data to GPU
buffer.bind();      // glBindBufferBase
// draw N instances
buffer.unbind();
```

**UBO (per-frame data) — format-aware mode**
```java
CgBufferWriter w = frameUbo.writer();
w.reset()
 .beginRecord()
 .mat4("cg_ViewMatrix", view)
 .mat4("cg_ProjMatrix", proj)
 .vec4("cg_Time", t/20, t, t*2, t*3)
 .vec2("cg_Resolution", width, height);
frameUbo.endRecord();  // finalize the record (sets lastWrittenCount = 1)
frameUbo.upload();     // upload staged data to GPU
frameUbo.bind();       // glBindBufferBase(GL_UNIFORM_BUFFER, 1, ...)
```

**After program link** (once per program):
```java
frameUbo.bindBlock(CgUniformBuffer.BLOCK_NAME, programId);
// TBO path only:
shader.set1i("cg_ObjectTBO", objectBuffer.getTboTextureUnit());
```

## Key Design Rules

- **Never use MapAndSync for shader buffers** — offset mismatch with `glBindBufferBase`/`glTexBuffer`.
- **Never hard-code `"CgFrameBlock"`** — always use `CgUniformBuffer.BLOCK_NAME`.
- **Binding location is immutable** — set at construction. No `bindTo()` method.
- **`endRecord()` works on both SSBO and UBO** — for UBO: no write session needed, `endRecord()` sets `lastWrittenCount = 1`. Call `endRecord()` before `upload()`.
- **TBO always binds to `DEFAULT_TBO_TEXTURE_UNIT` (7)** — the `bindingLocation` param to the TBO constructor is ignored; the texture unit is always 7.
- **`deleteGlResources()` hook** — override in subclasses owning additional GL objects.
- **Engine-reserved binding slots 0–9** — `create()` and `CgShaderBufferRegistry.getOrCreate()`
  throw `IllegalArgumentException` if `bindingLocation < CgBindingPoints.USER_START (= 10)`.
- **Engine pipeline buffers bypass the registry** — `CgMaterialPipeline`'s frameUbo/objectBuffer
  use binding points 0 and 1, managed directly by `CgMaterialPipeline`, never in the registry.
