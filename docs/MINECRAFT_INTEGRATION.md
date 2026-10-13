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
Using all of it from a mod: [`docs/NETWORKING.md`](NETWORKING.md). This section is how the hosts carry it.

**A mod sends a typed `CgMessage`** (a name, a direction and a `CgCodec`, declared at init) to a `CgAudience`.
Each connection opens with `cg/hello`: the client says which message namespaces it declared, until the server
answers with its own, and a send to a peer lacking the namespace, on another version, or silent (no mod) is
skipped and counted rather than sent. **A send leaves at once**, flushed in order with the game's packets that tick,
so send from the game thread; networked objects' changes still coalesce to the tick's end. `CgNetwork.onServerTick` runs a
listener each server tick before those changes go out, and `serverTicks()` counts the ticks.

**Audiences** are `player`, `all`, `dimension`, `near`, `tracking` (an entity, or a chunk), `trackingAndSelf` and
`except`. All but the first two and the last ask the host's levels and players (`platform.world`), which each host
provides beside its channel, both sides, in facts only. `CgLevels` (`LevelsModern`, `LevelsLegacy`, `Levels1710`)
answers a level's dimension, an entity's position, network id and life, and the world and entity queries over any
level, a server's included, for what runs on the server tick (a beam's cast). `CgPlayerQuery` (`PlayerQueryModern`,
`PlayerQueryLegacy`, `PlayerQuery1710`) answers a player's profile id, the server's player by id, and who has a chunk
or an entity loaded. Modern Minecraft has no public answer to the last, so `PlayerQueryModern` ports vanilla's
tracking rule; legacy and 1.7.10 ask their entity tracker. Core reads them only through its facades
(`CgWorldQueries`, `CgEntityQueries`, `CgPlayers`): `platform/CLAUDE.md` § *The wiring standard*.

**Networked objects** (`net.object`: `CgNetType`, `CgNetObject`) are server-owned objects of typed fields, mirrored
to the clients that can see them: changes coalesce to one frame a tick, a player coming into view is sent the
current values, and a `persisted()` type saves under the world's directory (`crystalgraphics/objects/`) at stop and
every five minutes. Who sees an object is its anchor (a point within its type's range, an entity's or a chunk's
trackers, a dimension, everyone), or an audience per object (`visibleTo`). Effects over the network are `vfx.net`'s
cues, a type each (`docs/VFX.md` § *Effects over the network: cues*). Hosts forward the server starting too
(`serverStarting`, about-to-start on modern), so persisted objects are loaded before any player joins.

**`CgRequest`** is a typed question with one answer (`toServer`/`toClient`, `onServer`/`onClient`, `ask`), over the
router's correlation, timeout and cancel; a peer without the namespace answers `CgRequest.UNSUPPORTED`. Mods
mostly push: a `CgMessage` unless the asker needs the answer.

### Collision — `collision`, `shape`

World queries, hitboxes and snapshots are core (`docs/COLLISION.md`); a host only answers what a level holds, in
facts, through the `platform.world` slots and the mirror's. A host decides nothing about collision: every rule (who may be hit, the
nearest first, hit once) is core's.

| Slot | What it answers | Modern | Legacy Forge | 1.7.10 |
|---|---|---|---|---|
| `CgLevels` | a `CgWorldQuery` and a `CgEntityQuery` over any level, a server's included, the client's by its client subclasses: what `CgCollisionWorld.of(level)` asks | `LevelsModern` | `LevelsLegacy` | `Levels1710` |
| … its block queries | collision boxes per block, fluid height and kind, loaded columns, the border, the level's epoch, and `copyBlocks` for a snapshot | `LevelWorldQueryModern` | `LevelWorldQueryLegacy` | `LevelWorldQuery1710` |
| … its entity queries | `within` a box, each one's real box, flags, kind, parent, root vehicle | `LevelEntityQueryModern` | `LevelEntityQueryLegacy` | `LevelEntityQuery1710` |
| `CgHostShapes` | Minecraft's boxes, block shapes and entities into `CgAABB`/`CgShape`, and boxes back (`CgAABB.of`, `set`, `toHost`, `CgShape.of`) | `HostShapesModern` (`AABB`, `VoxelShape`, `Entity`) | `HostShapesLegacy` (`AxisAlignedBB`, box lists, `Entity`) | `HostShapes1710` (the same) |
| `CgHostDebug` | whether Minecraft's own hitbox view (F3+B) is on, which the collision show follows | `HostDebugModern` | `HostDebugLegacy` | `HostDebug1710` |
| `CgMirrorHost` (`collision.mirror`) | a server level's loaded chunks without loading one, a section's states, each state's shape, and the write hook's column on a chunk: what a level's mirror keeps snapshots from | `MirrorHostModern` | `MirrorHostLegacy` | `MirrorHost1710` |

