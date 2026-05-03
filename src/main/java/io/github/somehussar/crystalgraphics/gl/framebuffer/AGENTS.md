# gl/framebuffer — Framebuffer Abstraction Layer

Provides a unified FBO (Framebuffer Object) API across three OpenGL backends:
**Core GL30**, **ARB_framebuffer_object**, and **EXT_framebuffer_object**.

---

## Architecture: Parent + Dispatch Pattern

All shared logic lives in `CgAbstractFramebuffer`. Each concrete subclass contains
only static factory methods and 10 one-line `glDo*()` dispatch overrides that route
calls to their respective LWJGL class.

```
CgFramebuffer (interface, api/framebuffer/)
    └── CgAbstractFramebuffer (all shared logic + 10 abstract dispatch methods)
            ├── CgCoreFramebuffer   → routes via GL30.*
            ├── CgArbFramebuffer    → routes via ARBFramebufferObject.*
            └── CgExtFramebuffer    → routes via EXTFramebufferObject.*EXT()
```

`CgFramebufferFactory` selects the best available backend via waterfall
(Core → ARB → EXT) and exposes `create`, `createFromSpec`, and `wrap`.

---

## What CgAbstractFramebuffer Owns

| Concern | Location |
|---|---|
| Instance fields (`fboId`, `width`, `height`, `colorTextures`, `depthTexture`, etc.) | `CgAbstractFramebuffer` |
| `RuntimeSlot` inner class (runtime attachment tracking) | `CgAbstractFramebuffer` |
| `specFrom` / `depthSpecFrom` static helpers | `CgAbstractFramebuffer` |
| `getColorTextureId`, `getDepthTextureId` | `CgAbstractFramebuffer` |
| `getRuntimeAttachments`, `RuntimeAttachmentsImpl` inner class | `CgAbstractFramebuffer` |
| `drawBuffers` (Core + ARB; EXT overrides to always throw) | `CgAbstractFramebuffer` |
| `freeGlResources` (uses abstract dispatch) | `CgAbstractFramebuffer` |
| `resize` / `resizeLegacy` / `resizeSpec` / `resizeRuntimeSlots` | `CgAbstractFramebuffer` |
| Static ownership tracker `ALL_OWNED` + `freeAll()` | `CgAbstractFramebuffer` |

## What Each Subclass Owns

- `create(width, height, depth, mrt)` — legacy single-color factory
- `createFromSpec(CgFramebufferSpec)` — multi-attachment spec factory
- `cleanupOnFailure(...)` — static failure-atomic cleanup for its backend
- `callFamily()` — returns `CORE_GL30`, `ARB_FBO`, or `EXT_FBO`
- `newFromSpec(spec)` — delegates to `createFromSpec` (used by `resizeSpec`)
- 10 `glDo*()` dispatch overrides (one line each)

---

## Abstract GL Dispatch Methods

These are the only things that differ between backends. Each is a single LWJGL call:

| Method | Core | ARB | EXT |
|---|---|---|---|
| `glDoGenFramebuffer()` | `GL30.glGenFramebuffers()` | `ARBFramebufferObject.glGenFramebuffers()` | `EXTFramebufferObject.glGenFramebuffersEXT()` |
| `glDoDeleteFramebuffer(id)` | `GL30.glDeleteFramebuffers` | `ARBFramebufferObject.glDeleteFramebuffers` | `EXTFramebufferObject.glDeleteFramebuffersEXT` |
| `glDoBindFbo(id)` | `GL30.glBindFramebuffer(GL_FRAMEBUFFER, id)` | `ARBFramebufferObject.glBindFramebuffer(...)` | `EXTFramebufferObject.glBindFramebufferEXT(...)` |
| `glDoFramebufferTexture2D(att, texId)` | `GL30.glFramebufferTexture2D(...)` | `ARBFramebufferObject.glFramebufferTexture2D(...)` | `EXTFramebufferObject.glFramebufferTexture2DEXT(...)` |
| `glDoCheckFramebufferStatus()` | `GL30.glCheckFramebufferStatus(...)` | `ARBFramebufferObject.glCheckFramebufferStatus(...)` | `EXTFramebufferObject.glCheckFramebufferStatusEXT(...)` |
| `glDoGenRenderbuffer()` | `GL30.glGenRenderbuffers()` | `ARBFramebufferObject.glGenRenderbuffers()` | `EXTFramebufferObject.glGenRenderbuffersEXT()` |
| `glDoBindRenderbuffer(id)` | `GL30.glBindRenderbuffer(...)` | `ARBFramebufferObject.glBindRenderbuffer(...)` | `EXTFramebufferObject.glBindRenderbufferEXT(...)` |
| `glDoRenderbufferStorage(fmt,w,h)` | `GL30.glRenderbufferStorage(...)` | `ARBFramebufferObject.glRenderbufferStorage(...)` | `EXTFramebufferObject.glRenderbufferStorageEXT(...)` |
| `glDoDeleteRenderbuffer(id)` | `GL30.glDeleteRenderbuffers` | `ARBFramebufferObject.glDeleteRenderbuffers` | `EXTFramebufferObject.glDeleteRenderbuffersEXT` |
| `glDoFramebufferRenderbuffer(att,rbo)` | `GL30.glFramebufferRenderbuffer(...)` | `ARBFramebufferObject.glFramebufferRenderbuffer(...)` | `EXTFramebufferObject.glFramebufferRenderbufferEXT(...)` |

