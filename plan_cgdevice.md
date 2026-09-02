# `CgDevice` — the migration of `core/` to one device seam

**Status**: design v2, 2026-09-02. Nothing here is implemented.
**Precondition**: `plan_mc26_diagnosis.md` — why a device-level seam is required at all.
**Companions**: `plan_harness_lwjgl3.md` (the harness moves first, on its own), `plan_cgvulkan.md`
(the self-owned Vulkan backend), **`plan_cgdevice_milestones.md`** (the schedule: D0–D6, rip in
place, no shims).
**Scope**: what `core/` becomes so that ONE code path renders through OpenGL (1.7.10, the harness,
1.20.x if revived) and through Vulkan (a self-owned backend in the harness; Blaze3D's `GpuDevice` on
26.x) with no `if (vulkan)` in the engine — at production quality, and simpler than today.

**v2 over v1.** v1 was the rulebook, written from the material, state, pass, buffer and UI-context
frameworks. v2 adds what a full migration needs: the rest of `core/` read the same way (§1), the
scrap-or-keep decisions that the licence to rewrite unlocks (§2), the production-grade requirements
the rules do not cover (§5), a host contract (§6), a per-package matrix for all 249 files (§7), the
CrystalGUI consumer surface from a call census (§8), and the order of work with its tests (§9).
Every claim about today's code names the file and the method.

---

## 0. The direction

> **One of each.** One seam (`CgDevice`). One shader authoring surface (`.shader` materials). One
> draw model (immutable pipelines, explicit passes, bind groups). One upload model (a per-frame ring).
> One batching model (SSBO-instanced primitives — `CgQuadRenderer`, `CgVectorRenderer`). One lifecycle
> (device frame / device close). **Anything that is a second way of doing one of those six is
> scrapped, not ported.**

That is the simplicity criterion and the production criterion at once. Every silent-failure class in
`AGENTS.md`'s invariant table that concerns rendering — state leaking between materials, a wrong
framebuffer drawing nothing, a first draw lost into a fresh FBO, a scissor stack unbalanced, a
uniform written to the wrong program — is a consequence of having two ways to do one of these
things. Vulkan does not permit the second way; the GL backend can emulate the first in a few hundred
lines. So core is written against the Vulkan-shaped model on both backends, and the vocabulary is
WebGPU's, which wgpu runs over GL ES 3.0 and Vulkan today and which Blaze3D 26.2 arrived at
independently.

The engine's objects are kept. `CgTexture*`, `CgFrameBuffer`, `CgMesh`, `CgMaterial`, the text stack,
the shader graph — every API a consumer is written against — stay, and hold a device handle where
they hold a GL name today. That is bgfx's arrangement. A caller constructs a `CgTexture2D` as now.

### 0.1 The floor: Core GL 3.3, on every host, 1.7.10 included (decided 2026-09-02)

**No API-family waterfall survives anywhere — not in core, not in the GL backend.** Framebuffers,
VAOs, sampler objects, instanced arrays, `glMapBufferRange`, fence sync (3.2), UBOs and multisample
textures are all core in 3.3, so the GL backend calls `GL30`/`GL32`/`GL33` statics directly, on LWJGL 2
and LWJGL 3 alike. What this deletes from the binding layer and the backend:

- the three-way Core/ARB/EXT `if` inside every FBO and renderbuffer method of `Lwjgl2GLBackend`,
  `EXTFramebufferObject`, `EXTFramebufferBlit`, `ARBFramebufferObject`;
- the four `ARBShaderObjects` unified-handle methods (`glDeleteObject`, `glGetObjectParameteri`,
  `glGetObjectInfoLog`, `glGetHandle`) and `CgArbShaderProgram`;
- `ARBSync`, `ARBInstancedArrays.glVertexAttribDivisorARB`, `ARBSamplerObjects` dispatch;
- `CgCapabilities.FramebufferPath` and `preferredFboBackend()`, `optimalDepthBlitMask` (an EXT quirk),
  `CgGlStateShadow.FboFamily.EXT`, and most of `CgGLContext`'s 21 extension probes;
- `bindFramebufferCompat`, which existed so `OpenGlHelper` could pick the family on 1.7.10 — the
  adapter hands the host's target in instead (R7).

What is **not** a waterfall and stays as a **feature gate inside the GL backend**, invisible to core:
SSBO (4.3) vs the TBO getter (§11), `glCopyImageSubData` (4.3) vs a blit, `ARB_clip_control` (4.5) vs
the depth-range epilogue (R6), `layout(binding=)` (4.2) vs by-name wiring (R8), timestamp queries.
Each is one boolean on `CgDeviceInfo.features`, read once at device creation.

The consequence worth stating once so it is a decision and not an accident: `CgCapabilities`'
javadoc names *"OpenGL 2.0+ / Intel HD 3000 and above"* as the hardware range. HD 3000 is GL 3.1 on
Windows and is below the floor.

### 0.2 macOS — the ceiling, and the wall

Apple's OpenGL is frozen at **4.1 core profile** — deprecated since 10.14, still shipped, Apple
Silicon included — and offers exactly two context kinds: a **2.1 legacy** profile with GLSL 1.20, or
a **3.2+ core** profile. There is no compatibility profile above 2.1. Two consequences, pointing in
opposite directions.

**The ceiling is why 3.3 is the floor, and why every feature gate in §0.1 is mandatory.** Nothing
above 4.1 exists on any Mac: no SSBO (4.3), no `glCopyImageSubData` (4.3), no `layout(binding=)`
(4.2), no `ARB_clip_control` (4.5), no persistent mapping (`ARB_buffer_storage`, 4.4), no compute. So
the TBO path is not an old-hardware courtesy — it is the per-instance path for every Mac on 1.20.x
and on 26.x-under-Prefer-OpenGL — and the same holds for by-name block wiring, the depth-range
epilogue, the blit fallback and map-per-allocation in the frame ring. The design already contains
each of them; Mac makes them load-bearing. **A parity run on a Mac is therefore the acceptance test
for the GL backend's gate paths**: no Windows or Linux driver forces all of them at once.

**The wall is 1.7.10.** Vanilla 1.7.10 is fixed-function — `glPushMatrix`, immediate mode, the alpha
test — so it must create the legacy context, and on macOS that context is OpenGL 2.1 with GLSL 1.20:
no VAO, no UBO, no instancing, no `#version 330`. That is not a limit this plan introduces; it is where
CrystalGraphics stands today (the compiler emits `#version 330 core`), and it is why Angelica — the
1.7.10 shader and performance mod — declares macOS unsupported. **1.7.10 on macOS is out of scope**,
recorded here so it is a decision. "Core 3.3 on every host, 1.7.10 included" means 1.7.10 on Windows
and Linux; macOS enters at 1.20.x, where Minecraft itself creates a 3.2 core context and Apple answers
with 4.1.

