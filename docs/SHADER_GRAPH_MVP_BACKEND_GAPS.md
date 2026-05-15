# CrystalGraphics — Shader Graph MVP Backend Gap Analysis

> **Date**: May 2026  
> **Goal**: Identify everything CrystalGraphics needs before a Unity-style shader graph
> editor can be built on top of CrystalGUI + CrystalGraphics.

---

## What Already Exists (Don't Rebuild)

Before gaps — credit where it's due. The framebufferPath is significantly ahead of where most
people would expect a 1.7.10 mod to be.

| System | Status | Notes |
|---|---|---|
| FBO abstraction (Core/ARB/EXT waterfall) | ✅ Complete | MRT, resize, bind/read/draw split |
| FBO spec + texture attachments | ✅ Complete | `CgFramebufferSpec`, color/depth/stencil specs, runtime attach |
| `getColorTextureId(n)` / `getDepthTextureId()` | ✅ Complete | Can read texture IDs off FBO attachments |
| Shader program abstraction (GL20/ARB) | ✅ Complete | `CgShaderProgram` with full uniform API |
| Managed shader lifecycle | ✅ Complete | `CgShader`: compile, fail-safe, markDirty, hot-reload |
| Shader cache + manager | ✅ Complete | `CgShaderManager`: load, cache, reloadAll |
| Shader bindings (deferred uniform API) | ✅ Complete | `CgShaderBindings`: vec2/3/4, mat3/4, arrays, colorARGB |
| Scoped shader binding | ✅ Complete | `CgShader.bindScoped()`, try-with-resources |
| GLSL `#define` preprocessor | ✅ Complete | `CgShaderPreprocessor` V1 |
| GLSL `#include` preprocessor | ✅ Complete | `CgShaderPreprocessor` V2: `#include`, `#pragma once`, cycle detection, GLSL std lib shipped |
| `sampler2DRaw(name, unit, texId)` | ✅ Complete | `CgShaderBindings.sampler2DRaw(...)` implemented |
| `CgTexture` standalone texture object | ✅ Complete | `CgTexture`, `CgTextureSpec`, `CgTexture2D/2DArray/3D/Cubemap` all shipped |
| Mipmap generation | ✅ Complete | `CgTextureSpec.generateMipmaps(target)` |
| Instanced rendering | ✅ Complete | `CgInstanceRenderer`, `CgQuadInstanceRenderer`, `CgInstanceFormat`, full instancing stack |
| Blend state | ✅ Complete | `CgBlendState`: disabled/alpha/premul/additive + custom `glBlendFuncSeparate` |
| Depth + cull state | ✅ Complete | `CgDepthState`, `CgCullState` with standard presets |
| VAO/VBO streaming (3-tier waterfall) | ✅ Complete | Sync ring → orphan → subdata |
| Batch renderer (immediate + upload-once) | ✅ Complete | `CgBatchRenderer` V3.1 |
| External GL state capture (coremod) | ✅ Complete | ASM callsite rewriter, Angelica-safe |
| State boundary save/restore | ✅ Complete | `CgStateBoundary` |

---

## Gap Analysis: Priority Tiers

---

### P0 — Blocking Gaps (Shader Graph Cannot Function Without These)

---

#### P0.1 — `sampler2D` by Raw GL Texture ID in `CgShaderBindings`

**What's missing:**  
`CgShaderBindings.sampler2D()` only accepts `ResourceLocation` — it binds through
Minecraft's texture manager. There is no variant that takes a raw `int textureId`.

**Why it blocks:**  
The core shader graph workflow is:
1. Render a preview scene into an FBO
2. Pass `fbo.getColorTextureId(0)` into the shader as a `sampler2D`

Step 2 is impossible today. You can get the texture ID from `CgFramebuffer`, but you
cannot inject it into a shader via `CgShaderBindings`. You'd have to bypass the entire
binding system and call raw GL — which defeats the abstraction.

