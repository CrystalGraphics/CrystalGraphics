# Minecraft 1.7.10 Framebuffer (FBO) Analysis

**Analysis Date**: 2026-02-18
**Source Location**: `build/rfg/minecraft-src/java/net/minecraft/`
**Purpose**: Understand vanilla Minecraft 1.7.10 FBO implementation for CrystalGraphics integration

---

## Executive Summary

Minecraft 1.7.10 uses a sophisticated FBO system managed through three core classes:
1. **Minecraft.java** - Owns the primary framebuffer (`framebufferMc`)
2. **Framebuffer.java** - Vanilla's FBO wrapper class
3. **OpenGlHelper.java** - Extension detection and GL abstraction layer

The system uses a **waterfall fallback** (Core → ARB → EXT) identical to what CrystalGraphics aims to implement. Vanilla already handles this detection, but CrystalGraphics can provide a more robust, mod-friendly abstraction.

---

## 1. Primary Framebuffer: Minecraft.framebufferMc

### Field Declaration
**File**: `net/minecraft/client/Minecraft.java`  
**Line**: 278
```java
private Framebuffer framebufferMc;
```

### Initialization
**File**: `net/minecraft/client/Minecraft.java`  
**Line**: 509 (inside `startGame()`)
```java
this.framebufferMc = new Framebuffer(this.displayWidth, this.displayHeight, true);
this.framebufferMc.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
```
**Note**: The `true` parameter requests a depth buffer.

### Render Loop Integration

#### Binding (Start of Frame)
**File**: `net/minecraft/client/Minecraft.java`  
**Lines**: 1051–1052
```java
GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
this.framebufferMc.bindFramebuffer(true);  // true = set viewport
```
**Critical Point**: This is where vanilla takes control of the FBO. CrystalGraphics needs to track this binding.

#### World Rendering
**File**: `net/minecraft/client/Minecraft.java`  
**Line**: 1067
```java
this.entityRenderer.updateCameraAndRender(this.timer.renderPartialTicks);
```
EntityRenderer renders the world inside the bound FBO.

#### Unbinding and Display (End of Frame)
**File**: `net/minecraft/client/Minecraft.java`  
**Lines**: 1097–1101
```java
this.framebufferMc.unbindFramebuffer();
GL11.glPopMatrix();
GL11.glPushMatrix();
this.framebufferMc.framebufferRender(this.displayWidth, this.displayHeight);
GL11.glPopMatrix();
```
The FBO is unbound, then its texture is rendered to the screen using a full-screen quad.

### Resize Handling

**File**: `net/minecraft/client/Minecraft.java`  
**Line**: 1165 (via `func_147120_f()`)  
**Line**: 1643 (inside `resize(int width, int height)`)
```java
this.updateFramebufferSize();
```

**File**: `net/minecraft/client/Minecraft.java`  
**Line**: 1647 (inside `updateFramebufferSize()`)
```java
private void updateFramebufferSize() {
    this.framebufferMc.createBindFramebuffer(this.displayWidth, this.displayHeight);
    if (this.entityRenderer != null) {
        this.entityRenderer.updateShaderGroupSize(this.displayWidth, this.displayHeight);
    }
}
```
**Key Point**: `createBindFramebuffer` deletes the old FBO and creates a new one. This is a complete recreation, not a resize.

---

## 2. Vanilla Framebuffer Implementation

**File**: `net/minecraft/client/shader/Framebuffer.java`

### Key Fields
```java
public int framebufferObject;      // GL FBO handle
public int framebufferTexture;     // Color attachment texture
public int depthBuffer;            // Depth/stencil attachment
public float[] framebufferColor;   // Clear color
public int framebufferWidth;       // Dimensions
public int framebufferHeight;
boolean useDepth;                  // Depth buffer requested?
```

### Key Methods

#### Creation/Binding
**File**: `Framebuffer.java`  
**Line**: 40
```java
public void createBindFramebuffer(int width, int height) {
    // If already exists, delete and recreate
    if (this.framebufferObject >= 0) {
        this.deleteFramebuffer();
    }
    this.createFramebuffer(width, height);
    this.bindFramebuffer(true);
}
```

