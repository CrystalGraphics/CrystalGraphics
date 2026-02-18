# Critical Implementation Gotchas for CrystalGraphics

**Analysis Date**: 2026-02-18  
**Purpose**: Between-the-lines details that will break integration if not handled  
**Scope**: Hidden behaviors in vanilla Minecraft 1.7.10 FBO/shader system

---

## Executive Summary

Vanilla Minecraft's FBO/shader system has **numerous undocumented behaviors** that contradict surface-level analysis. The biggest integration risks are:

1. **FBO handle churn + unconditional "bind 0" patterns** - Nothing restores previous bindings
2. **Shader system global state** - Can desync if anything else touches GL programs/texture units
3. **Forge's deliberate stencil avoidance** - Due to real driver crash issues
4. **Resource leaks by design** - Vanilla intentionally leaks temporary FBOs
5. **Documentation contradictions** - Analysis docs don't match actual code behavior

**Critical**: Do not assume vanilla follows "best practices" GL state management. It doesn't. Your integration must work around vanilla's patterns, not expect vanilla to cooperate.

---

## 1. Documentation Contradictions (Analysis Docs vs. Reality)

### Framebuffer Creation Binding Behavior

**MINECRAFT_FBO_ANALYSIS.md Claims**:
> "`createBindFramebuffer` creates then binds + sets viewport"

**Actual Behavior** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/Framebuffer.java:40-59`):
```java
public void createBindFramebuffer(int width, int height) {
    if (!OpenGlHelper.isFramebufferEnabled()) {
        // Just set dimensions, no GL work
        return;
    }
    
    GL11.glEnable(GL11.GL_DEPTH_TEST);  // Side effect!
    
    if (this.framebufferObject >= 0) {
        this.deleteFramebuffer();  // Deletes old FBO
    }
    
    this.createFramebuffer(width, height);  // Creates new FBO
    this.checkFramebufferComplete();
    OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, 0);  // BINDS 0, NOT SELF!
}
```

**Impact**: The FBO is **not** left bound after creation. Any code assuming it is will bind the wrong target.

### Deletion Implementation

**MINECRAFT_FBO_ANALYSIS.md Shows**:
> Pseudo-code or incorrect method signatures for deletion

**Actual Deletion** (`Framebuffer.java:62-87`):
```java
public void deleteFramebuffer() {
    if (OpenGlHelper.isFramebufferEnabled()) {
        this.unbindFramebufferTexture();
        this.unbindFramebuffer();  // Binds 0
        
        if (this.depthBuffer > -1) {
            OpenGlHelper.func_153184_g(this.depthBuffer);  // Correct wrapper
            this.depthBuffer = -1;
        }
        
        if (this.framebufferTexture > -1) {
            TextureUtil.deleteTexture(this.framebufferTexture);
            this.framebufferTexture = -1;
        }
        
        if (this.framebufferObject > -1) {
            OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, 0);  // Bind 0 AGAIN
            OpenGlHelper.func_153174_h(this.framebufferObject);  // Delete wrapper
            this.framebufferObject = -1;
        }
    }
}
```

**Impact**: Deletion **always binds framebuffer 0** before and during deletion. Does not restore previous binding.

### Texture Filter Settings

**MINECRAFT_FBO_ANALYSIS.md Shows**:
> LINEAR + CLAMP_TO_EDGE

**Actual Code** (`Framebuffer.java:111, 138-149`):
```java
this.setFramebufferFilter(9728);  // GL_NEAREST, not LINEAR!

public void setFramebufferFilter(int filter) {
    // ...
    GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 10496.0F);  // GL_CLAMP
    GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 10496.0F);  // GL_CLAMP
}
```

**Impact**: Default is `NEAREST` filtering and `CLAMP` wrapping, not what docs suggest.

### Shader System Behavior

**MINECRAFT_SHADER_ANALYSIS.md Claims**:
> "endShader doesn't unbind program"

**Actual Code** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderManager.java:184-200`):
```java
public void func_147993_b() {  // endShader
    OpenGlHelper.func_153161_d(0);  // DOES unbind: glUseProgram(0)
    
    for (int i = 0; i < this.listSamplerTextures.size(); i++) {
        // Unbind textures on all units
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + i);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
    
    // BUT: Does NOT reset active texture unit back to defaultTexUnit!
}
```