**What to add:**
```java
// in CgShaderBindings:
CgShaderBindings sampler2DRaw(String name, int unit, int glTextureId);
CgShaderBindings sampler2DRaw(String name, int unit, int glTextureId, int glTarget); // for non-GL_TEXTURE_2D
```

**Effort**: Trivial — one additional binding type in the existing deferred patch system.

---

#### P0.2 — Standalone `CgTexture` Object

**What's missing:**  
There is no managed texture object that lives independently of an FBO. All textures in
the system are either Minecraft `ResourceLocation`-managed or raw IDs from FBO
attachments. There is no API to:
- Create a texture from pixel data / a `BufferedImage`
- Set filtering (nearest, linear, mipmap)
- Set wrap mode (clamp to edge, repeat, border)
- Upload updated pixel data (for live preview frames)
- Generate mipmaps

**Why it blocks:**  
Shader graph nodes need texture inputs — a "Texture Sample" node must load and display
an actual texture. Without `CgTexture`, you have no way to get an arbitrary image into a
shader without going through Minecraft's texture manager (which is restrictive and doesn't
support custom formats or filter modes).

Also needed for: noise textures, LUTs, icon textures in the node UI itself.

**What to add:**
```
api/texture/
  CgTexture              — managed GL texture (create, upload, filter, wrap, delete)
  CgTextureSpec          — immutable creation spec (format, filter, wrap, mipmap)
  CgTextureFilter        — enum: NEAREST, LINEAR, LINEAR_MIPMAP_LINEAR, etc.
  CgTextureWrap          — enum: CLAMP_TO_EDGE, REPEAT, MIRRORED_REPEAT
  CgPixelData            — CPU-side pixel buffer (format, width, height, data)

gl/texture/
  CgTextureImpl          — GL20/ARB implementation
```

**Effort**: Medium (1–2 days). The GL calls are straightforward; the design work is
in the API and lifecycle model.

---

#### P0.3 — GLSL `#include` Preprocessor (V2)

**What's missing:**  
`CgShaderPreprocessor` is V1 by design: it explicitly does not support `#include`.

**Why it blocks:**  
A shader graph emits GLSL by composing many node functions. Without `#include`, every
node's GLSL utility functions must be inlined into a single monolithic source string.
This is workable for trivial graphs but becomes completely unmanageable for real shader
graphs (30+ nodes with shared math libraries).

At minimum you need:
- `#include "crystalgui:shader/lib/math.glsl"` → resolves from resource system
- Standard utility libraries: `noise.glsl`, `color_space.glsl`, `math.glsl`, `pbr.glsl`

**What to add:**
```
CgShaderPreprocessorV2
  - #include resolution: ResourceLocation-based file lookup
  - Cycle detection (A includes B includes A)
  - Include guard support (#pragma once)
  - Standard library shipped with CrystalGraphics under assets/crystalgraphics/shaders/lib/
```

**Effort**: Medium (1–2 days for core; more for the standard library content).

---

### P1 — Important (Shader Graph Will Work But Be Painful Without These)

---

#### P1.1 — Multi-Pass Render Pipeline Orchestrator

**What's missing:**  
There is no high-level "render pass sequence" abstraction. Today you manually:
1. Bind FBO
2. Bind shader
3. Draw fullscreen quad
4. Unbind
5. Repeat for next pass

For a shader graph with effects (blur, bloom, tone mapping, compositing), you'll write
the same boilerplate 10 times per frame with subtle ordering bugs.

**What to add:**
```
api/pass/
  CgRenderGraph          — DAG of render passes with declared inputs/outputs
  CgRenderPass           — one FBO + one shader + clear policy + blend + draw call
  CgPassInput            — named texture input (from prior pass output or external)
  CgPassOutput           — named texture output (FBO color attachment slot)

gl/pass/ (already has CgProjectionMode, CgClearPolicy — extend these)
  CgRenderGraphExecutor  — topological sort + execution
```

This doesn't need to be a full Render Graph with GPU barriers (that's overkill here).
A simple ordered list of passes with declared texture hand-offs is sufficient for MVP.

