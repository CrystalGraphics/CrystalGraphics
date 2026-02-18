# CrystalGraphics Integration Strategy

**Purpose**: Practical guide for integrating CrystalGraphics with Minecraft 1.7.10  
**Based On**: MINECRAFT_FBO_ANALYSIS.md and MINECRAFT_SHADER_ANALYSIS.md  
**Target**: Mod developers using CrystalGraphics

---

## Executive Summary

CrystalGraphics must coexist with vanilla Minecraft's FBO/shader system and other mods. This document outlines three integration strategies:

1. **Cooperative** (Recommended) - Work alongside vanilla, track state via Mixins
2. **Wrapper** - Wrap vanilla FBOs for tracking while using CrystalGraphics API
3. **Override** - Replace vanilla's FBO management entirely (high risk)

The recommended approach is **Cooperative + Wrapper**, using Forge events for lifecycle and Mixins for state tracking.

---

## 1. Understanding the Challenge

### The Multi-Mod Problem

In a typical Minecraft 1.7.10 modpack:
- **Vanilla** owns `framebufferMc` and binds it in `runGameLoop()`
- **Optifine** may replace the entire render pipeline
- **Shader mods** bind their own FBOs for post-processing
- **Your mod** wants to add effects without breaking others

**The State Desync Risk**:
```
Vanilla binds FBO 1
  ↓
CrystalGraphics thinks FBO 0 is bound (stale state)
  ↓
Your mod binds FBO 2 via CrystalGraphics
  ↓
Vanilla unbinds FBO 1 (wrong! should unbind FBO 2)
  ↓
GL state corrupted, crashes or visual glitches
```

### The Solution

CrystalGraphics must:
1. **Track** all FBO bindings (vanilla + mods)
2. **Intercept** GL calls to update state
3. **Provide** a wrapper API that's compatible with vanilla
4. **Sync** state periodically as a safety net

---

## 2. Recommended Integration Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                    Minecraft 1.7.10                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    │
│  │   Vanilla   │    │   Optifine  │    │  Other Mod  │    │
│  │  Framebuffer│    │  (optional) │    │   FBOs      │    │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘    │
│         │                  │                   │            │
│         └──────────────────┼───────────────────┘            │
│                            │                                │
│  ┌─────────────────────────▼─────────────────────────────┐   │
│  │              CrystalGraphics Mixin Layer               │   │
│  │  ┌─────────────────────────────────────────────────┐   │   │
│  │  │  Intercepts glBindFramebuffer calls             │   │   │
│  │  │  Updates AbstractFramebuffer.currentBuffer      │   │   │
│  │  │  Tracks ownership (vanilla vs CrystalGraphics)  │   │   │
│  │  └─────────────────────────────────────────────────┘   │   │
│  └─────────────────────────┬─────────────────────────────┘   │
│                            │                                │
│  ┌─────────────────────────▼─────────────────────────────┐   │
│  │              CrystalGraphics API Layer                   │   │
│  │  ┌─────────────────┐  ┌─────────────────┐              │   │
│  │  │ AbstractFramebuffer│  │ AbstractShader  │              │   │
│  │  │  - create()     │  │  - compile()    │              │   │
│  │  │  - bind()       │  │  - bind()       │              │   │
│  │  │  - delete()     │  │  - setUniform() │              │   │
│  │  └─────────────────┘  └─────────────────┘              │   │
│  └─────────────────────────┬─────────────────────────────┘   │
│                            │                                │
│  ┌─────────────────────────▼─────────────────────────────┐   │
│  │              Forge Event Integration                   │   │
│  │  - RenderTickEvent (resize checks)                     │   │
│  │  - RenderWorldLastEvent (post-processing)            │   │
│  │  - FMLPreInitializationEvent (setup)                 │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

#### 1. Mixin Layer (State Tracking)
**Purpose**: Intercept all FBO bindings to maintain accurate state