`CgCollisionWorld.client(partialTick)` asks the client level's queries (`CgWorldQueries.client()`,
`CgEntityQueries.client()`, the same the VFX engine reads), its entities interpolated to where the frame draws them.
The client halves are handed to `CgLevels` from client-only code (`ClientModern`, `ClientLegacy`, `Client1710`).

**How each era spells it** (read with `mcapi.py` on every node, 1.7.10 from its sources; the plan's S4a table in full):

| Touch point | Modern 1.13.2–26.3 | Legacy 1.8.9–1.12.2 | 1.7.10 |
|---|---|---|---|
| An entity's box | `getBoundingBox()`, interpolated on a client by its lerped position | `getEntityBoundingBox()`, `AxisAlignedBB` | the `boundingBox` field, a mutable `AxisAlignedBB` |
| A block's boxes | `VoxelShape.toAabbs()`, converted once per shared shape; the entity's `CollisionContext` for `as(id)` | `addCollisionBoxesToList` | the same |
| Entities in a box | 1.19.3+ `getEntities(EntityTypeTest, AABB, Predicate, List)` into a reused list; before, the list-returning form. Parts included (vanilla's dragon loop, Forge's `getPartEntities()`) | `getEntitiesWithinAABBExcludingEntity`, parts through `Chunk` | the same, with one mutable query box kept |
| A part by id | `ServerLevel.getEntityOrPart` 1.17.1+, `Level.dragonParts()` 1.21.4+; else the parts `within` handed out, remembered (16) with their parent | a server's `getEntityByID` | the same |
| A part's whole | `EnderDragonPart.parentMob` (its package moves at 1.21.11); a modded `PartEntity` is its own whole (`getParent()` is loader API) | `entityDragonObj` to 1.10.2, `MultiPartEntityPart.parent` 1.12.2 | `entityDragonObj` |
| Root vehicle | `getRootVehicle()` | `getLowestRidingEntity()` 1.10.2+, the `ridingEntity` chain 1.8.9 | the `ridingEntity` chain |
| Flags a hit asks | `isSpectator()`, `isPickable()`, `isAttackable()`, `isInvulnerable()` or a player's abilities | `isSpectator()`, `canBeCollidedWith()`, `canBeAttackedWithItem()`, `isEntityInvulnerable(...)` | no spectators; `canBeCollidedWith()`, `canAttackWithItem()` |
| An unloaded block | `isLoaded(pos)` before `getBlockState`: never loads one | `isBlockLoaded(pos)`: `getBlockState` would load a server chunk | `blockExists(x, y, z)` |
| A snapshot's blocks | `copyBlocks` by section (`LevelChunkSection`'s states), a palette entry per distinct state | by section (`ExtendedBlockStorage`) | the same |

**The level mirror** (`collision.mirror.CgLevelMirror`) keeps every area a server level's snapshots and watches ask
for, so a snapshot costs the tick nothing. A host gives it a write hook, a mixin on the chunk, which logs each block
write of a watched chunk before it lands and detaches the chunk when it unloads; the mirror replays the log on a
worker. Both sides (`VariantMixins`' common list): an integrated server runs on the client.

| Era | Write hook (`ChunkWriteHook`, HEAD) | Unload | Shapes |
|---|---|---|---|
| Modern 1.14.4–1.21.4 | `LevelChunk.setBlockState(BlockPos, BlockState, boolean)`, also as `m_6978_`, `func_177436_a`, `method_12010` | `setLoaded(boolean)` | each state's cached shape, on a worker; a block with a dynamic shape resolved where it stands, at the tick's end, and every tick if a block entity moves it |
| Modern 1.21.5–26.3 | the same with an `int` flags argument | the same | the same |
| Legacy 1.8.9–1.12.2 | `Chunk.setBlockState` (`func_177436_a`) | `onChunkUnload` (`func_76623_d`) | every block resolved where it stands (they read their neighbours): a written cell and its six neighbours at the tick's end, a new section within the per-tick budget |
| 1.7.10 | `Chunk.func_150807_a` and `setBlockMetadata` | `onChunkUnload` | the same |

- **No mirror where the hook did not apply**: a client's level, Forge 1.13.2 and 1.14.3 (no loaded-chunk lookup that
  never loads), and Forge and NeoForge dev runs, which apply no CrystalGraphics mixin. There `snapshot` copies the area
  on the spot, as `copyBlocks` below.
