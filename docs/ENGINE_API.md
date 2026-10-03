# The engine's API — stages, the world, resources, state, lifecycle

Moved from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs. Loads itself, imported by the `CLAUDE.md` of the packages it describes: `api`, `gl`, `render`, `gpu` and `util`.

### Render stages — drawing into a host's frame

`CgRenderStage` is a point in a host's frame. A renderer registers on one and records into the stage's frame
each time it fires; the frame executes on the host's target at once. CrystalGraphics defines
`WORLD_OPAQUE` and `WORLD_TRANSPARENT` and every host fires them; a mod defines its own.

```java
// Drawing at a stage
CgRenderStage.Registration drawing = CgRenderStage.WORLD_OPAQUE.register(0, frame -> {
    CgPassConstants camera = frame.defaults(new CgPassConstants());   // time, size, depth convention
    camera.view.set(view);
    camera.projection.set(projection);
    CgRasterPass pass = frame.pass(camera, CgOrder.SORTED);
    // ... chunks into pass ...
    pass.end();
});
drawing.close();                                                      // stops it

// A stage of your own: fill its frame, then fire it from your hook on the render thread
public static final CgRenderStage AFTER_SKY = CgRenderStage.define("mymod:after_sky");
AFTER_SKY.host().set(partialTick, width, height, mainFramebufferId)
        .view().set(camX, camY, camZ, viewRotation, projection);
AFTER_SKY.fire();

// Anywhere on the render thread: the world's camera as of its latest frame
CgHostView world = CgRenderStage.WORLD_OPAQUE.host().view();
```

- Each stage owns its `CgHostFrame`, refilled by the host before every `fire` (nothing is allocated). Its
  `CgHostView` is the host's own camera: the absolute position in doubles, and the view and projection the host
  draws with. Minecraft draws its world camera-relative, so the view maps a point minus that position.
- Every host captures Minecraft's camera where that version computes it; the per-era sources are
  `HostView1710`, `HostViewLegacy` and `HostViewModern`.
- Beside the camera each frame carries the world's facts: `CgHostEnvironment` (`frame.environment()`) — the sun,
  moon, stars and daylight, weather and lightning, the dimension's sky, fog where the host keeps it on the CPU (1.17.1 to
  1.21.1), the fluid the camera is in, perspective, FOV and render distance, the player's sight effects, their particles,
  graphics and accessibility settings (an effect spawns `particleShare()` of its particles), and the game clock, paused
  and `/tick` state. Absent is NaN, -1 and false, never a default. Questions about a *position* — collision and its
  boxes, fluid, light, what a block is made of, tint and biome colours, heightmaps, precipitation — are the
  `CgWorldQuery` slot's, composed into ground scans and a raycast by `com.crystalgraphics.world.CgWorldQueries`. A GUI stage carries the GUI's projection in its own frame,
  so the world's camera stays readable while the host draws its GUI.
- Every era answers the environment, the host textures and the world slots (`CgWorldQuery`, `CgEntityQuery`,
  `CgHostCamera`, `CgWorldSound`, `CgWorldEvents`), each in its host's `platform.world` package: `*1710`, `*Legacy` and
  `*Modern`. What a version cannot answer stays absent, and each class's javadoc says what.