**Effort**: Large (3–5 days). The API design is the hard part.

---

#### P1.2 — Blend Equation Support

**What's missing:**  
`CgBlendState` uses `glBlendFuncSeparate` (controls src/dst factors) but never calls
`glBlendEquation` or `glBlendEquationSeparate`. The blend equation is always `GL_FUNC_ADD`
(the default).

**Why it matters:**  
Several material blend modes require non-ADD equations:
- **Darken / Lighten**: `GL_MIN` / `GL_MAX`
- **Subtract**: `GL_FUNC_SUBTRACT` / `GL_FUNC_REVERSE_SUBTRACT`
- Post-processing effects (HDR tone mapping, bloom compositing)

**What to add:**
```java
// Extend CgBlendState record:
public record CgBlendState(
    boolean enabled,
    int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
    int equationRgb,   // NEW: GL_FUNC_ADD, GL_MIN, GL_MAX, GL_FUNC_SUBTRACT
    int equationAlpha  // NEW
)

// apply() adds:
GL14.glBlendEquationSeparate(equationRgb, equationAlpha);

// New presets:
CgBlendState.MIN
CgBlendState.MAX
CgBlendState.SUBTRACT
```

**Effort**: Trivial (< 1 hour). Pure record field addition + apply() update.

---

#### P1.3 — Fullscreen Quad Utility

**What's missing:**  
Every render pass needs a fullscreen quad (two triangles covering NDC space). Today
callers must manually push 4 vertices into `CgBatchRenderer` every pass. This is
boilerplate repeated everywhere.

**What to add:**
```
gl/util/
  CgFullscreenQuad       — static utility: draw a single NDC-space quad through a given
                           CgBatchRenderer or directly via a dedicated VAO
```

**Effort**: Tiny (30 minutes).

---

#### P1.4 — `CgRenderState` Framebuffer Slot

**What's missing:**  
`CgRenderState` manages shader + blend + depth + cull + texture. It does not include the
target framebuffer. When building a render pass, callers must bind the FBO themselves
before `CgRenderState.apply()`.

For a multi-pass system, making the FBO part of the render state would eliminate an
entire class of "forgot to bind the FBO" bugs.

**What to add:**
```java
// Extend CgRenderState builder:
.framebuffer(CgFramebuffer fbo)   // optional; null = default framebuffer

// apply() adds:
if (framebuffer != null) framebuffer.bind();
```

**Effort**: Small (2–3 hours). Mostly API threading through the builder.

---

### P2 — Nice to Have (Improve Performance/Ergonomics, Not MVP-Critical)

---

#### P2.1 — Uniform Buffer Objects (UBOs)

**What's missing:**  
No `CgUniformBuffer` / UBO abstraction. All uniforms are pushed via individual
`glUniform*` calls.

**Why it matters for shader graph:**  
A material might have 20–40 uniform parameters (roughness, metallic, albedo, normals,
custom coefficients). Without UBOs, every parameter change is a separate GL call.
UBOs let you pack all material parameters into a single buffer upload.

**What to add:**
```
api/buffer/
  CgUniformBuffer        — managed UBO (create, upload struct, bind to binding point)
  CgUniformBlockBinding  — named binding point registry

gl/buffer/
  CgUniformBufferImpl    — GL31 UBO implementation with fallback to individual uniforms
```

**Note:** Requires GL 3.1 (`GL_ARB_uniform_buffer_object`). Check capability before use.  
**Effort**: Medium (2 days).

---

#### P2.2 — Instanced Rendering

**What's missing:**  
`CgBatchRenderer.flush()` calls `glDrawElements`. There is no `glDrawElementsInstanced`
path.

**Why it matters for shader graph:**  
Node connection wires (bezier curves) are the most numerous draw calls in a shader graph.
With instancing, you submit one call with N instances and let the GPU do the work.
Without it, you draw each wire individually.

Also useful for: node backgrounds, port circles, grid lines.

