# widget — UMG's widget trees

A `widgets/` asset is a widget blueprint's tree: named widgets laid out by Slate's rules and painted onto a canvas, its
parts animated by name on the widget sequencer. **Using it**: `docs/WIDGETS.md`.

| Class | Owns |
|---|---|
| `CgWidget` | one widget as designed (`UWidget`) and its builder (canvas, horizontal box, border, scale box at a user scale, retainer box, image, text block, user widget): kind, name, slot, render transform, pivot, opacity, visibility, colours, brush, text and style, a nested tree |
| `CgWidgetSlot` | a panel slot; unset fields resolve by the parent's kind, as each UMG slot class has its own defaults |
| `CgWidgetBrush` | `FSlateBrush`: a texture, an SVG or a UI-domain material (`Primitive Element`), image size, tint, draw type |
| `CgLinearColour` | `FLinearColor`; `argb` decodes sRGB, `toArgb` encodes |
| `CgWidgetTree` | the compiled tree, shared: depth-first arrays, names unique, slots resolved, a canvas's paint order |
| `CgUserWidget` | an instance (`UUserWidget`): the live state, Slate's layout on change, `paint(canvas)` onto `api.canvas.CgCanvas`, kept as a paint chunk and replayed while its revision holds (a move or a fade a node's value, never a repaint; `repaints()`); a sequence target whose parts are its widgets by name, a nested user widget reached whole; its own animations (`playAnimation`, one player each, restarted in place; `playAnimationTo` a stretch of one, `animationCurrentTime`; `onAnimationFinished`), UMG's `PlayAnimation` and `PlayAnimationTo`; per-instance material parameters (`materialParameters`, animatable as `material_parameters`), its brush material's `_Parameters` on an instance it owns; a retainer box's `blur` (`blur(name, sigma)`, animatable as `blur`), a layer with a blur filter |
| `CgWidgetCodec` | the kind's compiler and writer |
| `CgWidgetPreparer` | a widget asset's UI materials named to the precache before it is READY (its styles' faces are `text.style.CgTextStylePreparer`'s) |
| `CgUiScale` | a kit's `ui.yaml`, `UUserInterfaceSettings`' DPI curve |
| `CgWorldWidgetLayer`, `CgWidgetComponent` | widgets following points in the world (`SWorldWidgetScreenLayer`, a screen-space `UWidgetComponent`): queued, started at the layer's `PLAYS` tick, placed by pivot and draw size at the projected point, nearer last, painted at `HUD`'s `WORLD_WIDGETS`; released when their sequence ends or the level changes |
| `CgUserWidgetPool` | `FUserWidgetPool`: instances by tree, handed back reset |
| `CgScreenWidgetLayer` | widgets on the screen (`AddToViewport`): each laid out at the whole screen in its kit's UI units, painted by z-order, taken off at a level change; drawn on `HUD` at `SCREEN_WIDGETS` |

## Rules

- **Slate decides**: each layout and paint step cites the Slate or UMG source it follows (`SConstraintCanvas`,
  `SBoxPanel`, `LayoutUtils.h`'s `AlignChild`, `SCompoundWidget`, `SBorder`, `SImage`, `Geometry.h`,
  `WidgetTransform.h`). A behaviour Slate has is ported, never approximated.
- **Tints are linear** and multiply down the tree in linear; they are sRGB-encoded once, at the box, as Slate's
  `PackVertexColor`.
- **Layout runs on change only** (text, visibility, a brush, an allotted size, a nested widget's); what a sequence
  writes is render-only. Paint allocates nothing.
- **A change bumps the paint revision** unless it is a node's: a move (translation, scale, shear, angle) or a fade
  (render opacity, a leaf's colour alpha, a border's content alpha) puts the widget in a node once, then only writes
  its value. Every setter and sequence write compares before storing, so a held value changes nothing. A tree with a
  retainer box is never kept (its layer is a pass).
- **Nothing is drawn before its animation's first frame** (`UMGSequencePlayer::PlayInternal`): a component starts in
  its layer's `PLAYS` tick, before `SEQUENCES`, and is drawn only once started. Evaluating one player alone at its
  start is not possible: the blender's apply restores every channel another player left.
- **A widget's animations are its own**: `playAnimation` keeps one player per animation and binds every role to the
  widget; a component or layer never holds a player of its own.
- **A brush material draws through an instance the widget owns** (UMG's dynamic material instance), over the
  shared material the preparer precaches as `ELEMENT`.
- **A kind is added only where a game's widget needs it** (the census in the format's widget section); one the engine
  cannot draw fails at load, by name, never draws as something else.
- Tests record onto a canvas under a null device and read the elements back (`core/src/test`, `Painted`); text is
  measured with a real font. Gates: `CgUserWidgetTest`, `CgWidgetCodecTest`, `CgWorldWidgetLayerTest`,
  `CgScreenWidgetLayerTest`, and the harness's `ui-widgets`.
