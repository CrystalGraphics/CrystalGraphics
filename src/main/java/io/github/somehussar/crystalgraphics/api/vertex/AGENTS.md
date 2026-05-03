# api/vertex — Public Vertex and Instance Layout APIs

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Public, GL-free vertex format and instance layout descriptors. These are pure data objects —
no GL calls, no shader coupling. They serve as registry keys and attribute layout sources
consumed by the VAO/VBO and instancing backends.

## Type Map

| Type | Role |
|------|------|
| `CgAttributeLayout` | Common interface for attribute layout descriptors. Implemented by both `CgVertexFormat` and `CgInstanceLayout`. Provides `getStride()`, `getAttributeCount()`, `getAttribute(int)`, `getFloatsPerElement()`. Enables uniform VAO setup code across base and instance layouts. |
| `CgVertexFormat` | Immutable, hashable vertex format descriptor. Registry key for `CgVertexArrayRegistry`. Value-equal by attribute list + stride. Builder creates sequential attribute slots. Pre-defined: `POS2_UV2_COL4UB`, `POS3_UV2_COL4UB`. Implements `CgAttributeLayout`. |
| `CgVertexAttribute` | Single attribute within a format: name, type, components, offset, normalized flag, semantic metadata. Package-private constructor. Value-equal. |
| `CgVertexSemantic` | Enum of attribute roles: `POSITION`, `UV`, `COLOR`, `NORMAL`, `GENERIC`. Used by `CgVertexWriter` for fluent routing. |
| `CgAttribType` | Enum of GL primitive types (`FLOAT`, `UNSIGNED_BYTE`, etc.) with byte sizes. |
| `CgVertexConsumer` | Fluent vertex-write interface implemented by `CgVertexWriter`. |
| `CgVertexTransformUtil` | Utility for transforming vertex positions via `PoseStack`. |
| `CgInstanceLayout` | Immutable, value-equal per-instance attribute layout. Implements `CgAttributeLayout`. See below. |

## CgInstanceLayout

`CgInstanceLayout` is the instance-side analogue of `CgVertexFormat`. It describes the
interleaved per-instance data layout for instanced rendering.

### Key design rules

- **mat4 expands to four physical vec4 attributes** — a single logical mat4 occupies
  four consecutive attribute slots. `addMat4("a_model")` appends attributes named
  `"a_model0"`, `"a_model1"`, `"a_model2"`, `"a_model3"`.
- **Only divisor 1 is supported in v1** — multi-rate instancing (divisor > 1) is deferred.
  `builder.divisor(N)` throws `IllegalArgumentException` for any `N != 1`.
- **Value equality** — two layouts with the same stride, divisor, and physical attributes
  are equal. Debug name is excluded from equality (diagnostic only).
- **Zero attributes rejected** — `build()` throws `IllegalStateException` if no attributes were added.

### Attribute slot assignment

Slots in `CgInstanceLayout` are relative to zero. The instanced VAO assigns them starting
at `baseFormat.getAttributeCount()`. For example, with `POS2_UV2_COL4UB` (3 base attrs):
- Instance slot 0 → VAO location 3
- Instance slot 1 → VAO location 4
- ... etc.

### Pre-built common layout

`CgInstanceLayout.TRANSFORM_COLOR_CUSTOM` — engine-primitive layout with:
- `a_instanceModel0..3` — mat4 model transform (64 bytes, 4 vec4 attributes)
- `a_instanceColor` — normalized ubyte4 RGBA color (4 bytes)
- `a_instanceCustom` — float vec4 custom data (16 bytes)
- **Total stride: 84 bytes**, 6 physical attributes.

### Builder API

```java
CgInstanceLayout layout = CgInstanceLayout.builder("my-layout")
    .addMat4("a_model")          // adds 4 float vec4 attributes
    .addColor4ub("a_color")      // adds 1 normalized ubyte4 attribute
    .addVec4("a_custom")         // adds 1 float vec4 attribute
    .build();
```

## Design Rules

- **No GL calls** in any type in this package. All types are pure data.
- **Value equality** for `CgVertexFormat` and `CgInstanceLayout` enables content-based
  registry caching — construction from separate call sites with the same attributes
  produces equal keys.
- **Attribute constructor is package-private** — use the builder APIs to create formats
  and layouts; never construct `CgVertexAttribute` directly from outside this package.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/vertex/` | Consumes `CgVertexFormat` and `CgInstanceLayout` as registry keys and attribute layout sources for VAO configuration |
| `gl/buffer/staging/` | `CgVertexWriter` is driven by `CgVertexFormat` semantics; `CgInstanceWriter` is driven by `CgInstanceLayout` |
| `gl/render/` | `CgBatchRenderer` and `CgInstancedBatchRenderer` use these as format identifiers |
