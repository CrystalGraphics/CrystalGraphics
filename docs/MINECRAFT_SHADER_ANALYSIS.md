# Minecraft 1.7.10 Shader Analysis

**Analysis Date**: 2026-02-18  
**Source Location**: `build/rfg/minecraft-src/java/net/minecraft/`  
**Purpose**: Understand vanilla Minecraft 1.7.10 shader implementation for CrystalGraphics integration

---

## Executive Summary

Minecraft 1.7.10 introduced a JSON-based shader system for post-processing effects (the "Super Secret Settings"). The shader architecture consists of:

1. **ShaderGroup** - Orchestrates multiple shader passes with FBO chains
2. **Shader** - Represents a single shader pass (input → output FBO)
3. **ShaderManager** - Low-level GLSL program management
4. **OpenGlHelper** - Extension detection and GL abstraction

The system supports both Core GL20 and ARB shader extensions, using the same waterfall pattern as FBOs.

---

## 1. Shader Architecture Overview

### File Locations
- `net/minecraft/client/shader/ShaderGroup.java` - Post-processing pipeline
- `net/minecraft/client/shader/Shader.java` - Single pass definition
- `net/minecraft/client/shader/ShaderManager.java` - GLSL program management
- `net/minecraft/client/shader/ShaderUniform.java` - Uniform variable handling
- `net/minecraft/client/renderer/OpenGlHelper.java` - GL abstraction

### Relationship Diagram
```
EntityRenderer
    └── theShaderGroup (ShaderGroup)
            ├── mainFramebuffer (Framebuffer)
            ├── List<Shader> (shaderPasses)
            └── Map<String, Framebuffer> (mapFramebuffers)
                    
Shader (single pass)
    ├── manager (ShaderManager) - GLSL program
    ├── framebufferIn (Framebuffer)
    ├── framebufferOut (Framebuffer)
    └── listAuxFramebuffers (intermediate targets)
            
ShaderManager
    ├── program (GL program ID)
    ├── vertexShader (GL shader ID)
    ├── fragmentShader (GL shader ID)
    └── listUniforms (ShaderUniforms)
```

---

## 2. ShaderGroup: Post-Processing Pipeline

**File**: `net/minecraft/client/shader/ShaderGroup.java`

### Purpose
Manages a chain of shader passes for post-processing effects. Parses JSON definitions and orchestrates FBO/Shader bindings.

### JSON Format
Shader definitions are stored in `assets/minecraft/shaders/program/*.json`:

```json
{
    "targets": [
        "swap",
        "previous"
    ],
    "passes": [
        {
            "name": "blur",
            "intarget": "minecraft:main",
            "outtarget": "swap",
            "auxtargets": [
                {
                    "name": "PrevSampler",
                    "id": "previous"
                }
            ],
            "uniforms": [
                {
                    "name": "Radius",
                    "values": [10.0]
                }
            ]
        }
    ]
}
```

### Key Fields
```java
private final Framebuffer mainFramebuffer;           // Screen/main output
private final List<Shader> listShaders;             // Shader passes in order
private final Map<String, Framebuffer> mapFramebuffers;  // Named FBO targets
```

### Initialization Flow
**File**: `ShaderGroup.java`  
**Method**: Constructor (Line ~85)

1. **Parse JSON** - Load shader definition
2. **Create Framebuffers** - `addFramebuffer()` for each named target
3. **Create Shaders** - `addShader()` for each pass
4. **Link Samplers** - Connect `auxtargets` to shader uniforms

### Render Execution
**File**: `ShaderGroup.java`  
**Method**: `loadShaderGroup(float partialTicks)` (Line ~158)

