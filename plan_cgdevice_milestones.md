# `CgDevice` — implementation milestones

**Status**: schedule, 2026-09-02. Nothing here is implemented.
**Specs this schedules**: `plan_cgdevice.md` (the seam, the rules, the matrix), `plan_harness_lwjgl3.md`
(D0), `plan_cgvulkan.md` (D5), `plan_mc26_diagnosis.md` (why, and D6's ceiling).
**Branch**: everything below happens on one branch name across three repositories (§5) and merges to
`master` at the end of D4.

---

## 0. How to read this

**Rip in place.** This is the opposite of `plan_ui_rewrite.md`'s M6, where a second engine was built
beside the first and the old one kept running until the port was whole. Here, when an API goes, it is
deleted, and every caller is rewritten in the same milestone; there is no legacy overload, no
compatibility shim, no "old path still works meanwhile". The branch is the safety.

**A milestone is the unit that may be red inside and must be green at its exit.** Green means: every
module compiles, the scoped tests named in the milestone pass, and the harness renders the scene set
the milestone names within the tolerance it names. Sub-steps inside a milestone are ordered by
dependency for the author's benefit; their "done when" checks are code-level (a package no longer
names a type) and do not require the build to compile.

**The instruments** (§1) are set up before anything is ripped: they are the only way a red-inside
milestone can be steered.

Sizes are lines, estimated from `plan_cgdevice.md` §7 and §10, and are estimates.

---

## 1. Instruments

