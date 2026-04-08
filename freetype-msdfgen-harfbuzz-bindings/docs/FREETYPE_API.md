# FreeType API Reference

## Core Classes

### `FreeTypeLibrary`
Entry point. One instance per application.

| Method | Description |
|--------|-------------|
| `create()` | Initialize FreeType library |
| `newFace(path, index)` | Load font from file |
| `newFaceFromMemory(data, index)` | Load font from byte array |
| `getVersion()` | Returns `[major, minor, patch]` |
| `destroy()` | Release all resources |

### `FTFace`
Loaded font face. Set size before loading glyphs.

| Method | Description |
|--------|-------------|
| `setPixelSizes(w, h)` | Set glyph size in pixels (0 = auto) |
| `setCharSize(w, h, hDPI, vDPI)` | Set size in 26.6 fixed-point |
| `getCharIndex(charCode)` | Unicode codepoint → glyph index |
| `loadGlyph(index, flags)` | Load glyph into slot |
| `loadChar(charCode, flags)` | Load glyph by Unicode codepoint |
| `renderGlyph(mode)` | Rasterize loaded glyph |
| `getGlyphMetrics()` | Get metrics of loaded glyph |
| `getGlyphBitmap()` | Get rendered bitmap |
| `getFamilyName()` | Font family (e.g., "Arial") |
| `getStyleName()` | Style (e.g., "Bold") |
| `getKerning(left, right, mode)` | Kerning vector `[x, y]` |
| `hasKerning()` | Whether font has kerning table |
| `destroy()` | Release face resources |

### `FTBitmap`
Rendered glyph bitmap data.

| Field | Description |
|-------|-------------|
| `getWidth()` | Bitmap width in pixels |
| `getHeight()` | Bitmap height in pixels |
| `getPitch()` | Bytes per row (may be negative) |
| `getPixelMode()` | GRAY=2, MONO=1, LCD=5 |
| `getBuffer()` | Raw pixel data |
| `getLeft()` | X offset from glyph origin |
| `getTop()` | Y offset from baseline (positive=up) |

### `FTGlyphMetrics`
All values in 26.6 fixed-point (divide by 64 for pixels).

| Field | Description |
|-------|-------------|
| `getWidth/Height()` | Glyph bounding box |
| `getHoriBearingX/Y()` | Bearing from origin |
| `getHoriAdvance()` | Horizontal advance width |
| `getWidthPixels()` | Pre-converted pixel width |

### `FTLoadFlags`
Bitwise OR flags for `loadGlyph()` / `loadChar()`.

| Flag | Value | Effect |
|------|-------|--------|
| `FT_LOAD_DEFAULT` | 0 | Normal loading |
| `FT_LOAD_RENDER` | 4 | Auto-render after load |
| `FT_LOAD_NO_HINTING` | 2 | Disable hinting |
| `FT_LOAD_FORCE_AUTOHINT` | 32 | Use auto-hinter |
| `FT_LOAD_NO_BITMAP` | 8 | Ignore embedded bitmaps |
| `FT_LOAD_MONOCHROME` | 4096 | 1-bit rendering |

### `FTRenderMode`

| Mode | Effect |
|------|--------|
| `FT_RENDER_MODE_NORMAL` | 8-bit grayscale (default) |
| `FT_RENDER_MODE_MONO` | 1-bit monochrome |
| `FT_RENDER_MODE_LCD` | LCD sub-pixel (horizontal) |
