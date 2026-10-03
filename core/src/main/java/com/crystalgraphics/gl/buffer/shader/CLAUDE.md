# gl/buffer/shader — Shader Buffer Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)
> Parent guide: [`gl/buffer/CLAUDE.md`](../CLAUDE.md)

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
CgEngineBufferRegistry      (engine buffers a shader opts into via `#pragma cg_use`)
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
│  CgStreamBuffer (transport)                                  │
│  Default createForShaderBuffer: orphan at offset 0, what     │
│  glBindBufferBase and glTexBuffer read -- a block written    │
│  once stays readable while bound. Frame-local SSBO/UBO       │
│  (createFrameLocal): the frame ring, bound by range; the     │
│  upload re-binds. Only for data every reading frame uploads. │
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
| `CgShaderBuffer` | Abstract base + factories. Owns `String name`, `CgStreamBuffer dataBuffer`, `CgBufferWriter writer`, write-session API, `bind(CgShader)`, `wireToShader(CgShader)` (wiring only, no context bind), abstract `wireShader(CgShader)` hook, `delete()` template, `getGlBufferId()`. Two factory variants: `create(String name, CgBufferFormat format, int userIndex)` (user-facing — adds per-type USER_START internally) and `createInternal(String name, CgBufferFormat format, int bindingPoint)` (engine-internal, raw binding). **`CgBufferFormat` and `name` are mandatory**. Every upload passes `uploadData`, which skips one of at most 4 KB (`COMPARE_LIMIT_FLOATS`) equal to the last — except a `FRAME` buffer's first upload of a frame — and re-binds a `FRAME` buffer either way; counters `shaderBuffer.upload` / `shaderBuffer.uploadSkipped`. |
| `CgShaderStorageBuffer` | SSBO backend. GL 4.3 core or `ARB_shader_storage_buffer_object`. `wireShader` calls `glGetProgramResourceIndex`+`glShaderStorageBlockBinding` to wire the named block to `bindingLocation`. Not a no-op. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture. `bindingLocation` IS the texture unit (no separate constant). `wireShader` calls `glUniform1i(getName(), bindingLocation)` to wire the `samplerBuffer` uniform. `bindInternal()` includes an Intel sampler object workaround: unbinds any sampler object from the unit before binding the TBO texture. |
| `CgUniformBuffer` | UBO for a uniform block, with a `CgBufferLifetime`: `FRAME` for every engine block — material properties (`CgMaterial` uploads at every bind), the text block (`getOrCreateUboInternal`) — or `RETAINED`, the default for `getOrCreateUbo(format, name, index)`. `upload()` passes `CgShaderBuffer.uploadData`'s skip. Constructors `(name, format, bindingLocation[, lifetime])`. `getName()` is the GLSL block name. `wireShader` calls `glUniformBlockBinding(programId, blockIndex, bindingLocation)`. Uses unified `endRecord()` from parent. |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/TBO/UBO buffers. Two caches: `ShaderBufferKey(name, format, bindingPoint)` (covers both SSBO and TBO) and `UboKey(name, format, bindingPoint)`. `getOrCreate(String name, CgBufferFormat format, int userIndex)` and `getOrCreateUbo(CgBufferFormat format, String name, int userIndex)` take 0-based `userIndex`; per-type USER_START is added internally. `deleteAll()` called by `CgGraphicsLifecycle.destroyContext()`. Engine-internal pipeline buffers bypass this registry. |

## Binding Point and Texture Unit Namespaces

SSBO binding points, TBO texture units, and UBO binding points are **three separate namespaces**.
Engine buffers take from the **high end** of each range; user buffers from the **low end**.

