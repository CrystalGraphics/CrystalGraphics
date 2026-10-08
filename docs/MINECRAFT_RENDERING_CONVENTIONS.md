# Minecraft's rendering conventions, version by version

**What this is**: every way Minecraft changed the frame CrystalGraphics draws into — a depth direction, a
clear value, a format, a cache that skips calls — found by reading or measuring, each with what the engine
does about it. It grows with every release. Minecraft is moving toward a new rendering model (Vulkan beside
OpenGL in 26.2, and more to come), so each 26.x can carry changes like these; this is where they are caught
before a player finds them.

**Status per row**: **adapted** (the engine handles it; the commit is CrystalGraphics' unless it says
CrystalGUI), **recorded** (known, and nothing of ours depends on it yet — the row says who will), **open**
(needs work).

**Measured against** CrystalGraphics `2747aba` and the client jars of 1.21.11 to 26.2. **Add to it** whenever a
Minecraft version is added (`docs/BUILD.md` § *Adding a Minecraft version*): survey as in §1, one row per
difference.

---

## 1. How a new version is surveyed

1. **Static**: `python singlejar-logic/mcrender.py <previous> <new>` — every rendering
   convention fact that differs: constants, enum lists, GL calls with literal arguments, the fields a JOML
   matrix call reads, the Blaze3D enum constants each method selects. `--api` adds classes and members.
   Unobfuscated jars only (26.1 on). A restructured backend renames many methods, so compare *what* is
   called as well as *where*: the distinct GL calls with literal arguments, the constants and the enums.
2. **Run time**: prodSmoke the previous and the new version with
   `-PcgSmokeProps=crystalgraphics.host.census=true`, then `python singlejar-logic/census_diff.py <instance> <instance>`:
   the live GL state at each entry point (`opaque`, `transparent`, `gui`, `frame`), only where the clients
   disagree. This is also the only way to compare across the 26.1 obfuscation boundary.
3. **Blaze3D's cache**: diff `GlStateManager`'s methods. Any state it newly caches must be routed through it
   in `Blaze3dGLBackend`, or Minecraft skips its next set of that state against a stale cache.
4. **Triage** each difference into a row below; fix what must be adapted, verified on the new version.

---

## 2. The register

### Minecraft 26.3 (against 26.2)

`mcrender.py` scans `com/mojang/renderpearl` and names types without their package from 26.3, so the move
below is not reported as a thousand differences.