**Impact**: Shader unbind **does** call `glUseProgram(0)`, but leaves active texture unit in unknown state.

### Shader Effect List

**MINECRAFT_SHADER_ANALYSIS.md Lists**:
> 22 effects with specific names

**Actual Array** (`build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java:127-152`):
```java
private static final String[] shaderResourceLocations = new String[] {
    "shaders/post/notch.json",
    "shaders/post/fxaa.json",
    // ... exact count/order differs from docs
};
```

**Impact**: Any code depending on effect ordering or count from docs will break.

---

## 2. FBO Lifecycle Edge Cases

### Resize is Full Teardown + Recreate

**Trigger**: Window resize or fullscreen toggle

**Call Path** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:1645-1653`):
```java
private void updateFramebufferSize() {
    this.framebufferMc.createBindFramebuffer(this.displayWidth, this.displayHeight);
    if (this.entityRenderer != null) {
        this.entityRenderer.updateShaderGroupSize(this.displayWidth, this.displayHeight);
    }
}
```

**Gotcha**: `createBindFramebuffer` internally **deletes the old FBO** if it exists (`Framebuffer.java:51-58`):
```java
if (this.framebufferObject >= 0) {
    this.deleteFramebuffer();  // Complete teardown
}
this.createFramebuffer(width, height);  // Fresh GL objects
```

**Impact**:
- Old FBO handle becomes invalid immediately
- Any external tracking of the FBO handle (texture ID, depth buffer ID) is now stale
- CrystalGraphics **must** detect this and update its wrapper references

### Fullscreen Toggle Timing Race

**Call Path** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:1567-1617`):
```java
public void toggleFullscreen() {
    // 1. Update dimensions FIRST
    this.displayWidth = Display.getDesktopDisplayMode().getWidth();
    this.displayHeight = Display.getDesktopDisplayMode().getHeight();
    
    // 2. Recreate FBO with NEW dimensions
    if (this.fullscreen) {
        this.updateFramebufferSize();
    } else {
        this.resize(this.displayWidth, this.displayHeight);
    }
    
    // 3. THEN actually toggle fullscreen
    Display.setFullscreen(!Display.isFullscreen());
}
```

**Gotcha**: FBO is recreated to "desktop size" **before** `Display.setFullscreen()`. If the platform applies the fullscreen change lazily, there's a window where:
- FBO size = desktop resolution
- Actual drawable size = windowed resolution
- Viewport is wrong

**Impact**: Transient frame with mismatched FBO/screen dimensions.

### World Unload/Dimension Change Does NOT Touch FBOs

**Call Path** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:2249-2344`):
```java
public void loadWorld(WorldClient world, String loadingMessage) {
    // Tears down world entities, unloads chunks
    // Does NOT touch framebufferMc or shader FBOs
    
    if (this.theWorld != null) {
        this.theWorld.sendQuittingDisconnectingPacket();
        // ...
    }
    
    this.theWorld = world;
    // ...
}
```

**Gotcha**: GL resources (FBOs, shaders, textures) **persist** across world changes.

**Impact**: Any integration assuming "world switch = GL reset" is wrong. FBO handles remain valid.

### Vanilla Intentionally Leaks FBOs

**Example** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:752-800`):
```java
private void loadScreen() throws LWJGLException {
    // ...
    Framebuffer framebuffer = new Framebuffer(this.displayWidth, this.displayHeight, true);
    framebuffer.bindFramebuffer(false);
    
    // Render loading screen
    // ...
    
    framebuffer.unbindFramebuffer();
    // NEVER calls framebuffer.deleteFramebuffer() - permanent leak!
}
```

