# Adding Stencil Support (CrystalGraphics) for Angelica/Iris-Style Framebuffers

This document describes practical ways to ensure stencil is present on the framebuffers used by Angelica/Iris, and what (if anything) CrystalGraphics should add/adjust to support those patterns.

It is written for:

- Java 8
- LWJGL 2.9.3
- Minecraft 1.7.10 (Forge)
- CrystalGraphics' Core/ARB/EXT FBO abstraction

## What Angelica/Iris Actually Needs

From `research_repos/temp_angelica`:

- Iris prefers to *re-use Minecraft's main depth attachment* to avoid incompatibilities with mods that require stencil.
  - See `research_repos/temp_angelica/src/main/java/net/coderbot/iris/postprocess/FinalPassRenderer.java` (commented explanation)
  - The main depth attachment is injected as a **depth texture** by `research_repos/temp_angelica/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/MixinFramebuffer.java`.

The stencil story is therefore mostly:

1) Ensure the *main* framebuffer depth texture is created as `depth+stencil` when stencil is requested.
2) Ensure all derived FBOs correctly attach that depth texture as a depth-stencil attachment.

In this checkout, Angelica already does (2) automatically via Iris' `GlFramebuffer.addDepthAttachment(...)`, which detects combined formats and chooses `GL_DEPTH_STENCIL_ATTACHMENT`.

## How Angelica Enables Depth+Stencil Today

- `MixinFramebuffer` checks `MinecraftForgeClient.getStencilBits() != 0`.
  - If true: allocates the depth texture as `GL_DEPTH24_STENCIL8` and attaches it to both `GL_DEPTH_ATTACHMENT` and `GL_STENCIL_ATTACHMENT`.
  - If false: allocates a depth-only texture.

Practical takeaway:

- If stencil isn't showing up in Iris/Angelica rendering, the first place to look is whether Forge is requesting stencil bits (and whether the GPU/driver supports the depth-stencil texture format).

## CrystalGraphics: Current Capabilities vs. What Is Missing

CrystalGraphics already supports *renderbuffer-based* stencil attachments via `CgDepthStencilSpec`:

- `CgDepthStencilSpec.packedDepthStencil(GL_DEPTH24_STENCIL8)` (packed renderbuffer)
- `CgDepthStencilSpec.separate(depthFormat, stencilFormat)` (separate renderbuffers)
- `CgDepthStencilSpec.stencilOnly(GL_STENCIL_INDEX8)`

Implemented in backends:

- Core GL30: `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/CoreFramebuffer.java`
- ARB: `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/ArbFramebuffer.java`
- EXT: `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/ExtFramebuffer.java`
  - EXT has no `GL_DEPTH_STENCIL_ATTACHMENT`; CrystalGraphics correctly attaches a single packed RBO to both depth + stencil attachment points.

What CrystalGraphics does *not* currently support:

- A **packed depth-stencil texture** attachment (e.g., allocating a `GL_DEPTH24_STENCIL8` texture and attaching it to the FBO).

Why this matters:

- Angelica/Iris uses a depth texture for sampling, and in the stencil-enabled path it uses `GL_DEPTH24_STENCIL8` **as a texture**, not a renderbuffer.
- If CrystalGraphics wants to create FBOs that behave like Angelica's main framebuffer depth texture, it likely needs to support a packed depth-stencil **texture** mode.

## Recommended Practical Approaches

### Approach A (No CrystalGraphics changes): Rely on Angelica's main depth texture

If CrystalGraphics is only being used *alongside* Angelica/Iris (not replacing their render target system), the simplest practical route is:

- Do not attempt to replace Iris' depth system.
- If you need stencil in your own rendering, use `CgStateBoundary` to preserve bindings and render into the current framebuffer (which may already have stencil).
- If you need your own offscreen FBO with stencil, create it in CrystalGraphics using `CgDepthStencilSpec.packedDepthStencil(...)` or `separate(...)`.

This keeps compatibility risk low.

### Approach B (Small CrystalGraphics enhancement): Add packed depth-stencil *texture* support

Add a new mode to `CgDepthStencilSpec` to allocate a packed depth-stencil attachment as a texture.

Proposed API addition (conceptual):

```java
// New factory method
public static CgDepthStencilSpec packedDepthStencilTexture(int packedFormat) { ... }

// Semantics:
// - hasDepth=true, hasStencil=true, isPacked=true
// - depthAsTexture=true (but specifically for packed depth-stencil)
// - requires a "depth-stencil texture" capability check
```

Backend behavior:

- Core GL30 / ARB:
  - Allocate `GL_TEXTURE_2D` with `internalFormat = GL_DEPTH24_STENCIL8`.
  - Use `format = GL_DEPTH_STENCIL`, `type = GL_UNSIGNED_INT_24_8`.
  - Attach using `GL_DEPTH_STENCIL_ATTACHMENT` (or attach to both depth + stencil as fallback).

