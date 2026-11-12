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
public static final CgTexture2D DIGITS = CgTextures.of("mymod:textures/hud/digits.png");

CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame ->
        frame.canvas().quad(CgCanvasMaterials.SCREEN.image()).texture(0, DIGITS)
             .at(x, y).size(w, h).uv(u0, v0, u1, v1).color(0xFFFFD040).submit());
```

That is a whole HUD renderer, with no shader of its own. The stage hands you its canvas, under its camera and
onto its target, and what you submit lands in that firing.

---

## 2. Where a canvas comes from

| You are | Use | Space |
|---|---|---|
| Inside a stage renderer (`CgRenderStage.register`) | `frame.canvas()` | the stage's: the window's pixels on `HUD`/`SCREEN`, camera-relative world on `WORLD_OPAQUE`/`WORLD_TRANSPARENT` |
| Outside any stage: a preview, a harness scene, the loading screen | `new CgCanvas()`, then `flush()` | its `camera(...)`, into the current target |
| Recording a frame of your own (a UI, an offscreen picture) | `new CgCanvas()` with `recordInto(...)` | the constants you give it (§ 8) |

**Inside a stage, always `frame.canvas()`.** It opens lazily on the stage's target at your first item, so a
renderer that draws nothing this frame records nothing, and it is closed for you when your renderer returns.

---

## 3. Quads

```java
CgCanvas canvas = frame.canvas();
CgMaterial image = CgCanvasMaterials.SCREEN.image();

canvas.quad(image).at(x, y).size(w, h).color(argb).submit();                          // a coloured box
canvas.quad(image).texture(0, icon).at(x, y).size(32, 32).submit();                  // an image
canvas.quad(image).texture(0, atlas).uv(u0, v0, u1, v1).at(x, y).size(w, h).submit(); // a sprite from a sheet
canvas.quad(CgCanvasMaterials.SCREEN.imageArray(CgBlendMode.ALPHA)).texture(0, sheet)
        .atlasLayer(3).at(x, y).size(w, h).submit();                                  // a layer of a 2D array
canvas.quad(image).at(-8, -8).size(16, 16).pose(rotation).submit();                   // rotated about its pose's origin
canvas.quad(image).texture(0, icon).at(x, y).size(32, 32).opacity(0.5f).submit();    // half faded
canvas.quad(BAR).at(x, y).size(w * fill, h).custom0(fill, 0f, 0f, 0f).submit();       // a material of your own
```

| Call | Default | Note |
|---|---|---|
| `size(w, h)` | — | **Required.** Submit throws without it |
| `at(x, y[, z])` | `(0, 0, 0)` | Its corner. `z` translates, it does not tilt |
| `uv(u0, v0, u1, v1)` | the whole texture | Top-left, then bottom-right |
| `color(argb)` | opaque white | `0xAARRGGBB` |
| `opacity(f)` | 1 | Fades the whole item: a box's border with its fill, which the colour's alpha does not reach. Per item, so it batches |
| `texture(unit, texture[, sampler])` | the material's own | Overrides one sampler unit for this quad; a `CgSampler` samples it in place of its spec's filter and wrap |
| `state(renderState)` | the material's | A blend or depth the shader cannot decide |
| `custom0/custom1(x, y, z, w)`, `custom2(v)` | 0 | Free per-quad values: `CG_QUAD_CUSTOM0..2`. Also `custom0(argb)` |
| `pose(matrix)` | none | Baked on the CPU: rotation, scale and skew act as on the four corners |
| `world(x, y, z)` | none | Absolute position in doubles (§ 6) |
| `clip(entry)`, `node(spatial, effect)` | none | For a recorder that keeps clip tables and nodes: CrystalGUI's |

**Build and `submit()` in one expression.** `canvas.quad(...)` returns the canvas's one scratch builder, reset
by the next call; never keep it. To keep a builder across frames, `canvas.retainedQuad()` gives one of your
own, which needs `.material(m)` before each `submit()`.

### Default materials

`CgCanvasMaterials` ships what a HUD, a marker or a whole UI needs, so most consumers write no shader: Unreal's
simple element shader, one look per kind of item, varied by blend mode.

| | `SCREEN` (over what is there: GUI stages, immediate canvases) and `WORLD` (depth-tested, world stages) |
|---|---|
| `image()`, `image(blend)` | the item's texture times its colour; no texture draws the colour alone |
| `mask(blend)` | the item's colour, its alpha times the texture's: a silhouette in any colour |
| `imageArray(blend)`, `maskArray(blend)` | the same from the layer of a 2D array `atlasLayer` names |
| `curve()` | lines, strokes, triangles and cells, alpha-blended (§ 4) |
| `box()` | a rounded, bordered, flat, textured or nine-slice box, premultiplied: the shape its quad names (below) |
| `coverage()`, `strokeCoverage()`, `colourCoverage()` | screen only: vector shapes rastered into a texture as exact coverage (below) |

```java
canvas.quad(CgCanvasMaterials.SCREEN.maskArray(CgBlendMode.ADDITIVE)).texture(0, digits).atlasLayer(d)
        .at(x, y).size(w, h).color(0xFFFFE080).submit();                  // a glow in the shape of a digit
