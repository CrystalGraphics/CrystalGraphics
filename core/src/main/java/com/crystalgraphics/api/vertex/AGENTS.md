# api/vertex — Public Vertex and Instance Layout APIs

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Public, GL-free vertex format and instance layout descriptors. These are pure data objects —
no GL calls, no shader coupling. They serve as registry keys and attribute layout sources
consumed by the VAO/VBO and instancing backends.

## Type Map

| Type | Role |
|------|------|
| `CgAttributeFormat` | Common interface for attribute layout descriptors. Implemented by both `CgVertexFormat` and `CgInstanceFormat`. Provides `getStride()`, `getAttributeCount()`, `getAttribute(int)`, `getFloatsPerElement()`. Enables uniform VAO setup code across base and instance layouts. |
| `CgVertexFormat` | Immutable, hashable vertex format descriptor. Registry key for `CgVertexArrayRegistry`. Value-equal by attribute list + stride. Builder creates sequential attribute slots. **Auto-registers under `key name` on `build()`** — the `key name` doubles as the `#type` key in `.shader` files. `forShaderType(String)` looks up a registered format; `registeredShaderTypes()` returns all known names. Pre-defined: `POS2_UV2_COL4UB`, `POS3_UV2_COL4UB`, `SPATIAL` (registered as `"spatial"`, CrystalShader pipeline: cg_Position/cg_TexCoord0/cg_Normal, 32 bytes). Implements `CgAttributeFormat`. Registry collision (same name, different layout) throws `IllegalStateException` at second `build()`. |
| `CgVertexAttribute` | Single attribute within a format: name, type, components, offset, normalized flag, semantic metadata. Package-private constructor. Value-equal. `getGlslType()` derives the GLSL type string from `(type, components, normalized)`: float family for FLOAT or normalized types; int/ivec family for signed integer non-normalized; uint/uvec family for unsigned integer non-normalized. Throws `IllegalStateException` on unmapped combos. |
| `CgVertexSemantic` | Enum of attribute roles: `POSITION`, `UV`, `COLOR`, `NORMAL`, `GENERIC`. Used by `CgVertexWriter` for fluent routing. |
| `CgAttribType` | Enum of GL primitive types (`FLOAT`, `UNSIGNED_BYTE`, etc.) with byte sizes. |
| `CgVertexConsumer` | Fluent vertex-write interface implemented by `CgVertexWriter`. |
| `CgVertexTransformUtil` | Utility for transforming vertex positions via `PoseStack`. |
| `CgInstanceFormat` | Immutable, value-equal per-instance attribute layout. Implements `CgAttributeFormat`. See below. |

## CgInstanceFormat

`CgInstanceFormat` is the instance-side analogue of `CgVertexFormat`. It describes the
interleaved per-instance data layout for instanced rendering.

### Key design rules

- **mat4 expands to four physical vec4 attributes** — a single logical mat4 occupies
  four consecutive attribute slots. `mat4("a_model")` appends attributes named
  `"a_model0"`, `"a_model1"`, `"a_model2"`, `"a_model3"`.
- **Only divisor 1 is supported in v1** — multi-rate instancing (divisor > 1) is deferred.
  `builder.divisor(N)` throws `IllegalArgumentException` for any `N != 1`.
- **Value equality** — two layouts with the same stride, divisor, and physical attributes
  are equal. Debug name is excluded from equality (diagnostic only).
- **Zero attributes rejected** — `build()` throws `IllegalStateException` if no attributes were added.

### Attribute slot assignment

Slots in `CgInstanceFormat` are relative to zero. The instanced VAO assigns them starting
at `baseFormat.getAttributeCount()`. For example, with `POS2_UV2_COL4UB` (3 base attrs):
- Instance slot 0 → VAO location 3
- Instance slot 1 → VAO location 4
- ... etc.

### Pre-built common layout

`CgInstanceFormat.TRANSFORM_COLOR_CUSTOM` — engine-primitive layout with:
- `a_instanceModel0..3` — mat4 model transform (64 bytes, 4 vec4 attributes)
- `a_instanceColor` — normalized ubyte4 RGBA color (4 bytes)
- `a_instanceCustom` — float vec4 custom data (16 bytes)
- **Total stride: 84 bytes**, 6 physical attributes.

### Builder API

```java
CgInstanceFormat layout = CgInstanceFormat.builder("my-layout")
    .mat4("a_model")             // adds 4 float vec4 attributes
    .color4UB("a_color")         // adds 1 normalized ubyte4 attribute
    .vec4("a_custom")            // adds 1 float vec4 attribute
    .build();
```

## Design Rules

- **No GL calls** in any type in this package. All types are pure data.
- **Value equality** for `CgVertexFormat` and `CgInstanceFormat` enables content-based
  registry caching — construction from separate call sites with the same attributes
  produces equal keys.
- **Attribute constructor is package-private** — use the builder APIs to create formats
  and layouts; never construct `CgVertexAttribute` directly from outside this package.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/vertex/` | Consumes `CgVertexFormat` and `CgInstanceFormat` as registry keys and attribute layout sources for VAO configuration |
| `gl/buffer/staging/` | `CgVertexWriter` is driven by `CgVertexFormat` semantics; `CgInstanceWriter` is driven by `CgInstanceFormat` |
| `gl/render/` | `CgBatchRenderer` and `CgInstanceRenderer` use these as format identifiers |
