# GL Debug Harness — Agent Knowledge Base

**Module**: `gl-debug-harness/`  
**Purpose**: Standalone LWJGL 2 / OpenGL 3.0 debug harness for CrystalGraphics, running outside Minecraft to test font rendering, FBO pipelines, atlas generation, and shader behavior in isolation.

## Quick Start

```bash
# List all modes
./gradlew :gl-debug-harness:runHarness --args="--list"

# Run a specific scene
./gradlew :gl-debug-harness:runHarness --args="--mode=triangle-2D"

# Run atlas dump
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"

# Run with options
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=mtsdf --font-size-px=128"

# Run a 3D scene
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"

# Run a 3D scene with custom output name
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d --output-name=my-test"

# Run tests
./gradlew :gl-debug-harness:test

# Compile only
./gradlew :gl-debug-harness:compileJava
```

---

## Architecture Overview

The harness is split into two execution strategies that share a common scene lifecycle:

- **Managed runtime**: single-shot scenes that call `init -> render (once) -> dispose`. Used for FBO captures, atlas dumps, and diagnostic tools.
- **Interactive runtime**: continuous render-loop scenes with camera, overlays, and timed capture. Calls `init -> render (loop) -> dispose`.

Both runtimes use the same `HarnessSceneLifecycle` interface. Interactive scenes extend it via `InteractiveSceneLifecycle` to add loop-control hooks (`isRunning()`, `uses3DCamera()`, `shouldShutdownOnComplete()`).

Scenes should implement `HarnessSceneLifecycle` (managed) or `InteractiveSceneLifecycle` (interactive) directly.

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

### Scene Lifecycle Contract

The unified lifecycle (`HarnessSceneLifecycle`) defines four hooks:

```java
public interface HarnessSceneLifecycle {
    void init(HarnessContext ctx);                // One-time GL resource setup
    void render(HarnessContext ctx, FrameInfo frame); // Render one frame (once for managed, per-frame for interactive)
    default void onResize(int width, int height) {} // Optional display resize notification
    void dispose();                                // Release GL resources
}
```

Interactive scenes extend this with loop-control hooks:

```java
public interface InteractiveSceneLifecycle extends HarnessSceneLifecycle {
    boolean isRunning();              // Return false to exit the render loop
    boolean uses3DCamera();           // True enables camera, floor, HUD
    boolean shouldShutdownOnComplete(); // True exits the program when done
}
```

`FrameInfo` is an immutable snapshot of per-frame timing: delta time, elapsed time, and frame number. For managed scenes, the runtime passes `FrameInfo.SINGLE_FRAME` (zero timing, frame 1).

### Config Resolution

Typed scene configuration is resolved once before scene execution. Scenes read their config from `ctx.getSceneConfig()` rather than parsing raw CLI args.

Resolution order (later wins):
1. Hardcoded defaults in config class constructors
2. System properties (`-Dharness.font.path=...`)
3. CLI args (`--font-path=...`)

Config classes:
- `HarnessConfig` — base (outputDir, width, height, fontPath, outputName)
- `AtlasDumpConfig` — adds atlasType, atlasSize, fontSizePx, text, parityPrewarm, prewarmBitmap, dumpAllPages, atlasPageSize
- `TextSceneConfig` — adds text, atlasSize, fontSizePx, dumpBitmapAtlas

### Typed Context Model

`HarnessContext` is the composition root. It provides typed sub-objects instead of loose mutable fields:

| Accessor | Type | Available For | Purpose |
|---|---|---|---|
| `ctx.getViewport()` | `ViewportState` | All scenes | Live viewport dimensions, updated on resize |
| `ctx.getOutputSettings()` | `OutputSettings` | All scenes | Immutable output directory + name prefix |
| `ctx.getSceneConfig()` | `HarnessConfig` (or subclass) | All scenes | Typed scene config resolved from CLI/sysprops |
| `ctx.getWorldSettings()` | `WorldSettings` | Interactive scenes | Immutable sky/floor colors resolved once per run |
| `ctx.getRuntimeServices()` | `RuntimeServices` | Interactive scenes | Typed access to runner, pause, post-render callbacks |
| `ctx.getArtifactService()` | `ArtifactService` | Interactive scenes | Framework-owned capture with semantic suffix API |
| `ctx.getCamera3D()` | `Camera3D` | Interactive scenes | Shared 3D camera |
| `ctx.getTaskScheduler()` | `TaskScheduler` | Interactive scenes | Time-based callback system |