```java
public void loadShaderGroup(float partialTicks) {
    // Update dynamic uniforms (time, etc.)
    int i = 0;
    for (Shader shader : this.listShaders) {
        // Determine input FBO
        Framebuffer framebufferIn = shader.getFramebufferRaw();
        if (framebufferIn == null) {
            framebufferIn = this.mainFramebuffer;  // Default to screen
        }
        
        // Determine output FBO
        Framebuffer framebufferOut = shader.getFramebuffer();
        if (framebufferOut == null) {
            framebufferOut = this.mainFramebuffer;  // Default to screen
        }
        
        // Bind input texture to sampler
        shader.addSamplerTexture("DiffuseSampler", framebufferIn);
        
        // Execute shader pass
        shader.loadShader(partialTicks);
        
        // Copy output to auxiliary targets
        framebufferOut.framebufferClear();
        framebufferOut.bindFramebuffer(false);
        shader.getShaderManager().useShader();  // Bind program
        // ... render full-screen quad ...
        shader.getShaderManager().endShader();   // Unbind program
        framebufferOut.unbindFramebuffer();
    }
}
```

### Key Methods

#### Add Framebuffer Target
```java
private void addFramebuffer(String name, int width, int height) {
    Framebuffer framebuffer = new Framebuffer(width, height, true);  // useDepth=true
    framebuffer.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
    this.mapFramebuffers.put(name, framebuffer);
    
    if (name.equals("minecraft:main")) {
        this.mainFramebuffer = framebuffer;
    }
}
```

#### Add Shader Pass
```java
private void addShader(String name, Framebuffer in, Framebuffer out) {
    Shader shader = new Shader(this.resourceManager, name, in, out);
    this.listShaders.add(this.listShaders.size(), shader);
}
```

---

## 3. Shader: Single Pass Definition

**File**: `net/minecraft/client/shader/Shader.java`

### Purpose
Encapsulates a single shader program with its inputs, outputs, and uniform configuration.

### Key Fields
```java
private final ShaderManager manager;              // GLSL program
private final Framebuffer framebufferIn;          // Input FBO (null = screen)
private final Framebuffer framebufferOut;         // Output FBO (null = screen)
private final List<Object> listAuxFramebuffers;   // Auxiliary sampler targets
private final List<String> listAuxFramebufferNames;
```

### Key Methods

#### Load Shader (Execute Pass)
**File**: `Shader.java`  
**Method**: `loadShader(float partialTicks)` (Line ~112)

```java
public void loadShader(float partialTicks) {
    // Clear output FBO
    this.framebufferOut.framebufferClear();
    
    // Bind output FBO for rendering
    this.framebufferOut.bindFramebuffer(false);
    
    // Bind input texture to sampler slot 0
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.framebufferIn.framebufferTexture);
    
    // Set uniform values
    for (ShaderUniform uniform : this.manager.getShaderUniforms()) {
        uniform.setUniform();
    }
    
    // Activate and use shader program
    this.manager.useShader();
    
    // Render full-screen quad
    // (via Tessellator - draws quad with texture coords)
    
    // Deactivate shader
    this.manager.endShader();
    
    // Unbind output FBO
    this.framebufferOut.unbindFramebuffer();
    
    // Copy result to auxiliary targets if needed
    this.framebufferIn.unbindFramebuffer();
    for (Object aux : this.listAuxFramebuffers) {
        // Copy framebufferOut to aux target
    }
}
```

#### Add Sampler
```java
public void addSamplerTexture(String name, Framebuffer framebuffer) {
    // Add framebuffer texture as named sampler
    this.listAuxFramebuffers.add(framebuffer);
    this.listAuxFramebufferNames.add(name);
}
```

---

## 4. ShaderManager: GLSL Program Management

**File**: `net/minecraft/client/shader/ShaderManager.java`

### Purpose
Low-level management of GLSL shader programs: compilation, linking, uniform handling, and activation.

### Extension Detection
Vanilla uses `OpenGlHelper.field_153214_y` to select shader path:
- `false` = Core GL20 (`glCreateShader`, `glShaderSource`, etc.)
- `true` = ARB Extension (`glCreateShaderObjectARB`, etc.)

### Key Fields
```java
private final int program;              // GL program ID
private final int vertexShader;         // Vertex shader ID
private final int fragmentShader;       // Fragment shader ID
private final List<ShaderUniform> shaderUniforms;  // Uniform variables
private final Map<String, ShaderUniform> mapShaderUniforms;  // Name lookup
private int programRef;                   // Currently active program (GL state tracking)
```