| # | Convention | Before (26.2) | 26.3 | Where Minecraft says so | Ours | Status |
|---|---|---|---|---|---|---|
| 31 | **The window toolkit** | GLFW | **SDL3**; `lwjgl-glfw` is not shipped at all | `Window.handleEvent(SDL_Event)`, `RenderSystem.pollEvents(SDLEventHandler)`, LWJGL 3.4.3 with `lwjgl-sdl` | A 26.3 node registers `runtime/lwjgl/sdl` (`SdlInputService`, `SdlCursorService`) in place of the GLFW pair; no GLFW class may be named on it. Fabric's input chain is SDL's event filter | adapted |
| 32 | **Key and mouse numbering** | GLFW key codes (`KEY_ESCAPE = 256`); buttons 0 left, 1 right, 2 middle | **SDL scancodes** (`KEY_A = 4`, `KEY_ESCAPE = 41`); buttons 1 left, 2 middle, 3 right | `InputConstants`; NeoForge's `ScreenEvent.KeyInput.getKey()` (scancode) beside `getKeycode()` (SDL keycode) | `CgSdlKeyCodes`; hosts translate buttons through `translateMouseCodes` and name keys through CrystalGUI's `CgUiInput.hostKey` | adapted |
| 33 | **Blaze3D's GPU layer is its own library** | `com.mojang.blaze3d.{opengl,vulkan,textures}`, `systems.GpuDevice` | `com.mojang.renderpearl.{backend.opengl, backend.vulkan, api.textures, api.device}`; `GpuTexture`, `GpuSampler` and `GpuDevice` are interfaces; `GlDevice(GlBackend, GpuDebugOptions)` | the jar | A `replacements.string` for 26.3+ in both Stonecutter scripts; `GlStateManager`'s statics are unchanged but for an added `_glReadBuffer` | adapted |
| 34 | Pipelines compile off the frame | synchronous | `GpuDevice.compilePipeline(…, Executor)` → a future; `RenderSystem` pipeline caches | `GpuDevice`, `RenderSystem` | Minecraft's own pipelines | recorded |
| 35 | **Translucency** | back-to-front into the main target | **moment-based OIT, behind the experimental Improved Transparency option** (off by default; classic back-to-front otherwise): `OitStage` DEPTH_BOUNDS, TRANSMITTANCE, ACCUMULATE; RGBA16F/RGBA32F transmittance targets beside the D32 depth; `executeDepthBoundsCull`, `executeOit`, `executeOitWaterMask` | `LevelRenderer` (`OIT_WAVELET_RANK = 2`) | Where our transparent pass lands against the OIT resolve is open (§3) | recorded |
| 36 | First-person hands | the world's depth | **their own depth**, merged after (`render3dHud`, `integrate3DHudDepth`, `PROJECTION_3D_HUD_Z_FAR = 100`) | `GameRenderer` | Nothing of ours draws there | recorded |
| 37 | Depth direction, clip range, projection order, main target format | rows 1–4 | **unchanged**: `GEQUAL`, clear 0, `ZERO_TO_ONE`, `zFar` before `zNear`, `D32_FLOAT` | `DepthStencilState`, `GlDevice.<init>`, `Projection`, `MainTarget.<init>` | Row 1–4's adaptations hold | recorded |
| 38 | GL vertex arrays | one `VertexArrayCache` | built per pipeline (`VertexArray$Separate(GlProgram, CreateInfo)`) | `renderpearl.backend.opengl` | Its VAOs, not ours; the census confirms what is bound at entry | recorded |
| 39 | **The frame is extracted, then drawn** | `GameRenderer.render(DeltaTracker, boolean)` | `extract(DeltaTracker, boolean)`, then `render()`; `LevelExtractor.extract` resets `LevelRenderState`, and with it `ParticlesRenderState` | `GameRenderer`, `LevelExtractor` | Fabric's `FrameEndHook` takes `render()V`'s tail. Forge's transparent pass hooked `ParticlesRenderState.reset` as "after particles"; on 26.3 that is extraction, before anything is drawn | adapted |
| 40 | **Terrain passes are opened by the caller** | `ChunkSectionsToRender.renderGroup(group, sampler)` opened its own render pass | `renderGroup(group, RenderPass, GpuSampler, GpuTextureView, boolean)`: solid terrain and classic translucency share one pass `LevelRenderer`'s main-pass lambda opens; OIT runs after it closes | `LevelRenderer.executeSolid`, `executeClassicTransparency`, `executeOit` | Forge's world passes run at the head and tail of `executeOit` or `executeClassicTransparency`, whichever runs — outside any pass with OIT, inside the solid pass without it (§3) | adapted |
| 46 | **The input method is the app's to draw** | GLFW has no IME API: the OS drew its own composition box and candidate list | `RenderSystem` sets `SDL_HINT_IME_IMPLEMENTED_UI` to `composition`, so SDL hides the OS's box; the run arrives as `GuiEventListener.preeditUpdated(PreeditEvent)`, and `TextInputManager.setTextInputArea(x1, y1, x2, y2)` -- two corners in GUI units, not a size -- places the candidate list below that box | `RenderSystem`, `KeyboardHandler`, `EditBox` + `IMEPreeditOverlay` | CrystalGUI's `CgUiScreen` forwards the run to `Input.consumeComposition` (the focused `TextEditor` or `TextField` shows it inline, underlined) and the caret's box to `setTextInputArea` | adapted |

### Minecraft 26.2 (against 26.1.2)

