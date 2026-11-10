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
| `fromHost`, a host with a state cache (Minecraft) | the host's belief, read from its cache (`GLCheckedHostState`) | a Java read |
| `fromHost`, a host with none (`GLHostState.byQuery`) | every field by `glGet*` | one stall a section, never a draw |

A field no source can say stays `UNKNOWN`, so its first call reaches GL and the filter knows it from then on.

## The host boundary: a device section

A Minecraft host shares the context (GL) or the queue (Vulkan 26.2+) with the game. Our work runs inside a **device
section** (`CgDeviceSection`), opened round every host entry (a render stage's firing, the frame's tick, a paint, the
loading screen), and the state is handed over at the outermost section's two edges, nowhere else:

```java
CgDeviceSection.begin();   // CgRHI.fromHost: the host's belief read, the filter's view of it adopted, conventions set
try (CgExecutor.Into ignored = CgExecutor.into(hostFramebuffer, width, height)) {
    ... the engine's passes ...
}
CgDeviceSection.end();     // CgRHI.toHost: what the section changed set back to what the host believes
```

- **Once a hook.** Sections nest, so a graph's execution or an upload inside a hook opens no hand-over of its own; one
  outside any hook is its own section. A device installed inside an open section takes the frame at once.
- **The host's target is named, never bound.** A hook says what it draws into (`CgExecutor.into`): a stage takes
  `CgHostFrame.mainFramebuffer`, a paint the target its host's `CgHostPaint.Host.enter` names. An execution drawing into
  the current target with none named throws.

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

## Each host's state

| Host | `GLHostState` | `glGet` per section |
|---|---|---|
| Minecraft 1.13–26.2 on GL | `HostStateModern`: `GlStateManager` through `GLHostCache`, plus the program (0 below 1.21.5; its encoder's from 1.21.5), the framebuffers (the main target below 1.21.5; `GlStateManager`'s pair from 1.21.5) and the vertex array (none; `BufferUploader`'s record 1.17–1.19.1). Every texture unit by `glGet` while an Iris/Oculus pack runs, since a pack samples above Blaze3D's table. The first section turns Minecraft's `GL_DEBUG_OUTPUT` off; 1.19.2–1.21.4 forget `BufferUploader`'s vertex array as each closes; 26.2's 0..1 clip range is put back | none, past the checks |
| Forge 1.8.9–1.12.2 | `HostStateLegacy`: the same cache; blend by `glGet` (`blendFunc` sets the alpha pair it does not record); program 0, no vertex array, the main framebuffer and the display's viewport, as at every hook | blend |
| 1.7.10, 26.3 | `GLHostState.byQuery`: no cache to read (on 1.7.10 Angelica answers it from its mirror) | every field |
| the harness | none: the device owns its context | — |

**Over `GlStateManager`** (`GLHostCache`): its fields by shape, as SRG, intermediary and MCP rename them
(`-Dcrystalgraphics.host.stateCache.byShape=true` skips Mojang's names in a dev run), and what Minecraft holds at every
hook for what it does not cache: blend equation, front face, polygon mode, stencil, and the scissor test off where it
keeps none (to 1.16.3). `GLCheckedHostState` checks each domain's first 600 reads against `glGet`; a domain that
disagrees, or that the cache does not answer, reads `glGet` from then on, with a line in the log. The checks wait while
the loading screen draws (`GLCheckedHostState.deferChecksWhile`): a check's `glGet` waits for the driver's queue, deepest
then. A guess the driver may not share (the vertex array from 1.19.2) is set back as guessed and `excuse`d from the
checks. What no domain holds (samplers, pixel store, the pack and unpack buffers, the device's conventions) goes back to
GL's defaults as the section closes. `-Dcrystalgraphics.host.stateCache=false` reads every domain with `glGet`.

A compatibility context's alpha test, which every fragment the device writes would pass through, is off for the section
where the host has it on, and back on as it closes.

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
put out of step, and scopes callers wrapped round their work to cope. No host section reads it any more; what is left
of it serves `CgGL`'s own calls until H7's last step deletes both.
