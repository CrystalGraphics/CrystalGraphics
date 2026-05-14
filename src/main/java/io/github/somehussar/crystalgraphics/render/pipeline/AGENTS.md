# render/pipeline — CrystalGraphics Pipeline Renderer Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

## What This Package Is

Concrete pass renderers for the CrystalGraphics forward pipeline. Each renderer owns
one pass in the `CgRenderPipeline.execute()` sequence.

## Class Map

| Type | Role |
|------|------|
| `CgDepthPrepassRenderer` | Fills the depth buffer before the opaque forward pass. Path 1 (solid): shared engine depth shader. Path 2 (alpha-test): `bindForVariant(FORWARD)` + `colorMask=false`. Returns `true` if drew → forward pass uses `GL_EQUAL`; returns `false` → forward pass uses `GL_LEQUAL`. |
| `CgForwardRenderer` | Opaque auto-instanced forward pass. Front-to-back sorted. Merges consecutive same-material+same-mesh commands into one `drawInstanced(N)` call (`canMerge()` by identity). Uses `drawChain(FORWARD, drawCall)`. |
| `CgTransparentRenderer` | Back-to-front transparent pass. No auto-instancing (per-object depth order). `drawDirect()` per command (not `drawInstanced(1)`). ZWrite OFF, Blend ON. |

## Ownership Rules

- All renderers are owned by `CgRenderPipeline` (created in constructor, no singletons)
- Render state per-material is owned by `CgMaterial.doBind()` — do NOT call `rs.apply()` / `rs.clear()` externally
- GL state overrides (colorMask, depthMask, blend) are applied AFTER `bindForVariant()` for path-2 depth prepass
- `CgBufferWriter.vec4(String, Vector4f)` exists — use it for `custom0-3` writes