**What to add:**
```java
// In CgBatchRenderer or a new CgInstancedRenderer:
void flushInstanced(int instanceCount);
// + per-instance attribute buffer support (glVertexAttribDivisor)
```

**Effort**: Medium (2 days). Requires extending the VAO binding to support divisor attributes.

---

#### P2.3 — Mipmap Generation API

**What's missing:**  
No `glGenerateMipmap` call exposed via CrystalGraphics API. Required for texture nodes
that need mipmapped sampling.

**What to add:**
```java
// On CgTexture (from P0.2):
void generateMipmaps();
```

**Effort**: Trivial — one GL call, once `CgTexture` exists (P0.2 dependency).

---

#### P2.4 — Shader Compilation Error Reporting API

**What exists:**  
Compilation errors are currently logged. `CgShader.isCompiled()` tells you if it failed.

**What's missing:**  
No way to retrieve the compilation error string programmatically. A shader graph editor
needs to display inline GLSL errors next to the offending node — you can't do that if the
error message only goes to the log.

**What to add:**
```java
// On CgShader:
String getLastCompileError();   // null if compiled successfully or never attempted
List<CgShaderDiagnostic> getDiagnostics();  // parsed line/column/message

@Value
class CgShaderDiagnostic {
    CgDiagnosticSeverity severity;  // ERROR, WARNING
    int line;
    String message;
}
```

**Effort**: Small (2–3 hours). Parse the GL info log string that's already being captured.

---

## Summary Table

| Gap | Priority | Effort | Blocks | Status |
|---|---|---|---|---|
| `sampler2DRaw(name, unit, texId)` in `CgShaderBindings` | **P0** | Trivial | FBO → shader pipeline | ✅ Done |
| `CgTexture` standalone texture object | **P0** | Medium | Texture input nodes | ✅ Done |
| GLSL `#include` preprocessor V2 | **P0** | Medium | Multi-node shader composition | ✅ Done |
| Multi-pass render pipeline (`CgRenderGraph`) | **P1** | Large | Effects, post-processing | ❌ Not started |
| Blend equation support (`GL_MIN`/`GL_MAX`/etc.) | **P1** | Trivial | Material blend modes | ❌ Not started |
| Fullscreen quad utility | **P1** | Tiny | Every render pass | ❌ Not started |
| FBO slot in `CgRenderState` | **P1** | Small | Ergonomics / correctness | ❌ Not started |
| UBO support | **P2** | Medium | Material parameter efficiency | ❌ Not started |
| Instanced rendering | **P2** | Medium | Wire batching performance | ✅ Done |
| Mipmap generation | **P2** | Trivial | Mipmapped textures | ✅ Done |
| Shader diagnostic / error reporting API | **P2** | Small | In-editor error display | ❌ Not started |

---

## Recommended Build Order

```
Phase 1 — Unblock the pipe (P0):
  1. sampler2DRaw               ← 30 min, immediate unblock
  2. CgTexture                  ← standalone texture lifecycle
  3. GLSL #include preprocessor ← shader composition foundation

Phase 2 — Make it usable (P1):
  4. Blend equations            ← trivial, do alongside Phase 1
  5. Fullscreen quad utility    ← tiny, do alongside Phase 1
  6. FBO slot in CgRenderState  ← small, ergonomics payoff
  7. CgRenderGraph / multi-pass ← hardest P1, do last

Phase 3 — Performance + polish (P2, post-MVP):
  8. UBO support
  9. Instanced rendering
  10. Mipmap generation (free once CgTexture exists)
  11. Shader diagnostic API
```

---

## What Lives in CrystalGUI (Not Cg)

The shader graph UI itself — nodes, ports, canvas, bezier wires, property inspector —
is a CrystalGUI concern. CrystalGraphics only needs to provide the rendering substrate.

The GLSL code generator (graph → GLSL source) is also a CrystalGUI/application concern,
not a CrystalGraphics concern. CrystalGraphics just needs the `#include` preprocessor
to make composed GLSL manageable.

---

*Generated from direct source audit of `CrystalGraphics/src/main/java/` — May 2026*
