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

## Layered Design

```
┌──────────────────────────────────────────────────────────────┐
│  CgShaderBuffer (abstract base)                              │
│  Owns: String name, CgStreamBuffer dataBuffer,               │
│        CgBufferWriter writer, write-session API,             │
│        bind(CgShader), wireShader() hook, delete template    │
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

## Type Map

| Type | Role |
|------|------|
| `CgShaderBuffer` | Abstract base + factories. Owns `String name`, `CgStreamBuffer dataBuffer`, `CgBufferWriter writer`, write-session API, `bind(CgShader)`, abstract `wireShader(CgShader)`, `delete()` template, `getGlBufferId()`. Two factory variants: `create(String name, CgBufferFormat format, int userIndex)` (user-facing, adds USER_START internally) and `createInternal(String name, CgBufferFormat format, int bindingPoint)` (engine-internal, raw binding). **`CgBufferFormat` and `name` are mandatory**. |
| `CgShaderStorageBuffer` | SSBO backend. `wireShader` is a true no-op — `layout(binding=N)` in GLSL handles wiring at link time. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture. `bindingLocation` IS the texture unit (no separate DEFAULT_TBO_TEXTURE_UNIT constant). `wireShader` calls `glUniform1i(getName(), bindingLocation)` to wire the `samplerBuffer` uniform. Warns if uniform not found. |
| `CgUniformBuffer` | UBO for per-frame data. Constructor `(String name, CgBufferFormat format, int bindingLocation)`. Owns `BLOCK_NAME`, `upload()`. `wireShader` calls `glUniformBlockBinding(programId, blockIndex, bindingLocation)`. The `blockName` field was removed — `getName()` on the parent is used instead. |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/TBO/UBO buffers. Two caches: `ShaderBufferKey(name, format, bindingPoint)` (covers both SSBO and TBO) and `UboKey(name, format, bindingPoint)`. `getOrCreate(String name, CgBufferFormat format, int userIndex)` and `getOrCreateUbo(CgBufferFormat format, String name, int userIndex)` take 0-based `userIndex`; USER_START is added internally. `deleteAll()` called by `CgGraphicsLifecycle.destroyContext()`. |

## Binding Point and Texture Unit Namespace

SSBO binding points and TBO texture units share the **same logical index space**:

| Resource | Point | Constant |
|----------|-------|----------|
| SSBO/TBO (per-object, engine) | 0 | `CgBindingPoints.OBJECT_DATA` / `TBO_ENGINE_UNIT` |
| UBO (per-frame, engine)       | 1 | `CgBindingPoints.FRAME_DATA` |
| 2–4                           | reserved | engine future use |
| User buffers                  | ≥ 5 | `CgBindingPoints.USER_START` |

`CgBindingPoints.toTboUnit(binding)` is an identity mapping (binding N → texture unit N).

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

The engine object buffer is named `"CgObjectDataBuffer"` (both the SSBO block and TBO sampler uniform
use this name in `cg_env.glsl`).

## Lifecycle

**SSBO/TBO (per-object data)**
```java
// userIndex 0 → actual binding point = CgBindingPoints.USER_START (5)
CgShaderBuffer myBuf = CgShaderBuffer.create("myData", MY_FORMAT, 0);

myBuf.beginWrite(N);
for (int i = 0; i < N; i++) {
    myBuf.writer().beginRecord().vec4("color", r, g, b, a);
    myBuf.endRecord();
}
myBuf.endWrite();

shader.bind();
myBuf.bind(shader);   // SSBO: no-op wiring; TBO: sets samplerBuffer uniform
// draw N instances
myBuf.unbind();
shader.unbind();
```

**UBO (per-frame data)**
```java
CgBufferWriter w = frameUbo.writer();
w.reset().beginRecord()
 .mat4("cg_ViewMatrix", view)
 .mat4("cg_ProjMatrix", proj)
 .vec4("cg_Time", t/20, t, t*2, t*3)
 .vec2("cg_Resolution", width, height);
frameUbo.endRecord();
frameUbo.upload();
frameUbo.bind();

