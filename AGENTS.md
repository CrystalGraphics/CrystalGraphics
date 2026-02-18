# CrystalGraphics - AI Agent Knowledge Base

**Project Type**: Java Library (Java 8)  
**Target Environment**: Minecraft 1.7.10 Forge Mods  
**Graphics API**: OpenGL via LWJGL 2.9.3  
**Build System**: Gradle (GTNH Convention Plugin)

---

## Minecraft Source Code Location

**CRITICAL**: The Minecraft 1.7.10 and Forge source code is decompiled and deobfuscated at:

```
build/rfg/minecraft-src/java/
├── net/minecraft/           # Minecraft source
│   ├── client/
│   │   ├── Minecraft.java              # Main game class, owns framebufferMc
│   │   ├── renderer/
│   │   │   ├── EntityRenderer.java   # Render pipeline, shader integration
│   │   │   ├── OpenGlHelper.java     # GL extension detection (REFERENCE IMPL)
│   │   │   └── texture/TextureUtil.java
│   │   └── shader/
│   │       ├── Framebuffer.java      # Vanilla FBO wrapper
│   │       ├── ShaderGroup.java      # Post-processing pipeline
│   │       ├── Shader.java           # Single shader pass
│   │       ├── ShaderManager.java    # GLSL program management
│   │       └── ShaderUniform.java    # Uniform variable handling
│   └── ...
└── cpw/mods/fml/             # Forge source
    ├── client/FMLClientHandler.java
    ├── common/gameevent/TickEvent.java
    └── ...
```

**Key Files to Reference**:
- `net/minecraft/client/Minecraft.java` - FBO lifecycle (`framebufferMc`)
- `net/minecraft/client/renderer/OpenGlHelper.java` - Extension detection (THE reference implementation)
- `net/minecraft/client/renderer/EntityRenderer.java` - Render pipeline
- `net/minecraft/client/shader/Framebuffer.java` - Vanilla FBO implementation
- `net/minecraftforge/client/event/RenderWorldLastEvent.java` - Forge event hook

**Analysis Documents**:
- `MINECRAFT_FBO_ANALYSIS.md` - Complete trace of vanilla FBO system
- `MINECRAFT_SHADER_ANALYSIS.md` - Vanilla shader architecture
- `INTEGRATION_STRATEGY.md` - How to integrate with MC/Forge

---

## What This Project Is

CrystalGraphics is a **graphics abstraction layer** for OpenGL, specifically designed for Minecraft 1.7.10 modding. It provides a **unified API** for creating and managing framebuffer objects (FBOs) and shaders across different OpenGL versions and extension types.

### The Core Problem It Solves

Minecraft 1.7.10 runs on LWJGL 2.9, which exposes OpenGL functionality through **incompatible Java classes**:

- **GL30** (Core OpenGL 3.0)
- **ARBFramebufferObject** (ARB extension)
- **EXTFramebufferObject** (EXT extension - legacy)

These have different method signatures (`glGenFramebuffers()` vs. `glGenFramebuffersEXT()`), different enum constants, and require different capability checks. You cannot simply "import the right one"—you must abstract the calls.

### Why This Matters

**Hardware Fragmentation**: Players run Minecraft 1.7.10 on everything from 2010-era Intel integrated graphics (which only support EXT extensions) to modern RTX 4090s (which support Core GL30). A mod that works on modern hardware will crash on legacy hardware without proper abstraction.

**Multi-Mod Environment**: Multiple mods manipulate OpenGL state simultaneously. CrystalGraphics must:
1. Track FBOs it creates vs. FBOs created by other mods
2. Provide hooks for state synchronization via Mixins
3. Gracefully handle other mods calling `glBindFramebuffer` directly

### Project Goals

1. **Framebuffer Abstraction** (High Priority): Unified API for FBO creation/management with automatic fallback (Core → ARB → EXT)
2. **Shader Abstraction** (High Priority): Similar pattern for shader programs (GL20 vs. ARB vs. EXT)
3. **Capability Detection** (Mid Priority): High-level API to check hardware capabilities
4. **Rendering Approaches** (Mid Priority): Framework for defining post-processing effects with automatic quality degradation on low-end hardware

### Remain Minecraft/Forge Agnostic

