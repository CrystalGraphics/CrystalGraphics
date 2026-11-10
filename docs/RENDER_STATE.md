# Render state — who owns it, on GL and on Vulkan

**A consumer never saves or restores GPU state.** A draw's state is its pipeline's, declared once with its material;
the engine sets it on the device for each draw and hands the context back to the host at a section's edge. Nothing a
mod, Minecraft or another pass left bound can change how a draw of ours looks, and nothing we draw leaks into theirs.

This is how the engine is built on `CgRHI`. What still predates it, `CgGL` and its scopes, is listed at the end and goes
in `rhi` H7 (`plan/crystalgraphics/rhi/steps/H7.md`).

## What a consumer writes

The state lives in the material, beside its shader:

```
Pass {
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
    }
    ...
}
```

and the draw names the material, a mesh and a target; nothing round it:

```java
CgMaterial glow = CgMaterial.load("mymod:shaders/glow.shader");
CgWorldRenderer.get().draw(CgMeshShapes.cube(), glow).at(x, y, z).submit();
```

- No scope, no `glEnable`, no "put it back" after: two draws in a row with different materials each get their own.
- A pass's target, its viewport and its clears are the render pass's (`CgPassDesc`), not state left from the last one.
- The same code on GL and on Vulkan.

## On Vulkan: the API owns it

Vulkan has no global state. A graphics pipeline (a PSO) bakes the shaders, the vertex layout, the raster, depth,
stencil and blend state and the target formats; a render pass names the target; binding sets name the resources. The
Vulkan implementation builds each pipeline once from its description (`CgGraphicsPipelineDesc`) and binds it. There
is nothing to save and nothing to filter.

## On GL: the device owns it, privately

**GL 4.6 is still a global state machine.** DSA (`glCreate*`, `glNamed*`, `glTextureParameter*`) removed bind-to-edit,
so making and filling an object disturbs no binding. What a draw reads is still the context's: the bound program,
vertex array and framebuffer, the blend, depth, stencil and cull enables and functions, viewport and scissor, colour
masks, and the texture units, samplers and buffer points. GL has no PSO.

So the GL implementation emulates one, as Unreal's OpenGL RHI, ANGLE and bgfx do:

| Piece | Job | Unreal's |
|---|---|---|
| `GLGraphicsPipeline` | the pipeline as a description: its linked program, a vertex array for its format, its binding points; its fixed-function state applied by the pass before a draw, never at creation | the PSO fallback |
| `GLContextState` | what the context has, per field; `UNKNOWN` differs from every value | `FOpenGLContextState` |
| `GLStateFilter` | a driver over the raw one: a call setting what the context already has is dropped, every other goes through | the per-field compare in `Update*InOpenGLContext` |
| `GLEncoder` | binds a pipeline and a set by asking only for what differs; resources through the multi-bind calls (`glBindTextures`, `glBindSamplers`, `glBindBuffersRange`) | the pending state applied at a draw |

**So there is still a state manager on GL**, because the API needs one, but it is one class with one owner, below
the RHI. No consumer sees it, and it is exact by construction: inside a device section only the device writes GL, so
what it believes is what the context has.

```java
GLStateFilter gl = new GLStateFilter(new Lwjgl3GLDriver());
gl.glEnable(GL_DEPTH_TEST);
gl.glEnable(GL_DEPTH_TEST);   // dropped: the context has it
```

### How the filter knows what is bound

**By being the only writer, never by asking.** Each call through the filter records what it set; that record is what
the next call is compared against. GL has no way to read the context's state but `glGet*`, and on a threaded driver
each one waits for the driver thread to run everything queued before it: an engine that asks per draw stalls per
draw. AZDO does not make reading state cheaper; it makes less of it needed (DSA, the multi-bind calls, persistent
mapping, multi-draw indirect, bindless), which is what `GLEncoder` uses.

The record is filled three ways:

| When | From | Cost |
|---|---|---|
| Inside a section | the filter's own writes | nothing |
| `fromHost`, a host with a state cache (Minecraft) | the host's belief, read from its cache (`GLHostState`; until H7, `sharedWithCgGL` exports `CgGL`'s shadow) | a Java read |
| `fromHost`, a host with none (`GLHostState.byQuery`) | every field by `glGet*` | one stall a section, never a draw |

A field no source can say stays `UNKNOWN`, so its first call reaches GL and the filter knows it from then on.

## The host boundary: a device section

A Minecraft host shares the context (GL) or the queue (Vulkan 26.2+) with the game. Our work runs inside a **device
section**, opened and closed by the engine round every host entry (a render stage's firing, the frame's tick, the
loading screen), and the state is handed over at its two edges, nowhere else:

```java
device.fromHost();   // the host's belief read, the filter's view of it adopted, the device's conventions set
... the engine's passes ...
device.toHost();     // what the section changed set back to what the host believes
```

- **A diff, not a save.** `toHost` is `GLStateFilter.restore(entry)` with the belief read at `fromHost`, its calls
  through the filter, so only what the section's passes changed reaches GL: nothing is kept for it but that one
  record.
- **What the host believes, not what GL has.** Minecraft's `GlStateManager` skips a call its cache thinks made, so the
  section sets back the cache's belief (`GLHostState`). A field the host keeps no belief about is left as found. Which fields each Minecraft era caches, and what each
  host does about them: `MINECRAFT_RENDERING_CONVENTIONS.md`.
- **The device's conventions** are set at entry and taken back at exit: sRGB writes, zero-to-one clip, seamless cube
  maps, program point size, pack and unpack alignment 1. A convention left on would change the host's own drawing
  (the UI washed out by sRGB encoded twice, the bug that put them here).
- **On Vulkan** the hand-over is the command buffer and the images' layouts: each section's commands spliced into
  Minecraft's submit, its images back in the layout Minecraft expects (`Blaze3dVulkanHost`).

## Foreign GL inside our frame

Code that writes GL outside the device leaves the filter wrong: it skips a call it believes redundant and draws with
the foreign code's state. Two rules keep that from happening:

- **Between sections, anything may run.** At the next `fromHost` the filter's view is the host's belief again.
- **Inside a section, nothing else writes GL** unless the filter is told: `GLStateFilter.invalidate()` makes every
  field unknown, so the next call of each reaches GL. A render stage's callback (`CgStageFrame.callback`,
  `CgRenderStage`'s hook) runs engine work, never GL: what it executes takes the callback's target as its current
  target (`CgExecutor.into`).

## What predates this, and goes in H7

`CgGL`'s state shadow (`CgGlState`, `CgGlSlot`, `CgGlStateShadow`, its providers, `CgGlStateManager`) and `CgGlScope`
(`ENGINE_API.md` § *GL State Save/Restore*) are the old shape: a cache every caller read and wrote, which anything could
put out of step, and scopes callers wrapped round their work to cope. They are still used by code not yet on the RHI
(H7's census: 176 files naming `CgGL`), and on a host before the device is installed; a Minecraft host's entry is read
from that shadow (`GLHostState.sharedWithCgGL`). H7 ports those callers and deletes them, gives each era a
`GLHostState` reading `GlStateManager`'s cache itself, with the native shader path that lets the device draw the loading screen before the shader toolchain
lands.
