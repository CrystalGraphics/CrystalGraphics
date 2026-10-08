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
  1.21.1), the fluid the camera is in, perspective, FOV and render distance, whether a screen is up, the player's sight effects, their particles,
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
- **`frame.resources()` is the firing's blackboard**: a renderer publishes a resource under a static `CgFrameKey`
  and one recording later in the same firing reads it, as Filament's blackboard does. It empties when the firing
  executes, so a hand-off across firings (opaque to transparent, 1.7.10's second anaglyph firing) needs a resource
  that outlives the frame. The engine's keys are `CgFrameKeys`: `EMISSION`, the world renderer's emission target;
  `DISTORTION`, its distortion field once applied (`CgDistortionField`: an array and how many layers hold offsets); `OVERDRAW`, its overdraw count while that view is on; `SCENE`, the HDR scene while it is on.

```java
public static final CgFrameKey<CgGraphTexture> MASK = CgFrameKey.of("mymod:mask", CgGraphTexture.class);
frame.resources().put(MASK, mask);                    // the producer, at a lower order
CgGraphTexture mask = frame.resources().get(MASK);    // a later renderer of the same firing; null if none
```

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
         .light(15f, sky)                              // optional: the world's light at its position otherwise
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

// A set of instances in a GPU buffer of object records, culled on the GPU: a count the CPU knows, or one a kernel wrote
world.draw(rockLods, stone).instances(rocks, CgGpuCount.of(n)).at(x, y, z).bounds(field).submit();
world.draw(shard, crystal).instances(shards, CgGpuCount.at(alive, 0, capacity)).at(x, y, z).submit();

// A level per screen height (CgMeshLods, Unity's LODGroup): picked per draw at record time
world.draw(CgMeshShapes.sphereLods(), smoke).at(x, y, z).transform(scale).submit();

// A label: a CgTextRenderer.Draw at a point, facing the camera or turned as a sign; depth-tested
world.text("Spawn").at(x, y + 2, z).height(0.5f).font(font).stroke(0.08f, 0xFF000000).submit();
world.text(sign).at(x, y, z).rotation(facingSouth).anchor(0f, 0f).family(family).targetPx(48).submit();
```

- **Drawing what kernels wrote** (`.indirect`, `.instances`) is its own workflow:
  [`GPU_DRIVEN_RENDERING.md`](GPU_DRIVEN_RENDERING.md).
- A draw of `CgMeshLods` takes the level for the screen height its bounds cover, and none below the last level's.
- **Labels** (`world.text`) draw after every transparent draw of the firing, unsorted among them: a glow in front
  of a label does not cover it. A label is a queued `CgTextRenderer.Draw` (strokes, shadows, families, paragraphs),
  drawn under each firing's camera; `height` is a line's height in blocks. Labels are retained: a label's glyphs are
  captured when what it draws changes and kept on the GPU, and a frame otherwise writes one matrix a label. 10,000
  outlined, shadowed labels are 3 draws and about 230 ns a label (`--mode=world-labels`). Their text writes depth
  where it is half covered or more, so a nearer label's text hides a farther one's; without the HDR scene that pass
  runs before the firing's transparent draws and glows, so a glow behind a label is hidden from bloom too (with it,
  bloom reads the finished scene). A farther label's text may still show over a nearer one's shadow.
- **Culled** against the view by the draw's stated bounds, else its mesh's, either grown by `pad`, and **sorted**
  (`CgSortKey`): first by `CgSortLayer` (Unity's sorting layers: `BACKGROUND`, `DEFAULT`, `EFFECTS`, `OVERLAY`, and any
  defined `before`/`after` one), then opaque by material, front to back, then mesh; transparent back to front, a
  `.group(x, y, z)` sorting as one at its position (Niagara's system; every VFX effect is one) and its draws by their
  `.order(0..15)` within it, then by `.batchKey(0..65535)` before distance: draws of one material given one key run
  together and join into one multi-draw (the VFX's GPU particles, a group per system). Equal neighbours instance.
- `WORLD_OPAQUE` records a prepass (materials with a depth pass, and alpha-tested ones) and the opaque pass;
  `WORLD_TRANSPARENT` the transparent pass. Each declares `sceneDepth`/`sceneColor`, so the graph copies the target
  for a reader only where one draws.
- Shaders see **camera-relative** world space: `CG_CAMERA_WORLD_POS` is the origin, and `CG_ABSOLUTE_WORLD_POS(p)`
  adds `cg_WorldOrigin` back for an effect that must not move with the camera.
- **Lit by the world** (`docs/SHADERS.md` § *Lighting and fog*): each draw carries the block and sky light at its
  position, read once a frame per block (`CgWorldLight`); `.light(block, sky)` states it and `.fullBright()` lights
  it fully. Every world pass binds the host's lightmap, and its constants carry the sun and the fog
  (`CgWorldAtmosphere`, from `CgHostEnvironment`).
- **Emission**: a material with an Emissive pass (`docs/SHADERS.md` § *The Emissive pass*) glows. After the
  transparent pass that pass is drawn into the emission target, hidden by the scene's depth, and published as
  `CgFrameKeys.EMISSION`; the post stack blooms it (§ *The post stack*). The target is R11G11B10F, at the tier's share of
  the world's size (Low 0.25, Medium and High 0.5, Ultra 1); `world.emissionScale(scale)` overrides it. A draw's
  `.emission(scale)` scales its glow (0 leaves it out), and a material's `_EmissionColor`/`_EmissionStrength` its
  material's, both through `CG_EMISSION`. Where the target is a framebuffer of the host's and bloom will read the
  emission (`CgFrameKeys.EMISSION_READ`, which the post stack puts), a transparent draw whose Emissive pass is codeless
  on its Forward pass's blend draws its glow in the same draw, into an emission beside the target. That emission is the
  target's size whatever the scale: 33 MB of transient R11G11B10F at 4K, against 8 MB at half size. A host target that
  takes no second attachment (multisampled, scaled, framebuffer 0) is logged once and drawn the old way, and so is
  every glow on a device without `independentBlend` (`CgCapabilities.independentBlend()`; Minecraft 26.2's device is
  created with it where the GPU has it).
  `world.mergeEmission(false)` draws every Emissive pass on its own again.
- **The HDR scene** (the player's `CgGraphicsSettings.HDR`, on by default; `world.hdrScene(on)` overrides it for the
  session, unsaved, as `-Dcrystalgraphics.world.hdrScene` does from launch; render-hdr-scene):
  `WORLD_TRANSPARENT` draws into a linear RGBA16F scene beside the host's depth instead of the host's colour. Its first
  pass decodes the host's colour into it and makes it the stage's target (`CgStageFrame.retarget`, `CgFrameKeys.SCENE`);
  the post stack's composite encodes it back, a pixel nothing changed returning byte for byte. Between the two, raw GL
  into the host's framebuffer is overwritten and `cg_SceneColor` reads linear HDR. Materials' sRGB colour is decoded as
  it is written (`docs/SHADERS.md` § *Lighting and fog*). Glows add into the scene itself, with no emission target, and
  bloom takes the scene's light past `CgPostStack.get().bloom().threshold(t)` (1). Flipped live, it takes effect at the next firing: the harness and the demo bind it to G.
- **Half resolution**: a transparent draw of soft light that adds (`Blend ONE ONE`: a glow, a volume) marked
  `.halfResolution()` draws into a half-size target before the transparent pass and is added over the target by a
  depth-aware upsample, at a quarter of the pixels. Its shader hides itself behind the scene from `cg_DepthBuffer`
  with `DepthTest ALWAYS`, since that target has no depth. `world.halfResolution(false)` draws them at full size.
- **Distortion**: a transparent draw whose material has a Distortion pass (`docs/SHADERS.md` § *The Distortion pass*)
  adds an offset into a layer of an RGBA16F array half the world's size (`world.distortionScale(scale)`) after the
  transparent pass, hidden by the scene's depth; an apply over the hazes' rect then bends the target by it, reading the offsets
  bilinearly and a copy of the scene. A haze bends what sorts before it; a draw marked `.afterDistortion()` (or a material in `Queue = "AfterDistortion"`) is not bent by the hazes sorted before it:
  their apply is placed just before it in the transparent pass, and nearer hazes bend it after. GPU zones
  `world.distortion` and `world.distortionApply`; a frame with no distortion records neither. The field is published
  as `CgFrameKeys.DISTORTION`, and a post effect bends a side input by it with `post.distorted(texture)`: bloom
  bends the emission so the glow moves with the scene beneath it.
- **Warming**: `world.prepare(material)` starts every program the renderer will draw a material with (each pass of its
  chain, its depth, Emissive and Distortion passes, and the multi-draw form of each) without waiting, and answers true
  once all are built. Poll it from a warm-up list before a material's first draw: a program compiled at its first
  draw is 7-110 ms of that frame on GL.
- **GPU time by group**: under `crystalgraphics.gpu.groups` a pass's GPU time lands per material
  (`gpu:world.transparent/<shader path>`); `.gpuGroup(label)` charges a draw to a label of its own instead
  (`docs/PROFILING.md`).
- A host drawing the world twice in a frame (1.7.10's anaglyph) fires both stages twice; each draw is drawn under
  each firing's view.

### The post stack — what runs after the world

`CgPostStack` (`render/post`, its own guide) records on `WORLD_TRANSPARENT` after the world renderer: every active
`CgPostEffect` at its `CgPostPoint` (`AFTER_WORLD`, `BEFORE_COMPOSITE`, `AFTER_COMPOSITE`), and between the last two
one composite pass laying the firing's looks over the target. Bloom is its built-in effect.

```java
CgPostStack.get().bloom().intensity(1.5f);                          // 1 by default; 0 for none
CgRenderStage.Registration fx = CgPostStack.get().add(myEffect);    // a mod's effect at its point
fx.close();

// An effect's look for its moment: a volume, blended by priority and distance, weighed over its life
CgPostVolume burst = CgPostStack.get().volume(10, new CgPostSettings().flash(1.5f).bloom(2f).chromatic(0.6f))
        .at(x, y, z).radius(24f).blend(16f);
burst.weight(1f - age / life);
burst.close();
CgPostStack.get().volume(20, new CgPostSettings().impact(CgImpact.LINES, 1f));   // an impact frame, everywhere
```

- The looks: flash (exposure in stops), vignette, chromatic aberration round the focus, impact frames (`CgImpact`:
  negative, black and white, speed lines; and `SUBJECT`, `SUBJECT_INVERTED`, `SUBJECT_LINES`, what glows split from
  the world, masked by the HDR scene's light past white). Flash and impact frames are scaled by the player's `FLASHES`
  comfort setting.
- **An impact frame is beats, not a fade**: `CgImpactSequence` holds each look for whole frames at 24 a second and cuts;
  an effect sets the look it returns on a volume at full weight, with a hard edge. `CgEnergyWave`'s blast plays one
  inside a hitstop (`BLAST_BEATS`); `--mode=vfx-blast-flash` shows it.

```java
static final CgImpactSequence HIT = CgImpactSequence.at(24f)
        .beat(CgImpact.SUBJECT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(CgImpact.SUBJECT_LINES, 2).build();
CgImpact look = HIT.look(sinceHit);                      // each tick; null outside it
impact.weight(look != null ? 1f : 0f);
if (look != null) settings.impact(look, 1f);
```
- A volume's focus is where it stands on screen unless `CgPostSettings.focus(x, y)` says otherwise.
- **A mod's effect** implements `CgPostEffect` (its javadoc has the example): a raster pass into `post.target()` at
  `AFTER_WORLD` or `AFTER_COMPOSITE`, or composite inputs at `BEFORE_COMPOSITE`. `render/post/CLAUDE.md` § *Writing an
  effect* has the rules; `--mode=post-effects` is one at each point.

**Object record** (`CgInstanceKind.OBJECT`, STD430, 48 floats): `modelMatrix` 0–15, `normalMatrix` 16–31 (the
shader reads its 3×3; 28–29 the light, `CG_OBJECT_LIGHT`; 30 the emission scale less 1, `CG_OBJECT_EMISSION`),
`custom0`–`custom3` 32–47.

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

Every texture type is made, filled and deleted from any thread, and takes its bytes from an upload lease a worker
wrote (§ *Async*).

`CgTextureType` — typed enum of ~42 GL format constants; single source of truth for (internalFormat, baseFormat, type). `CgTextureSpec` — immutable `@Builder` describing format + filter + wrap + optional shadow compare. `CgMipmapConfig` — `NONE` / `TRILINEAR` / `NEAREST`. Concrete impls: `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`.

**Package guides**: `api/texture/CLAUDE.md` · `gl/texture/CLAUDE.md`

### Meshes

**A mesh is data** (`api/mesh/CgMesh`): built and edited on any thread, with no GPU in it. The graph and the world
renderer draw it from `render/mesh/CgMeshStore`, which keeps every mesh's copy in pooled slabs per vertex format,
uploads what changed before a frame's first pass, and draws with base-vertex calls (plan `mesh-rewrite`). Where
`CgCapabilities.multiDraw()` holds, a run of draws sharing a pipeline, bindings and a slab is one multi-draw call
(`render/graph/CLAUDE.md`, *Multi-draw*).

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
asked. `gl/buffer/CLAUDE.md` has the tiers. A `RETAINED` buffer is made, written and deleted from any thread (§ *Async*).

Do NOT attach the engine's own blocks (`CgFrameBlock`, `CgObjectDataBuffer`) — declared in `cg_env.glsl`, wired automatically. Duplicate declarations cause compile failure.

**Package guides**: `api/buffer/CLAUDE.md` · `gl/buffer/shader/CLAUDE.md`

## Async: off the render thread, and beside the frame

Two kinds, independent of each other:

- **Off the render thread**: the CPU's work (recording, building, decoding, writing bytes) on any thread, leaving the
  render thread only the device calls.
- **Beside the frame's queue**: GPU work on another Vulkan queue (compute, transfer) while the frame draws. GL has one
  queue and runs the same work in order, with the same result.

### What runs where

| Work | Any thread | The render thread | On Vulkan, beside the frame |
|---|---|---|---|
| A frame: `CgRecording` recorded, `CgFrameBuilder` built | yes: one thread per recording at a time, one builder per thread | `CgExecutor.execute` | — |
| Meshes (`CgMesh`): build, edit, release | yes | the store places them and copies their bytes into slabs before the first pass | the copies, on the transfer queue |
| Textures (2D, arrays, 3D, cubemaps): make, fill, grow, delete | yes, through each texture's `CgDeferral` | what was queued lands before the next frame executes | a copy into a texture nothing else has used yet, and a whole upload of 256 KB or more into one the frame has used, on the transfer queue |
| Shader buffers, `RETAINED`: make, write, delete | yes, through each buffer's `CgDeferral` | the upload lands (`glBufferSubData`) before the next frame executes | — (its memory is host-visible: landing is a CPU copy) |
| Shader buffers, `FRAME` | no | written and uploaded by the frame that reads them | — |
| Glyphs | every glyph (bitmap, MSDF field, shadow cell) on the font registry's workers: a new glyph draws a frame or more late | finished glyphs land in the atlas here, a budget of them a frame | a new page's copy, on the transfer queue |
| Compute passes | recorded with the frame | executed with it | an `async()` pass, on the compute queue |
| Readbacks | `CgRecording.readback`, recorded with the frame | `CgReadback` asked for here; every sink runs here, frames later | — |
| `.shader` and `.compute` files | parsed on any thread | — | — |
| Shader compiles | Vulkan: shaderc on a worker where the host turned on `compileInBackground()` | GL: the driver, at the first draw, or started by `prepare()`; Vulkan: modules and pipelines | — |
| Framebuffers, materials' programs, kernels' programs | no | yes | — |

### Objects from any thread: `CgDeferral`

A texture or a `RETAINED` shader buffer takes the same calls on any thread. Where the device may not be driven (a
worker, or inside a recording), its work queues, in order, and the executor lands every object's queue before the next
frame executes (`CgDeferral.applyAll`).

```java
// On a worker: made, filled and handed over; drawn by the next frame that executes
CgTexture2D noise = CgTexture2D.createEmpty(256, 256, CgTextureSpec.RGBA8_LINEAR);
noise.uploadRegion(0, 0, 0, 256, 256, pixels, GL_RGBA, GL_UNSIGNED_BYTE);   // pixels copied into a lease here

CgShaderBuffer table = CgShaderBuffer.create("Heights", HEIGHT_FORMAT, 0);   // RETAINED
table.beginWrite(cells);
// ... records ...
table.endWrite();
```

- **An id is 0 until the work lands.** `getId()`, `bind()` and `getGlBufferId()` land what is queued first, so call them
  on the render thread outside a recording; a shader buffer's `bind()` before its storage exists throws.
- **One owner at a time**: an object's work is asked for from one thread at once.
- **A task that throws is logged and dropped**; the rest of the object's queue still runs.
- `CgTexture2DArray` takes no lease: its CPU mirror needs a source it can read.

### Upload leases: the bytes written once

`CgUploads.lease(bytes)` is memory the GPU copies from, written by whoever makes the bytes; a texture lands it with no
further CPU copy. Passing a `ByteBuffer` instead copies it into a lease on the calling thread.

```java
CgUploadLease lease = CgUploads.lease(4 * w * h);                       // any thread
decoder.decodeInto(lease.bytes());                                       // write exactly size() bytes
texture.uploadRegion(0, x, y, w, h, lease, GL_RGBA, GL_UNSIGNED_BYTE);  // handed to one upload

cube.uploadFace(CgTextureCubemap.POSITIVE_Y, 0, 0, 0, size, size, CgUploads.lease(face.remaining()).put(face),
        GL_RGBA, GL_UNSIGNED_BYTE);
```

| Tier | Where | What landing costs |
|---|---|---|
| `UNPACK` | a persistently mapped unpack buffer, where the context has persistent mapping (every desktop driver) | GL copies it by DMA: 72 MB with no GPU hitch on NVIDIA, 1.5-3 ms of render thread. Vulkan records a device copy from it |
| `DIRECT` | pooled direct memory: macOS's 4.1, a 3.3 context, an upload that converts its format, or a shader buffer's | the driver copies it at the call: the landing frame pays 17-22 ms of GPU per 72 MB on GL |

- Hand a lease to exactly one upload, or `release()` it: one never handed over keeps its block from being reused.
- Its bytes are write-only (unpack memory is slow to read, and the GPU may be reading it already).
- Land it while the context it was leased in is current.

### Vulkan's transfer queue

Where the device has a queue family that only copies, these copies run on it while the frame draws:

- **a copy into a texture only the transfer queue has used**: every new texture's first uploads, glyph pages' growth
  included;
- **an upload of 256 KB or more replacing a whole texture the frame has used**: it is written into a new texture,
  which takes the transfer queue, and the old one is freed once the frames reading it finish. Only where it covers
  all of level 0 and no other level is specified, since those would be lost: an upload into a texture whose mipmaps
  were generated stays on the frame's queue;
- **the mesh store's copies into its slabs**, between `CgGL.cgBeginTransfer` and `cgEndTransfer`.

Any other copy into a texture runs on the frame's queue. Copies there one after another share one barrier on each side,
and one call where they share a source and a texture: a burst of glyph cells is a few calls behind one pair of
barriers, not a pair each.

The owned device takes a transfer-only family (family 1 on NVIDIA); Minecraft 26.2 and 26.3's device lends us the
transfer queue Minecraft creates and never uses. The frame's queue waits for a batch at its next pass, compute pass or
async section, at the first work touching an image or buffer the batch wrote, or at the frame's end; mipmaps asked of
such a texture meanwhile are generated after that wait. `-Dcrystalgraphics.vulkan.transfer=false` keeps every copy on
the frame's queue.

A copy of your own goes there through the bracket:

```java
CgGL.cgBeginTransfer();
try {
    CgGL.glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, from, to, size);   // and more
} finally {
    CgGL.cgEndTransfer();
}
```

- It may not read what the frame's queue writes, nor write what a frame in flight reads.
- One bracket's copies are not ordered among themselves; the next bracket's come after them.
- Copies only: a draw, a dispatch, async work, a host section's end or the frame's end inside it throws.

### Async compute

`CgComputePass.async()` runs a pass on the device's compute queue (`CgCapabilities.asyncCompute()`: the owned Vulkan
device), beside the drawing between it and its first reader, which waits for it. Everywhere else it runs in order,
Minecraft's device included (below). `docs/SHADERS.md` § *Beside the drawing* says which passes to mark.

```java
CgComputePass step = recording.compute("sparks.step").async();
step.dispatch(simulate, capacity).bind("IN", sparks).bind("OUT", sparks);
step.end();
```

- `-Dcrystalgraphics.vulkan.asyncCompute=false|graphics` runs it in order, or on a second queue of the frame's family;
  `-Dcrystalgraphics.graph.asyncAll=true` sends every pass that can go async.

#### On Minecraft's device: in order, for now

Off by default on a hosted device; `-Dcrystalgraphics.vulkan.asyncCompute=true` turns it back on, and then an async
pass may not touch Minecraft's own textures (its main target, the lightmap): it throws.

**Why off.** Minecraft submits once a frame, at its end, and our work reaches the GPU only inside that submit. An
async pass waits for the work recorded before it (the step reads this frame's particle uploads), so it can start only
once Minecraft's whole frame has run; its reader then waits inside the next frame's submit, and that wait holds back
the whole submit, Minecraft's own work included. Nothing overlaps: frame, async pass, frame. In the blasts scene on
26.2 that was 5 ms a frame (16.97 ms with it, 12.06 without), all of it a GPU wait in Minecraft's present. The owned
device submits mid-frame, so there the same pass does overlap.

**What would bring it back.** Two changes together, so the pass never waits on Minecraft's submit:

- Its inputs reach the GPU without the main queue: through the transfer queue, which we submit on our own timeline,
  or from the previous frame. It can then start as soon as it is recorded, beside the frame's drawing.
- Its reader takes the result a frame later (the graph already carries async waits into later executions), so the
  wait lands in a submit the pass has already finished by. The cost is a frame of latency on what it computes.

**When it is worth it.** Only for a large compute pass in a GPU-bound frame. The blasts scene's async pass, the
particle step, is 0.03 to 0.2 ms of GPU, and the frame is CPU-bound (9.87 ms a frame, 2.08 of GPU), so a perfect
overlap would save nothing.

### Readbacks

Nothing reads the GPU back with a stall: a readback copies into memory the CPU maps, behind a fence, and its sink runs on
the render thread once the GPU is done, two or three frames later.

```java
CgReadback.buffer(counts, 0, 4, data -> alive = data.getInt(0));                     // render thread
recording.readback(voxels, 0, 0, 0, 40, 128, 96, 2, data -> check(data));            // in a frame graph, ordered
CgPixelReadback thumbnails = new CgPixelReadback(3);                                 // a framebuffer, shrunk first
```

- The data is valid only inside the sink, in native order: copy out what you keep.
- On Vulkan, buffers read back are in memory the CPU caches (a `READ` usage hint, or `GL_MAP_READ_BIT` storage).

### What stays synchronous, and why

| Stays | Why (`render-async-uploads` §4) |
|---|---|
| GL's copies run on the render thread's one context | a second context saved the render thread 1-2.5 ms per 72 MB; decided against (2026-10-05) |
| Copies into a texture the frame's queue already uses (a glyph page filling) | at most 0.01 ms a frame, and a transfer copy would need two cross-queue waits |
| Readback copies, on the frame's queue | at most 0.3 ms a frame, for a debug view |
| `FRAME` shader buffers | written by the frame that reads them, by definition |
| A shader variant's compile, at its first draw: 7-110 ms on GL | `render-shader-compile`, proposed. Meanwhile `CgMaterial.prepare()` and `CgKernel.prepare()` start one early |
| Framebuffers and programs: made on the render thread | — |

Measured by: `deferral.apply`, the workers' `upload.lease-copy`, and the GPU zones `upload.deferred` and `upload.meshes`
(`crystalgraphics.gl.detail`); `--mode=upload-stress` bursts textures from workers and from the render thread, and
`--mode=async-compute` times compute beside drawing.

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

`CgCapabilities.detect()` — cached per context; **throws below OpenGL 3.3**. Above the floor it answers `shaderBufferPath()` (SSBO → TBO, forceable with `-Dcrystalgraphics.shaderBuffer.tier=<path>`), `vertexStreamTier()` / `shaderStreamTier()` (the stream-buffer waterfall, see `gl/buffer/CLAUDE.md`), `isCopyImageSubDataSupported()`, the limits (`getMaxDrawBuffers()`, `getMaxTextureUnits()`, …) and `isCoreProfile()`. For compute and GPU-driven draws it answers what a consumer needs — `compute()`, `storageImages()`, `subgroups()`, `floatAtomics()`, `drawIndirect()`, `multiDrawIndirect()`, `indirectCount()`, `drawParameters()`, `feedbackCount()`, `asyncCompute()`, `bindless()`, `multiDraw()` (whether the mesh store joins draws; `-Dcrystalgraphics.mesh.multiDraw=false` turns it off) — each joined from a core version, its ARB extension and, on the tracked backend, the device; and `computeTier()` (`V` · `G43` · `G40` · `G33` · `CPU`), forceable with `-Dcrystalgraphics.compute.tier=<tier>`, which throws naming what a context lacks.

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
CgBlendState.ADDITIVE          // SRC_ALPHA / ONE: adds nothing where alpha is 0
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
| 6c | `CgBufferTextures.releaseAll()` | The buffer textures materials and lowered kernels read buffers through, one per unit, and the zero buffer an unbound one reads |
| 6d | `CgReadback.releaseAll()` | Pooled staging buffers and the fences of readbacks still in flight; each pending sink is told it failed |
| 7 | `CgShaderBufferRegistry.get().deleteAll()` | User SSBO/TBO/UBO resources |
| 8 | `CgWorldRenderer.get().release()` | Its draws, and the depth snapshot's reference (the framebuffer is freed by step 9) |
| 8a | `CgPostStack.get().release()` | Its materials' references (the registry freed them at step 6); its transients are the graph's. Volumes and registered effects stay, for the next context |
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