| Instrument | Set up in | What it answers |
|---|---|---|
| **PNG baselines** — every managed harness scene captured on the current tree, committed under `harness-output/baseline/lwjgl2/` (D0's own gate) and re-captured under `baseline/lwjgl3-gl/` at D0's exit (the reference for everything after) | D0 | "does it still draw the same picture" |
| **Profiler baselines** — `CgProfiler` reports for `text-3d`, `cgui-text-stress`, `cgui-desktop` (glass on), `cgui-gallery`, the editor open frame; committed beside the PNGs | D0 exit | "did it get slower" (§9.2 of the device plan: median × 1.10, draw calls + 10 %, zero `glGet` per frame) |
| **Parity tolerance** (§9.1) — the comparator, its edge mask, per-scene overrides, the heat-map output | D1 | "same picture" for changes that legitimately move a pixel |
| **The recording device** — `CgRecordingDevice implements CgDevice`, no GPU, every call appended to a list with its arguments | D1 | submission structure, headlessly: pass nesting, bind-group contents, ring allocation, deferred deletion, pipeline completeness — before any scene renders |
| **Guards** — `core/build.gradle.kts` `doLast` refusing `com.crystalgraphics.platform.gl.{CgGL,CgGLBackend,CgGLContext,CgCapabilities}` and `platform.gl.state.*` in `core/`; a `headlessTest` that loads every new `platform` type with no LWJGL on the classpath | D3 exit | "core is on one path" and "the seam is headless-safe" |
| **Scoped test lists** — each milestone names the JUnit classes it runs; the whole suite is never run (`:CrystalGraphics:core:test` hangs; CrystalGUI's `ui.*` wildcard never reports) | every milestone | — |

---

## 2. Dependency graph

```
D0 harness → LWJGL 3 (GL, byte-identical)
 │
 ├─ D1 seam declared · GL backend · safe scrap        (core untouched; old scenes still green)
 │    │
 │    └─ D2 buffers, meshes, the frame ring            (API-compatible; old scenes still green)
 │         │
 │         └─ D3 THE FLIP: materials, state, textures, framebuffers, passes, renderers,
 │              pipeline, text, previews, lifecycle, CrystalGUI, harness scenes, guards
 │              (red inside; green at exit: every scene within tolerance of the D0 baseline)
 │              │
 │              └─ D4 hosts: mc1710 on device(); mc1201 decided; docs sweep; MERGE
 │                   │
 │                   ├─ D5 Vulkan backend · Vulkan dialect · parity (Windows + Mac)
 │                   │
 │                   └─ D6 Blaze3D adapter for 26.x   (entry: 26.2 sources fetched, §6.1 verified)
```

D5 and D6 are independent of each other. D6 cannot start before its entry criterion whatever else
is done.

---

## 3. Milestones

### D0 — The harness to LWJGL 3

**Spec**: `plan_harness_lwjgl3.md`. **Touches core**: no.

**Goal**: the only place anything can run gains Vulkan, shaderc, VMA, arm64 and a macOS core
context, with the engine's GL path provably unchanged.

**Contents, in order**
1. Capture the LWJGL 2 PNG baselines for every managed scene on the machine that will compare them.
2. Build file: LWJGL 3 BOM, `glfw`/`opengl`/`vulkan`/`shaderc`/`vma`, per-platform natives,
   `-XstartOnFirstThread` on macOS, `--device=gl|vulkan` argument (`gl` only for now).
3. The bundle: `Lwjgl3GLBackend` (over `GL33C`, still implementing today's `CgGLBackend` — the
   waterfall methods throw `UnsupportedOperationException` until D1 deletes them), `Lwjgl3GLContext`,
   GLFW `InputAdapter` with the GLFW→LWJGL2 key table, `GlfwCursorService` from `CursorService1201`.
4. `HarnessContext`, `InteractiveSceneRunner`, `ResizeHandler`, `InputPauseHandler`, `Camera3D` on
   GLFW; HiDPI framebuffer size.
5. The fixed-function audit of `FloorRenderer`, `WorldAxisRenderer`, `HUDRenderer`,
   `PauseScreenRenderer`, `QuadRenderer`, `VertexBinding` and the 14 raw-GL scenes: anything using
   `glBegin` or the matrix stack is rewritten over `CgQuadRenderer`/`CgVectorRenderer`/a material.
6. Delete the harness-local `com/crystalgraphics/CrystalGraphicsVersion.java`.
7. Re-capture the baselines on LWJGL 3 GL; capture the profiler baselines.

**Deletes**: LWJGL 2 and the `extractLwjglNatives` task from the harness.

**Exit**: every managed scene's PNG **byte-identical** to the LWJGL 2 capture on the same GPU;
`cgui-gallery`, `cgui-desktop`, `cgui-textfield` (clipboard), `cgui-button` (sound counter) drive
through GLFW input; the harness runs on a Mac (Apple GL 4.1) — new, no baseline.

**Proves**: the binding layer is not where the engine's behaviour lives.

**Docs**: the harness `AGENTS.md`; the module table in `CrystalGraphics/AGENTS.md` (LWJGL3, finally
true); `CrystalGUI/AGENTS.md`'s harness section.

**Hazards**: a HiDPI Mac reads the window size where the framebuffer size is meant; `glReadPixels`
row order in `ScreenshotUtil`; a keycode the table maps wrong shows up as one dead key in one widget.

**Size**: ≈ 2–3k changed.

---

### D1 — The seam declared, the GL backend built, the safe scrap

**Spec**: device plan §0.1, §2 (the rows marked *dead* or *no dependents*), §3, §3.1, §5.11.
**Touches core**: deletions only — nothing core calls changes.

**Goal**: `CgDevice` exists, `CgGlDevice` implements it end to end, and a device-only scene renders,
while every old scene still renders because core has not moved.

**Contents, in order**
1. `platform/`: every type in §3 and §3.1 — `CgDevice`, the descriptors, the enums, `CgFormat`,
   `CgDeviceInfo`, `CgCommandEncoder`, `CgRenderPass`, `CgFrame`, `CgSurface`. Pure declarations.
   `CgPlatformService.device()` beside `gl()` (both exist until D4).
2. **Delete the waterfalls** (§0.1): the ARB/EXT methods of `CgGLBackend` and their implementations
   in `Lwjgl2GLBackend`, `Lwjgl3GLBackend`, `GL1201Backend`; `CgArbShaderProgram`;
   `CgCapabilities.FramebufferPath`/`preferredFboBackend`; `CgGlStateShadow.FboFamily.EXT`;
   `optimalDepthBlitMask`; `bindFramebufferCompat`; the extension probes of `CgGLContext` bar the
   feature gates. Core still compiles — it never named any of these directly.
3. `CgGlDevice` under `platform/gl/backend/`: over `CgGLBackend` + `CgGlStateManager`. Pipeline
   state diff on bind; the FBO cache, VAO cache, sampler cache and per-layout unit allocation with
   generation-keyed invalidation (§5.11); the frame ring over `glMapBufferRange(UNSYNCHRONIZED |
   INVALIDATE_RANGE)` with a fence per frame; the deletion queue; feature gates from the context
   (`storageBuffers`, `texelBuffers`, `copyImage`, `clipControl`, `explicitBinding`,
   `persistentMapping`, `timestamps`); host hand-back at `endPass`; the depth-range fix-up decision
   (clip control or epilogue flag for the compiler); `CgSurface` from a host-supplied FBO id.
4. `CgRecordingDevice` and its first tests: descriptor validation (a pipeline with an incomplete
   state is refused; ring alignment; a closed object is freed after its frame).
5. The parity comparator (§9.1) as a harness utility, with the heat-map output.
6. A **device-only harness scene** (`device-smoke`): clear → triangle from a ring slice → textured
   quad → a nested layer pass with MSAA and resolve → readback. It calls `CgGlDevice` directly and
   nothing in core.
7. **The safe scrap** — everything nothing depends on: `gl/render`'s layer system (`CgBufferSource`,
   `CgLayer`, `CgRenderLayer`, `CgDynamicTextureRenderLayer`, `CgAbstractRenderer`,
   `CgQuadInstanceRenderer`, `CgBatchRenderer`; the two harness scenes on them go too and come back
   in D3 over `CgMesh.drawInstanced`); `gl/pass`; `demo/` (→ harness scenes + mc1710's integration
   package; `CgGraphicsLifecycle.onOpaquePass` calls `CgRenderPipeline` directly);
   `CgSystemUniformRegistry`, `CgUniformInjector`, `CgUniformName`; `CgHalfFloat`, `CgTextureState`,
   `CgScissorRect`, `CgGlyphMetrics`; `CgMsdfQualityProbe` → harness.

**Deletes**: see 2 and 7. ≈ −2,600 lines from core, ≈ −900 from the backends.

**Exit**: `device-smoke` renders on `CgGlDevice`, on Windows and on a Mac; every old scene's PNG
byte-identical to the D0 baseline (core unchanged); recording-device tests green;
`CgGlStateManagerTest` green; `:core:compileJava` green.

**Proves**: a `CgDevice` can be implemented over the GL function table at all, with the state manager
retargeted — the one claim the whole design rests on — before any of core is at stake.

**Docs**: `platform/.../AGENTS.md` (the new seam); the GL-state section of `CrystalGraphics/AGENTS.md`
(waterfalls gone, `CgGlDevice` is the caller); `gl/render/AGENTS.md`, `gl/pass` gone.

**Hazards**: the frame ring's `UNSYNCHRONIZED` map on drivers that ignore the flag (Intel of a
certain age) — the fence is what makes it correct regardless; the FBO cache's generation bump
missed on `resize`, which draws into a dead FBO and shows nothing.

