# The canvas — 2D drawing on the screen and in the world

`CgCanvas` is how anything flat is drawn: a HUD image, a damage number, a health bar over a head, a line, a
filled shape, text. It is Unreal's `FCanvas` over the render graph. You say what each item is (a material,
maybe a texture, where and how big), and the canvas turns runs of matching items into single draws, in the
order you gave them. There is nothing to create, begin, end, flush or delete, and nothing to tear down when
the context goes.

Package: `com.crystalgraphics.render.canvas`. The guide for code inside it is its `CLAUDE.md`; this page is
for code that uses it.

---

## 1. The short version

```java
public static final CgMaterial DIGIT = CgMaterial.load("mymod:shaders/digit.shader");   // #pragma cg_use quad
public static final CgTexture2D DIGITS = CgTextures.of("mymod:textures/hud/digits.png");

CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame ->
        frame.canvas().quad(DIGIT).texture(0, DIGITS)
             .at(x, y).size(w, h).uv(u0, v0, u1, v1).color(0xFFFFD040).submit());
```

That is a whole HUD renderer. The stage hands you its canvas, under its camera and onto its target, and what
you submit lands in that firing.

---

## 2. Where a canvas comes from

| You are | Use | Space |
|---|---|---|
| Inside a stage renderer (`CgRenderStage.register`) | `frame.canvas()` | the stage's: the window's pixels on `HUD`/`SCREEN`, camera-relative world on `WORLD_OPAQUE`/`WORLD_TRANSPARENT` |
| Outside any stage: a preview, a harness scene, the loading screen | `new CgCanvas()`, then `flush()` | `CgImmediate.constants()`, into whatever target is bound |
| Recording a frame of your own (a UI, an offscreen picture) | `new CgCanvas()` with `recordInto(...)` | the constants you give it (§ 8) |

**Inside a stage, always `frame.canvas()`.** It opens lazily on the stage's target at your first item, so a
renderer that draws nothing this frame records nothing, and it is closed for you when your renderer returns.

---

## 3. Quads

```java
CgCanvas canvas = frame.canvas();

canvas.quad(FLAT).at(x, y).size(w, h).color(argb).submit();                          // a coloured box
canvas.quad(IMAGE).texture(0, icon).at(x, y).size(32, 32).submit();                  // an image
canvas.quad(IMAGE).texture(0, atlas).uv(u0, v0, u1, v1).at(x, y).size(w, h).submit(); // a sprite from a sheet
canvas.quad(IMAGE).texture(0, sheet).atlasLayer(3).at(x, y).size(w, h).submit();     // a layer of a 2D array
canvas.quad(BAR).at(x, y).size(w * fill, h).custom0(fill, 0f, 0f, 0f).submit();       // a per-quad parameter
canvas.quad(SPIN).at(-8, -8).size(16, 16).pose(rotation).submit();                   // rotated about its pose's origin
```

| Call | Default | Note |
|---|---|---|
| `size(w, h)` | — | **Required.** Submit throws without it |
| `at(x, y[, z])` | `(0, 0, 0)` | Its corner. `z` translates, it does not tilt |
| `uv(u0, v0, u1, v1)` | the whole texture | Top-left, then bottom-right |
| `color(argb)` | opaque white | `0xAARRGGBB` |
| `texture(unit, texture)` | the material's own | Overrides one sampler unit for this quad |
| `state(renderState)` | the material's | A blend or depth the shader cannot decide |
| `custom0/custom1(x, y, z, w)`, `custom2(v)` | 0 | Free per-quad values: `CG_QUAD_CUSTOM0..2`. Also `custom0(argb)` |
| `pose(matrix)` | none | Baked on the CPU: rotation, scale and skew act as on the four corners |
| `world(x, y, z)` | none | Absolute position in doubles (§ 6) |
| `clip(entry)`, `node(spatial, effect)` | none | For a recorder that keeps clip tables and nodes: CrystalGUI's |

**Build and `submit()` in one expression.** `canvas.quad(...)` returns the canvas's one scratch builder, reset
by the next call; never keep it. To keep a builder across frames, `canvas.retainedQuad()` gives one of your
own, which needs `.material(m)` before each `submit()`.

### The material

A material the canvas draws quads with declares the quad record, and places each corner from it:

```glsl
#type none
#pragma cg_use quad

Properties {
    _MainTex ("Image", sampler2D) = "white"
}

struct v2f { vec2 uv; vec4 color; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF
        DepthTest ALWAYS      // a HUD: over whatever the game left in depth
        DepthWrite OFF
    }
    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv    = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }
    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv) * i.color;
    }
}
```

`crystalgraphics:shaders/hud_image.shader` is exactly this, ready to use. What the vertex stage can read:
`CG_QUAD_WORLD_POS`, `CG_QUAD_UV`, `CG_QUAD_COLOR`, `CG_QUAD_ATLAS_LAYER`, `CG_QUAD_CUSTOM0`, `CG_QUAD_CUSTOM1`,
`CG_QUAD_CUSTOM2`, `CG_QUAD_NORMAL`. Antialiased edges off the pixel grid (a rotated quad) use the
`CG_QUAD_EDGE_*` macros in `shaders/env/buffer/quad.glsl`.

- The `#pragma` is required: a shader reading `CG_QUAD_*` without it is refused at parse.
- Precache it like any other material ([`PRECACHE.md`](PRECACHE.md)), or its first frame waits for the compile.

---

## 4. Curves, triangles and cells

The same canvas draws vector shapes. Each is resolved per pixel from its signed distance, so it is sharp at
any scale and antialiased by the same filter as a quad's edge.

```java
canvas.curve(CURVE).line(x0, y0, x1, y1).width(1f).color(argb).submit();                      // a line
canvas.curve(CURVE).from(x0, y0).via(cx, cy).to(x1, y1).width(3f, 0.5f).colors(a, b).submit(); // tapered, two colours
canvas.curve(CURVE).cubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1).width(2f)
        .cap(CgCurveBatch.CAP_ROUND, CgCurveBatch.CAP_ARROW).submit();                         // a wire with an arrow
canvas.triangle(CURVE).points(x0, y0, x1, y1, x2, y2).color(argb).cornerRadius(2f).submit();
canvas.cell(FILL).points(x0, y0, x1, y1, x2, y2, x3, y3)
        .softEdges(CgCurveBatch.CELL_LEFT | CgCurveBatch.CELL_RIGHT).color(argb).submit();     // one span of a fill
```

- **Widths are half-widths**, in the same units as the points. A `pose(...)` scales them with the points.
- **Caps**: `CAP_BUTT` (default), `CAP_ROUND`, `CAP_SQUARE`, `CAP_ARROW`, set per end.
- **Feather**: the default is antialiasing, a pixel and a half whatever the scale. `feather(4f)` is a deliberate
  soft edge (a glow), and that one scales with the pose.
- **Gradients** on triangles and cells: `gradient(start, end, originX, originY, dirX, dirY)`, per pixel.
- **Cells** are for tessellated fills: mark the edges on the shape's outline `softEdges`, leave the cuts between
  cells hard, and neighbours meet with no seam. A lone shape wants `triangle`.
- The material declares `#pragma cg_use curve`. `crystalgraphics:shaders/curve.shader` is the shipped one.

---

## 5. Order, batching and material values

**Items draw in the order you submit them**, across quads, curves and text alike. You never flush to keep
order.

**Neighbours that match are one draw.** Same material, same `state`, same textures and nothing changed on the
material between them: a hundred digits from one atlas are one draw. A different material or texture starts
a new one. Alternating two materials item by item costs a draw per item, so group where you can. Nothing is
ever wrong for not grouping, only slower.

**An item draws with its material's values as they are when you submit it.** A property set between two
items applies from the second:

```java
material.applyProperties(b -> b.vec4("_Tint", 1f, 0f, 0f, 1f));
canvas.quad(material).at(0, 0).size(10, 10).submit();       // red
material.applyProperties(b -> b.vec4("_Tint", 0f, 0f, 1f, 1f));
canvas.quad(material).at(20, 0).size(10, 10).submit();      // blue, a second draw
```

A value that changes per item belongs in `custom0..2`, which keeps one draw.

---

## 6. On the screen and in the world

**GUI stages (`HUD`, `SCREEN`)**: pick the unit when you take the canvas, once a turn.