frame.canvas().quad(CgCanvasMaterials.WORLD.image()).world(x, y, z).pose(facing)
        .at(-0.5f, -0.5f).size(1f, 1f).texture(0, icon).submit();          // a marker in the world, behind walls
```

- Blends: `ALPHA`, `PREMULTIPLIED` (a premultiplied texture), `ADDITIVE` (weighed by alpha), `MODULATE`,
  `SUBTRACT`. `OPAQUE` throws: an image at full alpha under `ALPHA` covers.
- Each is one shared instance: never set a property on it. Values of your own are a `CgMaterial.newInstance` of
  `CgCanvasMaterials.IMAGE_SHADER` (with `MASK` on for a mask).
- The screen's alpha and additive ones, its box, curve, gradients and grids are built at load; the rest at their first
  ask, a frame or two before they draw.
- **Every screen material antialiases its own edges** when a quad is rotated or sheared, and an image or box filters
  its texels, with no MSAA; axis-aligned items are untouched. The width is one unit of the canvas's space: a window
  pixel on `frame.canvas()`, softer by the scale on `guiScaledCanvas()`. In the world the scene's own antialiasing does.
- A material of your own with the same look, for a property you set (a texture you keep): `newBox()`, `newCurve()`.
  Never a property to fade with: that is the item's `opacity`.

### Boxes

A **box** is a rectangle with rounded corners, a border, and a fill: a colour, a stretched texture, or a nine-slice
sprite. It is what a health bar, a panel, a button or a tooltip's background is drawn with, and it is how
CrystalGUI draws every `background` and `border` in CSS. Each corner has its own elliptical radius, each side its own
border width, and every edge is antialiased, rotated or not.

Drawing one takes two steps: describe the shape in the canvas's **shape table**, which answers an index, then draw a
quad under `CgCanvasMaterials.SCREEN.box()` that names that index with `custom2`. The shape says what the box looks
like; the quad says where it goes. Every box, whatever its shape, is the same pipeline, so a screen of boxes is a few
draws: they part only where their textures differ.

```java
CgCanvas canvas = frame.canvas();
CgMaterial box = CgCanvasMaterials.SCREEN.box();

// A flat rounded rect with a 1-pixel border: a health bar's frame
int frameShape = canvas.shapes().begin(CgShapeTable.FLAT, 120, 12)
        .radii(6, 6, 6, 6, 6, 6, 6, 6)                        // (rx, ry) per corner: TL, TR, BR, BL
        .border(1, 1, 1, 1, 0xFF000000)                        // widths left, top, right, bottom, then colour
        .fill(0xC0202020)
        .end();
canvas.quad(box).custom2(frameShape).at(x, y).size(120, 12).submit();