**Size**: ≈ +1,200 `platform/` types, ≈ +3,000 `CgGlDevice`, ≈ +600 recording device + comparator,
≈ +400 smoke scene.

---

### D2 — Buffers, meshes, the frame ring in core

**Spec**: device plan R5, R9, §5.4.1, §7 rows `gl/buffer`, `gl/buffer/shader`, `gl/mesh`, `gl/vertex`.
**Touches core**: yes, API-compatibly.

**Goal**: every buffer core owns is a device buffer and every per-frame write is a ring allocation,
with the object APIs unchanged so no consumer moves.

**Contents, in order**
1. `CgShaderBuffer` and `CgUniformBuffer` rebuilt over `CgGpuBuffer` + `CgBufferWriter`; `writer()`,
   `beginWrite`/`endRecord`/`endWrite`, `upload`, `uploadRaw`, `bind` keep their signatures; `bind`
   binds the current slice's range through the backend. Persistent-vs-ring per §5.4.1: a
   `CgUniformBuffer` created for material properties is persistent (staged copy on `upload`); the
   renderers' instance buffers and the frame block are ring.
2. `CgQuadRenderer`/`CgVectorRenderer` `flush`: allocate a ring slice, write, bind, draw. The
   static class-wide `GPU_BUFFER` becomes a per-frame slice; the doc line *"whoever calls flush()
   last owns the buffer's contents"* is deleted with the behaviour.