| # | Convention | Before (26.1.2) | 26.2 | Where Minecraft says so | Ours | Status |
|---|---|---|---|---|---|---|
| 1 | **Depth direction** | standard: nearer is smaller, cleared to 1, `LEQUAL` | **reversed-Z**: nearer is greater, cleared to 0, `GEQUAL` | `RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0`; `DepthStencilState.DEFAULT` → `CompareOp.GREATER_THAN_OR_EQUAL`; every `RenderPipelines` default the same | World passes run with `CgGL.setDepthReversed`: depth functions and clears mirrored (5e5667d), polygon offset negated (1865191); our own targets built outside it — GUI paints (`OwnDepthConvention`), context init and resize (fd5cdcd) | adapted |
| 2 | **Clip-space depth range** | GL default, −1..1 | **0..1** (`glClipControl(LOWER_LEFT, ZERO_TO_ONE)`) when `ARB_clip_control`/GL 4.5, else −1..1 | `GlDevice.<init>`; `DeviceInfo.isZZeroToOne` | `CgGL.setDepthReversed(reversed, zeroToOne)` from `DeviceInfo`; under Vulkan it also picks each program's zero-to-one vertex stage (`cgClipZeroToOne`), without which our world drew over everything Minecraft did; a projection built for the world pass follows it (0e6c7ed); `cg_DepthParams.y` (a45ad32); `OwnDepthConvention` restores −1..1 for our own targets | adapted |
| 3 | **Projection planes** | `setPerspective`/`setOrtho(near, far)` | **far and near swapped** — both the world's perspective and the GUI's orthographic | `Projection.getMatrix` reads `zFar, zNear` first | World: the host's matrices arrive reversed and our demo builds its own the same way. **GUI: Minecraft's own GUI depth is reversed too** — matters when we draw Minecraft items and entities inside our UIs (the private plan `platform-native-renders`) | adapted (world) · recorded (GUI) |
| 4 | **Main target depth format** | `TextureFormat.DEPTH32` | **`GpuFormat.D32_FLOAT`** | `MainTarget.allocateDepthAttachment`, `RenderTarget.createBuffers` | Depth snapshot probes its source's format (7da83a0); `cg_DepthBuffer` read through `cg_LinearEyeDepth` (a45ad32) | adapted |
| 5 | **Blend equation cached** | never cached | **cached** beside the factors (`BlendState.modeRgb/modeAlpha`); `BlendFunction` carries a `BlendOp` (ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX) | `GlStateManager._blendEquationSeparate` | `Blaze3dGLBackend.glBlendEquationSeparate` routed through it (5dfef0e) | adapted |
| 6 | **Blend enable and colour mask per target** | one | **per colour target**, up to 8 (`BLEND[]`, `_enableBlend(int)`, `_colorMask(int, int)`) | `GlStateManager` | `Blaze3dGLBackend` tells all eight on a `glEnable(GL_BLEND)`; `glColorMaski` routed | adapted |
| 7 | Blend factors | cached | still one global entry (`BLEND[0]`) | `_blendFuncSeparate` | routed, as before | recorded |
| 8 | Colour clears | `glClearColor` + `glClear` | `glClearBufferfv` (`_clearBuffer`); no clear value cached | `GlStateManager._clearBuffer` | nothing to route | recorded |
| 9 | Texture formats | `TextureFormat` = RGBA8, RED8, RED8I, DEPTH32 | **`GpuFormat`** — the full table (8/16-bit UNORM/SNORM/UINT/SINT, float, D16, D24S8, D32F, D32F_S8, S8) | `com.mojang.blaze3d.GpuFormat` | Nothing reads Minecraft's formats yet; native renders and texture sharing will | recorded |
| 10 | Vertex formats | `VertexFormatElement` enum, one vertex buffer per pass | **semantic names and ids** (`DefaultVertexFormat.*_SEMANTIC_NAME`, `BufferBuilder.*_SEMANTIC_ID`), **16 vertex buffers** per pass, `PrimitiveTopology`/`IndexType` split out | `BufferBuilder`, `DefaultVertexFormat`, `RenderPass.MAX_VERTEX_BUFFERS` | Native renders will feed Minecraft's pipelines | recorded |
| 11 | Minecraft's own GL resource model | per-frame uploads | persistent mapping (`glBufferStorage`), a fence per submit, **2 submits in flight**, multi-draw indirect, base instance | `GlCommandEncoder.MAX_SUBMITS_IN_FLIGHT`, `GlTransientMemory`, `DeviceFeatures` | Its buffers, not ours. A `DRAW_INDIRECT_BUFFER` left bound is harmless to our draws; the census confirms what is bound at entry | recorded |
| 12 | Device heuristics | none | `HintsAndWorkarounds` (buffer writes slow, anisotropy broken), `GlHeuristics` (GL on DX12, AMD), `DeviceType` | `com.mojang.blaze3d.systems` | Minecraft's own choices | recorded |
| 13 | **A Vulkan backend** | GL only | user-switchable Vulkan, GL the fallback | `com.mojang.blaze3d.vulkan` | CrystalGraphics draws through its own Vulkan device hosted on Minecraft's (`Blaze3dVulkanHost`, device-seam D5): each host section's commands spliced into Minecraft's submit, its images back in `GENERAL` at every hand-over | handled |
| 14 | Input constant (not rendering) | `InputConstants.MOUSE_BUTTON_8 = 0` | `= 7` | `InputConstants` | nothing of ours names it | recorded |

