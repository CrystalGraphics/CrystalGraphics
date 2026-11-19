# render/ui — the UI canvas's backend

The engine behind `api.canvas.CgCanvas` (`docs/UI_CANVAS.md`). P4.1 builds it step by step; the old canvas
(`render/canvas`) lives until UI14.

| Path | Owns |
|---|---|
| `CgUiRecorder` | Element records into a recording's `LOOKBACK` raster passes: one draw per run of material and texture, one chunk per spatial node; its tape is what a `CgPaintChunk` captures |
| `element/CgUiElements` | The 96-byte element record (`CgInstanceKind.ELEMENT`): its field offsets, fill kinds, flags and writers |
| `property/` | The property trees a recording carries: spatial nodes, effect nodes, the palette a frame executes them as. Their values per frame, `CgPropertyValues`, are the graph's (`render/graph`) |

## Rules

- **The record has three mirrors**: `CgInstanceKind.elementFormat()`, `CgUiElements`' offsets and flags, and
  `canvas/primitives/element.glsl` + `Element.primitive`. Change one, change all three; `CgUiElementsTest` pins the size.
- **A zero record must stay neutral**: opacity is stored as its fade and intensity as its boost over 1.
- **Batching is `CgBatcher`'s.** Bound every draw tightly; never merge draws here.
- **An element draw is pulled**: one instance over six vertices a record, from a data-less mesh. The encoder draws
  its submesh 0 with the batch's range; the whole mesh would draw every record after the batch's first.
- **A chunk names only what it made**, besides the clip and node it began under: replay renumbers those alone, so an
  outer clip or node, a one-frame texture or a new pass refuses the capture (`CgPaintChunk.Refusal`).
- **Hot paths allocate nothing**: a canvas reuses its record, its box builder and its tile sink.
