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
| `CgCanvas.java` | The canvas: draw builders, set-target/scissor/constants/view, the per-thread current canvas, replay's tape. Its javadoc has the examples |
| `CgQuadBatch.java` | Quad items: the `Quad` builder and the class-wide instance buffer (`#pragma cg_use quad`) |
| `CgCurveBatch.java` | Curve, triangle and cell items over the curve record (`#pragma cg_use curve`) |
| `CgInstanceRun.java` | One batcher's open run: breaks on material, its `changes()`, state, textures or a stale binding snapshot; seals into the recording or `CgImmediate` |
| `CgQuadRecord.java` | The quad record's float offsets, for code that bakes records itself (`CgTextRenderer`, `CgWorldText`) |
| `CgClipTable.java` | A recording's rounded clips (`#pragma cg_use clip`) |
| `CgShapeTable.java` | A recording's box shapes, so every box is a quad of one pipeline (`#pragma cg_use shape`) |
| `CgCurveSplitter.java` | Cubic to quadratic splitting, GPU-free |

## Rules

- **Nothing to begin, end, delete or tear down.** A canvas holds no GPU object; the instance buffers are the engine's
  (`CgEngineBufferRegistry`) and live on the frame ring.
- **An item draws under its material as it is when submitted.** `CgMaterial.changes()` moves on every mutator, and a
  change between two items starts a draw. A material written past its mutators (a block written in place) must be
  re-given (`overrideBlock`) or the run keeps the old values.
- **One active batcher**: a quad after a curve seals the curve's run first, so painter's order holds without flushes.
- **Inside a stage firing, draw into `frame.canvas()`**, never a canvas of your own: it opens lazily on the firing's
  target and constants, so a renderer that draws nothing records nothing. Code drawing for its caller (the text
  renderer) reads `CgCanvas.current()`.
- **World space is camera-relative**: a stage's canvas has the camera as `origin`; `Quad.world(x, y, z)` places at
  an absolute position in doubles.
- The pragma declares a record during parsing, before anything compiles. There is deliberately no Java-side attach:
  one existed and lost to any path that compiled the shader earlier.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