**Impact**: Vanilla **does not** clean up all FBOs. CrystalGraphics cannot assume "vanilla always frees resources."

---

## 3. GL State Assumptions (Vanilla's Patterns)

### No "Restore Previous" Discipline

**Pattern**: Almost all FBO operations end with **bind 0**, not "bind previous."

**Examples**:
1. `Framebuffer.createBindFramebuffer(...)` → `glBindFramebuffer(..., 0)` (`Framebuffer.java:58`)
2. `Framebuffer.deleteFramebuffer()` → `glBindFramebuffer(..., 0)` twice (`Framebuffer.java:67, 83`)
3. `Framebuffer.framebufferClear()` → binds self, clears, unbinds to 0 (`Framebuffer.java:265-279`)

**Impact**: If CrystalGraphics expects "bind/restore" semantics, state will desync. Must explicitly rebind desired FBO after vanilla operations.

### Unconditional Depth Test Enable

**Call** (`Framebuffer.java:49`):
```java
public void createBindFramebuffer(...) {
    GL11.glEnable(GL11.GL_DEPTH_TEST);  // Always!
    // ...
}
```

**Gotcha**: If depth test is intentionally disabled, vanilla silently re-enables it during FBO recreation.

**Impact**: Depth test state is not preserved. CrystalGraphics must re-disable if needed.

### Viewport is Disposable Global State

**Pattern**: Viewport is set/overwritten freely, never restored.

**Examples**:
1. `Framebuffer.bindFramebuffer(true)` sets viewport to FBO dimensions (`Framebuffer.java:205`)
2. `Framebuffer.framebufferClear()` binds with `setViewport=true`, clears, unbinds **without restoring viewport** (`Framebuffer.java:267, 278`)
3. Shader passes set viewport repeatedly (`build/rfg/minecraft-src/java/net/minecraft/client/shader/Shader.java:70-73`)

**Impact**: Viewport state is volatile. Assume it's wrong after any vanilla FBO operation.

### Color Mask Alpha Writes Frequently Disabled

**Pattern**: Alpha writes disabled during blits, only partially restored.

**Examples**:
1. `Framebuffer.framebufferRender(...)` uses `glColorMask(true, true, true, false)` and restores to all-true at end (`Framebuffer.java:230, 261`)
2. `Shader.loadShader()` also disables alpha writes (`build/rfg/minecraft-src/java/net/minecraft/client/shader/Shader.java:90-102`)

**Impact**: If CrystalGraphics assumes alpha channel is preserved through pipeline, it will break.

### Texture Matrix is Pushed/Overwritten for Shaders

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java:1098-1103`):
```java
GL11.glMatrixMode(GL11.GL_TEXTURE);
GL11.glPushMatrix();
GL11.glLoadIdentity();
// ... shader group execution ...
GL11.glPopMatrix();
```

**Implication**: Vanilla expects other code (water/portal effects) may have modified texture matrix. Post-processing resets it.

**Impact**: Texture matrix is not stable. CrystalGraphics must save/restore if it uses it.

---

## 4. Shader System Integration Gotchas

### Where Shader Post-Processing Happens

**Frame Structure** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:1050-1101`):
```java
// 1. Bind main FBO
this.framebufferMc.bindFramebuffer(true);

// 2. Render world (inside EntityRenderer.updateCameraAndRender)
this.entityRenderer.updateCameraAndRender(this.timer.renderPartialTicks);
    // 2a. World render to main FBO
    // 2b. Shader post-processing (if active) - INSIDE updateCameraAndRender
    // 2c. EntityRenderer rebinds main FBO for GUI
    // 2d. GUI overlay rendered

// 3. Unbind main FBO
this.framebufferMc.unbindFramebuffer();

// 4. Blit main FBO texture to screen
this.framebufferMc.framebufferRender(this.displayWidth, this.displayHeight);
```

**Critical**: Shader post happens **inside** `updateCameraAndRender`, **before** GUI overlay.