### Program Creation Flow

#### Step 1: Load Shader Source
**Method**: Constructor (Line ~55)
```java
public ShaderManager(IResourceManager resourceManager, 
                     String vertexShaderFile, 
                     String fragmentShaderFile) {
    // Load GLSL source from assets/minecraft/shaders/program/
    String vertexSource = this.loadShader(resourceManager, vertexShaderFile);
    String fragmentSource = this.loadShader(resourceManager, fragmentShaderFile);
    
    // Compile shaders
    this.vertexShader = this.createShader(vertexSource, GL20.GL_VERTEX_SHADER);
    this.fragmentShader = this.createShader(fragmentSource, GL20.GL_FRAGMENT_SHADER);
    
    // Link program
    this.program = this.linkProgram();
    
    // Discover uniforms
    this.setupUniforms();
}
```

#### Step 2: Create Shader Object
**Method**: `createShader(String source, int type)` (Line ~145)
```java
private int createShader(String source, int type) {
    int shader;
    
    // Create shader object (Core vs ARB)
    if (OpenGlHelper.field_153214_y) {
        // ARB Extension path
        shader = ARBShaderObjects.glCreateShaderObjectARB(type);
    } else {
        // Core GL20 path
        shader = GL20.glCreateShader(type);
    }
    
    if (shader == 0) {
        throw new RuntimeException("Failed to create shader");
    }
    
    // Upload source
    if (OpenGlHelper.field_153214_y) {
        ARBShaderObjects.glShaderSourceARB(shader, source);
    } else {
        GL20.glShaderSource(shader, source);
    }
    
    // Compile
    if (OpenGlHelper.field_153214_y) {
        ARBShaderObjects.glCompileShaderARB(shader);
        if (ARBShaderObjects.glGetObjectParameteriARB(shader, 
            ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) == GL11.GL_FALSE) {
            throw new RuntimeException("Shader compilation failed: " + 
                ARBShaderObjects.glGetInfoLogARB(shader, 32768));
        }
    } else {
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Shader compilation failed: " + 
                GL20.glGetShaderInfoLog(shader, 32768));
        }
    }
    
    return shader;
}
```

#### Step 3: Link Program
**Method**: `linkProgram()` (Line ~175)
```java
private int linkProgram() {
    int program;
    
    // Create program (Core vs ARB)
    if (OpenGlHelper.field_153214_y) {
        program = ARBShaderObjects.glCreateProgramObjectARB();
    } else {
        program = GL20.glCreateProgram();
    }
    
    if (program == 0) {
        throw new RuntimeException("Failed to create program");
    }
    
    // Attach shaders
    if (OpenGlHelper.field_153214_y) {
        ARBShaderObjects.glAttachObjectARB(program, this.vertexShader);
        ARBShaderObjects.glAttachObjectARB(program, this.fragmentShader);
        ARBShaderObjects.glLinkProgramARB(program);
        
        if (ARBShaderObjects.glGetObjectParameteriARB(program, 
            ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB) == GL11.GL_FALSE) {
            throw new RuntimeException("Program linking failed: " + 
                ARBShaderObjects.glGetInfoLogARB(program, 32768));
        }
    } else {
        GL20.glAttachShader(program, this.vertexShader);
        GL20.glAttachShader(program, this.fragmentShader);
        GL20.glLinkProgram(program);
        
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Program linking failed: " + 
                GL20.glGetProgramInfoLog(program, 32768));
        }
    }
    
    return program;
}
```

### Shader Activation

#### Use Shader (Bind Program)
**Method**: `useShader()` (Line ~210)
```java
public void useShader() {
    // Optimization: Only bind if not already active
    if (this.programRef != this.program) {
        if (OpenGlHelper.field_153214_y) {
            ARBShaderObjects.glUseProgramObjectARB(this.program);
        } else {
            GL20.glUseProgram(this.program);
        }
        this.programRef = this.program;
    }
    
    // Upload all uniform values
    for (ShaderUniform uniform : this.shaderUniforms) {
        uniform.setUniform();
    }
}
```