| Resource | Slot | Constant |
|----------|------|----------|
| SSBO/TBO (per-object, engine) | ssbo `maxSsboBindings - 1` (e.g. 7), tbo `maxTexImageUnits - 1` (e.g. 15) | `CgBindingPoints.OBJECT_DATA` (a `Binding(ssbo, tbo)` record — call `.resolve()` for the active path) |
| SSBO/TBO (`CgQuadRenderer`, engine) | one slot below `OBJECT_DATA`'s on each namespace | `CgBindingPoints.QUAD_RENDERER` (same `Binding` shape) |
| UBO (per-frame, engine)   | `maxUniformBufferBindings - 1` (e.g. 35) | `CgBindingPoints.FRAME_DATA_UBO` |
| User SSBOs | `CgBindingPoints.USER_START_SSBO` (0) upward | pass 0-based `userIndex` to factories |
| User TBOs  | `CgBindingPoints.USER_START_TBO` (5) upward | pass 0-based `userIndex` to factories |
| User UBOs  | `CgBindingPoints.USER_START_UBO` (0) upward | pass 0-based `userIndex` to factories |

Reserved engine SSBO/TBO pairs are `CgBindingPoints.Binding(ssbo, tbo)` records, not two loose
`int`s — bundles both namespace slots together and knows how to `resolve()` itself against the
already-detected capability path, so a consumer needing one reserved pair
(`CgShaderBufferRegistry.getOrCreateInternal(name, format, binding)`) passes a single object.

`CgBindingPoints.init(CgCapabilities)` must be called (by `CgGraphicsLifecycle.initContext`) before
any engine buffer is constructed.

## Two-Phase Buffer Usage: Wire vs Bind

Each engine buffer uses two separate GL operations:

- **Wire** (`wireToShader(shader)`) — per-program, called once after each link via `CgMaterial.recompile()`. Associates block/sampler name with slot. Idempotent.
- **Bind** (`bind()`) — per-context. Establishes the actual GL binding: `glBindBufferBase` for a `RETAINED` buffer, which stays bound; `glBindBufferRange` at the latest upload for a `CgBufferLifetime.FRAME` one, which every upload re-binds (`CgShaderBuffer.uploadData`); `glActiveTexture+glBindTexture` for a TBO. The executor binds the engine pair per pass: the frame block from the pass's constants, the object buffer it owns.

User buffers may still use `bind(shader)` which does both in one call (requires shader to be active).

## Object Record ABI (must match `cg_env.glsl`)

Each per-object slot is **48 floats / 192 bytes** (`CgInstanceKind.OBJECT.format()`):

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

## `CgEngineBufferRegistry` — `#pragma cg_use`

Engine-provided buffers a `.shader` opts into by name:

```glsl
#pragma cg_use quad     // → CgQuadRenderer's per-instance buffer, macro QUAD_DATA
```

`CgMaterialShader.recompile()` resolves the token here and attaches the buffer **after parse, before
compile** — so the GLSL declaration exists no matter what triggers the first compile. The parser
hard-fails a shader that uses `QUAD_DATA`/`CG_QUAD_*` without declaring it.

**Why a registry rather than `cg_env.glsl`:** `CgFrameBlock`/`CgObjectDataBuffer` are declared
unconditionally because nearly every shader wants them. A quad buffer is wanted by a small minority,
and on the TBO path the declaration costs a texture unit — so it is opt-in.

**Why providers hold a `Supplier`, not a buffer:** the built-in `quad` entry is seeded with the
method reference `CgQuadRenderer::sharedBuffer`, which does *not* trigger that class's static
initialization at registration time. That matters — its buffer allocates against `CgBindingPoints`
and is only valid once `CgBindingPoints.init` has run. The supplier is invoked at attach time,
when a GL context exists.

**Adding one:** `CgEngineBufferRegistry.register(token, supplier, macroName)`, before any shader
referencing the token is parsed. Registering a duplicate token throws.

**Built-ins are seeded in this class's own static block**, not by the provider registering itself.
That was tried: self-registration only takes effect once something touches the provider class, which
nothing guarantees before a shader naming the token is parsed — `initContext` itself compiles
`text.shader`, which declares `cg_use quad`. Making it work needs an explicit "initialize this class
now" call at exactly the right point in startup, which couples correctness to init order and broke
the headless parser tests. Seeding here has no ordering requirement: the registry initializes on the
parser's first lookup, which is exactly when a token is needed.

