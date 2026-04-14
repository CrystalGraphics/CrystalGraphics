# CrystalGraphics - AI Agent Knowledge Base

**Project Type**: Java Library (Java 8)  
**Target Environment**: Minecraft 1.7.10 Forge Mods  
**Graphics API**: OpenGL via LWJGL 2.9.3  
**Build System**: Gradle (GTNH Convention Plugin)

---

## TO BUILD
Run ./gradlew.bat compileJava and WAIT until it finishes. DO NOT be impatient and kill the process, it will take about 60 seconds, 
and DO NOT run it multiple times in parallel, it will cause out-of-memory errors. 

## Font/Text System — Start Here

For any work on the font/text framework, use the new documentation and package-local guides first.

### Canonical docs

- `docs/font/README.md` — top-level entry point
- `docs/font/architecture.md` — package ownership and boundaries
- `docs/font/pipeline-map-and-glossary.md` — end-to-end flow + terminology
- `docs/font/api-guide.md` — public API usage

### Current package map for font/text

- `api/font` — public font-domain API + layout bridge
- `api/text` — public text-domain values
- `text/layout` — internal layout algorithm
- `text/cache` — glyph supply / cache / async generation
- `text/atlas` — atlas storage
- `text/atlas/packing` — packing algorithms
- `text/msdf` — distance-field generation logic
- `text/render` — draw-time orchestration
- 
### Source package guides

- `src/main/java/io/github/somehussar/crystalgraphics/api/font/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/api/text/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/layout/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/render/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/cache/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/atlas/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/atlas/packing/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/text/msdf/AGENTS.md`

Do not rely on older font/text notes outside this set; the current docs above are the intended source of truth.

## VAO/VBO Backend — Start Here

For any work on vertex array objects, vertex buffer streaming, or the shared
vertex input registry, use the package-local guides first.

### Current package map for VAO/VBO

- `gl/vertex` — VAO wrapper, per-format vertex input bindings, shared registry
- `gl/buffer` — streaming VBO strategies (sync ring, orphan, subdata), shared quad IBO

### Source package guides

- `src/main/java/io/github/somehussar/crystalgraphics/gl/vertex/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/buffer/AGENTS.md`

### Related API types (in `api/vertex/`)

- `CgVertexFormat` — immutable, hashable vertex format descriptor (the registry key)
- `CgVertexAttribute` — single attribute within a format (name, type, components, offset, semantic metadata)
- `CgVertexSemantic` — enum of attribute roles (POSITION, UV, COLOR, NORMAL, GENERIC)
- `CgAttribType` — enum of GL primitive types with byte sizes
- `CgTextureBinding` — lightweight (target, textureId) value type for texture identity

## Batch Render Layer System — Start Here

For any work on the batched layer rendering, render state system, CPU staging,
or buffer source assembly, use the package-local guides first.

### Current package map for batch rendering

- `api/state` — immutable render state descriptors (depth, cull, texture policy, composite render state)
- `gl/render` — render layer interface, fixed/dynamic layers, batch renderer, buffer source
- `gl/buffer/staging` — CPU-side staging buffer and format-aware vertex writer
- `gl/text` — text layer factory (`CgTextLayers`) for MSDF/bitmap text layers

### Source package guides