#### Actual GL Object Creation
**File**: `Framebuffer.java`  
**Line**: 58
```java
private void createFramebuffer(int width, int height) {
    this.framebufferWidth = width;
    this.framebufferHeight = height;
    this.framebufferTexture = TextureUtil.glGenTextures();
    
    // Texture setup
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.framebufferTexture);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 
                      this.framebufferWidth, this.framebufferHeight, 
                      0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
    
    // FBO creation via OpenGlHelper
    this.framebufferObject = OpenGlHelper.func_153165_e();  // glGenFramebuffers
    this.framebufferClear();  // Clear to set color
    
    if (this.useDepth) {
        this.depthBuffer = OpenGlHelper.func_153194_a(this.framebufferWidth, this.framebufferHeight);
    }
    
    this.bindFramebuffer(false);
    OpenGlHelper.func_153188_a(
        OpenGlHelper.field_153198_e,        // GL_FRAMEBUFFER
        OpenGlHelper.field_153200_g,        // GL_COLOR_ATTACHMENT0
        GL11.GL_TEXTURE_2D, 
        this.framebufferTexture, 
        0
    );
    
    if (this.useDepth) {
        // Attach depth buffer
    }
    
    this.checkFramebufferComplete();
    this.unbindFramebuffer();
}
```

#### Binding
**File**: `Framebuffer.java`  
**Line**: 201
```java
public void bindFramebuffer(boolean setViewport) {
    if (this.useDepth) {
        // Depth buffer binding logic
    }
    
    OpenGlHelper.func_153171_g(
        OpenGlHelper.field_153198_e,  // GL_FRAMEBUFFER
        this.framebufferObject
    );
    
    if (setViewport) {
        GL11.glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
    }
}
```

#### Unbinding
**File**: `Framebuffer.java`  
**Line**: 220
```java
public void unbindFramebuffer() {
    OpenGlHelper.func_153171_g(
        OpenGlHelper.field_153198_e,  // GL_FRAMEBUFFER
        0                             // Unbind (screen)
    );
}
```

#### Rendering to Screen
**File**: `Framebuffer.java`  
**Line**: 230
```java
public void framebufferRender(int width, int height) {
    // Render the FBO texture to screen using full-screen quad
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.framebufferTexture);
    GL11.glBegin(GL11.GL_QUADS);
    // Quad vertices with texture coordinates
    GL11.glEnd();
}
```

#### Deletion
**File**: `Framebuffer.java`  
**Line**: 270
```java
public void deleteFramebuffer() {
    if (this.framebufferObject > -1) {
        OpenGlHelper.func_153174_h(OpenGlHelper.field_153198_e, this.framebufferObject);
        this.framebufferObject = -1;
    }
    if (this.framebufferTexture > -1) {
        TextureUtil.deleteTexture(this.framebufferTexture);
        this.framebufferTexture = -1;
    }
    if (this.depthBuffer > -1) {
        OpenGlHelper.func_153171_g(OpenGlHelper.field_153205_b, this.depthBuffer);
        this.depthBuffer = -1;
    }
}
```

---

## 3. Extension Detection: OpenGlHelper

**File**: `net/minecraft/client/renderer/OpenGlHelper.java`

This is **THE** reference implementation for CrystalGraphics. Vanilla uses the exact same waterfall pattern.

### Detection Logic

**File**: `OpenGlHelper.java`  
**Method**: `initializeTextures()`

```java
ContextCapabilities contextcapabilities = GLContext.getCapabilities();

// FBO Support Detection
field_153209_al = contextcapabilities.OpenGL30 ||  // Core
                  contextcapabilities.GL_ARB_framebuffer_object ||  // ARB
                  contextcapabilities.GL_EXT_framebuffer_object;       // EXT

if (field_153209_al) {  // FBOs supported
    field_153212_w = 0;  // Default to Core
    
    if (contextcapabilities.OpenGL30) {
        field_153212_w = 0;  // Core GL30
    } else if (contextcapabilities.GL_ARB_framebuffer_object) {
        field_153212_w = 1;  // ARB Extension
    } else if (contextcapabilities.GL_EXT_framebuffer_object) {
        field_153212_w = 2;  // EXT Extension
    }
}

// Shader Support Detection
field_153213_x = contextcapabilities.OpenGL21 ||
                 (contextcapabilities.GL_ARB_vertex_shader && 
                  contextcapabilities.GL_ARB_fragment_shader && 
                  contextcapabilities.GL_ARB_shader_objects);

if (field_153213_x) {
    field_153214_y = !contextcapabilities.OpenGL21;  // Use ARB if not Core
}
```

