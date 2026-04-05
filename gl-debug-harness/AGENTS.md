# GL Debug Harness — Agent Knowledge Base

**Module**: `gl-debug-harness/`  
**Purpose**: Standalone LWJGL 2 / OpenGL 3.0 debug harness for CrystalGraphics, running outside Minecraft to test font rendering, FBO pipelines, atlas generation, and shader behavior in isolation.

## Quick Start

```bash
# List all modes
./gradlew :gl-debug-harness:runHarness --args="--list"

# Run a specific scene
./gradlew :gl-debug-harness:runHarness --args="--mode=triangle"

# Run atlas dump
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"

# Run with options
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=both --font-size-px=64"

# Run a 3D scene
./gradlew :gl-debug-harness:runHarness --args="--mode=world-text-3d"

# Run a 3D scene with custom output name
./gradlew :gl-debug-harness:runHarness --args="--mode=world-text-3d --output-name=my-test"

# Run tests
./gradlew :gl-debug-harness:test

# Compile only
./gradlew :gl-debug-harness:compileJava
```

## Architecture

### Scene Registry

All scenes and diagnostic tools are registered explicitly in `SceneRegistry.createDefault()`. No reflection or annotation scanning.

Each entry has:
- **SceneDescriptor**: mode id, description, lifecycle mode, FBO/depth requirements, default dimensions, clear color
- **HarnessSceneFactory**: deferred scene instantiation

Lifecycle modes:
- `MANAGED`: Standard scenes that use shared helpers (FBO, projection, etc.)
- `FULL_OVERRIDE`: Scenes that own their entire GL lifecycle
- `DIAGNOSTIC`: Non-rendering tools that produce reports/dumps
- `INTERACTIVE`: 3D scenes with camera controls, render loop, and task scheduler

### Config Precedence

Values are applied in this order (later wins):
1. Hardcoded defaults in config class constructors
2. System properties (`-Dharness.font.path=...`)
3. CLI args (`--font-path=...`)

Config classes:
- `HarnessConfig` — base (outputDir, width, height, fontPath)
- `AtlasDumpConfig` — adds atlasType, atlasSize, fontSizePx, text, parityPrewarm, prewarmBitmap, dumpAllPages, atlasPageSize
- `TextSceneConfig` — adds text, atlasSize, fontSizePx, dumpBitmapAtlas

### Shared Helpers

| Helper | Purpose |
|---|---|
| `HarnessFontUtil` | Font path resolution (config → test font → system font) |
| `HarnessBitmapUtil` | FreeType bitmap normalization (pitch/row-order) |
| `HarnessProjectionUtil` | Orthographic matrix construction |
| `HarnessFboHelper` | FBO creation, bind/unbind, clear, capture, teardown |
| `HarnessShaderUtil` | Shader compilation and resource loading |
| `ScreenshotUtil` | PNG capture from backbuffer, FBO, or texture |
| `HarnessOutputDir` | Output directory creation |

## Maintained Scenes

| Mode ID | Output Directory | Lifecycle | Description |
|---|---|---|---|
| `triangle` | `triangle/triangle.png` | MANAGED | Basic colored triangle on backbuffer |
| `fbo-triangle` | `fbo-triangle/fbo-triangle.png` | MANAGED | Same triangle rendered to FBO |
| `atlas-dump` | `atlas-dump/atlas/atlas-dump-24px.png` + `atlas/atlas-dump-32px.png` | MANAGED | Glyph atlas dump via CgTextRenderer production pipeline |
| `text-scene` | `text-scene/text-scene.png` + `atlas/atlas-dump-<size>px.png` | MANAGED | Full text rendered via CgTextRenderer + FBO |
| `world-text-scene` | `world-text-scene/world-text-scene.png` | MANAGED | 3D world-space text — single-shot capture (always MSDF, depth-tested) |
| `world-text-3d` | `world-text-3d/{name}-normal.png`, `{name}-paused.png`, `{name}-topdown.png` | INTERACTIVE | Interactive 3D world-space text with camera controls |
| `camera-3d-validation` | `camera-3d-validation/{name}-front-view.png`, etc. (4 angles) | INTERACTIVE | 3D camera validation with cube + floor |
| `render-validation` | `render-validation/{name}-normal.png`, `{name}-paused.png`, `{name}-top-down.png` | INTERACTIVE | Render validation: floor + HUD + pause overlay |

All outputs are organized into scene-specific subdirectories under `harness-output/`. The `{name}` placeholder defaults to the scene mode ID, but can be overridden with `--output-name=PREFIX`.

