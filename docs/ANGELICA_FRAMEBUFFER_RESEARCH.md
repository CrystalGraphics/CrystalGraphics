# Angelica Framebuffer Creation Map (temp_angelica)

This document maps where Angelica creates/owns/attaches framebuffers (FBOs) in the local research checkout at:

`research_repos/temp_angelica`

Scope includes:

- Iris shader pipeline FBOs and render targets (gbuffers, composite passes, shadows)
- Vanilla `net.minecraft.client.shader.Framebuffer` changes (Mixin)
- Misc FBO usage shipped in the Angelica tree (MCPatcher code, utilities)
- HUDCaching usage that instantiates a stencil-capable framebuffer (via GTNHLib)

This is intended as a practical input for: adding/keeping stencil support when integrating CrystalGraphics with Angelica/Iris.

## Quick Orientation

Angelica bundles a backport of Iris under `net.coderbot.iris.*`. Iris is responsible for most offscreen framebuffer creation.

In this checkout there is no separate `shadersmod.*` source tree; instead, ShadersMod-like integration points are implemented via mixins under `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/` (notably the `Framebuffer` mixin that replaces the vanilla depth renderbuffer with a depth texture).

Key pattern:

1) Iris uses the *main Minecraft framebuffer's depth texture* (not its own depth renderbuffer) so that it automatically matches whether the main framebuffer is `depth-only` or `depth+stencil`.
2) Iris creates its own FBOs (via `GlFramebuffer`) and attaches color textures it owns (`RenderTarget`) plus a depth texture ID (usually the main depth texture, or a `DepthTexture` Iris owns).

## FBO Inventory (Creation / Attachment Sites)

### Iris FBO primitive

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/gl/framebuffer/GlFramebuffer.java`
  - `GlFramebuffer()`
    - Creates an FBO ID via `RenderSystem.createFramebuffer()`.
    - Tracks attachments and MRT limits.
  - `addColorAttachment(int index, int texture)`
    - Attaches textures to `GL_COLOR_ATTACHMENT0 + index` via `RenderSystem.framebufferTexture2D(...)`.
  - `addDepthAttachment(int texture)`
    - Reads texture internalFormat via `TextureInfoCache` and maps it to `DepthBufferFormat`.
    - If `DepthBufferFormat.isCombinedStencil()` attaches as `GL_DEPTH_STENCIL_ATTACHMENT`.
    - Else attaches as `GL_DEPTH_ATTACHMENT`.
  - `bind() / bindAsReadBuffer() / bindAsDrawBuffer()`
    - Bind via `OpenGlHelper.func_153171_g/*glBindFramebuffer*/`.
  - `destroyInternal()`
    - Deletes FBO via `OpenGlHelper.func_153174_h/*glDeleteFramebuffers*/`.

Notes:

- Iris's `GlFramebuffer` is *texture-attachment oriented*: it does not allocate renderbuffers itself.

### Iris render targets (gbuffers / composites)

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/rendertarget/RenderTargets.java`
  - Constructor `RenderTargets(...)`
    - Builds N `RenderTarget` textures for colortex outputs.
    - Creates utility FBOs:
      - `depthSourceFb` (reads from the main depth texture)
      - `noTranslucentsDestFb` + `noHandDestFb` (own depth textures)
  - `createColorFramebuffer(...)`
    - Creates a `GlFramebuffer`, attaches 1..N color textures, sets draw/read buffers.
    - Completeness checked via `GlFramebuffer.isComplete()`.
  - `createColorFramebufferWithDepth(...)`
    - `createColorFramebuffer(...)` + `addDepthAttachment(currentDepthTexture)`.
  - `createGbufferFramebuffer(...)`
    - Same as above; depth attachment is the current main depth texture.
  - `createEmptyFramebuffer()`
    - Special-case: depth attached + a dummy color attachment 0 (comment notes pre-GL3 requirement).
  - `resizeIfNeeded(...)`
    - Detects main depth recreation (via version counter) and re-attaches depth texture ID.
    - Resizes `RenderTarget` textures and Iris-owned `DepthTexture` instances.

Depth/stencil implications:

- If the main depth texture internal format is `GL_DEPTH24_STENCIL8` (or similar combined format), `GlFramebuffer.addDepthAttachment` will attach it as `GL_DEPTH_STENCIL_ATTACHMENT`.
- Iris-owned `DepthTexture` allocation also follows `DepthBufferFormat`, so the format propagated from the main framebuffer controls whether Iris creates depth-only or depth+stencil copies.