> **Do not add a Java-side attach helper for these.** One existed
> (`CgQuadRenderer.attachTo(material)`) and was removed: attaching at first use loses to anything
> that compiles the shader earlier, and the resulting undeclared-symbol failure surfaced as an
> unrelated `#pragma cg_feature` complaint. The pragma is the wiring.

## Lifecycle

**SSBO/TBO (user buffer per-object data) — format-aware mode**
```java
// userIndex 0 → SSBO: binding CgBindingPoints.USER_START_SSBO (0)
//              → TBO:  unit   CgBindingPoints.USER_START_TBO   (5)
CgShaderBuffer myBuf = CgShaderBuffer.create("myData", MY_FORMAT, 0, CgBufferLifetime.FRAME);   // rewritten every frame

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

**UBO — format-aware mode** (the frame block, `CgBufferLifetime.FRAME`)
```java
CgBufferWriter w = frameUbo.writer();
w.reset()
 .beginRecord()
 .mat4("cg_ViewMatrix", view)
 .mat4("cg_ProjMatrix", proj)
 .vec4("cg_Time", t/20, t, t*2, t*3)
 .vec2("cg_Resolution", width, height);
frameUbo.endRecord();  // finalize the record (sets lastWrittenCount = 1)
frameUbo.upload();     // skipped if the bytes match and this frame already has them
frameUbo.bind();       // glBindBufferRange(GL_UNIFORM_BUFFER, FRAME_DATA_UBO, buffer, offset, size)
```

**After program link** (engine — via `CgMaterial.recompile()`):
```java
shader.bind();
CgUniformBuffer.wireBlock(shader, CgPassConstants.BLOCK_NAME, CgBindingPoints.FRAME_DATA_UBO);
CgShaderBuffer.wireBlock(shader, CgInstanceKind.OBJECT_BLOCK_NAME, CgBindingPoints.OBJECT_DATA);  // SSBO block or TBO sampler
shader.unbind();
```

## Key Design Rules

- **`name` is first constructor param** for all subclasses. Never null.
- **`wireToShader` vs `bind(CgShader)`**: prefer `wireToShader` after link (engine); use `bind(shader)` for user buffers (does both bind + wire in one call, requires program already active).
- **`bind(CgShader)` requires shader to be already bound AND shader must not be null** — GL uniform queries need the program active. If buffer is attached to a `CgMaterial`, prefer argless `bind()` — material handles `wireShader()` automatically on each compile.
- **SSBO `wireShader` is NOT a no-op** — calls `glShaderStorageBlockBinding` post-link.
- **TBO `bindingLocation` IS the texture unit** — no separate offset or DEFAULT_TBO_TEXTURE_UNIT constant.
- **`validateBindingPoint()` was removed** — `create()` adds per-type USER_START internally.
- **`CgUniformBuffer.bindBlock()` methods were removed** — use `buffer.bind(shader)` or `wireToShader`.
- **`CgUniformBuffer.blockName` field was removed** — use `getName()` from parent.
- **`DEFAULT_TBO_TEXTURE_UNIT` constant was removed** — `bindingLocation` is used directly.
- **`CgBindingPoints.OBJECT_DATA`, `FRAME_DATA`, `USER_START`, `TBO_ENGINE_UNIT`, `toTboUnit()` were removed** — use the new per-type constants (`OBJECT_DATA_SSBO`, `OBJECT_DATA_TBO`, `FRAME_DATA_UBO`, `USER_START_SSBO`, `USER_START_TBO`, `USER_START_UBO`).
- **Engine blocks bypass the registry** — the frame block and object buffer use high-end binding slots resolved at runtime; the executor owns the object buffer and binds the frame block from each pass's constants.
- **The frame ring only through `CgBufferLifetime.FRAME`** — it binds by range at each upload's offset. A `RETAINED` buffer and every TBO stay at offset 0, which `glBindBufferBase`/`glTexBuffer` read.
- **`CgBindingPoints.init()` must run before any engine buffer construction** — `CgGraphicsLifecycle.initContext` calls it first.
