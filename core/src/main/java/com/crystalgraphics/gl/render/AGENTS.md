# gl/render — the instanced renderers

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What this package is

The two renderers everything 2D draws through, and the tables their records name. Each queues instance
records on the CPU and turns them into recorded draws (`CgInstanceRun`): inside a recording they go into it, and
outside one a `flush()` draws them at once through `CgImmediate`, the frame graph's executor.

| File | Role |
|------|------|
| `CgQuadRenderer.java` | Instanced quads. Per-instance data (`origin`/`right`/`up`/UVs/colour/atlasLayer/node/clip) lives in a class-wide SSBO/TBO at `CgBindingPoints.QUAD_RENDERER`, not in vertex attributes. Fluent `quad()…submit()` queues; `flush()` draws. Backs `CgTextRenderer` and all of CrystalGUI's box drawing. |
| `CgVectorRenderer.java` | Its twin for Bézier strokes, filled triangles and cells, over the curve record (`#pragma cg_use curve`). |
| `CgInstanceRun.java` | A renderer's queued records as recorded draws: one draw per run under one `useMaterial`, under the material's pipeline and a snapshot of it; textures bound by hand laid over the material's samplers. |
| `CgAbstractRenderer.java` | The shared `begin()`/`end()`/`isDirty()`/`delete()` lifecycle of the two renderers. |
| `CgClipTable.java` | A recording's rounded clips: an instance names an entry and is drawn only inside it (`#pragma cg_use clip`). |
| `CgShapeTable.java` | A recording's box shapes by index -- rounded, bordered, flat, textured, nine-slice -- so every box is a quad of one pipeline (`#pragma cg_use shape`). |
| `CgCurveSplitter.java` | Cubic to quadratic splitting for `CgVectorRenderer`, GPU-free. |

### A renderer's shader declares its buffer

```glsl
#type none
#pragma cg_use quad     // QUAD_DATA / CG_QUAD_WORLD_POS / CG_QUAD_UV / CG_QUAD_COLOR
```

The pragma attaches the buffer during parsing, before anything can compile, and the parser rejects a shader that
uses those symbols without it. `useMaterial(material)` is still required before `submit()`: it names the material
the next records are drawn with.

> There is deliberately **no** `attachTo(material)` helper -- one existed and was removed. Attaching from Java runs
> whenever the caller does, which loses to any path that compiles the shader earlier (`CgMaterial.enableKeyword`
> recompiles on the spot when the shader is unparsed), and the undeclared `QUAD_DATA` surfaced as an unrelated
> *"Keyword 'X' is not declared as `#pragma cg_feature`"*.

## What went

The pre-graph batch and instancing stacks -- `CgBatchRenderer`, `CgBufferSource`, `CgRenderLayer`,
`CgDynamicTextureRenderLayer`, `CgLayer`, `CgInstanceRenderer`, `CgQuadInstanceRenderer` -- were deleted with the
render graph's mesh rewrite (M0, 2026-10-02): nothing drew through them once the graph recorded every draw.