---

## EXT-Specific Quirks

- **No separate draw/read targets**: `bindDraw()` and `bindRead()` both bind to
  `GL_FRAMEBUFFER_EXT`. Overridden in `CgExtFramebuffer`.
- **No MRT**: `drawBuffers()` always throws `UnsupportedOperationException`. Overridden.
- **No `GL_DEPTH_STENCIL_ATTACHMENT`**: Packed depth-stencil attaches the same RBO
  to both `GL_DEPTH_ATTACHMENT_EXT` and `GL_STENCIL_ATTACHMENT_EXT`. The factory
  sets `stencilRenderbufferId = depthRenderbufferId`. `freeGlResources` guards against
  double-delete with `stencilRenderbufferId != depthRenderbufferId`.

---

## Two Creation Paths

### Legacy path — `create(width, height, depth, mrt)`
Single color texture at `GL_COLOR_ATTACHMENT0`, optional depth renderbuffer.
Uses `singleColorTexture` field. `spec` is `null`.

### Spec-based path — `createFromSpec(CgFramebufferSpec)`
N color textures, optional depth/stencil as texture or renderbuffer, optional
packed depth-stencil. Uses `colorTextures[]` array. `spec` is retained for resize.

---

## Resize Behavior

`resize(w, h)` dispatches to:
- `resizeLegacy` — if `spec == null`: frees old resources, allocates new ones via
  `glDo*` dispatch, re-attaches runtime slots.
- `resizeSpec` — if `spec != null`: calls `newFromSpec(newSpec)` to build a fresh
  instance, then steals its GL resource IDs into `this` and marks the shell deleted.
  Runtime slots are re-attached via `resizeRuntimeSlots`.

---

## Ownership & Lifecycle

- **Owned** (`owned == true`): added to `ALL_OWNED` on construction. `delete()` calls
  `freeGlResources()` then removes from `ALL_OWNED`. `freeAll()` deletes all at shutdown.
- **Wrapped** (`owned == false`): not in `ALL_OWNED`. `delete()` throws
  `IllegalStateException`. Created via `CgFramebufferFactory.wrap(...)`.

---

## GL Constants

All constants used by shared logic are defined as `static final int` in
`CgAbstractFramebuffer` with package-level visibility (`static final int`, no modifier).
Subclasses define only the constants they need for their own factory methods.
All numeric values are identical between Core/ARB/EXT (e.g. `GL_FRAMEBUFFER = 0x8D40`).

---

## Adding a New Backend

1. Extend `CgAbstractFramebuffer`.
2. Add two private constructors (legacy path + spec path) calling the appropriate
   `super(...)` constructor.
3. Add a package-private wrap constructor calling `super(fboId, width, height, supportsMrt)`.
4. Implement `create`, `createFromSpec`, `cleanupOnFailure`, `callFamily`, `newFromSpec`.
5. Implement all 10 `glDo*()` methods, each a single call to your LWJGL class.
6. Register the new backend in `CgFramebufferFactory` waterfall.
