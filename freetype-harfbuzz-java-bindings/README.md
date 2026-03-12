# FreeType + HarfBuzz Java Bindings

JNI bindings for [FreeType](https://freetype.org/) and [HarfBuzz](https://harfbuzz.github.io/) text rendering libraries, designed for LWJGL 2.9.3 and Java 8 compatibility.

## Why JNI (Not JNA)?

**JNI was chosen over JNA** for the following reasons:

1. **Performance**: JNI has ~10x lower call overhead than JNA. Text shaping involves thousands of native calls per frame — JNA's reflection-based FFI would be a bottleneck.
2. **Memory control**: JNI gives direct control over native memory lifecycle (critical for FreeType's `FT_New_Memory_Face` which requires caller-managed buffers).
3. **FreeType-HarfBuzz integration**: The `hb_ft_font_create()` bridge passes raw C pointers between libraries — JNA cannot express this safely.
4. **LWJGL 2.9.3 compatibility**: LWJGL 2.x itself uses JNI, so this approach is architecturally consistent.
5. **Type safety**: JNI compile-time checks catch signature mismatches that JNA would fail at runtime.

## Supported Platforms

| Platform | Architecture | Status |
|----------|-------------|--------|
| Windows  | x86_64      | ✅     |
| Linux    | x86_64      | ✅     |
| Linux    | aarch64     | ✅     |
| macOS    | x86_64      | ✅     |
| macOS    | aarch64 (Apple Silicon) | ✅ |

## Quick Start

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

## Native Library Loading

The loader searches in order:
1. `-Dfreetype.harfbuzz.native.path=/path/to/dir`
2. Classpath: `/natives/{platform}/libfreetype_harfbuzz_jni.{so,dylib,dll}`
3. System `java.library.path`

## Building from Source

See [docs/BUILD_NATIVES.md](docs/BUILD_NATIVES.md).

## API Reference

- [FreeType API](docs/FREETYPE_API.md)
- [HarfBuzz API](docs/HARFBUZZ_API.md)
- [Text Rendering Guide](docs/TEXT_RENDERING_GUIDE.md)
- [macOS Compatibility](docs/MACOS_COMPATIBILITY.md)

## Dependencies

- **FreeType** 2.13.2 (statically linked)
- **HarfBuzz** 8.3.0 (statically linked)
- **Java** 8+
- No LWJGL 3.x dependency

## Version Compatibility

| This Library | FreeType | HarfBuzz | Java | LWJGL 2.x |
|-------------|----------|----------|------|-----------|
| 1.0.0       | 2.13.2   | 8.3.0    | 8+   | 2.9.3+    |

## License

MIT License. FreeType is FTL/GPL2. HarfBuzz is MIT.