- `fire` is the whole entry: it opens the host section, starts the engine if nothing has, times the stage
  (trace zone and GPU timer, named by the id's path) and does nothing after a teardown.
- An id is defined once (`define` throws on a second); renderers record in ascending order, ties in
  registration order; any thread may register.
- `frame.callback(name, body)` draws immediately at its place in the stage, for work not yet recorded.

### CgWorldRenderer — drawing into the world

`CgWorldRenderer` draws meshes at both world stages under the host's camera. A draw is submitted at an absolute
position in doubles and made camera-relative at record time, as Minecraft draws; it lives for the frame it was
submitted in.

```java
CgWorldRenderer world = CgWorldRenderer.get();
world.onFrame(view -> {                                // once a frame, before the world records
    world.draw(mesh, material)                         // scratch: build and submit in one expression
         .at(x, y, z)                                  // absolute, in doubles
         .transform(rotationScale)                     // optional, about that position
         .custom(0, r, g, b, a)                        // CG_OBJECT_CUSTOM0
         .submit();
});
world.draw(pane, glass).at(x, y, z).queue(CgRenderQueue.TRANSPARENT).submit();   // overrides the material's queue

// Part of a mesh, geometry with no vertex data, and bounds the draw states itself:
world.draw(CgMesh.quads(capacity), sparks).indices(0, live * 6).at(x, y, z).bounds(-1, -1, -1, 1, 1, 1).submit();
world.draw(model, brass).submesh(1).at(x, y, z).submit();
world.draw(billow, smoke).at(x, y, z).transform(scale).pad(0.4f).submit();   // grown for a displacing shader

// How much draws comes from a count a kernel wrote: a quad per live spark, a billow per live puff
world.draw(CgMesh.quads(capacity), sparks).indirect(alive, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
world.draw(billow, smoke).indirect(alive, 0, CgIndirect.INSTANCES, 1).at(x, y, z).bounds(box).submit();

// A level per screen height (CgMeshLods, Unity's LODGroup): picked per draw at record time
world.draw(CgMeshShapes.sphereLods(), smoke).at(x, y, z).transform(scale).submit();
```

- A draw of `CgMeshLods` takes the level for the screen height its bounds cover, and none below the last level's.
- **Culled** against the view by the draw's stated bounds, else its mesh's, either grown by `pad`, and **sorted**
  (`CgSortKey`): first by `CgSortLayer` (Unity's sorting layers: `BACKGROUND`, `DEFAULT`, `EFFECTS`, `OVERLAY`, and any
  defined `before`/`after` one), then opaque by material, front to back, then mesh; transparent back to front, a
  `.group(x, y, z)` sorting as one at its position (Niagara's system; every VFX effect is one) and its draws by their
  `.order(0..15)` within it. Equal neighbours instance.
- `WORLD_OPAQUE` records a prepass (materials with a depth pass, and alpha-tested ones) and the opaque pass;
  `WORLD_TRANSPARENT` the transparent pass. Each declares `sceneDepth`/`sceneColor`, so the graph copies the target
  for a reader only where one draws.
- Shaders see **camera-relative** world space: `CG_CAMERA_WORLD_POS` is the origin, and `CG_ABSOLUTE_WORLD_POS(p)`
  adds `cg_WorldOrigin` back for an effect that must not move with the camera.
- A host drawing the world twice in a frame (1.7.10's anaglyph) fires both stages twice; each draw is drawn under
  each firing's view.

**Object record** (`CgInstanceKind.OBJECT`, STD430, 48 floats): `modelMatrix` 0–15, `normalMatrix` 16–31 (the
shader reads its 3×3), `custom0`–`custom3` 32–47.

**An immediate object draw** — a preview, a harness scene — goes through `CgImmediate` with its own pass
constants:

```java
CgPassConstants camera = new CgPassConstants();
camera.view.set(view);
camera.projection.set(projection);
camera.resolution(w, h).time(CgFrameClock.seconds()).cameraFromView();
try (CgImmediate draw = CgImmediate.begin(camera)) {
    draw.chunks().draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(draw.bindings()), mesh);
    int at = draw.chunks().instance();
    model.get(draw.chunks().data(), at);                       // then the normal matrix at +16, custom at +32
}
```

## Core Framework Systems

### Framebuffers

`CgFrameBuffer` is the unified FBO abstraction. Create it via `CgFrameBufferFormat` builder — the format describes all attachments, the FBO dispatches through `CgGL`.

```java
CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("my_fbo")
        .color(0, CgTextureType.RGBA8)            // slot 0 → texture attachment
        .color(1, CgTextureType.RGBA16F)           // slot 1 → texture attachment
        .depth(CgTextureType.DEPTH24_STENCIL8)     // depth → texture attachment
        .depthRbo(CgTextureType.DEPTH24_STENCIL8)  // (or: depth → renderbuffer)
        .build();
CgFrameBuffer fbo = CgFrameBuffer.create("my_fbo", width, height, fmt); // or CgFrameBuffer.createScreenSized("my_fbo", fmt);
fbo.bind();     // ... render ...
fbo.unbind();
fbo.delete();   // only valid on owned FBOs; CgFrameBuffer.wrap() creates non-owned
```

`CgFrameBufferRegistry` — cache for screen-sized FBOs that auto-resize on window resize.

**Package guides**: `api/framebuffer/CLAUDE.md` · `gl/framebuffer/CLAUDE.md`

### Textures

```java
CgTexture2D tex    = CgTexture2D.create("mymod:textures/foo.png");
CgTexture2D hdr    = CgTexture2D.createEmpty(512, 512, CgTextureSpec.RGBA16F_LINEAR);
CgTexture2D shadow = CgTexture2D.createEmpty(512, 512, CgTextureSpec.DEPTH24_SHADOW);  // PCF
tex.bind(0);   // bind to texture unit 0
tex.delete();
```

`CgTextureType` — typed enum of ~42 GL format constants; single source of truth for (internalFormat, baseFormat, type). `CgTextureSpec` — immutable `@Builder` describing format + filter + wrap + optional shadow compare. `CgMipmapConfig` — `NONE` / `TRILINEAR` / `NEAREST`. Concrete impls: `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`.

**Package guides**: `api/texture/CLAUDE.md` · `gl/texture/CLAUDE.md`

### Meshes

**A mesh is data** (`api/mesh/CgMesh`): built and edited on any thread, with no GPU in it. The graph and the world
renderer draw it from `render/mesh/CgMeshStore`, which keeps every mesh's copy in pooled slabs per vertex format,
uploads what changed before a frame's first pass, and draws with base-vertex calls (plan `mesh-rewrite`).

```java
CgMesh tri = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
    int a = m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0).end();
    int b = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).end();
    int c = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).end();
    m.triangle(a, b, c);
});
CgMesh ball = CgMeshShapes.sphere(24, 32);              // shared: one per format and size
world.draw(ball, material).at(x, y, z).submit();
ball.release();                                          // own meshes only; the GPU copy goes once frames retire

// Rewritten every frame: on the frame ring, nothing to release; reserve() keeps its edits from allocating.
// The GPU reads the ring more slowly than a slab, so a mesh drawn many times a frame stays DYNAMIC.
CgMesh trail = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> {});
trail.reserve(2 * maxPoints, 0);
trail.edit(points, Trail::write);                        // each frame, before it draws

// No vertex data: the shader (#type none) places each vertex from CG_VERTEX_ID
CgMesh sparks = CgMesh.quads(1024);                      // corner CG_VERTEX_ID & 3, quad CG_VERTEX_ID >> 2
CgMesh bolt = CgMesh.vertices(64, CgMeshTopology.TRIANGLE_STRIP);

// From a file: every OBJ material group and glTF primitive is a submesh, with the material it names
CgMeshLoader.Model ship = CgMeshLoader.model("mymod:models/ship.glb", CgVertexFormat.SPATIAL);
for (int i = 0; i < ship.mesh().submeshCount(); i++) {
    world.draw(ship.mesh(), materialNamed(ship.material(i))).submesh(i).at(x, y, z).submit();
}
```

**Package guides**: `api/mesh/CLAUDE.md` · `render/CLAUDE.md` (`mesh/`)

### Vertex Formats

`CgVertexFormat.SPATIAL` — the canonical format for spatial materials: `cg_Position` (vec3) + `cg_TexCoord0` (vec2) + `cg_Normal` (vec3), stride 32 bytes. It is the format `CgMeshShapes` builds when asked for none. Two formats with identical attribute lists are value-equal.

**Per-instance data is never a vertex attribute**: it is an engine buffer's record (`CgInstanceKind` -- `OBJECT`, `QUAD`, `CURVE`), read through `CG_INSTANCE_ID`.

**Package guides**: `api/vertex/CLAUDE.md`

### Shader Buffers

Attach user-owned SSBO/TBO or UBO blocks to a material. The engine injects GLSL declarations automatically on the next compile.

```java
// SSBO/TBO — access via macro in shader: GLYPH_DATA(n).advance
CgBufferFormat fmt = CgBufferFormat.builder("GlyphMetrics", STD430)
        .vec4("bbox").vec2("uv0").float_("advance").build();
CgShaderBuffer buf = CgShaderBuffer.create("GlyphMetricsBuffer", fmt, 0);   // RETAINED: written once
material.attach(buf, "GLYPH_DATA");     // macroName must be ^[A-Z][A-Z0-9_]*$
buf.bind();                              // caller's responsibility before each draw
material.detach("GLYPH_DATA");

// Rewritten every frame before the draws that read it: the frame ring
CgShaderBuffer particles = CgShaderBuffer.create("Particles", particleFmt, 1, CgBufferLifetime.FRAME);
particles.beginWrite(n);
// ... n records ...
particles.endWrite();                   // this frame's region, re-bound there

// UBO — flat scope, direct field name access in shader: ambientColor (no prefix)
CgBufferFormat sceneFmt = CgBufferFormat.builder("SceneParams", STD140)
        .vec4("ambientColor").float_("exposure").build();
CgUniformBuffer ubo = CgUniformBuffer.create(sceneFmt, "SceneParams", 0, CgBufferLifetime.FRAME);
material.attach(ubo);                   // no macroName — UBO is a single instance
ubo.upload();                           // before every draw that reads it; a compare when nothing moved
material.detachUbo("SceneParams");
```

**Lifetime, not type, decides where a shader buffer lives** (`CgBufferLifetime`, passed to every factory; the ones
without it mean `RETAINED`). `FRAME`: uploaded in every frame that reads it — the frame ring, no orphan and no
driver rename. Any shader buffer's upload of at most 4 KB equal to the last one is skipped — for a `FRAME`
buffer, once this frame already has it. `RETAINED`: readable
until the next upload however many frames later — orphaning storage at offset 0. A `FRAME` buffer read in a frame
that did not upload it reads another frame's bytes, with no error. Every buffer the engine owns is `FRAME`: the
quad and curve instances, the object buffer, the material blocks (uploaded at every bind), the frame block
(copied in at a frame's first material bind) and the text block. A TBO takes `RETAINED`'s storage whatever is
asked. `gl/buffer/CLAUDE.md` has the tiers.

Do NOT attach the engine's own blocks (`CgFrameBlock`, `CgObjectDataBuffer`) — declared in `cg_env.glsl`, wired automatically. Duplicate declarations cause compile failure.

**Package guides**: `api/buffer/CLAUDE.md` · `gl/buffer/shader/CLAUDE.md`

## Infrastructure

### GL State Save/Restore

Wrap any block of GL work in a `CgGlScope` to guarantee state restoration on exit — even on exceptions. Always save only the slots you intend to modify.

> Imports come from **`com.crystalgraphics.platform.gl.state`** (`CgGlState`, `CgGlScope`, `CgGlSlot`); `CgGlStateManager` is in `platform.gl`.

```java
// Save specific slots:
try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
    fbo.bind();
    shader.bind();
    // draw
}  // FBO and PROGRAM restored automatically on close

// Convenience shorthands:
CgGlState.saveProgram()   // → save(PROGRAM)
CgGlState.saveFull()      // → save(FBO, PROGRAM, TEXTURES, VERTEX_INPUT)
CgGlState.saveAll()       // → every slot (used by CgExecutor around a frame)
```

`CgGlSlot` constants: `FBO` · `PROGRAM` · `TEXTURES` · `VERTEX_INPUT` · `BLEND` · `DEPTH` · `CULL` · `STENCIL` · `COLOR_MASK` · `VIEWPORT` · `SCISSOR` · `POLYGON_OFFSET` · `ALPHA_TEST` · `LINE_WIDTH` · `POLYGON_MODE` · `POINT_SIZE`, and three **captured at first write** — `STORAGE_BUFFERS` · `IMAGES` · `INDIRECT_BUFFERS`: a scope declaring them reads nothing when it opens, saves each binding point the first time it is written inside it, and restores only those (`gl/state/CLAUDE.md`)

**Package guides**: `api/state/CLAUDE.md` · `gl/state/CLAUDE.md`

### Capabilities

`CgCapabilities.detect()` — cached per context; **throws below OpenGL 3.3**. Above the floor it answers `shaderBufferPath()` (SSBO → TBO, forceable with `-Dcrystalgraphics.shaderBuffer.tier=<path>`), `vertexStreamTier()` / `shaderStreamTier()` (the stream-buffer waterfall, see `gl/buffer/CLAUDE.md`), `isCopyImageSubDataSupported()`, the limits (`getMaxDrawBuffers()`, `getMaxTextureUnits()`, …) and `isCoreProfile()`. For compute and GPU-driven draws it answers what a consumer needs — `compute()`, `storageImages()`, `subgroups()`, `floatAtomics()`, `drawIndirect()`, `multiDrawIndirect()`, `indirectCount()`, `drawParameters()`, `feedbackCount()`, `asyncCompute()`, `bindless()` — each joined from a core version, its ARB extension and, on the tracked backend, the device; and `computeTier()` (`V` · `G43` · `G40` · `G33` · `CPU`), forceable with `-Dcrystalgraphics.compute.tier=<tier>`, which throws naming what a context lacks.

**`CgGpuReport`** is the full answer, for diagnosis rather than decisions: every feature a compute or draw tier is chosen
from (`core`, the extension that gives it, or `no`) and the limits that bound it, from the driver on GL and from
`CgDevice.describe` on the Vulkan device. Each context logs it once as `[crystalgraphics] gpu …`, `prodSmoke`
gathers every client's into `build/prodSmoke/gpu-report.txt`, and the harness's `--mode=capability-report` prints it
as a table.

Nothing core in 3.3 has an ARB or EXT fallback; what is above it (SSBO, `glCopyImageSubData`) keeps its gate and its fallback. **A 3.2 context with 3.3's extensions passes**: vanilla 1.17–1.21.4 asks for 3.2 core and NVIDIA returns exactly that, so Fabric and pre-early-window Forge run on one. On it LWJGL 3 loads no 3.3 entry point, which is why `Lwjgl3GLBackend.glVertexAttribDivisor` falls back to the ARB name.

### Render State

Pre-defined constants cover the common cases:

```java
CgRenderState.DEFAULT          // blend OFF, depth TEST_WRITE, cull BACK, stencil DISABLED
CgDepthState.TEST_WRITE        // depth test LEQUAL + depth write ON
CgDepthState.TEST_ONLY         // depth test LEQUAL + depth write OFF
CgBlendState.ALPHA             // SRC_ALPHA / ONE_MINUS_SRC_ALPHA
CgBlendState.ADDITIVE          // ONE / ONE
CgCullState.BACK               // GL_BACK face culling
```

`state.apply()` / `state.clear()` — always bracket render work to prevent GL state leaks.

**Package guide**: `api/state/CLAUDE.md`

### Instanced renderers

Everything 2D draws through `CgQuadRenderer` (quads: UI boxes, glyphs, blits) or `CgVectorRenderer` (strokes, triangles, cells): records queued on the CPU, turned into recorded draws by `CgInstanceRun` -- into the recording inside one, through `CgImmediate` on `flush()` outside one. `CgTextRenderer` owns a `CgQuadRenderer`.

**Package guides**: `gl/render/CLAUDE.md` · `gl/buffer/staging/CLAUDE.md`

### Font/Text System

- **Canonical docs**: `docs/font/README.md` (entry point) · `docs/font/api-guide.md` (usage) · `docs/font/architecture.md` (package boundaries)
- **Public entry points**: `api/font/` · `api/text/`
- **Internal**: `text/layout/` · `text/cache/` · `text/atlas/` · `text/msdf/` · `text/render/` · `text/font/` (font files without natives: `.ttc` faces, names, coverage; the per-script fallback tables)
- **This jar ships no fonts.** Text a caller's own fonts cannot draw falls back to the installed ones through `CgSystemFonts` / `CgFontFamily.withFallback`; tests read `core/src/test/resources/fonts/`.

Do not rely on older font/text notes outside the `docs/font/` set — that is the current source of truth.

---

### Resource I/O — `CgIO` and `CgTextureIO`

#### `CgIO` — Universal Resource Loader

`util/io/CgIO` is the single entry point for loading any text-based asset (shaders, config). Every part of the engine that reads a file calls it. It resolves paths through a waterfall:

| Priority | Strategy | Condition |
|---|---|---|
| 0 | Absolute filesystem path | Path is an absolute file |
| 1 | Filesystem override dir | `-Dcrystalgraphics.shader.resourceOverrideDir` is set |
| 2 | Minecraft resource manager | `IResourceManager` available (in-game) |
| 3 | Classpath | Final fallback (always works in harness + tests) |

**Accepted path formats** — all normalized to `/assets/{domain}/{rest}` internally:

```
mymod:shaders/terrain.shader         → /assets/mymod/shaders/terrain.shader
crystalgraphics:shaders/lib/math.glsl → /assets/crystalgraphics/shaders/lib/math.glsl
/assets/mymod/shaders/foo.vert       → unchanged
shader/foo.vert                      → /assets/crystalgraphics/shader/foo.vert  (default domain)
```

Key methods:
- `CgIO.loadSource(path)` — returns UTF-8 string or `null` on failure (used by shader preprocessor, material loader)
- `CgIO.openStream(path)` — returns raw `InputStream` or `null`
- `CgIO.normalizePath(path)` — path normalization only, no I/O
- `CgIO.toResourceLocation(path)` — converts any supported format to MC `ResourceLocation`

#### `CgTextureIO` — Image Loader

`util/io/CgTextureIO` decodes image files into direct `ByteBuffer`s ready for GL upload. Path resolution delegates to `CgIO.openStream()`. Returns `CgImageData(pixels, width, height, channels)` or `null` on failure — never throws. Channel count (1/3/4) is preserved from the source image so callers can derive the correct `GL_RED`/`GL_RGB`/`GL_RGBA` upload format. Pixels are bottom-left row order (GL convention).

Also owns `CgTextureIO.createFallback()` — generates the purple/black 8×8 checkerboard texture used when a texture fails to load.

---

## Lifecycle & Registries

### CgGraphicsLifecycle

The single coordination point for GL context init and teardown. **Call these and nothing else** — do not free individual registries manually.

```java
// On GL context creation (GL thread):
CgGraphicsLifecycle.initContext(viewportWidth, viewportHeight);
// Initialises: CgBindingPoints, CgFrameBufferRegistry, CgFallbackTextures, and installs CgWorldRenderer

// On window resize (GL thread):
CgGraphicsLifecycle.onResize(newWidth, newHeight);
// Triggers recreation of all screen-sized FBOs in CgFrameBufferRegistry

// On GL context destruction (GL thread):
CgGraphicsLifecycle.destroyContext();
// Runs the canonical teardown sequence (see below)

// About to draw OUTSIDE a world pass — a menu, a title screen, any GUI (GL thread):
CgGraphicsLifecycle.ensureContext(width, height);
// Initialises once, resizes if the viewport moved, no-ops after destroyContext()
```

> **`ensureContext` exists because the engine initialises on the first WORLD render**, which is right
> for anything drawn in a world and wrong for everything else. On a title screen no world pass ever
> runs, so `isInitialized()` stayed false for the life of the process and a UI that politely checked
> before painting drew nothing at all — and because Minecraft only clears the colour buffer when it
> renders a level, the frame still held the previous screen. A screenshot then came back showing the
> main menu, which is indistinguishable from a working UI that was simply not asked to draw. Any
> `Screen` that paints through this engine calls it first; `CgRenderStage.fire` calls the same method, so
> there is one definition rather than two.

**Canonical teardown order** (enforced inside `destroyContext()`):

| Step | What | Why |
|------|------|-----|
| 0 | `CgTextRendererRegistry.get().deleteAll()` | Any `CgTextRenderer` still alive (backstop — individual owners should already have called `delete()`) |
| 1 | `CgMeshStore.get().releaseAll()` | The store's slabs: each VAO, then its buffers. Meshes keep their data and are placed again by the next context, but for a `GPU_ONLY` one, which needs an edit first |
| 2 | `CgQuadIndexBuffer.freeAll()` | Shared quad IBO |
| 5 | `CgTextureManager.get().freeAll()` | All cached textures + fallback |
| 5c | `CgFontRegistry.get().releaseAll()` | Glyph atlas textures + background generation executor, reset in place (reusable immediately) |
| 6 | `CgMaterialRegistry.get().deleteAll()` | Material instances + GL shader programs |
| 6b | `CgCompute.releaseAll()` | Kernel programs and their blocks; the parsed files stay for the next context |
| 7 | `CgShaderBufferRegistry.get().deleteAll()` | User SSBO/TBO/UBO resources |
| 8 | `CgWorldRenderer.get().release()` | Its draws, and the depth snapshot's reference (the framebuffer is freed by step 9) |
| 8b | `CgPreviewPool.deleteAll()` | Shader-graph preview targets, thumbnails and main previews. **Context-owned, not renderer-owned** — their storage is made by the executor outside any registry, so nothing below reaches it. Before step 9, since a target holds framebuffers |
| 9 | `CgFrameBufferRegistry.get().deleteAll()` | All owned FBOs |

> A slab deletes its VAO **before** its buffers: a VAO naming deleted buffers is stale GPU state.

> **The backend closes last, and not in `destroyContext()`.** Every deletion above goes through it, and a
> device releases memory when its frames retire, so whoever built a device-backed backend closes it after:
> the harness closes `CgVulkanDevice`, then its `OwnedVulkanHost` (`PlatformServiceHarness.shutdown`). A GL
> context dies with its window.

### Registry Overview

All registries are **singletons accessed via `.get()`**. You normally interact with them through the high-level API (e.g. `CgMaterial.load()`), not directly. Know they exist for debugging and teardown.

| Registry | Singleton | What it owns | When to call directly |
|----------|-----------|-------------|----------------------|
| `CgMaterialRegistry` | `CgMaterialRegistry.get()` | All `CgMaterial` instances (per-instance UBOs) | `reloadAll()` on hot-reload; `deleteAll()` on teardown (via lifecycle) |
| `CgMaterialShaderRegistry` | `CgMaterialShaderRegistry.get()` | Shared `CgMaterialShader` GL program assets | Internal — managed by `CgMaterialRegistry` |
| `CgTextureManager` | `CgTextureManager.get()` | All `CgTexture` instances (2D, array, 3D, cubemap) | `getOrCreate(path)` for cached texture load; `reloadAll()` on F3+T |
| `CgFrameBufferRegistry` | `CgFrameBufferRegistry.get()` | Screen-sized FBOs that auto-resize | `getOrCreate(name, format)` for screen-sized FBOs |
| `CgShaderBufferRegistry` | `CgShaderBufferRegistry.get()` | User-attached SSBO/TBO/UBO objects | `deleteAll()` on teardown (via lifecycle) |
| `CgFontRegistry` | `CgFontRegistry.get()` | Glyph atlas textures (bitmap/MSDF/MTSDF) + background generation executor | `releaseAll()` on teardown (via lifecycle); parameterized constructors remain public for harness testing of custom atlas sizes/configs — see `text/cache/CLAUDE.md` |
| `CgTextRendererRegistry` | `CgTextRendererRegistry.get()` | Tracks every `CgTextRenderer` for teardown; auto-resizes screen-sized ones (`create()`, the default — opt out via `createManualSized()`) on `onResize()` | Does not own renderer *lifecycle* the way other registries do — owners still call `delete()` themselves; `deleteAll()` on teardown is a backstop, not the primary path — see `text/render/CLAUDE.md` |
