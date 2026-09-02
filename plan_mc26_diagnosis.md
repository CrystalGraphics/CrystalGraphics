# Minecraft 26.x and CrystalGraphics — a diagnosis

**Status**: diagnosis, written 2026-09-02. Nothing here is implemented.
**Companion**: `plan_cgdevice.md` — the core-side model this diagnosis concludes is required.
**Scope**: whether and how CrystalGraphics can target Minecraft 26.x, given that 26.2 ships a Vulkan
backend, and what that means for a codebase whose every seam is shaped like OpenGL.

---

## 0. The conclusion first

1. **26.x is not "Vulkan".** 26.1 is OpenGL. 26.2 adds Vulkan as an experimental, *user-switchable*
   backend behind the `GpuDevice` abstraction Mojang introduced in 1.21.5, with OpenGL kept as the
   fallback. A mod on 26.2+ does not get to choose the API — the player does, at runtime.
2. **CrystalGraphics' platform seam abstracts the wrong axis.** `CgGLBackend` is a 144-method OpenGL
   function table. Its three implementations differ only in *which GL binding* they call. Vulkan has
   no `glEnable`; the seam cannot be implemented over it in either direction.
3. **It is not a rewrite.** Roughly three-quarters of `core/` (text, fonts, the shader graph, material
   parsing, vertex and buffer formats, IO, the JNI bindings) never names GL. What does is ~12–13k lines
   of GL-object code, most of which becomes *thinner* under a device-level seam, plus a deletion list
   of GL-only machinery that has no counterpart and should not be ported.
4. **What is required** is a device-level seam beside the GL one — WebGPU's shape, which Blaze3D 26.2
   converged on independently — with `core/` adopting the Vulkan-shaped rules on *both* backends. That
   is `plan_cgdevice.md`.
5. **Independence has a ceiling in-process.** Under MC-on-Vulkan there is one `VkDevice`, one
   swapchain, one render thread, all Mojang's. "Our own Vulkan" in the game means being a guest on
   their device. The seam is ours; the backend under it in a given loader is a deployment detail.

---

## 1. Verified facts about 26.x

| Version | Date | Rendering |
|---|---|---|
| 26.1 "Tiny Takeover" | 24 Mar 2026 | **OpenGL.** Java 25 required. Chunk geometry storage reworked. `RenderPipeline` extended to 16 vertex buffers / 8 `ColorTargetState`s. `GpuDevice` takes a `Runnable` for startup shaders. GPUs with ≤512 MB dropped. |
| 26.2 "Chaos Cubed" | June 2026 (snapshot-1: 7 Apr 2026) | **Experimental Vulkan** beside OpenGL. Options: Default / Prefer Vulkan / Prefer OpenGL, each falling back to the other. Requires Vulkan 1.2 + dynamic rendering + push descriptors; MoltenVK on macOS; prefers the dedicated GPU. `com.mojang.blaze3d.vulkan` parallels `com.mojang.blaze3d.opengl`. GLSL → SPIR-V through a `GlslCompiler`. **Reversed depth buffer.** Render thread separate from game logic under Vulkan. |

26.2's `GpuDevice` layer, per the NeoForge 26.1→26.2 primer:
`GpuBuffer` (`AutoCloseable`, `map`), `GpuBufferSlice`, `GpuTexture`/`GpuTextureView` (both
`FrameBufferAttachment`), `GpuFormat` (`RGBA8_UNORM`, `D32_FLOAT_S8_UINT`…), `RenderPipeline`
(`withBlend`, `withDepthTestFunction`, `withDepthWrite`, `withCull`, up to 16 vertex formats and 8
colour targets, **`withBindGroupLayout`** — bind groups replaced inline samplers/uniforms in 26.2),
`RenderPass` (`setVertexBuffer(GpuBufferSlice)`, per-pass `RenderArea`, clear colour),
`CommandEncoder`, `GpuSurface` (`blitFromTexture` replaced `RenderTarget#blitToScreen`),
`BlendFactor`/`BlendOp`, `PrimitiveTopology`, `IndexType`, `VertexFormat.Builder.addAttribute`,
`DeviceInfo`/`DeviceLimits`/`DeviceFeatures`, `BackendCreationException`. World geometry goes through
the feature system: `SubmitNode` → `FeatureRenderPhase` → `FeatureRenderer`, with
`TranslucentSubmit.distanceToCameraSq()` for ordering. `Font`'s draw methods are gone
(`Font#prepareText` + `GlyphVisitor`). `GlStateManager` is "accessible but discouraged".