- `src/main/java/io/github/somehussar/crystalgraphics/api/state/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/render/AGENTS.md`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/buffer/staging/AGENTS.md`

### Key architectural invariants

- **Layers own state, renderer owns upload** — `CgBatchRenderer.flush()` never
  touches GL state beyond VBO/VAO/IBO. Shader, texture, blend, depth, and cull
  are the layer's responsibility via `CgRenderState.apply()/clear()`.
- **Two batch renderer lifecycles** — The immediate `flush()` path is for
  layer-based non-UI uses. The upload-once/draw-many path (`uploadPendingVertices()`
  / `drawUploadedRange()` / `finishUploadedDraws()`) is for CrystalGUI's
  draw-list replay. See `gl/render/AGENTS.md` for details.
- **Shared VBO/VAO ownership** stays with `CgVertexArrayRegistry`/`CgVertexArrayBinding`.
  The batch renderer borrows, never creates or deletes, shared GPU resources.
- **CgBufferSource is per-context owned** — not a singleton. Each render context
  (UI, world overlay) creates and owns its own buffer source.
- **CgTextureBinding vs CgTextureState** — `CgTextureBinding` is a raw value (target + id);
  `CgTextureState` is the policy layer (unit, sampler, fixed/dynamic/none). They compose.
- **Generic text quad sink** — `CgTextQuadSink` decouples text quad
  emission from submission model. The text renderer has both layer-based and
  target-based (sink-based) internal paths. See `text/render/AGENTS.md` for details.

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
- `StandaloneCgShader`: Non-Minecraft `CgShader` impl — compiles from inline GLSL source, no MC deps. Used by the GL debug harness and any non-MC consumer.
- `StandaloneCgShaderBindings`: Non-Minecraft `CgShaderBindings` impl — deferred patch-list without MC texture manager. `sampler2D(ResourceLocation)` throws unsupported.

#### 4. VAO/VBO Backend (✅ COMPLETE)

**Vertex Input** (`gl/vertex/`):
- `CgVertexArray`: VAO wrapper with core GL30 / ARB waterfall
- `CgVertexArrayBinding`: Pairs one VAO + one stream buffer for a vertex format
- `CgVertexArrayRegistry`: Singleton cache — one binding per `CgVertexFormat`, lazy-created

**Buffer Streaming** (`gl/buffer/`):
- `CgStreamBuffer`: Abstract streaming VBO with waterfall factory (`create()`)
- `MapAndSyncStreamBuffer`: Tier A — 3-slot ring buffer with `ARB_sync` fences
- `MapAndOrphanStreamBuffer`: Tier B — orphan via `glMapBufferRange`
- `SubDataStreamBuffer`: Tier C — CPU staging + `glBufferSubData` (GL 1.5 baseline)
- `CgQuadIndexBuffer`: Shared quad IBO, `GL_UNSIGNED_SHORT`, max 16384 quads

**Streaming Strategy Waterfall**: `CgCapabilities.isArbSync()` → sync ring; `isMapBufferRangeSupported()` → orphan; else → subdata.

#### 5. Capability Detection (✅ COMPLETE)

- `CgCapabilities.detect()`: Queries LWJGL ContextCapabilities
- Waterfall selection: Core GL30 > ARB > EXT
- Reports: maxDrawBuffers, maxTextureUnits, stencil/depth support

#### 6. Integration & Testing (✅ COMPLETE)

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
│   ├── buffer/                    # VBO streaming strategies + shared quad IBO
│   │   ├── CgStreamBuffer.java            # Abstract base + waterfall factory
│   │   ├── MapAndSyncStreamBuffer.java    # Tier A: 3-slot ring + GL fence sync
│   │   ├── MapAndOrphanStreamBuffer.java  # Tier B: orphan via glMapBufferRange
│   │   ├── SubDataStreamBuffer.java       # Tier C: CPU staging + glBufferSubData
│   │   └── CgQuadIndexBuffer.java         # Shared quad IBO (GL_UNSIGNED_SHORT)
│   ├── vertex/                    # VAO wrapper + shared vertex input bindings
│   │   ├── CgVertexArray.java             # VAO create/bind/configure (core/ARB)
│   │   ├── CgVertexArrayBinding.java      # Pairs VAO + stream buffer per format
│   │   └── CgVertexArrayRegistry.java     # Singleton: format → binding cache
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

## Code Style: Lombok

**Rule: Prioritize Lombok annotations to eliminate handwritten getter/setter boilerplate in all new code.**
Lombok generates Java 8-compatible bytecode. All annotations listed above work correctly with Java 8 and LWJGL 2.9.3. No runtime dependency is added — Lombok is `compileOnly`.

### When to Use Each Annotation

| Annotation | Use When |
|---|---|
| `@Data` | Simple POJOs / value objects with all fields participating in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — when you need getters on all fields but setters on only some, or vice versa |
| `@RequiredArgsConstructor` | Immutable classes — generates constructor for all `final` fields (pairs well with `@Getter` only) |
| `@Builder` | Complex object construction with many optional parameters |
| `@Value` | Fully immutable data carriers (makes class final, all fields private final, no setters) |
| `@ToString` / `@EqualsAndHashCode` | When you need only one of these without full `@Data` |
| `@Slf4j` / `@Log` | Logger field generation (prefer `@Slf4j` if SLF4J is available) |

### Guidelines

1. **Prefer `@Data` for simple POJOs** that are pure data holders with no complex logic.
2. **Use `@Getter` + `@RequiredArgsConstructor` for immutable classes** — avoid `@Data` when you don't want setters.
3. **Use `@Builder` for classes with 4+ constructor parameters** or when many parameters are optional.
4. **Apply `@Getter`/`@Setter` at field level** when only specific fields need accessors.
5. **Do NOT use `@Data` on entities or classes with inheritance** — use explicit `@Getter`/`@Setter`/`@ToString`/`@EqualsAndHashCode` instead to control behavior.
6. **Always use `@EqualsAndHashCode(callSuper = true)`** on subclasses to avoid subtle bugs.

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