| Take | Coordinates | For |
|---|---|---|
| `frame.canvas()` | the window's pixels, origin top left, as Unreal's canvas | your own layout: sized from the window, or fitted to a reference canvas |
| `frame.guiScaledCanvas()` | Minecraft's GUI pixels: scaled by its GUI Scale setting | sitting beside vanilla's interface at its size |

```java
CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
    CgCanvas gui = frame.guiScaledCanvas();                               // every item after is in GUI pixels
    gui.quad(ICON).at(4, 4).size(16, 16).submit();                   // 16 GUI pixels: 64 on the window at scale 4
    gui.text().text("Ready").font(font).at(24, 6).submit();          // still rastered sharp at the window's pixels
});
```

- Both are the same canvas: taking the other one mid-turn switches what follows, and order is kept.
- `frame.host().guiScale()` is the scale itself (1 in the harness), for converting a size by hand.

Pick your layer with the order you register at:

| Order | What it is |
|---|---|
| `CgRenderStage.WORLD_WIDGETS` (-100) | Things that follow a point in the world: damage numbers, name plates, markers. Under every window |
| `CgRenderStage.VIEWPORT_WIDGETS` (0) | The game's interface: CrystalGUI's pinned windows draw here |
| above 0 | Over the windows: a cursor trail, a debug overlay |

Something that follows a point in the world projects it with the world's camera:

```java
CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
    CgHostView view = CgRenderStage.WORLD_OPAQUE.host().view();
    // project (x, y, z) - view's position through view and projection into the window's pixels, then:
    frame.canvas().quad(DIGIT).texture(0, DIGITS).at(sx - w / 2, sy - h / 2).size(w, h).submit();
});
```

**World stages (`WORLD_OPAQUE`, `WORLD_TRANSPARENT`)**: the canvas is in the world, camera-relative, under the
firing's camera, so what you draw is depth-tested against the world (by your material's `RenderState`).
`world(x, y, z)` places an item at an absolute position in doubles, exact far from the origin; `at` and `pose`
then place it from there:

```java
CgRenderStage.WORLD_TRANSPARENT.register(0, frame ->
        frame.canvas().quad(GLOW).world(x, y + 2, z).pose(facingCamera)
             .at(-0.5f, -0.5f).size(1f, 1f).color(0x80FFFFFF).submit());
```

A shape in the world is drawn by its distance in the canvas's XY plane: give it a pose that turns that plane
to face where you want. For meshes, lights and anything with volume, use `CgWorldRenderer`
([`ENGINE_API.md`](ENGINE_API.md) § *CgWorldRenderer*).

---

## 7. Text

Text is an item like any other: `canvas.text()` returns a draw, and its `submit()` lands it in order with your
quads.

```java
CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS + 1, frame -> {
    CgCanvas canvas = frame.canvas();
    canvas.quad(PANEL).at(8, 8).size(120, 24).color(0xC0101418).submit();
    canvas.text().text("Ready").font(font).at(14, 12).color(0xFFFFFFFF).submit();     // over the panel
    canvas.text().text(name).family(family).at(14, 40)
            .stroke(0.08f, 0xFF000000).shadowCount(1).shadow(0, 1f, 1f, 1.5f, 0f, 0x80000000, false).submit();
});

CgTextDraw fps = canvas.retainedText().font(font).at(8, 8);       // held across frames, on that canvas
fps.text(Integer.toString(currentFps)).submit();                   // each frame
float height = fps.measure().totalHeight();                        // without drawing
```

- **It draws under the canvas's camera.** It rasters at the target's device pixels whatever the pose's scale, so
  text scaled up stays sharp; on a world stage it is world text, distance-field and sized by how big it is on screen.
- **It is culled** against the camera before anything is resolved: off-screen text costs a frustum test.
- **`submit()` returns false** while a glyph or shadow is still building on a worker; it draws what is ready,
  and the rest arrives a frame or two later.
- **A draw's shadows, outline and fill layer within the draw**; across draws, submit order holds: a later label
  covers an earlier one, shadow and all.
- `canvas.text()` is a scratch like `quad()`: build and submit in one expression.
- Labels in the world that persist and move have their own path, `CgWorldRenderer.get().text(...)`: billboarded
  or turned, depth-tested, kept on the GPU across frames ([`ENGINE_API.md`](ENGINE_API.md)).