// Its fill, as wide as the health left: a shape of its own size
float w = 118 * health;
int fillShape = canvas.shapes().begin(CgShapeTable.FLAT, w, 10).radii(5, 5, 5, 5, 5, 5, 5, 5).fill(0xFF3FBF4F).end();
canvas.quad(box).custom2(fillShape).at(x + 1, y + 1).size(w, 10).submit();
```

The kinds a shape can be:

| Kind | Fills with | Use it for |
|---|---|---|
| `FLAT` | the shape's `fill(argb)` colour | panels, bars, buttons, badges |
| `TEXTURE` | the quad's texture, stretched over the box | a rounded portrait, an icon with a border |
| `NINE_SLICE` | the quad's texture cut into nine regions (`slices`): corners kept at their size, edges and centre stretched or tiled | a themed window frame or button from one sprite, at any size |

And two that need no shape, since every table holds them:

| Index | Draws |
|---|---|
| `CgShapeTable.PLAIN` (0: a quad with no `custom2`) | the texture times the colour: an ordinary image under the box material, so it batches with boxes |
| `CgShapeTable.PREMULTIPLIED` (1) | the same for a texture whose colour is already premultiplied: a layer drawn over a transparent clear |

```java
// A round, bordered portrait: TEXTURE samples the quad's texture
int portrait = canvas.shapes().begin(CgShapeTable.TEXTURE, 64, 64).radii(32, 32, 32, 32, 32, 32, 32, 32)
        .border(2, 2, 2, 2, 0xFFFFD040).end();
canvas.quad(box).texture(0, face).custom2(portrait).at(x, y).size(64, 64).submit();

// A window frame from a 48x48 sprite with 8-pixel corners, its centre tiled at the sprite's size
int window = canvas.shapes().begin(CgShapeTable.NINE_SLICE, w, h)
        .slices(8, 8, 8, 8,                                    // insets, in the box's units
                0f, 0f, 1f, 1f,                                // the sprite's outer uv rect (u0 v0 u3 v3)
                8 / 48f, 8 / 48f, 40 / 48f, 40 / 48f,          // its inner rect, where the corners end (u1 v1 u2 v2)
                32, 32,                                        // the centre's size in the sprite
                CgShapeTable.REPEAT, CgShapeTable.REPEAT,      // how each axis fills: STRETCH, REPEAT, ROUND, SPACE
                true)                                          // draw the centre
        .end();
canvas.quad(box).texture(0, frameSprite).custom2(window).at(x, y).size(w, h).submit();

// An inset field's bevel: the top edge dark, the bottom light, the sides the uniform colour
int field = canvas.shapes().begin(CgShapeTable.FLAT, 200, 22).radii(3, 3, 3, 3, 3, 3, 3, 3)
        .border(1, 1, 1, 1, 0xFF404040, 0xFF202020, 0xFF606060).fill(0xFF303030).end();
```

- **The shape's size is the box's.** Give `begin` the size the quad is drawn at: the corners, the border and the
  slices are measured in it, and a quad drawn larger stretches them.
- **The quad's colour tints the fill, never the border**: one shape serves a hovered and an idle button.
- **Equal shapes share an index**: describing the same shape every frame costs a lookup, not a new entry.
- **Shapes live in the canvas's recording**, so a box draws into a stage's canvas (`frame.canvas()`);
  `canvas.shapes()` throws on a canvas recording nothing. An index means nothing in another recording or frame.
- `begin(...)...end()` is one scratch builder: build and end it in one expression, before the next `begin`.
- `ROUND` tiles a whole number of times, rescaled to fit; `SPACE` draws whole tiles with even gaps, as CSS's
  `border-image-repeat`. `CgShapeTable.tiles(mode, span, source)` is the count either gives.
- The box is premultiplied out; `WORLD.box()` is the same, tested against the scene's depth.

### Gradients

A **gradient** is a box filled with colours that blend from one to the next: CSS's `linear-gradient` and
`radial-gradient`. A wash fading a panel into the world behind it, a glow under a selected slot, a sky. It is drawn
by a `CgCanvasGradient`, which owns its material: set it up for the box, then draw a quad over the box under its
`material()`.

```java
CgCanvasGradient wash = new CgCanvasGradient(CgCanvasMaterials.SCREEN);   // once, kept: it owns its material