**Impact**: Vanilla expects "world + (maybe shader post) + GUI" to end up in `framebufferMc` before final blit.

### Shader Passes Smash State Without Restoring

**Example** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/Shader.java:48-59`):
```java
private void preLoadShader() {
    GL11.glDisable(GL11.GL_BLEND);
    GL11.glDisable(GL11.GL_DEPTH_TEST);
    GL11.glDisable(GL11.GL_ALPHA_TEST);
    GL11.glDisable(GL11.GL_FOG);
    GL11.glDisable(GL11.GL_LIGHTING);
    GL11.glDisable(GL11.GL_COLOR_MATERIAL);
    GL11.glEnable(GL11.GL_TEXTURE_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    // NEVER RESTORES ANY OF THIS
}
```

**Impact**: Shader execution leaves GL state in unknown configuration. Next render pass must set state from scratch.

### ShaderManager Doesn't Restore Texture Unit

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderManager.java:184-200`):
```java
public void func_147993_b() {  // endShader
    OpenGlHelper.func_153161_d(0);  // Unbind program
    
    for (int i = 0; i < this.listSamplerTextures.size(); i++) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + i);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
    // DOES NOT CALL setActiveTexture(defaultTexUnit) AT END!
}
```

**Gotcha**: After shader execution, active texture unit is `defaultTexUnit + (sampler count - 1)`, not `defaultTexUnit`.

**Impact**: Next texture bind happens on wrong unit unless caller explicitly resets.

### Intermediate Shader Targets Always Request Depth

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderGroup.java:311-320`):
```java
public void addFramebuffer(String name, int width, int height) {
    Framebuffer framebuffer = new Framebuffer(width, height, true);  // depth=true!
    // ...
}
```

**Impact**: Even "pure post-processing" shader targets allocate depth (and maybe depth+stencil) renderbuffers. Hidden VRAM cost.

### Shader Link Failure is Log-Only

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderLinkHelper.java:53-60`):
```java
if (OpenGlHelper.func_153175_a(program, OpenGlHelper.field_153207_o) == 0) {
    // Link failed
    logger.warn("Error encountered when linking program containing VS " + vertex + " and FS " + fragment + ". Log output:");
    logger.warn(OpenGlHelper.func_153166_e(program, 32768));
    // DOES NOT THROW - continues with broken program!
}
```

**Impact**: Broken shader programs continue execution, causing "mysteriously broken rendering" instead of hard fail.

### Shader Uniform "Dirty Flag" is Ignored

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderUniform.java:231-258`):
```java
public void func_148093_b() {
    if (!this.field_148105_h) {
        ; // No-op check
    }
    // Uploads anyway, regardless of dirty flag
    // ...
}
```

**Impact**: Uniforms are uploaded every frame even if unchanged. No optimization.

---

## 5. Threading Model

### Single Main Thread for GL

**Detection** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:286-287, 3012-3015`):
```java
private Thread field_152352_aC;  // Main thread

public boolean func_152345_ab() {  // isCallingFromMinecraftThread
    return Thread.currentThread() == this.field_152352_aC;
}
```

**Reality**: In 1.7.10, render + tick happen on same main thread. All GL calls are on this thread.

**Cross-Thread Work** (`Minecraft.java:1658-1669, 2978-3010`):
```java
private final Queue field_152351_aB = new ConcurrentLinkedQueue();  // Scheduled tasks

public void runTick() {
    synchronized (this.field_152351_aB) {
        while (!this.field_152351_aB.isEmpty()) {
            ((FutureTask)this.field_152351_aB.poll()).run();
        }
    }
    // ...
}
```

**Impact**: Any GL/FBO/shader work **must** be scheduled via this queue if triggered from another thread.