## Diagnostic Tools

| Mode ID | Output | Description |
|---|---|---|
| `gl-state-dump` | `gl-state-dump.txt` | Current GL state: bindings, flags, limits, errors |
| `capability-report` | `capability-report.txt` | CgCapabilities + environment summary |

## Agent Debug Toolkit (7 Tools)

1. **ScreenshotUtil** — PNG capture API for backbuffer/FBO/texture
2. **TextureInspector** — Capture arbitrary texture ID to PNG
3. **FboInspector** — Dump FBO color attachments to PNG
4. **GlStateDumper** — Structured GL state report to file
5. **CapabilityReport** — CgCapabilities + harness environment report
6. **GlErrorChecker** — Drain and name all pending GL errors
7. **AtlasDumper** — Dump CgGlyphAtlas texture + manifest file

### Usage from Scene Code

```java
// Screenshot capture
ScreenshotUtil.captureBackbuffer(width, height, outputDir, "screenshot.png");
ScreenshotUtil.captureFboColorTexture(fboId, texId, w, h, outputDir, "fbo.png");
ScreenshotUtil.captureTexture(texId, w, h, GL30.GL_R8, outputDir, "tex.png");

// Texture inspector
TextureInspector.capture(texId, 512, 512, 0x8229, outputDir, "inspect.png");

// FBO inspector
FboInspector.dumpColorAttachment(fboId, 800, 600, outputDir, "fbo-color.png");

// GL error check
boolean hadErrors = GlErrorChecker.checkAndLog("after render pass");
List<String> errors = GlErrorChecker.drainErrors();

// Atlas dumper
AtlasDumper.dump(atlas, 1024, 0x8229, outputDir, "my-atlas");
// Produces: my-atlas.png + my-atlas-manifest.txt
```

## Scene Authoring Conventions

### Adding a New Scene

1. Create a class implementing `HarnessScene` in the harness package
2. Register it in `SceneRegistry.createDefault()` with a `SceneDescriptor`
3. If it needs a typed config, extend `HarnessConfig` and add the factory in `FontDebugHarnessMain.createConfig()`
4. Use shared helpers instead of open-coding GL setup:
   - `HarnessFboHelper.create()` for FBO lifecycle
   - `HarnessProjectionUtil.screenOrtho()` for projection matrices
   - `HarnessFontUtil.resolveFontPath()` for font discovery
   - `HarnessBitmapUtil.normalizeBitmapBuffer()` for FreeType bitmaps

### Output Conventions

- Each scene outputs to its own subdirectory: `harness-output/{sceneName}/`
- The base output directory is `config.getOutputDir()` (default: `gl-debug-harness/harness-output/`)
- Interactive scenes use the output name prefix for filenames: `{outputName}-{suffix}.png`
- The output name defaults to the scene's mode ID, overridable via `--output-name=PREFIX`
- PNG filenames must be deterministic and stable
- Non-PNG artifacts (manifests, reports) use `.txt` extension

### Testing

- Non-GL code: JUnit 4 tests in `gl-debug-harness/src/test/java/`
- GL-touching code: Verify via `runHarness` command + artifact existence check
- Never mock GL calls in unit tests

## System Properties

| Property | Default | Description |
|---|---|---|
| `harness.output.dir` | `gl-debug-harness/harness-output` | Output directory |
| `harness.font.path` | (system font) | Font file path |
| `harness.width` | `800` | Window/FBO width |
| `harness.height` | `600` | Window/FBO height |
| `harness.atlas.type` | `both` | Atlas type for atlas-dump |
| `harness.atlas.size` | `512` | Atlas texture size for atlas-dump |
| `harness.bitmap.px.size` | `24` | Bitmap atlas font size in px |
| `harness.msdf.px.size` | `32` | MSDF atlas font size in px (min: 32) |
| `harness.font.size.px` | varies | Shared font size (overrides both bitmap/msdf) |
| `harness.parity.prewarm` | `false` | Deterministic MSDF prewarm for parity testing |
| `harness.prewarm.bitmap` | `false` | Deterministic bitmap prewarm |
| `harness.dump.all.pages` | `false` | Dump all atlas pages (not just first) |
| `harness.atlas.page.size` | auto | Override per-page atlas texture dimension |

## Build Configuration

The harness is a standalone Gradle subproject (`gl-debug-harness/build.gradle.kts`) that depends on the root project for CgCapabilities, CgGlyphAtlas, and font APIs. It bundles LWJGL 2 natives and JNI bindings for freetype-harfbuzz and msdfgen.