### Performance: what Minecraft's context and device cost us (26.2, 2026-10-08)

Found profiling 360,000 particles (`GPU_DRIVEN_RENDERING.md` § *Case study*); evidence in
`plan/crystalgraphics/mc-perf-notes.md`. None of them changes a pixel: each one is a cost the harness never pays.

| # | What Minecraft does | Cost to us | Ours | Status |
|---|---|---|---|---|
| 47 | **Turns `GL_DEBUG_OUTPUT` on** at its default `glDebugVerbosity` of 1 (`GlDebug.enableDebugCallback`) | On NVIDIA, every GL call of ours and of Minecraft's got several times dearer: the blasts scene 15.37 → 9.61 ms a frame without it, Minecraft's `Entity Model` 1.21 → 0.22 ms | `Blaze3dGLBackend.fromHost` turns it off once for the context; `-Dcrystalgraphics.gl.keepHostDebugOutput=true` keeps it. Switching it per host section cost the driver ~1.1 ms a switch | adapted |
| 48 | **Submits once a frame**, at its end, and our Vulkan work reaches the GPU only inside that submit | An async compute pass waited for Minecraft's whole frame, and its reader held up the next frame's submit: the GPU ran serially, 5 ms a frame in `vkQueuePresentKHR` | `HostedVulkanHost` runs async passes in order; `-Dcrystalgraphics.vulkan.asyncCompute=true` restores them. What would bring them back: `ENGINE_API.md` § *Async compute* | adapted (26.2, 26.3) |
| 49 | **Creates its device with `multiDrawIndirect` and `shaderDrawParameters`** (`VulkanBackend.REQUIRED_DEVICE_FEATURES`) but not `drawIndirectFirstInstance` | Our hosted device had assumed none of the three, so no draw of ours joined on Minecraft's Vulkan: 323 draws stayed 323 calls | `MinecraftDeviceFeatures.addTo` records Minecraft's two and asks for `drawIndirectFirstInstance` where the GPU has it; `Blaze3dVulkanHost` answers multi-draw from them | adapted (26.2) · open (26.3: the hook is 26.2's only) |

### Minecraft 26.1 → 26.1.1 → 26.1.2

`mcrender.py`: **0 convention facts differ** between 26.1 and 26.1.1, and between 26.1.1 and 26.1.2.

### Minecraft 1.21.11 → 26.1

The shipped 1.21.11 jar is obfuscated, but NeoForge's Mojang-named one is not:
`mcrender.py --jar <node>/build/moddev/artifacts/neoforge-21.11.45-merged.jar --jar <26.1 client jar>`.
It carries NeoForge's patches, so a difference is checked against a NeoForge-patched 26.1.2
(`minecraft-patched-26.1.2.*-merged.jar`) before it is called Mojang's.

| # | Convention | 1.21.11 | 26.1 | Where | Ours | Status |
|---|---|---|---|---|---|---|
| 15 | Depth direction and format | standard, `LEQUAL`, `DEPTH32` | the same, now `CompareOp` + `DepthStencilState` | `RenderPipeline$Builder`, `DepthStencilState.DEFAULT` | unchanged | recorded |
| 16 | Projection | `GameRenderer.getProjectionMatrix`: `perspective(near, far)` | `Projection.getMatrix`: `setPerspective(near, far, zZeroToOne)` | `Projection` | the host's matrices, unchanged in direction | recorded |
| 17 | GL version asked for | the context the window gave (3.2 core through 1.21.4) | **3.3** (`GlBackend.VERSION_MAJOR/MINOR`) | `GlBackend` | our floor accepts either (814b1bd) | recorded |
| 18 | Colour write mask | four booleans | a bitfield (`ColorTargetState.WRITE_*`), per colour target | `ColorTargetState` | `Blaze3dGLBackend.glColorMask` maps it from 26.1 | adapted |
| 19 | Wrapping a GL texture | `GlDevice.createExternalTexture` | **gone** | `GlDevice` | Nothing uses it yet; sharing our textures with Minecraft (native renders) will need another way | recorded |

### NeoForge's patches on top (every NeoForge 1.21.5+)

| # | Convention | Vanilla | NeoForge | Where | Ours | Status |
|---|---|---|---|---|---|---|
| 20 | **Stencil state cached** | not cached from 1.21.5 (stencil left Blaze3D) | **cached again** — `_stencilFunc/Front/Back`, `_stencilOp…`, `_stencilMask` | NeoForge's patched `GlStateManager` | `Blaze3dGLBackend` routes stencil only below 1.21.5, so on NeoForge ours goes past the cache. Latent: every scope restores the driver value, and the round trip reports no leaks on any client. Routing it would be NeoForge-only code in `common`; do it if a stencil leak ever appears | recorded |
| 21 | **Main target with stencil** (26.2) | depth only, `D32_FLOAT` | `MainTarget(w, h, stencil)`: when a mod asks, **`D32_FLOAT_S8_UINT`**, attached as stencil too | NeoForge's `MainTarget`, `DirectStateAccess.bindFrameBufferTextures(…, boolean)` | `LifecycleModern.mainFbo` attaches it to `DEPTH_STENCIL_ATTACHMENT` when the format has stencil (`GpuFormat.hasStencilAspect` on 26.2, the format's name on NeoForge 26.1), so the depth snapshot matches it; before, a depth-only attachment made the snapshot pick `DEPTH32F` and the blit fail the format match. Not yet run on a stencilled target: no mod in the test instances asks for one | adapted |