### Resource Reload Can Be Called from Wrong Thread

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java:241-260`):
```java
public void onResourceManagerReload(IResourceManager resourceManager) {
    // Deletes and rebuilds shader groups
    if (this.theShaderGroup != null) {
        this.theShaderGroup.deleteShaderGroup();
    }
    this.theShaderGroup = null;
    // ...
    // NO THREAD CHECK - assumes called on main thread
}
```

**Gotcha**: If a mod triggers resource reload from unusual thread, this becomes a GL context error/crash.

**Impact**: CrystalGraphics must verify thread before any GL operations in reload hooks.

---

## 6. Error Handling Patterns

### FBO Completeness Failure is Hard Exception

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/Framebuffer.java:152-179`):
```java
public void checkFramebufferComplete() {
    int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
    
    if (status != OpenGlHelper.field_153202_i) {  // GL_FRAMEBUFFER_COMPLETE
        if (status == OpenGlHelper.field_153203_j) {
            throw new RuntimeException("GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT");
        }
        // ... other status codes ...
        throw new RuntimeException("glCheckFramebufferStatus returned unknown status:" + status);
    }
}
```

**Call Sites** (`Minecraft.java:1645-1653`):
```java
private void updateFramebufferSize() {
    this.framebufferMc.createBindFramebuffer(...);  // Internally calls checkFramebufferComplete
    // NO TRY/CATCH - escalates to crash report
}
```

**Impact**: FBO creation failure is always fatal (no recovery path).

### Shader Compilation Failure is Hard Exception

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderLoader.java:72-78`):
```java
if (OpenGlHelper.func_153157_c(shader, OpenGlHelper.field_153208_p) == 0) {
    String log = StringUtils.trim(OpenGlHelper.func_153158_d(shader, 32768));
    throw new JsonException("Couldn't compile " + shaderType + " program: " + log);
}
```

**Caught In** (`EntityRenderer.java:216-231`):
```java
private void activateNextShader() {
    try {
        this.theShaderGroup = new ShaderGroup(...);
        // ...
    } catch (IOException e) {  // JsonException extends IOException
        logger.warn("Failed to load shader: " + resourceLocation, e);
        this.shaderIndex = -1;  // Disable effect
    }
}
```

**Impact**: Shader compilation failure is caught and logged, effect disabled gracefully.

### GL Errors are Logged, Not Fatal

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:878-893`):
```java
public void checkGLError(String phase) {
    int error;
    while ((error = GL11.glGetError()) != 0) {
        String errorString = GLU.gluErrorString(error);
        logger.error("########## GL ERROR ##########");
        logger.error("@ " + phase);
        logger.error(error + ": " + errorString);
        // DOES NOT THROW
    }
}
```

**Impact**: Integration bugs show up as spam + later corruption, not immediate crash.

---

## 7. Driver-Specific Workarounds

### Forge Intentionally Avoids Stencil

**Call** (`build/rfg/minecraft-src/java/net/minecraftforge/client/ForgeHooksClient.java:318-341`):
```java
public static void createDisplay() throws LWJGLException {
    PixelFormat format;
    
    if (System.getProperty("forge.forceDisplayStencil", "false").equals("true")) {
        format = new PixelFormat().withDepthBits(24).withStencilBits(8);
    } else {
        format = new PixelFormat().withDepthBits(24);  // NO STENCIL
    }
    
    Display.create(format);
}
```

**Impact on FBO** (`Framebuffer.java:120-130`):
```java
if (this.useDepth) {
    if (MinecraftForgeClient.getStencilBits() == 0) {
        // Depth-only renderbuffer
        OpenGlHelper.func_153186_a(..., 33190, ...);  // GL_DEPTH_COMPONENT
    } else {
        // Packed depth-stencil
        OpenGlHelper.func_153186_a(..., GL_DEPTH24_STENCIL8_EXT, ...);
    }
}
```

**Gotcha**: Stencil support is **policy-based**, not capability-based. Drivers crash with stencil formats, so Forge disables by default.

**Impact**: CrystalGraphics cannot assume "GL supports stencil = use stencil." Must check Forge policy.

