# CrystalGraphics - AI Agent Knowledge Base

**Project Type**: Java Library (Java 8)  
**Target Environment**: Minecraft 1.7.10 Forge Mods  
**Graphics API**: OpenGL via LWJGL 2.9.3  
**Build System**: Gradle (GTNH Convention Plugin)

---

## TO BUILD
Run ./gradlew.bat compileJava and WAIT until it finishes. DO NOT be impatient and kill the process, it will take about 60 seconds, 
and DO NOT run it multiple times in parallel, it will cause out-of-memory errors. 

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
- `docs/MINECRAFT_FBO_ANALYSIS.md` - Complete trace of vanilla FBO system
- `docs/MINECRAFT_SHADER_ANALYSIS.md` - Vanilla shader architecture
- `docs/CRITICAL_GOTCHAS.md` - Hidden vanilla behaviors
- `docs/INTEGRATION_STRATEGY.md` - Integration patterns
- `.sisyphus/plans/external-gl-state-capture.md` - Implementation plan (read-only)

## External References

**LWJGL 2.9 Javadoc**: https://javadoc.lwjgl.org/  
**OpenGL Registry**: https://www.khronos.org/registry/OpenGL/  
**Minecraft 1.7.10 Source**: Via ForgeGradle deobfuscation  
**GTNH Build Plugin**: https://github.com/GTNewHorizons/
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

**External GL State Capture**: Other mods frequently bind/unbind FBOs and shader programs. Without observing these external calls, CrystalGraphics would drift and corrupt GL state.

### Project Goals

1. **External GL State Capture** (✅ IMPLEMENTED): Always-on coremod transformer that intercepts FBO/program/texture binds made by other mods
2. **Framebuffer Abstraction** (✅ IMPLEMENTED): Unified API for FBO creation/management with automatic fallback (Core → ARB → EXT)
3. **Shader Abstraction** (✅ IMPLEMENTED): Similar pattern for shader programs (GL20 vs. ARB)
4. **Capability Detection** (✅ IMPLEMENTED): High-level API to check hardware capabilities
5. **Angelica Coexistence** (✅ IMPLEMENTED): Gap-only mode when Angelica shader mod is present

### Project Philosophy

**Pragmatic, Not Perfect**: This targets a 13-year-old game on a legacy OpenGL version. The goal is stability across a wide hardware range, not cutting-edge graphics.

**Fail Fast**: If a capability is unavailable, throw an exception. Don't silently degrade in ways that hide bugs.

**Multi-Mod First**: Assume other mods will mess with GL state. Design for cooperation, not control.

**Test on Real Hardware**: Simulated GL contexts don't expose driver bugs. Test on actual Intel/NVIDIA/AMD GPUs.

### Architecture Principles

- **Always-on Coremod**: Ships as `IFMLLoadingPlugin` with ASM transformer
- **No LWJGL Class Patching**: Cannot transform `org.lwjgl.*` (system classloader), use callsite interception
- **Angelica-First**: Detect Angelica presence; run in gap-only mode when present
- **Cross-API Safety**: Track observed "call family" and explicitly unbind prior family before binding new family
- **Fail Fast**: Throw exceptions for unsupported capabilities rather than silently degrading

---

## Current Implementation State

**Architecture**: ✅ Implemented  
**Infrastructure**: ✅ Complete (tests, integration mod, examples)  
**Implementation**: ✅ Production-ready

### What Exists

#### 1. External GL State Capture (✅ COMPLETE)

**Coremod Layer** (`mc/coremod/`):
- `CrystalGraphicsCoremod`: IFMLLoadingPlugin entrypoint, Angelica detection, mode selection
- `CrystalGraphicsTransformer`: ASM callsite rewriter (INVOKESTATIC only), exclusions, idempotency
- `CoverageMatrix`: Single source of truth for redirect coverage (FULL vs GAP_ONLY)
- `CrystalGLRedirects`: Redirect methods; updates mirror then calls original; recursion-safe

**State Tracking** (`gl/state/`):
- `GLStateMirror`: Pure-Java tracked state (FBO, program, texture) + recursion depth guard
- `CallFamily`: Enum tracking GL call families (CORE_GL30, ARB_FBO, EXT_FBO, etc.)
- `CgStateSnapshot`: Immutable point-in-time state capture
- `CgStateBoundary`: Save/restore API with fallback to glGet* when mirror is untrusted

**Coverage**:
- FULL_MODE (Angelica absent): All tracked callsites rewritten
- GAP_ONLY_MODE (Angelica present): Only ARB/EXT FBO binds and ARB shader binds rewritten

#### 2. Framebuffer Abstraction (✅ COMPLETE)

