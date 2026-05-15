# gl/debug — Debug Rendering Utilities

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

One-shot debug utilities for visualizing GPU state — textures, depth buffers, framebuffer
attachments — directly on-screen. All utilities are GL-thread-only, statically accessible,
and lazily initialized to avoid startup overhead.

## Class Map

| Class | Role |
|---|---|
| `CgDebugBlit` | Fullscreen texture blit. Renders any texture (depth or RGBA) as a viewport-covering overlay using the covering-triangle technique. |

## Covering-Triangle Technique

`CgDebugBlit` uses a "big triangle" to fill the screen with zero CPU vertex data:

- **No VBO** — vertex positions come entirely from `gl_VertexID` in the vertex shader
- **One empty VAO** — created with `CgVertexArray.create()`, no `configure()` call (GL 3.x core requires a bound VAO)
- **`GL11.glDrawArrays(GL_TRIANGLES, 0, 3)`** — three vertices produce positions `(-1,-1)`, `(3,-1)`, `(-1,3)` in clip space; the GPU clips the overshoot

This avoids any mesh, VBO, or vertex-write infrastructure for a tool that only runs during development.

## Usage Example

```java
// Visualize the scene depth buffer (near=0.1, far=512):
CgDebugBlit.depth(framebuffer.getDepthAttachmentId(), 0.1f, 512f);

// Visualize a color attachment as-is:
CgDebugBlit.rgba(framebuffer.getColorAttachmentId(0));

// Free resources on context teardown:
CgDebugBlit.dispose();
```

## Lifecycle

- `CgDebugBlit` is a **lazy singleton** — initialized on the first `depth()` or `rgba()` call
- `dispose()` must be called when the GL context is destroyed; wire it into
  `CgGraphicsLifecycle.destroyContext()` or call from your mod's teardown hook
- All methods are **GL-thread-only**

## GL State Behaviour

Every `depth()` and `rgba()` call:
1. Saves **all 16 GL state slots** via `CgGlState.saveAll()`
2. Applies `CgDepthState.NONE`, `CgBlendState.DISABLED`, `CgColorMask.ALL`
3. Draws the covering triangle
4. Restores all saved GL state on scope exit

The caller's GL state is fully preserved across a debug blit call.

## What NOT to Add Here

- No VBOs, meshes, or `CgMeshBuilder` usage — covering triangle needs no vertex data
- No `.shader` files — GLSL is inline strings only inside `CgDebugBlit`
- No hooks into `CgRenderPipeline` or `CgRenderHook` — callers decide when to invoke blits
