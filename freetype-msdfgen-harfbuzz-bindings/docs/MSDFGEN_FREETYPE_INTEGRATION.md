# FreeType Integration Guide

## Overview

The msdfgen-java-bindings library supports loading font glyphs via FreeType for MSDF generation. This is an **optional** feature that requires rebuilding the native library with FreeType enabled.

When FreeType is available, you can load `.ttf`, `.otf`, and other font formats directly, extract glyph shapes, and generate MSDF bitmaps — instead of manually constructing shapes from edge segments.

## Prerequisites

### FreeType Library

FreeType must be installed on the build system:

**Windows (vcpkg):**
```bash
vcpkg install freetype:x64-windows
# Then pass -DCMAKE_TOOLCHAIN_FILE=[vcpkg root]/scripts/buildsystems/vcpkg.cmake
```

**Linux (apt):**
```bash
sudo apt-get install libfreetype-dev
```

**macOS (Homebrew):**
```bash
brew install freetype
```

## Building with FreeType Support

### Quick Build

```bash
# Linux/macOS
cd native/msdfgen-jni
MSDFGEN_USE_FREETYPE=ON ./build-natives.sh

# Windows
cd native\msdfgen-jni
set MSDFGEN_USE_FREETYPE=ON
build-natives.bat
```

### Manual CMake Build

```bash
cd native/msdfgen-jni

# Clone msdfgen if not present
git clone --depth 1 --branch v1.13 https://github.com/Chlumsky/msdfgen.git msdfgen

# Configure with FreeType
cmake -S . -B build \
    -DCMAKE_BUILD_TYPE=Release \
    -DMSDFGEN_SOURCE_DIR=./msdfgen \
    -DMSDFGEN_USE_FREETYPE=ON

# Build
cmake --build build --config Release
```

### What Changes

When `MSDFGEN_USE_FREETYPE=ON`:
- msdfgen's `ext/` sources are compiled (including `import-font.cpp`)
- FreeType headers and library are linked
- `MSDFGEN_USE_FREETYPE` and `MSDFGEN_EXTENSIONS` preprocessor flags are set
- `nHasFreetypeSupport()` returns `true` at runtime

When `MSDFGEN_USE_FREETYPE=OFF` (default):
- Only msdfgen `core/` is compiled
- No FreeType dependency
- FreeType JNI methods return error codes gracefully
- `nHasFreetypeSupport()` returns `false`

## Java API

### Checking Availability

```java
if (FreeTypeMSDFIntegration.isAvailable()) {
    // FreeType is available
} else {
    // Fall back to manual shape construction
}
```

### Loading Fonts and Glyphs

```java
FreeTypeMSDFIntegration ft = FreeTypeMSDFIntegration.create();
try {
    // Load from file path
    FreeTypeMSDFIntegration.Font font = ft.loadFont("/path/to/font.ttf");
    
    // Or load from byte array (embedded resource)
    // byte[] fontData = readResource("fonts/myfont.ttf");
    // FreeTypeMSDFIntegration.Font font = ft.loadFontData(fontData);
    
    // Or load from InputStream
    // InputStream is = getClass().getResourceAsStream("/fonts/myfont.ttf");
    // FreeTypeMSDFIntegration.Font font = ft.loadFont(is);
    
    try {
        // Load glyph by Unicode codepoint
        FreeTypeMSDFIntegration.GlyphData glyph = font.loadGlyph('A');
        MSDFShape shape = glyph.getShape();
        double advance = glyph.getAdvance();
        
        try {
            // Prepare shape for MSDF generation
            shape.normalize();
            shape.edgeColoringSimple(3.0);
            
            // Generate MSDF
            MSDFBitmap bitmap = MSDFBitmap.allocMsdf(32, 32);
            MSDFTransform transform = MSDFTransform.autoFrame(shape, 32, 32, 4.0);
            MSDFGenerator.generateMsdf(bitmap, shape, transform);
            
            float[] pixels = bitmap.getPixelData();
            // ... use pixel data ...
            
            bitmap.free();
        } finally {
            shape.free();  // Caller must free the shape
        }
    } finally {
        font.destroy();
    }
} finally {
    ft.destroy();
}
```

### Coordinate Scaling

When loading glyphs, you can choose the coordinate scaling mode:

| Mode | Constant | Description |
|------|----------|-------------|
| None | `FONT_SCALING_NONE` | Raw font coordinates (large values) |
| EM-normalized | `FONT_SCALING_EM_NORMALIZED` | Normalized to 0..1 EM square (default) |
| Legacy | `FONT_SCALING_LEGACY` | msdfgen 1.x compatible scaling |

```java
// Default: EM-normalized
GlyphData glyph = font.loadGlyph('A');

// Explicit scaling
GlyphData glyph = font.loadGlyph('A', FreeTypeMSDFIntegration.FONT_SCALING_NONE);
```

### Kerning

```java
// Kerning between Unicode codepoints
double kerning = font.getKerning('A', 'V');

// Kerning between glyph indices
int idxA = font.getGlyphIndex('A');
int idxV = font.getGlyphIndex('V');
double kerning = font.getKerningByIndex(idxA, idxV);
```

### Glyph Index Lookup

```java
// Get the glyph index for a codepoint
int glyphIndex = font.getGlyphIndex('A');

// Load glyph by index (useful for glyph atlas generation)
GlyphData glyph = font.loadGlyphByIndex(glyphIndex);
```

## Resource Cleanup

**Critical**: All native handles must be explicitly freed in reverse order:

1. Free `MSDFShape` objects from `GlyphData.getShape()`
2. Free `MSDFBitmap` objects
3. Destroy `Font` objects
4. Destroy `FreeTypeMSDFIntegration` instance

Use try/finally blocks to ensure cleanup on exceptions.

## Error Handling

All methods throw `MSDFException` on failure:

```java
try {
    GlyphData glyph = font.loadGlyph(0xFFFF);  // Uncommon codepoint
} catch (MSDFException e) {
    // Glyph not found in font
    System.err.println("Glyph not available: " + e.getMessage());
}
```

`FreeTypeMSDFIntegration.isAvailable()` never throws — it returns `false` if the native library wasn't compiled with FreeType support.

## Troubleshooting

### "FreeType support is not available"

The native library was built without `-DMSDFGEN_USE_FREETYPE=ON`. Rebuild:

```bash
MSDFGEN_USE_FREETYPE=ON ./build-natives.sh
```

### "Failed to load font: /path/to/font.ttf"

- Verify the font file exists and is readable
- Check that the file is a valid font format (TTF, OTF, TTC, WOFF)
- On macOS, `.ttc` (TrueType Collection) files may require index selection

### CMake: "Could NOT find Freetype"

FreeType development headers are not installed. Install them:
- Linux: `sudo apt-get install libfreetype-dev`
- macOS: `brew install freetype`
- Windows: Use vcpkg or download from https://freetype.org/download.html

### Link errors on Windows

If using vcpkg, ensure you pass the toolchain file:
```bash
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=[vcpkg root]/scripts/buildsystems/vcpkg.cmake ...
```

## Performance Tips

- Reuse `FreeTypeMSDFIntegration` and `Font` instances — don't create/destroy per glyph
- Call `shape.free()` immediately after generating the bitmap
- For batch processing, allocate one bitmap and reuse it (same size)
- EM-normalized coordinates (`FONT_SCALING_EM_NORMALIZED`) produce shapes in 0..1 range, which works well with `MSDFTransform.autoFrame()`
