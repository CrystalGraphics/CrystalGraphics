# CrystalShader `.shader` File Format Specification

> **Version**: MVP (Waves 1–4)  
> **Minimum GLSL**: `#version 330 core`  
> **Parser**: `CgShaderParser` (`gl/material/`)  
> **Compiler**: `CgMaterialShaderCompiler` (`gl/material/`)

---

## 1. Overview

A `.shader` file is a single text file that contains both the vertex stage and the
fragment stage of a CrystalShader material. One file is loaded via `CgMaterial.load(path)`
and produces a linked GLSL program that works identically for both `glDrawElements`
(non-instanced) and `glDrawElementsInstanced` (instanced) — no modification, no `#ifdef`,
same VAO.

The file is **structurally parsed** by `CgShaderParser` into a `CgParsedShader` value
object, then **GLSL-generated** by `CgMaterialShaderCompiler` into full vertex + fragment
source strings, preprocessed once by `CgShaderPreprocessor`, and compiled by the
existing `CgShaderFactory.fromSource(vert, frag, format)` pipeline.

---

## 2. File Structure

A valid `.shader` file must contain the following sections **in order**:

```
#type <shader-type>

Properties {
    <property-list>
}

struct v2f {
    <field-list>
};

// optional global declarations (helper functions, uniforms, etc.)

void vertex(out v2f o) {
    <vertex-body>
}

void fragment(in v2f i, out vec4 fragColor) {
    <fragment-body>
}
```

All sections are **required** in the MVP. No section may appear more than once.

---

## 3. Section Specifications

### 3.1 `#type` Header

**Format**: `#type <word>`

Must be the **first non-blank, non-comment line** in the file.

**Valid values in MVP**:
- `spatial` — a world-space material with model/view/projection transforms

**Unknown types** → `CgShaderParseException` (hard error, no fallback).

```glsl
#type spatial
```

---

### 3.2 `Properties { }` Block

Declares per-material GLSL uniforms visible to both the vertex and fragment stages.
The compiler emits a `uniform <type> <name>;` declaration for each property in both
generated GLSL sources. Unused uniforms are silently optimized out by the driver.

**Format** (one per line, no comments inside block):
```
<name> : <type> [= <default>]
```

**Valid types**:

| Type        | GLSL declaration emitted      |
|-------------|-------------------------------|
| `float`     | `uniform float <name>;`       |
| `vec2`      | `uniform vec2 <name>;`        |
| `vec3`      | `uniform vec3 <name>;`        |
| `vec4`      | `uniform vec4 <name>;`        |
| `sampler2D` | `uniform sampler2D <name>;`   |

**Default values** are parsed and stored in `PropertyDecl.defaultValue` but are
**not validated** by the parser — semantic checking is the caller's responsibility.

**Rules**:
- One property per line.
- No comments inside the block body.
- No trailing commas.
- Property names may start with `_` (Unity-style convention), but must not begin
  with `cg_`, `CG_`, or `_v2f_` (engine-reserved prefixes).

```glsl
Properties {
    _MainTex : sampler2D
    _Color   : vec4 = (1.0, 1.0, 1.0, 1.0)
    _Tiling  : vec2 = (1.0, 1.0)
    _Alpha   : float = 1.0
}
```

---

### 3.3 `struct v2f { }` Block

Declares the interpolated data passed from the vertex stage to the fragment stage.
The compiler generates `out <type> _v2f_<name>;` flat-packed varyings for each field
in the vertex shader, and `in <type> _v2f_<name>;` in the fragment shader.

**Format** (one per line, no comments inside block body):
```
<float-family-type> <name>;
```

**Valid field types**:
- `float`, `vec2`, `vec3`, `vec4`, `mat3`, `mat4`

**Forbidden field types** (hard parse error):
- `int`, `uint`, `ivec2`, `ivec3`, `ivec4`, `uvec2`, `uvec3`, `uvec4`
- Integer types are forbidden because the flat-qualifier varying is not auto-emitted
  by the compiler, and per-vertex interpolation is undefined for integers.

**Rules**:
- One field per line.
- No arrays.
- No comments inside the block body.
- The struct body ends with `};` (semicolon after closing brace is required by GLSL).

The field name must not begin with `_v2f_` (engine reserved).

```glsl
struct v2f {
    vec3 worldPos;
    vec3 normal;
    vec2 uv;
};
```

---

### 3.4 Global Declarations (Optional)

Any GLSL declarations that appear **after** `struct v2f { ... };` and **before**
`void vertex(` are captured verbatim as `globalDecls` and emitted in both the
generated vertex and fragment sources.