**Public API** (`api/`):
- `CgFramebuffer`: Unified FBO interface (bind/unbind/resize/drawBuffers/ownership)
- `CgCapabilities`: Capability detection with preferred backend selection

**Implementations** (`gl/framebuffer/`):
- `AbstractCgFramebuffer`: Base class with ownership model and static cleanup
- `CoreFramebuffer`: GL30 backend with MRT support
- `ArbFramebuffer`: ARB_framebuffer_object backend with MRT support
- `ExtFramebuffer`: EXT_framebuffer_object backend (no MRT, no separate draw/read)
- `CgFramebufferFactory`: Waterfall factory (Core → ARB → EXT)

**Cross-API Safety**:
- `CrossApiTransition`: Explicit unbind-on-family-change behavior (Core <-> ARB <-> EXT)

#### 3. Shader Abstraction (✅ COMPLETE)

**Public API** (`api/`):
- `CgShaderProgram`: Unified shader interface (bind/unbind/uniforms/samplers)

**Implementations** (`gl/shader/`):
- `AbstractCgShaderProgram`: Base class with ownership model
- `CoreShaderProgram`: GL20 backend
- `ArbShaderProgram`: ARB_shader_objects backend
- `CgShaderFactory`: Waterfall factory (Core → ARB)

#### 4. Capability Detection (✅ COMPLETE)

- `CgCapabilities.detect()`: Queries LWJGL ContextCapabilities
- Waterfall selection: Core GL30 > ARB > EXT
- Reports: maxDrawBuffers, maxTextureUnits, stencil/depth support

#### 5. Integration & Testing (✅ COMPLETE)

**Integration Test Mod** (`mc/integration/`):
- `CrystalGraphicsIntegrationTest`: Dev-only Forge mod that verifies redirect layer

**Unit Tests** (`src/test/`):
- `CrystalGraphicsTransformerTest`: Bytecode-level ASM rewrite tests (10 tests)

**Forge Container**:
- `CrystalGraphics`: `@Mod(modid="crystalgraphics")` container for dependency resolution

---

## Architecture Patterns

### 1. Waterfall Fallback System

**Priority Order**: Core (GL30) → ARB → EXT

```java
// CgFramebufferFactory.create()
if (caps.isCoreFbo()) {
    return CoreFramebuffer.create(width, height, depth, mrt);
}
if (caps.isArbFbo()) {
    return ArbFramebuffer.create(width, height, depth, mrt);
}
if (caps.isExtFbo()) {
    return ExtFramebuffer.create(width, height, depth, mrt);
}
throw new UnsupportedOperationException("No FBO support available");
```

### 2. External State Capture via Callsite Rewriting

Cannot transform `org.lw
jgl.*` directly (system classloader). Instead:

```java
// In other mods' bytecode, we rewrite:
// GL30.glBindFramebuffer(target, id)
// to:
// CrystalGLRedirects.bindFramebufferCore(target, id)

// Redirect method updates mirror then calls original:
public static void bindFramebufferCore(int target, int id) {
    GLStateMirror.onBindFramebuffer(target, id, CallFamily.CORE_GL30);
    GL30.glBindFramebuffer(target, id);
}
```

### 3. Recursion-Safe State Tracking

```java
// ThreadLocal depth counter (not boolean) for nested redirects
private static final ThreadLocal<Integer> redirectDepth = ThreadLocal.withInitial(() -> 0);

public static void enterRedirect() {
    redirectDepth.set(redirectDepth.get() + 1);
}

public static boolean isInRedirect() {
    return redirectDepth.get() > 0;
}
```

### 4. Cross-API Binding Transitions

When switching families (ARB → Core), some drivers misbehave:

```java
// CrossApiTransition.bindFramebuffer()
CallFamily current = GLStateMirror.getCurrentFboFamily();
if (current != targetFamily && current != CallFamily.UNKNOWN) {
    // Unbind using OLD family first
    unbindViaFamily(current);
}
// Then bind using NEW family
bindViaFamily(targetFamily, target, id);
```

### 5. Ownership Model

```java
// CrystalGraphics-created FBOs can be deleted
CgFramebuffer fbo = CgFramebufferFactory.create(caps, w, h, depth, mrt);
fbo.delete(); // OK

// Wrapped external FBOs cannot be deleted
CgFramebuffer wrapped = CgFramebufferFactory.wrap(id, w, h, family);
wrapped.delete(); // Throws IllegalStateException
```

### 6. Boundary Save/Restore

```java
// CrystalGraphics code should wrap its GL operations:
CgStateSnapshot snapshot = CgStateBoundary.save();
try {
    // Do FBO/shader work
    fbo.bind();
    program.bind();
    // ... render ...
} finally {
    CgStateBoundary.restore(snapshot);
}
```

---

## Package Structure