#### End Shader (Unbind Program)
**Method**: `endShader()` (Line ~225)
```java
public void endShader() {
    // Don't unbind to 0 - vanilla keeps last shader active for optimization
    // This is a potential state tracking issue!
}
```

### Uniform Management

**File**: `net/minecraft/client/shader/ShaderUniform.java`

Vanilla discovers uniforms automatically from the shader source:

```java
private void setupUniforms() {
    int uniformCount = GL20.glGetProgrami(this.program, GL20.GL_ACTIVE_UNIFORMS);
    
    for (int i = 0; i < uniformCount; i++) {
        String name = GL20.glGetActiveUniform(this.program, i, 256);
        int location = GL20.glGetUniformLocation(this.program, name);
        int type = GL20.glGetActiveUniformType(this.program, i);
        
        ShaderUniform uniform = new ShaderUniform(name, type, location, this);
        this.shaderUniforms.add(uniform);
        this.mapShaderUniforms.put(name, uniform);
    }
}
```

---

## 5. Shader Extension Detection in OpenGlHelper

**File**: `net/minecraft/client/renderer/OpenGlHelper.java`

### Detection Logic

```java
ContextCapabilities contextcapabilities = GLContext.getCapabilities();

// Basic shader support (vertex + fragment)
field_153213_x = contextcapabilities.OpenGL21 ||
                 (contextcapabilities.GL_ARB_vertex_shader && 
                  contextcapabilities.GL_ARB_fragment_shader && 
                  contextcapabilities.GL_ARB_shader_objects);

// Select implementation path
if (field_153213_x) {
    if (contextcapabilities.OpenGL21) {
        field_153214_y = false;  // Use Core GL20
    } else {
        field_153214_y = true;   // Use ARB Extension
    }
}

// Shaders require FBOs!
shadersSupported = framebufferSupported && field_153213_x;
```

### Path Selection

| Field | Value | Meaning |
|-------|-------|---------|
| `field_153213_x` | true | Shader support available |
| `field_153214_y` | false | Core GL20 path (glUseProgram) |
| `field_153214_y` | true | ARB Extension path (glUseProgramObjectARB) |

### GL Wrapper Methods

#### Use Program
```java
public static void func_153161_d(int program) {
    if (field_153214_y) {
        ARBShaderObjects.glUseProgramObjectARB(program);
    } else {
        GL20.glUseProgram(program);
    }
}
```

#### Uniform Setters
```java
public static void func_153163_f(int location, int value) {  // glUniform1i
    if (field_153214_y) {
        ARBShaderObjects.glUniform1iARB(location, value);
    } else {
        GL20.glUniform1i(location, value);
    }
}

public static void func_153168_a(int location, float value) {  // glUniform1f
    if (field_153214_y) {
        ARBShaderObjects.glUniform1fARB(location, value);
    } else {
        GL20.glUniform1f(location, value);
    }
}

// ... etc for vec2, vec3, vec4, mat4
```

---

## 6. Vanilla Shader Effects

Minecraft 1.7.10 includes 22 built-in post-processing shaders:

### Effect List
```java
private static final String[] shaderResourceLocations = new String[] {
    "shaders/post/notch.json",
    "shaders/post/fxaa.json",
    "shaders/post/art.json",
    "shaders/post/bumpy.json",
    "shaders/post/blobs2.json",
    "shaders/post/pencil.json",
    "shaders/post/colorconvolve.json",
    "shaders/post/deconverge.json",
    "shaders/post/flip.json",
    "shaders/post/invert.json",
    "shaders/post/ntsc.json",
    "shaders/post/outline.json",
    "shaders/post/phosphor.json",
    "shaders/post/scan_pincushion.json",
    "shaders/post/sobel.json",
    "shaders/post/bits.json",
    "shaders/post/desaturate.json",
    "shaders/post/bloom.json",
    "shaders/post/blur.json",
    "shaders/post/wobble.json",
    "shaders/post/antialias.json",
    "shaders/post/creeper.json",
    "shaders/post/spider.json"
};
```

