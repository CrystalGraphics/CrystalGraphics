# render/pipeline — CrystalGraphics Pipeline Renderer Implementations

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

## What This Package Is

Concrete pass renderers for the CrystalGraphics forward pipeline. Each renderer owns
one pass in the `CgRenderPipeline.execute()` sequence.

## Class Map

| Type | Role |
|------|------|
| `CgDepthPrepassRenderer` | Auto-instanced depth prepass renderer. Fills the depth buffer before the opaque forward pass. Uses a `while` + `canMerge()` run-finder (same criterion as `CgForwardRenderer`, including `preDrawHook ==` check) to merge consecutive same-material+same-mesh+same-hook commands into one `drawInstanced(N)` call. **Primary path**: `bindForPass(DEPTH)` for materials with a compiled depth variant (`hasCompiledDepthPass()` = true). **Fallback path (alpha-test only)**: `bindForPass(FORWARD)` + `colorMask=false` for alpha-test materials without a compiled depth variant. Solid geometry with no compiled depth variant is skipped (batch advances without drawing). Fires `CgPreDrawHook` after `objBuf.endWrite()`, before the bind block (once per batch). Returns `true` if any geometry was drawn. |
| `CgForwardRenderer` | Opaque auto-instanced forward pass. Front-to-back sorted. Merges consecutive same-material+same-mesh+same-hook commands into one `drawInstanced(N)` call (`canMerge()` by identity, including `preDrawHook ==`). Uses `drawChain(FORWARD, drawCall)`. Fires `CgPreDrawHook` **before** `drawChain` (not inside the lambda) so it executes once for the base material only and does NOT re-fire for `nextPass` chain links (outline, glow, etc.) whose GL programs differ from the base. Depth function set to `GL_LEQUAL` at the top of `execute()` — no `GL_EQUAL` override; hardware early-Z handles prepass overdraw elimination. |
| `CgTransparentRenderer` | Back-to-front transparent pass. No auto-instancing (per-object depth order). Uses `drawChain(FORWARD, () -> cmd.mesh.drawDirect())` per command — traverses `nextPass` chains correctly (each chain link draws the same object with its own shader). ZWrite OFF, Blend ON. TODO: additive-blend instancing deferred (see TODO comment on `drawDirect()` line). |

## Ownership Rules

- All renderers are owned by `CgRenderPipeline` (created in constructor, no singletons)
- Render state per-material is owned by `CgMaterial.doBind()` — do NOT call `rs.apply()` / `rs.clear()` externally
- GL state overrides (colorMask, depthMask, blend) are applied AFTER `bindForPass()` for path-2 depth prepass
- `CgBufferWriter.vec4(String, Vector4f)` exists — use it for `custom0-3` writes