3. `CgMesh` over two `CgGpuBuffer`s and a `CgVertexLayout`; `drawDirect`/`drawInstanced` keep their
   signatures and go through the backend's VAO cache. `CgInstanceRenderer`'s two static draw helpers
   fold into `CgMesh`; `CgInstanceVertexBuffer` and `gl/vertex` move under the GL backend whole.
4. `CgQuadIndexBuffer` as a persistent static buffer.
5. Delete `CgStreamBuffer`, `MapAndSyncStreamBuffer`, `MapAndOrphanStreamBuffer`, the subdata tier;
   `CgTextureBuffer` becomes the GL backend's TBO implementation of `CgBufferUsage.TEXEL`.
6. Recording tests: "fifty flushes in one frame allocate fifty slices and wait on nothing";
   "a material upload is a staged copy before the first pass"; "a mesh closed mid-frame is freed
   after its fence".

**Deletes**: `gl/buffer`'s tiers (≈ −600), `gl/vertex` from core (≈ −1,100, moved), `CgInstanceRenderer`.

**Exit**: every scene's PNG within §9.1 tolerance of the D0 baseline (should be identical — the same
draws, differently fed); `cgui-text-stress` and `text-3d` profiles within the gate and
`quadRenderer.flush` showing zero fence waits in `CgDeviceStats`; recording tests green.

**Proves**: the ring is the right granularity — the one R5 claim a harness can measure.

**Docs**: `gl/buffer/AGENTS.md`, `gl/buffer/shader/AGENTS.md`, `gl/vertex/AGENTS.md` (moved),
`gl/mesh/AGENTS.md`; the `cg_env.glsl` paragraph in `CrystalGraphics/AGENTS.md`.

**Hazards**: a ring slice held across a frame boundary (a `CgBufferWriter` kept in a field) reads
garbage next frame — the recording device catches a slice used after its frame; alignment on
Apple GL for texel buffers.

**Size**: ≈ +900, ≈ −1,700.

---

### D3 — The flip

**Spec**: device plan R1–R4, R6–R8, R10, §2 (every remaining row), §5, §7, §8, §9.3.
**Touches core**: everything that remains; CrystalGUI's contact files; the harness's scenes.

**Goal**: `core/` renders through `CgDevice` and nothing else. Materials are pipelines, state is on
them, textures are bind-group entries, framebuffers begin passes, the UI and text draw inside passes,
the raw shader API is gone, and the guard makes it stay gone.

**Red inside, by design.** The sub-steps are in dependency order and each has a code-level "done
when"; the build does not compile between 3.1 and 3.11. To get one intermediate green, a
`-PcgOnly` Gradle property in the harness that drops `project(":core")` and registers only the
CrystalGraphics scenes lets 3.1–3.10 be verified on the 3D and text scenes before CrystalGUI is
touched in 3.11. Optional, and recommended.

**Contents, in order**

