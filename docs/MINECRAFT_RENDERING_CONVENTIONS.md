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

### Minecraft 26.2 (against 26.1.2)

| # | Convention | Before (26.1.2) | 26.2 | Where Minecraft says so | Ours | Status |
|---|---|---|---|---|---|---|
| 1 | **Depth direction** | standard: nearer is smaller, cleared to 1, `LEQUAL` | **reversed-Z**: nearer is greater, cleared to 0, `GEQUAL` | `RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0`; `DepthStencilState.DEFAULT` → `CompareOp.GREATER_THAN_OR_EQUAL`; every `RenderPipelines` default the same | World passes run with `CgGL.setDepthReversed`: depth functions and clears mirrored (5e5667d), polygon offset negated (1865191); our own targets built outside it — GUI paints (`OwnDepthConvention`), context init and resize (fd5cdcd) | adapted |
| 2 | **Clip-space depth range** | GL default, −1..1 | **0..1** (`glClipControl(LOWER_LEFT, ZERO_TO_ONE)`) when `ARB_clip_control`/GL 4.5, else −1..1 | `GlDevice.<init>`; `DeviceInfo.isZZeroToOne` | `CgGL.setDepthReversed(reversed, zeroToOne)` from `DeviceInfo`; a projection built for the world pass follows it (0e6c7ed); `cg_DepthParams.y` (a45ad32); `OwnDepthConvention` restores −1..1 for our own targets | adapted |
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
| 13 | **A Vulkan backend** | GL only | user-switchable Vulkan, GL the fallback | `com.mojang.blaze3d.vulkan` | CrystalGraphics stands down under Vulkan (`CgGraphicsLifecycle.standDown`); the hosted Vulkan device is the private plan `device-seam` | recorded |
| 14 | Input constant (not rendering) | `InputConstants.MOUSE_BUTTON_8 = 0` | `= 7` | `InputConstants` | nothing of ours names it | recorded |

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
| 22 | **Sampler objects bound on units 0–2**, which override a texture's own filtering, wrapping and LOD | 1.21.11 → 26.2 (`GlRenderPass` binds one per draw, never unbinds, caches none) | `CgHostSamplers` parks them for our world passes (2747aba) and CrystalGUI's frame through its composite (CrystalGUI acd355d7), and puts them back | adapted |
| 23 | **Scissor test on** at the world passes (26.2 Fabric), and a small box set (1.21.11 NeoForge) | 26.2; any | The world passes save `SCISSOR` and disable the test (2747aba); CrystalGUI's frame already did | adapted |
| 24 | `UNPACK_ROW_LENGTH` 64–512 and skips set | 1.21.11 → 26.2 | `CgTightUnpack` resets the unpack state around our uploads | adapted |
| 25 | Alpha writes off (`COLOR_WRITEMASK 1 1 1 0`) at a GUI paint | 1.21.11 → 26.2 | `CgUiPaintContext.beginFrame` sets all four | adapted |
| 26 | `PROGRAM_POINT_SIZE` on | 1.21.11 → 26.2 | A points shader of ours must write `gl_PointSize` there | recorded |
| 27 | `TEXTURE_CUBE_MAP_SEAMLESS` on | 1.21.11 → 26.2 | Our cube maps sample seamlessly there, and not on 1.20.1 | recorded |
| 28 | Depth bits actually allocated | 24-bit unsigned for every "DEPTH32" through 26.1.x; 32-bit float on 26.2 | The snapshot probes the real format (row 4) | recorded |
| 29 | Context | GL 4.6 on Forge 26.1.1 and 26.2; 3.3 on Fabric and NeoForge; 3.2 on 1.20.1 | Our floor covers all three | recorded |
| 30 | Depth test off at a GUI paint | 1.21.11 → 26.2 (on in 1.20.1) | Our frame sets its own | recorded |

---

## 3. Open

Nothing. The census hooks are permanent: `opaque`, `transparent` and `frame` in `CgGraphicsLifecycle`, each after
its stood-down guard (a stood-down host has no GL context to read), and CrystalGUI's `gui` in
`CgUiPaintContext.beginFrame`.
