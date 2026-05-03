# `api/shader` — Public Shader API

Public, stable contracts for the managed shader system. No GL calls, no Minecraft imports. Everything here is consumed by both the MC module and the standalone GL harness.

---

## Class Map

### `CgShader`
The primary handle for a managed shader. Wraps a `CgShaderProgram` with lazy compilation, dirty-reload semantics, and binding accumulation.

Key contract points:
- `bind()` — compiles on first call if dirty; no-op if not compiled
- `unbind()` — no-op if not compiled
- `markDirty()` — defers recompile to next `bind()`; safe to call mid-render
- `preprocess(CgShaderPreprocessor)` — attaches a preprocessor post-construction, marks dirty; pass `null` to clear
- `applyBindings(Consumer<CgShaderBindings>)` — ephemeral bindings applied once on next bind then auto-cleared
- `bindings()` — persistent binding container, survives across frames
- `bindScoped()` / `bindScoped(CgScopeRestoreOption...)` — try-with-resources binding; restores prior program on close
- `getUniformLocation(String)` — cached; invalidated on recompile
- `getLastCompileError()` — returns the GL info log from the last failed compile/link, or `null` if compile succeeded or has not been attempted
- `getActiveUniforms()` — returns an unmodifiable `List<CgActiveUniform>` introspected from the linked program; empty list if not compiled

### `CgShaderManager`
Factory + cache interface for managed shaders. Entry point for mod code.

```java
CgShader s = CgShaderFactory.SHADER_MANAGER.load("mymod:shaders/foo.vert", "mymod:shaders/foo.frag");
s.preprocess(new CgShaderPreprocessor(myDefines));
s.bind();
```

Key methods: `load(vert, frag)`, `load(vert, frag, CgVertexFormat)`, `getIfLoaded(key)`, `reloadAll()`, `deleteAll()`.

### `CgShaderPreprocessor`
GLSL source preprocessor. Handles `#include`, `#pragma once`, `#define` injection, header text, and cycle detection.

**`#include` resolution:**
- Both `"quoted"` and `<angle>` forms accepted
- Absolute: `crystalgraphics:shaders/lib/math.glsl` → `/assets/crystalgraphics/shaders/lib/math.glsl`
- Relative: `../common/util.glsl` resolved against the including file's directory
- Loaded via `CgIO.loadSource()` (classpath → MC resource manager → filesystem override)

**`#pragma once`:** Files with this directive are included at most once per `process()` call. Stripped from output.

**Cycle detection:** Throws `CgPreprocessorException` if a file appears in its own include chain.

**`#line` directives:** Emitted around includes when `-Dcrystalgraphics.shader.devmode` is set.

**Source cache:** Per-instance, shared across calls. Disabled when `-Dcrystalgraphics.shader.resourceOverrideDir` is set. Call `invalidateCache()` after a resource reload.

```java
CgShaderPreprocessor pp = new CgShaderPreprocessor();
String expanded = pp.process(rawGlsl, "crystalgraphics:shaders/my_shader.frag");

shader.preprocess(pp);  // attaches to managed shader, recompiles on next bind()
```

Constructors: `()`, `(Map<String,String> defines)`, `(String headerText, Map<String,String> defines)`.

### `CgPreprocessorException`
`RuntimeException` thrown by `CgShaderPreprocessor` on include-not-found or circular include. Fields: `shaderPath` (normalized path), `line` (1-based, or 0 if unknown).

### `CgShaderBindings`
Deferred uniform/sampler container. Accumulated via fluent API, flushed to the program on `apply(CgShader)`.

Supported setter families:
- `set1i`, `set1f`, `set2f`, `set3f`, `set4f` — scalar/vector uniforms
- `vec2`, `vec3`, `vec4` — JOML vector overloads
- `mat3`, `mat4` — JOML matrix uniforms (serialized via thread-local `FloatBuffer`)
- `argbColor` — ARGB int → normalized vec4
- `sampler2D(name, unit, CgTexture)`, `sampler2DRaw(name, unit, target, texId)` — texture binding + sampler unit
- `apply(CgShader)` — flush all ops; `clear()` — reset

Missing uniforms: warn-once per program ID, not per frame.