| # | Sub-step | Done when |
|---|---|---|
| 3.1 | `api/state`: neutral enums replace `CgGL.GL_*`; `CgRenderState.overlaidWith(defaults)` + `isComplete()`; delete `apply()`/`clear()`, `CgAlphaState` | `api/state` names no `CgGL` |
| 3.2 | The compiler: `CgRenderStateParser` and `CgShaderKeywords` map to the neutral enums; `CgGlslEmitter` emits explicit `layout(location)` on inputs/varyings/outputs and `layout(set, binding)` (GL dialect: `binding=` behind the gate, by-name below), the TBO getter behind the gate, the depth epilogue behind the gate, `CG_FRAG_COORD`; `CgMaterialShaderCompiler` outputs a `CgPipelineTemplate` (two `CgShaderSource`s, a set-1 `CgBindGroupLayoutDesc`, the partial state, the vertex layout); `CgShaderPreprocessor` moves to `gl/material/parse` | `gl/material/parse` names no `CgGL`; `ShippedShaderStagePurityTest` compiles every shipped `.shader` through the new emitter |
| 3.3 | Materials: `CgMaterialShader` caches templates by `(pass, keywords)`; `CgMaterial` resolves pipelines through the device cache keyed per R1 and owns its set-1 bind group; `bind(CgRenderPass)` replaces `bind()`; `bindForPass(pass, variant)`; `drawChain(pass, …)`; `fromStages(vert, frag, format)`; `CgEngineBufferRegistry` tokens become set-0 entries; `onShaderRecompiled` bumps the generation and rebuilds the group | `api/material`, `gl/material` name no `CgGL`; recording tests: pass defaults overlay, an undeclared blend domain resolves to the default, `applyProperties` inside a pass redirects set 1 to a ring copy |
| 3.4 | **Delete the raw shader API**: `api/shader` bar the preprocessor, `gl/shader`, `mc/shader`, `CgDebugBlit` (→ `blit.shader` material), `CgBindingPoints` | the packages are gone |
| 3.5 | Textures: `CgTexture2D/2DArray/3D/Cubemap` over `CgGpuTexture` + sampler; `CgTextureType` → `CgFormat` mapping table in the backend; upload/mipmap/readback bodies into the backend; `CgTextureCopy` over `encoder.copyTexture`/`blit`; delete `bind(unit)`, `activeUnit()`, `CgTextureMutable` | `gl/texture`, `api/texture` name no `CgGL` |
| 3.6 | Framebuffers: `CgFrameBuffer` = attachment set + `beginPass(encoder, loadOps)`; `blitFrom`/`clear*` over the encoder; renderbuffer slots → `TRANSIENT` textures; delete `bind`/`unbind`, `bindDraw`/`bindRead`, `getId`, the dispatch; `CgFrameBufferRegistry` bumps generations on resize | `gl/framebuffer` names no `CgGL` |
| 3.7 | Renderers: `CgQuadRenderer`/`CgVectorRenderer` `useMaterial(pass, material)`, set-0 groups for the instance buffers; `CgMesh.drawInstanced(pass, n)` / `(pass, instanceSlice)` | `gl/render` names no `CgGL` |
| 3.8 | `api/render` + `render/pipeline`: `CgRenderPipeline` per device (constructed by the host with a device, no singleton); opaque and transparent passes over `encoder.beginPass(surface)` with pass defaults; the depth snapshot via `copyTexture`; frame block as a per-pass slice; `CgPreDrawHook` takes `CgFrame`; the prepass's alpha-test fallback becomes a variant; no `CgGlScope` | `api/render`, `render/pipeline` name no `CgGL`/`CgGlState` |
| 3.9 | Text: `CgTextRenderer` — one bind group per glyph atlas, a set-2 slice per draw (projection, `pxRange`), pipeline variants for world/UI × MSDF/bitmap through `CgTextRenderContext`; delete `TEXT_DATA_UBO`'s in-place rewrite, `ATLAS_TEXTURE_REF`, `restoreStateWith`, `CgTextRendererRegistry`; `CgGlyphAtlas`/`Page` pending uploads and `CgFontRegistry.tickFrame`'s drain over `encoder.writeTexture` (§5.10) | `text/*` names no `CgGL`; recording tests: a glyph seen mid-pass is uploaded at the next frame's start and skipped this frame |
| 3.10 | Previews and lifecycle: `CgPreviewTarget` (resolve attachment), `CgPreviewRenderer`/`CgMainPreviewRenderer` own pass + slice; `CgGraphicsLifecycle` = device create at `initContext`, `beginFrame` at `tickFrame`, `device.close()` at `destroyContext`, warm-up of known pipelines; `CgGpuProfiler` over timestamps; `PoseStack`'s three GL calls deleted; `CgCapabilities`/`CgGLContext` reduced to the backend's own | `shadergraph`, `gl/lifecycle`, `api`, `util` name no `CgGL` — **core names no `CgGL` anywhere** |
| 3.11 | CrystalGUI: `CgUiPaintContext` (frame = pass on the MSAA target with resolve; layers = nested passes with `LOAD`; `withMaterial` = `setPipeline` + set-2 opacity slice; own frame slice, no `CgRenderPipeline.getInstance()`; scissor top-left; no alpha-test disable), `CgUiBackdrop` (`surface().color()`, `encoder.blit`, a pass per blur stage), `ScissorStack` (CPU stack + `pass.setScissor`), `CgUiRenderer`, `WindowSnapshot`, `CgUiLifecycle` (per device), the shader-graph app previews; `CG_FRAG_COORD` in `gui_gradient`, `gui_glass`, `gui_blur`, `gui_downsample`; `CgTextureManager.get` and the drawables unchanged | CrystalGUI `core/` names no `CgGL`, `CgGlState`, `CgCapabilities`; its `:core:test` scoped classes green |
| 3.12 | Harness: the raw-shader/batch scenes rewritten as material scenes; `ScreenshotUtil` over `encoder.readback`; `GlStateResetHelper`, `GlStateDumper`, `FboInspector` deleted or over the device; the parity scene skeleton (GL vs the D0 baseline for now) | no harness scene names `CgShaderFactory`, `CgBatchRenderer`, `CgDebugBlit`, `CgGL` |
| 3.13 | Guards on: the `core/build.gradle.kts` import guard; the `headlessTest` that loads every `platform` type without LWJGL | both green |