Legacy convenience accessors like `ctx.getScreenWidth()` and `ctx.getOutputDir()` still work, but new code should use the typed accessors directly.

### World Settings

`WorldSettings` is an immutable per-run snapshot of world rendering parameters (sky color, floor color, floor half-size). It replaces direct calls to the mutable `WorldConfig` singleton during rendering.

World settings are resolved once at startup via `WorldSettings.resolveFromDefaults()` and frozen in the context. No rendering code should call `WorldConfig.get()` after this point.

```java
// How it works internally (handled by FontDebugHarnessMain):
ctx.setWorldSettings(WorldSettings.resolveFromDefaults());

// Scenes and renderers read from the frozen snapshot:
WorldSettings ws = ctx.getWorldSettings();
float skyR = ws.getSkyR();
```

### Artifact Capture Service

The `ArtifactService` centralizes all interactive screenshot capture. Scenes request captures by semantic suffix, and the service handles filename composition, viewport dimension lookup, and post-render callback scheduling.

```java
// In scene code:
ArtifactService artifacts = ctx.getArtifactService();

// Request a post-render capture (fires after full frame: scene + floor + HUD + pause):
artifacts.requestCapture("front-view");
// Writes: {outputDir}/{outputName}-front-view.png

// Immediate FBO capture (not post-render):
artifacts.captureFbo(fboId, texId, width, height, "fbo-dump");

// Immediate backbuffer capture (use only when you know the content is ready):
artifacts.captureNow("snapshot");
```

The service reads viewport dimensions live from `ViewportState` at capture time, so it never uses stale cached values.

---

## Shared Helpers

| Helper | Location | Purpose |
|---|---|---|
| `HarnessFontUtil` | `util/` | Font path resolution (config -> test font -> system font) |
| `HarnessBitmapUtil` | `util/` | FreeType bitmap normalization (pitch/row-order) |
| `HarnessProjectionUtil` | `util/` | Orthographic + perspective projection. Owns shared constants: `FOV_DEGREES`, `NEAR_PLANE`, `FAR_PLANE` |
| `HarnessFboHelper` | `util/` | FBO creation, bind/unbind, clear, capture, teardown |
| `HarnessShaderUtil` | `util/` | Shader compilation and resource loading |
| `ScreenshotUtil` | `util/` | PNG capture from backbuffer, FBO, or texture |
| `HarnessOutputDir` | `util/` | Output directory creation |
| `GlStateResetHelper` | `util/` | Canonical post-scene GL state reset (unbind shaders, VAOs, textures, restore depth/blend) |
| `RenderPassState` | `util/` | Per-pass GL state boundary helpers (world, scene, overlay, capture point) |
| `ValidationCubeHelper` | `util/` | Shared colored-cube geometry + shader for validation scenes |
| `ValidationChoreographer` | `validation/` | Scripted camera/pause/capture sequence coordinator |

### GL State Management

Scenes don't manage GL state cleanup themselves. The harness provides two mechanisms:

1. **`GlStateResetHelper.resetAfterScene()`**: Called by the runtime after every scene `render()` call. Unbinds shaders, VAOs, VBOs, textures; restores depth test; disables blend and cull face; unbinds FBOs.

2. **`RenderPassState`**: Explicit state boundary helpers for each pipeline pass. Each pass declares the state it needs via `beginXxxPass()` rather than relying on folklore cleanup.

```
Pipeline order:
  beginWorldPass()    -> floor rendering (depth ON, blend OFF)
  beginScenePass()    -> scene content (safe defaults; scene may modify)
  resetAfterScene()   -> clean up scene leftovers
  beginOverlayPass()  -> pause + HUD (depth OFF, blend ON with alpha)
  endOverlayPass()    -> restore prior state
  beginCapturePoint() -> post-render callback fires for screenshots
```

---

## Maintained Scenes

