# gl/framebuffer — Framebuffer Abstraction Layer

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

Provides a unified FBO (Framebuffer Object) API across three OpenGL backends:
**Core GL30**, **ARB_framebuffer_object**, and **EXT_framebuffer_object**.

## Delegation Pattern

`CgFrameBuffer.create(name, w, h, format)` → `CgFrameBufferRegistry.get().getOrCreate(...)` → `CgFrameBuffer.createInternal(...)` (on cache miss).

This mirrors `CgMaterial.load` → `CgMaterialRegistry.getOrCreate` → `CgMaterial.create` exactly.

---

## Architecture: Parent + Dispatch Pattern

All shared logic lives in `CgFrameBuffer`. Each concrete backend contains
only a constructor and nine one-line dispatch overrides that route calls to
their respective LWJGL class.

```
CgFrameBuffer (abstract base, gl/framebuffer/)
    ├── CgCoreFrameBuffer   → routes via GL30.*
    ├── CgArbFrameBuffer    → routes via ARBFramebufferObject.*
    └── CgExtFrameBuffer    → routes via EXTFramebufferObject.*EXT()

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
| `getColorTexture/getDepthTexture/getColorAttachment/getDepthAttachment` | `CgFrameBuffer` |
| `isScreenSized()` — true if created via `CgFrameBufferRegistry.acquireScreenSized` | `CgFrameBuffer` |
| `delete()` — frees GL resources; sets `deleted = true`; does NOT touch registry | `CgFrameBuffer` |
| `wrap(name, fboId, w, h, family)` — non-owned wrapper via `WrappedFrameBuffer` inner class | `CgFrameBuffer` |
| `createScreenSized(name, format)` — delegates to `CgFrameBufferRegistry.acquireScreenSized` | `CgFrameBuffer` |

## Factory Split

| Method | Visibility | Role |
|--------|-----------|------|
| `CgFrameBuffer.create(name, w, h, format)` | `public static` | Thin delegator → `CgFrameBufferRegistry.get().getOrCreate(...)` |
| `CgFrameBuffer.createInternal(name, w, h, format)` | `package-private static` | Real work — backend selection, `initGl`, validation; called only by registry |

## What Each Backend Owns

- One package-private constructor `(String name, CgFrameBufferFormat format, int width, int height)`
  calling `super(name, format, width, height)`.
- `callFamily()` — returns `CORE_GL30`, `ARB_FBO`, or `EXT_FBO`.
- Nine dispatch overrides (one line each) — see table below.

---

## Abstract GL Dispatch Methods

These are the only things that differ between backends. Each is a single LWJGL call:

| Abstract Method | Core | ARB | EXT |
|----------------|------|-----|-----|
| `doGenFramebuffer()` | `GL30.glGenFramebuffers()` | `ARBFramebufferObject.glGenFramebuffers()` | `EXTFramebufferObject.glGenFramebuffersEXT()` |
| `deleteFramebuffer(id)` | `GL30.glDeleteFramebuffers` | `ARBFramebufferObject.glDeleteFramebuffers` | `EXTFramebufferObject.glDeleteFramebuffersEXT` |
| `deleteRenderbuffer(id)` | `GL30.glDeleteRenderbuffers` | `ARBFramebufferObject.glDeleteRenderbuffers` | `EXTFramebufferObject.glDeleteRenderbuffersEXT` |
| `doBindFbo(target, id)` | `GL30.glBindFramebuffer(target, id)` | `ARBFramebufferObject.glBindFramebuffer(target, id)` | `EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, id)` |
| `doFramebufferTexture2D(target, att, texTarget, texId)` | `GL30.glFramebufferTexture2D(...)` | `ARBFramebufferObject.glFramebufferTexture2D(...)` | `EXTFramebufferObject.glFramebufferTexture2DEXT(...)` |
| `doFramebufferRenderbuffer(target, att, rboId)` | `GL30.glFramebufferRenderbuffer(...)` | `ARBFramebufferObject.glFramebufferRenderbuffer(...)` | `EXTFramebufferObject.glFramebufferRenderbufferEXT(...)` |
| `doGenRenderbuffer()` | `GL30.glGenRenderbuffers()` | `ARBFramebufferObject.glGenRenderbuffers()` | `EXTFramebufferObject.glGenRenderbuffersEXT()` |
| `doRenderbufferStorage(fmt,w,h)` | `GL30.glRenderbufferStorage(...)` | `ARBFramebufferObject.glRenderbufferStorage(...)` | `EXTFramebufferObject.glRenderbufferStorageEXT(...)` |
| `doCheckFramebufferStatus()` | `GL30.glCheckFramebufferStatus(...)` | `ARBFramebufferObject.glCheckFramebufferStatus(...)` | `EXTFramebufferObject.glCheckFramebufferStatusEXT(...)` |

---

## EXT-Specific Quirks

- **No separate draw/read targets**: `bindDraw()` and `bindRead()` both bind to
  `GL_FRAMEBUFFER_EXT`. Overridden in `CgExtFrameBuffer`.
- **No MRT**: `drawBuffers()` always throws `UnsupportedOperationException`. Overridden.
- **`doBindFbo` ignores the `target` argument**: EXT only exposes the combined target.

---

## Attachment Model

`CgFrameBuffer.Attachment` is a public static inner class with two paths:

- **Texture path**: `texture != null`, `renderbufferId == 0` — sampleable via `getTexture()`
- **Renderbuffer path**: `texture == null`, `renderbufferId != 0` — non-sampleable, faster

`Attachment.delete()` calls `parent.deleteRenderbuffer(id)` — routes through the
backend's abstract dispatch. Never calls `GL30.glDeleteRenderbuffers` directly.

---

## drawBuffers API

```java
fbo.bind();
fbo.drawBuffers(0, 1, 2);  // slot INDICES, not GL_COLOR_ATTACHMENT0+n constants
```

`drawBuffers(int... slotIds)` converts internally to `GL_COLOR_ATTACHMENT0 + n`.
EXT backend always throws `UnsupportedOperationException`.

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

## Adding a New Backend

1. Extend `CgFrameBuffer` (package-private — no `public`).
2. Add one package-private constructor `(String name, CgFrameBufferFormat format, int w, int h)` calling `super(name, format, w, h)`.
3. Implement `callFamily()`.
4. Implement all nine abstract dispatch methods (one line each).
5. Override `bindDraw()`, `bindRead()`, and `drawBuffers()` if needed (EXT pattern).
6. No factory methods needed — `CgFrameBuffer.createInternal()` handles backend selection.