**Target Classes**:
- `OpenGlHelper.func_153171_g()` (vanilla's glBindFramebuffer wrapper)
- `GL30.glBindFramebuffer()` (direct Core GL calls)
- `ARBFramebufferObject.glBindFramebuffer()` (ARB calls)
- `EXTFramebufferObject.glBindFramebufferEXT()` (EXT calls)

**Implementation**:
```java
@Mixin(OpenGlHelper.class)
public class MixinOpenGlHelper {
    @Inject(method = "func_153171_g", at = @At("HEAD"))
    private static void onBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        // Update CrystalGraphics state tracking
        FramebufferHandler.updateCurrentBuffer(framebuffer);
    }
}
```

**Challenge**: Multiple entry points (vanilla uses `OpenGlHelper`, mods may use raw GL)
**Solution**: Mixin to all known entry points, or use `glGetInteger(GL_FRAMEBUFFER_BINDING)` as fallback

#### 2. API Layer (CrystalGraphics Core)
**Purpose**: Provide abstraction over FBOs/shaders with proper lifecycle management

**Key Classes**:
- `FramebufferFactory` - Create FBOs with waterfall fallback
- `AbstractFramebuffer` - Base class for all FBOs
- `ShaderFactory` - Create shader programs
- `AbstractShaderProgram` - Base class for shaders

**Integration Point**:
```java
public class CrystalGraphicsMod {
    @SubscribeEvent
    public void onPreInit(FMLPreInitializationEvent event) {
        // Initialize CrystalGraphics with Minecraft's GL context
        RenderSystem.initialize();
        
        // Register vanilla FBO wrapper for tracking
        Framebuffer vanillaFbo = Minecraft.getMinecraft().getFramebuffer();
        AbstractFramebuffer wrapped = new WrappedFramebuffer(vanillaFbo);
        FramebufferHandler.registerWrappingMethod(() -> wrapped);
    }
}
```

#### 3. Forge Event Layer (Lifecycle)
**Purpose**: React to Minecraft's render lifecycle

**Critical Events**:

| Event | Bus | Purpose | Action |
|-------|-----|---------|--------|
| `FMLPreInitializationEvent` | FML | Setup | Initialize RenderSystem, register Mixins |
| `TickEvent.RenderTickEvent` (START) | FML | Per-frame | Check window resize, sync state |
| `RenderWorldLastEvent` | Forge | Post-world render | Apply custom shaders/FBOs |
| `RenderGameOverlayEvent` | Forge | GUI render | Apply UI shaders |
| `TextureStitchEvent` | Forge | Texture reload | Rebuild FBOs if needed |

---

## 3. Integration Strategy: Cooperative (Recommended)

### Overview
Coexist with vanilla and other mods. Track state via Mixins, but don't replace vanilla's FBO management.

### Implementation Steps

#### Step 1: Initialize CrystalGraphics

```java
@Mod(modid = "crystalgraphics", name = "CrystalGraphics", version = "1.0.0")
public class CrystalGraphicsMod {
    
    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Step 1: Initialize GL context detection
        RenderSystem.initialize();
        
        // Step 2: Detect vanilla's chosen path (Core/ARB/EXT)
        int vanillaFboPath = getVanillaFboPath();
        CrystalLogger.info("Vanilla FBO path: " + vanillaFboPath);
        
        // Step 3: Register Mixin hooks (via ASM or Mixin framework)
        // This is done via the Mixin config, not code
    }
    
    private int getVanillaFboPath() {
        // Access OpenGlHelper's private field via reflection
        // field_153212_w: 0=Core, 1=ARB, 2=EXT
        try {
            Field field = OpenGlHelper.class.getDeclaredField("field_153212_w");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception e) {
            // Fallback: detect ourselves
            return detectFboPath();
        }
    }
}
```

#### Step 2: Track Vanilla FBO

```java
public class VanillaFboTracker {
    
    private static AbstractFramebuffer vanillaFramebuffer;
    
    public static void init() {
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer vanillaFbo = mc.getFramebuffer();
        
        // Wrap vanilla's FBO for tracking
        vanillaFramebuffer = new WrappedFramebuffer(vanillaFbo, false);
        // false = don't delete (vanilla owns it)
        
        // Register as wrapping method
        FramebufferHandler.registerWrappingMethod(() -> vanillaFramebuffer);
    }
    
    private static class WrappedFramebuffer extends AbstractFramebuffer {
        private final Framebuffer vanillaFbo;
        
        public WrappedFramebuffer(Framebuffer vanillaFbo, boolean owned) {
            super(vanillaFbo.framebufferObject, 
                  vanillaFbo.framebufferWidth, 
                  vanillaFbo.framebufferHeight,
                  new FramebufferCapabilities(),
                  owned);
            this.vanillaFbo = vanillaFbo;
        }
        
        @Override
        public void bind() {
            vanillaFbo.bindFramebuffer(true);
        }
        
        @Override
        public void unbind() {
            vanillaFbo.unbindFramebuffer();
        }
        
        // ... other methods delegate to vanillaFbo
    }
}
```

#### Step 3: Subscribe to Render Events

```java
public class CrystalGraphicsEventHandler {
    
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Sync state at start of frame
            FramebufferHandler.syncStateFromHardware();
            
            // Check for window resize
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.displayWidth != lastWidth || mc.displayHeight != lastHeight) {
                onResize(mc.displayWidth, mc.displayHeight);
            }
        }
    }
    
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        // Perfect hook for post-processing effects
        // World has rendered, but GUI hasn't
        
        if (postProcessingEffect != null) {
            // Bind custom FBO
            AbstractFramebuffer effectFbo = FramebufferFactory.createFramebuffer(
                FramebufferCapabilities.DEFAULT,
                Minecraft.getMinecraft().displayWidth,
                Minecraft.getMinecraft().displayHeight
            );
            
            effectFbo.bind();
            
            // Apply shader with world texture as input
            // ... shader setup ...
            
            // Render full-screen quad
            renderFullscreenQuad();
            
            effectFbo.unbind();
            
            // Now effectFbo contains processed image
            // Can composite back to screen or chain to next effect
        }
    }
    
    private void onResize(int width, int height) {
        // Resize all tracked CrystalGraphics FBOs
        for (AbstractFramebuffer fbo : FramebufferHandler.getCreatedFramebuffers()) {
            if (fbo instanceof ResizableFramebuffer) {
                ((ResizableFramebuffer) fbo).resize(width, height);
            }
        }
        
        lastWidth = width;
        lastHeight = height;
    }
}
```

#### Step 4: Mixin Hooks

**MixinOpenGlHelper.java**:
```java
@Mixin(OpenGlHelper.class)
public class MixinOpenGlHelper {
    
    @Inject(method = "func_153171_g", at = @At("HEAD"))
    private static void preBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        // This is called whenever vanilla binds an FBO
        // Update CrystalGraphics state
        AbstractFramebuffer current = FramebufferHandler.getCurrentBuffer();
        if (current == null || current.getFramebufferPointer() != framebuffer) {
            // State changed externally - update our tracking
            AbstractFramebuffer newBuffer = findBufferById(framebuffer);
            if (newBuffer != null) {
                FramebufferHandler.setCurrentBuffer(newBuffer);
            } else {
                // Unknown FBO (from another mod) - mark as unowned
                FramebufferHandler.setCurrentBufferUnknown(framebuffer);
            }
        }
    }
    
    private static AbstractFramebuffer findBufferById(int id) {
        // Search createdFramebuffers set
        for (AbstractFramebuffer fbo : FramebufferHandler.getCreatedFramebuffers()) {
            if (fbo.getFramebufferPointer() == id) {
                return fbo;
            }
        }
        return null;
    }
}
```

**MixinMinecraft.java** (for resize handling):
```java
@Mixin(Minecraft.class)
public class MixinMinecraft {
    
    @Inject(method = "updateFramebufferSize", at = @At("HEAD"))
    private void preUpdateFramebufferSize(CallbackInfo ci) {
        // Trigger CrystalGraphics resize before vanilla
        CrystalGraphicsEventHandler.onPreVanillaResize();
    }
    
    @Inject(method = "updateFramebufferSize", at = @At("RETURN"))
    private void postUpdateFramebufferSize(CallbackInfo ci) {
        // Trigger CrystalGraphics resize after vanilla
        CrystalGraphicsEventHandler.onPostVanillaResize();
    }
}
```

---

## 4. Integration Strategy: Wrapper

### Overview
Wrap vanilla FBOs in CrystalGraphics abstractions for seamless API usage.

### Use Case
You want to use CrystalGraphics' API but need to interoperate with vanilla's `Framebuffer` objects.

### Implementation

```java
public class FramebufferAdapter {
    
    /**
     * Wrap a vanilla Framebuffer for CrystalGraphics API compatibility
     */
    public static AbstractFramebuffer wrapVanillaFramebuffer(Framebuffer vanilla) {
        return new AbstractFramebuffer(
            vanilla.framebufferObject,
            vanilla.framebufferWidth,
            vanilla.framebufferHeight,
            extractCapabilities(vanilla),
            false  // Vanilla owns this - don't delete
        ) {
            @Override
            public void drawBuffers(int... drawBuffers) {
                // Vanilla's Framebuffer doesn't support MRT
                // Just bind the color attachment
                if (drawBuffers.length > 0 && drawBuffers[0] == GL30.GL_COLOR_ATTACHMENT0) {
                    // Already the default
                }
            }
            
            @Override
            public void bind() {
                vanilla.bindFramebuffer(true);
            }
            
            @Override
            public void unbind() {
                vanilla.unbindFramebuffer();
            }
            
            @Override
            protected void freeMemory() {
                // Don't delete - vanilla owns it
                // Just remove from tracking
            }
        };
    }
    
    /**
     * Extract capabilities from vanilla Framebuffer
     */
    private static FramebufferCapabilities extractCapabilities(Framebuffer vanilla) {
        FramebufferCapabilities caps = new FramebufferCapabilities();
        if (vanilla.useDepth) {
            caps = caps.with(FramebufferFeature.FEATURE_DEPTH_BUFFER);
        }
        // Vanilla Framebuffer doesn't support stencil or MRT
        return caps;
    }
}
```

### Usage Example

```java
// Get vanilla FBO
Framebuffer vanillaFbo = Minecraft.getMinecraft().getFramebuffer();

// Wrap for CrystalGraphics API
AbstractFramebuffer wrapped = FramebufferAdapter.wrapVanillaFramebuffer(vanillaFbo);

// Now use CrystalGraphics API
wrapped.bind();
// ... render ...
wrapped.unbind();

// Safe to "delete" (won't actually delete vanilla FBO)
wrapped.delete();  // Calls freeMemory() which is no-op for unowned
```

---

## 5. Integration Strategy: Override (High Risk)

### Overview
Replace vanilla's FBO management entirely with CrystalGraphics.

### ⚠️ Warning
This is **NOT RECOMMENDED** unless you have a very specific use case and are willing to:
- Maintain compatibility with Optifine yourself
- Handle all edge cases vanilla handles
- Risk breaking other mods

### Use Case
Building a total conversion mod that replaces Minecraft's rendering entirely.

### Implementation Sketch

```java
// NOT RECOMMENDED - for reference only
public class OverrideStrategy {
    
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Cancel vanilla's framebuffer binding
            // Bind CrystalGraphics FBO instead
            
            Minecraft mc = Minecraft.getMinecraft();
            AbstractFramebuffer cgFbo = getCrystalGraphicsMainFbo();
            
            // Replace vanilla's framebufferMc
            // This requires reflection and is fragile:
            try {
                Field fboField = Minecraft.class.getDeclaredField("framebufferMc");
                fboField.setAccessible(true);
                
                // Create proxy that delegates to CrystalGraphics
                Framebuffer proxy = createProxyFramebuffer(cgFbo);
                fboField.set(mc, proxy);
            } catch (Exception e) {
                throw new RuntimeException("Failed to override vanilla FBO", e);
            }
        }
    }
}
```

**Problems with this approach**:
1. Reflection breaks easily with obfuscation changes
2. Optifine likely does the same thing - conflicts guaranteed
3. Hard to maintain across MC versions
4. Other mods may detect vanilla FBO by class type and break

---

## 6. State Synchronization Strategies

### The Problem
Even with Mixins, some mods may bind FBOs directly without going through intercepted methods.

### Solution 1: Periodic Hardware Sync (Recommended)

```java
public class StateSync {
    private static int lastKnownFramebuffer = -1;
    private static int syncCounter = 0;
    
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        // Sync every N frames or on demand
        if (++syncCounter >= 60) {  // Every 60 frames (~1 second)
            syncCounter = 0;
            syncFromHardware();
        }
    }
    
    public static void syncFromHardware() {
        // Query actual GL state
        int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        
        if (currentFbo != lastKnownFramebuffer) {
            // State changed externally
            AbstractFramebuffer buffer = findBufferById(currentFbo);
            if (buffer != null) {
                FramebufferHandler.setCurrentBuffer(buffer);
            } else {
                FramebufferHandler.setCurrentBufferUnknown(currentFbo);
            }
            lastKnownFramebuffer = currentFbo;
        }
    }
}
```

### Solution 2: Event-Based Sync

Sync at critical points in the render pipeline:

```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onRenderWorldLast(RenderWorldLastEvent event) {
    // Sync before our custom rendering
    StateSync.syncFromHardware();
}

@SubscribeEvent(priority = EventPriority.LOWEST)
public void onRenderWorldLastPost(RenderWorldLastEvent event) {
    // Sync after to catch any changes
    StateSync.syncFromHardware();
}
```

### Solution 3: Mixin to Raw GL Calls

Most aggressive but comprehensive:

```java
@Mixin(GL30.class)
public class MixinGL30 {
    @Inject(method = "glBindFramebuffer", at = @At("HEAD"))
    private static void onBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        FramebufferHandler.updateCurrentBuffer(framebuffer);
    }
}

@Mixin(ARBFramebufferObject.class)
public class MixinARBFramebufferObject {
    @Inject(method = "glBindFramebuffer", at = @At("HEAD"))
    private static void onBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        FramebufferHandler.updateCurrentBuffer(framebuffer);
    }
}

@Mixin(EXTFramebufferObject.class)
public class MixinEXTFramebufferObject {
    @Inject(method = "glBindFramebufferEXT", at = @At("HEAD"))
    private static void onBindFramebufferEXT(int target, int framebuffer, CallbackInfo ci) {
        FramebufferHandler.updateCurrentBuffer(framebuffer);
    }
}
```

---

## 7. Optifine Compatibility

### The Challenge
Optifine is ubiquitous in 1.7.10 modpacks and heavily modifies rendering.

### Detection

```java
public class OptifineCompat {
    
    private static Boolean optifinePresent = null;
    
    public static boolean isOptifinePresent() {
        if (optifinePresent == null) {
            try {
                Class.forName("Config");
                optifinePresent = true;
            } catch (ClassNotFoundException e) {
                optifinePresent = false;
            }
        }
        return optifinePresent;
    }
    
    public static boolean isShaderActive() {
        if (!isOptifinePresent()) return false;
        try {
            Class<?> configClass = Class.forName("Config");
            Method method = configClass.getMethod("isShaders");
            return (Boolean) method.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }
}
```

### Compatibility Strategy

1. **Detect early** in `FMLPostInitializationEvent`
2. **Defer** to Optifine if shaders are active
3. **Use conservative** state tracking (sync from hardware frequently)

```java
@SubscribeEvent
public void onRenderWorldLast(RenderWorldLastEvent event) {
    if (OptifineCompat.isShaderActive()) {
        // Optifine manages shaders - don't interfere
        // Or integrate with Optifine's shader pipeline specifically
        return;
    }
    
    // Safe to use CrystalGraphics
    applyCustomPostProcessing();
}
```

---

## 8. Testing Strategy

### Unit Tests (with Mock GL)

```java
@Test
public void testFboCreation() {
    // Mock GL context
    MockGLContext.setup();
    
    FramebufferCapabilities caps = FramebufferCapabilities.DEFAULT
        .with(FramebufferFeature.FEATURE_DEPTH_BUFFER);
    
    AbstractFramebuffer fbo = FramebufferFactory.createFramebuffer(caps, 1920, 1080);
    
    assertNotNull(fbo);
    assertTrue(fbo.getFramebufferPointer() > 0);
    assertEquals(1920, fbo.getWidth());
    assertEquals(1080, fbo.getHeight());
}
```

### Integration Tests (with Real GL)

```java
public class IntegrationTest {
    
    @Test
    public void testVanillaCompatibility() {
        // Requires Minecraft running
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer vanillaFbo = mc.getFramebuffer();
        
        // Wrap vanilla FBO
        AbstractFramebuffer wrapped = FramebufferAdapter.wrapVanillaFramebuffer(vanillaFbo);
        
        // Bind via CrystalGraphics API
        wrapped.bind();
        
        // Verify vanilla sees the same state
        assertEquals(vanillaFbo.framebufferObject, 
                     GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        
        wrapped.unbind();
    }
}
```

### Hardware Compatibility Tests

| Test | GL Version | Extensions | Expected Path |
|------|------------|------------|---------------|
| Modern NVIDIA | 4.6 | GL30, ARB | Core |
| Intel UHD | 4.6 | GL30, ARB | Core |
| Old Intel HD | 3.1 | EXT only | EXT |
| AMD Legacy | 3.3 | GL30, ARB | Core |

---

## 9. Common Pitfalls

### 1. Forgetting to Unbind

**Wrong**:
```java
fbo.bind();
// render
// forgot to unbind!
// Next render operation goes to wrong FBO
```

**Right**:
```java
fbo.bind();
try {
    // render
} finally {
    fbo.unbind();
}
```

### 2. Deleting Owned FBOs

**Wrong**:
```java
AbstractFramebuffer vanillaWrapped = wrapVanillaFramebuffer(vanillaFbo);
vanillaWrapped.delete();  // ❌ Deletes vanilla's FBO!
```

**Right**:
```java
AbstractFramebuffer vanillaWrapped = wrapVanillaFramebuffer(vanillaFbo);
vanillaWrapped.delete();  // ✅ Safe - Wrapper's freeMemory() is no-op
// Or just don't call delete() on wrapped vanilla FBOs
```

### 3. Thread Safety

**Wrong**:
```java
// Called from worker thread
new Thread(() -> {
    fbo.bind();  // ❌ GL contexts are thread-specific
}).start();
```

**Right**:
```java
// Only bind on main client thread
Minecraft.getMinecraft().addScheduledTask(() -> {
    fbo.bind();  // ✅ Safe
});
```

### 4. Resizing Without Recreating

**Wrong**:
```java
// Window resized to 4K
fbo.bind();
// Still has 1080p texture attached
// Render output is wrong size/corrupted
```

**Right**:
```java
@SubscribeEvent
public void onResize(DisplayResizeEvent event) {
    // Delete and recreate
    fbo.delete();
    fbo = FramebufferFactory.createFramebuffer(caps, 
        event.getNewWidth(), 
        event.getNewHeight()
    );
}
```

---

## 10. Summary

### Recommended Approach

1. **Use Cooperative + Wrapper strategy**
2. **Initialize in** `FMLPreInitializationEvent`
3. **Subscribe to** `RenderTickEvent` and `RenderWorldLastEvent`
4. **Use Mixins** to `OpenGlHelper.func_153171_g()` for state tracking
5. **Sync from hardware** periodically as safety net
6. **Wrap vanilla FBOs** for CrystalGraphics API compatibility
7. **Detect Optifine** and defer when active

### Key Integration Points

| Integration Point | File/Method | Purpose |
|-------------------|-------------|---------|
| Initialization | `FMLPreInitializationEvent` | Setup RenderSystem |
| State Tracking | `MixinOpenGlHelper.func_153171_g()` | Track FBO bindings |
| Resize | `Minecraft.updateFramebufferSize()` | Sync FBO sizes |
| Post-Processing | `RenderWorldLastEvent` | Apply custom effects |
| Safety Sync | `RenderTickEvent` | Periodic state verification |

### Next Steps

1. Implement `CoreFramebufferHandler` with actual GL calls
2. Add Mixin infrastructure to build.gradle
3. Create test mod that uses CrystalGraphics API
4. Test with and without Optifine
5. Document public API for mod developers

---

**Document References**:
- `MINECRAFT_FBO_ANALYSIS.md` - Vanilla FBO implementation details
- `MINECRAFT_SHADER_ANALYSIS.md` - Vanilla shader implementation details
- `AGENTS.md` - Project overview and architecture