// Each frame: dark at the top, gone at the bottom
wash.size(w, h)                    // the box, in the units the quad is drawn in
    .angle(180f)                   // CSS's angle: 0 up, 90 right, 180 down, 270 left
    .clearStops()
    .stop(0f, 0xC0101418)          // position along the line, then straight ARGB
    .stop(1f, 0x00101418);
for (int i = 0, n = wash.windows(); i < n; i++) {
    wash.window(i);                // writes this window's stops into the material
    canvas.quad(wash.material()).at(x, y).size(w, h).submit();
}
```

One quad draws up to eight stops; `windows()` is how many quads the stops take (one for up to eight), and each
`window(i)` sets the material for its quad. Most gradients are one window, and the loop is always right.

```java
// A radial glow from the bottom centre, the box's top corners rounded
glow.size(w, h)
    .radial(w / 2, h, w / 2, h)                 // centre (x, y) and radii (rx, ry), from the box's top-left
    .radii(8, 8, 8, 8, 0, 0, 0, 0)
    .clearStops().stop(0f, 0xFFFFE080).stop(0.6f, 0x40FFE080).stop(1f, 0x00FFE080);

// A hard stop: two colours at one position, a two-tone bar
bar.size(w, h).angle(90f).clearStops()
   .stop(0f, 0xFF3FBF4F).stop(health, 0xFF3FBF4F).stop(health, 0xFF5A1010).stop(1f, 0xFF5A1010);
```

- **Positions run 0 to 1 along the gradient line.** For an angle that is CSS's line, through the box's centre, so 0
  and 1 land on the corners at any angle; for a radial, 0 is the centre and 1 the ellipse.
- **Before the first stop and past the last, the end colours fill**, as in CSS.
- **Stops must ascend**: one below the stop before is raised to it.
- **Colours blend premultiplied**, as CSS specifies: a fade to transparent keeps its hue instead of passing through
  grey. The result is dithered, so a shallow fade does not band.
- **Write a window, submit its quad, then the next.** A quad takes the material's values as it is submitted; a window
  written after it does not reach it.
- **One gradient per drawer.** Its material holds the last window written; two drawers sharing one overwrite each
  other.
- The quad's colour tints the whole gradient. `radii(...)` rounds the gradient's own corners.

### Grids

A **grid** is a box ruled with lines at a fixed spacing: a map's cells, an editor's canvas, a graph's background. It
is drawn by a `CgCanvasGrid`, one quad however many cells, and stays clean at any zoom: a line thinner than a pixel
is drawn a pixel wide and faded rather than flickering, and cells smaller than a pixel dissolve into an even tint
rather than moiré (Ben Golus's "Pristine Grid").

```java
CgCanvasGrid ruling = new CgCanvasGrid(CgCanvasMaterials.SCREEN);   // once, kept

ruling.size(w, h)                      // the box
      .cell(16f, 16f)                  // the spacing, each axis on its own
      .line(1f)                        // the line's width
      .color(0x30FFFFFF)               // straight ARGB
      .apply();                        // writes it into the material
canvas.quad(ruling.material()).at(x, y).size(w, h).submit();

