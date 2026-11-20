# render/ui — the UI canvas's backend

The engine behind `api.canvas.CgCanvas` (`docs/UI_CANVAS.md`): its records, recorder, paths, SVG, backdrop and
property trees. `CgCanvasPrimitives` holds its path materials (curves and the coverage rasters).

| Path | Owns |
|---|---|
| `CgUiRecorder` | The canvas's records into a recording's `LOOKBACK` raster passes: elements and a text item's glyph quads, one draw per run of kind, material, state and texture, one chunk per spatial node; its tape is what a `CgPaintChunk` captures |
| `element/CgUiElements` | The 96-byte element record (`CgInstanceKind.ELEMENT`): its field offsets, fill kinds, flags and writers |
| `path/` | A path's geometry and its cache: flattening (Wang's formula), the trapezoid triangulator, the stroker and dasher, the cell records, and the shared coverage atlas with its publication rule (`CgPathCache`) |
| `svg/` | `api.vector.svg.CgSvg`'s inside, ported from CrystalGUI: the scanner, resolver, paths, transforms, styles, colours and gradients that make a scene; the tessellator and `CgSvgOps` that make its draw ops; `CgSvgCells`, an op's coverage as cell records |
| `backdrop/CgBackdrop` | What is behind a pane: the target's pixels under it copied, blurred on the 1/2/4 ladder. The canvas's `backdropFilter` draws over it |
| `property/` | The property trees a recording carries: spatial nodes, effect nodes, the palette a frame executes them as. Their values per frame, `CgPropertyValues`, are the graph's (`render/graph`) |

## Rules

- **The record has three mirrors**: `CgInstanceKind.elementFormat()`, `CgUiElements`' offsets and flags, and
  `canvas/primitives/element.glsl` + `Element.primitive`. Change one, change all three; `CgUiElementsTest` pins the size.
- **A zero record must stay neutral**: opacity is stored as its fade and intensity as its boost over 1.
- **Batching is `CgBatcher`'s.** Bound every draw tightly; never merge draws here.
- **An element draw is pulled**: one instance over six vertices a record, from a data-less mesh. The encoder draws
  its submesh 0 with the batch's range; the whole mesh would draw every record after the batch's first.
- **Text lands as glyph quads** (`CgInstanceKind.QUAD`, `CgQuadRecord`) beside elements, through `CgTextBatch.Target`:
  the batch bakes the canvas's transform into each quad, so text takes no spatial node of its own. A chunk keeps both
  kinds; an element's clip is an int's bits, a glyph quad's the int as a float.
- **A path element carries its paint frame in the radii's place** (`CgUiElements.paintFrame`, `FLAG_COVERAGE`): its
  coverage is the texture's alpha at `uv`, so a path takes no corners, no border, and no paint that needs `uv` itself.
- **A path cell is drawn only by its claimer until its build has executed** (`CgPathCache`); anything else that meets
  it pending rasterises its own. Never draw from a cell you did not see ready or claim.
- **An SVG op shares the path cache** under its own key (tier, op, coloured, stroke half-width): a fill in its own colours
  accumulates premultiplied (`colourCoverage`) and draws as a premultiplied texture, every other op white coverage
  under its colour. The tessellation is the reference: change `svg/` and score `steps/UI7/truth.py` before and after.
- **A chunk names only what it made**, besides the clip and node it began under: replay renumbers those alone, so an
  outer clip or node, a one-frame texture or a new pass refuses the capture (`CgPaintChunk.Refusal`).
- **A layer's draws blend by their blend's layer form** (`CgUiRecorder.layered`, `CgBlendState.layered`): a straight-
  alpha draw into a picture composited later must leave its coverage in alpha, not its square. A blend that only scales
  what is there (`MASK`) is its own form; one with none (a multiply) is refused inside a layer.
- **A layer's pass draws at its picture's origin** (`CgUiRecorder.view`, the pass's view origin) under a fresh identity
  node; the outer clip and nodes apply once, at its composite.
- **A kept walk is a layer over a requested texture** (`api.canvas.CgKeptLayer`): its first pass clears, and on a
  walk that is not whole every pass into the texture takes the damage (`CgRasterPass.damage`), so the clear is cut
  too. A dropped walk invalidates the layer: what it noted never reached the texture.
- **Hot paths allocate nothing**: a canvas reuses its record, its box builder and its tile sink.
