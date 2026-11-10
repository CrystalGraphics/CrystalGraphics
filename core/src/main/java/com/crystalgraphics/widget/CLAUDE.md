# widget — UMG's widget trees

A `widgets/` asset is a widget blueprint's tree: named widgets laid out by Slate's rules and painted onto a canvas, its
parts animated by name on the widget sequencer. **Using it**: `docs/vfx/widgets.md`.

| Class | Owns |
|---|---|
| `CgWidget` | one widget as designed (`UWidget`) and its builder: kind, name, slot, render transform, pivot, opacity, visibility, colours, brush, text and style, a nested tree |
| `CgWidgetSlot` | a panel slot; unset fields resolve by the parent's kind, as each UMG slot class has its own defaults |
| `CgWidgetBrush` | `FSlateBrush`: a texture or a UI-domain material, image size, tint, draw type |
| `CgLinearColour` | `FLinearColor`; `argb` decodes sRGB, `toArgb` encodes |
| `CgWidgetTree` | the compiled tree, shared: depth-first arrays, names unique, slots resolved, a canvas's paint order |
| `CgUserWidget` | an instance (`UUserWidget`): the live state, Slate's layout on change, `retain` (each retainer box's content into the painter's retained image) then `paint`; a sequence target whose parts are its widgets by name, a nested user widget reached whole |
| `CgWidgetPainter`, `CgCanvasWidgetPainter` | where leaves go; the canvas one draws images as quads (a UI material's where the brush has one), text through `canvas.text()`, and retainer boxes through a `CgCanvasRetainer` it owns, its regions packed in rows each frame |
| `CgWidgetCodec` | the kind's compiler and writer |
| `CgUiScale` | a kit's `ui.yaml`, `UUserInterfaceSettings`' DPI curve |

## Rules

- **Slate decides**: each layout and paint step cites the Slate or UMG source it follows (`SConstraintCanvas`,
  `SBoxPanel`, `LayoutUtils.h`'s `AlignChild`, `SCompoundWidget`, `SBorder`, `SImage`, `Geometry.h`,
  `WidgetTransform.h`). A behaviour Slate has is ported, never approximated.
- **Tints are linear** and multiply down the tree in linear; they are sRGB-encoded once, at the painter, as Slate's
  `PackVertexColor`.
- **Layout runs on change only** (text, visibility, a brush, an allotted size, a nested widget's); what a sequence
  writes is render-only. Paint allocates nothing.
- **Retain, then paint**: every instance a frame draws runs `retain` before any runs `paint`, since the retained
  image's pass must be recorded before the canvas reads it; the painter refuses a region opened after a canvas draw.
- **A kind is added only where a game's widget needs it** (the census in the format's widget section); one the engine
  cannot draw fails at load, by name, never draws as something else.
- Headless: layout and the codec need no GL and no fonts (a test answers `CgWidgetPainter.layout`); only
  `CgCanvasWidgetPainter` draws. Gates: `CgUserWidgetTest`, `CgWidgetCodecTest`.