While the primary use case is Minecraft 1.7.10 mods, the library should work in any LWJGL 2.9 context without Minecraft/Forge dependencies.

---

## Current Implementation State

**Architecture**: ✅ Designed  
**Infrastructure**: ❌ Missing (no tests, no examples)  
**Implementation**: ❌ Stub only (all handlers return `null`/`false`)

### What Exists

#### 1. Framebuffer Abstraction (Skeleton Only)

**Core Classes**:
- `AbstractFramebuffer`: Abstract base class for FBO implementations
- `FramebufferHandler`: Abstract base class for extension-specific handlers
- `FramebufferFactory`: Factory with waterfall logic (Core → ARB → EXT)
- `FramebufferCapabilities`: Immutable capability set (depth buffer, stencil, MRT)

**Handler Implementations** (all non-functional stubs):
- `CoreFramebufferHandler` (GL30)
- `ARBFramebufferHandler` (ARB extension)
- `EXTFramebufferHandler` (EXT extension)

**Current Status**: Structure is correct, but **zero working code**. All handlers return `null` or `false`.

#### 2. Capability System (Partial)

**Enums Defined**:
- `FramebufferFeature`: `FEATURE_DEPTH_BUFFER`, `FEATURE_STENCIL_BUFFER`, `FEATURE_MULTI_RENDER_TARGETS`
- `RenderCapability`: High-level capabilities (defined but **unused**)

**Detection Logic**: ❌ Not implemented

#### 3. Resource Management

**Tracking System**:
```java
static final Set<AbstractFramebuffer> createdFramebuffers = new HashSet<>();
```
Tracks all FBOs created by this library for bulk cleanup.

**Ownership Flag**:
```java
protected final boolean doWeOwnThisBuffer;
```
Prevents deleting FBOs created by other mods.

**Wrapping Hook**:
```java
protected static Supplier<AbstractFramebuffer> wrappingMethod = () -> null;
```
Allows external code (via Mixins) to inject FBO state when other mods call `glBindFramebuffer` directly.

---

## Architecture Patterns

### 1. Waterfall Fallback System

**Priority Order**: Core (GL30) → ARB → EXT

```java
// FramebufferFactory.createFramebuffer()
if (CoreFramebufferHandler.get().isSupported(caps)) {
    return CoreFramebufferHandler.get().create(caps, width, height);
}
if (ARBFramebufferHandler.get().isSupported(caps)) {
    return ARBFramebufferHandler.get().create(caps, width, height);
}
if (EXTFramebufferHandler.get().isSupported(caps)) {
    return EXTFramebufferHandler.get().create(caps, width, height);
}
throw new UnsupportedOperationException(...);
```

### 2. Singleton Handlers

Each handler is a singleton initialized lazily:
```java
private static final CoreFramebufferHandler INSTANCE = new CoreFramebufferHandler();

public static FramebufferHandler get() {
    EnsureRenderSystemExists();
    return INSTANCE;
}
```

### 3. Capability-Based Creation

FBOs are requested by capability, not by implementation:
```java
FramebufferCapabilities caps = FramebufferCapabilities.DEFAULT
    .with(FramebufferFeature.FEATURE_STENCIL_BUFFER)
    .with(FramebufferFeature.FEATURE_MULTI_RENDER_TARGETS);

AbstractFramebuffer fbo = FramebufferFactory.createFramebuffer(caps, 1920, 1080);
```

### 4. Initialization Guard

All operations require `RenderSystem.initialize()` to be called first:
```java
public static void EnsureRenderSystemExists() {
    if (!RenderSystem.hasInitialized()) {
        throw new IllegalStateException("Render system hasn't initialized yet.");
    }
}
```

---

## Package Structure

```
io.github.somehussar.crystalgraphics/
├── RenderSystem.java              # Global init/deinit
├── RenderCapability.java          # High-level capability enum (UNUSED)
├── framebuffer/
│   ├── AbstractFramebuffer.java   # Base class for FBO implementations
│   ├── FramebufferHandler.java    # Base class for extension handlers
│   ├── FramebufferFactory.java    # Factory with waterfall logic
│   ├── capabilities/
│   │   ├── FramebufferCapabilities.java  # Immutable capability set
│   │   └── FramebufferFeature.java       # Enum of FBO features
│   └── impl/
│       ├── CoreFramebufferHandler.java   # GL30 implementation (STUB)
│       ├── ARBFramebufferHandler.java    # ARB implementation (STUB)
│       └── EXTFramebufferHandler.java    # EXT implementation (STUB)
```

