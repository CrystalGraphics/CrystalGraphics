# gpu/resource — CgTexture GL Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)
> API guide: [`api/texture/CLAUDE.md`](../../api/texture/CLAUDE.md)

## What This Package Is

Concrete `CgTexture` implementations. All four texture types (`CgTexture2D`,
`CgTexture2DArray`, `CgTexture3D`, `CgTextureCubemap`) are transparently cached
via the singleton `CgTextureManager`.

## File Map

| File | Role |
|------|------|
| `CgTextureManager.java` | Singleton cache + reload + fallback. See below. |
| `CgTextureAbstract.java` | Shared base: id, `Texels`, dimensions, its own `sampler()` and `setSampler` (refused once shared), bind, delete, `checkUsable`, and the load at first use (below). |
| `CgTexture2D.java` | Single 2D texture (`GL_TEXTURE_2D`). `create(path[, settings])` (cached, loaded at first use), `createDirect(path)`, `missing()` (the checkerboard), `createEmpty(w, h, format[, levels])` (storage for that many mip levels, written by a kernel or a pass rather than generated), `createFromPixels(...)`. Two `upload()` overloads, and `uploadRegion` for a region of one level (what `CgRecording.update` writes a graph texture with). `bindLevel(unit, level)` binds a view of one level. A path ending in `.hdr` is a Radiance image (`util/io/CgRadianceHdr`), uploaded as half floats. A path ending in `.cgtex` is a cooked texture (`util/io/CgCookedTexture`): its levels land from one lease, as BC7 blocks where `CgCapabilities.bc7()` holds, else decoded by `CgBc7` to RGBA8 on the loading thread; the file decides format and levels, its settings the sampling. |
| `CgTexture2DArray.java` | 2D-array texture (`GL_TEXTURE_2D_ARRAY`). `create(paths...)` (cached, loaded at first use, images of different sizes centred), `createDirect(paths...)`; `allocateEmpty(w, h, layers, format)` for incremental uploads, `growLayers` (new storage, the old layers copied on the GPU). |
| `CgTextureAbstract.java` | The shared base. Owns the texture's `CgDeferral` (`gpu/`): all its device work goes through it, so `CgTexture2D`, `CgTexture2DArray`, `CgTexture3D` and `CgTextureCubemap` are made, filled, grown and deleted from any thread, their work waiting for the render thread where the device may not be driven. `getId`/`bind` run what is queued first; an id is 0 until then off the render thread. Pixels for waiting work are copied once into a `CgUploadLease` by the thread that passed them (`gpu/CgUploads`), and land with no copy on the render thread; `CgTexture2D.uploadRegion` and `CgTexture3D.uploadRegion` also take a lease its producer wrote. |
| `CgTexture3D.java` | 3D texture (`GL_TEXTURE_3D`). `create(paths...)` (cached, loaded at first use), `createDirect(paths...)`; `createEmpty(w, h, d, format[, levels])` through its `CgDeferral`, any thread, `uploadRegion` for a box of one level and `generateMipmaps()` from level 0: a volume a kernel, a bake or an upload fills, and a frame-graph volume's storage. |
| `CgTextureCubemap.java` | Cubemap (`GL_TEXTURE_CUBE_MAP`). `create(posX...negZ)`, `createDirect(posX...negZ)`, `createStacked(path[, settings])` (one image, faces stacked top to bottom in Unreal's slice order, each face i unchanged; `w x 6w` or it throws at first use), `createEmpty(size, format)`, any thread through its `CgDeferral` (faces decoded on the calling thread); `uploadFace(face, level, x, y, w, h, ...)` from a buffer or a lease, the face carried in the lease's `z`. |

## Where an upload runs

An upload has a CPU half (bytes into memory the GPU can copy from) and a GPU half (the copy). Off the render thread
the first is the producer's and the second lands at the render thread's next deferral apply, before the frame's
first pass (plan `render-async-uploads`), within `crystalgraphics.deferral.budget` (4 ms) a frame: a burst of textures
lands over frames, and one bound sooner lands at its bind (`CgDeferral.spreadOverFrames`):

```java
// A worker that makes the bytes writes them straight into a lease: no copy anywhere else
CgUploadLease lease = CgUploads.lease(4 * w * h);
bake(lease.bytes());
texture.uploadRegion(0, 0, 0, w, h, lease, GL_RGBA, GL_UNSIGNED_BYTE);   // any thread; lands on the render thread

// A worker holding its own buffer: the texture copies it into a lease on this thread
texture.uploadRegion(0, 0, 0, w, h, pixels, GL_RGBA, GL_UNSIGNED_BYTE);
```

| Backend | The GPU half |
|---|---|
| GL device | `copyBufferToTexture` from the lease's buffer, as an unpack-buffer upload: DMA, 72 MB with no GPU hitch on NVIDIA, 1.5-3 ms of render thread. A converting upload is converted on the CPU first |
| Vulkan device | a device copy from the lease's buffer; into a texture nothing else has touched yet, on the transfer queue, which the frame's queue waits for at its next pass (`platform/CLAUDE.md`). `-Dcrystalgraphics.vulkan.transfer=false` keeps it on the frame's queue |

A second GL context for uploads was measured and declined (`render-async-uploads` §4f); the harness keeps it as
`upload-stress -Dcrystalgraphics.harness.upload.shared=true`.

## Textures from files: loaded at first use

Every `create(...)` from paths goes through `CgTextureManager` and answers an object that has not read its files.
`CgTextureAbstract` holds the hook:

```
create(paths)           manager.getOrCreate(key, Texels.file(path), () -> unloaded texture)   // no decode, no GL
first use               getWidth/getHeight/getId/getLevels/isMissing/bind/any write
  -> ensureLoaded()     once, synchronized, on the thread that asks
  -> load()             decode the files, queue the upload through gpu (CgDeferral)
reload()                load() again in place: same object, same id; a file that will not load keeps what it had
createDirect(...)       the same object, load() at once, not cached
```

- **A subclass reads its own fields only after `checkUsable()` or inside `load()`**: before its first use a texture
  from files has width and height 0.
- **`load()` is the one decode path per type**, for the first load and every reload. A first load that fails leaves a
  stand-in and sets `missing`: the checkerboard for a 2D texture, a clear layer for an array, a clear 1x1 texture for
  a 3D or cubemap.
- **An array centres images of different sizes** on a layer the largest one's size (`CgTexture2DArray.centred`);
  `CgTextures.frames(pattern, count)` names numbered files.
- **`CgTextures` (`api/texture`) is the shorthand** over each type's `create`: `of`, `frames`, `volume`, `cube`,
  `create`. A new way to make a texture from files goes on both.
- **A file loads as its settings file says** (`CgTextureSettings`, `noise.png`'s `noise.yaml`), or plain without one;
  `Texels.file` turns settings into the texel type, the chain and the sampler. A made texture's are
  `Texels.made(format, levels)`. No factory takes a sampler: `api/texture/CLAUDE.md` § *Sampling*.
- **No decode on a server**: naming a texture builds an object and a cache entry. Nothing reads a file until something
  asks for its size or binds it.
- **Every device call goes through `gpu`**, in `load()` too: the first use may be a worker's.

## CgTextureManager

```java
CgTexture2D getOrCreate(String path);                                            // CgTexture2D.create; fallback for no path
CgTexture2D getOrCreate(String path, CgTextureSettings settings);                 // as those settings say
CgTexture getOrCreate(String key, Texels spec, Supplier<CgTexture> maker);        // package: what each type's create uses
CgTexture2D getFallback();                                                       // the shared CgTexture2D.missing()
void reloadAll();                                                                // a resource reload
void freeAll();                                                                  // CgEngineLifecycle.shutdown()
```

- **Keyed by the paths and the `Texels`**: the paths joined by `\0`, then the texel type, chain and sampler by value.
  One image loaded with two settings is two textures; the first caller's never wins over a second's.
- **A texture it hands out is shared** (`share()`): its `sampler(...)` throws, since every caller of the path has it.
- Each cached texture is tracked by its key's paths (`CgAssetReloader`), so saving one of its files reloads it in
  place, and only it.
- The fallback is never cached under a key, and is freed with the cache.

## Storage and sampling

Every texture's storage is a device texture (`CgDeviceTextures`, `CgTextureAbstract.storeOnDevice`): made with the
shape its first whole upload or `createEmpty` gives it, written by `writeTexture` or a lease's GPU copy, its chain by
`generateMipmaps`. The engine knows it by the name `CgDeviceNames` gives it, and that name changes when the storage
is remade (a grow, a resize): read `getId()` at each use.

A texture's own sampling is a `CgSampler` value (`sampler()`), the one a binding reads where it names none
(`render/draw/CgDeviceSamplers`, cached by identity: keep the same object). `bind(unit)` notes it for the pass being recorded, and
`CgTexture2D.bindLevel(unit, level)` notes a view of one level; neither issues a GL call.

## Design Rules

- `load()` is the single decode path per type and `upload()` the single whole upload; factories and `reload()` call them.
- `reload()` is always in place: the same Java object. Its id is the storage's and may change; read it at each use.
- `create(...)` is cached and tracked, so a change to its files reloads it; `createDirect(...)` is neither.
- Never call `glGet*` here; the `Texels` is the source of truth.
- `delete()` is idempotent (second call is silent no-op).

## TODO — Animatable Textures

> **Future work: `ITickable` / animatable texture support**

Add an `AnimatedCgTexture2D` (or similar) that:
- Implements `CgTexture` (delegates bind/getId/etc. to the underlying `CgTexture2D`).
- Holds a sequence of frames (array of `CgImageData` or pre-uploaded `CgTexture2D` ids).
- Implements an `ITickable.tick(float deltaTime)` interface so the Minecraft adapter
  (`mc/` package) can advance frames each game tick or render tick.
- Frame scheduling options: fixed FPS, per-frame duration list (like MC's `.mcmeta` format).
- Could integrate with `CgAssetReloader` to reload all frames on F3+T.
- Manager interaction: register under a synthetic key; `reloadAll()` already calls `reload()`
  which can reload all frames in-place.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md

## Framebuffers

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

A `CgFrameBuffer` is a set of device textures a pass draws into: colour slots and a depth, as a
`CgFrameBufferFormat` lays them out. There is no GL framebuffer object behind an owned one. Nothing binds it: an
execution takes it as its current target through `CgExecutor.into(target)`, and a graph pass attaches its textures.

### Names

Every framebuffer has an `int` name (`getId()`), what the frame graph, readbacks and a host frame's
`mainFramebuffer` key by:

- **Owned**: a name the engine gives it, from `0x40000000` up, above any a GL driver hands out.
  `CgFrameBuffer.owned(id)` finds it until deleted.
- **Wrapped** (`wrap(name, fbo, w, h)`): a host's framebuffer, its attachments imported (`CgHostImports`);
  framebuffer 0 is the window's. Owns nothing; `delete()` does nothing.

`CgFrameBuffer.attachment(fbo, depth, w, h)` answers a texture view for either kind: an owned one's own, else the
import. Use it, never `CgHostImports.attachment`, wherever a name may be ours.

### What it does

| Concern | |
|---|---|
| `create` (registry, by name and format), `createOwned`, `createOnDevice` (with `Images` placing each slot: the graph's transients), `createVolume` (a `CgTexture3D`, nothing draws into it; or around one the caller made, its sampling and levels kept), `createArray` (a `CgTexture2DArray`) | factories |
| `rhiColor(slot)`, `rhiDepth()` — the device textures a pass attaches | attachments |
| `getColorTexture(slot)`, `getDepthTexture()` — the sampleable textures; null for an attach-only slot | attachments |
| A renderbuffer or multisampled slot is an attach-only device texture: a pass attaches it, nothing samples it | attachments |
| `clear*` — a render pass of its own with clear loads; depth in GL's convention, mirrored for a reversed host | device |
| `readColor` (RGBA8), `readTexture`/`readTextureFloats` (static, any texture as RGBA8 / RGBA32F) — read now, waiting for the GPU, rows bottom first | device |
| `copyColorTo(fbo)`, `copyColorAndDepthTo(fbo)` — a blit into another framebuffer at the same size: a main target into the window | device |
| `depthTypeOf(fbo, w, h)` — a framebuffer's depth type, from its attachment's format | names |
| `resize`, `delete` — remake or release its textures; the name stays until deleted | lifecycle |

### Registry

```java
CgFrameBuffer fbo = CgFrameBuffer.create("shadow_map", 1024, 1024, SHADOW_FORMAT);   // cached by name and format
CgFrameBuffer hdr = CgFrameBuffer.createScreenSized("hdr_buffer", HDR_FORMAT);       // resized in place
CgGraphicsLifecycle.onResize(newWidth, newHeight);
```

`CgFrameBufferRegistry` holds what `create` and `createScreenSized` made; `deleteAll()` at shutdown. A
`createOwned` framebuffer is the caller's to resize and delete.

### Reading a framebuffer back without a stall — `CgPixelReadback`

```java
CgPixelReadback readback = new CgPixelReadback(3);
readback.request(fbo.getId(), fbo.getWidth(), fbo.getHeight(), 256, tag);   // shrink on the device, queue the read
readback.poll(pixels -> use(pixels.tag(), pixels.rgb()));                     // every frame; never waits
```

`readColor` waits for the GPU to finish everything before it. This halves the source with linear blits, then reads it
back through `CgReadback` (`gpu/resource`), landing a frame or two later. Copy the landed bytes out in ONE bulk `get`: a
get per byte from the mapped buffer measured 2-3 ms for a 256x144 picture.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md

## Buffers

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

### What This Package Is

The buffers the GPU may still be reading while the CPU writes the next contents — vertex streams, and the
storage under every shader buffer — plus the global quad index buffer shared by all quad renderers.

This package should never contain VAO logic or vertex format knowledge — that belongs in `gl/vertex`. The
stream buffers here are format-agnostic byte pipes.

### Three kinds of stream, all on the frame ring

| | Factory | Lives |
|---|---|---|
| **Vertex stream** | `CgStreamBuffer.create(capacity)` | one frame: each upload at a new offset in this frame's region |
| **Shader-buffer storage** (`CgBufferLifetime.RETAINED`) | `CgStreamBuffer.createForShaderBuffer(target, capacity)` | until the next upload, always at offset 0: `RetainedStreamBuffer` writes each upload into one shared staging ring and copies it in on the GPU (Unreal's `RHIUpdateBuffer`, Vulkan's staging upload) |
| **Frame-local SSBO or UBO** (a shader buffer with `CgBufferLifetime.FRAME`) | `CgStreamBuffer.createFrameLocal(target, capacity)` | one frame, bound by range (`glBindBufferRange`) at each upload's offset; `CgShaderBuffer.uploadData` re-binds after every upload. Only for data every reading frame uploads first — the quad, curve and object buffers, the material and frame blocks (copied in at a frame's first material bind) and the text UBO. A UBO starts at its block size and doubles on overflow |

| Ring tier | Needs | What it costs |
|---|---|---|
| `PERSISTENT` | GL 4.4 / `ARB_buffer_storage` | nothing per upload: the storage is mapped once, coherent |
| `RING` | GL 3.2 sync + `glMapBufferRange` | one unsynchronised map per upload |

`CgCapabilities.vertexStreamTier()` decides it once per context and the factories only read it;
`-Dcrystalgraphics.stream.tier=persistent|ring` forces one, and a tier the context cannot do throws from
`CgCapabilities.detect()`. The orphan and subdata tiers went in rhi X2: the floor has the ring. The harness's
`capability-report` prints the tier chosen.

Which shader buffers go on the ring is a question of lifetime, not type: every frame that reads the buffer
uploads it first. Instance data meets it by being written just before its draw. A block written once meets it
when every reader passes a point that copies it into the frame: `CgMaterial.bind` does, for the material's
properties (uploaded only when their bytes changed, or on its first bind of a frame); the frame block is a
recorded pass's constants, packed into the frame's binding table; text's block is each `CgTextMaterials` instance's
own, uploaded at its bind.
Retained storage stays for a mod's own registry buffers, whose readers pass no such point. The copy is not made at
a frame's first host section: on Minecraft's Vulkan host that is the frame end's, before Minecraft's submit, where a
ring's wait for the frame three back is refused.

```
CgFrameRing (static clock)
├── FRAMES = 3 regions per ring; frame() advances at endFrame()
├── endFrame() — one glFenceSync per frame; CgGraphicsLifecycle.tickFrame() calls it, nothing else
├── awaitRetired(frame) — at a ring's first upload of a frame, for the frame FRAMES back
│   (profiler scope frameRing.wait; almost always already signalled)
└── reset() — context teardown

FrameRingStreamBuffer (PERSISTENT and RING)
├── one GL buffer = FRAMES regions; bump-allocated, 256-byte aligned
├── RING: map() → glMapBufferRange(UNSYNCHRONIZED | INVALIDATE_RANGE | FLUSH_EXPLICIT) at the cursor
├── PERSISTENT: glBufferStorage + one coherent persistent map; map() returns a slice, uploadFloats writes
│   through one float view of the whole mapping (rebuilt with it), commit() flushes nothing
├── RING small writes (a frame-local UBO, ≤256 B): one glBufferSubData at the reserved offset, not a
│   map/unmap pair
├── commit() → the offset, which the caller binds (a range for a shader buffer)
├── an offset is valid only in the frame that committed it: FRAMES later its bytes are overwritten
├── a frame that outgrows its region gets fresh storage at once, every region doubled, to 64 MB (only once:
│   new storage waits for a threaded driver to drain its queue) —
│   counted as frameRing.overflow. RING orphans under the same name; PERSISTENT storage is immutable, so it
│   takes a new buffer and bumps getGeneration(), which the bindings compare alongside the offset
└── a host that never ends a frame fills one region and renews storage: correct, unpipelined

RetainedStreamBuffer (shader-buffer storage)
├── map()/mapFloats()/write() hand out a region of one shared staging ring (GL_COPY_READ_BUFFER)
├── commit() → glCopyBufferSubData into its own storage at 0, growing it first; returns 0
└── releaseStaging() at context teardown, through CgStreamBuffer.releaseShared()

CgQuadIndexBuffer (global singleton)
├── shared IBO: pattern [0,1,2, 2,3,0, 4,5,6, 6,7,4, …]
├── GL_UNSIGNED_SHORT → max 16384 quads (65536/4 vertices)
├── lazy creation on first get(), initial grow to 256 quads minimum
├── doubling growth strategy, never shrinks
├── bindAndEnsureCapacity(neededQuads) — bind + grow if needed
├── freeAll() — static cleanup for context destroy
└── used by ALL quad renderers (text, UI, sprites)
```

### Key Design Decisions

- **No wait inside a frame.** The ring fences once per frame, and the only wait is at a frame's first
  upload, for the frame three back. The sync ring it replaced fenced every upload and waited mid-frame
  when it lapped, which a backend that records and submits later cannot do at all.
- **`commit()` returns the data offset** — new per upload on the ring, 0 for shader-buffer storage.
- **A vertex stream is not storage.** An upload read in a later frame reads another frame's bytes.
- **One quad IBO for everything** — `CgQuadIndexBuffer` is a global singleton; all quad-based renderers
  share it. Max 16384 quads due to `GL_UNSIGNED_SHORT`.

### Lifecycle Rules

1. **Creation**: through the two factories. The `gl/vertex` registry creates vertex streams; `CgShaderBuffer`
   creates shader-buffer storage.
2. **Per-frame upload**: `map(size)` → write into the returned `ByteBuffer` → `commit(usedBytes)` → draw at
   the returned offset. `afterSubmit()` is a no-op kept for existing callers.
3. **Cleanup**: stream buffers are deleted by their owners; `CgQuadIndexBuffer.freeAll()` and
   `CgFrameRing.reset()` run in `CgEngineLifecycle.shutdown()`.

### Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gpu/resource/` | `CgShaderBuffer` owns one shader-buffer storage |
| `lifecycle/` | `CgGraphicsLifecycle.tickFrame()` ends the ring's frame |

### File Map

| File | Role |
|------|------|
| `CgStreamBuffer.java` | Abstract base and the three factories. Fields: `glBuffer`, `target`, `capacityBytes`, `writeOffset`. |
| `CgFrameRing.java` | The frame clock: one fence per frame, `awaitRetired`. |
| `FrameRingStreamBuffer.java` | The two ring tiers, persistent and mapped. |
| `RetainedStreamBuffer.java` | Shader-buffer storage: staged in the ring, copied in at offset 0. |
| `CgQuadIndexBuffer.java` | Global shared quad IBO. Pattern `[0,1,2,2,3,0,...]`. Max 16384 quads. Doubling growth. |
| `CgReadback.java` | A buffer range, a framebuffer region or a 3D texture's box (`slices`, each slice attached in turn to one framebuffer bound for reading) read back without a stall: copied on the GPU into pooled staging, handed to a sink from `poll()` in `tickFrame` once `CgFrameRing` has retired the frame it was issued in (its own fence is queried only where the host has ended no frame: a query of a young fence waits for a threaded driver to drain). On the `PERSISTENT` tier the staging is mapped once, coherent, and read with no GL call; below it each delivery maps it, which on a threaded driver waits for the queue to drain. What `CgRecording.readback` and `CgPixelReadback` use. In-flight reads are dropped at teardown |
| `CgBufferReadback.java` | A buffer's words read now, a stall: for a count a draw needs this frame (G33) and the CPU tier's copies |

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md

## Shader buffers

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)
> Parent guide: [`gpu/resource/CLAUDE.md`](../CLAUDE.md)

### What This Package Is

GPU-resident shader buffer implementations for the CrystalShader material pipeline.
These classes provide the CPU→GPU data path for per-object data (SSBO) and
per-frame uniform data (UBO) consumed by shader programs.

**Distinct from `gpu/resource/`** (vertex streaming VBOs). These buffers are read by
shaders via named blocks or texture samplers, not vertex attribute pointers.

### Class Hierarchy

```
CgShaderBuffer  (abstract — base for all shader buffer types)
├── CgShaderStorageBuffer   (SSBO: GL 4.3 core or ARB_shader_storage_buffer_object)
└── CgUniformBuffer         (UBO: per-frame uniform data, GL_UNIFORM_BUFFER)

CgShaderBufferRegistry      (singleton registry — user-created buffers only)
CgEngineBufferRegistry      (engine buffers a shader opts into via `#pragma cg_use`)
```

All three types are `public`. The TBO path (`CgTextureBuffer`) is unhooked: `docs/TBO_PATH.md`. Callers may hold concrete types for type-specific APIs
without casts.

### Layered Design

```
┌──────────────────────────────────────────────────────────────┐
│  CgShaderBuffer (abstract base)                              │
│  Owns: String name, CgStreamBuffer dataBuffer,               │
│        CgBufferWriter writer, write-session API,             │
│        bind(), delete template                               │
├──────────────────────────────────────────────────────────────┤
│  CgStreamBuffer (transport)                                  │
│  Default createForShaderBuffer: staged, copied in at offset  │
│  0, what glBindBufferBase reads -- a block written           │
│  once stays readable while bound. Frame-local SSBO/UBO       │
│  (createFrameLocal): the frame ring, bound by range; the     │
│  upload re-binds. Only for data every reading frame uploads. │
├──────────────────────────────────────────────────────────────┤
│  CgBufferWriter + CgStagingBuffer (CPU-side float staging)   │
│  Format-aware mode: beginRecord() pre-zeros a record;        │
│  named writes scatter values; buf.endRecord() finalizes.     │
│  All buffers and UBOs use format-aware mode — format is      │
│  mandatory on every CgShaderBuffer instance.                 │
└──────────────────────────────────────────────────────────────┘
```

### Type Map

| Type | Role |
|------|------|
| `CgShaderBuffer` | Abstract base + factories. Owns `String name`, `CgStreamBuffer dataBuffer`, `CgBufferWriter writer`, write-session API, `bind()` (noted for a device pass's sets), `delete()` template, `getGlBufferId()`. Two factory variants: `create(String name, CgBufferFormat format, int userIndex)` (user-facing — adds per-type USER_START internally) and `createInternal(String name, CgBufferFormat format, int bindingPoint)` (engine-internal, raw binding). **`CgBufferFormat` and `name` are mandatory**. Every upload passes `uploadData`, which skips one of at most 4 KB (`COMPARE_LIMIT_FLOATS`) equal to the last — except a `FRAME` buffer's first upload of a frame — and re-binds a `FRAME` buffer either way; counters `shaderBuffer.upload` / `shaderBuffer.uploadSkipped`. |
| `CgShaderStorageBuffer` | SSBO backend. GL 4.3 core or `ARB_shader_storage_buffer_object`. |
| `CgUniformBuffer` | UBO for a uniform block, with a `CgBufferLifetime`: `FRAME` for every engine block — material properties (`CgMaterial` uploads at every bind), the text block (`getOrCreateUboInternal`) — or `RETAINED`, the default for `getOrCreateUbo(format, name, index)`. `upload()` passes `CgShaderBuffer.uploadData`'s skip. Constructors `(name, format, bindingLocation[, lifetime])`. `getName()` is the GLSL block name. Uses unified `endRecord()` from parent. |
| `CgParameterCollectionBuffer` | The `globals` engine buffer: every `CgParameterCollection`'s values, a vec4 each (`GLOBAL_DATA`), FRAME lifetime, at `CgBindingPoints.GLOBALS`. Uploaded once a frame by `CgParameterCollection.upload()`, which the world renderer calls |
| `CgBonePalettes` | Every skinned draw's bones for a ring frame: `write(skinning, count)` answers a draw's base (three vec4 rows a bone, a 3×4 transform), and the world renderer `record`s the frame's palettes once, into a persistent graph buffer each skinned draw binds at `CgBindingPoints.BONE_PALETTES` (`Draw.bones(base)` puts the base in `CG_OBJECT_SPARE.x`). Its registered `CgShaderBuffer`, one identity bone, is only the skinned program's declaration and what the binding holds between skinned draws |
| `CgShaderBufferRegistry` | Singleton registry for user-created SSBO/UBO buffers. Two caches: `ShaderBufferKey(name, format, bindingPoint)` (every SSBO) and `UboKey(name, format, bindingPoint)`. `getOrCreate(String name, CgBufferFormat format, int userIndex)` and `getOrCreateUbo(CgBufferFormat format, String name, int userIndex)` take 0-based `userIndex`; per-type USER_START is added internally. `deleteAll()` called by `CgEngineLifecycle.shutdown()`. Engine-internal pipeline buffers bypass this registry. |

### Binding Point Namespaces

SSBO binding points and UBO binding points are **separate namespaces**.
Engine buffers take from the **high end** of each range; user buffers from the **low end**.

| Resource | Slot | Constant |
|----------|------|----------|
| SSBO (per-object, engine) | ssbo `maxSsboBindings - 1` (e.g. 7) | `CgBindingPoints.OBJECT_DATA` (a `Binding(ssbo, tbo)` record — call `.resolve()` for the active path) |
| SSBO (`CgQuadBatch`, engine) | one slot below `OBJECT_DATA`'s | `CgBindingPoints.QUAD_RENDERER` (same `Binding` shape) |
| UBO (per-frame, engine)   | `maxUniformBufferBindings - 1` (e.g. 35) | `CgBindingPoints.FRAME_DATA_UBO` |
| User SSBOs | `CgBindingPoints.USER_START_SSBO` (0) upward | pass 0-based `userIndex` to factories |
| User UBOs  | `CgBindingPoints.USER_START_UBO` (0) upward | pass 0-based `userIndex` to factories |

Reserved engine SSBO pairs are `CgBindingPoints.Binding(ssbo, tbo)` records, not two loose
`int`s — the pair is the TBO path's seam, kept with `tbo = -1` while it is unhooked; `resolve()` answers the
SSBO slot, so a consumer needing one reserved pair
(`CgShaderBufferRegistry.getOrCreateInternal(name, format, binding)`) passes a single object.

`CgBindingPoints.init(CgCapabilities)` must be called (by `CgEngineLifecycle.ensureGraphicsContext`) before
any engine buffer is constructed.

### Two-Phase Buffer Usage: Wire vs Bind

Each engine buffer uses two separate GL operations:

- **Bind** (`bind()`) — per-context. Establishes the actual GL binding: `glBindBufferBase` for a `RETAINED` buffer, which stays bound; `glBindBufferRange` at the latest upload for a `CgBufferLifetime.FRAME` one, which every upload re-binds (`CgShaderBuffer.uploadData`); `glActiveTexture+glBindTexture` for a TBO. The executor binds the engine pair per pass: the frame block from the pass's constants, the object buffer it owns.

User buffers may still use `bind(shader)` which does both in one call (requires shader to be active).

### Object Record ABI (must match `cg_env.glsl`)

Each per-object slot is **48 floats / 192 bytes** (`CgInstanceKind.OBJECT.format()`):

```
floats  0–15 : mat4 modelMatrix   (column-major)
floats 16–31 : mat4 normalMatrix  (full mat4; shader reads upper-left 3×3 as mat3)
floats 32–35 : vec4 custom0
floats 36–39 : vec4 custom1
floats 40–43 : vec4 custom2
floats 44–47 : vec4 custom3
```

The engine object buffer is named `"CgObjectDataBuffer"` (the SSBO block in `cg_env.glsl`).

### `CgEngineBufferRegistry` — `#pragma cg_use`

Engine-provided buffers a `.shader` opts into by name:

```glsl
##pragma cg_use quad     // → CgQuadBatch's per-instance buffer, macro QUAD_DATA
```

`CgMaterialShader.recompile()` resolves the token here and attaches the buffer **after parse, before
compile** — so the GLSL declaration exists no matter what triggers the first compile. The parser
hard-fails a shader that uses `QUAD_DATA`/`CG_QUAD_*` without declaring it.

**Why a registry rather than `cg_env.glsl`:** `CgViewBlock`/`CgEnvironmentBlock`/`CgObjectDataBuffer` are declared
unconditionally because nearly every shader wants them. A quad buffer is wanted by a small minority,
and the declaration costs a storage binding — so it is opt-in.

**Why providers hold a `Supplier`, not a buffer:** the built-in `quad` entry is seeded with the
method reference `CgQuadBatch::sharedBuffer`, which does *not* trigger that class's static
initialization at registration time. That matters — its buffer allocates against `CgBindingPoints`
and is only valid once `CgBindingPoints.init` has run. The supplier is invoked at attach time,
when a GL context exists.

**Adding one:** `CgEngineBufferRegistry.register(token, supplier, macroName)`, before any shader
referencing the token is parsed. Registering a duplicate token throws.

**Generated GLSL** (`registerGenerated`, `globals`): a provider may carry GLSL made at each compile, appended after
its env file, and a parse naming the token records its `generatedPath()` as read, so `CgAssetReloader.changed` on that
path recompiles every shader using it. Such a buffer, and `rows`, is **bound per draw** (`perDraw`): `CgMaterial.captureBindings`
puts it in each snapshot from the parse. The other engine buffers are their executors', and on the SSBO path a FRAME
upload re-binds its own; but a texture buffer is not re-bound by an upload, and a draw captured before its material's
first compile has no attached buffers yet, so a buffer nothing else binds would read unit 0's nothing in that frame.

**Built-ins are seeded in this class's own static block**, not by the provider registering itself.
That was tried: self-registration only takes effect once something touches the provider class, which
nothing guarantees before a shader naming the token is parsed — `CgGraphicsLifecycle.init` itself compiles
`text.shader`, which declares `cg_use quad`. Making it work needs an explicit "initialize this class
now" call at exactly the right point in startup, which couples correctness to init order and broke
the headless parser tests. Seeding here has no ordering requirement: the registry initializes on the
parser's first lookup, which is exactly when a token is needed.

> **Do not add a Java-side attach helper for these.** One existed
> (`CgQuadBatch.attachTo(material)`) and was removed: attaching at first use loses to anything
> that compiles the shader earlier, and the resulting undeclared-symbol failure surfaced as an
> unrelated `#pragma cg_feature` complaint. The pragma is the wiring.

### Lifecycle

**SSBO (user buffer per-object data) — format-aware mode**
```java
// userIndex 0 → SSBO binding CgBindingPoints.USER_START_SSBO (0)
CgShaderBuffer myBuf = CgShaderBuffer.create("myData", MY_FORMAT, 0, CgBufferLifetime.FRAME);   // rewritten every frame

myBuf.beginWrite(N);
for (int i = 0; i < N; i++) {
    myBuf.writer().beginRecord().vec4("color", r, g, b, a);
    myBuf.endRecord();
}
myBuf.endWrite();

myBuf.bind();         // noted: what a device pass's sets read at its binding
// draw N instances
```

**UBO — format-aware mode** (the frame block, `CgBufferLifetime.FRAME`)
```java
CgBufferWriter w = frameUbo.writer();
w.reset()
 .beginRecord()
 .mat4("cg_ViewMatrix", view)
 .mat4("cg_ProjMatrix", proj)
 .vec4("cg_Time", t/20, t, t*2, t*3)
 .vec2("cg_Resolution", width, height);
frameUbo.endRecord();  // finalize the record (sets lastWrittenCount = 1)
frameUbo.upload();     // skipped if the bytes match and this frame already has them
frameUbo.bind();       // noted by range at this upload's offset
```

Nothing is wired after a compile: the material compiler declares each block at its binding (`CG_BIND`).

### Threads

A `RETAINED` buffer is made, written and deleted from any thread, as a texture is: its GL work goes through its
`CgDeferral` (`gpu/`), and off the render thread an upload's floats are copied into a direct-memory lease
(`CgUploads.lease(bytes, true)`) by the thread that wrote them, landed with `glBufferSubData` before the next frame
executes. A `FRAME` buffer is the render thread's, made and uploaded there.

```java
CgShaderBuffer table = CgShaderBuffer.create("Heights", HEIGHT_FORMAT, 0);   // on a worker: RETAINED
table.beginWrite(cells);
// ... records ...
table.endWrite();                 // a lease now, the buffer's before the next frame
```

- `bind()` and `getGlBufferId()` land what is queued first, so they must run on the render thread outside a recording;
  `bind()` on a buffer whose storage is not made yet throws.
- Inside a deferred task or a hot path, read `dataBuffer.getGlBuffer()`, never `getGlBufferId()`, which flushes.
- A lease never comes from an unpack buffer: unpack memory is slow to read (`CgUploadLease.bufferSubData` throws on
  one).

### Key Design Rules

- **`name` is first constructor param** for all subclasses. Never null.
- **`validateBindingPoint()` was removed** — `create()` adds per-type USER_START internally.
- **`CgUniformBuffer.blockName` field was removed** — use `getName()` from parent.
- **The TBO path is unhooked** (rhi X2): `CgTextureBuffer`, `USER_START_TBO` and the texel binding are gone; `docs/TBO_PATH.md` reconnects them.
- **Engine blocks bypass the registry** — the frame block and object buffer use high-end binding slots resolved at runtime; the executor owns the object buffer and binds the frame block from each pass's constants.
- **The frame ring only through `CgBufferLifetime.FRAME`** — it binds by range at each upload's offset. A `RETAINED` buffer stays at offset 0, which `glBindBufferBase` reads.
- **`CgBindingPoints.init()` must run before any engine buffer construction** — `CgEngineLifecycle.ensureGraphicsContext` calls it first.
