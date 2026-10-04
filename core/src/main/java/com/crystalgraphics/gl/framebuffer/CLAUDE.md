# gl/framebuffer — Framebuffer Abstraction Layer

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

Provides the FBO (Framebuffer Object) API over core GL 3.0 framebuffers — the GL 3.3 floor has no ARB or
EXT fallback, so `CgFrameBuffer` makes its own GL objects through `CgGL`, and a private subclass wraps foreign FBOs.

## Delegation Pattern

`CgFrameBuffer.create(name, w, h, format)` → `CgFrameBufferRegistry.get().getOrCreate(...)` → `CgFrameBuffer.createInternal(...)` (on cache miss).

This mirrors `CgMaterial.load` → `CgMaterialRegistry.getOrCreate` → `CgMaterial.create` exactly.

---

## Architecture: Parent + Dispatch Pattern

Everything lives in `CgFrameBuffer`, its GL dispatch (`doGenFramebuffer`, `doBindFbo`, ...) one `CgGL` call each.

```
CgFrameBuffer (gl/framebuffer/)  → an owned FBO, through CgGL (core GL 3.0)
    └── WrappedFrameBuffer       → a foreign FBO, not owned: the dispatch overridden with no-ops

CgFrameBufferRegistry       → single source of truth for all owned FBOs;
                              framebuffers LinkedHashMap; screen-sized auto-resize
```

---

## What CgFrameBuffer Owns

| Concern | Location |
|---------|----------|
| Instance fields (`fboId`, `width`, `height`, `name`, `format`, `owned`, `deleted`, `screenSized`) | `CgFrameBuffer` |
| `colorAttachments` (TreeMap<Integer, Attachment>) + `depthAttachment` | `CgFrameBuffer` |
| `initGl(w, h, format)` — allocates textures/renderbuffers, checks completeness | `CgFrameBuffer` |
| `bind/bindDraw/bindRead/unbind` — route through `CrossApiTransition` | `CgFrameBuffer` |
| `drawBuffers(int... slotIds)` — slot indices 0,1,2 → GL_COLOR_ATTACHMENT0+n | `CgFrameBuffer` |
| `reattachColor/reattachColorRaw/reattachDepth` | `CgFrameBuffer` |
| `bindLevel(level)`, `levelWidth/levelHeight` — draws into one mip level of every colour texture, no depth above 0; a framebuffer per level, made at first use | `CgFrameBuffer` |
| `getColorTexture/getDepthTexture/getColorAttachment/getDepthAttachment` | `CgFrameBuffer` |
| `isScreenSized()` — true if created via `CgFrameBufferRegistry.acquireScreenSized` | `CgFrameBuffer` |
| `delete()` — frees GL resources; sets `deleted = true`; does NOT touch registry | `CgFrameBuffer` |
| `wrap(name, fboId, w, h, family)` — non-owned wrapper via `WrappedFrameBuffer` inner class | `CgFrameBuffer` |
| `createScreenSized(name, format)` — delegates to `CgFrameBufferRegistry.acquireScreenSized` | `CgFrameBuffer` |

## Factory Split

| Method | Visibility | Role |
|--------|-----------|------|
| `CgFrameBuffer.create(name, w, h, format)` | `public static` | Thin delegator → `CgFrameBufferRegistry.get().getOrCreate(...)` |
| `CgFrameBuffer.createInternal(name, w, h, format)` | `package-private static` | Real work — `initGl`, validation; called only by registry |

## Attachment Model

`CgFrameBuffer.Attachment` is a public static inner class with two paths:

- **Texture path**: `texture != null`, `renderbufferId == 0` — sampleable via `getTexture()`
- **Renderbuffer path**: `texture == null`, `renderbufferId != 0` — non-sampleable, faster

`Attachment.delete()` calls `parent.deleteRenderbuffer(id)` — routes through the
framebuffer's own dispatch. Never calls `GL30.glDeleteRenderbuffers` directly.

---

## drawBuffers API

```java
fbo.bind();
fbo.drawBuffers(0, 1, 2);  // slot INDICES, not GL_COLOR_ATTACHMENT0+n constants
```

`drawBuffers(int... slotIds)` converts internally to `GL_COLOR_ATTACHMENT0 + n`.

A format with more than one colour slot draws into every slot from creation, its level framebuffers too
(`drawEveryColorSlot`): GL's default is attachment 0 alone, which dropped every other output of a graph pass.
`drawBuffers` narrows it for a caller that wants fewer.

---

## Depth-Only FBOs

When `CgFrameBufferFormat.isDepthOnly()` is true (no color slots),
`GL_DRAW_BUFFER` and `GL_READ_BUFFER` are automatically set to `GL_NONE`
during `initGl`. No manual call needed.

---

## Ownership & Lifecycle

`CgFrameBufferRegistry.framebuffers` (`LinkedHashMap<FrameBufferKey, CgFrameBuffer>`) is the
single source of truth for all owned FBOs, keyed by `(name, format)`.

- **Owned** (`owned == true`): created via `createInternal`; stored in `framebuffers` by registry.
  `delete()` frees GL resources and sets `deleted = true` — does NOT remove from `framebuffers`.
- **Wrapped** (`owned == false`): never in `framebuffers`. `delete()` is a no-op.
  Created via `CgFrameBuffer.wrap(...)`.
- **Screen-sized** (`screenSized == true`): owned FBOs created via `acquireScreenSized`.
  On resize, `fbo.resize(w, h)` is called in-place — the Java reference stays valid.

---

## CgFrameBufferRegistry

```java
// Fixed-size FBO (cached by name+format):
CgFrameBuffer fbo = CgFrameBuffer.create("shadow_map", 1024, 1024, SHADOW_FORMAT);

// Screen-sized FBO — reference is stable across window resizes:
CgFrameBuffer fbo = CgFrameBuffer.createScreenSized("hdr_buffer", HDR_FORMAT);

// In your window-resize handler:
CgGraphicsLifecycle.onResize(newWidth, newHeight);
```

- `getOrCreate(name, w, h, format)` — returns cached FBO or creates at given dimensions on miss.
- `acquireScreenSized(name, format)` — like `getOrCreate` but sets `fbo.screenSized = true`. The returned reference is stable.
- `onResize(w, h)` — calls `fbo.resize(w, h)` in-place on all screen-sized FBOs. Java references remain valid.
- `deleteAll()` — deletes all owned FBOs and clears `framebuffers`. Called by `CgGraphicsLifecycle.destroyContext()`.

**Do not cache screen-sized FBOs across frames** — re-query each frame; the registry
replaces the internal FBO on every resize.

---

## Reading a framebuffer back without a stall — `CgPixelReadback`

```java
CgPixelReadback readback = new CgPixelReadback(3);
readback.request(fbo.getId(), fbo.getWidth(), fbo.getHeight(), 256, tag);   // shrink, queue the read
readback.poll(pixels -> use(pixels.tag(), pixels.rgb()));                     // every frame; never waits
```

A synchronous `glReadPixels` waits for the GPU to finish everything before it. This copies to a
pixel-pack buffer behind a fence and hands the pixels over a frame or two later. It **unbinds
`GL_PIXEL_PACK_BUFFER` at once** — a pack buffer left bound takes every later `glReadPixels` in the
process, a host's screenshot included. Copy the mapped bytes out in ONE bulk `get`: a get per byte from
the mapped buffer measured 2-3 ms for a 256x144 picture.

---

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
