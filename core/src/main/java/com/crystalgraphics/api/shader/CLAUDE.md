# `api/shader` — GLSL sources: the preprocessor and the library

What every shader's source goes through, and the GLSL library it may include. No program handle lives here: a shader is
reached through a material (`CgMaterial`, its values through `CgMaterialValues`) or a kernel (`CgKernel`); the linked
programs are internal (`gl/shader`). The raw-program API (`CgShader`, `CgShaderProgram`, `CgShaderBindings`, the
shader manager, global uniform injectors) was deleted in rhi SH9.

---

## Class Map

### `CgShaderPreprocessor`
GLSL source preprocessor. Handles `#include`, `#pragma once`, `#define` injection, header text, and cycle detection.

**`#include` resolution:**
- Both `"quoted"` and `<angle>` forms accepted
- Absolute: `crystalgraphics:shaders/lib/math.glsl` → `/assets/crystalgraphics/shaders/lib/math.glsl`
- Relative: `../common/util.glsl` resolved against the including file's directory
- Loaded via `CgIO.loadSource()` (classpath → MC resource manager → filesystem override)

**`#pragma once`:** Files with this directive are included at most once per `process()` call. Stripped from output.

**Cycle detection:** Throws `CgPreprocessorException` if a file appears in its own include chain.

**`#line` directives:** Emitted around includes when `crystalgraphics.shader.devmode` is on.

**Source cache:** One for every instance. Disabled when `crystalgraphics.resourceOverrideDirs` is set (`CgIO.OVERRIDE_DIRS`) and hot reload is off; with hot reload on it caches from launch, its watchers evicting each edited include. Call `invalidateCache()` after a resource reload.

```java
CgShaderPreprocessor pp = new CgShaderPreprocessor();
String expanded = pp.process(rawGlsl, "crystalgraphics:shaders/my_shader.frag");
```

Constructors: `()`, `(Map<String,String> defines)`, `(String headerText, Map<String,String> defines)`.

### `CgPreprocessorException`
`RuntimeException` thrown by `CgShaderPreprocessor` on include-not-found or circular include. Fields: `shaderPath` (normalized path), `line` (1-based, or 0 if unknown).

### `CgShaderStages`
Which stage a source is for, and the stage defines the compilers emit.

---

## GLSL Library Files

Located at `src/main/resources/assets/crystalgraphics/shaders/lib/`. All files use `#pragma once`; every shader compiles at the one target, `#version 450 core`. Loaded via classpath at `CgIO.loadSource("crystalgraphics:shaders/lib/math.glsl")`.

| File | Contents |
|---|---|
| `math.glsl` | Constants (`CG_PI`, `CG_FLT_EPS`…), `saturate`, `remap`, `remap01`, `sq`, `cb`, `positive_pow`, `safe_pow`, `sign_pow`, `smootherstep`, `deg_to_rad`/`rad_to_deg` |
| `vector.glsl` | `length2`, `safe_normalize`, `orthonormalize`, `fresnel` (Schlick), `rotate_axis` (Rodrigues), `project_onto`/`reject_from`, `angle_between` |
| `color.glsl` | `luminance` (BT.709), `srgb_to_linear`/`linear_to_srgb` (precise), `fast_srgb_to_linear`/`fast_linear_to_srgb` (Chilliant), `rgb_to_hsv`/`hsv_to_rgb` (branchless), `rotate_hue`, `desaturate` |
| `uv.glsl` | `rotate_uv`, `scale_uv`, `tile_uv`, `pan_uv`, `flip_uv_x`/`flip_uv_y`, `cartesian_to_polar_uv` |
| `noise.glsl` | `hash12`/`hash22`/`hash13` (Dave Hoskins sin-free), `value_noise`, `fbm4`/`fbm6` (unrolled IQ), `fbm(p, octaves)`, `fbm_ridged` |

Include in a `.shader` or `.compute`:
```glsl
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
```
`vector.glsl` and `color.glsl` both include `math.glsl`. `#pragma once` prevents double-expansion regardless of include order.

---

Also loaded with this folder:

@../../../../../../../../docs/SHADERS.md