Mojang's guidance: the Vulkan shift "will create more work for graphical modders than a simple
point update", and modders should "stick to the game's internal rendering APIs as much as possible".

Still true from 1.21.x and inherited: shader JSONs are gone (a `RenderPipeline` *is* the material),
uniforms are UBOs (`Projection`, `DynamicTransforms`, `Fog`, `Lighting`, `Globals`), the GUI is
retained (`GuiGraphics` submits `GuiElementRenderState`s that `GuiRenderer` draws later), entities
submit rather than render (1.21.9), `RenderTarget.frameBufferId` no longer exists (1.21.5).

Sources: minecraft.wiki — Java Edition 26.1; minecraft.wiki — Java Edition 26.2-snapshot-1;
docs.neoforged.net/primer/docs/26.2/; tomshardware.com and techspot.com coverage of the Vulkan
announcement; minecraft.net "Another step towards Vibrant Visuals for Java Edition".

---

## 2. How the platform abstraction is set up today

Read on 2026-09-02 from `platform/`, `mc1710/`, `mc1201/` and CrystalGUI's `gl-debug-harness/`.

### 2.1 The seam (`platform/`, 27 files, no LWJGL or MC imports)

- **`CgPlatform.register(CgPlatformService)`** — one call per host. `CgPlatformService` is a closed
  bundle of **nine abstract methods, no defaults**: `gl()`, `capabilities()`, `resources()`,
  `rendering()`, `lifecycle()`, `reload()`, `input()`, `sound()`, `cursor()`. Abstract on purpose so
  a new loader cannot silently inherit a no-op. `register` also runs `CgGL.init` +
  `CgCapabilities.init`, catching `NoClassDefFoundError` so a dedicated server registers with no GL.
- **`CgService<T>`** — the open half: typed slots a consumer declares (`CgService.of(name, absent)`)
  and a loader fills through the same registry (`CgPlatform.provide/get`). Exists so there is never a
  second static registry to forget.