### Framebuffer Support Requires Separate Blending

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/renderer/OpenGlHelper.java:90-93`):
```java
openGL14 = contextcapabilities.OpenGL14 || contextcapabilities.GL_EXT_blend_func_separate;
framebufferSupported = openGL14 && (contextcapabilities.GL_ARB_framebuffer_object || 
                                     contextcapabilities.GL_EXT_framebuffer_object || 
                                     contextcapabilities.OpenGL30);
```

**Gotcha**: Vanilla requires **both** FBO extension **and** separate blend functions. If a GPU supports FBOs but not separate blending, entire FBO/shader path is disabled.

**Impact**: Some old hardware may support FBOs technically but vanilla won't use them.

---

## 8. State Machine Ordering Constraints

### Required Init Ordering

**Correct Order** (`Minecraft.java:472-499`):
```java
public void startGame() throws LWJGLException {
    // 1. Create display FIRST
    this.createDisplay();
    
    // 2. THEN initialize OpenGL helpers (queries GLContext)
    OpenGlHelper.initializeTextures();
    
    // 3. Later: Initialize shader link helper
    // (happens lazily in EntityRenderer.updateRenderer)
}
```

**Dependency** (`ShaderManager.java:149-151`):
```java
public ShaderManager(...) {
    // ...
    this.programFilename = filename;
    this.program = ShaderLinkHelper.getStaticShaderLinkHelper().createProgram();
    // REQUIRES ShaderLinkHelper.staticShaderLinkHelper to be set!
}
```

**Impact**: Creating `ShaderManager` before `EntityRenderer.updateRenderer()` is a null deref risk.

### Resize Must Happen Before Shader Group Resize

**Call** (`Minecraft.java:1645-1653`):
```java
private void updateFramebufferSize() {
    this.framebufferMc.createBindFramebuffer(this.displayWidth, this.displayHeight);  // 1. Main FBO first
    
    if (this.entityRenderer != null) {
        this.entityRenderer.updateShaderGroupSize(this.displayWidth, this.displayHeight);  // 2. Then shaders
    }
}
```

**Reason** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderGroup.java:351-362`):
```java
public void resetProjectionMatrix() {
    // Uses mainFramebuffer dimensions to set projection
    this.projectionMatrix.setIdentity();
    this.projectionMatrix.func_178968_a(0.0F, 
        (float)this.mainFramebuffer.framebufferTextureWidth,  // READS FROM MAIN FBO
        (float)this.mainFramebuffer.framebufferTextureHeight, 
        0.0F, 1000.0F, 3000.0F);
}
```

**Impact**: If shaders resize before main FBO, projection matrix uses stale dimensions.

---

## 9. Validation Gaps

### No Restore Previous FBO Binding Verification

**Pattern**: Vanilla assumes explicit rebinding is always correct.

**Risk**: If CrystalGraphics relies on "bind/restore" semantics anywhere, vanilla will break it.

**Solution**: CrystalGraphics must explicitly rebind desired FBO after any vanilla call. Cannot assume preservation.

### No Packed Depth-Stencil Extension Check

**Code** (`Framebuffer.java:127`):
```java
OpenGlHelper.func_153186_a(OpenGlHelper.field_153199_f, 
    EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT, ...);
```

**Issue**: Uses `GL_DEPTH24_STENCIL8_EXT` directly without verifying `GL_EXT_packed_depth_stencil` extension exists.

**Why It Works**: Forge's `getStencilBits()` policy already guards this path.

**Risk**: If CrystalGraphics bypasses Forge policy, this can crash on hardware without packed depth-stencil.