### `CgShaderProgram`
Low-level compiled program handle. Used by `CgShader` internally. Direct callers should prefer `CgShader`. Methods: `bind()`, `unbind()`, `getUniformLocation(String)`, `setUniform1f/1i/2f/3f/4f/mat3/mat4(loc, ...)`, `isDeleted()`, `delete()`, `getActiveUniforms()` (default returns empty list; overridden in `CoreShaderProgram` and `ArbShaderProgram` with real GL query).

### `CgActiveUniform`
Immutable `@Value` descriptor of one active uniform as reported by `glGetActiveUniform` / `glGetActiveUniformARB`. Fields:
- `name` — uniform name (e.g. `"u_time"`, `"myArray[0]"`)
- `glType` — GL type constant (`GL_FLOAT`, `GL_FLOAT_VEC2`, `GL_SAMPLER_2D`, …)
- `size` — array element count (1 for non-arrays)
- `location` — result of `glGetUniformLocation`

Built-in `gl_*` uniforms are excluded. Returned by `CgShader.getActiveUniforms()` and `CgShaderProgram.getActiveUniforms()`.

### `CgShaderCacheKey`
Immutable value object: `(vertexLocation, fragmentLocation, defines)`. Sorted defines for deterministic equality. Used as cache key in `CgShaderManagerImpl`.

### `CgShaderScope`
`Closeable` scope returned by `CgShader.bindScoped()`. Restores the previously bound program (or full GL state with `FULL_BINDINGS`) on close.

### `CgScopeRestoreOption`
Enum controlling `bindScoped` restore depth. `FULL_BINDINGS` saves/restores framebuffer + textures + active unit + shader via `CgStateBoundary`.

### `CgUniformInjector`
Functional interface for system-wide per-frame uniform injection. Registered with `CgSystemUniformRegistry`. Called on every `bind()` before per-shader bindings.

```java
CgSystemUniformRegistry.getInstance().register(
    CgUniformName.of("u_time"),
    (shader, name) -> {
        int loc = shader.getUniformLocation(name);
        if (loc >= 0) shader.getProgram().setUniform1f(loc, CrystalGraphics.getTimeSinceStart());
    }
);
```

---

## Binding Application Order (per `bind()`)

1. `CgSystemUniformRegistry.applyAll()` — system uniforms (`u_time`, `u_resolution`, …) in ascending priority
2. `shader.bindings().apply(shader)` — persistent per-shader bindings
3. `shader.ephemeralBindings.apply(shader)` — one-shot bindings from `applyBindings()`; cleared immediately after

---

## GLSL Library Files

Located at `src/main/resources/assets/crystalgraphics/shaders/lib/`. All files use `#pragma once` and target `#version 330 core`. Loaded via classpath at `CgIO.loadSource("crystalgraphics:shaders/lib/math.glsl")`.

| File | Contents |
|---|---|
| `math.glsl` | Constants (`CG_PI`, `CG_FLT_EPS`…), `saturate`, `remap`, `remap01`, `sq`, `cb`, `positive_pow`, `safe_pow`, `sign_pow`, `smootherstep`, `deg_to_rad`/`rad_to_deg` |
| `vector.glsl` | `length2`, `safe_normalize`, `orthonormalize`, `fresnel` (Schlick), `rotate_axis` (Rodrigues), `project_onto`/`reject_from`, `angle_between` |
| `color.glsl` | `luminance` (BT.709), `srgb_to_linear`/`linear_to_srgb` (precise), `fast_srgb_to_linear`/`fast_linear_to_srgb` (Chilliant), `rgb_to_hsv`/`hsv_to_rgb` (branchless), `rotate_hue`, `desaturate` |
| `uv.glsl` | `rotate_uv`, `scale_uv`, `tile_uv`, `pan_uv`, `flip_uv_x`/`flip_uv_y`, `cartesian_to_polar_uv` |
| `noise.glsl` | `hash12`/`hash22`/`hash13` (Dave Hoskins sin-free), `value_noise`, `fbm4`/`fbm6` (unrolled IQ), `fbm(p, octaves)`, `fbm_ridged` |

Include in shaders:
```glsl
#version 330 core
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
```
`vector.glsl` and `color.glsl` both include `math.glsl`. `#pragma once` prevents double-expansion regardless of include order.