- **`CgGLBackend`** — abstract class, **144 abstract methods**: framebuffers (Core/ARB/EXT waterfall
  inside the backend), shaders, buffers, VAOs, textures, draws, state, clears, queries, samplers,
  mapping, sync, matrix stack, renderbuffers, ARBShaderObjects unified handles. Plus
  `bindFramebufferCompat` (route through the host's own FBO tracker) and vestigial
  `isAvailable()`/`getPriority()` nothing calls.
- **`CgGL`** — the static facade `core/` calls. Every state setter consults **`CgGlStateManager`**
  (CPU-side shadow, redundant-call elimination, scoped save/restore via `CgGlState.save(...)`,
  `hostForeign`) before delegating. Shadow truth is *asserted* by writes and *poured in* at scope
  boundaries from a **`CgGlStateProvider`** — `CgGlGetProvider` (driver reads) as the base.
- **`CgGLContext`** — 21 boolean capability probes → **`CgCapabilities.detect()`**, the immutable
  snapshot (`preferredFboBackend()`, `shaderBufferPath()` SSBO›TBO›NONE, limits, `isCoreProfile`).
- Eight of the nine services are already API-neutral. **`gl()` is the whole problem.**

### 2.2 The three hosts

| | mc1710 | mc1201 (`common/` + forge/neoforge/fabric) | gl-debug-harness |
|---|---|---|---|
| Bundle | `PlatformService1710` — lazy, interface-typed fields so a server never constructs an LWJGL class | `PlatformService1201`, same shape | `PlatformServiceHarness` — eager public fields; scenes swap `soundImpl` / clipboard |
| Backend | `Lwjgl2GLBackend` → LWJGL2 statics; compat bind via `OpenGlHelper.func_153171_g` | `GL1201Backend` → 3-tier `RenderSystem` › `GlStateManager` › raw `GL*C`; alpha test + matrix stack **throw** | `Lwjgl2GLBackend` — near-copy of mc1710's, minus `OpenGlHelper`, plus `ARBCopyImage` fallback |
| Context | `Lwjgl2GLContext` over `GLContext.getCapabilities()` | `GL1201Context` over `GL.getCapabilities()` | as mc1710 |
| State provider | `AngelicaStateProvider` (reflection; installed at preInit if present) | `Blaze3DStateProvider` written, **never installed** | glGet default |
| Registration | `CrystalGraphics.onPreInit` → `PlatformService1710.onPreInit()` | each `@Mod` / `ClientModInitializer` constructor | `FontDebugHarnessMain`, before `HarnessContext.create()` |
| Lifecycle in | Mixins: `MixinMinecraft` (resize, shutdown at `shutdownMinecraftApplet` HEAD), `CgRenderHook` on `EntityRenderer.renderWorld` (opaque/transparent passes, `onFrameRendered`) | Loader events only (`RenderLevelStageEvent`/`WorldRenderEvents`, reload, shutdown). Init lazy inside `onOpaquePass`. **`onResize` and `onFrameRendered` not wired.** All three mixin JSONs are empty | `InteractiveSceneRunner` loop: `onResize`, `onFrameRendered`, `Display.update()` |
| Build | in `settings.gradle.kts` | **commented out — never compiled** | LWJGL **2.9.4** (AGENTS.md's table says LWJGL3; it is not) |

### 2.3 Gaps recorded while reading

- `mc1201`'s hooks read `RenderTarget.frameBufferId`, removed in 1.21.5. Its "route through MC's
  mirror" idea belongs to pre-`GpuDevice` Blaze3D and transfers to 26.x in neither direction.
- The harness cannot see anything that crosses a device seam: LWJGL2 has no Vulkan, no shaderc, no VMA.
- Fixed-function reach still in `core/`: `PoseStack` pushes/loads the GL matrix stack (3 sites),
  `CgAlphaState` toggles `GL_ALPHA_TEST`; CrystalGUI's `CgUiPaintContext` disables the alpha test
  twice per frame. All already throw on 1.20's core profile.

---

## 3. Why both "raw" options fail on 26.2+

| Option | Outcome |
|---|---|
| Raw OpenGL (a `GL1201Backend`-style module over `blaze3d.opengl.GlStateManager`) | Runs **only** for players on Prefer OpenGL. Under Vulkan there is no GL context — the mod draws nothing, silently. Dead the day GL is removed. |
| Raw Vulkan | Needs MC's `VkDevice`, queue, command buffers, swapchain image and its render thread's sync — internals of `blaze3d.vulkan` with no stability promise — and runs only for Vulkan players. |
| Blaze3D `GpuDevice` | Works under both backends, survives GL's removal, is what Mojang asks for. |

You would never write Vulkan *in the game*. The fear is mis-aimed; the cost is real but different.

---

## 4. Triage of CrystalGraphics by stack

`core/`: **249 files / 53,365 lines. ~70 files reference `CgGL`/`CgGlState`/`CgCapabilities`; 179
never do.** JNI bindings: 57 files / 8,621 lines, no GL. `platform/` gl + state: 3,578 lines.

`CgGL.*` call sites by package: api/texture 95 · gl/shader 94 · gl/framebuffer 85 · gl/texture 83 ·
api/state 68 · gl/buffer 64 · gl/material 46 · demo 30 · gl/vertex 19 · gl/mesh 16 · gl/render 9 ·
util/profiling 7 · api/vertex 7 · api/mesh 6 · `PoseStack` 6 · text/atlas 5 · gl/pass 4 ·
shadergraph 7 · api/render 3 · gl/debug 2 · api/framebuffer 2 · text/render 1 · mc/shader 1 ·
api/shader 1.

| Stack | Lines | GL-bound? | Fate on a device seam |
|---|---|---|---|
| `platform/` gl + state | 3,578 | entirely | Stays for GL targets; the device seam goes beside it. Nothing ports. |
| `gl/framebuffer` — `CgFrameBuffer` + the Core/ARB/EXT waterfall | 1,349 | entirely | **`CgFrameBuffer` stays** as core's render-target API (format builder, registry, attachments, `blitFrom`, `clear*`, `resize`); the FBO-object body moves into the GL backend; **the waterfall is deleted** (Core GL 3.3 floor on every host, 1.7.10 included — decided 2026-09-02, `plan_cgdevice.md` §0.1); `bind()` becomes "begin a pass on this target". |
| `gl/texture` + `api/texture` — `CgTexture*`, `CgTextureType` GL triples | 2,426 | 178 calls | **`CgTexture*` stay** as core's texture API over a device handle; `CgTextureType` becomes an API-neutral format; the GL upload bodies move into the backend; only `bind(unit)` goes (bind groups). |
| `gl/vertex` — VAO + registry | 1,109 | yes | Moves into the GL backend whole — already documented as internal, no consumer names a VAO; `CgMesh` and the `api/vertex` formats (1,044, 7 refs) keep their APIs and become the pipeline's vertex layout. |
| `gl/buffer` — `CgStreamBuffer` waterfall, `CgShaderBuffer` SSBO/**TBO**, `CgUniformBuffer` | ~1,300 | yes | Sync-ring becomes the only path; TBO drops out of core (GL-backend-internal fallback at most). `api/buffer` std140/430 maths (630, 0 refs) reused as-is. |
| `gl/shader` — Core + **ARB** program objects | 1,073 | 94 | **Delete** for the device path: SPIR-V/pipeline compile is the backend's. |
| `gl/material` + `api/material` — the `.shader` pipeline | 5,200 | split cleanly | **The asset.** Parsing (~2,600 lines, 0 refs) reused verbatim; `CgMaterialShader` (902, 3 refs), the compiler's program half (12 refs), `CgShaderKeywords` (34) replaced. |
| `api/state` — `CgRenderState` and friends | 803 | 68 | Value objects survive; `apply()`/`clear()` do not — state is pipeline state. |
| `api/render` — command queue, sort, passes | 1,503 | 3 | Concept survives; on 26.x it maps onto the feature system. |
| `gl/render` — quad/vector/batch renderers | 2,729 | 9 direct, built on GL objects | Logic survives; objects underneath swap. CrystalGUI draws through this. |
| `text/*` | 11,581 | **2 of 42 files** | Keep whole — a capability MC lacks and just made harder for itself. |
| `api/font`, `api/text` | 2,614 | none | Keep. |
| `shadergraph/` | 6,311 | 3 preview files | Emits GLSL into `.shader`; the right output once `.shader` → pipeline exists. |
| `gl/lifecycle` | 569 | yes | Registry sweep is GL-only; listener seam survives; device-owned objects close themselves. |
| `mc/shader` — F3+T reload of raw `CgShader` | 1,058 | yes | Delete for the device path. |
| `api/PoseStack` | 3 sites | legacy | Drop the fixed-function calls. |
| `util/*` | 1,365 | 7 (timer queries) | Keep. |

### 4.1 `.shader` is nearly a `RenderPipeline.Builder` already

| `.shader` | Device-level equivalent (Blaze3D 26.2 names) |
|---|---|
| `#type spatial` (registered `CgVertexFormat`) | vertex layout (`VertexFormat.Builder.addAttribute`, max 16) |
| `Pass { RenderState { Blend, DepthTest, DepthWrite, Cull } }` | pipeline state (`withBlend`/`withDepthTestFunction`/`withDepthWrite`/`withCull`) |
| `Properties { … }` + attached SSBO/UBO | a bind-group layout (`withBindGroupLayout`) |
| `#pragma cg_feature X` | shader defines (MC's own `IS_GUI`, `IS_SEE_THROUGH`) |
| `Queue = …` | render phase (solid / translucent / translucent-after-terrain) |
| `struct GBuffer { vec4 a : RT0; }` | `ColorTargetState[]` (≤8) |
| GLSL + `#include` | GLSL → SPIR-V; `#moj_import` is their include |
| `cg_env.glsl` SSBO/TBO dual path | SSBO only; frame/object blocks become bind-group entries |

### 4.2 CrystalGUI's exposure

154 of 1,038 files import `com.crystalgraphics`, the vast majority only the input vocabulary
(`CgKeyCodes` ×53, `CgPlatform` ×48, `CgMouseCodes` ×40, `CgModifiers` ×31). **Direct GL: 13 calls
in 3 files** — `CgUiPaintContext` (5), `CgUiBackdrop` (5), `ScissorStack` (3). Rendering contact:
`CgUiPaintContext` (25 CG types), `CgUiBackdrop`, `FontFamilyCache` + text layout (neutral), the
drawables' `CgTexture2D` handles, and nine `.shader` materials (`gui_quad`, `gui_rounded_rect`,
`gui_curve`, `gui_gradient`, `gui_glass`, `gui_blur`, `gui_downsample`, `gui_layer_blit`,
`gui_color_field`). Two genuinely new problems on 26.x: the retained `GuiRenderer` (an
immediate-mode UI lands under vanilla chrome unless it renders after the flush or into a texture
submitted as an element), and `ScissorStack` → per-pass render area / dynamic scissor.

### 4.3 Vulkan-specific traps (all one-line breakages, none philosophical)

- Reversed-Z on 26.2: every `LEQUAL`, every `cg_DepthBuffer` compare, `CgDepthState.TEST_WRITE`.
- Vulkan GLSL: `gl_InstanceIndex`, `layout(set, binding)`, `#version 450`, no loose uniforms.
- SPIR-V validation is stricter than NVIDIA's GL driver: the stage-purity class of bug becomes a
  hard compile error on every GPU.
- Threading: rendering runs on its own thread under Vulkan; every "on the GL thread" rule re-bases on
  "inside a command encoder on the render thread".
- No `glGet`, no `blitToScreen`, `Minecraft#screen` → `gui.screen()`, `Font` draw methods gone.
- Java 25 required by 26.1 (`core/` is Java 17 bytecode — fine).
- macOS: a self-owned Vulkan backend there is MoltenVK, which needs the portability-enumeration
  instance flag and `VK_KHR_portability_subset`; dynamic rendering and push descriptors are the two
  extensions Mojang requires and ships on it. OpenGL on Mac is 4.1 — no SSBO, no `binding=`, no clip
  control, no persistent mapping.

---

## 5. The design direction

### 5.1 The seam is right; the level is wrong

GL and Vulkan cannot be unified at the GL-call level. They unify one level up, at the vocabulary
every cross-API engine converges on — bgfx, sokol, wgpu/WebGPU, Diligent, and Blaze3D 26.2:

> create buffer / write · create texture / write · create **pipeline** (shader + vertex layout +
> render state, immutable) · begin **pass** (attachments, load/clear, area) · bind pipeline · bind
> **groups** · set vertex/index buffers · draw(instanced) · end pass · submit · fence.

So: `platform/` exposes a **`CgDevice`** with that vocabulary; `CgGLBackend` drops *underneath* the
GL device backend as its binding layer; `core/` never names `CgGL` again. "One path, same files" is
achievable on that seam — at the price that **core adopts the Vulkan-shaped model on both
backends**, because GL can emulate immutable-state-and-explicit-passes trivially and Vulkan cannot
emulate GL's global state at all. That model is `plan_cgdevice.md`.

### 5.2 What independence means in-process

Under MC-on-Vulkan the game owns the one `VkDevice`, queue, swapchain and render thread. The
backends possible under our seam on 26.x, in order of sanity:

1. **`CgDevice` over Blaze3D's `GpuDevice`** — thin (same shape), stable, works under both MC
   backends. Core does not depend on MC; one backend does, which is what a backend is for.
2. **`CgDevice` over MC's raw `VkDevice`/queue** — internals with no promise, coordination with their
   render thread and swapchain sync, Vulkan players only. More work for less safety.
3. **A second self-owned device + external memory** (`VK_KHR_external_memory` /
   `GL_EXT_memory_object`) — maximal independence; a research project on 1.7.10's driver population.

Recommendation: ship (1) for 26.x; keep a **self-owned Vulkan backend for the harness/standalone**,
which is where "CrystalGraphics is not Minecraft's renderer" is true and testable; build the seam so
(2)/(3) can slot in later at no cost.

### 5.3 What to port, not invent

WebGPU's object model as implemented by wgpu — designed to run on GL ES 3.0 *and* Vulkan from one
API, with the y-flip, the no-loose-uniforms rule and the frames-in-flight discipline already decided.
Blaze3D 26.2 arriving at the same names is what makes backend (1) a translation table.

### 5.4 Prerequisites and the acceptance test

- **Harness to LWJGL 3** (GL + Vulkan + shaderc + VMA in one artifact set). Non-negotiable.
- **Core GL 3.3 is the floor on every host, 1.7.10 included** (decided 2026-09-02). Every
  Core/ARB/EXT waterfall — FBO, shader objects, sync, instancing, samplers — is deleted rather than
  moved; the GL backend calls `GL30`/`GL32`/`GL33` directly on LWJGL 2 and 3 alike. Features above 3.3
  (SSBO, `glCopyImageSubData`, clip control, `binding=`, persistent mapping) are single feature gates
  inside the backend.
- **macOS caps OpenGL at 4.1 core**, so every one of those gates is the Mac path and the floor cannot
  rise; **1.7.10 on macOS is a 2.1 legacy context and out of scope** for any GL 3 engine (it is today
  too, and it is Angelica's stated limit); 26.x on macOS runs MC's Vulkan through MoltenVK or Apple's
  4.1 GL and the Blaze3D adapter is indifferent to which. `plan_cgdevice.md` §0.2.
- **`mc1201` folds into the GL family** — GL with a state provider; 26.x is the device family.
- **Acceptance**: the `cgui-engine-parity` idea applied to GPU backends — one scene, one `.shader`,
  GL and Vulkan, two PNGs, diffed. Every item in §4.3 and in `plan_cgdevice.md` is a silent-failure
  class; a diff is the only thing that sees it.

### 5.5 Order of work

1. Define `CgDevice` in `platform/` (port the WebGPU shape). Nothing else moves.
2. Build the GL backend **by moving**: today's `gl/*` bodies drop under it; `CgGLBackend` stays as
   its LWJGL2/LWJGL3 binding layer.
3. Purge `core/`: no `CgGL`, no loose uniforms, state on pipelines, explicit passes, ring-buffered
   uploads, one coordinate convention. ~70 files. `plan_cgdevice.md` is the spec.
4. Dual-dialect GLSL emission + shaderc in the one compiler.
5. Self-owned Vulkan backend in the LWJGL3 harness; parity scene green.
6. Blaze3D adapter for 26.x.
