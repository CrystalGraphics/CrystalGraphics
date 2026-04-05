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
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=msdf --font-size-px=128"

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

### Config Precedence

Values are applied in this order (later wins):
1. Hardcoded defaults in config class constructors
2. System properties (`-Dharness.font.path=...`)
3. CLI args (`--font-path=...`)

Config classes:
- `HarnessConfig` — base (outputDir, width, height, fontPath)
- `AtlasDumpConfig` — adds atlasType, atlasSize, fontSizePx, text
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

| Mode ID | Output | Lifecycle | Description |
|---|---|---|---|
| `triangle` | `triangle.png` | MANAGED | Basic colored triangle on backbuffer |
| `fbo-triangle` | `fbo-triangle.png` | MANAGED | Same triangle rendered to FBO |
| `atlas-dump` | `atlas/atlas-dump-24px.png` + `atlas/atlas-dump-32px.png` (default: both) | MANAGED | Glyph atlas dump via CgTextRenderer production pipeline |
| `text-scene` | `text-scene.png` + `atlas/atlas-dump-<size>px.png` | MANAGED | Full text rendered via CgTextRenderer + FBO |

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

- All output goes to `config.getOutputDir()` (default: `gl-debug-harness/harness-output/`)
- PNG filenames must be deterministic and stable
- Non-PNG artifacts (manifests, reports) use `.txt` extension
- Scene mode ID should match the primary output filename stem

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

## Build Configuration

The harness is a standalone Gradle subproject (`gl-debug-harness/build.gradle.kts`) that depends on the root project for CgCapabilities, CgGlyphAtlas, and font APIs. It bundles LWJGL 2 natives and JNI bindings for freetype-harfbuzz and msdfgen.

Key tasks:
- `compileJava` — Compile all harness code
- `test` — Run JUnit 4 tests (non-GL)
- `runHarness` — Launch the standalone harness
- `extractLwjglNatives` — Extract LWJGL DLLs (auto-dependency of runHarness)
