# api/vertex — Public Vertex Format APIs

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Public, GL-free vertex format descriptors: pure data, no GL calls, no shader coupling. A mesh's
vertices are laid out by one, and a `.shader` file names one by its `#type`.

Per-instance data is not a vertex format: it is an engine buffer's record (`CgInstanceKind`), read
through `CG_INSTANCE_ID`.

## Type Map

| Type | Role |
|------|------|
| `CgVertexFormat` | Immutable, hashable vertex format descriptor. Value-equal by attribute list + stride. Builder creates sequential attribute slots. **Auto-registers under `key name` on `build()`** — the `key name` doubles as the `#type` key in `.shader` files. `forShaderType(String)` looks up a registered format; `registeredShaderTypes()` returns all known names. Pre-defined: `POS2_UV2_COL4UB`, `POS3_UV2_COL4UB`, `SPATIAL` (registered as `"spatial"`, CrystalShader pipeline: cg_Position/cg_TexCoord0/cg_Normal, 32 bytes). Registry collision (same name, different layout) throws `IllegalStateException` at second `build()`. |
| `CgVertexAttribute` | Single attribute within a format: name, type, components, offset, normalized flag, semantic metadata. Package-private constructor. Value-equal. `getGlslType()` derives the GLSL type string from `(type, components, normalized)`: float family for FLOAT, HALF_FLOAT or normalized types; int/ivec family for signed integer non-normalized; uint/uvec family for unsigned integer non-normalized. Throws `IllegalStateException` on unmapped combos. |
| `CgVertexSemantic` | Enum of attribute roles: `POSITION`, `UV`, `COLOR`, `NORMAL`, `GENERIC`. Used by `CgVertexWriter` for fluent routing. |
| `CgAttribType` | Enum of GL primitive types (`FLOAT`, `HALF_FLOAT`, `UNSIGNED_BYTE`, etc.) with byte sizes. A non-normalised integer attribute (`CgVertexAttribute.isInteger()`) reads as integers: the mesh store sets it with `glVertexAttribIPointer`. |
| `CgVertexConsumer` | Fluent vertex-write interface implemented by `CgVertexWriter`. |
| `CgVertexTransformUtil` | Utility for transforming vertex positions via `PoseStack`. |

## Design Rules

- **No GL calls** in any type in this package. All types are pure data.
- **Value equality** for `CgVertexFormat`: construction from separate call sites with the same attributes
  produces equal formats.
- **Attribute constructor is package-private** — use the builder to create formats; never construct
  `CgVertexAttribute` directly from outside this package.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `api/mesh/`, `render/mesh/` | A mesh's vertices are in one `CgVertexFormat`; the store's pool for it sets its VAO's attributes from it |
| `gl/buffer/staging/` | `CgVertexWriter` is driven by `CgVertexFormat` semantics |
| `gl/material/parse/` | A `.shader` file's `#type` names a registered format |

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
