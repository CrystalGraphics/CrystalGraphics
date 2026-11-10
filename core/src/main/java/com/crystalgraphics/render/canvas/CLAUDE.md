# render/canvas — 2D draws into the frame graph

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What this package is

`CgCanvas` is what every quad, curve, triangle, cell and glyph draws into: Unreal's `FCanvas` over its render graph.
An item names its material, state and textures; consecutive matching items are one draw; order is kept across
kinds. A canvas records into raster passes, or, recording nothing, draws at `flush()` through `CgImmediate`. The
same canvas serves the world and the screen: a stage's frame hands one out (`CgStageFrame.canvas()`), under that
firing's camera. **Using it: [`docs/CANVAS.md`](../../../../../../../../docs/CANVAS.md).**

| File | Role |
|------|------|
| `CgCanvas.java` | The canvas: draw builders, `text()`, set-target/scissor/constants/view, its camera (`cameraProjection/View/Width/Height`) and `visible(...)` cull, replay's tape. Its javadoc has the examples |
| `CgQuadBatch.java` | Quad items: the `Quad` builder and the class-wide instance buffer (`#pragma cg_use quad`) |
| `CgCurveBatch.java` | Curve, triangle and cell items over the curve record (`#pragma cg_use curve`) |
| `CgInstanceRun.java` | One batcher's open run: breaks on material, its `changes()`, state, textures or a stale binding snapshot; seals into the recording or `CgImmediate` |
| `CgCanvasMaterials.java` | The default materials, `SCREEN` and `WORLD`: image, mask, their 2D-array forms, curve, box, and screen coverage rasters; one shared instance per kind and blend, `newBox`/`newCurve` for a caller's own; the blend the material state's. Their shaders are `shaders/canvas/` (with `text.shader`), and `lib/canvas.glsl` |
| `CgCanvasGradient.java`, `CgCanvasGrid.java`, `CgCanvasGlass.java` | A gradient (CSS's line or a radial, eight stops a draw), a grid and a pane of glass (its `Look` presets): each owns its material and packs its properties for the next quad. A thumbnail is no canvas draw: `CgGpuOps.shrink` |
| `CgCanvasBackdrop.java` | What is behind a pane of glass: a copy of the pixels a quad covers and its blur, captured between the canvas's passes (`CgCanvas.breakPass`), on the screen or in the world. It owns the two textures |
| `CgCanvasRetainer.java` | UMG's Retainer Box: items painted through a canvas of its own into a requested texture, cleared transparent, then drawn from it as quads, under the premultiplied image (fades as one picture) or the additive mask (its silhouette glows); `blur` a region by a sigma, `CgGpuOps.blurScaled` into a texture per reduction, drawn from with `quad(..., reduce)`. The one sanctioned canvas of your own inside a firing: it draws into its texture alone |
| `CgCanvasLabels.java` | Text kept on the GPU across frames, each piece placed by a matrix of its own (`CgWorldRenderer.labels()`, which `world.text` queues on): a queue of `Label`s, each a point, height, anchor and turn over a queued `CgTextDraw`, placed per firing under the host view (`prepare`). Label i keeps a copy of its draw and its `CgTextCapture`, captured again only when the draw differs (`CgTextDraw.sameAs`), the capture was incomplete or the atlas evicted. Records live in one persistent buffer per (rank, batch), label i's in ranges whose `node` is i; a changed label rewrites its ranges in place where it fits, else appends and leaves holes (zeroed records), compacted past 4,096 and half; a label gone keeps its ranges hidden for 300 frames. `prepare` uploads the dirty spans and every label's matrix (an OBJECT record); `drawDepth` draws ranks 0 to 3 into depth alone (`drawCapturedDepth`, alpha 0.5 and up), `draw` every batch (`drawCaptured`), linear where the canvas's target is float |
| `CgQuadRecord.java` | The quad record's float offsets, for code that bakes records itself (`CgTextBatch`, `CgCanvasLabels`) |
| `CgClipTable.java` | A recording's rounded clips (`#pragma cg_use clip`) |
| `CgShapeTable.java` | A recording's box shapes, so every box is a quad of one pipeline (`#pragma cg_use shape`); `canvas.shapes()`, drawn by `CgCanvasMaterials.box()` |
| `CgCurveSplitter.java` | Cubic to quadratic splitting, GPU-free |

## Rules

- **Nothing to begin, end, delete or tear down.** A canvas holds no GPU object; the instance buffers are the engine's
  (`CgEngineBufferRegistry`) and live on the frame ring.
- **An item draws under its material as it is when submitted.** `CgMaterial.changes()` moves on every mutator, and a
  change between two items starts a draw. A material written past its mutators (a block written in place) must be
  re-given (`overrideBlock`) or the run keeps the old values.
- **One active batcher**: a quad after a curve seals the curve's run first, so painter's order holds without flushes.
- **Inside a stage firing, draw into `frame.canvas()`**, never a canvas of your own: it opens lazily on the firing's
  target and constants, so a renderer that draws nothing records nothing. Code drawing for its caller takes the
  canvas as a parameter. The exception is `CgCanvasRetainer`'s, which draws into its own texture alone.
- **Text is an item** (`text/render/CLAUDE.md`): `text()` lands a draw's quads through the canvas's `CgTextBatch`,
  made on first use. It reads the canvas's camera at each draw — the recording's constants, else the armed source's,
  else `CgImmediate.constants()` — so an immediate canvas's camera changes only between a `flush()` and the next draw.
- **`visible(pose, box)` is the canvas's frustum cull**, against its camera; text culls each draw with it.
- **World space is camera-relative**: a stage's canvas has the camera as `origin`; `Quad.world(x, y, z)` places at
  an absolute position in doubles.
- The pragma declares a record during parsing, before anything compiles. There is deliberately no Java-side attach:
  one existed and lost to any path that compiled the shader earlier.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