Key tasks:
- `compileJava` — Compile all harness code
- `test` — Run JUnit 4 tests (non-GL)
- `runHarness` — Launch the standalone harness
- `extractLwjglNatives` — Extract LWJGL DLLs (auto-dependency of runHarness)

---

## 3D Interactive Harness System

The harness includes a full 3D interactive rendering system for scenes that need continuous render loops, camera controls, and timed screenshot capture.

### Components

| Component | Location | Purpose |
|---|---|---|
| `Camera3D` | `camera/Camera3D.java` | First-person camera with WASD/SPACE/SHIFT movement and mouse look |
| `FloorRenderer` | `camera/FloorRenderer.java` | Infinite ground plane at Y=0 with grid pattern |
| `HUDRenderer` | `camera/HUDRenderer.java` | On-screen position/rotation display (top-left corner) |
| `PauseScreenRenderer` | `camera/PauseScreenRenderer.java` | Semi-transparent overlay shown when paused |
| `TaskScheduler` | `scheduler/TaskScheduler.java` | Time-based callback system for automated events |
| `InteractiveSceneRunner` | `InteractiveSceneRunner.java` | Drives the render loop: init → renderFrame → cleanup |
| `HarnessDebugTools` | `debug/HarnessDebugTools.java` | Programmatic camera control + screenshot capture API |
| `WorldConfig` | `config/WorldConfig.java` | Global world settings (sky color, floor color, grid spacing) |

### Rendering Pipeline (per frame)

1. **Input**: Poll keyboard for pause toggle (ESCAPE or T)
2. **Camera update**: Process WASD/SPACE/SHIFT movement + mouse look (skipped when paused)
3. **Task scheduler tick**: Fire any callbacks whose time has elapsed
4. **GL state reset**: Ensure depth test on, blend off, depth writes on
5. **Clear**: Clear color+depth with sky color from `WorldConfig`
6. **Floor**: Render the ground plane (if `uses3DCamera()`)
7. **Scene content**: Call `scene.renderFrame(deltaTime, elapsedTime, frameNumber)`
8. **GL state reset**: Unbind shader/VAO/textures left by scene
9. **Pause overlay**: Render semi-transparent bar at bottom (if paused)
10. **HUD**: Render camera position/rotation text (if `uses3DCamera()`)
11. **Post-render callback**: Fire one-shot screenshot capture callback (if set)
12. **Swap buffers**: `Display.update()` + `Display.sync(60)`

### Camera Controls

| Key | Action |
|---|---|
| W/S | Move forward/backward |
| A/D | Strafe left/right |
| SPACE | Move up |
| SHIFT | Move down |
| Mouse | Look around (yaw/pitch) |
| ESCAPE or T | Toggle pause (releases mouse cursor) |

### HUD Display

Top-left corner shows camera state in real-time:
```
Pos: (X, Y, Z)
Rot: yaw=Y pitch=P
```

---

## Creating New Interactive Scenes

### Step 1: Implement `InteractiveHarnessScene`

```java
public class MyScene implements InteractiveHarnessScene {

    private boolean running = true;
    private Camera3D camera;
    private TaskScheduler scheduler;
    private InteractiveSceneRunner runner;
    private String outputDir;
    private String outputName;
    

    // InteractiveHarnessScene lifecycle
    @Override
    public void init(HarnessContext ctx) {
        this.camera = camera;
        this.scheduler = scheduler;
        this.outputDir = outputDir;
        this.outputName = outputName;

        // Initialize GL resources here
        // Position camera: camera.moveCamera(x, y, z)
        // Schedule timed events: scheduler.schedule(timeSeconds, id, callback)
    }

    @Override
    public void renderFrame(float deltaTime, double elapsedTime, long frameNumber) {
        // Render your scene content each frame
        // Floor, HUD, and pause overlay are handled by the runner
    }

    @Override
    public void cleanup() {
        // Delete GL resources
    }

    @Override
    public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean uses3DCamera() { return true; }
}
```

### Step 2: Register in `SceneRegistry`

```java
reg.register(
    SceneDescriptor.builder("my-scene")
        .description("My custom 3D scene")
        .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
        .category(SceneDescriptor.Category.SCENE)
        .needsFbo(false)
        .needsDepthBuffer(true)
        .build(),
    new HarnessSceneFactory() {
        public HarnessScene create() { return new MyScene(); }
    }
);
```

### Step 3: Schedule Timed Screenshots

Use `TaskScheduler` for automated validation captures:

```java
// In init():
scheduler.schedule(0.5, "setup-camera", new Runnable() {
    @Override
    public void run() {
        camera.moveCamera(0f, 3f, 8f);
        camera.setPitch(-15f);
    }
});

// Use post-render callback for accurate screenshot timing:
scheduler.schedule(0.6, "capture", new Runnable() {
    @Override
    public void run() {
        final String filename = outputName + "-my-view.png";
        runner.setPostRenderCallback(new Runnable() {
            @Override
            public void run() {
                ScreenshotUtil.captureBackbuffer(
                    runner.getCurrentWidth(), runner.getCurrentHeight(),
                    outputDir, filename);
            }
        });
    }
});

// Stop scene when done:
scheduler.schedule(1.0, "shutdown", new Runnable() {
    @Override
    public void run() { running = false; }
});
```

### Step 4: Access Global Systems

- **Camera**: Passed to `init()` — call `camera.moveCamera()`, `camera.setYaw()`, `camera.setPitch()`
- **Scheduler**: Passed to `init()` — call `scheduler.schedule(time, id, callback)`
- **World settings**: `WorldConfig.get()` — sky color, floor color, grid spacing
- **Debug tools**: Create via `new HarnessDebugTools(camera, outputDir, outputName, w, h)`
- **Screenshots**: `ScreenshotUtil.captureBackbuffer(w, h, dir, filename)` or use `HarnessDebugTools.screenshot()`

---

## Console Arguments for Harness

### Output Organization

All scene outputs are organized into scene-specific subdirectories:

```
harness-output/
├── triangle/
│   └── triangle.png
├── atlas-dump/
│   └── atlas/
│       ├── bitmap-atlas-dump-24px.png
│       └── msdf-atlas-dump-32px.png
├── world-text-3d/
│   ├── world-text-3d-normal.png
│   ├── world-text-3d-paused.png
│   └── world-text-3d-topdown.png
├── camera-3d-validation/
│   ├── camera-3d-validation-front-view.png
│   ├── camera-3d-validation-side-view.png
│   ├── camera-3d-validation-top-down-view.png
│   └── camera-3d-validation-diagonal-view.png
└── render-validation/
    ├── render-validation-normal.png
    ├── render-validation-paused.png
    └── render-validation-top-down.png
```

### Custom Output Name Prefix

Use `--output-name=PREFIX` to customize output filenames:

```bash
# Default: scene name used as prefix
./gradlew :gl-debug-harness:runHarness --args="--mode=world-text-3d"
# → harness-output/world-text-3d/world-text-3d-normal.png
# → harness-output/world-text-3d/world-text-3d-paused.png
# → harness-output/world-text-3d/world-text-3d-topdown.png

# Custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=world-text-3d --output-name=test1"
# → harness-output/world-text-3d/test1-normal.png
# → harness-output/world-text-3d/test1-paused.png
# → harness-output/world-text-3d/test1-topdown.png

# Camera validation with custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=camera-3d-validation --output-name=gpu-test"
# → harness-output/camera-3d-validation/gpu-test-front-view.png
# → harness-output/camera-3d-validation/gpu-test-side-view.png
# → harness-output/camera-3d-validation/gpu-test-top-down-view.png
# → harness-output/camera-3d-validation/gpu-test-diagonal-view.png
```

### All Available Scenes

| Mode ID | Type | Description |
|---|---|---|
| `triangle` | MANAGED | Basic colored triangle |
| `atlas-dump` | MANAGED | Glyph atlas texture dump |
| `text-scene` | MANAGED | Full text rendering via CgTextRenderer |
| `world-text-scene` | MANAGED | 3D world-space text (single-shot) |
| `world-text-3d` | INTERACTIVE | Interactive 3D world-space text with camera |
| `camera-3d-validation` | INTERACTIVE | Camera validation: cube from 4 angles |
| `render-validation` | INTERACTIVE | Render pipeline validation: floor + HUD + pause |
| `gl-state-dump` | DIAGNOSTIC | GL state dump to text file |
| `capability-report` | DIAGNOSTIC | CgCapabilities + environment report |

### Common CLI Arguments

| Argument | Default | Description |
|---|---|---|
| `--mode=<id>` | (required) | Scene to run |
| `--output-dir=<dir>` | `gl-debug-harness/harness-output` | Base output directory |
| `--output-name=<prefix>` | scene mode ID | Custom filename prefix for outputs |
| `--font-path=<path>` | system font | Font file path |
| `--width=<n>` | 800 | Window/FBO width |
| `--height=<n>` | 600 | Window/FBO height |
| `--list` | — | List all available modes |
| `--help` | — | Show help with all options |
