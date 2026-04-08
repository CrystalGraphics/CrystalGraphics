# FreeType + MSDFgen + HarfBuzz Java Bindings

JNI bindings for [FreeType](https://freetype.org/), [MSDFgen](https://github.com/Chlumsky/msdfgen), and [HarfBuzz](https://harfbuzz.github.io/) — designed for LWJGL 2.9.3 and Java 8 compatibility.

This is a unified native bindings library that provides:
- **FreeType**: Font loading, glyph rasterization, bitmap rendering
- **MSDFgen**: Multi-Channel Signed Distance Field generation for GPU text rendering
- **HarfBuzz**: OpenType text shaping with full Unicode support

## Why JNI (Not JNA)?

**JNI was chosen over JNA** for the following reasons:

1. **Performance**: JNI has ~10x lower call overhead than JNA. Text shaping involves thousands of native calls per frame — JNA's reflection-based FFI would be a bottleneck. SDF generation is also CPU-intensive.
2. **Memory control**: JNI gives direct control over native memory lifecycle (critical for FreeType's `FT_New_Memory_Face` which requires caller-managed buffers).
3. **FreeType-HarfBuzz integration**: The `hb_ft_font_create()` bridge passes raw C pointers between libraries — JNA cannot express this safely.
4. **LWJGL 2.9.3 compatibility**: LWJGL 2.x itself uses JNI, so this approach is architecturally consistent.
5. **Type safety**: JNI compile-time checks catch signature mismatches that JNA would fail at runtime.
6. **Static linking**: MSDFgen C++ code is compiled directly into the JNI shared library. No separate msdfgen.dll to distribute.

## Supported Platforms

| Platform | Architecture | FreeType+HarfBuzz | MSDFgen |
|----------|-------------|-------------------|---------|
| Windows  | x86_64      | ✅                | ✅      |
| Windows  | x86         | ✅                | ✅      |
| Linux    | x86_64      | ✅                | ✅      |
| macOS    | x86_64      | ✅                | ✅      |
| macOS    | aarch64 (Apple Silicon) | ✅     | ✅      |

## Quick Start — FreeType + HarfBuzz

```java
FreeTypeLibrary ft = FreeTypeLibrary.create();
FTFace face = ft.newFace("/path/to/font.ttf", 0);
face.setPixelSizes(0, 24);

// Create HarfBuzz font from FreeType face
HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

// Shape text
HBBuffer buffer = HBBuffer.create();
buffer.addUTF8("Hello, World!");
buffer.guessSegmentProperties();
HBShape.shape(hbFont, buffer);

// Get results
HBGlyphInfo[] infos = buffer.getGlyphInfos();
HBGlyphPosition[] positions = buffer.getGlyphPositions();

// Render each glyph
for (int i = 0; i < infos.length; i++) {
    face.loadGlyph(infos[i].getCodepoint(), FTLoadFlags.FT_LOAD_RENDER);
    FTBitmap bitmap = face.getGlyphBitmap();
    // ... upload bitmap to GL texture at positioned offset ...
}

// Cleanup (order matters: HB before FT)
buffer.destroy();
hbFont.destroy();
face.destroy();
ft.destroy();
```

## Quick Start — MSDFgen

```java
import com.crystalgraphics.msdfgen.*;

// Create a simple square shape
Shape shape = Shape.create();
Contour contour = shape.addContour();

Segment top = Segment.createLinear();
top.setPoint(0, 0, 1);
top.setPoint(1, 1, 1);
contour.addEdge(top);

// ... add more segments ...

// Prepare shape
shape.normalize();
shape.edgeColoringSimple(3.0);

// Generate MSDF
Bitmap bitmap = Bitmap.allocMsdf(32, 32);
Transform transform = Transform.autoFrame(shape, 32, 32, 4.0);
Generator.generateMsdf(bitmap, shape, transform);

// Get pixel data
float[] pixels = bitmap.getPixelData(); // 32 * 32 * 3 floats

// Clean up native memory
bitmap.free();
shape.free();
```

## Native Library Loading

### FreeType + HarfBuzz
1. `-Dfreetype.harfbuzz.native.path=/path/to/dir`
2. Classpath: `/natives/{platform}/libfreetype_harfbuzz_jni.{so,dylib,dll}`
3. System `java.library.path`

### MSDFgen
1. `-Dmsdfgen.library.path=/path/to/lib`
2. Classpath extraction (extracts from JAR to temp directory)
3. System `java.library.path`

## Building from Source

See [docs/BUILD_NATIVES.md](docs/BUILD_NATIVES.md) for FreeType+HarfBuzz and [docs/MSDFGEN_BUILD_NATIVES.md](docs/MSDFGEN_BUILD_NATIVES.md) for MSDFgen.

## API Reference

### FreeType + HarfBuzz
- [FreeType API](docs/FREETYPE_API.md)
- [HarfBuzz API](docs/HARFBUZZ_API.md)
- [Text Rendering Guide](docs/TEXT_RENDERING_GUIDE.md)
- [macOS Compatibility](docs/MACOS_COMPATIBILITY.md)

### MSDFgen
- [MSDFgen FreeType Integration](docs/MSDFGEN_FREETYPE_INTEGRATION.md)
- [MSDFgen Integration Guide](docs/MSDFGEN_INTEGRATION_GUIDE.md)
- [MSDFgen Build Natives](docs/MSDFGEN_BUILD_NATIVES.md)
- [MSDFgen macOS Compatibility](docs/MSDFGEN_MACOS_COMPATIBILITY.md)

### MSDFgen API Design
- `Shape` / `Contour` / `Segment` - vector shape construction (mirrors msdfgen C++ API)
- `Bitmap` - pixel data container (SDF/PSDF/MSDF/MTSDF types)
- `Generator` - SDF generation entry points
- `Transform` - projection + distance range configuration
- All native objects must be explicitly `free()`'d

## Dependencies

- **FreeType** 2.13.2 (statically linked)
- **HarfBuzz** 8.3.0 (statically linked)
- **MSDFgen** v1.13 (statically linked)
- **Java** 8+
- No LWJGL 3.x dependency

## Version Compatibility

| This Library | FreeType | HarfBuzz | MSDFgen | Java | LWJGL 2.x |
|-------------|----------|----------|---------|------|-----------|
| 1.0.0       | 2.13.2   | 8.3.0    | v1.13   | 8+   | 2.9.3+    |

## License

MIT License. FreeType is FTL/GPL2. HarfBuzz is MIT. MSDFgen is MIT.
