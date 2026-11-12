# The TBO path — unhooked, kept reconnectable

Every engine buffer a shader reads is a storage block (SSBO: GL 4.3 core, or `ARB_shader_storage_buffer_object`). Until
rhi X2 (2026-11-04) a context without them read the same buffers as **buffer textures** (TBO, GL 3.1+), with the
shaders fetching each record's texels. The RHI's floor is GL 4.6 and Vulkan (`rhi.md` D8), so the path was unhooked;
this page is what to read to bring it back.

**Restore from**: CrystalGraphics `7f9984fa` (rhi branch), the last commit with it wired; the harness at `5dbfcef`.

## What is still there

The seams were left in place so reconnecting is restoring code, not redesigning:

- **The detect**: `CgCapabilities.textureBufferMaterialPath` (GL 3.3, no SSBO) and `ShaderBufferPath.TBO`. The choice
  in `CgCapabilities.shaderBufferPath` skips it (a comment marks where it went: after `SSBO_ARB`), and forcing it
  with `-Dcrystalgraphics.shaderBuffer.tier=TBO` is refused.
- **The binding pair**: `CgBindingPoints.Binding(ssbo, tbo)`. `init` still counts a texture unit down per engine buffer
  when `PATH == TBO` and `resolve()` still answers `tbo` there; today `tbo` is always -1.
- **The shader hook** is gone (rhi SH6): materials compile to one target, GLSL 4.50 over storage blocks, and
  `env/cg_env.glsl` declares the object records as a storage block alone. Its TBO branches are at `7f9984fa`.

## What it did, and where (at `7f9984fa`)

| Piece | Where |
|---|---|
| A shader buffer as a buffer texture: `glTexBuffer` over its storage, bound to a texture unit, the shader's `samplerBuffer` set to it | `gpu/resource/CgTextureBuffer`, made by `CgShaderBuffer.createInternal` when the path was TBO |
| One buffer texture per unit, pointed at whatever buffer a draw reads there | `gpu/resource/CgBufferTextures` (released in `CgGraphicsLifecycle`) |
| Attached buffers' GLSL: a `samplerBuffer` and a getter fetching each field from 16-byte texels (float-family fields only, stride a multiple of 16) | `CgGlslEmitter.emitTbo`, `appendTboFieldFetch`, `componentSwizzle`; chosen in `CgMaterialShaderCompiler.appendAttachedBuffers` |
| The object records: `uniform samplerBuffer CgObjectDataBuffer`, 12 RGBA32F texels a record, `cg_FetchObjectData(int)` | `env/cg_env.glsl`'s `#else` branches; bound by `CgExecutor.bindRecords` at `OBJECT_DATA.tbo()` |
| A material's `Buffers { }`: a `usamplerBuffer _cg_tbo_NAME` read through `_cg_load_NAME`, its texels R32UI/RG32UI/RGBA32UI by element width, a struct a texel per field | `CgBufferReaders.reader(sb, b, false)`, `tbo`, `texelWords`, `texelFormat`; bound by `CgBindingTable.texelBuffer` on `CgBindingPoints.materialBufferUnit(samplers, i)`; wired by `CgMaterialShader.wireMaterialBuffers`; `CgMaterial.checkTexels` refused a buffer past `GL_MAX_TEXTURE_BUFFER_SIZE` |
| A snapshot's texel buffers | `CgBindingTable`: a fifth header count, `(unit, format)` pairs and refs, bound through `CgBufferTextures` |
| The graph's view of them | `CgRasterPass` declared object records and buffers `SAMPLED_READ`, and each texel buffer |
| The type gate | `CgGpuType.isTboCompatible()` (float-family only) and the per-type TBO notes on `CgGpuType`/`CgBufferLayout`; `CgAttachedBuffer.tboGetterName` (`"_cg_get" + structName`, the getter's name) |
| Program interface | `CgShaderBuffer.wireBlock` set the sampler uniform; `CgShaderProgram`'s `storage` skipped block lookups (now `material.CgProgram`) |
| User buffers | `CgBindingPoints.USER_START_TBO` (unit 5, above Minecraft's 0–4) in `CgShaderBuffer.create` and `CgShaderBufferRegistry.getOrCreate` |
| The compiler's version | `glslVersion` answered 330 off the SSBO path; `appendMaterialBuffers` refused more samplers plus buffers than the units below the reserved ones |
| Rules kept for it | `CgCurveBatch`'s curve record stays float-typed at a 16-byte stride, so it would read through texels unchanged |
| Storage | a TBO cannot bind an offset, so it took retained storage (`createForShaderBuffer`) whatever lifetime was asked |
| Tests | `CgGlslEmitterTest`'s `emitTbo` cases, the TBO halves of both `ShippedShaderStagePurityTest`s, `CgMaterialBuffersTest.texturePathReadsTexels`, `CgShaderBufferRegistryTest.userStartTboIsAboveMcUnits`; the parse tests stubbed the path as `TBO` |

## Reconnecting

1. Restore `CgTextureBuffer` and `CgBufferTextures`, and choose `TBO` again in `CgCapabilities.shaderBufferPath`.
2. Put back each `PATH == TBO` branch above: the factories, `wireBlock`, `bindRecords`, the raster pass's accesses,
   the material's texel buffers and the binding table's texel section, the compiler's `useSsbo` and 330.
3. Restore `cg_env.glsl`'s two TBO branches behind a define the compiler emits only on the TBO path, and a second
   compiler target below `CgMaterialShaderCompiler.VERSION`.
4. Retained storage is now staged (`RetainedStreamBuffer`, copied in at offset 0), which `glTexBuffer` reads as it
   read the orphaned storage.