| Mode ID                | Output Directory                                                                 | Lifecycle | Description |
|------------------------|----------------------------------------------------------------------------------|---|---|
| `triangle-2d`          | `triangle-2d/triangle.png`                                                       | MANAGED | Basic colored triangle on backbuffer |
| `text-2d`              | `text-2d/text-scene.png` + `atlas/atlas-dump-<size>px.png`                       | MANAGED | Full text rendered via CgTextRenderer + FBO |
| `text-3d`              | `text-3d/{name}-normal.png`, `{name}-paused.png`, `{name}-topdown.png`           | INTERACTIVE | Interactive 3D world-space text with camera controls. Implemented by `TextScene3D`. |
| `camera-3d` | `camera-3d/{name}-front-view.png`, etc. (4 angles)                     | INTERACTIVE | 3D camera validation with cube + floor |
| `atlas-dump`           | `atlas-dump/atlas/atlas-dump-24px.png` + `atlas/atlas-dump-32px.png`             | MANAGED | Glyph atlas dump via CgTextRenderer production pipeline |


All outputs go into scene-specific subdirectories under `harness-output/`. The `{name}` placeholder defaults to the scene mode ID, overridable with `--output-name=PREFIX`.

## Diagnostic Tools

| Mode ID | Output | Description |
|---|---|---|
| `gl-state-dump` | `gl-state-dump.txt` | Current GL state: bindings, flags, limits, errors |
| `capability-report` | `capability-report.txt` | CgCapabilities + environment summary |

---

## 3D Interactive Runtime System

The interactive runtime decomposes into focused services instead of one monolithic runner:

| Component | Location | Purpose |
|---|---|---|
| `InteractiveSceneRunner` | `InteractiveSceneRunner.java` | Slim loop coordinator: sequences services, drives render loop |
| `FrameClock` | `runtime/FrameClock.java` | High-resolution frame timing (delta, elapsed, frame number) |
| `InputPauseHandler` | `runtime/InputPauseHandler.java` | Keyboard pause toggle (ESC/T) + mouse grab management |
| `ResizeHandler` | `runtime/ResizeHandler.java` | Display resize detection + propagation to viewport, GL, and renderers |
| `OverlayPipeline` | `runtime/OverlayPipeline.java` | Coordinates HUD and pause overlays with resize awareness |
| `WorldPassCoordinator` | `runtime/WorldPassCoordinator.java` | Coordinates world-base contributors like `FloorRenderer` |
| `OverlayCaptureOrchestrator` | `runtime/OverlayCaptureOrchestrator.java` | Post-scene overlay rendering order + post-render capture callback |
| `ArtifactService` | `capture/ArtifactService.java` | Semantic capture API (suffix-based filenames, live viewport) |
| `CaptureCallback` | `capture/CaptureCallback.java` | Interface for scheduling post-render captures (implemented by runner) |
| `Camera3D` | `camera/Camera3D.java` | First-person camera with WASD/SPACE/SHIFT movement and mouse look |
| `FloorRenderer` | `camera/FloorRenderer.java` | Infinite ground plane at Y=0 with grid pattern |
| `HUDRenderer` | `camera/HUDRenderer.java` | On-screen position/rotation display (top-left corner) |
| `PauseScreenRenderer` | `camera/PauseScreenRenderer.java` | Semi-transparent overlay shown when paused |
| `TaskScheduler` | `scheduler/TaskScheduler.java` | Time-based callback system for automated events |
| `HarnessDebugTools` | `debug/HarnessDebugTools.java` | Programmatic camera control + screenshot capture via `ArtifactService` |

### Rendering Pipeline (per frame)

The interactive runner sequences these steps every frame:

1. **Frame clock tick**: `FrameClock.tick()` computes delta/elapsed time, increments frame number
2. **Resize check**: `ResizeHandler.checkAndPropagate()` detects display resize, updates viewport + renderers
3. **Input polling**: `InputPauseHandler.pollPauseToggle()` checks ESC/T for pause toggle
4. **Camera update**: Process WASD/SPACE/SHIFT movement + mouse look (skipped when paused)
5. **Task scheduler tick**: Fire any callbacks whose time has elapsed
6. **World pass**: `RenderPassState.beginWorldPass()` then `WorldPassCoordinator` renders world base (e.g. floor)
7. **Scene pass**: `RenderPassState.beginScenePass()` then `scene.render(ctx, frameInfo)`
8. **Post-scene sequence**: `OverlayCaptureOrchestrator.executePostSceneSequence()`:
   - GL state reset via `GlStateResetHelper.resetAfterScene()`
   - Overlay pass (handled by `OverlayPipeline`: pause overlay if paused, HUD if 3D camera active)
   - Post-render capture callback (one-shot, after all overlays, before swap)