**Deletes**: `api/shader` (bar 2 files), `gl/shader`, `mc/shader`, `gl/debug`, `CgAlphaState`,
`CgBindingPoints`, `CgTextureMutable`, `CgTextRendererRegistry`, `CgGlScope`/`CgGlState` from core's
vocabulary, `PoseStack`'s GL calls, `CgRenderPipeline`'s singleton. ≈ −4,500 lines.

**Exit**: **every scene** — CrystalGraphics' and all 17+ `cgui-*` — renders within §9.1 tolerance
of the D0 baseline (identical where nothing moved; the edge mask on for wires and curves);
`cgui-gradient-probe` and `cgui-snapshot-probe` re-run their documented readback assertions;
interactive scenes drive; the profiler gate passes on every baselined scene with zero `glGet` per
frame; recording tests green; guards green; `ShippedShaderStagePurityTest`, `CgGlStateManagerTest`,
and CrystalGUI's scoped render tests green; runs on a Mac with every feature gate off.

**Proves**: one path. The `.shader` format survived unchanged, the consumer surface survived bar four
methods (§8), and the engine is smaller than it started.

**Docs**: the whole of §9.3 — `CrystalGraphics/AGENTS.md`'s render, state, framebuffer, material,
teardown, registry and platform sections; every package guide named there; `CrystalGUI/AGENTS.md`'s
*Stack 4* and the listed invariant rows; `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8. Written as each
sub-step lands, not at the end.

**Hazards**: the size — this is the milestone that must not be allowed to sprawl, which is what the
per-sub-step "done when" and the `-PcgOnly` intermediate green are for; `gl_FragCoord.y` (§9.1) —
a dither mirrored reads as a tolerance problem; a `CgFrameBuffer` clear that was an explicit `glClear`
mid-paint and is now a `LOAD` pass — find them by the recording device, not by eye; the text
renderer's world/UI depth variant selected wrongly draws UI text depth-tested against nothing and
disappears.

**Size**: ≈ +4,000 (materials, bind groups, passes, text, UI), ≈ −4,500. Net core ≈ 47k.

---

### D4 — Hosts, decisions, docs, merge

**Spec**: device plan §5.8, §6; diagnosis §2.2, §5.4. **Touches core**: no.

**Goal**: the shipping target runs on the device, the dead module is decided, the documentation is
true, and the branch merges.

**Contents, in order**
1. mc1710: `PlatformService1710.device()` returns `CgGlDevice(new Lwjgl2GLBackend(), …)`;
   `gl()`/`capabilities()` removed from `CgPlatformService`; `CgRenderHook` supplies the surface (MC's
   FBO id and size — what it passes as `sourceFboId` today); the adapter neutralises the
   fixed-function alpha test at `beginPass` and hands MC its state back at `endPass`;
   `CrystalGraphicsFontDemo` over the moved demo. `Lwjgl2GLBackend` at ≈ 650 lines.
2. Runs: `runClient` (the editor, `CgUiScreen`, a pinned window, the shader graph compiling and
   previewing), `runObfClient` (the only production-shaped run), `serverSmoke` (the device is never
   constructed on a server — `device()` is lazy and the platform types load headlessly).
3. **mc1201 decided**: either delete `mc1201/` (it has never compiled, its hooks are dead, and 1.20.x
   is not a shipping target) or reduce it to `GL1201Backend` on `device()` behind the same
   `PlatformService1201` shape and leave it commented out. Recommendation: delete, with the
   `Blaze3DStateProvider` findings folded into a note for D6.
4. The documentation sweep: every remaining item in §9.3 that D3's sub-steps did not close;
   `CrystalGraphics/AGENTS.md`'s *Global Coding Rules* ("never raw GL" becomes "never `CgGL`; the
   device is the only GPU seam"); the invariant tables' rows marked obsolete rather than deleted,
   the way the UI rewrite marks `(M5: no counterpart.)`.
5. Merge to `master` in all three repositories (§5).

**Exit**: `runClient`, `runObfClient` and `serverSmoke` green; the harness gate green on Windows and
Mac; `git grep CgGL` in `core/` and CrystalGUI `core/` returns nothing; AGENTS.md's build table and
module table describe the tree.

**Proves**: production, not the harness.

**Size**: ≈ +400, ≈ −350 (mc1710); mc1201 as decided.

---

### D5 — The Vulkan backend

**Spec**: `plan_cgvulkan.md`; device plan R8 (`VULKAN450`), §9.1. **Touches core**: the compiler's
Vulkan dialect only.

**Contents**: the bring-up order of `plan_cgvulkan.md` §8 — clear, triangle, textured quad,
`CgQuadRenderer`, text, a nested MSAA layer pass, `cgui-gallery`, the parity scene — with the
`VULKAN450` emitter (`#version 450`, `set/binding`, `gl_InstanceIndex`, `CG_FRAG_COORD`'s flip) and
shaderc added to `ShippedShaderStagePurityTest` first, so every shipped `.shader` validates as
SPIR-V before a GPU sees one. Validation layers on for every harness run of this backend.

