# The UI canvas

`com.crystalgraphics.api.canvas.CgCanvas`: 2D drawing for screens, HUDs and textures. Rectangles filled by any paint
(`docs/PAINT.md`), rounded, bordered and shadowed; images, nine-slices and tiles; under a transform, opacity and clip
stack. Items land in a recording as batched element draws, in paint order.

It replaces the old `render.canvas.CgCanvas` (`CANVAS.md`) step by step through P4.1; text, vector paths, layers and
the widget stack arrive in later steps, and until then those still draw on the old canvas.

## Getting one

```java
CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
    CgCanvas canvas = CgCanvas.on(frame);        // window pixels, from the top-left
    // CgCanvas.onGui(frame)                     // the host GUI's pixels: Minecraft's GUI scale
    canvas.fill(8, 8, 200, 40, 0xC0101418);
});
```

Off a stage, into a texture of your own:

```java
CgCanvas canvas = new CgCanvas();
canvas.begin(recording, target, CgLoad.CLEAR, CgCamera.screen(w, h), 1f);
canvas.box(0, 0, w, h).fill(CgGradient.linear(90).stop(0, a).stop(1, b)).draw();
canvas.end();
```

## Items

```java
canvas.fill(x, y, w, h, 0xFF2060C0);                         // one colour
canvas.fill(x, y, w, h, CgGradient.radial(0.5f, 0.5f, 0.5f, 0.5f).stop(0, glow).stop(1, 0));
canvas.fill(x, y, w, h, CgGrid.checker(0xFFC0C0C0, 0xFF404040).cell(8));

canvas.box(x, y, 160, 32)                                    // a box: describe, then draw
      .fill(0xFF202630)
      .radius(6)                                             // or radii(CgCornerRadii.of(8, 8, 0, 0)), elliptical too
      .border(1, 0x30FFFFFF)                                 // or per side: widths and colours
      .shadow(CgShadow.of(0, 4, 12, 0x80000000))             // .inset() draws inside the border
      .draw();

canvas.image(texture, x, y, 32, 32);                         // stretched
canvas.image(CgBrush.sprite("mymod:hud", "panel"), x, y, w, h);   // the sheet's nine-slice and tiling
canvas.box(x, y, 16, 16).mask(icon, 0xFFFFD040).draw();      // a silhouette in any colour
```

## State

```java
canvas.save();
canvas.translate(cx, cy).rotate(30).scale(2, 2);
canvas.opacity(0.5f).intensity(4f);                          // intensity: past 1 on an HDR target
canvas.clip(0, 0, 64, 64, CgCornerRadii.of(8));              // intersects the clip now
canvas.image(icon, -16, -16, 32, 32);
canvas.restore();
```

## What to know

- **Paint order holds.** A later item draws over an earlier one it overlaps; items that overlap nothing between them
  join one draw whatever their order, so interleaving looks costs nothing where they do not touch.
- **Axis-aligned edges snap to device pixels** (`pixelSnap(false)` turns it off); rotated items antialias instead.
  Translation and positive scale bake into each item; rotation and skew cost a spatial node, up to 4096 a recording.
- **Square clips intersect; rounded or rotated ones nest**, at most `CgClipTable.MAX_DEPTH` deep past the square ones.
- **A box reuses one builder**: describe and draw it in one statement.
- **Colours are `0xAARRGGBB`, straight alpha.** Gradients interpolate premultiplied.
- **A material of your own** is a `#domain ui` material of `Primitive Element`: its `surface()` runs over the item's
  paint (`s.color`, premultiplied), and `box(...).material(m)` draws with it.
- **One thread draws a canvas at a time**; separate canvases record concurrently.

## Inside

The front writes one 96-byte record per item (`render.ui.element.CgUiElements`): rect, uv, elliptical radii, border
widths and colours, colour, fill kind, nodes, clip, opacity and intensity, paint. `render.ui.CgUiRecorder` puts runs
of one material and texture into chunks of a `LOOKBACK` raster pass, and the frame's `CgBatcher` joins draws that
overlap nothing between them (Skia's bounds-checked batching). An element batch is one instance over six vertices a
record of a data-less mesh; `canvas/primitives/Element.primitive` pulls its record by vertex index and paints it.