Use this section for:
- Helper functions shared by both stages
- Additional `uniform` declarations not in `Properties`
- Forward declarations

**Forbidden**:
- `#version` directives (the compiler emits `#version 430 core` or `#version 330 core`)
- Any declaration of `main()` (the compiler emits the `main()` wrapper)

---

### 3.5 `void vertex(out v2f o) { }` Block

The user vertex body. Receives a writable `v2f` output struct `o` and must write
`gl_Position` and all `v2f` fields through `o`.

**Signature is fixed**: `void vertex(out v2f o)` — do not declare `main()`.

Built-in engine values available:
- `CG_OBJECT_TO_WORLD` — model matrix (`mat4`)
- `CG_NORMAL_MATRIX` — normal matrix (`mat3`)
- `CG_MATRIX_MVP` — `ProjMatrix * ViewMatrix * ModelMatrix` (`mat4`)
- `CG_OBJECT_CUSTOM0..3` — per-object `vec4` custom slots
- `CG_INSTANCE_ID` — instance index (`int`), 0 for non-instanced draws
- `cg_Position` — vertex position attribute (`vec3`)
- `cg_TexCoord0` — vertex UV attribute (`vec2`)
- `cg_Normal` — vertex normal attribute (`vec3`)
- `cg_ViewMatrix`, `cg_ProjMatrix` — frame matrices (`mat4`)

---

### 3.6 `void fragment(in v2f i, out vec4 fragColor) { }` Block

The user fragment body. Receives the interpolated `v2f` input struct `i` (readonly),
and the output color as `fragColor` (writeable `vec4`).

**Signature is fixed**: `void fragment(in v2f i, out vec4 fragColor)`.

The output name `fragColor` is fixed by convention in MVP.

Built-in engine values available (same as vertex, except attribute aliases are unavailable):
- All `CG_OBJECT_*`, `CG_MATRIX_MVP`, `CG_INSTANCE_ID`, `cg_ViewMatrix`, `cg_ProjMatrix`

---

## 4. Engine Built-Ins

All built-in names are provided by `cg_env.glsl`, included automatically by the compiler.
**Do not `#include "crystalgraphics:shaders/env/cg_env.glsl"` manually in `.shader` files.**

| Name                  | Type     | Stage          | Description                                      |
|-----------------------|----------|----------------|--------------------------------------------------|
| `CG_OBJECT_TO_WORLD`  | `mat4`   | vertex+frag    | Model-to-world matrix for instance `CG_INSTANCE_ID` |
| `CG_NORMAL_MATRIX`    | `mat3`   | vertex+frag    | Normal matrix (transpose(inverse(M3x3)))          |
| `CG_OBJECT_CUSTOM0`   | `vec4`   | vertex+frag    | Per-object custom slot 0                         |
| `CG_OBJECT_CUSTOM1`   | `vec4`   | vertex+frag    | Per-object custom slot 1                         |
| `CG_OBJECT_CUSTOM2`   | `vec4`   | vertex+frag    | Per-object custom slot 2                         |
| `CG_OBJECT_CUSTOM3`   | `vec4`   | vertex+frag    | Per-object custom slot 3                         |
| `CG_INSTANCE_ID`      | `int`    | vertex+frag    | Instance index (0 for non-instanced draws)       |
| `CG_OBJECT_DATA`      | struct   | vertex+frag    | Raw per-object record (avoid direct use)         |
| `CG_MATRIX_MVP`       | `mat4`   | vertex+frag    | `cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD` |
| `cg_ViewMatrix`       | `mat4`   | vertex+frag    | View matrix from `CgFrameBlock` UBO              |
| `cg_ProjMatrix`       | `mat4`   | vertex+frag    | Projection matrix from `CgFrameBlock` UBO        |
| `cg_Position`         | `vec3`   | **vertex only**| Vertex position attribute                        |
| `cg_TexCoord0`        | `vec2`   | **vertex only**| Vertex UV attribute                              |
| `cg_Normal`           | `vec3`   | **vertex only**| Vertex normal attribute                          |

---

## 5. Engine-Reserved Bindings

The following GL binding points are **engine-reserved** and must not be declared
or overridden by authored shaders:

| Binding Type | Point | Purpose                    |
|--------------|-------|----------------------------|
| SSBO         | 0     | `CgShaderBuffer` (per-object data) |
| UBO          | 1     | `CgFrameBlock` (frame matrices)    |

User-declared custom buffers are **forbidden in MVP**.

---

## 6. Reserved Name Prefixes