```
io.github.somehussar.crystalgraphics/
├── CrystalGraphics.java           # Forge @Mod container (dependency resolution)
├── api/                           # Stable public contracts (no MC imports)
│   ├── CgFramebuffer.java         # FBO interface
│   ├── CgShaderProgram.java       # Shader interface
│   └── CgCapabilities.java        # Capability detection
├── gl/                            # OpenGL backends + state logic
│   ├── CrossApiTransition.java    # Safe family-switching
│   ├── framebuffer/               # FBO implementations
│   │   ├── AbstractCgFramebuffer.java
│   │   ├── CoreFramebuffer.java
│   │   ├── ArbFramebuffer.java
│   │   ├── ExtFramebuffer.java
│   │   └── CgFramebufferFactory.java
│   ├── shader/                    # Shader implementations
│   │   ├── AbstractCgShaderProgram.java
│   │   ├── CoreShaderProgram.java
│   │   ├── ArbShaderProgram.java
│   │   └── CgShaderFactory.java
│   └── state/                     # State tracking
│       ├── GLStateMirror.java
│       ├── CallFamily.java
│       ├── CgStateSnapshot.java
│       └── CgStateBoundary.java
├── mc/                            # Minecraft/Forge integration
│   ├── coremod/                   # ASM transformer
│   │   ├── CrystalGraphicsCoremod.java
│   │   ├── CrystalGraphicsTransformer.java
│   │   ├── CoverageMatrix.java
│   │   └── CrystalGLRedirects.java
│   └── integration/               # Dev test mod
│       └── CrystalGraphicsIntegrationTest.java
└── mixins/                        # Mixin configs (existing)
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

### Capability Detection Pattern

```java
public static CgCapabilities detect() {
    ContextCapabilities caps = GLContext.getCapabilities();
    
    boolean coreFbo = caps.OpenGL30;
    boolean arbFbo = caps.GL_ARB_framebuffer_object;
    boolean extFbo = caps.GL_EXT_framebuffer_object;
    boolean coreShaders = caps.OpenGL20;
    boolean arbShaders = caps.GL_ARB_shader_objects;
    
    int maxDrawBuffers = coreShaders 
        ? GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) 
        : 1;
    
    // ... construct and return ...
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

**Key Properties**:
```properties
modId = crystalgraphics
modGroup = io.github.somehussar.crystalgraphics
coreModClass = mc.coremod.CrystalGraphicsCoremod
containsMixinsAndOrCoreModOnly = false  # Now false because we have @Mod container
```

### Dependencies

```gradle
implementation("org.lwjgl.lwjgl:lwjgl:2.9.3")
implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
compileOnly("org.projectlombok:lombok:1.18.32")
testImplementation("junit:junit:4.13.2")
```

**Runtime Dev Mods**:
- NotEnoughItems (NEI)
- Nashorn (JavaScript engine backport)

### Development Environment

**Minecraft Version**: 1.7.10  
**Forge Version**: 10.13.4.1614  
**MCP Mappings**: stable_12

**Common Commands**:
```bash
./gradlew.bat test              # Run unit tests
./gradlew.bat runClient         # Launch dev client with integration test mod
./gradlew.bat build             # Build release jar
```

**Memory-Constrained Gradle**:
```bash
./gradlew.bat test --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=256m"
```

---

## Debug / Troubleshooting Flags

### Coremod/Transformer Flags

```bash
# Disable all transforms
-Dcrystalgraphics.redirector.disable=true

# Enable verbose logging
-Dcrystalgraphics.redirector.verbose=true

# Limit verbose logs to class prefix
-Dcrystalgraphics.redirector.verbosePrefix=net.minecraft.

# Force Angelica mode (override auto-detection)
-Dcrystalgraphics.redirector.forceAngelica=true|false
```

### Boundary Snapshot Flags

```bash
# Force glGet* capture even if mirror seems valid
-Dcrystalgraphics.boundary.forceGlGet=true
```

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
   - If `isOwned() == false`, NEVER call `delete()`
   - Use `CgFramebufferFactory.wrap()` for externally-created FBOs

4. **EXT suffix is mandatory**:
   - Always use `*EXT` methods for EXT extension
   - `EXTFramebufferObject.glBindFramebufferEXT()` not `glBindFramebuffer()`

5. **No GL calls in state mirror**:
   - `GLStateMirror` is pure Java tracking only
   - Never call `glGetInteger` from mirror methods

6. **Recursion guard is depth-based**:
   - Use `GLStateMirror.enterRedirect()` / `exitRedirect()`
   - Check `isInRedirect()` before updating mirror in redirects

7. **Java 8 only**:
   - No lambdas (unless Lombok generates them)
   - No `var` keyword
   - No modules

### Testing Requirements

**Before claiming "done"**:
1. T