// After program link — wire UBO block index to binding slot:
frameUbo.bind(shader);  // calls wireShader → glUniformBlockBinding
```

## Key Design Rules

- **`name` is first constructor param** for all subclasses. Never null.
- **`bind(CgShader)` requires shader to be already bound** — GL uniform queries need the program active.
- **SSBO `wireShader` is a true no-op** — `layout(binding=N)` in GLSL handles it at link time.
- **TBO `bindingLocation` IS the texture unit** — no separate offset or DEFAULT_TBO_TEXTURE_UNIT constant.
- **`validateBindingPoint()` was removed** — `create()` adds USER_START internally; no manual range checks needed.
- **`TBO_USER_START` constant was removed** — it equalled USER_START; use `CgBindingPoints.USER_START` directly.
- **`CgUniformBuffer.bindBlock()` methods were removed** — use `buffer.bind(shader)` instead.
- **`CgUniformBuffer.blockName` field was removed** — use `getName()` from parent.
- **`DEFAULT_TBO_TEXTURE_UNIT` constant was removed** — `bindingLocation` is used directly.
- **Engine pipeline buffers bypass the registry** — `CgMaterialPipeline`'s frameUbo/objectBuffer
  use binding points 0 and 1, managed directly by `CgMaterialPipeline`, never in the registry.
- **Never use MapAndSync for shader buffers** — offset mismatch with `glBindBufferBase`/`glTexBuffer`.


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
(`getTboTextureUnit()`) without casts. `bindBlock()` has been removed — use `buffer.bind(shader)`.

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
| `CgShaderBuffer` | Abstract base + factories. Owns `CgStreamBuffer`, `CgBufferWriter`, write-session API (`beginWrite/endRecord/endWrite`), `delete()` template with `deleteGlResources()` hook, `getGlBufferId()`, `@Getter String name`. Two factory variants: `create(String name, CgBufferFormat format, int userIndex)` (user-facing — adds `USER_START` internally) and `createInternal(String name, CgBufferFormat format, int bindingPoint)` (engine-internal, raw binding). `bind(CgShader)` calls `bind()` then `wireShader(shader)`. Abstract `wireShader(CgShader)` hook implemented per subclass. **`CgBufferFormat` is mandatory.** Buffer starts at capacity 1 and auto-grows on `beginWrite(N)`. Binding location is `final` after construction. |
| `CgShaderStorageBuffer` | SSBO backend. GL 4.3 core or `ARB_shader_storage_buffer_object`. `wireShader` is a true no-op. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture. `bindingLocation` IS the GL texture unit — no hardcoded override. `wireShader` sets `glUniform1i(getName(), bindingLocation)`. `deleteGlResources()` deletes the texture. |
| `CgUniformBuffer` | UBO for per-frame data. Constructor `(String name, CgBufferFormat format, int bindingLocation)`. `getName()` is the GLSL block name. `bindBlock()` **deleted** — use `buffer.bind(shader)`. `wireShader` calls `glUniformBlockBinding`. Uses unified `endRecord()` from parent. |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/TBO/UBO buffers. Two caches: `ShaderBufferKey(name, format, bindingPoint)` (covers both SSBO and TBO) and `UboKey(name, format, bindingPoint)`. `getOrCreate(name, format, userIndex)` and `getOrCreateUbo(format, name, userIndex)` take 0-based user index. `deleteAll()` called by `CgGraphicsLifecycle.destroyContext()`. Engine-internal pipeline buffers bypass this registry. |

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
| UBO (per-frame)         | 1 | `CgBindingPoints.FRAME_DATA` |
| User buffers            | ≥ 5 | `CgBindingPoints.USER_START` (pass 0-based userIndex to factories) |

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
shader.bind();
buffer.bind(shader);  // glBindBufferBase + wireShader (SSBO: no-op; TBO: sets samplerBuffer uniform)
// draw N instances
buffer.unbind();
shader.unbind();
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
frameUbo.bind(shader);  // glUniformBlockBinding — wires CgFrameBlock UBO block index
// TBO path — handled automatically by objectBuffer.bind(shader) in CgMaterial.bind()
```

## Key Design Rules

- **Never use MapAndSync for shader buffers** — offset mismatch with `glBindBufferBase`/`glTexBuffer`.
- **Never hard-code `"CgFrameBlock"`** — always use `CgUniformBuffer.BLOCK_NAME`.
- **Binding location is immutable** — set at construction. No `bindTo()` method.
- **`endRecord()` works on both SSBO and UBO** — for UBO: no write session needed, `endRecord()` sets `lastWrittenCount = 1`. Call `endRecord()` before `upload()`.
- **TBO `bindingLocation` IS the texture unit** — set from the factory via `USER_START + userIndex`. No hardcoded unit.
- **`deleteGlResources()` hook** — override in subclasses owning additional GL objects.
- **Engine-reserved binding slots 0–4** — `create()` adds `USER_START` internally; `createInternal()` takes raw binding points for engine-reserved slots.
- **Engine pipeline buffers bypass the registry** — `CgMaterialPipeline`'s frameUbo/objectBuffer use binding points 0 and 1, managed directly by `CgMaterialPipeline`, never in the registry.
- **`bind(CgShader)` requires shader already bound** — `shader.bind()` must be called before `buffer.bind(shader)` for uniform/block-index queries to land on the correct program.
