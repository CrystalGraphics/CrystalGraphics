# Platform SPI — `com.crystalgraphics.platform`

The seam between CrystalGraphics' `core/` and whatever runs it — a Minecraft loader, the harness, an
application. `core/` imports only this package, never `net.minecraft.*`, `org.lwjgl.*` or a loader.

**It also serves CrystalGUI**, which has no platform registry of its own: its input, clipboard and cursor
seams are here too — see [UI-facing services](#ui-facing-services).

---

## File index

| File | Kind | What it owns |
|---|---|---|
| `CgPlatform` | Final class | The registry, both halves: `register(CgPlatformService)` and the `CgService` slots |
| `CgPlatformService` | Interface | The bundle a platform registers: `startDevice()`, which the graphics start calls. Every other capability is a slot |
| `CgService` | Class | An OPEN slot: a consumer declares it, a platform fills it, it carries its own absent-value |
| `com.crystalgraphics.variable.CgVar`, `CgVars` | Classes | Every engine switch and setting as a typed variable: sources code < file < `-D` < console < remote, listed for a console and `docs/DEBUG_FLAGS.md`. A flag a runtime reads is declared on a platform class here, never read raw |
| `gl/CgCapabilities` | Class | The capability snapshot, read from the installed device's `CgRHI.Globals` |
| `gl/state/*` | Classes | The GL device's state: `GLContextState` (Unreal's context state), `GLStateFilter` (its driver over the raw one, dropping a call setting what the context has), `GLHostState` (what a host sharing the context believes, read at `fromHost`, set back at `toHost`) |
| `rhi/CgRHI` | Interface | The device API (Unreal's `FDynamicRHI`): objects, the frame's commands, frames in flight; `CgRHI.get()` the one installed, `CgRHI.Globals` what it can do. **Nothing else goes at `rhi/`'s root** |
| `rhi/format/`, `resource/`, `pipeline/`, `binding/`, `command/`, `shader/`, `sync/` | Interfaces, records | Its types by subsystem: formats; buffers, textures, samplers, views and `CgRHIResource`; graphics and compute pipelines; binding layouts and per-draw bindings; the command list, passes and accesses; modules and `CgGlslCompiler`; timer queries |
| `rhi/memory/` | Classes | Allocators over any `CgRHI`'s buffers: `CgSlabAllocator` (ranges kept across frames), `CgFrameArena` (a frame's uploads, written in place in mapped pages), `CgAllocation` |
| `rhi/nulldrv/CgNullRHI` | Class | The null RHI (Unreal's `NullDrv`): logs every command and throws on misuse — for tests and headless runs |
| `rhi/CgHostImports` | Interface + slot | What a host owns, as device resources: a texture by its handle, a framebuffer's attachments by its name. Installed by the device (`GLImports`, `VulkanHostImports`, `CgHostImports.surface`) |
| `rhi/CgDeviceNames` | Final class | The engine's int names for device textures and buffers, from `0x40000000`, never reused: what a `CgTexture2D` id or a graph buffer is |
| `gl/CgGLDevice` | Class | `CgRHI` on GL 4.6, as Unreal's OpenGL RHI: commands run as called, a pipeline's state applied before each draw where it differs (the PSO fallback), framebuffers cached by attachments, frames retired by fences. Its contract runner is `GLCommandListTest` (the Vulkan module's tests, where shaderc and spvc are) |
| `gl/driver/` | Interface, constants | `CgGLDriver`, the GL 4.6 calls the device makes, DSA only (Unreal's `FOpenGL`), one per LWJGL era (`Lwjgl3GLDriver`; `Lwjgl2GLDriver`, LWJGL 2.9.4's own bindings, GL 4.5 plus `ARB_indirect_parameters` and `ARB_bindless_texture`: no trampoline); `GLC`, their constants. LWJGL 2's contract runner is `Lwjgl2CommandListTest`, its GLSL the fixtures `GLCommandListTest` keeps (`-PupdateGlFixtures=true`) |
| `gl/resource/`, `binding/`, `pipeline/`, `command/`, `sync/`, `format/` | Classes | The device's types by `rhi/`'s subsystems. `binding/GLBindingPoints`: a pipeline layout's sets as ranges of binding points per class, push constants after them; `pipeline/GLShaderTranslator`: Vulkan SPIR-V to GLSL under those points (`SpvcGlslTranslator`); `pipeline/GLParallelBuild`: programs built by the driver's own compiler threads (`KHR_parallel_shader_compile`), issued and polled from the render thread, the default where a driver has it (`crystalgraphics.gl.parallelLinks`; NVIDIA and AMD do, Intel's Windows driver does not); `pipeline/GLLinkWorker`, `GLProgramBuild`: programs linked on a thread in a context of their own, loaded as binaries (`CgGLDevice.linkWorker()`), for a driver without it: on NVIDIA its context stalls the render thread's GL calls while it compiles; `pipeline/GLProgramBinaries`: programs kept across launches (`CgGLDevice.programCache`); `pipeline/GLProgramCache`: one linked program per modules and layout, shared by every pipeline of them whatever state it adds, started ahead by `CgRHI.prepareProgram` (a precache polls it, so no draw waits on a link), which finishes one costly program a frame: NVIDIA compiles a parallel-built program's back end at its first status read, on the asking thread, 0.1-0.5 s each, and every GL context in the process waits on it; `CgToolchainDevice` turns all three on for every GL-era host; `command/GLEncoder`, the command list and its passes |
| `service/CgHostResources` | Static owner + `Host` slot | The host's resource packs: `open(namespace, path)` (null where none has it), `openStack` (every pack's copy, lowest first: what `sounds.json` merges from), `namespaces()`. What `CgIO` opens through before the classpath. Client only; `HostResourcesModern`, `HostResourcesLegacy`, `HostResources1710`, whose `listenForReloads` sends F3+T and a pack change to `CgAssetReloader` |
| `service/CgCursorService` | Interface + slot | Presenting a cursor image; `CgCursorService.SERVICE` |
| `world/CgWorldQuery` | Interface | One level's blocks: collision and its boxes (as no one, or as one entity meets them), the world border, fluid, light, what a block is made of, tint and biome colours (a client level's), heightmaps, precipitation, the particle sprite. Primitives; core composes the scans (`com.crystalgraphics.world.CgWorldQueries`) and every query (`collision.CgCollisionWorld`). Handed out per level by `CgLevels` |
| `world/CgBlockCopy` | Interface | Where a host copies a box of its blocks from its sections (`CgWorldQuery.copyBlocks`): a palette entry per block, each distinct block described once under the host's key. What a collision snapshot is made from; a host without it is read block by block |
| `world/CgEntityQuery` | Interface | One level's entities by int id: interpolated pose and box, flags (what a hit asks too: spectator, pickable, attackable, invulnerable; solid, what Minecraft stops other entities at), kind, parent (a dragon part's dragon), root vehicle, a box walk handing ids to a visitor, parts included, and `<V> V entity(id)` / `entity(UUID)`, the host's own entity (a snapshot throws). Core computes hands, head and feet (`CgEntityAttachments`) |
| `world/CgLevels` | Static owner + `Host` slot | The host's levels, both sides: a `CgWorldQuery` and a `CgEntityQuery` for any level, the client's answered by its client subclasses (`clientWorld()`, `clientEntities()`, `clientLevel()`), a level's dimension, a server's level by dimension, loaded chunks, where the world saves, and an entity object's id, position and life; `single(world, entities)` for a test or the harness. One host class per era (`LevelsModern`, `LevelsLegacy`, `Levels1710`), provided from each era's install; its client half from the client (`ClientModern`, `ClientLegacy`, `Client1710`). Core's `CgWorldQueries` and `CgEntityQueries` are built on it |
| `world/CgPlayers` | Static owner + `Host` slot | The host's players, both sides: a player entity's profile id (`idOf`), the server's player by profile id or entity id, the client's own (`local`), and who has a chunk or an entity loaded. `PlayersModern`, `PlayersLegacy`, `Players1710`; what `CgAudience` and networked objects are made from |
| `world/CgHostShapes` | Static owner + `Host` slot | The host's boxes and block shapes converted, both sides: what `CgAABB.of`/`set(Object)`/`toHost()` and `CgShape.of(Object)` reach. `HostShapesModern`, `HostShapesLegacy`, `HostShapes1710`; without a host every conversion throws |
| `world/CgBoxSink` | Interface | Where a host writes a box, so it fills the caller's own `CgAABB` with nothing between |
| `world/CgModelSpace` | Interface + slot | The camera and the rotation the world's entity pose stack starts from, read live during the level render: what turns a model's pose matrix into a world point (`CgEntityAttachments.mark`). Modern hosts from 1.15; absent before and elsewhere |
| `world/CgHostCamera` | Interface + slot | The offset and FOV scale the host adds to its camera each frame; core sums every shake into it (`CgCameraShake`) |
| `world/CgHostDebug` | Interface + slot | The host's own debug views: whether Minecraft's F3+B hitboxes are on, which `crystalgraphics.collision.show` follows |
| `audio/CgAudioOutput`, `CgAudioStream` | Interface + slot | Where the engine's mixed sound goes: a stream the audio thread opens and alone writes (`Lwjgl3AudioOutput`, a context of its own; `Lwjgl2AudioOutput`, a source in the host's). Client only; `NONE` opens nothing |
| `audio/CgAudioDecoder` | Interface + slot | Ogg Vorbis from memory with the host's own decoder: `StbVorbisDecoder` (LWJGL 3), `JOrbisDecoder` (LWJGL 2 hosts' JOrbis), never bundled. Client only; `NONE` throws |
| `audio/CgHostVolume` | Interface + slot | The host's volume sliders, master and by category, that the engine's sound follows. Client only; `NONE` answers 1 |
| `audio/CgHostDuck` | Interface + slot | Scales the host's own playing and new sounds by category, never its sliders: a mix's `duckHost`. Client only; `NONE` ignores it |
| `service/CgI18n` | Static owner + `Host` slot | A translation key's text in the player's language (`I18n`): what a Minecraft sound event's subtitle reads as; `language()` as a kit names it, `locale()` as a `Locale`. Client only; `NONE` and a missing key answer null |
| `service/CgModLoader` | Static owner + `Host` slot | The mod loader: its name, version and dev flag, every loaded mod (`Mod`: id, version, name, roots), `isLoaded`, and each mod's root, which `asset.load.CgAssetMounts` mounts. Both sides; `ModLoaderForge`, `ModLoaderNeoForge`, `ModLoaderFabric`, `ModLoaderLegacy`, `ModLoader1710`. The new SPI shape (§ *The wiring standard*) |
| `command/CgCommands`, `CgCommandSource`, `CgCommandArgs`, `CgBrigadier` | Static owner + `Host` slot | Commands on every version, as Brigadier's own: `server(c)`/`client(c)` add a `Contribution` (a class generic over the source type), a host `install`s them into the dispatcher the loader hands it, `run`/`suggest` serve a console or a script over a dispatcher of `CgCommandSource`. A source is facts (kind, name, id, operator level, reply, fail). `CgBrigadier.present()` is asked before touching a class that names Brigadier: 1.7.10 and Forge 1.8.9-1.12.2 have none, and core's `CgPlainCommands` serves them. `CommandsModern` (Forge, NeoForge, Fabric; Fabric's client source is its own). [`docs/COMMANDS.md`](../../../../../../../docs/COMMANDS.md) |
| `service/CgShaderPacks` | Static owner + `Host` slot | The shader-pack mod: which one (`iris`, `oculus`, `angelica`, `none`), whether a pack is in use, whether its shadow pass is drawing. Client only; `irisApi()` reads Iris's public `IrisApi` v0, which all three ship, and is what the modern and 1.7.10 hosts provide; legacy Forge provides nothing |
| `service/CgGameDirectory` | Static owner + `Host` slot | The game directory, where `config/` lives (what CrystalGraphics' settings file and cache are kept under; absent: the working directory), and `world()`, the integrated server's loaded world, null with none. Client only; `GameDirectoryModern`, `GameDirectoryService`, `GameDirectoryService1710` |
| `service/CgGameClasses` | Static owner + `Host` slot | The game's own classes as they run: `minecraftVersion()`, a class's `bytes` after every transformer and mixin, and `runtimeName` (Notch to SRG on LaunchWrapper, else itself). Both sides; `GameClassesModern`, `GameClassesLegacy`, `GameClasses1710`. What CrystalGUI's language mod reads the game through |
| `service/CgServers` | Static owner + `Host` slot | The server this process runs: `running`, `dedicated`, its `directory`, `version`, `halt`. Both sides; `ServersModern`, `ServersLegacy`, `Servers1710` |
| `service/CgGameThreads` | Static owner + `Host` slot | The game's client and server threads as a `Binding` (`execute`, `isCurrent`, `isAvailable`), what core's `job.CgHostThread` runs work on. Client slot from the client install, server from either; `GameThreadsModern`, `GameThreadsLegacy`, `GameThreads1710` |
| `service/CgHostWindow` | Static owner + `Host` slot | The surface's `width()` and `height()` in device pixels. Client only; `HostWindowModern`, `HostWindowLegacy`, `HostWindow1710` |
| `service/CgGameSession` | Static owner + `Host` slot | The client's game session for an unattended run (`gauntlet`, `docs/GAUNTLET.md`): `ready`, `inWorld`, `enterWorld(save)` (the save, else the first, else, where the era can, a new flat one: `saveToEnter` decides which for every host), `quit` through the game's own shutdown. Client only; `GameSessionModern`, `GameSessionLegacy`, `GameSession1710` |
| `service/CgScreens` | Static owner + `Host` slot | A screen of the engine's in front of the game, for a UI drawn at `CgRenderStage.SCREEN`: `open(owner)`, `close(owner)` (at the next tick), `isOpen`, `anyOpen` (any screen, the host's own too), `giveBack(key)` for a key a `SCREEN` input listener left, and the host's own screens for an unattended run: `closeFront()`, `openChat()`. The host treats it as its own screen (cursor free, player still, Escape the owner's) and it draws nothing. Client only; `ScreensModern`, `ScreensLegacy`, `Screens1710` |
| `service/CgStorageLayout` | Final class | A product's `<base>/<product>/` tree stated once: `configIn` (`config`, durable) and `cacheIn` (`cache`, disposable). `CgStorageLayout.of("crystalgraphics")` is CrystalGraphics' own, which `CgCacheDirectory` roots under; a consumer makes its own and adds its folders. The config stores, cache files and downloads that use it are core's `storage` package |
| `io/CgAtomicFile` | Final class | `write(file, bytes)`: replaces a file whole (a unique temp beside it, moved over), making its folders. Every kept file goes through it: `CgFiles.writeAtomic`, the config stores, and the GL, SPIR-V and asset-registry caches |
| `service/CgCacheDirectory` | Final class | `of(name)`: a folder under `<game>/crystalgraphics/cache/` for what can be rebuilt — the SPIR-V (`spirv`) and pipeline (`vulkan`) caches a host passes its compiler and device, and the GL device's program binaries (`CgGLDevice.programCache`). Null where `-Dcrystalgraphics.cache=false` or it cannot be made; `-Dcrystalgraphics.cache.dir` moves it |
| `world/CgWorldEvents` | Final class | What the client learns happens in the world (explosion, block broken, entity hurt or killed, lightning), pushed by hosts to listeners, on the render thread |
| `service/CgNetworkChannel` | Interface + slot | Carrying one frame to the server or a player, and its ceiling: the whole platform side of networking. `CgNetworkChannel.SERVICE`; everything above it is core's `net` |
| `service/CgProcessExit` | Static owner + `Host` slot | `halt(status)`: ending the process at once the way the host allows, `Runtime.halt` unless provided. 1.7.10 and legacy Forge provide `FMLCommonHandler.exitJava`, since FML traps a mod's own halt. For a diagnostic run that closes itself, never an ordinary shutdown |
| `input/CgSystemInput` | Interface | The raw event sink and its two event types |
| `input/CgInputListener`, `CgInputStage` | Interface, class | How a host's input reaches code that is not the host's: a mod registers a listener on a stage (`CgInputStage.SCREEN`); each era's host builds the event in the engine's vocabulary and fires it, and a listener that consumes it keeps the screen from seeing it. [`docs/INPUT.md`](../../../../../../../docs/INPUT.md) |
| `input/CgKeyBindings` | Static owner + `Host` slot | Keys a player can rebind: `declare(id, key, category, action)`, early (a `client` listener's `onPreInit`); each era's host registers a game binding and runs the action per press (`KeyBindingsModern`, `KeyBindingsLegacy`, `KeyBindings1710`). Client only. [`docs/INPUT.md`](../../../../../../../docs/INPUT.md) § *Key bindings* |
| `input/CgInput` | Static owner + `Host` slot | The host's input as it stands, every answer an event cannot carry in one place: `isKeyDown`, `isMouseDown`, `modifiers`, `mouseButtons`, `pointerGrabbed` (the game holds the cursor), and a toolkit's codes in the engine's (`key`, `mouseButton`) for a host building an event; `grabPointer`, `movePointer(x, y)` for an unattended run. The toolkit's host where it answers all of it (`InputLwjgl2` on 1.7.10 and legacy Forge, `InputGlfw` in the harness), else an era's over it (`InputModern`: Minecraft's pointer grab over `InputGlfw`, `InputSdl` from 26.3). Client only. [`docs/INPUT.md`](../../../../../../../docs/INPUT.md) § *What is held now* |
| `input/CgClipboard` | Static owner + `Host` slot | The system clipboard's text: `get()` (never null), `set(text)`, on the client thread; from another, through core's `CgHostThread.CLIENT` (`run` to write, `call(...).then(...)` to read). `ClipboardGlfw`, `ClipboardLwjgl2` (AWT), `ClipboardSdl`. Client only |
| `input/CgKeyCodes`, `CgGlfwKeyCodes`, `CgMouseCodes`, `CgModifiers` | Constants | Code tables with no LWJGL import |

