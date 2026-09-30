# runtime/mc/modern/common — Agent Knowledge Base

The modern-era code that is no loader's: the `common` branch of the Stonecutter tree, built once per
Minecraft version (`:runtime:mc:modern:common:<version>`). Each loader node compiles against the common
node of its own version through the `commonOutput` configuration; `cg-modern-common.gradle.kts` picks
the toolchain from the node's pins (`docs/BUILD.md` § *Nodes and toolchains*).

**No loader type appears here** — a Forge, NeoForge or Fabric import compiles against one loader and is
used by three.

| Package | AGENTS.md | What it contains |
|---|---|---|
| `com.crystalgraphics.mc.modern.platform` | [platform/AGENTS.md](src/main/java/com/crystalgraphics/mc/modern/platform/AGENTS.md) | `PlatformServiceModern`, `Blaze3dGLBackend`, `HostStateVerifier`, the services, and `LifecycleModern` — the one class a loader talks to |

The world-render hooks are loader events wherever one exists; the exceptions are node mixins in the
loader branches (Forge 1.21.3+, Fabric 1.14.4–1.15.2), never here.

## Key design points

- **No GL in constructors.** GL work waits for the first frame that owns the render context.
- **Mixin AP comes from the toolchain.** A second `annotationProcessor` for Mixin produces duplicate-AP
  SRG mapping errors.

## The frame end

Every loader calls `LifecycleModern.frameEnd()` once a frame, **after the GUI** — a title screen
included — and that is where `FrameHooks.endFrame()` and so `CgLifecycleListener.onFrame` run. The
transparent pass does not end the frame: a GUI-only frame takes `GameRenderer.render`'s screen branch and
has no world pass at all.

| Loader | Frame end |
|---|---|
| Forge | `TickEvent.RenderTickEvent` at `END`; its `Post` from 1.20.4, on its own `BUS` from 1.21.6 |
| NeoForge | `TickEvent.RenderTickEvent` at `END` (1.20.2–1.20.4), `RenderFrameEvent.Post` (1.20.6+) |
| Fabric | node mixin `FrameEndHook`, `GameRenderer.render` TAIL — Fabric has no frame event |

On 26.1+ `onFrame` is also **the last point to draw over the host's picture**: the GUI is extracted
before the level renders, so anything painted from a GUI hook lands under the world. CrystalGUI paints
its desktop and HUD from there on those nodes.

## 26.1+: the main target, and 26.2 under Vulkan

- **Our own framebuffer over Minecraft's main target.** 26.1 made `GlDevice` package-private and 26.2
  dropped `GlTexture.getFbo`, so `LifecycleModern.bindMainTarget` builds one FBO through `CgGL`, attached
  as Minecraft's `FrameBufferCache` attaches it (colour and depth, level 0), keyed on the two texture
  ids and deleted on teardown. The main target is `mc.getMainRenderTarget()` on 26.1 and
  `mc.gameRenderer.mainRenderTarget()` on 26.2.
- **Reversed-Z on 26.2, on OpenGL too.** Minecraft 26.2 sets clip control to `ZERO_TO_ONE` once at
  device init, swaps near and far in its projection, clears depth to 0 and tests `GREATER_THAN_OR_EQUAL`.
  The world passes run with `CgGL.setDepthReversed(true)`, which mirrors every compare function and clear
  value a caller writes (`LifecycleModern.worldDepth`), so no material changes. Our own drawing -- the
  CrystalGUI desktop and shader-graph previews -- runs between `OwnDepthConvention.enter()` and `leave()`,
  which put GL's default clip range and a clear depth of 1.0 back for its duration. `cg_DepthBuffer` holds
  reversed values there, so a shader comparing against it is wrong on 26.2; polygon offset pulls the other
  way. Every piece is a no-op below 26.2.
- **Vulkan.** 26.2 can run Blaze3D on Vulkan, and then `CgGL` runs on the tracked backend over a
  `CgVulkanDevice` hosted on Minecraft's own device (`vulkan.Blaze3dVulkanHost`, device-seam D5). Which
  API is running is `GraphicsApi.vulkan()`, read off Minecraft's device at the first host section;
  `PlatformServiceModern.gl()` builds the matching backend there. Each host section records into command
  buffers of its own from Blaze3D's per-submit pool and hands them to Minecraft's submit at `toHost`;
  `bindMainTarget` imports the main target's images, which our passes leave in `GENERAL`; the frame closes
  in `FrameHooks.endFrame`, before Minecraft's submit. The GL-only repairs stand aside: Blaze3D's texture
  units, `HostStateVerifier`, `OwnDepthConvention`'s clip control, CrystalGUI's `CgUiHostGl.leave`.
  `LifecycleModern.canRender()` stands the engine down (`CgGraphicsLifecycle.standDown`) only when the
  hosted device cannot be built, with the cause in the log; on GL it asks GLFW for a current context, and
  from 26.3, which has no GLFW, takes a device that is not Vulkan as GL. 26.3 under Vulkan stands down for
  now: Forge's world hooks run inside the render pass Minecraft opens for terrain, where Blaze3D refuses our
  submit, and the 26.3 bring-up settles them (plan platform-transparent-pass). `-Dcrystalgraphics.host.verify=true`
  checks every hand-over. At the shutdown signal our device's close is queued on Blaze3D's destroy queue,
  which runs it once our last submit has completed or inside `VulkanDevice.close`, before the device goes.
- **SDL3 from 26.3.** 26.3 ships no GLFW: `PlatformServiceModern` registers `runtime/lwjgl/sdl`'s
  `SdlInputService` and `SdlCursorService` there, chosen per node with `//? if >=26.3`, since a 26.3 node
  cannot load a GLFW class at all.