**Expected Future Structure**:
```
io.github.somehussar.crystalgraphics/
├── shader/                        # NOT YET IMPLEMENTED
│   ├── AbstractShaderProgram.java
│   ├── ShaderHandler.java
│   ├── ShaderFactory.java
│   └── impl/
│       ├── CoreShaderHandler.java
│       ├── ARBShaderHandler.java
│       └── EXTShaderHandler.java
```

---

## Technical Constraints

### LWJGL 2.9.3 API Differences

**Method Signatures**:
```java
// Core (GL30)
int fbo = GL30.glGenFramebuffers();
GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

// ARB
int fbo = ARBFramebufferObject.glGenFramebuffers();
ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, fbo);

// EXT (NOTE: EXT suffix)
int fbo = EXTFramebufferObject.glGenFramebuffersEXT();
EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fbo);
```

**Critical**: EXT methods **require** the `EXT` suffix. Calling the non-suffix version crashes.

### Capability Detection

**Required Pattern** (not yet implemented):
```java
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

@Override
protected void handleInitialization() {
    ContextCapabilities caps = GLContext.getCapabilities();
    
    // Example for CoreFramebufferHandler
    if (!caps.OpenGL30) return;
    
    featuresSupported.add(FramebufferFeature.FEATURE_DEPTH_BUFFER);
    featuresSupported.add(FramebufferFeature.FEATURE_STENCIL_BUFFER);
    featuresSupported.add(FramebufferFeature.FEATURE_MULTI_RENDER_TARGETS);
}

@Override
public boolean availableInCurrentContext() {
    return GLContext.getCapabilities().OpenGL30;
}
```

### Hardware Compatibility Targets

**Minimum Spec**: Intel HD Graphics 3000 (2011, OpenGL 3.1 + EXT extensions)  
**Typical Spec**: NVIDIA GTX 700 series (2013, full OpenGL 4.x)  
**Modern Spec**: RTX 4090 (2023, OpenGL 4.6)

**Driver Quirks**:
- Old Intel drivers often claim GL30 support but have broken ARB implementations
- Some drivers don't properly route ARB calls to Core when available
- EXT is the most stable on legacy hardware, but lacks advanced features

---

## Build System

### Gradle Configuration

**Plugin**: `com.gtnewhorizons.gtnhconvention` (GT New Horizons custom build system)  
**Java Target**: Java 8 (required for Minecraft 1.7.10 compatibility)

**Key Files**:
- `build.gradle.kts`: Main build config (Kotlin DSL)
- `dependencies.gradle`: Centralized dependency management
- `gradle.properties`: Project metadata and feature toggles

### Dependencies

```gradle
implementation("org.lwjgl.lwjgl:lwjgl:2.9.3")
implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
compileOnly("org.projectlombok:lombok:1.18.32")
```

**Runtime Natives** (for testing):
```gradle
runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.3:natives-windows")
runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.3:natives-linux")
runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.3:natives-osx")
```

### Development Environment

**Minecraft Version**: 1.7.10  
**Forge Version**: 10.13.4.1614  
**MCP Mappings**: stable_12

**Dev Mods** (runtime only):
- NotEnoughItems (NEI)
- Nashorn (JavaScript engine backport)

### Code Quality Tools

**Current Status**: Disabled
```properties
disableSpotless = true
disableCheckstyle = true
```

---

## TODO (from TODO.md)

### High Priority

1. **Framebuffer Abstractions**:
   - Implement Core/ARB/EXT handlers with actual GL calls
   - Support depth buffers, stencil buffers, MRT
   - Decide: Support GL2.0 (EXT fallback) or require GL3.0?
   - Handle `getCurrentBuffer()` without excessive `glGet` calls
   - Consider Mixin approach for state tracking with other mods

2. **Shader Abstractions**:
   - Create shader handler pattern (mirror FBO structure)
   - Support GL20, ARB, EXT shader extensions
   - Handle binding back to other mods' shaders

### Mid Priority