**Who implements the bundle**: `PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`,
the harness's `PlatformServiceHarness` and CrystalGUI's `TestPlatformService`. Their device drivers, input devices
and clipboard come from tier 1 (`runtime/lwjgl/2`, `runtime/lwjgl/3`, `runtime/lwjgl/sdl`); a Minecraft
host adds only what names Minecraft.

---

## The wiring standard

How a platform service is declared, provided and read. **`CgModLoader` (`platform.service`) is the shape every new
`CgService` SPI takes** (the user, 2026-10-31): a final class named for the concern is its static owner; its nested `Host` interface is what a host implements, with a `NONE`; its slot is `HOST`; records
it answers are nested too (`CgModLoader.Mod`). Callers use the owner's static calls and never the slot. The older
slots (the audio ones, `CgModelSpace`, `CgHostCamera`, ...) are the previous shape, a bare interface with `SERVICE`,
and are to be rewritten to this one. Core may build on an owner where it adds logic of its own (`CgWorldQueries` and
`CgEntityQueries` over `CgLevels`).

```java
public final class CgModLoader {
    public interface Host {                       // a host's answer, one class per era: ModLoaderForge, ModLoader1710
        String name();
        List<Mod> mods();
        Host NONE = new Host() { /* answers "none" and no mods */ };
    }
    public record Mod(String id, String version, String name, List<Path> roots) { }
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:mod_loader", Host.NONE);
    public static boolean isLoaded(String id) { ... CgPlatform.get(HOST) ... }   // callers: CgModLoader.isLoaded(id)
}
CgPlatform.provide(CgModLoader.HOST, ModLoaderForge.INSTANCE);   // a host, once
```