### Activation
**File**: `net/minecraft/client/renderer/EntityRenderer.java`  
**Method**: `activateNextShader()` (Line ~1103)

The "Super Secret Settings" key cycles through these shaders.

---

## 7. EntityRenderer Integration

**File**: `net/minecraft/client/renderer/EntityRenderer.java`

### Shader Activation Flow

#### Load Shader Group
**Method**: `loadShader(String resourceLocation)` (Line ~1085)

```java
public void loadShader(String resourceLocation) {
    if (OpenGlHelper.isFramebufferEnabled()) {
        // Cleanup existing shader
        if (this.theShaderGroup != null) {
            this.theShaderGroup.deleteShaderGroup();
        }
        
        try {
            // Load new shader group
            this.theShaderGroup = new ShaderGroup(
                this.mc.getTextureManager(), 
                this.mc.getResourceManager(), 
                this.mc.getFramebuffer(), 
                resourceLocation
            );
            
            // Resize to match window
            this.theShaderGroup.createBindFramebuffers(
                this.mc.displayWidth, 
                this.mc.displayHeight
            );
        } catch (Exception e) {
            logger.warn("Failed to load shader: " + resourceLocation, e);
            this.theShaderGroup = null;
        }
    }
}
```

#### Update Shader Group (Per Frame)
**Method**: `updateShaderGroupSize(int width, int height)` (Line ~1097)

Called when window resizes to update all FBO targets:
```java
public void updateShaderGroupSize(int width, int height) {
    if (this.theShaderGroup != null) {
        this.theShaderGroup.createBindFramebuffers(width, height);
    }
}
```

#### Render with Shader
**Method**: `updateCameraAndRender(float partialTicks)` (Line ~1067)

```java
// Render world to main FBO
this.renderWorld(partialTicks, this.getRenderedWorldTime());

// Apply shader post-processing
if (this.theShaderGroup != null && this.mc.gameSettings.shaderActive) {
    GlStateManager.matrixMode(GL11.GL_PROJECTION);
    GlStateManager.pushMatrix();
    GlStateManager.loadIdentity();
    GlStateManager.ortho(0.0D, 
        this.mc.displayWidth, 
        this.mc.displayHeight, 
        0.0D, 
        1000.0D, 
        3000.0D
    );
    GlStateManager.matrixMode(GL11.GL_MODELVIEW);
    GlStateManager.pushMatrix();
    GlStateManager.loadIdentity();
    GlStateManager.translate(0.0F, 0.0F, -2000.0F);
    GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    GlStateManager.enableTexture2D();
    GlStateManager.disableLighting();
    GlStateManager.disableAlpha();
    
    // Execute all shader passes
    this.theShaderGroup.loadShaderGroup(partialTicks);
    
    GlStateManager.popMatrix();
    GlStateManager.matrixMode(GL11.GL_PROJECTION);
    GlStateManager.popMatrix();
}
```

---

## 8. Critical Implementation Notes for CrystalGraphics

### 1. Shader-FBO Dependency
In vanilla 1.7.10:
```java
shadersSupported = framebufferSupported && shaderCapabilitiesAvailable;
```
**You cannot have shaders without FBOs.** CrystalGraphics should maintain this dependency.

### 2. Program State Tracking
Vanilla uses `programRef` in `ShaderManager` to avoid redundant `glUseProgram` calls:
```java
private int programRef;  // Currently active program

public void useShader() {
    if (this.programRef != this.program) {
        // Only bind if different
        GL20.glUseProgram(this.program);
        this.programRef = this.program;
    }
}
```

**Problem**: This tracks state per-`ShaderManager`, not globally. Multiple managers could have different views of "current program."

**CrystalGraphics Solution**: Add global program tracking in `RenderSystem`:
```java
public class RenderSystem {
    private static int currentShaderProgram = 0;
    
    public static void bindShader(int program) {
        if (currentShaderProgram != program) {
            // Use OpenGlHelper wrapper or raw GL based on path
            currentShaderProgram = program;
        }
    }
}
```