// Zoomed: scale the cell with the view and keep the line at a pixel; rounded corners
ruling.size(w, h).cell(16f * zoom, 16f * zoom).line(1f).color(0x30FFFFFF).radii(6, 6, 6, 6, 6, 6, 6, 6).apply();
```

- Cells, lines and the box are in the units the quad is drawn in. A cell is at least half a unit.
- **`apply()` before each quad**: like a gradient's window, the quad takes the material's values as it is submitted.
- One grid per drawer, for the same reason as a gradient. A line width of 0 draws nothing.
- The first line sits on the box's edge, so the grid moves with the box: move `at` to scroll it.

### Glass

**Glass** is a pane over what is behind it: a blurred copy of the backdrop in its middle, the sharp copy bent through
a lens at its bezel, saturated, tinted toward a colour's brightness and colour, lit at its rim and grained. Windows'
acrylic and Mica, and Apple's liquid glass, are all settings of it. On the screen or in the world: the backdrop is read
where each pixel is, so a pane under any pose and any camera bends what is behind it. A `CgCanvasGlass` draws it and
owns its material; a `CgCanvasBackdrop` captures and blurs what is behind it and owns those textures.

```java
CgCanvasGlass pane = new CgCanvasGlass(CgCanvasMaterials.SCREEN);    // once, kept
CgCanvasBackdrop backdrop = new CgCanvasBackdrop("mymod.glass");      // once, kept

// Each frame, after what the pane covers is drawn:
CgCanvasGlass.Look look = CgCanvasGlass.LIQUID;
CgQuadBatch.Quad quad = canvas.quad(pane.material()).at(x, y).size(w, h);     // built, not yet submitted
pane.look(look).size(w, h).radii(16f)
    .backdrop(backdrop.capture(canvas, quad, look.blur(), pane.reach()))     // what is behind that quad, blurred
    .apply();