### Path Mapping

| Field | Value | Meaning |
|-------|-------|---------|
| `field_153212_w` | 0 | Core GL30 FBOs |
| `field_153212_w` | 1 | ARB FBO Extension |
| `field_153212_w` | 2 | EXT FBO Extension |
| `field_153214_y` | false | Core GL20 Shaders |
| `field_153214_y` | true | ARB Shader Extension |

### GL Wrapper Methods

#### Framebuffer Generation
```java
public static int func_153165_e() {  // glGenFramebuffers
    switch (field_153212_w) {
        case 0: return GL30.glGenFramebuffers();
        case 1: return ARBFramebufferObject.glGenFramebuffers();
        case 2: return EXTFramebufferObject.glGenFramebuffersEXT();  // EXT suffix!
        default: return 0;
    }
}
```

#### Framebuffer Binding
```java
public static void func_153171_g(int target, int framebuffer) {  // glBindFramebuffer
    switch (field_153212_w) {
        case 0: GL30.glBindFramebuffer(target, framebuffer); break;
        case 1: ARBFramebufferObject.glBindFramebuffer(target, framebuffer); break;
        case 2: EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer); break;
    }
}
```

#### Framebuffer Status Check
```java
public static int func_153167_i(int target) {  // glCheckFramebufferStatus
    switch (field_153212_w) {
        case 0: return GL30.glCheckFramebufferStatus(target);
        case 1: return ARBFramebufferObject.glCheckFramebufferStatus(target);
        case 2: return EXTFramebufferObject.glCheckFramebufferStatusEXT(target);
        default: return 0;
    }
}
```

#### Texture Attachment
```java
public static void func_153188_a(int target, int attachment, 
                                  int textarget, int texture, int level) {
    switch (field_153212_w) {
        case 0: GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level); break;
        case 1: ARBFramebufferObject.glFramebufferTexture2D(target, attachment, textarget, texture, level); break;
        case 2: EXTFramebufferObject.glFramebufferTexture2DEXT(target, attachment, textarget, texture, level); break;
    }
}
```

### Constant Mapping

Vanilla hardcodes GL constants as integers to avoid class loading issues:

```java
// Initialize these in static block based on field_153212_w
field_153198_e = 36160;  // GL_FRAMEBUFFER
field_153200_g = 36064;  // GL_COLOR_ATTACHMENT0
field_153205_b = 36128;  // GL_RENDERBUFFER
// etc.
```

---

## 4. Key Integration Points for CrystalGraphics

### Where to Hook

1. **Minecraft.runGameLoop() Line 1052**: When vanilla binds `framebufferMc`
   - Track via Mixin to `Framebuffer.bindFramebuffer()`
   - Update CrystalGraphics' `currentBuffer` state

2. **Minecraft.updateFramebufferSize() Line 1647**: When window resizes
   - Trigger CrystalGraphics FBO resize
   - Sync with `entityRenderer.updateShaderGroupSize()`

3. **OpenGlHelper.initializeTextures()**: After vanilla detection
   - Read `field_153212_w` to determine which path vanilla chose
   - Use this to set CrystalGraphics' fallback priority
   - Or override if CrystalGraphics provides better detection

### State Synchronization Strategy

#### Option A: Wrap Vanilla (Recommended)
Use Mixins to intercept `OpenGlHelper.func_153171_g()` (glBindFramebuffer):
```java
@Mixin(OpenGlHelper.class)
public class MixinOpenGlHelper {
    @Inject(method = "func_153171_g", at = @At("HEAD"))
    private static void onBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        // Update CrystalGraphics state tracking
        AbstractFramebuffer.currentBuffer = // lookup wrapper for this FBO ID
    }
}
```

