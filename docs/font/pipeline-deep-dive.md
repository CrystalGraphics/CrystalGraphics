# String To Screen Pipeline Deep Dive

This document describes the full runtime path from passing a Java `String` into the font API to seeing pixels on screen.

## 1. Entry point

The public entry sequence is:

1. load a `CgFont`
2. build a `CgTextLayout`
3. draw it with `CgTextRenderer`

Relevant files:
- `src/main/java/io/github/somehussar/crystalgraphics/api/font/CgFont.java`
- `src/main/java/io/github/somehussar/crystalgraphics/api/font/CgTextLayoutBuilder.java`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/text/CgTextRenderer.java`

## 2. Font loading

### 2.1 API entry
`CgFont.load(...)`

Code references:
- `CgFont.java:91-105`
- `CgFont.java:118-129`

Two load modes exist:
- from file path
- from raw bytes

Both end up in:
- `CgFont.loadFromBytes(...)` at `CgFont.java:134-171`

### 2.2 What load actually creates
`loadFromBytes(...)` performs:
1. construct `CgFontKey`
2. create `FreeTypeLibrary`
3. create `FTFace` from memory
4. set pixel size on the face
5. create HarfBuzz `HBFont` from that face
6. derive `CgFontMetrics`
7. build `CgFont`

Code references:
- `CgFont.java:136-171`

### 2.3 Metric extraction
The font metrics are computed from the FreeType face:
- ascender
- descender
- line gap
- line height
- estimated x-height
- estimated cap height

Code references:
- `CgFont.java:174-206`
- `CgFont.java:212-223`

## 3. Layout build entry

### 3.1 Public call
`CgTextLayoutBuilder.layout(...)`

Code reference:
- `CgTextLayoutBuilder.java:43-80`

Inputs:
- Java UTF-16 string
- `CgFont`
- max width
- max height

Outputs:
- immutable `CgTextLayout`

## 4. BiDi split and shaping

### 4.1 BiDi paragraph analysis
`CgTextLayoutBuilder` uses Java `Bidi` to split the paragraph into directional runs.

Code references:
- `CgTextLayoutBuilder.java:60-64`
- `CgTextLayoutBuilder.java:88-104`

For each run it computes:
- start index
- end index
- embedding level
- whether the run is RTL

### 4.2 Shaping with HarfBuzz
Each directional run is then shaped via `CgTextShaper.shape(...)`.

Code references:
- `CgTextLayoutBuilder.java:99-100`
- `CgTextShaper.java:50-109`

What `CgTextShaper` does:
1. extract substring
2. create `HBBuffer`
3. add UTF-8 text into the buffer
4. set direction
5. call `guessSegmentProperties()`
6. call `HBShape.shape(...)`
7. extract glyph infos and positions
8. convert HarfBuzz 26.6 values into pixels by dividing by 64
9. return `CgShapedRun`

Key lines:
- buffer setup: `CgTextShaper.java:74-80`
- glyph extraction: `CgTextShaper.java:82-100`
- final object creation: `CgTextShaper.java:102-105`

### 4.3 Output of shaping
A `CgShapedRun` contains:
- glyph ids
- cluster ids
- per-glyph x advances
- per-glyph x offsets
- per-glyph y offsets
- total advance

See:
- `CgShapedRun.java:35-63`

Important semantic note:
- `glyphIds` are glyph indices, not Unicode codepoints

## 5. Line breaking and visual ordering

After shaping, `CgLineBreaker.breakLines(...)` groups runs into visual lines.

Code reference:
- `CgLineBreaker.java:49-87`

It does:
1. accumulate shaped runs until width would be exceeded
2. finalize the line
3. reorder the runs visually if RTL is present
4. stop when max height is exceeded

Visual reorder is implemented with `Bidi.reorderVisually(...)`.

See:
- `CgLineBreaker.java:100-132`

## 6. Layout result

The final CPU-side result is `CgTextLayout`.

Code reference:
- `CgTextLayout.java:35-48`

It stores:
- lines of shaped runs
- total width
- total height
- font metrics

At this point there are still no GL calls.

## 7. Render entry

Rendering begins with:
- `CgTextRenderer.draw(...)`

Code reference:
- `CgTextRenderer.java:101-121`

This method:
1. checks that the renderer is not deleted
2. skips empty layouts
3. saves GL state through `CgStateBoundary.save()`
4. calls `drawInternal(...)`
5. restores GL state in `finally`

## 8. Per-glyph resolve phase

Inside `drawInternal(...)`, the renderer walks every line and every shaped run.

Code references:
- `CgTextRenderer.java:137-174`

For each glyph it computes:
- whether MSDF is requested based on target size
- the sub-pixel bucket from the shaped x offset
- the `CgGlyphKey`
- the atlas region by calling `registry.ensureGlyph(...)`
- the final pen-relative x and y positions

Key lines:
- choose MSDF: `CgTextRenderer.java:144-146`
- compute sub-pixel bucket: `CgTextRenderer.java:163-165`
- request atlas region: `CgTextRenderer.java:165-167`
- advance pen: `CgTextRenderer.java:167-170`

## 9. Glyph materialization in the registry

`CgFontRegistry.ensureGlyph(...)` chooses bitmap or MSDF path.

Code reference:
- `CgFontRegistry.java:50-57`

### 9.1 Bitmap path
`ensureBitmapGlyph(...)`

Code reference:
- `CgFontRegistry.java:129-169`

That path:
1. cache-checks the bitmap atlas
2. sets FreeType size
3. loads glyph by glyph index
4. applies sub-pixel outline translation if needed
5. renders the glyph bitmap
6. normalizes bitmap pitch
7. reads bearings
8. uploads into the atlas

### 9.2 MSDF path
`ensureMsdfGlyph(...)`

Code reference:
- `CgFontRegistry.java:171-188`

That path:
1. cache-checks the MSDF atlas
2. requests lazy MSDF font handle from `CgFont`
3. calls `CgMsdfGenerator.queueOrGenerate(...)`
4. falls back to bitmap if MSDF generation returns `null`

## 10. MSDF generation details

`CgMsdfGenerator.queueOrGenerate(...)`

Code reference:
- `CgMsdfGenerator.java:47-96`

Steps:
1. enforce per-frame budget
2. load glyph outline by glyph index through msdfgen FreeType bridge
3. reject empty shapes
4. apply complexity gate
5. normalize shape
6. edge-color shape
7. choose cell size
8. create MSDF bitmap
9. auto-frame transform
10. generate MSDF and error-correct it
11. compute bearings from transformed bounds
12. upload into MSDF atlas

## 11. VBO population

Once all atlas regions are resolved, the renderer builds quad geometry.

Code references:
- `CgTextRenderer.java:176-180`
- `CgTextRenderer.java:223-243`
- `src/main/java/io/github/somehussar/crystalgraphics/gl/text/CgGlyphVbo.java:17-69`
- `CgGlyphVbo.java:298-320`

The renderer writes two logical groups:
- bitmap quads first
- MSDF quads second

`appendQuads(...)` computes per-glyph quad placement as:
- `x = glyphX + region.bearingX`
- `y = glyphY - region.bearingY`

See:
- `CgTextRenderer.java:237-239`

The VBO vertex format is:
- x
- y
- u
- v
- packed color bytes

See:
- `CgGlyphVbo.java:21-29`

## 12. GPU draw passes

After VBO upload, the renderer issues two draws.

Code references:
- bitmap pass: `CgTextRenderer.java:188-196`
- MSDF pass: `CgTextRenderer.java:198-208`

### Bitmap pass
- bind bitmap shader
- upload projection matrix
- bind bitmap atlas texture
- draw first N quads

### MSDF pass
- bind MSDF shader
- upload projection matrix
- bind MSDF atlas texture
- upload `u_pxRange`
- draw remaining quads with index offset

## 13. Shader behavior

Shader files:
- `src/main/resources/assets/crystalgraphics/shader/bitmap_text.vert`
- `src/main/resources/assets/crystalgraphics/shader/bitmap_text.frag`
- `src/main/resources/assets/crystalgraphics/shader/msdf_text.vert`
- `src/main/resources/assets/crystalgraphics/shader/msdf_text.frag`

### Bitmap fragment shader
Samples the red channel from the bitmap atlas and multiplies it into alpha.

### MSDF fragment shader
Samples RGB distance values, reconstructs signed distance by median, computes screen pixel range from derivatives, and derives final alpha.

## 14. Demo path to screen

The current development integration example is in:
- `src/main/java/io/github/somehussar/crystalgraphics/mc/integration/CrystalGraphicsFontDemo.java`

Key code references:
- registration: `CrystalGraphicsFontDemo.java:40-43`
- zoom update in client tick: `CrystalGraphicsFontDemo.java:46-61`
- overlay draw event: `CrystalGraphicsFontDemo.java:63-91`
- lazy font/renderer setup: `CrystalGraphicsFontDemo.java:93-107`
- orthographic projection helper: `CrystalGraphicsFontDemo.java:121-141`

Interactive behavior:
- mouse wheel in `ClientTickEvent` updates font size
- overlay draw in `RenderGameOverlayEvent.Text` renders the text

This means the end-to-end screen path in the demo is:
1. `ClientTickEvent` updates demo size
2. `RenderGameOverlayEvent.Text` calls `ensureDemoFontSystem()`
3. `CgFont.load(...)` provides the font
4. `CgTextLayoutBuilder.layout(...)` shapes and breaks the string
5. `CgTextRenderer.draw(...)` resolves glyphs and draws them
6. bitmap/MSDF shaders produce final screen pixels

### Important debug note: projection matrix layout

The demo path depends on the orthographic matrix generated in
`CrystalGraphicsFontDemo.populateOrthoMatrix(...)`.

That buffer must be written in the order OpenGL expects for
`glUniformMatrix4(..., false, buffer)`, because the renderer uploads it through:
- `CgTextRenderer.java:255-265`
- `CoreShaderProgram.java:244-246`
- `ArbShaderProgram.java:273-275`

This matters because a wrong row-major/column-major layout can produce the exact
failure mode where:
- layout succeeds
- glyph atlases populate
- `glDrawElements` is hit
- nothing visible appears on screen

The current demo writes the projection values in the fixed layout at:
- `CrystalGraphicsFontDemo.java:130-140`

## 15. Summary

The full string-to-screen chain is:

`String`
-> `CgTextLayoutBuilder.layout(...)`
-> Java `Bidi`
-> `CgTextShaper.shape(...)`
-> `CgLineBreaker.breakLines(...)`
-> `CgTextLayout`
-> `CgTextRenderer.draw(...)`
-> `CgFontRegistry.ensureGlyph(...)`
-> bitmap rasterization or MSDF generation
-> `CgGlyphAtlas`
-> `CgGlyphVbo`
-> bitmap/MSDF shader passes
-> screen

The critical implementation split is:
- CPU text understanding happens before any GL work
- glyph realization happens lazily at draw time
- atlas caching avoids repeated rasterization/generation
- final rendering is a two-pass GPU draw over one shared quad buffer