### Run time: the GL census, 2026-09-30

`census_diff.py` over 1.20.1 Fabric, 1.21.11 Fabric and NeoForge, 26.1 Fabric, 26.1.1 Forge and 26.2 on all
three loaders, at the opaque and transparent passes, our GUI paint and the frame end. It confirms rows 1, 2
and 4 on real clients (26.2's world passes: clip `ZERO_TO_ONE`, clear 0.0, `GEQUAL`, 32-bit float depth; our
GUI paint back at −1..1 and 1.0), and found what no code diff could:

| # | What the host leaves at our entry | Versions | Ours | Status |
|---|---|---|---|---|
| 22 | **Sampler objects bound on units 0–2**, which override a texture's own filtering, wrapping and LOD | 1.21.11 → 26.2 (`GlRenderPass` binds one per draw, never unbinds, caches none) | `CgHostSamplers` unbinds them for our world passes (2747aba) and CrystalGUI's frame through its composite (CrystalGUI acd355d7): every unit, unread, since reading the bindings back was a `glGet` per unit; Minecraft rebinds its own per draw | adapted |
| 23 | **Scissor test on** at the world passes (26.2 Fabric), and a small box set (1.21.11 NeoForge) | 26.2; any | The world passes save `SCISSOR` and disable the test (2747aba); CrystalGUI's frame already did | adapted |
| 24 | `UNPACK_ROW_LENGTH` 64–512 and skips set | 1.21.11 → 26.2 | `CgTightUnpack` resets the unpack state around our uploads | adapted |
| 25 | Alpha writes off (`COLOR_WRITEMASK 1 1 1 0`) at a GUI paint | 1.21.11 → 26.2 | `CgUiPaintContext.beginFrame` sets all four | adapted |
| 26 | `PROGRAM_POINT_SIZE` on | 1.21.11 → 26.2 | A points shader of ours must write `gl_PointSize` there | recorded |
| 27 | `TEXTURE_CUBE_MAP_SEAMLESS` on | 1.21.11 → 26.2 | Our cube maps sample seamlessly there, and not on 1.20.1 | recorded |
| 28 | Depth bits actually allocated | 24-bit unsigned for every "DEPTH32" through 26.1.x; 32-bit float on 26.2 | The snapshot probes the real format (row 4) | recorded |
| 29 | Context | GL 4.6 on Forge 26.1.1 and 26.2; 3.3 on Fabric and NeoForge; 3.2 on 1.20.1 | Our floor covers all three | recorded |
| 30 | Depth test off at a GUI paint | 1.21.11 → 26.2 (on in 1.20.1) | Our frame sets its own | recorded |