### Iris shadow render targets

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/shadows/ShadowRenderTargets.java`
  - Constructor `ShadowRenderTargets(...)`
    - Creates shadow color textures (`RenderTarget`) lazily.
    - Creates Iris-owned `DepthTexture` for shadow depth (currently instantiated with `DepthBufferFormat.DEPTH` in this checkout).
    - Creates FBOs similarly to `RenderTargets`.
  - `createShadowFramebuffer(...)` and `createColorFramebufferWithDepth(...)`
    - Creates FBO + attaches depth texture.

### Iris textures used as FBO attachments

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/rendertarget/RenderTarget.java`
  - Allocates two textures per target (`mainTexture`, `altTexture`) via `glGenTextures`.
  - Uses `RenderSystem.texImage2D(...)` to size textures.
  - No depth/stencil here; these are color attachments.

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/rendertarget/DepthTexture.java`
  - Allocates a texture via `RenderSystem.createTexture(GL_TEXTURE_2D)`.
  - `resize(...)` uses `RenderSystem.texImage2D(..., format.getGlInternalFormat(), ..., format.getGlType(), format.getGlFormat(), null)`.
  - If `DepthBufferFormat` is a combined depth+stencil format, this becomes a depth-stencil texture.

### Vanilla Minecraft Framebuffer hook (depth texture + optional stencil)

- `research_repos/temp_angelica/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/MixinFramebuffer.java`
  - Mixes into `net.minecraft.client.shader.Framebuffer`.
  - Key behavior: replace the vanilla depth renderbuffer with a *depth texture*.
  - `@Redirect` on `Framebuffer.useDepth` inside `Framebuffer.createFramebuffer`
    - Returns `false` to prevent vanilla depth renderbuffer allocation.
  - `iris$createDepthTexture(int width, int height, CallbackInfo ci)`
    - Allocates `iris$depthTextureId`.
    - Chooses depth format based on `MinecraftForgeClient.getStencilBits() != 0`:
      - If stencil requested: `glTexImage2D(..., GL_DEPTH24_STENCIL8, ..., GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, null)`
      - Else: `glTexImage2D(..., GL_DEPTH_COMPONENT, ..., GL_DEPTH_COMPONENT, GL_FLOAT, null)`
    - Attaches it:
      - Always: `GL_DEPTH_ATTACHMENT`
      - If stencil requested: also `GL_STENCIL_ATTACHMENT`

Why this matters:

- This is the primary switch controlling whether the *main* depth attachment is depth-only or depth+stencil.
- Iris then discovers this via `TextureInfoCache` and automatically attaches as `GL_DEPTH_STENCIL_ATTACHMENT` when appropriate.

### HUDCaching framebuffer (stencil requested, external implementation)

- `research_repos/temp_angelica/src/main/java/com/gtnewhorizons/angelica/hudcaching/HUDCaching.java`
  - `HUDCaching.init()`:
    - `framebuffer = new SharedDepthFramebuffer(CustomFramebuffer.STENCIL_BUFFER);`
  - The actual GL allocation is in GTNHLib (`SharedDepthFramebuffer` / `CustomFramebuffer`) not in this checkout.
  - It is still a concrete place where Angelica requests a stencil-capable framebuffer.

### Miscellaneous FBO usage shipped in-tree

#### Iris utility FBOs outside RenderTargets

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/postprocess/FinalPassRenderer.java`
  - Constructor creates a standalone `GlFramebuffer` named `colorHolder`:
    - `this.colorHolder = new GlFramebuffer();`
    - `colorHolder.addColorAttachment(0, main.framebufferTexture);`
  - Used as a simple "holder" FBO for the main color texture during the final pass.
  - Depth attachment is intentionally not managed here.

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/postprocess/CenterDepthSampler.java`
  - Constructor creates a 1x1 FBO:
    - `this.framebuffer = new GlFramebuffer();`
    - Attaches a single color texture.
  - Purpose: render a full-screen quad to sample/smooth center depth into a 1x1 texture.
  - No depth/stencil attachment.

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/texture/util/TextureManipulationUtil.java`
  - Lazily creates a helper FBO (`colorFillFBO`) via `OpenGlHelper.func_153165_e/*glGenFramebuffers*/()`.
  - Binds it and temporarily attaches textures to `GL_COLOR_ATTACHMENT0` per mip level to clear-fill.

- `research_repos/temp_angelica/src/main/java/com/prupe/mcpatcher/hd/FancyDial.java`
  - Contains a private `FBO` helper class that allocates an EXT FBO:
    - `EXTFramebufferObject.glGenFramebuffersEXT()`
    - `glFramebufferTexture2DEXT(GL_COLOR_ATTACHMENT0_EXT, ...)`
  - No depth/stencil attachment in the snippet present in this checkout.

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/rendertarget/ColorTexture.java`
  - Calls `EXTFramebufferObject.glFramebufferTexture2DEXT(...)` to attach a texture.
  - Does not create an FBO itself; expects an appropriate FBO already bound.

## Call Graph / Lifecycle Notes

### Main pipeline setup

- `research_repos/temp_angelica/src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java`
  - Grabs the vanilla main framebuffer: `Minecraft.getMinecraft().getFramebuffer()`.
  - Reads the Iris depth texture ID injected by the mixin:
    - `((IRenderTargetExt) main).iris$getDepthTextureId()`
  - Uses `TextureInfoCache` to infer internal format, then computes `DepthBufferFormat`.
  - Constructs `RenderTargets(...)` with:
    - dimensions
    - main depth texture ID
    - a depth buffer version counter (increments on delete)

### Resize handling

- `RenderTargets.resizeIfNeeded(...)` re-attaches new main depth texture IDs when Minecraft recreates them.
  - This is why the mixin increments `iris$depthBufferVersion` on `deleteFramebuffer()`.

### Binding and API families

- Iris binds framebuffers via `OpenGlHelper.func_153171_g/*glBindFramebuffer*/`.
- Angelica has a bytecode redirector:
  - `research_repos/temp_angelica/src/main/java/com/gtnewhorizons/angelica/loading/shared/transformers/AngelicaRedirector.java`
  - It redirects several `OpenGlHelper` calls (including `func_153171_g`, `func_153165_e`, `func_153188_a`) to Angelica's `GLStateManager`.

Practical implication:

- Even when Iris code uses `OpenGlHelper`, Angelica can route those calls through its own GL state tracking layer.

## External References (Context)

These are not required to understand the local code, but they help place the design decisions in context:

- Angelica repository (upstream reference):
  - https://github.com/GTNewHorizons/Angelica
- GTNH wiki page describing Angelica as a shader loader for 1.7.10:
  - https://gtnh.miraheze.org/wiki/Shaders
- OptiFine shader + stencil incompatibility report (illustrates that enabling stencil can change FBO completeness/compat):
  - https://github.com/sp614x/optifine/issues/2556
- Angelica issue tracker example (general shader stability context):
  - https://github.com/GTNewHorizons/Angelica/issues/81