- EXT:
  - Allocate the same `GL_TEXTURE_2D`.
  - Attach to both `GL_DEPTH_ATTACHMENT_EXT` and `GL_STENCIL_ATTACHMENT_EXT` (EXT has no combined attachment point).

CrystalGraphics already has the plumbing to attach depth textures (depth-only) and packed renderbuffers; this extends it to cover the combined texture case.

### Approach C (If packed depth-stencil textures are unreliable): Separate depth texture + stencil renderbuffer

This is the "most compatible" *sampling + stencil* option for older drivers:

- Depth: allocate depth-only texture (`GL_DEPTH_COMPONENT16/24`) for sampling.
- Stencil: allocate `GL_STENCIL_INDEX8` renderbuffer and attach it.

This avoids relying on `GL_DEPTH24_STENCIL8` textures while still providing stencil.

It requires API support in CrystalGraphics for "depth texture + stencil renderbuffer" as a combined spec.

## Capability Checks and Fallback Rules (Practical)

Because Minecraft 1.7.10 targets old GPUs/drivers, treat depth-stencil textures as potentially fragile.

Recommended decision tree when stencil is requested:

1) If you do NOT need to sample depth: prefer `packedDepthStencil(renderbuffer)`.
2) If you need to sample depth:
   - Prefer `depth-only texture + stencil renderbuffer` (Approach C) when available.
   - Else try `packed depth-stencil texture` (Approach B) when supported.
   - Else fail fast (or drop stencil if the caller explicitly allows it).

CrystalGraphics already tracks:

- `CgCapabilities.hasPackedDepthStencil()` (EXT or NV packed depth-stencil)
- `CgCapabilities.hasDepthTexture()` (ARB_depth_texture)

If implementing Approach B or C, base checks on these.

## Integration Notes for Angelica/Iris

- Iris chooses the depth format for its render targets by inspecting the main framebuffer depth texture internal format.
  - `DeferredWorldRenderingPipeline` reads the internal format via `TextureInfoCache` and maps it to `DepthBufferFormat`.
- Therefore:
  - If the main depth texture internal format is `GL_DEPTH24_STENCIL8`, Iris will naturally propagate that combined format to its owned `DepthTexture` instances *and* attach the depth texture via `GL_DEPTH_STENCIL_ATTACHMENT`.

If CrystalGraphics introduces packed depth-stencil textures, it should match Angelica's exact attachment behavior:

- Attach the same texture to `GL_DEPTH_ATTACHMENT` and `GL_STENCIL_ATTACHMENT` when not using `GL_DEPTH_STENCIL_ATTACHMENT` (or when using EXT backends).

## Concrete Code Hotspots (CrystalGraphics)

If implementing Approach B or C, likely touch:

- `src/main/java/io/github/somehussar/crystalgraphics/api/framebuffer/CgDepthStencilSpec.java`
  - Add a new mode (packed depth-stencil texture) or a combined "depth texture + stencil renderbuffer" mode.

- `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/CoreFramebuffer.java`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/ArbFramebuffer.java`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/framebuffer/ExtFramebuffer.java`
  - Allocate/attach the new attachment types.

- `src/main/java/io/github/somehussar/crystalgraphics/api/CgCapabilities.java`
  - Possibly add a derived boolean like `hasDepthStencilTexture()` to centralize checks.

## Risks / Gotchas

- Some drivers accept `GL_DEPTH24_STENCIL8` renderbuffers but reject `GL_DEPTH24_STENCIL8` textures.
- EXT backend requires attaching packed depth-stencil as two attachments (depth + stencil) because there is no combined attachment point.
- If you introduce a packed depth-stencil texture mode, keep the existing renderbuffer mode as the default for non-sampling use cases.

## Reference Links (Specs / LWJGL2)

- OpenGL extension: `EXT_packed_depth_stencil` (defines packed depth-stencil formats and usage)
  - https://registry.khronos.org/OpenGL/extensions/EXT/EXT_packed_depth_stencil.txt
- LWJGL 2.9.3 javadoc (useful for verifying which class contains which constants/entrypoints)
  - Core: https://javadoc.io/doc/org.lwjgl.lwjgl/lwjgl/2.9.3/org/lwjgl/opengl/GL30.html
  - ARB: https://javadoc.io/doc/org.lwjgl.lwjgl/lwjgl/2.9.3/org/lwjgl/opengl/ARBFramebufferObject.html
  - EXT: https://javadoc.io/doc/org.lwjgl.lwjgl/lwjgl/2.9.3/org/lwjgl/opengl/EXTFramebufferObject.html