3. **Capability Checking**:
   - High-level API to query GL context capabilities
   - Use for shader and framebuffer abstraction decisions

4. **Rendering Approach Framework**:
   - Define post-processing effects as "approaches"
   - Set up shaders + FBOs for each approach
   - Automatic waterfall fallback if capabilities unavailable

---

## What AI Agents Need to Know

### When Working on This Project

1. **NEVER suppress OpenGL errors**:
   - No `glGetError()` without handling
   - No silent fallbacks that mask bugs

2. **Always check capabilities before GL calls**:
   - Use `GLContext.getCapabilities()` to detect extensions
   - Fail fast if required extension is unavailable

3. **Respect the ownership model**:
   - If `doWeOwnThisBuffer == false`, NEVER call `delete()`
   - Use wrapping hooks for externally-created FBOs

4. **Match existing patterns**:
   - Singleton handlers
   - Immutable capabilities with builder pattern
   - Factory methods for object creation

5. **Java 8 only**:
   - No lambdas (unless Lombok generates them)
   - No `var` keyword
   - No modules

### Testing Requirements

**Before claiming "done"**:
1. Test on GL30 context (Core path)
2. Test on GL2.1 + ARB context (ARB path)
3. Test on GL2.0 + EXT context (EXT path)
4. Verify resource cleanup (no GL object leaks)
5. Test multi-mod scenario (FBO created externally)

### Common Pitfalls

1. **EXT suffix**: Always use `*EXT` methods for EXT extension
2. **Enum sources**: Use constants from the correct class (`GL30.GL_FRAMEBUFFER` vs. `EXTFramebufferObject.GL_FRAMEBUFFER_EXT`)
3. **State tracking**: Don't assume `currentBuffer` is accurate—other mods bypass it
4. **Thread safety**: GL contexts are thread-local, but static state is not

---

## Questions to Ask Before Starting Work

### For FBO Implementation
- Which handler am I implementing? (Core/ARB/EXT)
- Do I have the correct LWJGL class imported?
- Am I using the right method suffixes (especially for EXT)?
- Have I populated `featuresSupported` based on actual capabilities?
- Does my `availableInCurrentContext()` check the right capability flag?

### For Shader Implementation
- Should I mirror the FBO handler pattern?
- What shader features need capability checks? (geometry shaders, tessellation, etc.)
- How do I handle shader compilation errors gracefully?

### For Testing
- Do I have a real GL context to test against?
- How do I mock/simulate different GL versions?
- What's the cleanup strategy to prevent test pollution?

---

## Success Criteria

**Milestone 1: FBO MVP**
- [ ] One handler fully implemented (suggest Core)
- [ ] Capability detection working
- [ ] FBO creation, binding, deletion working
- [ ] Integration test with real GL context passing

**Milestone 2: Complete FBO Support**
- [ ] All three handlers implemented (Core, ARB, EXT)
- [ ] Waterfall fallback tested on different GL versions
- [ ] Resource tracking verified (no leaks)
- [ ] Multi-mod scenario tested

**Milestone 3: Shader Support**
- [ ] Shader handler pattern implemented
- [ ] GL20/ARB/EXT shader compilation working
- [ ] Shader program binding/unbinding working

**Milestone 4: Production Ready**
- [ ] Capability API public and documented
- [ ] Rendering approach framework implemented
- [ ] Tested in real Minecraft 1.7.10 mod
- [ ] Tested with Optifine/shader mods

---

## External References

**LWJGL 2.9 Javadoc**: https://javadoc.lwjgl.org/  
**OpenGL Registry**: https://www.khronos.org/registry/OpenGL/  
**Minecraft 1.7.10 Source**: Via ForgeGradle deobfuscation  
**GTNH Build Plugin**: https://github.com/GTNewHorizons/

---

## Project Philosophy

**Pragmatic, Not Perfect**: This targets a 13-year-old game on a legacy OpenGL version. The goal is stability across a wide hardware range, not cutting-edge graphics.

**Fail Fast**: If a capability is unavailable, throw an exception. Don't silently degrade in ways that hide bugs.

**Multi-Mod First**: Assume other mods will mess with GL state. Design for cooperation, not control.

**Test on Real Hardware**: Simulated GL contexts don't expose driver bugs. Test on actual Intel/NVIDIA/AMD GPUs.
