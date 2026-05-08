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
without casts.

## Layered Design

```
┌──────────────────────────────────────────────────────────────┐
│  CgShaderBuffer (abstract base)                              │
│  Owns: String name, CgStreamBuffer dataBuffer,               │
│        CgBufferWriter writer, write-session API,             │
│        bind(CgShader), wireToShader(CgShader),               │
│        wireShader() hook, delete template                    │
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
| `CgShaderBuffer` | Abstract base + factories. Owns `String name`, `CgStreamBuffer dataBuffer`, `CgBufferWriter writer`, write-session API, `bind(CgShader)`, `wireToShader(CgShader)` (wiring only, no context bind), abstract `wireShader(CgShader)` hook, `delete()` template, `getGlBufferId()`. Two factory variants: `create(String name, CgBufferFormat format, int userIndex)` (user-facing — adds per-type USER_START internally) and `createInternal(String name, CgBufferFormat format, int bindingPoint)` (engine-internal, raw binding). **`CgBufferFormat` and `name` are mandatory**. |
| `CgShaderStorageBuffer` | SSBO backend. GL 4.3 core or `ARB_shader_storage_buffer_object`. `wireShader` calls `glGetProgramResourceIndex`+`glShaderStorageBlockBinding` to wire the named block to `bindingLocation`. Not a no-op. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture. `bindingLocation` IS the texture unit (no separate constant). `wireShader` calls `glUniform1i(getName(), bindingLocation)` to wire the `samplerBuffer` uniform. `bindInternal()` includes an Intel sampler object workaround: unbinds any sampler object from the unit before binding the TBO texture. |
| `CgUniformBuffer` | UBO for per-frame data. Constructor `(String name, CgBufferFormat format, int bindingLocation)`. `getName()` is the GLSL block name. `wireShader` calls `glUniformBlockBinding(programId, blockIndex, bindingLocation)`. Uses unified `endRecord()` from parent. |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/TBO/UBO buffers. Two caches: `ShaderBufferKey(name, format, bindingPoint)` (covers both SSBO and TBO) and `UboKey(name, format, bindingPoint)`. `getOrCreate(String name, CgBufferFormat format, int userIndex)` and `getOrCreateUbo(CgBufferFormat format, String name, int userIndex)` take 0-based `userIndex`; per-type USER_START is added internally. `deleteAll()` called by `CgGraphicsLifecycle.destroyContext()`. Engine-internal pipeline buffers bypass this registry. |

## Binding Point and Texture Unit Namespaces

SSBO binding points, TBO texture units, and UBO binding points are **three separate namespaces**.
Engine buffers take from the **high end** of each range; user buffers from the **low end**.

| Resource | Slot | Constant |
|----------|------|----------|
| SSBO (per-object, engine) | `maxSsboBindings - 1` (e.g. 7) | `CgBindingPoints.OBJECT_DATA_SSBO` |
| TBO (per-object, engine)  | `maxTexImageUnits - 1` (e.g. 15) | `CgBindingPoints.OBJECT_DATA_TBO` |
| UBO (per-frame, engine)   | `maxUniformBufferBindings - 1` (e.g. 35) | `CgBindingPoints.FRAME_DATA_UBO` |
| User SSBOs | `CgBindingPoints.USER_START_SSBO` (0) upward | pass 0-based `userIndex` to factories |
| User TBOs  | `CgBindingPoints.USER_START_TBO` (5) upward | pass 0-based `userIndex` to factories |
| User UBOs  | `CgBindingPoints.USER_START_UBO` (0) upward | pass 0-based `userIndex` to factories |

`CgBindingPoints.init(CgCapabilities)` must be called (by `CgMaterialPipeline.init()`) before
any engine buffer is constructed.

## Two-Phase Buffer Usage: Wire vs Bind

Each engine buffer uses two separate GL operations:

- **Wire** (`wireToShader(shader)`) — per-program, called once after each link via `CgMaterial.recompile()`. Associates block/sampler name with slot. Idempotent.
- **Bind** (`bind()`) — per-context, called once per frame in `CgMaterialPipeline.beginFrame()`. Establishes the actual GL binding (`glBindBufferBase` / `glActiveTexture+glBindTexture`).

User buffers may still use `bind(shader)` which does both in one call (requires shader to be active).

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

**SSBO/TBO (user buffer per-object data) — format-aware mode**
```java
// userIndex 0 → SSBO: binding CgBindingPoints.USER_START_SSBO (0)
//              → TBO:  unit   CgBindingPoints.USER_START_TBO   (5)
CgShaderBuffer myBuf = CgShaderBuffer.create("myData", MY_FORMAT, 0);

myBuf.beginWrite(N);
for (int i = 0; i < N; i++) {
    myBuf.writer().beginRecord().vec4("color", r, g, b, a);
    myBuf.endRecord();
}
myBuf.endWrite();

shader.bind();
myBuf.bind(shader);   // glBindBufferBase + wireShader (SSBO: glShaderStorageBlockBinding; TBO: glUniform1i)
// draw N instances
myBuf.unbind();
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
frameUbo.bind();       // glBindBufferBase(GL_UNIFORM_BUFFER, FRAME_DATA_UBO, ...)
```

**After program link** (engine — via `CgMaterial.recompile()`):
```java
shader.bind();
pipeline.frameBuffer().wireToShader(shader);   // glUniformBlockBinding for CgFrameBlock
pipeline.objectBuffer().wireToShader(shader);  // glShaderStorageBlockBinding (SSBO) or glUniform1i (TBO)
shader.unbind();
```

## Key Design Rules

- **`name` is first constructor param** for all subclasses. Never null.
- **`wireToShader` vs `bind(CgShader)`**: prefer `wireToShader` after link (engine); use `bind(shader)` for user buffers (does both bind + wire in one call, requires program already active).
- **`bind(CgShader)` requires shader to be already bound** — GL uniform queries need the program active.
- **SSBO `wireShader` is NOT a no-op** — calls `glShaderStorageBlockBinding` post-link.
- **TBO `bindingLocation` IS the texture unit** — no separate offset or DEFAULT_TBO_TEXTURE_UNIT constant.
- **`validateBindingPoint()` was removed** — `create()` adds per-type USER_START internally.
- **`CgUniformBuffer.bindBlock()` methods were removed** — use `buffer.bind(shader)` or `wireToShader`.
- **`CgUniformBuffer.blockName` field was removed** — use `getName()` from parent.
- **`DEFAULT_TBO_TEXTURE_UNIT` constant was removed** — `bindingLocation` is used directly.
- **`CgBindingPoints.OBJECT_DATA`, `FRAME_DATA`, `USER_START`, `TBO_ENGINE_UNIT`, `toTboUnit()` were removed** — use the new per-type constants (`OBJECT_DATA_SSBO`, `OBJECT_DATA_TBO`, `FRAME_DATA_UBO`, `USER_START_SSBO`, `USER_START_TBO`, `USER_START_UBO`).
- **Engine pipeline buffers bypass the registry** — `CgMaterialPipeline`'s frameUbo/objectBuffer use high-end binding slots resolved at runtime, managed directly by `CgMaterialPipeline`, never in the registry.
- **Never use MapAndSync for shader buffers** — offset mismatch with `glBindBufferBase`/`glTexBuffer`.
- **`CgBindingPoints.init()` must run before any engine buffer construction** — `CgMaterialPipeline.init()` calls it first.