**Exit**: all eight bring-up scenes; the **parity scene within §9.1 tolerance on Windows and on a
Mac (MoltenVK)**; validation clean; `CgDeviceStats` barrier counts not scaling with draw count;
the persisted pipeline cache measurably removing second-run hitches.

**Proves**: the whole claim — one core, two APIs, no `if (vulkan)`.

**Docs**: `plan_cgvulkan.md` becomes `docs/CGDEVICE_VULKAN.md`; the harness guide's `--device`.

**Size**: ≈ 5–6.5k new.

---

### D6 — The Blaze3D adapter for 26.x

**Spec**: device plan §6.1; diagnosis §4.2, §5.2. **Entry criterion** (hard): a decompiled 26.2
`com.mojang.blaze3d` tree checked in under `research_repos/mc26_sources/`, and every row of §6.1
answered from it — above all whether `precompilePipeline`'s source provider survived, which decides
whether the shader graph runs there at all.

**Contents** (designed after entry, not before): `CgBlazeDevice` as the translation table
(`CgPipelineDesc` → `RenderPipeline.Builder`, `CgRenderPass` → `RenderPass`, buffers, textures, bind
groups → `BindGroupLayout`, `CgSurface` → the main `RenderTarget`'s views, reversed-Z as the pass
policy, GLSL handed to their `GlslCompiler`); an `mc26` module with the loader bootstrap, the
feature-renderer hook for world geometry, the retained-`GuiRenderer` integration for `CgUiScreen`,
the render-thread question answered; the §6.1 gaps reported through `CgDeviceInfo.features`.

**Exit**: the editor and a pinned window on 26.x under both Prefer OpenGL and Prefer Vulkan, on
Windows and on a Mac; the shader graph compiling a `.shader` into a live `RenderPipeline`.

**Size**: ≈ 1–1.5k adapter + the module; unknowable until entry.

---

## 4. Deletion ledger