**26.x on macOS needs nothing extra.** Mojang runs its Vulkan backend there through MoltenVK and keeps
OpenGL (Apple's 4.1) as the fallback; the Blaze3D adapter sits on their abstraction and is indifferent
to which. The harness's self-owned Vulkan backend reaches Mac the same way — LWJGL 3 ships MoltenVK in
its macOS natives — with two portability details: the instance sets
`VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR`, and the device enables `VK_KHR_portability_subset`.
Dynamic rendering and push descriptors, the two extensions the Vulkan backend requires, are exactly
the two Mojang requires and ships on MoltenVK, so their availability there is established by
Mojang's own release rather than assumed. Apple Silicon: LWJGL 3 has arm64 natives; LWJGL 2 does not.

The harness today cannot run on a Mac at all — LWJGL 2 with `ContextAttribs(3, 0)
.withForwardCompatible(false)` is handed the legacy 2.1 context there. The LWJGL 3 harness requests
`GLFW_OPENGL_CORE_PROFILE` + `GLFW_OPENGL_FORWARD_COMPAT` at 3.3 and is handed 4.1.

---

## 1. What the deep dive found

Beyond v1's reading of `api/state`, `api/material`, `gl/material`, `gl/buffer`, `api/render`'s
pipeline and CrystalGUI's `CgUiPaintContext`/`CgUiBackdrop`/`ScissorStack`:

**A second shader authoring surface exists and is mostly dead.** `api/shader` (10 files, 1,955
lines: `CgShader`, `CgShaderProgram`, `CgShaderBindings`, `CgShaderManager`, `CgShaderCacheKey`,
`CgActiveUniform`, `CgSystemUniformRegistry`, `CgUniformInjector`, `CgShaderPreprocessor`), `gl/shader`
(1,073: `CgShaderFactory`, `CgCoreShaderProgram`, `CgArbShaderProgram`, `CgAbstractShaderProgram`) and
`mc/shader` (1,058: `CgShaderImpl`, `CgShaderManagerImpl`, `CgShaderReloadHook`) are the raw-GLSL API
that predates materials. `CgSystemUniformRegistry` and `CgUniformInjector` have **zero** references
outside their own files. `CgShader` is referenced from 16 files — the material compiler's output type,
`CgDebugBlit`, `CgFontDemo`, and **17 call sites in the harness** (`CgShaderFactory.load/fromSource`),
all of them scenes testing the raw layer itself. `CgShaderPreprocessor` (481 lines, `#include`,
`#pragma once`, cycle detection) is the one piece the material path genuinely uses.

**A second batching model exists and is dead.** `gl/render`'s layer system — `CgBufferSource`,
`CgLayer`, `CgRenderLayer`, `CgDynamicTextureRenderLayer`, `CgAbstractRenderer`,
`CgQuadInstanceRenderer` — has **zero** consumers. `CgBatchRenderer` (265) is used by two harness
scenes; `CgUiRenderer`'s own javadoc records that it *used to* wrap it and moved to `CgQuadRenderer`.
`CgInstanceRenderer` (165) survives as two static draw helpers `CgMesh.drawInstanced` calls and two
harness demos. The production batching model is `CgQuadRenderer` (535, 15 consumers) and
`CgVectorRenderer` (1,006, 23 consumers), both SSBO-instanced.

**A pass descriptor was started and abandoned.** `gl/pass` — `CgClearPolicy` ("what a render pass
clears when it begins") and `CgProjectionMode` — 100 lines, zero references. It is R3's pass
descriptor, first attempt.

**Demo code is wired into the lifecycle.** `CgGraphicsLifecycle.onOpaquePass` calls
`CgRenderDemo.INSTANCE.renderOpaque(...)`; `demo/` (512 lines) lives in `core/`; mc1710's
`CrystalGraphicsFontDemo` references both demos.

**The text renderer is where the GL model leaks hardest.** `CgTextRenderer` (1,388) keeps a
class-wide static `TEXT_DATA_UBO` that `syncProjection` rewrites in place whenever any renderer's
projection changes; a `CgTextureMutable` (`ATLAS_TEXTURE_REF`) whose GL id is swapped per flush to
point the material's `_MainTex` at the current atlas — a hack around the sampler property API; an
ad-hoc `(isWorldText() ? CgDepthState.TEST_ONLY : CgDepthState.NONE).apply()` *after* the material
bind, overriding `text.shader`'s own `DepthTest` lines; keyword toggles per flush (MSDF vs bitmap); a
`restoreStateWith(Runnable)` hook so callers can repair state after a flush; and
`CgTextRendererRegistry`, a registry whose jobs are auto-resizing screen-sized renderers and teardown.

**The glyph pipeline already has the right commit point.** `CgFontRegistry.tickFrame` drains completed
async results into atlas pages under byte, count and wall-clock budgets (its javadoc records the
tuning, including a reverted experiment). That drain is exactly where R5 wants texture writes. The
synchronous `CgGlyphAtlas.allocateBitmap/allocateMsdf` paths upload inside the draw path and are the
only ones that must change.

**Two consumers depend on the 3D pipeline singleton for a projection.** `CgUiPaintContext.beginFrame`
and `beginLayerFbo` write their ortho into `CgRenderPipeline.getInstance().getFrameData()` and call
`prepareFrame()` so `cg_ProjMatrix` is right for GUI shaders; `CgMainPreviewRenderer` copies frame
data in and out around its draw for the same reason. The UI's projection lives in the world
renderer's UBO.

**Previews are a pass with a resolve.** `CgPreviewTarget` is an MSAA-renderbuffer FBO plus a plain
one, `resolve()` is a blit, `CgPreviewPool` owns them per context (teardown step 8b),
`CgPreviewSlots` is the GL-free keep/reuse/evict policy. `CgMainPreviewRenderer.lastDriverError()`
surfaces compile failure to the editor.

**Materials already have most of the machinery.** `CgMaterialShader.recompile` parses, resolves
`#pragma cg_use` through `CgEngineBufferRegistry` (a `Supplier<CgShaderBuffer>` + macro per token),
builds the properties UBO, and caches programs by `(passName, keywords)`; `CgMaterial.onShaderRecompiled`
carries property values across a hot reload by name and type; `hasCompileFailed` latches a failure;
`drawChain`/`nextPass` sequence materials over one draw; shadow and depth passes are auto-generated.

**Dead classes** (zero references anywhere in core, CrystalGUI, harness, mc1710): `CgMsdfQualityProbe`
(641, a diagnostic), `CgSystemUniformRegistry` (274), `CgQuadInstanceRenderer` (130), `CgHalfFloat`
(103), `CgTextureState` (92), `CgMeshLoader` (86 — documented as the mesh-loading facade and used by
nothing), `CgScissorRect` (81), `CgClearPolicy` (56), `CgGlyphMetrics` (50), `CgProjectionMode` (44).

**The consumer surface is small.** CrystalGUI calls ~40 distinct CrystalGraphics methods (§8). The
harness calls ~30, a third of them the raw-shader layer.

---

## 2. Scrap or keep — the decisions

| Framework | Verdict | Replacement | What is lost |
|---|---|---|---|
| **Raw shader API** — `api/shader` (all but the preprocessor), `gl/shader`, `mc/shader`, `CgDebugBlit` | **Scrap.** A second authoring surface, dead in production, with its own uniform-injection, cache-key, hot-reload and ARB-object machinery | Materials only. `CgShaderPreprocessor` moves under `gl/material/parse`. `CgMaterial.fromStages(vertex, fragment, vertexFormat)` wraps a plain `.vert`/`.frag` pair as a one-pass material so nothing a raw shader could express becomes inexpressible. `CgDebugBlit` → a `blit.shader` material on a covering triangle | The harness's 17 raw-shader call sites; those scenes test the layer being removed and are rewritten as material scenes in the parity suite |
| **Batch render layers** — `CgBufferSource`, `CgLayer`, `CgRenderLayer`, `CgDynamicTextureRenderLayer`, `CgAbstractRenderer`, `CgQuadInstanceRenderer`, `CgBatchRenderer` | **Scrap.** Dead, and a second batching model | `CgQuadRenderer` / `CgVectorRenderer`, which already are the model. `CgInstanceRenderer` → `CgMesh.drawInstanced(pass, instanceSlice)` with a step-mode-INSTANCE layout (R9) | Two harness scenes |
| **`CgStreamBuffer` and its three tiers** | **Scrap.** Per-object rings at the wrong granularity (R5) | `CgFrameRing` — one per-frame bump allocator per usage, N frames in flight | nothing |
| **`CgShaderBuffer` / `CgUniformBuffer` / `CgTextureBuffer` hierarchy + registry** | **Rebuild.** Three classes, a registry and binding-point plumbing for "a buffer with a layout" | `CgGpuBuffer` + `CgBufferWriter` over `CgBufferFormat` (kept). A storage or uniform buffer is a bind-group entry. `CgTextureBuffer` (TBO) becomes a GL-backend-internal fallback (§11) | nothing a caller reaches: `writer()`/`beginWrite`/`endRecord`/`upload`/`attach` keep their names |
| **`CgBindingPoints`** | **Scrap** from core | per-layout allocation inside the GL backend (R4) | — |
| **`gl/pass`** | **Scrap.** Dead | `CgPassDesc`, which is what it was reaching for | — |
| **`demo/`** | **Out of core.** Demo scenes belong in the harness and mc1710's integration | `CgGraphicsLifecycle.onOpaquePass` calls `CgRenderPipeline` directly | — |
| **`CgRenderPipeline` singleton** | **Reshape.** Keep the command queue, sort keys, frustum, pass renderers; stop being the owner of everyone's projection | a per-device object; every pass owner writes its own frame slice (R5); the UI and the previews no longer reach into it | — |
| **`CgTextureMutable` / `ATLAS_TEXTURE_REF`** | **Scrap.** A hack around sampler binding | one bind group per glyph atlas (R2) | — |
| **`CgTextRendererRegistry`** | **Scrap.** Both jobs vanish: projection is per-draw, teardown is `device.close()` | — | — |
| **`CgVertexArray*`, `CgInstanceVertexBuffer`, `CgVertexBufferRegistry`** | **Move** into the GL backend | VAO cache per vertex layout (R9) | nothing — already documented as internal |
| **API-family waterfalls** — Core/ARB/EXT FBO and renderbuffer dispatch, `ARBShaderObjects`, `ARBSync`/`ARBInstancedArrays`/`ARBSamplerObjects` paths, `EXTFramebufferBlit`, `CgCapabilities.FramebufferPath`, `optimalDepthBlitMask`, `bindFramebufferCompat` | **Scrap** (§0.1) | direct `GL30`/`GL32`/`GL33` calls in the GL backend; feature gates for what sits above 3.3 | GL 3.1 hardware (Intel HD 3000 class) |
| **`gl/framebuffer` FBO objects** | **Move.** The `CgFrameBuffer` object stays in core | `CgFrameBuffer` = attachment set + pass descriptor; the FBO cache and completeness checks live in the GL backend (R3) | nothing a caller reaches |
| **`CgGlStateManager`, `CgGlScope`, providers** | **Move** into the GL backend | pipeline diff on bind; host hand-back at pass end (R1) | — |
| **`CgCapabilities`, `CgGLContext`** | **Move and shrink** | `CgDeviceInfo` at device creation (R7); the 21 probes become the handful of feature gates in §0.1 | — |
| **`CgAlphaState`, `PoseStack`'s three GL calls, `CgTextureState`, `CgScissorRect`** | **Scrap.** Fixed-function or dead | — | — |
| **Dead classes** listed in §1 | **Scrap**, except `CgMeshLoader` (a documented facade; keep) and `CgMsdfQualityProbe` (move to the harness as the diagnostic it is) | — | — |
| **Everything else** — text layout/cache/msdf/atlas/richtext, `api/font`, `api/text`, `shadergraph`, the material parser, `api/buffer`, `api/vertex`, `api/render`'s queue/pool/sort/frustum/hook, `api/framebuffer`, `api/texture` specs, `gl/mesh`, `gl/texture` objects, `util/*`, `mc/CgAssetReloader`, `mc/compat`, the JNI bindings | **Keep.** GL bodies move where they exist; APIs unchanged | — |

---

## 3. The vocabulary

`platform/` gains `CgDevice` and the objects below. `CgPlatformService.gl()` and `capabilities()`
become one `device()`; the other eight services are untouched. `CgGL`, `CgGLBackend`, `CgGLContext`,
`CgCapabilities` and `CgGlStateManager` become internals of the GL backend; `core/` never names them.

**The device objects sit under core's objects, not in their place.** `CgTexture2D` wraps a
`CgGpuTexture`; `CgFrameBuffer` owns views and a pass descriptor; `CgMesh` owns two `CgGpuBuffer`s
and a layout reference; `CgMaterial` owns pipeline templates and a bind group.

| Object | WebGPU | Blaze3D 26.2 | GL backend emulates with | Vulkan backend |
|---|---|---|---|---|
| `CgDevice` | `GPUDevice` | `GpuDevice` | the `CgGLBackend` function table + `CgGlStateManager` | `VkDevice` + queue + VMA |
| `CgDeviceInfo` (backend, vendor, limits, features) | adapter info / limits | `DeviceInfo`/`DeviceLimits`/`DeviceFeatures` | `CgCapabilities.detect()` once | physical-device properties |
| `CgGpuBuffer` (size, usage VERTEX/INDEX/UNIFORM/STORAGE/COPY_SRC/COPY_DST) — behind `CgMesh` and the buffer objects | `GPUBuffer` | `GpuBuffer` | a buffer name | `VkBuffer` + allocation |
| `CgBufferSlice` (buffer, offset, length) | `GPUBufferBinding` | `GpuBufferSlice` | `glBindBufferRange` / pointer offset | descriptor offset / bind offset |
| `CgGpuTexture` + `CgTextureView` (kind, `CgFormat`, size, mips, samples, usage) — behind `CgTexture2D/2DArray/3D/Cubemap` | `GPUTexture`/`GPUTextureView` | `GpuTexture`/`GpuTextureView` | a texture name (+ an FBO per view used as attachment) | `VkImage` + `VkImageView` |
| `CgSampler` (from `CgTextureSpec`'s filter/wrap/compare) | `GPUSampler` | bind-group entry | `glBindSampler` (GL 3.3) | `VkSampler` |
| `CgShaderModule` (stage, source in the backend's dialect) | `GPUShaderModule` | `ShaderSource` | `glCompileShader` | shaderc → SPIR-V → `VkShaderModule` |
| `CgBindGroupLayout` / `CgBindGroup` (binding, stages, UNIFORM / STORAGE / STORAGE_RO / TEXTURE / SAMPLER, dynamic offset) | `GPUBindGroupLayout`/`GPUBindGroup` | `BindGroupLayout` | a unit/point table; bind issues `glActiveTexture`+`glBindTexture`+`glBindSampler`, `glBindBufferRange` | descriptor set layouts / sets (or push descriptors, which 26.2 requires) |
| `CgPipeline` (modules, vertex layouts with step mode, topology, **complete** `CgRenderState`, target formats, sample count, bind-group layouts) | `GPURenderPipeline` | `RenderPipeline` | program + a state record diffed on bind | `VkPipeline` |
| `CgFrameBuffer` — **kept core object**: a named attachment set with a format, size, registry, and its own pass descriptor | none (bgfx's `FrameBufferHandle`) | `RenderTarget` | one FBO per attachment set, cached | `VkImageView`s + `VkRenderingInfo` |
| `CgRenderPass` (attachments with `loadOp`/`storeOp`/clear/`resolveTarget`, depth-stencil, render area) | `GPURenderPassEncoder` | `RenderPass` | FBO bind + viewport + `glClear` on begin; blit on end for resolve | `vkCmdBeginRendering` (dynamic rendering) |
| `CgCommandEncoder` (begin pass, copy buffer/texture, blit, write buffer/texture, timestamp) | `GPUCommandEncoder` | `CommandEncoder` | immediate GL calls | a `VkCommandBuffer` per frame |
| `CgFrame` + `CgFrameRing` (begin/end; per-frame ring, deletion queue, fence) | implicit | device frame | fence ring, as `MapAndSyncStreamBuffer` does today | frames in flight, `VkFence` per frame |
| `CgSurface` (the host's current colour/depth views, handed in by the adapter) | canvas context | `GpuSurface` / main `RenderTarget` | FBO 0 or the host's id | swapchain image / MC's target views |

```java
public interface CgDevice extends AutoCloseable {
    CgDeviceInfo        info();
    CgGpuBuffer         createBuffer(CgBufferDesc desc);
    CgGpuTexture        createTexture(CgTextureDesc desc);
    CgSampler           createSampler(CgSamplerDesc desc);
    CgShaderModule      createShader(CgShaderStage stage, CgShaderSource source);
    CgBindGroupLayout   createBindGroupLayout(CgBindGroupLayoutDesc desc);
    CgBindGroup         createBindGroup(CgBindGroupLayout layout, CgBindGroupDesc desc);
    CgPipeline          createPipeline(CgPipelineDesc desc);
    CgFrame             beginFrame();        // advances the ring, waits the oldest fence, drains deletions
    CgCommandEncoder    encoder();           // valid inside a frame
    CgSurface           surface();           // what the host is drawing to right now
    Thread              ownerThread();
}

public interface CgRenderPass extends AutoCloseable {
    void setPipeline(CgPipeline p);
    void setBindGroup(int index, CgBindGroup g, int... dynamicOffsets);
    void setVertexBuffer(int slot, CgBufferSlice s);
    void setIndexBuffer(CgBufferSlice s, CgIndexType t);
    void setViewport(float x, float y, float w, float h);   // top-left origin (R6)
    void setScissor(int x, int y, int w, int h);            // top-left origin (R6)
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);
    @Override void close();                                  // end
}
```

### 3.1 The descriptor types

Records, so two implementers cannot invent them differently. Every value below is either one
CrystalGraphics already has (`CgVertexAttribute`, `CgBufferFormat`, `CgTextureSpec`,
`CgFrameBufferFormat`, `CgMeshTopology`) or a neutral enum replacing a `CgGL.GL_*` constant.

```java
// ── enums (replace CgGL.GL_* everywhere in core) ────────────────────────────────────────────
enum CgBackend        { GL, VULKAN, BLAZE3D }
enum CgShaderStage    { VERTEX, FRAGMENT }
enum CgDialect        { GL330, GL430, VULKAN450 }
enum CgBlendFactor    { ZERO, ONE, SRC, ONE_MINUS_SRC, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, DST, ONE_MINUS_DST,
                        DST_ALPHA, ONE_MINUS_DST_ALPHA, SRC_ALPHA_SATURATED, CONSTANT, ONE_MINUS_CONSTANT }
enum CgBlendOp        { ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX }
enum CgCompareFunc    { NEVER, LESS, EQUAL, LESS_EQUAL, GREATER, NOT_EQUAL, GREATER_EQUAL, ALWAYS }
enum CgCullMode       { NONE, FRONT, BACK }
enum CgFrontFace      { CCW, CW }
enum CgStencilOp      { KEEP, ZERO, REPLACE, INVERT, INCREMENT_CLAMP, DECREMENT_CLAMP, INCREMENT_WRAP, DECREMENT_WRAP }
enum CgPrimitiveTopology { POINT_LIST, LINE_LIST, LINE_STRIP, TRIANGLE_LIST, TRIANGLE_STRIP }   // = CgMeshTopology; no fans (Metal has none)
enum CgIndexType      { UINT16, UINT32 }
enum CgVertexStepMode { VERTEX, INSTANCE }
enum CgFilter         { NEAREST, LINEAR }
enum CgMipmapFilter   { NONE, NEAREST, LINEAR }
enum CgAddressMode    { CLAMP_TO_EDGE, REPEAT, MIRROR_REPEAT, CLAMP_TO_BORDER }
enum CgLoadOp         { LOAD, CLEAR, DONT_CARE }
enum CgStoreOp        { STORE, DISCARD }
enum CgDepthDirection { FORWARD, REVERSED }                 // the pass policy (R6)
enum CgTextureKind    { D2, D2_ARRAY, D3, CUBE }
enum CgBufferUsage    { VERTEX, INDEX, UNIFORM, STORAGE, TEXEL, COPY_SRC, COPY_DST }   // EnumSet
enum CgTextureUsage   { SAMPLED, RENDER_ATTACHMENT, COPY_SRC, COPY_DST, TRANSIENT }    // EnumSet
enum CgBindingType    { UNIFORM, STORAGE, STORAGE_READONLY, TEXEL, SAMPLED_TEXTURE, SAMPLER, COMBINED_TEXTURE_SAMPLER }

// ── formats: WebGPU's names; every CgTextureType entry maps onto one ──────────────────────────
enum CgFormat {
    R8_UNORM, R8_SNORM, R8_UINT, RG8_UNORM, RG8_UINT, RGBA8_UNORM, RGBA8_SRGB, BGRA8_UNORM /* surfaces */,
    RGBA8_UINT, RGBA8_SINT,
    R16_UINT, R16_FLOAT, RG16_FLOAT, RGBA16_UINT, RGBA16_FLOAT,
    R32_UINT, R32_FLOAT, RG32_FLOAT, RGBA32_UINT, RGBA32_FLOAT,
    RGB10A2_UNORM, R11G11B10_FLOAT,
    DEPTH16_UNORM, DEPTH24_PLUS /* D24 or D32F, whichever the device has */, DEPTH32_FLOAT,
    DEPTH24_PLUS_STENCIL8 /* D24S8 or D32FS8 */, DEPTH32_FLOAT_STENCIL8, STENCIL8;
    // 3-channel entries (RGB8, SRGB8) map to the RGBA form; the backend expands rows on upload (§3.1 of the ledger).
    // "PLUS" is WebGPU's spelling for "the backend picks": Apple GPUs have no D24, MoltenVK reports D32FS8.
}

// ── descriptors ───────────────────────────────────────────────────────────────────────────────
record CgBufferDesc(String label, long size, EnumSet<CgBufferUsage> usage) {}
record CgTextureDesc(String label, CgTextureKind kind, CgFormat format, int width, int height,
                     int depthOrLayers, int mipLevels, int samples, EnumSet<CgTextureUsage> usage) {}
record CgTextureViewDesc(CgGpuTexture texture, int baseMip, int mipCount, int baseLayer, int layerCount) {}
record CgSamplerDesc(CgFilter min, CgFilter mag, CgMipmapFilter mip, CgAddressMode u, CgAddressMode v,
                     CgAddressMode w, float maxAnisotropy, @Nullable CgCompareFunc compare) {}   // from CgTextureSpec
record CgShaderSource(CgShaderStage stage, CgDialect dialect, String glsl, String debugName) {}

record CgBindingEntry(int binding, EnumSet<CgShaderStage> stages, CgBindingType type,
                      boolean dynamicOffset, String glslName /* by-name wiring below GL 4.2 */) {}
record CgBindGroupLayoutDesc(String label, List<CgBindingEntry> entries) {}
sealed interface CgBindingResource permits CgBufferSlice, CgTextureBinding {}
record CgTextureBinding(CgTextureView view, @Nullable CgSampler sampler) implements CgBindingResource {}
record CgBindGroupDesc(CgBindGroupLayout layout, List<CgBindingResource> resources /* by entry order */) {}

record CgVertexLayout(int stride, CgVertexStepMode step, List<CgVertexAttribute> attributes) {} // from CgVertexFormat / CgInstanceFormat
record CgColorTargetState(CgFormat format, @Nullable CgBlendState blend, int writeMask) {}
record CgStencilFace(CgCompareFunc compare, CgStencilOp fail, CgStencilOp depthFail, CgStencilOp pass) {}
record CgDepthStencilState(CgFormat format, boolean depthWrite, CgCompareFunc depthCompare,
                           @Nullable CgStencilFace front, @Nullable CgStencilFace back, int readMask, int writeMask) {}
record CgPipelineDesc(String label, CgShaderModule vertex, CgShaderModule fragment,
                      List<CgVertexLayout> vertexLayouts, CgPrimitiveTopology topology,
                      CgCullMode cull, CgFrontFace frontFace,
                      List<CgColorTargetState> targets, @Nullable CgDepthStencilState depthStencil,
                      int sampleCount, List<CgBindGroupLayout> bindGroupLayouts) {}

record CgColorAttachment(CgTextureView view, CgLoadOp load, CgStoreOp store, float[] clearRgba,
                         @Nullable CgTextureView resolveTarget) {}
record CgDepthStencilAttachment(CgTextureView view, CgLoadOp depthLoad, CgStoreOp depthStore, float clearDepth,
                                CgLoadOp stencilLoad, CgStoreOp stencilStore, int clearStencil) {}
record CgRect(int x, int y, int w, int h) {}                                           // top-left origin (R6)
record CgPassDesc(String label, List<CgColorAttachment> colors, @Nullable CgDepthStencilAttachment depthStencil,
                  @Nullable CgRect renderArea, CgDepthDirection depthDirection) {}

record CgDeviceLimits(int maxTextureSize, int maxTextureLayers, int maxColorAttachments, int maxSamples,
                      int maxVertexAttributes, int maxTextureUnitsPerStage, int maxUniformBlockSize,
                      int minUniformOffsetAlignment, int minStorageOffsetAlignment, int maxTexelBufferElements) {}
record CgDeviceFeatures(boolean storageBuffers, boolean texelBuffers, boolean copyImage, boolean clipControl,
                        boolean explicitBinding, boolean persistentMapping, boolean timestamps,
                        boolean anisotropy, boolean textureArrays, boolean texture3D, boolean stencil) {}
record CgDeviceInfo(CgBackend backend, String vendor, String renderer, String driver,
                    CgDeviceLimits limits, CgDeviceFeatures features) {}

// ── the encoder, the frame, the surface ───────────────────────────────────────────────────────
interface CgCommandEncoder {
    CgRenderPass beginPass(CgPassDesc desc);
    void writeBuffer(CgGpuBuffer dst, long offset, ByteBuffer data);          // via the staging ring
    void writeTexture(CgGpuTexture dst, int mip, int layer, CgRect region, ByteBuffer rows);
    void copyBufferToBuffer(CgBufferSlice src, CgBufferSlice dst);
    void copyTexture(CgGpuTexture src, CgGpuTexture dst);                     // same size and format
    void blit(CgTextureView src, CgRect srcRect, CgTextureView dst, CgRect dstRect, CgFilter filter);
    void generateMipmaps(CgGpuTexture texture);
    ByteBuffer readback(CgGpuTexture src, int mip, CgRect region);            // blocking; harness screenshots
    void clearColor(CgTextureView view, float[] rgba);                         // outside a pass
    void writeTimestamp(int slot);                                             // if features.timestamps
}
interface CgFrame {
    long index();
    CgBufferSlice allocate(long bytes, CgBufferUsage usage);                   // aligned per limits, valid until this frame's fence
    ByteBuffer map(CgBufferSlice ringSlice);                                   // ring slices only — persistent buffers are never mapped (§5.4.1)
    void deferClose(AutoCloseable resource);
    void end();                                                                // submit; on GL: swap is the host's
}
interface CgSurface {
    CgTextureView color(); @Nullable CgTextureView depth(); int width(); int height(); CgFormat format();
}
```

`CgTextureSpec` stays as the caller-facing spec; a `CgTexture` derives its `CgTextureDesc` and
`CgSamplerDesc` from it and holds both handles. `CgFrameBufferFormat` stays; `CgFrameBuffer` builds
its `CgPassDesc` from it (a `colorRenderbuffer` slot becomes a `TRANSIENT`, non-`SAMPLED` texture with
`samples > 1`).

---

## 4. The ten rules

Each: what the code does **today** (named), what **core** becomes, what the **GL backend** emulates,
what the **Vulkan backend** supplies, the **silent failure** guarded.

### R1 — Render state lives on the pipeline, and a pipeline is complete

**Today.** `CgRenderState` is five nullable slots plus colour masks, each `apply()`ing itself through
`CgGL` (`api/state/*`, 68 calls). `CgMaterial.doBind` runs `getPassRenderState(variant).apply()` before
`shader.bind()`, and **only declared domains are written; undeclared ones stay as the pass left them**
("ambient state": `CgUiPaintContext` enables blending around text; `CgTextRenderer.flush` applies a
depth state *after* the bind, overriding `text.shader`'s own lines). `CgRenderState.clear()` resets to
GL defaults. `CgTransparentRenderer` re-asserts ambient state per material. `CgGlStateManager`
exists to make these writes cheap and to hand the host its state back.

**Core.** `CgPipelineDesc` carries a **complete** `CgRenderState`. Ambient state becomes **pass
defaults**: a pass declares its base state, a material's `.shader` declares overrides (what the
nullable slots express), and the resolved pipeline is `passDefaults.overlaidWith(material)` computed
at pipeline creation. The text renderer's world/UI depth choice and its MSDF/bitmap keywords become
pipeline variants selected by `CgTextRenderContext`, not writes. `api/state`'s value objects stay as
descriptions; `apply()`/`clear()` go; `CgAlphaState` goes (a shader `discard` is the portable spelling).

The pipeline cache is keyed on `(shader variant, resolved state, vertex layouts, target formats,
sample count, bind-group layouts)`. A material used under two pass defaults has two pipelines.

**GL backend.** `bindPipeline` diffs the record against the shadow — `CgGlStateManager` with one
caller instead of sixty. `CgGlScope`/`hostForeign` survive there for one job: host hand-back at
`endPass`.

**Vulkan.** A `VkPipeline` per key.

**Guards against.** *"the transparent pass silently overwriting ZWrite"*, *"blending switched off and
every glyph an opaque block"*, *"a wrong elimination is a missing GL call"*.

### R2 — No loose uniforms; every value reaches a shader through a buffer or a bind group

**Today — closer than it looks.** Non-sampler `Properties {}` values already live in a UBO
(`CgMaterialProperties.partition` → `buildUboFormat()` → `layout(std140) uniform CgMaterialBlock`,
uploaded in `doBind` when `materialPropsDirty`). What remains loose:

| Loose uniform | Where |
|---|---|
| sampler unit integers, `setUniform1i(loc, unit)` per sampler property | `CgMaterialProperty.wireSamplerUnit`, `CgMaterialShader.wireShaderSamplers` (`cg_DepthBuffer`) |
| `u_time`, `u_resolution` injected by name on every `CgShader.bind()` | `CgSystemUniformRegistry` (dead) |
| raw `applyBindings` / `bindings().set…` | `CgDebugBlit`, `CgFontDemo`, `CgShaderImpl` |
| UBO block index → binding point post-link | `CgUniformBuffer.wireShader` |
| attribute locations bound by name pre-link | `CgShaderFactory` |
| `_LayerOpacity` rewritten into the material UBO per material switch | `CgUiPaintContext.withMaterial`/`withLayerOpacity` |
| `u_Projection` + `_PxRange` rewritten into a shared static UBO / property per text flush | `CgTextRenderer.syncProjection`, the `_PxRange` `applyProperties` |
| the atlas sampler retargeted by swapping a mutable texture id | `CgTextRenderer.ATLAS_TEXTURE_REF` |

**Core.** Three bind groups, fixed by convention so `layout(set = …)` is stable:

- **set 0 — frame/engine**: `CgFrameBlock`, the object-data SSBO, `cg_DepthBuffer` + sampler, the quad
  and curve instance SSBOs (`#pragma cg_use` tokens map here). Written by the pass owner per pass.
- **set 1 — material**: `CgMaterialBlock`, every sampler property, every `attach()`ed buffer. Owned
  by the material; rebuilt when a texture property changes. For text: one bind group per glyph atlas.
- **set 2 — per-draw**: a dynamic-offset slice from the frame ring. `_LayerOpacity`, a text draw's
  projection and `pxRange`. Not push constants — GL 3.3 has none.

The compiler emits `layout(set, binding)` and `layout(location)` explicitly in both dialects.
`CgSystemUniformRegistry` is deleted; `cg_Time`/`cg_Resolution` are in the frame block already.

**GL backend.** A bind group is a table: units and points allocated per layout (today's
`CgBindingPoints.init` countdown, per layout instead of global). **Vulkan.** Descriptor sets.

### R3 — Drawing is pass-scoped; a target switch is a pass boundary

**Today.** `CgUiPaintContext.beginFrame` binds `msaaFbo` and clears; `endFrame` blits to
`msaaResolveFbo` and composites with `blitLayer`; `beginLayerFbo(fbo, clear)` pushes a
`CgGlScope(FBO, VIEWPORT)`, binds, sets the viewport, re-applies the scissor, rewrites the frame UBO;
`CgUiBackdrop` reads `GL_DRAW_FRAMEBUFFER_BINDING`, blits scene → capture, resolves a sub-rect,
composites enclosing layers `withoutScissor`, blurs through `blurA`/`blurB`;
`CgRenderPipeline.executeOpaquePass` blits the depth snapshot then draws into the host's target
inside a `CgGlState.save(…)`; `CgPreviewRenderer` binds `target.drawTarget()`, draws, `resolve()`s;
`CgFrameBuffer.clear*` issues `glClear` against whatever is bound.

**Core.** Every draw is between `encoder.beginPass(desc)` and `pass.close()`:

| Today | Pass descriptor |
|---|---|
| `fbo.bind(); fbo.clearColor(…)` | colour attachment `loadOp = CLEAR(rgba)` |
| `beginLayerFbo(fbo, clear = false)` | `loadOp = LOAD` |
| MSAA draw + `blitFrom(msaaFbo)`; `CgPreviewTarget.resolve()` | attachment `{ view: msaa, resolveTarget, storeOp: DISCARD }` |
| `glViewport` on layer entry | `renderArea` / `setViewport` at pass begin |
| nested `beginLayerFbo` inside a live pass | end the outer pass, run the inner, re-begin the outer with `LOAD` (dynamic rendering has `SUSPENDING`/`RESUMING` for this shape) |
| `blitFrom`, `CgTextureCopy`'s blit fallback, the backdrop capture | `encoder.blit(src, dst, filter)` — a transfer op outside any pass |
| `blitDepthSnapshot(sourceFboId)` | `encoder.copyTexture(surface.depth(), snapshot)` at frame start — both APIs forbid sampling a live attachment |
| `captureSceneTarget()` via `glGet` | `device.surface().color()` (R7) |
| `withoutScissor` | `setScissor(fullTarget)` |
| `fbo.clearColor` at an arbitrary point | an explicit clear op (`vkCmdClearColorImage` outside / `vkCmdClearAttachments` inside a pass) — kept callable; the UI's layer clears should become load ops |

`CgFrameBuffer` produces its own descriptor: `try (CgRenderPass p = fbo.beginPass(encoder, loadOps))`.
The `LayerFrame` stack becomes a stack of descriptors; the projection per layer is a set-0 slice (R5).

**GL backend.** `beginPass` = bind the attachment set's FBO (one per set, cached — the GL half of
today's `CgFrameBuffer`), viewport, `glClear` for `CLEAR`; `close` = blit for a `resolveTarget`, then
hand the host its FBO/viewport back. **Vulkan.** `vkCmdBeginRendering` with load/store ops; native
resolve; layout transitions in and out.

**Guards against.** *"Unbalanced scissor stack after the main paint pass"*, *"a freshly allocated FBO
loses the first draw"*, *"the capture photographs its own empty target"*.

### R4 — Bind groups replace texture units and binding points

**Today.** `CgBindingPoints.init` counts SSBO/TBO/UBO points and units **down from the hardware
maximum** for engine buffers and hands user buffers points **up from zero**. `CgTexture.bind(unit)` is
`glActiveTexture` + `glBindTexture`; `activeUnit()` reads `GL_ACTIVE_TEXTURE` back. `attach(buffer,
"MACRO")` injects GLSL and wires a point post-link. The Blaze3D-provider note in AGENTS.md — *"tracks
only its first twelve units … counting downward … would unbind our own textures"* — is the cost.

**Core.** Binding is by layout entry. `attach()` keeps its signature and adds a set-1 entry.
`CgTexture` stays; `bind(unit)`/`activeUnit()` go. `CgEngineBufferRegistry`'s tokens (`quad`, `curve`)
resolve to set-0 entries. **GL backend.** Per-layout allocation. **Vulkan.** Descriptor sets.

### R5 — Frames in flight: an upload is an allocation, a deletion is deferred

**Today.** The idea exists at the wrong granularity: `MapAndSyncStreamBuffer` is a 3-slot ring with a
fence per upload, *per object*, backing every `CgShaderBuffer`/`CgUniformBuffer`. Objects are then
written in place many times per frame, relying on GL's in-order semantics:

| In-place write | Frequency |
|---|---|
| `CgQuadRenderer.GPU_BUFFER.uploadRaw` then `drawInstanced` — class-wide static; javadoc: *"whoever calls flush() last owns the buffer's contents"* | every `flush()` — dozens per UI frame |
| `CgVectorRenderer`'s instance buffer | same |
| `frameUbo` via `prepareFrame()` | every `beginLayerFbo`/`endLayerFbo`, every `updateOrtho` |
| `matPropsUbo` when `materialPropsDirty` (`_LayerOpacity`, `_PxRange`) | per material switch / text flush |
| `TEXT_DATA_UBO` via `syncProjection` | per projection change, shared across all text renderers |
| `objectBuffer` | per pass |
| `CgGlyphAtlas.allocateBitmap/allocateMsdf` → `uploadLayerRegion` | per new glyph, inside the draw path |

Three slots stall on the fourth upload; on Vulkan an in-place write is a **race** with a recorded draw.

**Core.** (1) **A per-frame write is a ring allocation**: `frame.allocate(bytes, usage)` → a
`CgBufferSlice` valid until the frame's fence. The renderers, the projection per layer, `_LayerOpacity`,
the text draw's projection and `pxRange` are slices. `CgStagingBuffer`/`CgInstanceWriter`/
`CgBufferWriter` keep their CPU role and write into mapped slices. Ring per usage, N frames in flight,
which is wgpu's `StagingBelt`. (2) **Texture writes happen outside passes**: `CgFontRegistry.tickFrame`'s
drain is already that point, budgets and all; the synchronous `allocate*` paths queue instead, so a
glyph first seen mid-pass draws on the next pass (the renderer already tolerates that for MSDF).
(3) **Deletion is deferred**: `close()` enqueues on the current frame; freed when its fence signals.
`destroyContext`'s eleven-step order collapses to `device.close()`; registries stay as caches.

**GL backend.** One `GL_STREAM_DRAW` buffer per usage, mapped unsynchronised per allocation, fenced
per frame. **Vulkan.** Host-visible rings, a `VkFence` per frame, a deletion queue, staging +
`vkCmdCopyBufferToImage` with barriers.

**Guards against.** Fence stalls inside `flush()`, and the race, which on Vulkan presents as
*geometry from a previous frame flickering* — the hardest rendering bug to attribute.

### R6 — One coordinate convention in core; each backend fixes up

**Today — three conventions.** UI: `ortho(0, w, h, 0, -1, 1)`; `ScissorStack.applyScissorIfNeeded`
flips to bottom-left with `targetHeight - (y + h)`; `glViewport` bottom-left; `CgTextureIO` decodes
bottom-left rows by design; `blitLayer` "carries the flip spelled `uv(0, 1, 1, 0)`"; `CgUiBackdrop`
computes `h - capH` rectangles by hand; world matrices come from the host; 26.2 is reversed-Z.

**Core.** WebGPU's convention, whole: NDC x right, **y up**, depth **[0, 1]**; viewport/scissor/render
area **top-left**; texture `(0,0)` is the first row in memory (unchanged — no image data moves);
projections built for `[0,1]` depth (JOML's `zZeroToOne`); reversed-Z is a **pass policy**
(`CgPassDesc.depthDirection`) the 26.x adapter declares, flipping `CgDepthState`'s compares once.

**GL backend.** `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` when `ARB_clip_control` exists, else the
compiler appends `gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;` to the GL-dialect vertex stage
(naga's `ADJUST_COORDINATE_SPACE`); rects flipped against the target height on the way to
`glScissor`/`glViewport`. **Vulkan.** Negative viewport height (maintenance1, core 1.1), which is wgpu's.

**Guards against.** Every flip becoming two, and the *"wrong by exactly the scale / displaced by
however far down the page"* class the invariant table records nine times.

### R7 — Nothing is queried from the device mid-frame

**Today.** `CgTexture.activeUnit()`, `CgTexture2DArray.uploadLayerRegion` (unpack alignment),
`CgTextureCopy` (FBO bindings), `CgUiBackdrop.captureSceneTarget` (the host's target),
`CgCapabilities.detectUncached` (limits), `CgGL.drainErrors`, `CgGpuProfiler`'s timer queries,
`CgFrameData`'s documented origin in fixed-function matrix reads.

**Core.** Limits from `device.info()` at creation; the host's target from `device.surface()`
(handed in — 1.7.10's FBO id, the harness's FBO 0, 26.x's main `RenderTarget` views); host matrices
through `CgFrameData` from the adapter, as every non-1.7.10 target does already; save/restore around
uploads gone (uploads are encoder ops); error draining is the GL backend's debug mode (Vulkan has
validation layers); timestamps are an optional device feature.

### R8 — One compiler, two dialects

**Today.** `CgMaterialShaderCompiler.buildVertexSource` emits `#version 430/330 core`, keyword
defines, `CG_VERTEX_STAGE`, the material's directives, auto-required extensions, `CG_USE_SSBO`,
`#include cg_env.glsl`, unlocated vertex inputs, sampler uniforms, `CgMaterialBlock`, attached
buffers (SSBO or the TBO `samplerBuffer` + getter), the v2f struct, `flat out int cg_InstanceId`,
varyings, the body. The parser half (~2,600 lines) touches no GL; `CgRenderStateParser` maps to
`CgGL.GL_*` constants and `CgShaderKeywords` holds 34 of them.

**Core.** The emitter gains a `Dialect` (`GL330`, `GL430`, `VULKAN450`):

| Emission | GL | Vulkan |
|---|---|---|
| `#version` | `330 core` / `430 core` | `450` |
| vertex inputs / varyings / frag outputs | `layout(location = i)` (3.3 core for inputs; by-name below 4.1 for varyings) | `layout(location = i)` required |
| blocks, buffers, samplers | `layout(std140/430, binding = k)` on 4.2+, by-name wiring below | `layout(set = s, binding = k, …)` |
| per-instance data | SSBO, or the TBO getter on 3.3 | SSBO only |
| instance id | `gl_InstanceID` | `gl_InstanceIndex` (`CG_INSTANCE_ID` already abstracts it) |
| depth range | the `gl_Position.z` epilogue without clip control | nothing |
| compile | text → `glCompileShader` | text → shaderc (LWJGL 3 `org.lwjgl.util.shaderc`) → SPIR-V |

`CgRenderStateParser` maps to API-neutral enums; each backend owns its enum table. `cg_env.glsl`'s TBO
half becomes GL-dialect-only. `ShippedShaderStagePurityTest`'s rule becomes a SPIR-V compile error.

### R9 — Vertex input is pipeline state; a mesh is buffers plus a layout reference

**Today.** `CgMesh.upload` creates VAO + VBO + IBO; `drawInstanced` binds the VAO and calls
`CgInstanceRenderer.drawElementsInstanced`; `CgVertexArrayRegistry` caches VAOs by format;
`CgInstanceVertexBuffer` configures divisor-1 attributes.

**Core.** `CgVertexFormat` (step VERTEX) and `CgInstanceFormat` (step INSTANCE) are the pipeline's
`vertexLayouts[]` — already the right shape. `CgMesh` = `{vertex, index, indexType, topology, counts}`,
no VAO; `drawInstanced(pass, count)` sets buffers and draws; `drawInstanced(pass, instanceSlice)`
replaces `CgInstanceRenderer`. **GL backend.** One VAO per `(layouts, bound buffers)`, cached.
**Vulkan.** Vertex-input state from the layouts.

### R10 — One device thread, one frame scope

**Today.** Twenty files say "GL thread"; `CgUiPaintContext`, `CgRenderPipeline`, the registries and
`CgGraphicsLifecycle` are static singletons. Under 26.2's Vulkan backend rendering runs on its own
thread.

**Core.** `CgDevice` records its owner thread and every entry point asserts it (the `UiThread.require`
pattern). All device work is inside `beginFrame()`…`end()`. CPU producers (shaping, MSDF, mesh
loading) stay on workers and hand bytes over — unchanged. The singletons become **per-device**
objects reachable from it, so the harness can hold a GL and a Vulkan device in one process for the
parity test.

---

## 5. Production-grade requirements the rules do not cover

### 5.1 Resource lifetime

- Every device object is `AutoCloseable`; `close()` defers to the current frame's deletion queue.
- Ownership is explicit: `create*` owns, `wrap`/views borrow, registries **cache** (they hold weak or
  explicit references and never decide teardown order). `createOwned` stays as "not in a registry".
- `device.close()` waits for all frames, drains every deletion queue, then destroys everything the
  device created, in one place. `CgGraphicsLifecycle.destroyContext` becomes: dispatch `onDestroy`
  listeners (reverse order, as today), then `device.close()`.
- A device object used after `close()` throws with its debug label (today's `checkNotDeleted` pattern).

### 5.2 Hot reload

- `CgMaterial.recompile` (F3+T through `CgAssetReloader` → `CgMaterialRegistry.reloadAll`) rebuilds the
  pipeline **templates**, invalidates every cached pipeline of that material, rebuilds its set-1 bind
  group, and carries property values across by name and type exactly as `onShaderRecompiled` does now.
- `hasCompileFailed` latches as today; `lastCompileError()` and the previews' `lastDriverError()` carry
  SPIR-V and GL messages alike. A failed material draws nothing and logs once, never per frame.
- `CgTextureManager.reloadAll` re-uploads into the *same* device texture (no handle change, so bind
  groups stay valid); a size or format change recreates and rebuilds dependent bind groups.
- Pipeline invalidation is by generation number on the material: a pass owner holding a stale
  pipeline compares generations at bind and re-resolves. No callbacks.

### 5.3 Resize

- `CgFrameBufferRegistry`'s screen-sized targets recreate on `onResize` as today; views change, so
  any bind group referencing a screen-sized colour texture (the backdrop's capture, the UI's resolve)
  is rebuilt — the registry announces a generation bump the same way.
- The harness's Vulkan backend recreates its swapchain on resize; in the game the host owns it.
- `CgUiPaintContext`'s MSAA and resolve targets follow the registry instead of resizing themselves in
  `beginFrame`.

### 5.4 Memory

- The frame ring is sized per usage from a budget (`CgDeviceConfig`: e.g. 8 MB uniform/storage,
  4 MB vertex, 16 MB staging) × frames in flight (3 on Vulkan, 2 on GL). Overflow allocates an extra
  chunk for the rest of the frame, logs once, and grows the ring for the next frame — never stalls
  mid-frame.
- Texture uploads go through the staging ring under `CgFontRegistry`'s existing byte/count/time
  budgets, which move from "how much to `glTexSubImage`" to "how much staging to consume".
- The ring's mapping strategy is a feature gate: persistent mapping (`ARB_buffer_storage`, 4.4)
  where present; `glMapBufferRange(MAP_UNSYNCHRONIZED_BIT | MAP_INVALIDATE_RANGE_BIT)` per allocation
  on Apple GL 4.1 and any 3.3 driver — which is what `MapAndSyncStreamBuffer` does today, so the Mac
  path is the one already measured.
- Immutable data (`CgMesh`, static textures) is device-local; uploaded once through staging.
- Device counters (`CgDeviceStats`: draws, pipeline switches, bind-group switches, ring bytes,
  uploads) are exposed to `CgProfiler` so the hot paths stay measurable.

### 5.4.1 Ring or persistent — the rule R5 glossed

> **Host memory writes go only into the ring. A persistent buffer or texture is written by a GPU
> copy from the ring, recorded on the encoder outside any pass.**

That one sentence removes the CPU/GPU race by construction: nothing the GPU may still be reading is
ever mapped, and a copy recorded in frame N+1 is ordered after frame N's draws by the queue (with a
barrier on Vulkan, implicitly on GL). No double-buffering of persistent resources is needed.

| Resource | Class | Written how |
|---|---|---|
| instance data (`CgQuadRenderer`, `CgVectorRenderer`, text), the per-pass frame block, set-2 per-draw slices, the object-data buffer (rewritten per pass), staging for every upload | **ring** | `frame.allocate` + `map` |
| `CgMaterialBlock` (material properties) | **persistent**, device-local | on dirty: a ring staging slice + `copyBufferToBuffer` before the frame's first pass |
| `CgMesh` vertex/index buffers, `CgQuadIndexBuffer` | persistent | uploaded once through staging |
| textures, glyph atlas pages | persistent | `writeTexture` from staging, outside passes (§5.10) |

Two consequences follow, and both are simplifications:

- **A value that changes within a frame is a per-draw value, not a material property.** That is
  what `_LayerOpacity` and the text renderer's `_PxRange`/projection are, which is why R2 puts them in
  set 2. Nothing else in the codebase writes a material property mid-frame.
- **A property written inside a pass still takes effect on the next draw**, without splitting the
  pass: the material allocates a ring copy of its block for the rest of this frame and points its
  set-1 binding at it (a push-descriptor write on Vulkan, a `glBindBufferRange` on GL); the persistent
  copy is refreshed at the next frame's start. The recording device asserts exactly this sequence.

Ring sizing is per usage × frames in flight (§5.4). A ring slice is freed when its frame's fence
signals; a persistent resource closed in frame k is destroyed after fence k.

### 5.5 Pipeline creation cost

- A Vulkan pipeline compile can take milliseconds; on GL it is a link. Both are hitches.
  `CgTextRenderer.warmUpMaterial` and `CgUiPaintContext.warm` generalise to
  `material.warm(passDefaults, targetFormats)` at init — `CgGraphicsLifecycle.initContext`'s
  `warmUpDeferredStartupCosts` is the place.
- The pipeline cache is per device and keyed as in R1; optionally persisted (`VkPipelineCache` to
  disk) for the Vulkan backend. GL has no equivalent worth doing.

### 5.6 Errors, validation, debug

- A `CgDevice.debug()` mode: `KHR_debug`/`ARB_debug_output` on GL, validation layers on Vulkan, object
  labels on both (`glObjectLabel` / `VK_EXT_debug_utils`) from the names every `create*` already takes.
  Off by default; the harness turns it on; `-Dcrystalgraphics.device.debug=true` elsewhere.
- Device loss is out of scope for v1 and named as such: the host owns the context/device; a lost
  device is a restart.
- `CgGL.drainErrors`/`assertNoGlError` become the GL backend's debug-mode behaviour.

### 5.7 Threading

- Device thread assertion at every entry (R10). The font executor, layout cache and MSDF generation
  are unchanged; their results reach the device only through `tickFrame`'s drain.
- The `CgTextLayoutCache` and `CgGlyphPlacementCache` are CPU-side and untouched.

### 5.8 Host coexistence

- The GL backend hands the host its state back at every `endPass` — the one job `CgGlScope` keeps.
  1.7.10's fixed-function alpha test is neutralised at `beginPass` by the mc1710 adapter, not by
  `CgUiPaintContext`.
- Under Blaze3D-hosted GL (1.20.x, or 26.x on Prefer OpenGL through the adapter), MC's `GlStateManager`
  cache is resynced at pass end (`_disableBlend`… through the adapter), which is what the mc1201
  three-tier routing was trying to do from the inside.
- On 26.x the adapter declares the pass policy (reversed-Z) and hands in the surface views.

### 5.9 Observability

- `CgProfiler` CPU scopes unchanged. `CgGpuProfiler` over device timestamps when the feature is
  present. `CgDeviceStats` per frame. The recording device (§9) makes submission structure assertable.

### 5.10 Glyph upload policy — what a draw does with a glyph whose texels have not landed

- `CgGlyphAtlas.allocateBitmap`/`allocateMsdf` stop uploading. They pack (CPU) as today and enqueue
  `(page, rect, bytes)` on the atlas's pending list; the returned `CgGlyphPlacement` carries
  `pendingUntilFrame = current + 1`.
- `CgFontRegistry.tickFrame` — called from `CgGraphicsLifecycle.tickFrame`, i.e. at `device.beginFrame()`
  time, **before any pass** — drains both the async results and that pending list, under the byte,
  count and wall-clock budgets it already has, into `encoder.writeTexture` calls (staging ring →
  copy). Nothing about the budgets or their tuning changes.
- `CgResolvedGlyphs.resolve` treats a pending placement exactly as it treats an MSDF glyph whose
  generation has not completed: the quad is skipped this frame and drawn on the next. One frame of
  latency for a never-before-seen glyph, which is what the async path already costs; zero pass splits.
- Glyphs beyond a frame's budget stay pending; the pending count is a `CgDeviceStats` counter.
- Creating a new atlas page or layer mid-frame is allowed (creation is not a pass op); its first
  upload follows the same queue.
- The harness's prewarm loops, which fast-forward frames to convergence before a capture, call
  `CgFontRegistry.tickFrame` directly today and keep doing so.

### 5.11 GL backend caches and their invalidation

Every cache the GL backend keeps is keyed on device objects that get resized, reloaded and closed.
The rule is the one §5.2 uses for pipelines — **generation numbers, never callbacks**:

- A `CgGpuTexture` and a `CgGpuBuffer` carry a `generation`, bumped on resize, reload-in-place and
  close. Every cache key includes the generations of the objects it references, and a reverse index
  from object → entries lets `close` drop entries eagerly; a stale generation found at lookup drops
  the entry lazily.
- **FBO cache**: key = the ordered list of `(view handle, generation)` for colour and depth
  attachments. `glCheckFramebufferStatus` runs at insert only, and an incomplete set throws with the
  format, as `CgFrameBuffer.create` does today.
- **VAO cache**: key = `(vertex-layout signature, vertex buffer handles + base offsets, index buffer
  handle)`. The index buffer is part of the key because a VAO captures the element-array binding —
  the fact `CgGlStateManager.vertexArrayChanged`'s javadoc records — so "never assume the IBO
  binding after a VAO switch" survives as a property of the key.
- **Pipeline cache**: key as in R1 plus the shader modules' generations (bumped on material
  recompile).
- **Sampler cache**: by `CgSamplerDesc` value.
- **Unit and binding-point allocation** is computed once per `CgBindGroupLayout` at creation:
  texture units count down from `maxTextureUnitsPerStage`, UBO and SSBO points from their maxima.
  A host adapter may reserve a low range (Blaze3D-hosted GL reserves units 0–11), which is the
  standing "twelve tracked units" hazard solved at the one place it belongs.
- **Host hand-back at `endPass`**: the shadow knows which domains the pass touched; restore is
  `CgGlStateManager`'s existing restore over those domains plus the host's FBO and viewport — exactly
  today's `CgGlScope.close`, invoked from one place.

---

## 6. The host contract

`CgPlatformService` changes by one method: `gl()` + `capabilities()` → `device()`, which returns the
device once the host's context exists (before that, `IllegalStateException`, as `CgPlatform`'s getters
already do). A host provides, per frame, through the existing services:

| Host | Device | Surface (`device.surface()`) | Frame matrices (`CgFrameData`) | Pass hooks | Reload / lifecycle |
|---|---|---|---|---|---|
| mc1710 | `CgGlDevice(new Lwjgl2GLBackend(), new Lwjgl2GLContext())` | MC's main FBO id + size (what `CgRenderHook` passes today as `sourceFboId`), depth available | read once per frame by the adapter from MC | `CgRenderHook` → `onOpaquePass`/`onTransparentPass`; `onFrameRendered` | as today |
| harness (GL) | `CgGlDevice(new Lwjgl3GLBackend(), …)` | FBO 0 + window size | scene's | `InteractiveSceneRunner` | as today |
| harness (Vulkan) | `CgVulkanDevice(instance, physical device, window)` | swapchain image + depth | scene's | same runner | same |
| 26.x | `CgBlazeDevice(RenderSystem.getDevice())` | MC's main `RenderTarget` colour/depth views; reversed-Z declared | from `RenderSystem`/level renderer | feature-renderer + GUI hooks (diagnosis §4.2) | MC's reload listener |

`CgRenderingService.onFrameBegin`/`getDisplayWidth/Height` stay; `CgLifecycleService.onContextInit`
creates the device. Nothing about input, sound, cursor, resources or reload changes.

On macOS, per host (§0.2): mc1710 — unreachable (legacy 2.1 context); harness GL — core profile 3.3
forward-compat, handed Apple 4.1, every feature gate off; harness Vulkan — MoltenVK with the
portability bit; 1.20.x — MC's own 3.2 core context, Apple 4.1; 26.x — whichever backend the player
chose, through the adapter.

### 6.1 The Blaze3D adapter's ceiling — what to verify against the 26.2 sources

Hooking into `GpuDevice` is a **backend**, not a cap on the engine: where Mojang's abstraction cannot
express a feature, the adapter reports it absent in `CgDeviceInfo.features` and core degrades the
same way the GL backend degrades on Apple GL 4.1. What the engine's capabilities actually rest on —
instanced primitives from a storage/texel buffer, materials as pipelines, explicit passes, bind
groups, MRT up to 8, dynamic-offset uniform slices, per-pass scissor, texture copies and readback,
custom vertex layouts, runtime-generated GLSL — is all expressible there. The known and suspected
gaps, each a one-line check against a decompiled `com.mojang.blaze3d` tree (which is **not** checked
in; `research_repos/` holds 1.20.1 only):

| Capability | Status on Blaze3D 26.2 | If absent |
|---|---|---|
| Runtime-generated shader source (the shader graph's whole output) | `GpuDevice.precompilePipeline(pipeline, sourceProvider)` has taken a `(ResourceLocation, ShaderType) → String` provider since 1.21.5 — **verify it survived 26.2's `ShaderSource`/`GlslCompiler` rework** | the shader graph on 26.x would need a resource-provider shim; a real blocker if neither exists |
| Storage buffers in a bind group | uniform, vertex, index and — as far as I know — **uniform texel buffers** (MC's own Mac-driven choice; SSBO would break Apple GL 4.1 for them too) | our TBO path *is* their path; nothing lost |
| Multisampled textures + resolve | **unverified** — MC does not use MSAA itself | UI and previews render single-sampled; `CgFrameBufferFormat.maxSamples()` resolves to 1 |
| Float / HDR / packed formats (`RGBA16F`, `R11F_G11F_B10F`, …) | `GpuFormat` is MC's enum — **likely a subset** of `CgTextureType`'s 42 | formats map to the nearest present; a material asking for an absent one fails at creation, loudly |
| 2D array textures (glyph atlases) | a layers parameter exists on texture creation since 1.21.5 — **verify** | atlases become one texture per page; the text renderer's page index becomes a bind-group switch |
| 3D textures, cubemaps | cubemap yes (the sky); 3D **unverified** | `CgTexture3D` unavailable on that host |
| Stencil state on a pipeline | **unverified** — MC does not use stencil | `CgStencilState` reported unsupported; nothing in the UI path uses it today |
| Sampler control (anisotropy, compare/PCF, per-axis wrap) | **likely limited** to MC's sampler vocabulary | shadow-compare samplers unavailable; PCF becomes a shader-side compare |
| GPU timestamps | MC profiles through Tracy internally — **probably not exposed** | `CgGpuProfiler` degrades, as designed |
| Reversed-Z | declared by the adapter as the pass policy | — |

Two routes past that ceiling exist and both are named in the diagnosis (§5.2): a self-owned device on
MC's `VkDevice` (no stability promise, Vulkan players only), or a second device with external-memory
interop. Neither is needed until a row above turns out to matter for a real feature, and the seam is
built so either can slot in under the same core.

---

## 7. Per-package migration matrix

Fates: **K** keep as is · **M** move the GL body under the GL backend, API unchanged · **R** reshape
(same name, new mechanism) · **S** scrap · **N** new.

| Package (files / lines) | Fate | Detail |
|---|---|---|
| `platform/` (27 / 3,578 gl+state) | N + M + S | N: `CgDevice`, `CgDeviceInfo`, `CgGpuBuffer`, `CgBufferSlice`, `CgGpuTexture`, `CgTextureView`, `CgSampler`, `CgShaderModule`, `CgBindGroupLayout`, `CgBindGroup`, `CgPipeline`, `CgPipelineDesc`, `CgRenderPass`, `CgPassDesc`, `CgCommandEncoder`, `CgFrame`, `CgFrameRing`, `CgSurface`, `CgFormat`, the state enums. M: `CgGL`, `CgGLBackend`, `CgGLContext`, `CgCapabilities`, `CgGlStateManager` + `state/` under `platform/gl/backend/`. S within those (§0.1): the ARB/EXT methods of `CgGLBackend` (renderbuffer and FBO variants collapse to one each, the `ARBShaderObjects` four go), `CgGLContext`'s extension probes bar the feature gates, `CgCapabilities.FramebufferPath`, `CgGlStateShadow.FboFamily.EXT`. K: `CgPlatform`, `CgService`, all seven services, `input/`. `CgPlatformService`: `gl()`+`capabilities()` → `device()` |
| `api/` (2 / 526) | S + R | `CgBindingPoints` S (per-layout allocation in the GL backend). `PoseStack` R: JOML only, three GL calls removed |
| `api/buffer` (5 / 630) | K | `CgBufferFormat`, `CgBufferField`, `CgGpuType` (std140/430 maths) unchanged |
| `api/font` (12 / 1,721) | K | `CgGlyphMetrics` S (dead) |
| `api/framebuffer` (1 / 448) | K + R | `CgFrameBufferFormat` kept; renderbuffer slots become "transient, not sampled" texture flags |
| `api/material` (7 / 1,673) | K + R | `CgMaterial` API kept whole (`load`, `newInstance`, `fromSource`, `attach`/`detach`, `applyProperties`, keywords, `bind`/`unbind`, `bindForPass`, `drawChain`, `nextPass`, `markDirty`, `recompile`, `delete`, `objectBuffer`, `materialBuffer`); internals R: pipeline templates + pipeline cache + set-1 bind group; `bind()` takes the pass. N: `fromStages(vert, frag, format)`. `CgMaterialRegistry`, `CgRenderPassVariant`, `CgRenderQueue`, `CgMaterialKey`, `CgAttachedBuffer` K |
| `api/mesh` (2 / 100) | K | |
| `api/render` (8 / 1,503) | K + R | K: `CgFrameData`, `CgRenderCommand`, `CgRenderCommandPool`, `CgRenderCommandQueue`, `CgSortKey`, `CgViewFrustum`. R: `CgRenderPipeline` — per-device, passes via the encoder, no `CgGlScope`, no demo call, frame slice written per pass; `CgPreDrawHook` receives a `CgFrame` to allocate its secondary instance data from |
| `api/shader` (10 / 1,955) | S + M | S: `CgShader`, `CgShaderProgram`, `CgShaderBindings`, `CgShaderManager`, `CgShaderCacheKey`, `CgActiveUniform`, `CgSystemUniformRegistry`, `CgUniformInjector`, `CgUniformName`. M: `CgShaderPreprocessor` + `CgPreprocessorException` → `gl/material/parse` |
| `api/state` (9 / 803) | K + R + S | K as descriptions: `CgRenderState`, `CgBlendState`, `CgDepthState`, `CgCullState`, `CgStencilState`, `CgColorMask`. R: no `apply()`/`clear()`; `CgRenderState` gains `overlaidWith(defaults)` and `isComplete()`. S: `CgAlphaState`, `CgTextureState`, `CgScissorRect` |
| `api/texture` (4 / 688) | K + R | K: `CgTexture`, `CgTextureSpec`, `CgMipmapConfig`. R: `CgTextureType` → `CgFormat` (neutral; the 42 entries survive as a per-backend mapping table; 3-channel formats expand on Vulkan, depth-stencil picks the nearest supported). `bind(unit)`/`activeUnit()` removed |
| `api/vertex` (8 / 1,044) | K | `CgVertexFormat`, `CgInstanceFormat`, `CgVertexAttribute`, `CgVertexSemantic`, `CgAttribType` are the pipeline's vertex layouts as they stand |
| `demo/` (2 / 512) | S | `CgRenderDemo` → a harness scene; `CgFontDemo` → harness; mc1710's `CrystalGraphicsFontDemo` follows. `onOpaquePass` calls `CgRenderPipeline` directly |
| `gl/buffer` (5 / 694) | S + K + N | S: `CgStreamBuffer`, `MapAndSyncStreamBuffer`, `MapAndOrphanStreamBuffer`, the subdata tier. K: `CgQuadIndexBuffer` (a static index buffer), `CgObjectBuffer` if still needed as the writer target. N: `CgFrameRing` |
| `gl/buffer/shader` | R + S | R: `CgShaderBuffer` and `CgUniformBuffer` rebuilt over `CgGpuBuffer` + `CgBufferWriter` with the same `writer()`/`beginWrite`/`endRecord`/`endWrite`/`upload`/`uploadRaw` API; `CgShaderBufferRegistry` → a cache. S: `CgTextureBuffer` from core (GL-backend fallback, §11) |
| `gl/buffer/staging` | K | `CgStagingBuffer`, `CgInstanceWriter`, `CgVertexWriter`, `CgBufferWriter` — CPU-side; they write into ring slices |
| `gl/debug` (1 / 253) | S | `CgDebugBlit` → `blit.shader` |
| `gl/framebuffer` (5 / 1,349) | K + M + S | K: `CgFrameBuffer` API, `CgFrameBufferRegistry`, `Attachment`. M: FBO objects and completeness checks. S: the Core/ARB/EXT dispatch, `optimalDepthBlitMask`, `bindFramebufferCompat`, `supportsMrt` (always true at 3.3). R: `bind`/`unbind` → `beginPass` |
| `gl/lifecycle` (2 / 569) | R | `initContext` creates the device and warms pipelines; `tickFrame` = `device.beginFrame()` + glyph drain + listeners; `destroyContext` = listeners + `device.close()`; `onOpaquePass`/`onTransparentPass` call the pipeline; `CgLifecycleListener` K |
| `gl/material` (4 / 1,762) | K + R | K: `CgMaterialProperties`, `CgMaterialProperty`, `CgMaterialShaderRegistry`. R: `CgMaterialShader` — `programCache` becomes a template cache keyed `(pass, keywords)`; `wireShader*` gone; engine buffers resolve to set-0 entries |
| `gl/material/parse` (14 / ~2,600) | K + R | K: every parser. R: `CgMaterialShaderCompiler` (dialects, `CgPipelineDesc` template output), `CgGlslEmitter` (explicit locations/bindings, dialect-specific buffer declarations), `CgRenderStateParser` (neutral enums), `CgShaderKeywords` (34 GL constants → neutral) |
| `gl/mesh` (8 / 1,620) | K + R | K: `CgMeshBuilder`, `CgObjLoader`, `CgGltfLoader`, `CgMeshLoader`, `CgMeshRegistry`, `CgMeshData`. R: `CgMesh` — two `CgGpuBuffer`s + layout, no VAO; `drawDirect(pass)`, `drawInstanced(pass, n)`, `drawInstanced(pass, instanceSlice)` |
| `gl/pass` (2 / 100) | S | dead; `CgPassDesc` is the real one |
| `gl/render` (11 / 2,729) | K + R + S | K + R: `CgQuadRenderer`, `CgVectorRenderer` (ring slices, set-0 bind groups, `useMaterial(pass, material)`), `CgCurveSplitter`. S: `CgBatchRenderer`, `CgAbstractRenderer`, `CgBufferSource`, `CgLayer`, `CgRenderLayer`, `CgDynamicTextureRenderLayer`, `CgQuadInstanceRenderer`, `CgInstanceRenderer` |
| `gl/shader` (4 / 1,073) | S / M | the core-profile compile/link half (`CgCoreShaderProgram`) moves into the GL backend's `createShader`/`createPipeline`; `CgArbShaderProgram`, `CgAbstractShaderProgram`'s family switch and `CgShaderFactory`'s waterfall are deleted (§0.1) |
| `gl/texture` (9 / 1,738) | K + M + S | K: `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`, `CgTextureManager`, `CgFallbackTextures`, `CgTextureCopy`, `CgTextureAbstract`. M: GL upload/mipmap/readback bodies. S: `CgTextureMutable` |
| `gl/vertex` (7 / 1,109) | M | whole package under the GL backend as its VAO cache |
| `mc/` (1 / 110), `mc/compat` (1 / 56) | K | `CgAssetReloader`, `CgIrisCompat` |
| `mc/shader` (3 / 1,058) | S | raw-shader hot reload |
| `render/pipeline` (3 / 331) | R | the three pass renderers take a `CgRenderPass` and pass defaults; `CgDepthPrepassRenderer`'s alpha-test fallback becomes a pipeline variant |
| `shadergraph/` (22 / 6,311) | K + R | K: everything GL-free (19 files). R: `CgPreviewTarget` (two textures + a descriptor with `resolveTarget`; `resolve()` implicit at pass end), `CgPreviewRenderer` and `CgMainPreviewRenderer` (own pass, own frame slice; `lastDriverError()` kept), `CgPreviewPool` (K, teardown via device) |
| `text/atlas` (2 / 1,296) | K + R | `CgGlyphAtlas`, `CgGlyphAtlasPage` API kept; uploads queued to the encoder (R5) |
| `text/cache` (9 / 2,818) | K | `tickFrame`'s drain is the commit point; budgets unchanged |
| `text/layout` (7 / 2,351), `text/richtext` (4 / 693) | K | |
| `text/msdf` (6 / 1,894) | K | `CgMsdfQualityProbe` → harness |
| `text/render` (7 / 2,529) | K + R + S | K: `CgResolvedGlyphs`, `CgGlyphPlacementCache`, `CgTextCuller`, `CgTextSortKey`. R: `CgTextRenderer` — bind group per atlas, set-2 slice per draw (projection, `pxRange`), pipeline variants for world/UI × MSDF/bitmap, no `restoreStateWith`, no `syncProjection` rewrite, no `ATLAS_TEXTURE_REF`. S: `CgTextRendererRegistry` |
| `util/` (3 / 248), `util/io` (2 / 334) | K | `CgHalfFloat` S (dead) |
| `util/profiling` (4 / 783) | K + R | `CgGpuProfiler` over device timestamps when present |
| JNI bindings (57 / 8,621) | K | untouched |

---

## 8. CrystalGUI's consumer surface

From a call census of `core/src/main/java/com/crystalgui` — every distinct CrystalGraphics method it
calls, and what happens to it:

| Call | Count | Fate |
|---|---|---|
| `CgTextLayout.of`, `CgFontFamily.of`, `CgFontFamilyGroup.ofRegular`, `textRenderer.context()` | 26 | unchanged |
| `CgMaterial.load`, `material.bind/unbind/applyProperties` | 13 | unchanged names; `bind` takes the pass inside `CgUiPaintContext` |
| `CgTextureManager.get/getFallback`, `texture.getWidth/getHeight` | 12 | unchanged |
| `CgFrameBuffer.createOwned`, `CgFrameBufferFormat.builder`, `fbo.getWidth/getHeight/resize/getColorTexture/delete/clearColor` | 45 | unchanged |
| `fbo.bind()` (3), `fbo.getId()` (2), `CgFrameBuffer.blitFrom` (3) | 8 | `bind` → `beginPass`; `getId` gone (`encoder.blit` takes the objects); `blitFrom` kept as a method over the encoder |
| `CgGlState.save` (8), `CgGL.*` (13) | 21 | gone — a pass is the isolation boundary; the scissor goes through `pass.setScissor` |
| `CgRenderPipeline.getInstance()` (8) — for `getFrameData()`/`prepareFrame()` | 8 | gone — the UI writes its own frame slice |
| `CgCapabilities.detect` (1) | 1 | `device.info()` |
| `CgGraphicsLifecycle.*` (5) | 5 | unchanged |
| `CgQuadRenderer`/`CgVectorRenderer` create/useMaterial/quad/curve/triangle/flush/begin/end/delete; `CgTextRenderer.createManualSized/draw/beginBatch/endBatch/delete` | ~25 | unchanged names; `useMaterial` takes the pass |
| `texture.bind(` (1) | 1 | a bind group — inside `CgUiPaintContext.bindTexture` |
| `CgIO.loadSource/openStream` (11) | 11 | unchanged |

So of ~40 methods, four disappear (`bind`, `getId`, `CgGlState.save`, `CgGL.*`), one moves
(`getInstance().getFrameData()`), and the rest are untouched. `CgUiPaintContext`, `CgUiBackdrop`,
`ScissorStack` and `CgUiRenderer` are the only files that change; the table in v1 §6 (kept below as
§8.1) is their line-by-line.

### 8.1 `CgUiPaintContext` and friends

| Today | Under the rules |
|---|---|
| `beginFrame`: `CgGlState.save(8 slots)`, disable alpha test, `msaaFbo.bind()` + clear, ortho into the 3D pipeline's `CgFrameData`, `prepareFrame()`, `renderer.begin()` | `frame = device.beginFrame(); pass = msaaFbo.beginPass(encoder, CLEAR, resolveTo(msaaResolveFbo))`; the ortho is the UI's own set-0 slice; no host state to save |
| `endFrame`: flush, `blitFrom(msaaFbo)`, close scope, second scope, `blitLayer` onto host | pass close resolves; one composite pass onto `device.surface()` with the premultiplied pipeline |
| `beginLayerFbo`/`endLayerFbo` with `LayerFrame` scopes | end the current pass, layer pass (`CLEAR` or `LOAD`), re-begin outer with `LOAD`; a projection slice per pass |
| `withMaterial`/`withLayerOpacity` writing `_LayerOpacity` into the material UBO | `setPipeline(material.pipelineFor(uiDefaults))`, `setBindGroup(2, opacitySlice)` |
| `pushScissor`/`popScissor` flipping to bottom-left | same CPU stack; `pass.setScissor(x, y, w, h)` top-left; the backend flips |
| `bindTexture(texture)` eliding rebinds | a bind group per texture, cached by identity |
| `CgUiBackdrop`: `glGet` the scene FBO, blit, resolve sub-rect, composite `withoutScissor`, two blur passes | `surface().color()` as source; `encoder.blit` for capture and resolve; a pass per blur stage; full-target scissor for composites |
| `disableFixedFunctionAlphaTest()` | gone — the mc1710 adapter's job at `beginPass` |
| `warmUpLayerFbo` | the GL backend's FBO cache, if the driver quirk survives |

---

## 9. Order of work and tests

Each step leaves the build green and the harness rendering.

0. **The harness to LWJGL 3, on the *existing* `CgGLBackend`** — `plan_harness_lwjgl3.md`. Independent
   of everything below, validated by the old scenes producing the same PNGs on LWJGL 3 GL as on
   LWJGL 2, and it is what makes steps 6 and 7 runnable at all. It goes first.
1. **`platform/`**: add the §3 types; `CgPlatformService.device()` beside `gl()`. Nothing else moves.
2. **GL backend by moving**: `CgGlDevice` over today's `CgGLBackend`; `gl/framebuffer`, `gl/vertex`,
   `gl/shader`, `gl/texture` bodies and `CgGlStateManager` drop under it, retargeted to
   `bindPipeline`/`beginPass`. Core still calls the old objects; they delegate.
3. **Scrap** (§2): raw shader API, batch layers, `gl/pass`, `demo/`, dead classes, `CgTextureMutable`,
   `CgTextRendererRegistry`, `CgStreamBuffer` tiers. Harness scenes that used them are rewritten as
   material scenes. This step is what makes step 4 tractable.
4. **Core, in dependency order**: `api/state` (R1) → `api/buffer`/`api/vertex` (unchanged) →
   `gl/buffer` rebuild + `CgFrameRing` (R5) → `gl/material` + compiler (R2, R8, templates) → `gl/render`
   renderers (R4, R5, R9) → `api/render` pipeline + `render/pipeline` (R3, R7) → `text/atlas` +
   `text/render` (R1, R2, R5) → `shadergraph` previews (R3) → `gl/lifecycle` (5.1, 5.5). Ends with an
   **import guard** in `core/build.gradle.kts` refusing `com.crystalgraphics.platform.gl.CgGL` — the
   same `doLast` CrystalGUI uses for `net.minecraft.*`.
5. **CrystalGUI**: §8.1, in table order.
6. **`CgVulkanDevice`** in the LWJGL 3 harness — `plan_cgvulkan.md`; the parity scene green.
7. **`CgBlazeDevice`** for 26.x — blocked on a decompiled 26.2 `com.mojang.blaze3d` tree (§6.1).

**Tests.**

- **A recording device** — `CgDevice` with no GPU, recording every call. The first time submission
  logic is unit-testable headlessly: "an undeclared blend domain resolves to the pass default", "a
  layer switch allocates a projection slice rather than rewriting set 0", "a mid-pass texture write is
  deferred to the next pass boundary", "a scissor reaches the backend top-left", "a closed buffer is
  freed only after its frame's fence", "a material recompile invalidates every cached pipeline".
- **The GL backend against those recordings** in the harness, for the GL calls they become.
- **The parity scene**: one scene, one `.shader`, `--device=gl` and `--device=vulkan`, two PNGs,
  diffed. The only test that sees R6.
- **`ShippedShaderStagePurityTest`** extended to compile every shipped `.shader` in both dialects.
- **The parity scene on a Mac** (Apple GL 4.1): the one environment where every GL feature gate —
  TBO, by-name block wiring, the depth epilogue, the blit fallback, map-per-allocation — is forced at
  once. A Windows/Linux green run says nothing about those paths.

### 9.1 Parity tolerance — what "diffed" means

GL and Vulkan (and MoltenVK, and two GL drivers) are not bit-exact: sample positions, line and point
rasterization, MSAA resolve arithmetic and derivative precision all legitimately differ. A parity
test that demands equality fails forever; one with no threshold sees nothing. The policy:

- Compare **after resolve**, on 8-bit RGBA. A pixel *differs* if any channel's absolute difference
  exceeds **T = 2**. A scene **passes** if differing pixels are ≤ **P = 0.5 %** of the frame.
- An optional **edge mask** — a 1-px dilation of the reference's Sobel edges — excludes the band
  where rasterization rules differ by design. On by default for scenes with thin geometry (wires,
  curves, borders); off for fills and gradients.
- Per-scene overrides in the scene descriptor: text scenes `T = 4` (MSDF edges are analytic and
  sensitive to `fwidth` precision); gradient scenes `T = 1` (the dither is a deterministic hash of
  the fragment coordinate, so both backends must produce the same levels).
- **`gl_FragCoord.y` runs bottom-up on GL and top-down on Vulkan, and the negative-viewport trick
  does not change that** — it flips NDC, not the fragment coordinate. Every shader that reads
  `gl_FragCoord` (the gradient's `hash12` dither, the backdrop's UV derivation, any screen-space
  effect) goes through a compiler-provided `CG_FRAG_COORD`, which the Vulkan dialect emits as
  `vec2(gl_FragCoord.x, cg_Resolution.y - gl_FragCoord.y)`. **R6 gains this line.** Without it the
  gradient scene fails parity with the dither pattern mirrored, which reads as a tolerance problem.
- Determinism: same seeds, same frame index, fonts driven to atlas convergence before capture (the
  existing prewarm loop), the same window size, vsync off.
- The diff writes a heat-map PNG beside the two captures so a failure is a picture, not a number.

### 9.2 No-regression gate

The migration replaces the hottest paths in the engine and must not give back what was measured:
`doBind` from 346.8 ms of `glGet` per frame to 0.00 ms; the desktop at 120 fps with glass live; the
open-frame targets in `project_editor_open_frame_targets`.

- **Baseline first.** Before step 1, capture `CgProfiler` reports for a fixed scene set on the
  current tree and commit them under `gl-debug-harness/harness-output/baseline/`: `text-3d`
  (`worldPassMs`, `textContextMs`, `sceneMs`, `overlayMs`, `onFrameRenderedMs`, swap),
  `cgui-text-stress`, `cgui-desktop` with glass, `cgui-gallery`, the editor open frame.
- **Gate per step.** A step fails if any scope's median exceeds baseline × 1.10, if draw calls per
  frame rise more than 10 %, or if the GL backend's debug counters show **any** `glGet` inside a
  frame (the recording device asserts the same for the device path).
- **Memory.** `CgDeviceStats` allocation totals against today's registry counts, same tolerance.
- The gate runs on Windows and on a Mac (Apple GL 4.1 exercises every feature-gate path — §0.2).

### 9.3 Documentation the migration makes false

This repository treats a guide that lies as a defect, and the invariant tables are the reason the
engine is navigable. Each step updates what it falsifies **in the same commit**, the standing rule
for services. The known list:

- **`CrystalGraphics/AGENTS.md`**: the GL-state section (`CgGlStateManager`, the four rules, the
  `state.verify`/`state.noDedup` flags), the `CgGlState.save`/`CgGlScope` examples, the framebuffer
  section's `bind`/`unbind`, the eleven-step teardown table, the registry table, `cg_env.glsl`'s
  SSBO/TBO paragraph, `#pragma cg_use`'s "never attach from Java", the Platform SPI section
  (`gl()`), the harness authoring rules ("use `CgVertexArray`, `CgStreamBuffer`…"), the
  hardware-range sentence in `CgCapabilities`.
- **Package guides**: `gl/framebuffer`, `gl/state` (already a signpost), `gl/vertex`, `gl/shader`,
  `api/shader`, `mc/shader`, `gl/render`, `gl/buffer`, `gl/buffer/shader`, `api/state`,
  `api/material`, `gl/material`, `text/render`, `platform/.../AGENTS.md`,
  `mc1710/.../platform/AGENTS.md`, the harness's `AGENTS.md`.
- **`CrystalGUI/AGENTS.md`**: *Stack 4: Render* (`CgUiPaintContext`'s frame, `withMaterial`,
  layers), and the invariant rows on the host's alpha test, the scissor stack, the FBO warm-up,
  `mirrored`, the backdrop's sub-rect and scissor, the `withMaterial` flush rule, the depth
  snapshot. `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8.
- Unchanged and worth saying so: `docs/CGUI_MODERN_UI_RENDERING_RESEARCH.md`, the font docs under
  `docs/font/`, everything about text layout and the cascade.

---

## 10. Size, estimated

Flagged as estimates from the line counts in §7, not measurements.

| | Lines |
|---|---|
| `core/` today | 53,365 |
| scrapped outright (§2: raw shader layer ~3,600, batch layers ~1,000, `CgStreamBuffer` tiers ~600, `demo/` 512, `gl/debug` 253, `gl/pass` 100, dead classes ~1,000, `CgTextureMutable`/`CgTextRendererRegistry`/`CgAlphaState`/`CgTextureState`/`CgScissorRect`/`CgBindingPoints` ~450, **the Core/ARB/EXT waterfalls ~900** — §0.1) | ≈ −8,400 |
| moved under the GL backend (`gl/vertex` 1,109, FBO objects ~400, texture GL bodies ~800, core-profile shader compile ~400) | ≈ −2,700 |
| new in `platform/` (device types) and the GL backend glue (pipeline diff, FBO/VAO caches, per-layout binding, frame ring) | ≈ +3,500 |
| compiler dialect work, pipeline cache, bind-group plumbing in materials and renderers | ≈ +1,500 |
| **`core/` after** | **≈ 47,000, all of it on one path** |
| `platform/` GL binding layer after the waterfalls go (`Lwjgl2GLBackend` 996 → one `GL30`/`GL33` call per method; `CgGLContext` 73 → the feature gates) | ≈ 650 + 30 |
| Vulkan backend (harness) | ≈ 6–8k, sized against `blaze3d.vulkan` |
| Blaze3D adapter | ≈ 1–1.5k |

---

## 11. Open decisions

| Decision | Recommendation | Why |
|---|---|---|
| Minimum GL | **Decided: Core 3.3 on every host, 1.7.10 included.** No API-family waterfall anywhere (§0.1) | one code path in the binding layer too |
| ARB shader objects, EXT FBO, every ARB dispatch | **Decided: dropped** (§0.1) | unreachable below the floor |
| SSBO vs TBO for per-instance data | **Decided by macOS: TBO stays**, as a feature gate inside the GL backend (§0.2) | Apple GL is 4.1; SSBO is 4.3. Every Mac on 1.20.x and 26.x-on-OpenGL takes the TBO path |
| 1.7.10 on macOS | **Out of scope** (§0.2) | a legacy 2.1 / GLSL 1.20 context; no GL 3 engine can run there, and CrystalGraphics does not today |
| Push constants | none in v1; set 2 is a dynamic-offset UBO slice | GL 3.3 has no equivalent; one mechanism |
| Explicit `binding=` on GL | emit on 4.2+, wire by name below | no second emitter path |
| Reversed-Z | pass policy, default forward | one flag, not every sheet |
| Frames in flight | 3 on Vulkan, 2 on GL | matches the fence ring's measured behaviour |
| Timestamp queries | optional device feature; `CgGpuProfiler` degrades | already tolerant |
| Shader compile for Vulkan | shaderc via LWJGL 3, explicit `set/binding` from the emitter, not shaderc's auto-binding | the layout must be knowable from the `.shader` without compiling it |
| `CgMeshLoader` | keep (a documented facade), or delete with the note | zero references either way |
| Second device in one process | supported from day one; no statics | the parity test and 26.x's render thread both need it |
| `CgBatchRenderer` for the harness's instancing scenes | scrap; rewrite the two scenes over `CgMesh.drawInstanced(pass, slice)` | it is the second batching model |