#### Option B: Parallel Tracking
Maintain CrystalGraphics' own state independently, but sync periodically:
- On `RenderTickEvent`: Check `glGetInteger(GL_FRAMEBUFFER_BINDING)`
- Update `currentBuffer` if mismatch detected

#### Option C: Override Completely
Replace vanilla's FBO management entirely:
- High risk, high control
- Requires intercepting all vanilla FBO operations
- Not recommended unless absolutely necessary

### Multi-Mod Considerations

**The Problem**: Multiple mods may bind FBOs directly via GL calls, bypassing both vanilla and CrystalGraphics.

**Solutions**:
1. **Mixin to GL calls**: Intercept raw `GL30.glBindFramebuffer` etc. (aggressive but comprehensive)
2. **Periodic sync**: Check actual GL state every frame or every N frames
3. **Event-based**: Subscribe to Forge's `RenderWorldLastEvent` etc. to inject FBO switches

### Resource Ownership

Vanilla's `Framebuffer` class:
- Owns its `framebufferObject`, `framebufferTexture`, `depthBuffer`
- Calls `deleteFramebuffer()` on resize or cleanup
- **Never** deletes external FBOs

CrystalGraphics should mirror this:
```java
if (doWeOwnThisBuffer) {
    // Can delete
    FramebufferHandler.createdFramebuffers.add(this);
} else {
    // Track but don't delete
    // This is the case for vanilla's framebufferMc wrapped by CrystalGraphics
}
```

---

## 5. Critical Implementation Notes

### EXT Suffix is Mandatory
Always use `*EXT` methods when `field_153212_w == 2`:
```java
// WRONG - will crash
EXTFramebufferObject.glBindFramebuffer(target, fbo);

// CORRECT
EXTFramebufferObject.glBindFramebufferEXT(target, fbo);
```

### Intel Graphics Bug
Minecraft 1.7.10 includes logic to handle broken Intel drivers:
- Requires `OpenGL14` or `EXT_blend_func_separate` before enabling FBOs
- This ensures `glBlendFuncSeparate` is available (common Intel failure point)
- CrystalGraphics should respect this or implement similar detection

### Shader-Framebuffer Dependency
In vanilla 1.7.10:
```java
shadersSupported = framebufferSupported && field_153213_x;
```
Shaders require FBOs. CrystalGraphics should maintain this dependency.

### Constants are Hardcoded
Vanilla uses integer constants (e.g., `36160` for `GL_FRAMEBUFFER`) rather than referencing `GL30.GL_FRAMEBUFFER`. This is to avoid class loading issues when GL30 isn't available.

CrystalGraphics can use the proper constants since it uses the waterfall pattern.

---

## 6. Summary for CrystalGraphics Design

### What Vanilla Does Well
1. **Waterfall detection**: Core → ARB → EXT with `field_153212_w`
2. **Wrapper methods**: All GL calls go through `OpenGlHelper.func_*`
3. **Resource management**: `Framebuffer` owns and cleans up its GL objects
4. **Resize handling**: Complete recreation in `updateFramebufferSize()`

### What CrystalGraphics Should Improve
1. **State tracking**: Vanilla has no global `currentBuffer` tracking
2. **Multi-mod support**: Vanilla assumes exclusive control
3. **Capability API**: Vanilla's detection is internal, not exposed
4. **Shader integration**: Vanilla's shader system is limited

### Recommended Approach

1. **Implement same waterfall** as vanilla (reuse their logic from `OpenGlHelper`)
2. **Add state tracking** via Mixins to `OpenGlHelper.func_153171_g()`
3. **Wrap vanilla FBOs** using `registerWrappingMethod` to track `framebufferMc`
4. **Mirror vanilla's resource ownership** model exactly
5. **Subscribe to Forge events** for resize and lifecycle management

### Files to Reference
- `build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java` - FBO lifecycle
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/Framebuffer.java` - FBO implementation
- `build/rfg/minecraft-src/java/net/minecraft/client/renderer/OpenGlHelper.java` - Extension detection
- `build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java` - Render pipeline
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderGroup.java` - Post-processing
- `build/rfg/minecraft-src/java/net/minecraftforge/client/event/` - Forge events