| What | Milestone |
|---|---|
| LWJGL 2 in the harness; `extractLwjglNatives`; the harness's `CrystalGraphicsVersion` copy | D0 |
| Core/ARB/EXT dispatch in every backend; `CgArbShaderProgram`; `FramebufferPath`; `FboFamily.EXT`; `optimalDepthBlitMask`; `bindFramebufferCompat`; `CgGLContext`'s extension probes | D1 |
| `CgBufferSource`, `CgLayer`, `CgRenderLayer`, `CgDynamicTextureRenderLayer`, `CgAbstractRenderer`, `CgQuadInstanceRenderer`, `CgBatchRenderer`; `gl/pass`; `demo/`; `CgSystemUniformRegistry`, `CgUniformInjector`, `CgUniformName`; `CgHalfFloat`, `CgTextureState`, `CgScissorRect`, `CgGlyphMetrics`; `CgMsdfQualityProbe` (moved) | D1 |
| `CgStreamBuffer` and its tiers; `CgInstanceRenderer`; `gl/vertex` from core; `CgTextureBuffer` as a core type | D2 |
| `api/shader` (bar `CgShaderPreprocessor`, `CgPreprocessorException`); `gl/shader`; `mc/shader`; `CgDebugBlit`; `CgBindingPoints`; `CgAlphaState`; `CgRenderState.apply/clear`; `CgTexture.bind/activeUnit`; `CgTextureMutable`; `CgTextRendererRegistry`; `CgFrameBuffer.bind/unbind/getId`; `CgGlScope`/`CgGlState` from core; `PoseStack`'s GL calls; the `CgRenderPipeline` singleton; `CgUiPaintContext`'s alpha-test disable and its use of `CgRenderPipeline.getInstance()` | D3 |
| `CgPlatformService.gl()` and `capabilities()`; mc1201 (as decided) | D4 |

---

## 5. Branch and repository mechanics

Three repositories are in play and one branch name — `cgdevice` — is used in all of them:

- **CrystalGraphics** (a submodule of CrystalGUI): D1–D5 land here.
- **gl-debug-harness** (a submodule of CrystalGUI, branch `crystalgui`): D0, D1's smoke scene, D3.12,
  D5's scenes land here; branch `cgdevice` from `crystalgui`.
- **CrystalGUI**: the two submodule pointer bumps per milestone, and D3.11's contact files.

CrystalGUI's `core/` compiles against CrystalGraphics through the composite build, so a CrystalGraphics
API removal breaks CrystalGUI's compile until 3.11 lands — which is the point of the branch and of
`-PcgOnly`. Submodule pointers are bumped at each milestone's exit, never mid-milestone. `master`
in all three stays on the LWJGL 2 harness and the GL-call engine until D4's merge, so the shipping
1.7.10 build is never on the branch.

---

## 6. Risk register

| Risk | Where | Mitigation |
|---|---|---|
| D3 sprawls | D3 | the per-sub-step "done when"; `-PcgOnly` for an intermediate green; the recording device for headless progress; no new features accepted on the branch |
| A milestone's baseline comparison passes for the wrong reason (an empty frame equals an empty frame) | all | every comparison also asserts a non-trivial pixel count differs from the clear colour, and the smoke scene's readback checks a known pixel |
| No Mac available for the gate | D0, D3, D5 | the gate is documented as "on a Mac"; without one, every GL feature-gate path is *untested*, and that is recorded in the milestone's exit note rather than glossed |
| MoltenVK divergence | D5 | `plan_cgvulkan.md` §7; the Mac parity run |
| shaderc rejects GLSL the GL driver accepted | D5 (surfaces in D3's purity test if the Vulkan dialect is emitted early) | emit `VULKAN450` from D3.2 and compile it in the purity test then, even though nothing runs it until D5 |
| `precompilePipeline`'s source provider gone in 26.2 | D6 | it is the entry criterion; a shim through a resource provider is the fallback, designed only if needed |
| The production run finds what the harness cannot (a client-only class on a server; the host's alpha test; `initGui` re-entry) | D4 | `serverSmoke`, `runObfClient`, and the invariant rows that already record those shapes |
| Performance regression hidden by a faster machine | every gate | the profiler baselines are per machine and the comparison is run on the machine that captured them |