quad.submit();
```

**In the world** it is the same at a world stage, with `CgCanvasMaterials.WORLD` (depth-tested) and a look scaled to
blocks: a look's lengths are pixels, and `scaled(f)` makes a block `1 / f` of them.

```java
CgRenderStage.WORLD_TRANSPARENT.register(100, frame -> {
    CgCanvas canvas = frame.canvas();
    CgCanvasGlass.Look look = CgCanvasGlass.FROSTED.scaled(1 / 32f);
    CgQuadBatch.Quad quad = canvas.quad(pane.material()).world(x, y, z).pose(facing).at(-1f, -0.5f).size(2f, 1f);
    pane.look(look).size(2f, 1f).radii(0.25f).backdrop(backdrop.capture(canvas, quad, look.blur(), pane.reach())).apply();
    quad.submit();
});
```

The looks, each a different material rather than a different amount:

| Look | Is |
|---|---|
| `DEFAULT` | a pane over a blur with a lens at its edge, lit and grained |
| `CLEAR` | plain glass: no blur, a gentle lens, a faint highlight |
| `FROSTED` | a wide blur under a light tint, no lens |
| `LIQUID` | Apple's liquid glass: a little blur, a wide strong lens, a bright rim, some dispersion |
| `ACRYLIC` | Windows' acrylic: a wide blur, a dark tint taking much of the brightness |
| `MICA` | Windows' Mica: a very wide blur, its brightness mostly the tint's |
| `PRISM` | dispersion: the colour channels refracted apart |

```java
pane.look(CgCanvasGlass.FROSTED).tint(0x40FFE0C0).apply();        // a look, then a value of your own
pane.look(CgCanvasGlass.ACRYLIC.withBlur(20f)).apply();            // a Look is a record: change one field
pane.lens(0f, 1f).specular(0f).noise(0f).apply();                  // frosted to the edge: those layers compile out
```

- **Capture after what should show through is drawn**, and submit the pane straight after: a capture ends the
  canvas's pass and records a copy of the pixels the quad covers, padded by the blur and the lens's `reach()`, then
  the blur. Panes take turns: each captures again, so the second sees the first through it.
- **The blur is in the target's pixels**, whatever the look's other lengths are in: Skia's scale-then-blur on compute
  (`CgGpuOps.blurScaled`), its edges mirrored as Chromium extends a backdrop.
- **The backdrop's colour is what shows**; its alpha is not read. Past the captured rect a tap folds back in once.
- In the linear HDR scene the blur runs linear and the colour work (saturation, tint, highlight) in sRGB, as on
  the screen: a look reads the same in both.
- A layer whose value is 0 (`ior` 1 or `bezel` 0, `chromatic`, `specular`, `noise`) is compiled out. One pane per
  drawer; `apply()` before each quad.
- **A backdrop composed by hand** (CrystalGUI lays its own layers over the scene): `backdrop.capture(recording, source,
  w, h, type, x0, y0, x1, y1)`, draw into `backdrop.sharp()`, then `backdrop.blur(recording, px)`, and give the pane
  `backdrop(backdrop, offsetX, offsetY, x0, y0, x1, y1)`: where the bound target's pixel (0, 0) is in the backdrop.
- `--mode=canvas-glass` is the gate.

### Coverage into a texture

To draw a vector shape many times, or to blur it, raster it once into a texture as **coverage**: each fill cell
adds its exact area into alpha, so a tessellated shape is exact, and the texture then draws as a tinted image.
Skia's path atlas. Three screen materials, by how the pieces combine:

| Material | Pieces | Writes |
|---|---|---|
| `SCREEN.coverage()` | a fill's cells, which partition it: their areas sum | `(1, 1, 1, coverage)` |
| `SCREEN.strokeCoverage()` | a stroke's segments, which overlap at every joint: their max | `(1, 1, 1, coverage)` |
| `SCREEN.colourCoverage()` | a fill with colours of its own | its premultiplied colour times the area, summed |

```java
// Into a texture cleared to transparent, on a canvas recording into it:
canvas.cell(CgCanvasMaterials.SCREEN.coverage()).points(...).submit();          // each cell of the fill
canvas.curve(CgCanvasMaterials.SCREEN.strokeCoverage()).line(...).submit();   // each segment of the outline
// Then drawn anywhere, tinted:
frame.canvas().quad(CgCanvasMaterials.SCREEN.image()).texture(0, icon).at(x, y).size(w, h).color(tint).submit();
```

- A summed raster runs past 1 where cells overlap: a float format (RGBA16F) keeps it, and the box clamps it.
- Each takes the exact pixel centre, so a shape's cells partition its pixels; for a 2D camera only.

### Shrinking a texture

A thumbnail is not a canvas draw: it is `CgGpuOps.shrink`, a compute op, each texel the average of the source area it
covers at any ratio (`SHADERS.md` § *Ops*). Shrink into a texture of its own, then draw that as an image.

```java
CgComputePass pass = recording.compute("thumbnail");
CgGpuOps.shrink(pass, window, 0, 0, window.getWidth(), window.getHeight(), thumbnail, 0, 0, 192, 108);
pass.end();
canvas.quad(CgCanvasMaterials.SCREEN.image()).texture(0, thumbnail).at(x, y).size(192, 108).submit();
```

### A material of your own

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

`crystalgraphics:shaders/canvas/image.shader` is the default image's, its blend left to the material state. What
the vertex stage can read:
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
- **`opacity(f)`** fades the whole item, as a quad's does.
- **Feather**: the default is antialiasing, a pixel and a half whatever the scale. `feather(4f)` is a deliberate
  soft edge (a glow), and that one scales with the pose.
- **Gradients** on triangles and cells: `gradient(start, end, originX, originY, dirX, dirY)`, per pixel.
- **Cells** are for tessellated fills: mark the edges on the shape's outline `softEdges`, leave the cuts between
  cells hard, and neighbours meet with no seam. A lone shape wants `triangle`.
- The material declares `#pragma cg_use curve`. `CgCanvasMaterials.SCREEN.curve()` and `WORLD.curve()` are the
  shipped ones.

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
    canvas.text().style(NAME_TAG).text(name).family(family).at(14, 40).submit();       // outlined, shadowed
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
- **A draw's style paints its layers bottom to top within the draw** (`CgTextStyle`); across draws, submit order
  holds: a later label covers an earlier one, shadow and all.