- The mirror publishes at `CgNetwork.onServerTick`, the END phase on every loader.
- **A write past the hook** (a mod setting a section's states directly) is found by an audit that compares each
  mirrored column with its chunk about once a minute, a few columns a tick, and the section is copied again. Legacy
  and 1.7.10, which resolve every block where it stands, are not audited.

- **Entity ids are Minecraft's network ids** (`entity.getId()`); a host turns one back with the level's lookup, parts
  included as above.
- **Nothing per call allocates beyond Minecraft's own**: one `AABB` per `within` on 1.8–26.3 (its fields are final),
  nothing on 1.7.10; host-box conversions write into the caller's box (`CgBoxSink`), 2.5 ns each.
- **Started hitboxes tick on the forwarded ticks** (`CgNetwork.onServerTick`, `onClientTick`), after the level's own,
  on its thread; job loops (`CgJob`, `docs/JOBS.md`) drain on the same ticks, and `CgNetwork.onSessionEnd` cancels
  every job a game thread started. A level's epoch changing stops the hitboxes of the level that went.
- **The collision show** (`crystalgraphics.collision.show`, or F3+B through `CgHostDebug`) rides the channel: a
  client asks (`crystalgraphics:collision/show`) and the server packs its hitboxes and queries within 64 blocks of
  that player (`CgEntityQueries.position` for where they are, `CgWorldQueries.of` for their level) into
  `crystalgraphics:collision/lines`, recording only while someone asks. `crystalgraphics.collision.serve=false`
  keeps a server's lines to itself unless its own `crystalgraphics.collision.show` is on.
- **Proven**: `checkAllTargets` for every host; the section copy against block-by-block reads in `serverSmoke` on
  Forge 1.20.1, Forge 1.16.5 and 1.7.10 (0 differing blocks, radius 8 to 60). A dragon part, a painting's offset box
  and a ring hitbox in a live level still want a dev client (the plan's S10).

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
> **So `runtime/mc/1710`'s `@Mod` class creates no GL objects at all**; the engine starts at the first
> game-loop frame's end, the title screen's, which genuinely owns the render context. A dev run cannot show the
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
(clouds write depth) rather than seen through it. **From 26.2 the hook stays inside the frame graph**: under Vulkan
the world's depth is gone once the graph has run, and a depth reader (the showcase's sky) covered the whole world:

| Loader | Opaque | Transparent |
|---|---|---|
| Forge 1.13.2–1.17.1 | `RenderWorldLastEvent` — both passes at the end of the level | the same |
| Forge 1.18–1.19.2 | `RenderLevelStageEvent` `AFTER_CUTOUT_BLOCKS` (ahead of entities); 1.18–1.18.1 fall back to `RenderLevelLastEvent` at runtime | `AFTER_WEATHER` (ahead of Fabulous's composite: Forge 40–45 have no `AFTER_LEVEL`) |
| Forge 1.19.3–1.19.4 | `AFTER_BLOCK_ENTITIES` | `AFTER_WEATHER` |
| Forge 1.20.1–1.21.1 | `AFTER_BLOCK_ENTITIES` | `AFTER_LEVEL` (every Forge 46+ build has it) |
| Forge 1.21.3–26.1.2 · 26.2 | node mixin `OpaquePassHook` | node mixin `TransparentPassHook`: tail of `LevelRenderer.renderLevel`, which executes the frame graph · return of `WorldBorderRenderer.render`, the weather pass's last draw |
| Forge 26.3 | head of `LevelRenderer.executeOit` / `executeClassicTransparency` | their tail, after the clouds and weather they draw |
| NeoForge 1.20.2–1.21.3 | `RenderLevelStageEvent` `AFTER_BLOCK_ENTITIES` | `AFTER_LEVEL` |
| NeoForge 1.21.4–1.21.8 · 1.21.9–1.21.11 | `RenderLevelStageEvent.AfterBlockEntities` · `.AfterEntities` | `AFTER_LEVEL` to 1.21.5, `.AfterLevel` from 1.21.6 (posted by `GameRenderer` once the level returns) |
| NeoForge 26.1.2 · 26.2+ | `RenderLevelStageEvent.AfterOpaqueFeatures` | `.AfterLevel` · `.AfterWeather` |
| Fabric 1.14.4–1.15.2 | node mixin `WorldPassHook` | the same |
| Fabric 1.16.5–1.21.8 · 1.21.9–1.21.11 | `WorldRenderEvents.AFTER_ENTITIES` · `BEFORE_TRANSLUCENT` | `LAST` · node mixin `TransparentPassHook` (`renderLevel`/`method_22710` tail) |
| Fabric 26.1+ | `LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` | node mixin `TransparentPassHook`: `renderLevel` tail on 26.1.2, `WorldBorderRenderer.render` return on 26.2, `executeOit`/`executeClassicTransparency` tail on 26.3 |

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