- Outlines, shadows, fonts and layout: [`font/README.md`](font/README.md).

---

## 8. A canvas of your own

Outside any stage, a canvas that is not recording draws at `flush()`, into the target bound now, under
`CgImmediate.constants()`:

```java
CgPassConstants screen = CgImmediate.constants();
screen.view.identity();
screen.projection.identity().ortho(0, w, h, 0, -1, 1);
screen.resolution(w, h).cameraFromView();

canvas.quad(IMAGE).texture(0, logo).at(x, y).size(lw, lh).submit();
canvas.quad(BAR).at(bx, by).size(bw * progress, bh).submit();
canvas.flush();                                   // drawn now, in order
```

Keep one canvas in a field and reuse it; it holds no GPU object.

**Recording into a frame graph** (what CrystalGUI's paint context does): the canvas is a command buffer's
set-target, set-constants and set-scissor.

```java
CgRecording frame = new CgRecording();
CgCanvas canvas = new CgCanvas();
canvas.recordInto(frame, target, CgLoad.clear(0, 0, 0, 0), screen);   // passes on target from here
canvas.pushScissor(x, y, w, h);                                         // clips what follows
canvas.quad(BOX).at(x, y).size(w, h).submit();
canvas.popScissor();
canvas.recordInto(frame, layer, CgLoad.clear(0, 0, 0, 0), layerOrtho); // another target; the pass ends
canvas.recordInto(frame, target, CgLoad.load(), screen);                // back, after the layer
canvas.stop();                                                           // everything lands
CgImmediate.execute(frame);
```

- Every state call (target, constants, scissor, view) lands what was drawn before it under the old state.
- `abandon()` throws away what was recorded, for a frame you will not execute.
- One thread at a time per canvas; any thread may record.

---

## 9. Easy to get wrong

| Mistake | What happens | Do instead |
|---|---|---|
| Holding `canvas.quad(...)`'s builder | The next `quad()` resets it under you | Build and submit in one expression, or `retainedQuad()` |
| No `size(...)` | `submit()` throws | Always give a size |
| A shader without `#pragma cg_use quad` / `curve` | Refused at parse | Add the pragma |
| A `new CgCanvas()` inside a stage renderer | Draws immediately, outside the stage's order and target | `frame.canvas()` |
| A new canvas every frame | Works, but allocates | Keep one in a field |
| Changing `CgImmediate.constants()` between an immediate canvas's draws | Text read the camera at each draw, quads at `flush()`: they disagree | `flush()` first, then change the camera |
| Writing a material's block in place between items | Earlier items draw with the new values | Change it through `applyProperties`/`overrideBlock`, or per item with `custom0..2` |
| A `world(...)` item on a GUI stage | The GUI canvas has no camera origin: placed as if at 0 | Project to pixels (§ 6) |
| Forgetting to precache the material | Its first frame waits for the compile | [`PRECACHE.md`](PRECACHE.md) |

---

## 10. Coming from the old renderers

| Before | Now |
|---|---|
| `CgQuadRenderer.create()` | `frame.canvas()`, or a `new CgCanvas()` kept in a field |
| `renderer.useMaterial(m)` then `renderer.quad()` | `canvas.quad(m)` |
| `renderer.bindTexture(0, t)` | `.texture(0, t)` on the quad |
| `CgVectorRenderer.create()` / `.curve()` | `canvas.curve(m)`, `canvas.triangle(m)`, `canvas.cell(m)` |
| `begin()` / `flush()` / `end()` at a stage | nothing |
| `sink(recorder)`, `CgPassRecorder` | `CgCanvas` itself |
| `delete()`, `onContextDestroy` cleanup | nothing |
| `CgQuadRenderer.Record` offsets | `CgQuadRecord` |
| `CgTextRenderer.create()`, `text.draw()` | `canvas.text()` |
| `text.retainedDraw()`, `queuedDraw(q)` | `canvas.retainedText()`, `CgTextDraw.queued(q)` |
| `text.context(CgTextRenderContext.orthographic/world(...))` | the canvas's camera: the stage's, the recording's constants, or `CgImmediate.constants()` |
| `beginBatch()` / `endBatch()`, `getDegradedDrawCount()` | nothing; `submit()` returns whether the draw was whole |
