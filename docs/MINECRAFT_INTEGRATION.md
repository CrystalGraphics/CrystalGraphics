# Minecraft integration glue

Moved from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs. Loads itself, imported by the `CLAUDE.md` of the packages it describes: everything under `runtime/mc/`.

## Minecraft Integration Glue

The layer that wires CrystalGraphics into each Minecraft. Read it when debugging frame timing, hot
reload, or GL state shared with Minecraft and other mods. Each host's classes: its own `CLAUDE.md`.

**Every hook below is a host section's bracket**: `CgGraphicsLifecycle`'s entries open one with
`CgGL.fromHost()` and close it with `toHost()`, and a host's own bracket around them nests.
Nothing on GL; the platform guide's *Host sections* has the rules.

### Connections — `CgNetwork`

Every host installs one channel, `crystalgraphics:wire` (`crystalgraphics` on 1.7.10 and legacy Forge), and
forwards join, leave, both ticks, the client's connect and disconnect, and server stopping into
`com.crystalgraphics.net.CgNetwork`, which holds one connection per player by profile UUID and one to the server.
The forwards are `NetworkModern` (each loader's entry class), `NetworkLegacy` and `Network1710`, registered at init
on both sides. A peer without the mod is accepted; what may be sent to it is the protocol's. Anything that
talks over a connection contributes to `CgProtocols` at init and asks `CgNetwork.forPlayer`/`client`.
CrystalGUI's `docs/CGUI_NETWORKING_PRIMER.md` is the guide.

**A mod sends a typed `CgMessage`** (a name, a direction and a `CgCodec`, declared at init) to a `CgAudience`.
Each connection opens with `cg/hello`: the client says which message namespaces it declared, until the server
answers with its own, and a send to a peer lacking the namespace, on another version, or silent (no mod) is
skipped and counted rather than sent.

**Audiences** are `player`, `all`, `dimension`, `near`, `tracking` (an entity, or a chunk), `trackingAndSelf` and
`except`. All but the first two and the last ask the `CgServerPlayers` slot (`platform.service`), which each host
fills beside its channel (`ServerPlayersModern`, `ServerPlayersLegacy`, `ServerPlayers1710`) with facts only:
dimension, position, profile id, and who has a chunk or an entity loaded. Modern Minecraft has no public answer to
the last, so `ServerPlayersModern` ports vanilla's tracking rule; legacy and 1.7.10 ask their entity tracker.

**`CgReplicated`** is a server-owned object mirrored to the clients that can see it: changes coalesce to one a
tick, a player coming into view is sent the current value, and `persisted()` saves under the world's directory
(`crystalgraphics/replicated/`) at stop and every five minutes. Hosts forward the server starting too
(`serverStarting`, about-to-start on modern), so a persisted definition is loaded before any player joins.

**`CgRequest`** is a typed question with one answer (`toServer`/`toClient`, `onServer`/`onClient`, `ask`), over the
router's correlation, timeout and cancel; a peer without the namespace answers `CgRequest.UNSUPPORTED`. Mods
mostly push: a `CgMessage` unless the asker needs the answer.

### 1.7.10 — `runtime/mc/1710`

`CrystalGraphics` is the `@Mod` class (`modid = "crystalgraphics"`) and does **no GL work**: mod loading
runs on the splash screen's shared context (§ *GL objects during mod loading* below). It registers the platform
(`PlatformService1710.onPreInit`/`onInit`) and the crash-report variant line. Other mods declare
`required-after:crystalgraphics`. **There is no coremod** — the GL-redirect layer was deleted on
2026-07-31; see [GL state](#gl-state--cgglstatemanager).

`CgRenderHook` is a Mixin on `EntityRenderer` with three injections:

- **Before `sortAndRender(pass=1)`** in `renderWorld` — `CgRenderStage.WORLD_OPAQUE.fire(...)`, with the
  opaque world already in Minecraft's main FBO and its depth.
- **Before `ForgeHooksClient.dispatchRenderLast`** — `CgRenderStage.WORLD_TRANSPARENT.fire(...)`, after
  translucent terrain.
- **`updateCameraAndRender` TAIL** — the frame tick, on every frame including a GUI with no world.

`MixinMinecraft` covers resize, fullscreen, resource reload and shutdown. Package guide:
`runtime/mc/1710/src/main/java/com/crystalgraphics/mc/v1710/platform/CLAUDE.md`.

**Never fire `WORLD_OPAQUE`/`WORLD_TRANSPARENT` from game code** on any host — each host's hooks fire them
once per frame at the right moment. Register on them instead (`ENGINE_API.md` § *Render stages*).

#### GL objects during mod loading: the right thread, the wrong context

> **The right thread is not the right CONTEXT, and on 1.7.10 that distinction is load-bearing.** FML's
> splash screen runs mod loading with a second, *shared* context of its own — so `FMLInitializationEvent`
> is on the client thread and still not on the renderer's context. Buffers and textures are shared
> between GL contexts; **container objects (VAO, FBO) are not.** A VAO built during mod loading is
> therefore named in a context nothing will ever draw with, while the VBO and IBO it points at stay
> valid, so the object reads as healthy from Java. `glGenVertexArrays` hands the same id to the next
> caller on the first real frame: one VAO, two owners, and the second one's attribute pointers replace
> the first's. Nothing errors — a mesh drawing another mesh's attributes at the wrong stride is
> degenerate geometry, which rasterises nothing.
>
> **So `runtime/mc/1710`'s `@Mod` class creates no GL objects at all**; the first render stage a host fires
> initialises lazily, on a frame that genuinely owns the render context. A dev run cannot show the
> failure (no splash in the way), so it appears only in an installed client. `CgMeshPool` warns
> (`[cg-vao]`) when the driver returns a vertex array name this process still owns — the one cheap signal that two
> contexts are in play. See CrystalGUI's `docs/CGUI_INVARIANTS.md` § *Rendering, GL and shaders*.

### Forge 1.8–1.12.2 — `runtime/mc/legacy`

1.7.10's shape, ported: `PlatformServiceLegacy` over tier 1, `GlStateManagerGLBackend` (Minecraft's GL
state cache kept in step, eight texture units), and the render, frame, resize and shutdown hooks as
SRG-named mixins that MixinBooter applies. `singlejar-logic/README.md` § *Forge 1.8 to 1.12.2*.

### Modern — `runtime/mc/modern` (Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+)

The `common` branch holds everything a loader does not decide: `PlatformServiceModern` (over tier 1),
`Blaze3dGLBackend` (tier 1 plus routing what Minecraft's `GlStateManager` caches through it),
`HostStateVerifier`, and `LifecycleModern` — the one class a loader talks to. Each loader node is
registration only, reached through one bootstrapper per loader (`ForgeBootstrap`, `NeoForgeBootstrap`,
`FabricBootstrap`) that picks the node by the running version.

**The world passes**, by era — each handler rebinds `mc.getMainRenderTarget()` first, since Fabulous
leaves a non-main FBO bound. **The transparent pass runs after Minecraft's clouds and weather**, so a haze bends
them, and where the hook allows after Fabulous composites them too. An effect behind a cloud is then hidden by it
(clouds write depth) rather than seen through it:

| Loader | Opaque | Transparent |
|---|---|---|
| Forge 1.13.2–1.17.1 | `RenderWorldLastEvent` — both passes at the end of the level | the same |
| Forge 1.18–1.19.2 | `RenderLevelStageEvent` `AFTER_CUTOUT_BLOCKS` (ahead of entities); 1.18–1.18.1 fall back to `RenderLevelLastEvent` at runtime | `AFTER_WEATHER` (ahead of Fabulous's composite: Forge 40–45 have no `AFTER_LEVEL`) |
| Forge 1.19.3–1.19.4 | `AFTER_BLOCK_ENTITIES` | `AFTER_WEATHER` |
| Forge 1.20.1–1.21.1 | `AFTER_BLOCK_ENTITIES` | `AFTER_LEVEL` (every Forge 46+ build has it) |
| Forge 1.21.3–26.2 | node mixin `OpaquePassHook` | node mixin `TransparentPassHook`: tail of `LevelRenderer.renderLevel` (`render` on 26.2), which executes the frame graph |
| Forge 26.3 | head of `LevelRenderer.executeOit` / `executeClassicTransparency` | their tail, after the clouds and weather they draw |
| NeoForge 1.20.2–1.21.3 | `RenderLevelStageEvent` `AFTER_BLOCK_ENTITIES` | `AFTER_LEVEL` |
| NeoForge 1.21.4–1.21.8 · 1.21.9–1.21.11 | `RenderLevelStageEvent.AfterBlockEntities` · `.AfterEntities` | `AFTER_LEVEL` to 1.21.5, `.AfterLevel` from 1.21.6 (posted by `GameRenderer` once the level returns) |
| NeoForge 26.1+ | `RenderLevelStageEvent.AfterOpaqueFeatures` | `.AfterLevel` |
| Fabric 1.14.4–1.15.2 | node mixin `WorldPassHook` | the same |
| Fabric 1.16.5–1.21.8 · 1.21.9–1.21.11 | `WorldRenderEvents.AFTER_ENTITIES` · `BEFORE_TRANSLUCENT` | `LAST` · node mixin `TransparentPassHook` (`renderLevel`/`method_22710` tail) |
| Fabric 26.1+ | `LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` | node mixin `TransparentPassHook` (`render` tail on 26.2+) |

The exact version splits are in each loader branch's `AGENTS.md`. **The frame ends after the GUI**, from
a loader frame event or, on Fabric, a mixin — `runtime/mc/modern/common/CLAUDE.md` § *The frame end*,
which also covers 26.1's own main-target framebuffer and 26.2 under Vulkan.

> ⚠️ **The transparent pass may need a rewrite from 26.3.** 26.3's *Improved Transparency* option
> (experimental, off by default) composites translucency with moment-based OIT instead of blending back to
> front, and with it off, translucent terrain draws inside the render pass solid terrain opened — so on
> default settings Forge's world passes run inside Minecraft's pass. Neither has been looked at with a
> transparent material on screen. Before touching the transparent pass, read the private plan
> `crystalgraphics/platform-transparent-pass` and `MINECRAFT_RENDERING_CONVENTIONS.md` rows 35, 39,
> 40 and §3.

**Iris/Oculus**: with a shader pack active, CrystalGraphics geometry renders into the main FBO **outside**
Iris's deferred GBuffer chain and appears unlit under deferred pipelines; `cg_DepthBuffer` stays valid.
`CgIrisCompat.isShaderPackActive()` detects it, and `CgWorldRenderer` warns once.

**Mixin policy**: prefer a native loader event, or a GLFW callback for input; a mixin only where neither
exists.

> ⚠️ **Loader events are not the stable surface for the render hook** — measured 2026-09-11. Across MC
> 1.17.1 / 1.18.2 / 1.19.2 / 1.20.x / 1.21.4, Forge's event API moved four ways — a package renamed
> (1.17), classes that did not exist yet (1.18), a constant added late (`AFTER_BLOCK_ENTITIES`, 1.20.1),
> and `RenderLevelStageEvent` gone by 1.21.3 — while `LevelRenderer.renderLevel` underneath never moved.
> The policy stands for input, lifecycle and anything with a real event; for the world passes a mixin on
> the Minecraft method is the more stable choice, and is what 1.7.10 and legacy Forge have always done.
> Forge 1.21.3+ is where it had to happen: those node mixins run Mojang names, so they need no refmap.

### Hot reload — `CgAssetReloader`

`core/src/main/java/com/crystalgraphics/mc/CgAssetReloader`, reached through each host's
`CgReloadService` on **F3+T** and on resource-pack changes. In order, each step isolated so one failure
does not stop the next:

1. `CgTextureManager.get().reloadAll()` — re-uploads every texture
2. `CgShaderManager.reloadAll()` — marks every raw `CgShader` dirty (recompiled on next `bind()`)
3. `CgMaterialRegistry.get().reloadAll()` + `CgMaterialShaderRegistry.get().reloadAll()` — marks every material dirty

### GL state — `CgGlStateManager`

> **Superseded 2026-07-30, finished 2026-07-31.** `GLStateMirror`, `CgGlStates` and the entire ASM coremod
> (`mc/coremod/` — coremod, transformer, redirects, coverage matrix) are **deleted**. Earlier revisions
> of this file described the mirror as what made `CgGlState.save/restore` reliable — it never could be.

**Why the mirror was abandoned.** It depended on an ASM transformer rewriting GL call sites process-wide.
That cannot be made reliable: our redirector is modelled on Angelica's, targets the same call sites, and has
been observed with **Angelica redirecting ours into its own**. Two transformers competing for the same
bytecode cannot both be authoritative, and any third mod doing raw GL ends the guarantee regardless. It was
also only ever fed on 1.7.10 — on the other three targets it was inert.

**What replaced it.** `gl/state/CgGlStateManager` keeps a CPU-side shadow, eliminates redundant GL calls,
and takes truth from a `CgGlStateProvider` at declared boundaries rather than by observing other code.
`CgGlState` / `CgGlScope` / `CgGlSlot` kept their signatures, so no call site changed.

Measured on the `text-3d` harness scene: `doBind.stateSave` went from **1,599 ms over 838 frames** to
**0.00 ms**, and the worst single frame from **346.8 ms** to a whole-`doBind` max of **2.16 ms**.

Package guide: `core/src/main/java/com/crystalgraphics/gl/state/CLAUDE.md`.
Design record and eight implementation corrections: `plan/gl-state-manager.md`.

**Four rules worth knowing before touching rendering code:**

1. **Raw `CgGL` is fine.** Tracking lives *inside* `CgGL`'s setters, so there is no way to write GL state
   without the shadow seeing it. The typed records (`CgBlendState`, `CgDepthState`, …) are a convenience,
   not a safety requirement — the `cgStateWriteGuard` task that used to police this was deleted along with
   the problem it policed.
2. Any code that resets GL state wholesale **with raw GL that bypasses `CgGL`** — a foreign mod — must
   call `CgGlState.invalidateAllIfPresent()`. Only what goes around `CgGL` is invisible.
3. **Only genuinely global state may be deduplicated.** Anything an object binding implicitly swaps must be
   invalidated when that object changes — `GL_ELEMENT_ARRAY_BUFFER` is per-VAO state, and treating it as
   global elided a required bind and killed every indexed draw through the affected VAO.
4. A wrong decision here produces a **missing GL call** — wrong rendering, no exception. Diagnose with
   `-Dcrystalgraphics.state.verify=true`, which names the offending domain, or
   `-Dcrystalgraphics.state.noDedup=true` to rule the manager out entirely.

---

## Minecraft Source Code Location

| Want | Where |
|---|---|
| **Any node's API, with no setup** | `python singlejar-logic/mcapi.py <Class> [member]` — which versions have it, and its signature on each |
| **What a new version changed about the frame** | `python singlejar-logic/mcrender.py <previous> <new>` — depth direction, clear values, formats, GL calls, projection plane order, pipeline defaults, from the client jars (26.1 on). The run-time half is `-Dcrystalgraphics.host.census`. Findings go in [`MINECRAFT_RENDERING_CONVENTIONS.md`](MINECRAFT_RENDERING_CONVENTIONS.md) |
| A modern node's decompiled sources | `./gradlew :runtime:mc:modern:<branch>:<version>:extractMcSources` → `runtime/mc/modern/<branch>/versions/<version>/build/mc-src/{java,resources}` (makes that node real; minutes the first time) |
| 1.7.10's | `runtime/mc/1710/build/rfg/minecraft-src/java/`, after a build of that module |

1.7.10 files worth knowing:

- `net/minecraft/client/Minecraft.java` — main game class, owns `framebufferMc`
- `net/minecraft/client/renderer/EntityRenderer.java` — render pipeline, shader integration
- `net/minecraft/client/renderer/OpenGlHelper.java` — GL extension detection (THE reference impl)
- `net/minecraft/client/shader/Framebuffer.java` — vanilla FBO wrapper
- `net/minecraft/client/shader/ShaderGroup.java` — post-processing pipeline
- `net/minecraft/client/shader/ShaderManager.java` — GLSL program management
- `cpw/mods/fml/client/FMLClientHandler.java` · `cpw/mods/fml/common/gameevent/TickEvent.java`

The early 1.7.10 research — the vanilla FBO and shader traces, the integration gotchas and strategy — is
archived in the private plan repository, `plan/crystalgraphics/archive/`.