### Run time: the GL census, 26.3 against 26.2

The same census on the three 26.3 clients, diffed per loader with `census_diff.py`. Every entry point
fires on every loader, Forge's new `executeOit` hooks included.

| # | What a host hands us | Seen on | Ours | Status |
|---|---|---|---|---|
| 41 | **No stencil on the window's framebuffer** (`STENCIL_SIZE` absent; 26.2's had 8 bits) | 26.3, every loader (SDL creates the window) | Nothing of ours draws stencil to the default framebuffer; our own targets carry their own | recorded |
| 42 | GL context 3.3 on Forge (4.6 on 26.2) | 26.3 Forge, like Fabric and NeoForge | Our floor | recorded |
| 43 | **Depth test on at a GUI paint**, `GEQUAL`, depth clear 1.0 | 26.3 (off from 1.21.11 to 26.2) | `CgUiPaintContext.beginFrame` sets its own depth state | recorded |
| 44 | Blend and scissor test on at the opaque pass | 26.3 NeoForge | Rows 22–23: the pass saves both, disables scissor, and a material applies its own blend | recorded |
| 45 | `DRAW_INDIRECT_BUFFER` bound at every entry; pack and unpack alignment 1 (4 on 26.2) | 26.3, every loader | Harmless to our draws, none indirect; alignment 1 only removes row padding from readbacks | recorded |

---

## 3. Open

- **Row 40, Forge 26.3 on default settings.** Improved Transparency is off by default, so both world passes
  run inside Minecraft's open solid-terrain pass, whose cached pipeline and bindings our scopes restore but
  whose framebuffer `bindMainTarget` hands over. prodSmoke draws and the census sees both passes fire;
  nobody has looked at a transparent material or depth-tested geometry there yet.
- **Row 35, 26.3 with Improved Transparency on.** Translucent terrain, water and particles then resolve
  through moment-based OIT, so a pass hooked where 26.2's translucent world ended (NeoForge
  `AfterTranslucentParticles`, Fabric `END_MAIN`) may draw after the composite rather than inside it.
  Answered on a 26.3 client with the option on and a transparent material over water.
- **Row 49 on 26.3.** `MinecraftDeviceFeatures.addTo` is 26.2's only, so on 26.3's Vulkan our draws do not join.
  Port the hook to `renderpearl`'s device creation (row 33), then check `mesh.multi-draws` on a 26.3 client.

The census hooks are permanent: `opaque`, `transparent` and `frame` in `CgGraphicsLifecycle`, each after
its stood-down guard (a stood-down host has no GL context to read), and CrystalGUI's `gui` in
`CgUiPaintContext.beginFrame`.
