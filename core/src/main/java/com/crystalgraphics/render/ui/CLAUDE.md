# render/ui — the UI canvas's backend

The engine behind `api.canvas.CgCanvas` (`docs/UI_CANVAS.md`). P4.1 builds it step by step; the old canvas
(`render/canvas`) lives until UI14.

| Path | Owns |
|---|---|
| `CgUiRecorder` | Element records into a recording's `LOOKBACK` raster passes: one draw per run of material and texture, one chunk per spatial node |
| `element/CgUiElements` | The 96-byte element record (`CgInstanceKind.ELEMENT`): its field offsets, fill kinds, flags and writers |
| `property/` | The property trees a recording carries: spatial nodes, effect nodes, the palette a frame executes them as |

## Rules

- **The record has three mirrors**: `CgInstanceKind.elementFormat()`, `CgUiElements`' offsets and flags, and
  `canvas/primitives/element.glsl` + `Element.primitive`. Change one, change all three; `CgUiElementsTest` pins the size.
- **A zero record must stay neutral**: opacity is stored as its fade and intensity as its boost over 1.
- **Batching is `CgBatcher`'s.** Bound every draw tightly; never merge draws here.
- **Hot paths allocate nothing**: a canvas reuses its record, its box builder and its tile sink.