### ShaderManager Redundant Uniform Location Queries

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderManager.java:227-253`):
```java
public void func_147995_c() {  // use
    // ...
    for (int i = 0; i < this.listSamplerTextures.size(); i++) {
        // Queries uniform location EVERY FRAME
        int location = OpenGlHelper.func_153194_a(this.program, sampler.getShaderName());
        // ...
    }
}
```

**Issue**: Locations were already computed in constructor (`ShaderManager.java:281-306`). Re-querying every frame is wasteful.

**Risk**: If another program is bound unexpectedly, these lookups silently poison state.

**Impact**: Performance overhead + potential state corruption.

---

## 10. Memory Management & Lifetime

### Main FBO Lives Until Resize or Exit

**Lifecycle**:
- Created in `Minecraft.startGame()` (`Minecraft.java:509`)
- Deleted + recreated on resize (`Minecraft.java:1645-1653`)
- **Never deleted on shutdown** (`Minecraft.shutdown()` just sets `running=false`; `Minecraft.java:1370-1374`)

**Impact**: GL objects are freed by OS on process exit, not by vanilla cleanup.

### Temporary FBO Leaks are Permanent

**Example** (`Minecraft.java:752-800`):
```java
private void loadScreen() {
    Framebuffer framebuffer = new Framebuffer(...);
    // Use for loading screen
    // NEVER calls framebuffer.deleteFramebuffer()
}
```

**Impact**: Loading screen FBO leaks permanently. Multiply by number of times loading screen is shown.

### FBO Deletion is Setting-Dependent

**Call** (`Framebuffer.java:62-88`):
```java
public void deleteFramebuffer() {
    if (OpenGlHelper.isFramebufferEnabled()) {  // Checks user setting!
        // Delete GL objects
    }
    // If setting is disabled, this is a no-op
}
```

**Gotcha**: If user toggles `gameSettings.fboEnable` off at runtime, vanilla's own cleanup becomes no-op.

**Impact**: Orphaned GL objects if FBO setting is disabled mid-session.

### ShaderGroup Deletion Also Setting-Dependent

**Call** (`build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderGroup.java:323-342`):
```java
public void deleteShaderGroup() {
    for (Framebuffer framebuffer : this.mapFramebuffers.values()) {
        framebuffer.deleteFramebuffer();  // Subject to same setting check
    }
    // ...
}
```

**Impact**: Shader FBOs also leak if `fboEnable` is disabled.

---

## Critical Integration Test Scenarios

To verify CrystalGraphics handles these gotchas:

1. **Toggle fullscreen mid-shader**: Shader active → fullscreen toggle → verify FBO handles updated
2. **Disable `fboEnable` while shaderGroup active**: Shader running → disable setting → verify no leaks
3. **Resource reload while in-world**: World loaded + shader active → F3+T → verify no crashes
4. **Rapid resize spam**: Resize window rapidly → verify no FBO handle staleness
5. **World switch with shader active**: Shader on → change dimension → verify shader persists correctly
6. **Screenshot during shader pass**: Shader rendering → F2 → verify no state corruption
7. **GL error spam test**: Intentionally leave GL errors → verify vanilla's checkGLError doesn't crash CrystalGraphics

---

## Recommendations for CrystalGraphics

1. **Never assume vanilla restores state** - Always explicitly rebind desired FBO/shader/viewport after vanilla calls
2. **Track FBO handle churn** - Detect when vanilla deletes/recreates `framebufferMc` and update wrappers
3. **Respect Forge stencil policy** - Check `MinecraftForgeClient.getStencilBits()`, not just GL capabilities
4. **Assume active texture unit is wrong** - Reset to `defaultTexUnit` after shader operations
5. **Don't rely on viewport** - Set viewport explicitly before every render operation
6. **Periodic state sync from hardware** - Use `glGetInteger(GL_FRAMEBUFFER_BINDING)` as safety net
7. **Thread-check all GL operations** - Verify on main thread via `Minecraft.func_152345_ab()`
8. **Expect temporary FBO leaks** - Vanilla leaks by design; don't assume all FBOs are tracked
9. **Log, don't crash on GL errors** - Follow vanilla's pattern of logging + continuing
10. **Test with `fboEnable` toggling** - Verify no issues when user disables FBOs mid-session

---

**End of Critical Gotchas Analysis**