**The owner reads its slot on every call** (the user, 2026-11-07): a slot read is one volatile read and a flag, what a
cached field would cost, and a cache pins whatever it first saw, a test's fake included. **Never cache anything built
from `NONE`'s answer**: a call made before the host provides the slot would pin the absent value for the session. What
an owner derives once at a cost (`CgModLoader.mods()`'s list) is kept only from a real host.

```java
private static Host host() {
    return CgPlatform.get(HOST);
}
```

**`NONE` never calls the owner**: a `Host.NONE` built through the owner's own factory would initialise the owner, whose
`HOST` reads `Host.NONE` while it is still null. `CgLevels` builds its `NONE` from a private class for that reason.

The rules, for both shapes.

1. **One SPI per concern**, in `platform.<area>` (`platform.world`, ...), named for what it answers, with a `NONE`
   that answers nothing and one slot. A concern never has two slots.
2. **One static owner per SPI, and only it reads the slot**: the SPI's own outer class in the new shape; in the older
   one a facade in core (`CgCamera`), an SPI with a single reader in core read by that class alone.
   `WorldServiceReadersTest` holds it.
3. **Per-level answers are bound views** (`of(level)`, `client()`), held while the level lives; hot paths hold the view.
4. **Host objects cross as `Object` and ids**; a caller wanting the host's object asks a `<V> V` getter and casts at its
   own call site (`hit.entity()`, `CgPlayers.player(id)`).
5. **Side is declared in the SPI's javadoc**, and every slot is provided from the era's one install, before
   `CgEngineLifecycle.preInit`: `PlatformService<Era>.install` for both sides, its client branch (modern:
   `installClient()`, from the loader's client side) for client-only ones. Never from a backend getter, which runs
   only at the first frame, nor from the network's installer, which owns the channel alone. A both-sides SPI's client
   half is handed over by a client-only class (`ClientModern.install()`), never by a lambda in a class a server loads.
6. **One host class per SPI per era**, named `<SPI without Cg><Era>` (`LevelsModern`, `Players1710`); a client
   extra is a subclass, never a second slot.

```java
// core
CgEntityQuery here = CgEntityQueries.client();        // over CgLevels.clientEntities(), never CgPlatform.get(CgLevels.HOST)
ServerPlayer p = CgPlayers.player(profileId);

// a host, in PlatformServiceModern.install, both sides
CgPlatform.provide(CgLevels.HOST, LevelsModern.INSTANCE);
// and in installClient
ClientModern.install();
```

---

## `CgPlatform` — the registry

`register(bundle)` is called **exactly once**, before any `core/` code runs. `resources()` answers `null`
before registration, so `CgIO` can fall back to the classpath during early boot. **Registration builds no
graphics**: a dedicated server has none, and making a device there is `NoClassDefFoundError` on LWJGL. The graphics
start's first stage calls `CgPlatform.startDevice()`, on a client's render thread; `serverSmoke` fails if a device was
installed (`CgRHI.installed()`).

```java
// a host's install, once per era: the bundle, then its slots (PlatformServiceModern.install, installClient)
CgPlatform.register(PlatformServiceModern.getInstance());                            // the bundle
CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(windowHandle));    // a slot, client only
```

## The device

Everything draws through the installed `CgRHI` (Unreal's RHI). A device's `install(compiler)` makes it the process's:
`CgRHI`, the `CgGlslCompiler` its pipelines compile with, and its `CgHostImports`.

| Host | Device | Started by |
|---|---|---|
| A GL-era Minecraft (1.7.10 to 26.3 on GL) | `CgGLDevice` in Minecraft's context, its host state the era's `GLHostState` | a `CgToolchainDevice` per era (`GLDevice1710`, `GLDeviceLegacy`, `GLDeviceModern`), added in `startDevice` |
| Minecraft 26.2+ on Vulkan | `CgVulkanDevice` hosted by Minecraft's (`HostedVulkanHost`) | `Blaze3dVulkanHost.start`, from `startDevice` |
| The harness | `CgGLDevice` owning the window's context, or `CgVulkanDevice` presenting to it (`--rhi=vulkan`) | `PlatformServiceHarness.onPreInit` |
| A test | `CgNullRHI`, or a real device over a hidden window | the test |

```java
new CgNullRHI(64, 64).install(new ShadercGlslCompiler());          // a test
device = new CgGLDevice(driver, translator, w, h, false, hostState);
device.install(new GLNativeGlslCompiler(device.driver()));         // a GL-era host, before the toolchain lands
```

- **Nothing draws before `startDevice`**, and a device that does not start fails the graphics start with its cause:
  there is nothing else to draw through.
- **The GL device floor is any LWJGL 3** on modern hosts: `Lwjgl3GLDriver` is compiled against 3.2.2, the oldest a
  client ships. A host windowed by SDL3 (26.3) gives the driver SDL's contexts (`Lwjgl3GLDriver(SdlWorkerContext::isolated)`).

### Host sections

A host section is a stretch of our work inside the host's frame: the host has control between them, and each is
bracketed where the host hands control to us and takes it back.

```java
CgDeviceSection.begin();   // the host hands us its frame
try {
    paint();
} finally {
    CgDeviceSection.end();   // and gets it back
}
```

- **Every host entry brackets itself**: `CgGraphicsLifecycle`'s stages, `onResize` and `tickFrame`, and
  `CgRenderStage.fire`, which the GUI stages go through; and the modern tree's world passes in `LifecycleModern`.
  Brackets nest; only the outermost pair reaches the device.
- **On GL, a host's cache is kept true at the section's edge, not per call.** `GLHostState` reads what the host
  believes at `fromHost` (Minecraft's `GlStateManager` cache, by each era's reader) and sets it back at `toHost`.
  **A domain it does not cover is a missing GL call, not an exception**: `-Dcrystalgraphics.host.verify=true` names it.
- **On a hosted Vulkan device** a section records into command buffers of our own from the host's pool, run in the
  host's submit after everything it recorded before; the close ends our pass and leaves the host's images where its
  tracking expects them (`HostedVulkanHost`).
- **A host's repair of its own state goes after the close**: it is the host's work, not ours.

### What a host owns

`CgHostImports` turns a host's texture handle (a GL name boxed, or a 26.2 image already wrapped) into a device texture,
and a host framebuffer's name into its attachments; `CgDeviceNames` gives each device texture and buffer the int the
engine knows it by.

```java
int lightmap = CgDeviceNames.name(CgHostImports.get().texture(hostHandle));
int main = vulkanHost.framebuffer(colour, depth);     // 26.2: Minecraft's main target, named for the graph
```

### The Vulkan device

- **Async compute** is a `CgCommandList` bracket (`beginAsync`/`endAsync`/`waitAsync`): dispatches, barriers and
  copies inside it go to the device's compute queue, and a draw, a host section's end or the frame's end inside it
  throws. `OwnedVulkanHost` takes a compute-only queue family (buffers and images then shared by both, and barriers
  on it kept to the stages it has), else a second queue of the frame's family, ordered by two timeline semaphores. A
  hosted device runs it on a compute queue its host made and never submits to (`HostedVulkanHost.computeQueue`:
  Minecraft 26.2 and 26.3's own), each async stretch submitted at once and waiting on a value the host's submit
  signals later; an async pass there refuses the host's images. On NVIDIA a second queue of the graphics family
  ran 0.3-0.6 ms slower than in order where the compute family saved 0.5 ms (`--mode=async-compute`):
  `-Dcrystalgraphics.vulkan.asyncCompute=graphics` forces it, `false` turns async off. GL runs it in order.
- **A copy into an image only the transfer queue has used runs there**, on devices with a family that only copies
  (owned: family 1 on NVIDIA; hosted: Minecraft 26.2 and 26.3's own transfer queue). A new image stays `UNDEFINED`
  until its first copy or its first sampling; any use on another queue keeps it off the transfer queue for good. So
  does a buffer copy or write between `beginTransfer` and `endTransfer` (the mesh store's uploads), whose caller
  promises it reads nothing the frame's queue writes and writes nothing a frame in flight reads. The batch is
  submitted, and the frame's queue waits for it, at the first work there touching such an image or buffer, at a pass,
  or at the frame's end. `-Dcrystalgraphics.vulkan.transfer=false` keeps every copy on the frame's queue.
- **BC7 images** are a block format (`CgFormat.block()` 4, sizes from `bytes(w, h, d)`), sampled and copied into
  only; Vulkan takes them where the device was created with `textureCompressionBC` (`CgVulkanHost`).
- **Memory**: a buffer the CPU reads (`CgGpuBuffer.Desc.hostReads`) is in memory the CPU caches, from a pool of its
  own; any other host-visible buffer may be write-combined, where reading 4 MB pixel by pixel took a second.
  Persistently mapped storage the CPU only writes (the frame ring, upload leases) and the uniform arena are
  `streamed` (`CgGpuBuffer.Desc.streamed`): system memory, since through a discrete GPU's BAR the CPU writes three
  times slower. Retained buffers stay where VMA puts them.
- **A draw, a dispatch or a barrier allocates nothing.** Uniform uploads come from `CgFrameArena`, and the device
  fills what it records per call in `VulkanScratch`, through a `ByteBuffer`, for LWJGL's raw `n` functions: on Java 25
  LWJGL 3.4's struct setters write through FFM, and each write C2 does not inline builds a `MemorySegment`. The
  desktop took 27% less CPU a frame for it.
- **Pipelines are built ahead from the recorded list** (`CgPipelineRecipes`, `CgPipelinePrecache`; Unreal's bundled
  PSO cache): every pipeline a draw built is written down by its program's key and the rest of its description, into
  the jar's `assets/crystalgraphics/pipelines/recorded.txt` and the user's `crystalgraphics/cache/pipelines/recorded.txt`.
  To ship a gate's list, copy its `recorded.txt` over the jar's.

`DevicePipelinesTest` (`runtime/lwjgl/vulkan`) builds every shipped program's pipelines on both devices.

---

## The engine's lifecycle is called, not provided

A host tells the engine what happened by calling it directly: no slot stands between, since no host decides
anything there. All on the GL thread:

| Call | From |
|---|---|
| `CgGraphicsLifecycle.tickFrame()` | the frame end, **once per rendered frame**, GUI-only frames included: 1.7.10's and legacy Forge's `CgRenderHook` at `updateCameraAndRender` TAIL; modern `FrameHooks.endFrame()`, from `LifecycleModern.frameEnd()` after the GUI (`runtime/mc/modern/common/CLAUDE.md` § *The frame end*); the harness's `PlatformServiceHarness.endFrame()` |
| `CgGraphicsLifecycle.onResize(w, h)` | the resize and fullscreen hooks (`MixinMinecraft`); modern `FrameHooks`' end-of-frame check |
| `CgEngineLifecycle.shutdown()` | the shutdown hook, while the context is whole |
| `CgAssetReloader.reload()` | each host's resource reload: `HostResources1710.listenForReloads()`, `HostResourcesLegacy.listenForReloads()`, `LifecycleModern.reload()` |

**`tickFrame()` is the only sanctioned per-frame tick** for engine singletons (`CgFontRegistry.tickFrame()`);
feature code never calls it. The hosts fire the world's render stages from their own hooks
(`CgRenderStage.WORLD_OPAQUE`, `WORLD_TRANSPARENT`); the window's size is `CgHostWindow`'s.

---

## Dependencies

`platform/` imports no LWJGL, Minecraft or loader type. log4j-api is `compileOnly` at 1.7.10's version:
exporting it would put that version on every consumer, and NeoForge requires `log4j-api` strictly 2.19.0.
Every host supplies its own.

---

## UI-facing services

Some slots exist for CrystalGUI rather than CrystalGraphics' own rendering. They sit here, in the parent
project's SPI, because CrystalGraphics is always present wherever CrystalGUI is: a second registry in the
child project would be one a loader could forget, leaving a working device beside a dead keyboard.

| Reached via | Provides |
|---|---|
| `CgInput` | what is held now, and a toolkit's codes in the engine's: CrystalGUI reads it through `PlatformPort` |
| `CgClipboard` | the clipboard's text |
| `CgPlatform.get(CgCursorService.SERVICE)` | presenting a cursor picture; CrystalGUI decides which |
| `CgWorldQueries.client()`, `CgEntityQueries.client()` (core) | the client level's blocks and entities, over `CgLevels`; `NONE` with no level, on a server and in the harness |
| `CgCamera` (core) | a write a host opts into: a camera offset; `NONE` ignores it |

**A sound by name is not a platform service** but the engine's own: `audio.CgSounds.play2D(name)` (unplaced, the
`UI` class) or `playAt`, a host event read from the packs' `sounds.json` through `CgHostResources`. Minecraft's sound engine is never
called.

### Absent is a configuration, not an oversight

**A slot's absent-value is mandatory**: it exists for a capability whose absence is legitimate. A dedicated server
has no keyboard, a headless test no clipboard, the harness no resource packs, and none of them has to register a
stub to stay quiet: `NONE` answers nothing held, an empty clipboard, no pack. `CgService.get()` announces an absence
once, on first read, so "nobody provided it" and "somebody chose the no-op" stay distinguishable in a log.

**The bundle's one method defaults to nothing**: `startDevice` is a client's, and a server or a headless test starts
no device.

### Java level

An abstract module, like `:core`: Java 25 source, plus a Java 8 copy (`downgradedJar`) that every
consumer below 25 resolves (`cgbuildlogic.abstractModule`). Modern syntax is free; **a newer API is not
checked** — jvmdg stubs what it can, and a call it cannot stub fails on a Java 8 instance. The shipped
jar is downgraded whole, once.
