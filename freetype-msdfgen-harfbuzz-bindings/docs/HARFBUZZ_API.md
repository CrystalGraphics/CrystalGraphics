# HarfBuzz API Reference

## Core Classes

### `HBBuffer`
Text buffer for shaping. Add text, set properties, shape, read results.

| Method | Description |
|--------|-------------|
| `create()` | Create empty buffer |
| `addUTF8(text)` | Add UTF-8 text |
| `addCodepoints(int[])` | Add raw Unicode codepoints |
| `setDirection(dir)` | Set text direction |
| `setScript(script)` | Set script tag |
| `setLanguage(lang)` | Set BCP-47 language tag |
| `guessSegmentProperties()` | Auto-detect direction/script/language |
| `getGlyphInfos()` | Get shaped glyph IDs and clusters |
| `getGlyphPositions()` | Get glyph positioning data |
| `getLength()` | Number of items in buffer |
| `clearContents()` | Clear text, keep properties |
| `reset()` | Full reset |
| `destroy()` | Release buffer |

### `HBFont`
Font object for shaping.

| Method | Description |
|--------|-------------|
| `createFromFile(path, index)` | Create from font file |
| `setScale(x, y)` | Set font scale |
| `setPpem(x, y)` | Set pixels per EM |
| `destroy()` | Release font |

### `HBShape`
Static shaping entry point.

| Method | Description |
|--------|-------------|
| `shape(font, buffer)` | Shape buffer using font |
| `shape(font, buffer, features)` | Shape with OpenType features |

Features are strings like `"kern"`, `"-liga"`, `"+smcp"`.

### `HBGlyphInfo`
Per-glyph output from shaping.

| Field | Description |
|-------|-------------|
| `getCodepoint()` | Glyph ID (font-specific, NOT Unicode) |
| `getCluster()` | Cluster index (maps back to input text) |

### `HBGlyphPosition`
Per-glyph positioning from shaping.

| Field | Description |
|-------|-------------|
| `getXAdvance()` | Horizontal advance (font units) |
| `getYAdvance()` | Vertical advance (font units) |
| `getXOffset()` | Horizontal offset from default position |
| `getYOffset()` | Vertical offset from default position |

### `HBDirection`

| Constant | Value | Use |
|----------|-------|-----|
| `HB_DIRECTION_LTR` | 4 | Left-to-right (Latin, CJK) |
| `HB_DIRECTION_RTL` | 5 | Right-to-left (Arabic, Hebrew) |
| `HB_DIRECTION_TTB` | 6 | Top-to-bottom (vertical CJK) |
| `HB_DIRECTION_BTT` | 7 | Bottom-to-top (rare) |

### `HBScript`
ISO 15924 script tags as 4-byte integers.

Common scripts: `HB_SCRIPT_LATIN`, `HB_SCRIPT_ARABIC`, `HB_SCRIPT_DEVANAGARI`, `HB_SCRIPT_HAN`, `HB_SCRIPT_HANGUL`, `HB_SCRIPT_HIRAGANA`, `HB_SCRIPT_KATAKANA`, `HB_SCRIPT_CYRILLIC`, `HB_SCRIPT_HEBREW`, `HB_SCRIPT_THAI`.