### 3. EXT Suffix Not Used for Shaders
Unlike FBOs (which have `EXTFramebufferObject.glBindFramebufferEXT`), shaders in vanilla only use:
- Core GL20: `GL20.glUseProgram`
- ARB Extension: `ARBShaderObjects.glUseProgramObjectARB`

There is no `EXT` shader path in vanilla 1.7.10. CrystalGraphics could add EXT shader support for GL1.5-era hardware, but it's not necessary for vanilla compatibility.

### 4. Uniform Auto-Discovery
Vanilla parses active uniforms from linked programs at runtime:
```java
int uniformCount = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
for (int i = 0; i < uniformCount; i++) {
    String name = GL20.glGetActiveUniform(program, i, maxLength);
    // Create ShaderUniform
}
```

CrystalGraphics should provide similar automatic uniform discovery or allow manual uniform specification.

### 5. JSON-Based Configuration
Vanilla's shader system is data-driven via JSON files. This is powerful for vanilla's built-in effects but may not suit all mod use cases.

**CrystalGraphics Options**:
- Support JSON configuration for "rendering approaches" (mid-priority TODO)
- Provide programmatic shader creation API (more flexible)
- Support both (best of both worlds)

### 6. Texture Sampler Binding
Vanilla binds FBO textures to sampler slots manually:
```java
GL13.glActiveTexture(GL13.GL_TEXTURE0 + samplerSlot);
GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
```

CrystalGraphics should abstract this, perhaps with:
```java
public void bindSampler(String name, AbstractFramebuffer source, int slot) {
    // Activate texture unit
    // Bind source FBO's texture
    // Set uniform location to slot
}
```

---

## 9. Integration Points for CrystalGraphics

### Where to Hook

1. **ShaderManager.useShader()**: Track current shader program
   - Mixin to update CrystalGraphics state
   - Prevent redundant bindings

2. **ShaderGroup.loadShaderGroup()**: Intercept post-processing
   - Inject custom shader passes
   - Replace vanilla shaders with enhanced versions

3. **EntityRenderer.loadShader()**: Shader loading lifecycle
   - Hook to add custom shader groups
   - Monitor shader activation/deactivation

### Recommended Architecture

```java
// CrystalGraphics shader abstraction
public abstract class AbstractShaderProgram {
    protected final int programId;
    protected final Map<String, ShaderUniform> uniforms;
    
    public abstract void bind();
    public abstract void unbind();
    public abstract void setUniform(String name, float... values);
    public abstract void setSampler(String name, AbstractFramebuffer source, int slot);
}

// Implementation using vanilla-compatible paths
public class CoreShaderProgram extends AbstractShaderProgram {
    @Override
    public void bind() {
        if (OpenGlHelper.field_153214_y) {
            ARBShaderObjects.glUseProgramObjectARB(programId);
        } else {
            GL20.glUseProgram(programId);
        }
        // Update tracking
    }
}
```

---

## 10. Summary

### What Vanilla Does
1. **Two-tier shader waterfall**: Core GL20 → ARB Extension
2. **JSON-driven configuration**: ShaderGroup from JSON files
3. **Automatic uniform discovery**: Parse uniforms from linked programs
4. **FBO-based post-processing**: Chain of input → shader → output passes
5. **State optimization**: Avoid redundant `glUseProgram` calls

### What CrystalGraphics Should Provide
1. **Same waterfall pattern** for compatibility
2. **Programmatic API**: Not just JSON-driven
3. **Better state tracking**: Global current shader tracking
4. **Sampler abstraction**: Easy texture/FBO binding to uniforms
5. **Integration with FBO abstraction**: Seamless FBO ↔ Shader workflow

### Files to Reference
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderGroup.java`
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/Shader.java`
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderManager.java`
- `build/rfg/minecraft-src/java/net/minecraft/client/shader/ShaderUniform.java`
- `build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java`
- `build/rfg/minecraft-src/java/net/minecraft/client/renderer/OpenGlHelper.java`
