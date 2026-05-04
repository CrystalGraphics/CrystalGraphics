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
```

All four types are `public`. Callers may hold concrete types for type-specific APIs
(`getTboTextureUnit()`, `bindProgramBlock()`) without casts.

## Layered Design

```
┌──────────────────────────────────────────────────────────────┐
│  CgShaderBuffer (abstract base)                              │
│  Owns: CgStreamBuffer dataBuffer, CgBufferWriter writer,     │
│        write-session API, delete template, getGlBufferId()   │
├──────────────────────────────────────────────────────────────┤
│  CgStreamBuffer (transport — createForShaderData)            │
│  Caps at MapAndOrphan (Tier B). Never MapAndSync (ring),     │
│  because ring slots return non-zero offsets incompatible     │
│  with glBindBufferBase and glTexBuffer (whole-buffer).       │
├──────────────────────────────────────────────────────────────┤
│  CgBufferWriter + CgStagingBuffer (CPU-side float staging)   │
│  Record-mode (SSBO/TBO): beginRecord/endRecord validates     │
│  exact float count per record via stored floatPerRecord.    │
│  Flat-mode (UBO): reset()/write.../upload() — no records.   │
└──────────────────────────────────────────────────────────────┘
```

## Why MapAndOrphan, Not MapAndSync

`CgStreamBuffer.createForShaderData(target, bytes)` caps at Tier B (orphan map):

- **SSBO/UBO**: `glBindBufferBase` always reads at offset 0. The sync ring returns
  `slot * slotSize` from `commit()` — data at a non-zero offset would require
  `glBindBufferRange`. Deferred until that plumbing exists.
- **TBO**: `glTexBuffer` attaches to the whole buffer object. Sub-range binding
  requires `glTexBufferRange` (GL 4.3+), which we don't target. MapAndSync is
  therefore always wrong for TBO regardless of `glBindBufferRange` support.
- **Orphan map** always writes at offset 0 → `glBindBufferBase` and `glTexBuffer`
  read the correct data on all three paths without extra plumbing.

## Type Map

| Type | Role |
|------|------|
| `CgShaderBuffer` | Abstract base + factory. Owns `CgStreamBuffer`, `CgBufferWriter`, write-session API (`beginWrite/advanceRecord/endWrite`), `delete()` template with `deleteGlResources()` hook, `getGlBufferId()`. Two constructors: record-mode and flat-mode. |
| `CgShaderStorageBuffer` | SSBO backend. Handles GL 4.3 core and `ARB_shader_storage_buffer_object`. Only owns `bindInternal/unbindInternal/getPath`. |
| `CgTextureBuffer` | TBO backend. Manages a `GL_TEXTURE_BUFFER` texture attached once at construction. `deleteGlResources()` deletes the texture. `glTexBuffer` binds to the buffer object (not storage), so auto-grow re-allocations need no re-attachment. |
| `CgUniformBuffer` | UBO for per-frame data. Flat-mode child of `CgShaderBuffer`. Owns `BLOCK_NAME`, `BINDING_POINT`, `upload()`, `bindProgramBlock()`. Bypasses parent session-tracking `bind()` with its own override. |

## Object Record ABI (must match `cg_env.glsl`)

Each per-object slot is 11 vec4 texels / 44 floats / 176 bytes (`FLOATS_PER_OBJECT`):

```
floats  0–15 : mat4 modelMatrix   (column-major)
floats 16–27 : mat3 normalMatrix  (3 columns, each padded to vec4 w=0)
floats 28–31 : vec4 custom0
floats 32–35 : vec4 custom1
floats 36–39 : vec4 custom2
floats 40–43 : vec4 custom3
```

## Binding Points

| Resource | Point | Constant |
|----------|-------|----------|
| SSBO / TBO (per-object) | 0 | `CgShaderBuffer.BINDING_POINT` |
| UBO (per-frame)         | 1 | `CgUniformBuffer.BINDING_POINT` |

## CgBufferWriter Modes

**Record-mode** (SSBO/TBO): `CgShaderBuffer` constructs writer with `floatPerRecord > 0`.
`endRecord(N)` validates exact float count written since `beginRecord()` (DEBUG),
then calls `staging.ensureRoomForStride(floatPerRecord)` to pre-allocate the next slot.

**Flat-mode** (UBO): `CgUniformBuffer` constructs writer with `floatPerRecord = 0`.
`beginRecord/endRecord` are never called. `putFloat` auto-grows the staging array.
Caller pattern: `writer().reset()` → write fields → `upload()`.

## Lifecycle

**SSBO/TBO (per-object data)**
```
buffer.beginWrite(N)               // declare N records, reset writer
  N × {
    writer().beginRecord()
    writer().mat4(...).mat3Padded(...).vec4Zero()...
    writer().endRecord(FLOATS_PER_OBJECT)
    buffer.advanceRecord()
  }
buffer.endWrite()                  // uploads staged data to GPU
buffer.bind(N)                     // glBindBufferBase, validates N <= written
// draw N instances
buffer.unbind()
```

**UBO (per-frame data)**
```
CgBufferWriter w = frameUbo.writer();
w.reset();
w.mat4(view).mat4(proj);          // write any uniform fields in declaration order
frameUbo.upload();                  // push to GPU
frameUbo.bind();                    // glBindBufferBase(GL_UNIFORM_BUFFER, 1, ...)
// draw
frameUbo.unbind();
```

**After program link** (once per program):
```
frameUbo.bindProgramBlock(CgUniformBuffer.BLOCK_NAME, programId);
// TBO path only:
shader.set1i("cg_ObjectTBO", objectBuffer.getTboTextureUnit());
```

**Context destroy**: call `delete()` on all instances. Parent handles buffer deletion;
`CgTextureBuffer.deleteGlResources()` also deletes the texture object.

## Key Design Rules

- **Never use MapAndSync for shader buffers** — offset mismatch with `glBindBufferBase`/`glTexBuffer`.
- **Never hard-code `"CgFrameBlock"`** — always use `CgUniformBuffer.BLOCK_NAME`.
- **`floatPerRecord` is caller-owned** — `CgShaderBuffer.create(floatPerRecord, capacity)`
  accepts any stride. `FLOATS_PER_OBJECT = 44` is the default ABI, not a hard constraint.
- **`deleteGlResources()` hook** — override in subclasses that own additional GL objects
  (e.g. `CgTextureBuffer` owns `tboTexId`). Parent `delete()` calls this then sets `deleted = true`.