9. **Swap buffers**: `Display.update()` + `Display.sync(60)`

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
// Screenshot capture (direct, for managed scenes)
ScreenshotUtil.captureBackbuffer(width, height, outputDir, "screenshot.png");
ScreenshotUtil.captureFboColorTexture(fboId, texId, w, h, outputDir, "fbo.png");
ScreenshotUtil.captureTexture(texId, w, h, GL30.GL_R8, outputDir, "tex.png");

// Interactive scene capture (preferred for interactive scenes)
ArtifactService artifacts = ctx.getArtifactService();
artifacts.requestCapture("my-view"); // -> {outputName}-my-view.png

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

---

## Scene Authoring Guide

### Adding a New Managed Scene

Managed scenes render a single frame and exit. They're used for FBO captures, atlas dumps, and diagnostic output.

**Step 1**: Create a class implementing `HarnessSceneLifecycle`:

```java
public class MyManagedScene implements HarnessSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
        // Set up GL resources (shaders, FBOs, textures)
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Render once. The runtime calls this exactly once.
        OutputSettings out = ctx.getOutputSettings();
        // ... render to FBO or backbuffer ...
        ScreenshotUtil.captureBackbuffer(
            ctx.getViewport().getWidth(),
            ctx.getViewport().getHeight(),
            out.getOutputDir(),
            out.getOutputName() + ".png"
        );
    }

    @Override
    public void dispose() {
        // Delete GL resources
    }
}
```

**Step 2**: Register in `SceneRegistry.createDefault()`:

```java
reg.register(
    SceneDescriptor.builder("my-scene")
        .description("My custom scene description")
        .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
        .category(SceneDescriptor.Category.SCENE)
        .needsFbo(true)
        .needsDepthBuffer(true)
        .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
        .build(),
    new HarnessSceneFactory() {
        public HarnessSceneLifecycle create() { return new MyManagedScene(); }
    }
);
```

Note: The factory returns `HarnessScene`. Since `MyManagedScene` implements `HarnessSceneLifecycle`, the main entry point detects this and calls the new lifecycle directly (no adapter needed).

**Step 3**: If the scene needs typed config, extend `HarnessConfig` and add the factory case in `FontDebugHarnessMain.createConfig()`.

### Adding a New Interactive Scene

Interactive scenes run a continuous render loop with camera controls, scheduled events, and automatic screenshot capture.

**Step 1**: Create a class implementing `InteractiveSceneLifecycle`:

```java
public class MyInteractiveScene implements InteractiveSceneLifecycle {

    private boolean running = true;
    private Camera3D camera;
    private TaskScheduler scheduler;
    private ArtifactService artifacts;

    @Override
    public void init(HarnessContext ctx) {
        // Grab shared subsystems from context
        this.camera = ctx.getCamera3D();
        this.scheduler = ctx.getTaskScheduler();
        this.artifacts = ctx.getArtifactService();

        // Initialize scene GL resources
        // ...

        // Position camera
        camera.moveCamera(0f, 3f, 8f);
        camera.setPitch(-15f);

        // Schedule timed events
        scheduler.schedule(0.5, "capture-normal", new Runnable() {
            @Override
            public void run() {
                // Request a post-render capture by semantic suffix.
                // The ArtifactService composes the filename and schedules
                // the capture after the full frame renders.
                artifacts.requestCapture("normal");
            }
        });

        scheduler.schedule(1.0, "shutdown", new Runnable() {
            @Override
            public void run() {
                running = false;
            }
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Render scene content each frame.
        // Floor, HUD, and pause overlay are handled by the runtime.
        // GL state reset after this call is also handled by the runtime.
    }

    @Override
    public void dispose() {
        // Delete GL resources
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean uses3DCamera() { return true; }

    @Override
    public boolean shouldShutdownOnComplete() { return true; }
}
```

**Step 2**: Register in `SceneRegistry.createDefault()`:

```java
reg.register(
    SceneDescriptor.builder("my-interactive")
        .description("My interactive 3D scene")
        .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
        .category(SceneDescriptor.Category.SCENE)
        .needsFbo(false)
        .needsDepthBuffer(true)
        .build(),
    new HarnessSceneFactory() {
        public HarnessSceneLifecycle create() { return new MyInteractiveScene(); }
    }
);
```

**Step 3**: Access shared systems through the context:

- **Camera**: `ctx.getCamera3D()` — `camera.moveCamera()`, `camera.setYaw()`, `camera.setPitch()`
- **Scheduler**: `ctx.getTaskScheduler()` — `scheduler.schedule(time, id, callback)`
- **Captures**: `ctx.getArtifactService()` — `artifacts.requestCapture("suffix")`
- **World settings**: `ctx.getWorldSettings()` — sky color, floor color, floor half-size (read-only)
- **Viewport**: `ctx.getViewport()` — live width/height, aspect ratio
- **Debug tools**: `new HarnessDebugTools(ctx.getCamera3D(), ctx.getArtifactService())`
- **Pause control**: `ctx.getRuntimeServices().setPaused(true)`
- **Validation**: `new ValidationChoreographer(...)` + `ValidationCaptureStep.builder(...)` for scripted capture sequences

### Output Conventions

- Each scene outputs to its own subdirectory: `harness-output/{sceneName}/`
- The base output directory is `config.getOutputDir()` (default: `gl-debug-harness/harness-output/`)
- Interactive scenes use `ArtifactService.requestCapture("suffix")` which produces `{outputName}-{suffix}.png`
- The output name defaults to the scene's mode ID, overridable via `--output-name=PREFIX`
- PNG filenames must be deterministic and stable
- Non-PNG artifacts (manifests, reports) use `.txt` extension

### GL State Rules for Scenes

- Scenes don't need to clean up GL state after rendering. The runtime calls `GlStateResetHelper.resetAfterScene()` automatically.
- Scenes start with safe defaults: depth test ON, blend OFF, depth writes ON (set by `RenderPassState.beginScenePass()`).
- Scenes may modify any GL state they need during `render()`. The post-scene reset handles cleanup.
- Scenes should NOT directly manage overlay or capture timing. Use `ArtifactService` for captures.

### Testing

- Non-GL code: JUnit 4 tests in `gl-debug-harness/src/test/java/`
- GL-touching code: Verify via `runHarness` command + artifact existence check
- Never mock GL calls in unit tests

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
├── text-3d/
│   ├── text-3d-normal.png
│   ├── text-3d-paused.png
│   └── text-3d-topdown.png
├── camera-3d/
│   ├── camera-3d-front-view.png
│   ├── camera-3d-side-view.png
│   ├── camera-3d-top-down-view.png
│   └── camera-3d-diagonal-view.png
└── render-validation/
    ├── render-validation-normal.png
    ├── render-validation-paused.png
    └── render-validation-top-down.png
```

### Custom Output Name Prefix

Use `--output-name=PREFIX` to customize output filenames:

```bash
# Default: scene name used as prefix
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"
# -> harness-output/text-3d/text-3d-normal.png
# -> harness-output/text-3d/text-3d-paused.png
# -> harness-output/text-3d/text-3d-topdown.png

# Custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d --output-name=test1"
# -> harness-output/text-3d/test1-normal.png
# -> harness-output/text-3d/test1-paused.png
# -> harness-output/text-3d/test1-topdown.png