The following identifier prefixes are **engine-reserved**. User symbols must not
begin with these prefixes:

- `cg_` — engine GLSL uniforms and varyings
- `CG_` — engine macros and constants
- `_v2f_` — compiler-generated v2f varying names

---

## 7. FORBIDDEN Features (MVP)

The following are **explicitly not supported** in this MVP and will cause parse or
compile errors if attempted:

- `#version` directives in `.shader` files (compiler injects them)
- `main()` function declarations in `.shader` files (compiler generates them)
- `#ifdef INSTANCED` or any draw-type branch (the whole point is no branching)
- Integer types (`int`, `uint`, `ivec*`, `uvec*`) in `struct v2f`
- `layout(binding=N)` in authored shader body (engine uses pre-link binding)
- `layout(location=N)` attribute qualifiers (format-based binding handles this)
- More than one `vertex()` or `fragment()` function
- Missing `#type` header
- Missing `Properties` block
- Missing `struct v2f` block
- Pass variants (DEPTH/SHADOW) — not in MVP
- Render state setters (blend, depth, cull) — not in MVP
- Hot reload at runtime — not in MVP
- User-declared custom shader buffers — not in MVP

---

## 8. Example Shaders

### 8.1 Minimal Spatial Shader

```glsl
#type spatial

Properties {
}

struct v2f {
    vec2 uv;
};

void vertex(out v2f o) {
    o.uv = cg_TexCoord0;
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(i.uv, 0.0, 1.0);
}
```

### 8.2 Full Spatial Shader with Texture, Color, and Custom Slots

```glsl
#type spatial

Properties {
    _MainTex : sampler2D
    _Color   : vec4 = (1.0, 1.0, 1.0, 1.0)
    _Tiling  : vec2 = (1.0, 1.0)
}

struct v2f {
    vec3 worldPos;
    vec3 normal;
    vec2 uv;
};

// Global declaration: helper function available in both stages
float computeAttenuation(float dist, float range) {
    return clamp(1.0 - dist / range, 0.0, 1.0);
}

void vertex(out v2f o) {
    vec4 worldPosition = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.worldPos = worldPosition.xyz;
    o.normal   = normalize(CG_NORMAL_MATRIX * cg_Normal);
    o.uv       = cg_TexCoord0 * _Tiling;
    gl_Position = cg_ProjMatrix * cg_ViewMatrix * worldPosition;
}

void fragment(in v2f i, out vec4 fragColor) {
    vec4 texColor = texture(_MainTex, i.uv) * _Color;
    // CG_OBJECT_CUSTOM0.x can carry per-object emissive intensity
    float emissive = CG_OBJECT_CUSTOM0.x;
    fragColor = texColor + vec4(emissive);
}
```

### 8.3 Non-Instanced Single Object Draw

```java
// Java usage for a non-instanced draw:
objectBuffer.writeSingle(modelMatrix);          // writes slot 0
material.bind(frame, frameUniformBuffer, objectBuffer, 1);
mesh.drawDirect();
```

### 8.4 Instanced Multi-Object Draw (same material, same shader file)

```java
// Java usage for an instanced draw:
objectBuffer.beginWrite(count);
for (Matrix4f m : modelMatrices) {
    objectBuffer.putMatrix(m);
}
objectBuffer.endWrite();
material.bind(frame, frameUniformBuffer, objectBuffer, count);
mesh.drawInstanced(count);
```

---

## 9. Parser Behavior Summary

`CgShaderParser.parse(source)` processes sections in order and returns a
`CgParsedShader` value object with these fields:

| Field           | Contents                                                       |
|-----------------|----------------------------------------------------------------|
| `shaderType`    | `"spatial"` (or other, MVP only accepts `"spatial"`)           |
| `properties`    | Ordered `List<PropertyDecl>` (name, glslType, defaultValue)   |
| `v2fStructBody` | Raw content between `struct v2f {` and `}`                    |
| `globalDecls`   | Everything after `};` of v2f and before `void vertex(`        |
| `vertexBody`    | Content inside `void vertex(out v2f o) { ... }`               |
| `fragmentBody`  | Content inside `void fragment(in v2f i, out vec4 fragColor) { ... }` |

Throws `CgShaderParseException` for:
- Missing `#type` header
- Unknown `#type` value (in future validation pass; parser stores it as-is)
- Missing `Properties` block
- Malformed property line (does not match `name : type [= default]`)
- Missing `struct v2f` block
- Integer type in `struct v2f` (`int`, `uint`, `ivec*`, `uvec*`)
- Missing `void vertex(` block
- Missing `void fragment(` block