- `canvas.text()` is a scratch like `quad()`: build and submit in one expression.
- **Text that persists and moves is kept on the GPU**: `CgCanvasLabels` captures each label's glyphs once and
  draws every label from them, a matrix a label a frame (10,000 outlined, shadowed labels in 3 draws). In the world,
  `CgWorldRenderer.get().text(...)` draws through one, billboarded or turned and depth-tested
  ([`ENGINE_API.md`](ENGINE_API.md)); its javadoc has the example for labels of your own.
- Styles (fills, outlines, shadows, gradients, materials), fonts and layout: [`font/README.md`](font/README.md).
- **A draw's colour fades each layer on its own**: half faded, an outlined, shadowed word shows its outline through
  its fill. To fade it as one picture, retain it (below).

### Retained: fading, tinting or glowing as one picture

`CgCanvasRetainer` is UMG's Retainer Box: items painted into a texture of its own, cleared transparent, then drawn
from it as images. Drawn under the premultiplied image, a region fades and tints as one flat picture; drawn under the
additive mask, its silhouette glows in a colour. A damage number that fades out is the use.

```java
static final CgCanvasRetainer RETAINED = new CgCanvasRetainer("mymod.hud");   // once, kept
// in a load task, so they are built before the first frame
image = CgCanvasMaterials.SCREEN.image(CgBlendMode.PREMULTIPLIED);
glow = CgCanvasMaterials.SCREEN.mask(CgBlendMode.ADDITIVE);

CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
    // 1. Paint, before the canvas's first item: 300 by 200 units, at the largest scale it is drawn at
    CgCanvas paint = RETAINED.paint(frame.recording(), 300, 200, scale);
    paint.text().style(DAMAGE).text(damage).at(10, 10).submit();      // a region per thing: this one at (0, 0)
    paint.text().style(TITLE).text("Critical!").at(10, 110).submit(); // and this one at (0, 100)
    RETAINED.end();

    // 2. Draw regions of it, each as one quad: placed, posed and coloured as any quad
    CgCanvas canvas = frame.canvas();
    RETAINED.quad(canvas, image, 0, 0, 300, 100).at(x, y).pose(pose).color(0x80FFFFFF).submit();   // half faded
    RETAINED.quad(canvas, glow, 0, 0, 300, 100).at(x, y).pose(pose).color(0x60FFE080).submit();    // its glow
});
```

- **Paint every frame it is drawn**, before the first item on `frame.canvas()`. A texture's first draw in the frame
  that samples it has come out empty on NVIDIA, and painting each frame follows a window's resize.
- **Several things share one**: a region each, all painted in one pass, then one quad per region.
- **`scale` is texels a unit**: the largest a region is drawn at, so it is never magnified.
- **Blurred, as CSS's `filter: blur()`**: after `end()`, `blur(recording, x, y, w, h, sigma)` blurs a region by a
  sigma in units (`CgGpuOps.blurScaled`, a compute pass) and returns the reduction to draw it with:
  `RETAINED.quad(canvas, image, 0, 0, 0, 300, 100, reduce)`. Paint the region three sigma larger than what it holds,
  or the blur is cut at its edge.
- **What it holds is premultiplied**: the `PREMULTIPLIED` image, never `ALPHA`, which darkens its edges. The quad's
  colour is straight; the material premultiplies it.
- It grows to fit and never shrinks. `release(recording)` when done with it, `delete()` at a context's teardown.

---

## 8. A canvas of your own

Outside any stage, a canvas that is not recording draws at `flush()`, into the current target, through its camera:

```java
CgCamera screen = new CgCamera();                // kept: the canvas reads it at each draw and flush
canvas.camera(screen);
screen.screenLens(w, h);                          // each frame: pixels, top-left origin

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
CgRenderer.executeNow(frame);
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
| Moving an immediate canvas's camera between its draws | Text read the camera at each draw, quads at `flush()`: they disagree | `flush()` first, then change the camera |
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
| `text.context(CgTextRenderContext.orthographic/world(...))` | the canvas's camera: the stage's, the recording's constants, or its `camera(...)` |
| `beginBatch()` / `endBatch()`, `getDegradedDrawCount()` | nothing; `submit()` returns whether the draw was whole |