# Camera validation with custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=camera-3d --output-name=gpu-test"
# -> harness-output/camera-3d/gpu-test-front-view.png
# -> harness-output/camera-3d/gpu-test-side-view.png
# -> harness-output/camera-3d/gpu-test-top-down-view.png
# -> harness-output/camera-3d/gpu-test-diagonal-view.png
```

### All Available Scenes

| Mode ID             | Type | Description |
|---------------------|---|---|
| `triangle-2d`       | MANAGED | Basic colored triangle |
| `atlas-dump`        | MANAGED | Glyph atlas texture dump |
| `text-2d`           | MANAGED | Full text rendering via CgTextRenderer |
| `text-3d`           | INTERACTIVE | Interactive 3D world-space text with camera |
| `camera-3d`         | INTERACTIVE | Camera validation: cube from 4 angles |
| `gl-state-dump`     | DIAGNOSTIC | GL state dump to text file |
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

---

## Build Configuration

The harness is a standalone Gradle subproject (`gl-debug-harness/build.gradle.kts`) that depends on the root project for CgCapabilities, CgGlyphAtlas, and font APIs. It bundles LWJGL 2 natives and JNI bindings for freetype-harfbuzz and msdfgen.

Key tasks:
- `compileJava` — Compile all harness code
- `test` — Run JUnit 4 tests (non-GL)
- `runHarness` — Launch the standalone harness
- `extractLwjglNatives` — Extract LWJGL DLLs (auto-dependency of runHarness)

---

## Package Structure

```
io.github.somehussar.crystalgraphics.harness/
├── FontDebugHarnessMain.java        # Entry point: CLI parsing, config resolution, runtime dispatch
├── HarnessSceneFactory.java         # Factory for deferred scene creation
├── HarnessSceneLifecycle.java       # Unified scene lifecycle v2 (init/render/onResize/dispose)
├── InteractiveSceneLifecycle.java   # Interactive extension (isRunning/uses3DCamera/shouldShutdown)
├── InteractiveSceneRunner.java      # Slim loop coordinator for interactive scenes
├── FrameInfo.java                   # Immutable per-frame timing snapshot
├── SceneRegistry.java               # Explicit scene/mode registration
├── camera/
│   ├── Camera3D.java                # First-person 3D camera
│   ├── FloorRenderer.java          # Ground plane at Y=0
│   ├── HUDRenderer.java            # On-screen camera state text
│   └── PauseScreenRenderer.java    # Semi-transparent pause overlay
├── capture/
│   ├── ArtifactService.java         # Framework-owned capture API (suffix-based naming)
│   └── CaptureCallback.java         # Post-render capture scheduling interface
├── config/
│   ├── HarnessConfig.java           # Base config (outputDir, width, height, fontPath, outputName)
│   ├── AtlasDumpConfig.java         # Atlas-specific config
│   ├── TextSceneConfig.java         # Text-scene-specific config
│   ├── HarnessContext.java          # Composition root with typed sub-objects
│   ├── ViewportState.java           # Mutable viewport tracking (updated on resize)
│   ├── OutputSettings.java          # Immutable output dir + name prefix
│   ├── RuntimeServices.java         # Typed interactive runtime access
│   ├── SceneDescriptor.java         # Scene metadata (id, lifecycle mode, FBO/depth needs)
│   ├── WorldConfig.java             # Mutable singleton (legacy, read at startup only)
│   └── WorldSettings.java           # Immutable per-run world rendering config
├── debug/
│   └── HarnessDebugTools.java       # Programmatic camera + capture via ArtifactService
├── runtime/
│   ├── FrameClock.java              # Frame timing service
│   ├── InputPauseHandler.java       # Pause toggle + mouse grab management
│   ├── ResizeHandler.java           # Display resize detection + propagation
│   ├── OverlayPipeline.java         # Coordinates HUD and pause overlays
│   ├── WorldPassCoordinator.java    # Coordinates world contributors (floor, etc.)
│   └── OverlayCaptureOrchestrator.java  # Post-scene overlay order + capture callback
├── scene/
│   ├── TriangleScene2D.java           # Basic triangle (managed)
│   ├── AtlasDumpScene.java          # Atlas dump (managed)
│   ├── TextScene2D.java               # Full text rendering (managed)
│   ├── TextScene3D.java # Interactive world-text with camera (interactive)
│   ├── Camera3DValidationScene.java # Camera validation: 4-angle cube captures
├── scheduler/
│   └── TaskScheduler.java           # Time-based callback scheduling
├── tool/
│   ├── GlStateDumper.java           # GL state dump diagnostic
│   ├── CapabilityReport.java        # Capabilities diagnostic
│   ├── FboInspector.java            # FBO color attachment inspector
│   ├── TextureInspector.java        # Texture capture inspector
│   ├── GlErrorChecker.java          # GL error drain utility
│   └── AtlasDumper.java             # Atlas texture + manifest dumper
├── validation/
│   ├── ValidationChoreographer.java # Scripted capture sequence coordinator
│   └── ValidationCaptureStep.java    # Value object for a single capture step
└── util/
    ├── ScreenshotUtil.java          # Low-level PNG capture primitives
    ├── GlStateResetHelper.java      # Canonical post-scene GL state reset
    ├── RenderPassState.java         # Per-pass GL state boundary helpers
    ├── ValidationCubeHelper.java    # Shared cube geometry for validation scenes
    ├── HarnessProjectionUtil.java   # Projection matrices + shared FOV/near/far constants
    ├── HarnessFontUtil.java         # Font path resolution
    ├── HarnessBitmapUtil.java       # FreeType bitmap normalization
    ├── HarnessShaderUtil.java       # Shader compilation
    ├── HarnessOutputDir.java        # Output directory creation
    └── HarnessDiagnostics.java      # Startup diagnostic logging
```
