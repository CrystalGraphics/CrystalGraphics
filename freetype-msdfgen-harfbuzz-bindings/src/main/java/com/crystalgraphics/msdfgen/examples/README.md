# MSDFgen Java Bindings - Examples

## Example Files

### BasicSDF.java
Minimal example that creates a shape manually (no FreeType needed) and generates an MSDF bitmap. Shows the core workflow:
1. Create `Shape` → add `Contour` → add `Segment` edges
2. Normalize and color edges
3. Generate MSDF with `Generator.generateMsdf()`
4. Read pixel data

```bash
java -cp msdfgen-java-bindings.jar com.msdfgen.examples.BasicSDF
```

### AdvancedSDF.java
Loads a real font via FreeType, renders multiple glyphs with error correction, and outputs glyph metrics. Falls back to manual shape construction if FreeType is unavailable.

```bash
# With system font auto-detection
java -cp msdfgen-java-bindings.jar com.msdfgen.examples.AdvancedSDF

# With explicit font path
java -cp msdfgen-java-bindings.jar com.msdfgen.examples.AdvancedSDF /path/to/font.ttf

# With font and custom text
java -cp msdfgen-java-bindings.jar com.msdfgen.examples.AdvancedSDF /path/to/font.ttf "MSDF!"
```

### PerformanceExample.java
Stress test that benchmarks shape generation, bitmap allocation, and FreeType glyph loading. Reports timing per operation and memory usage.

```bash
java -cp msdfgen-java-bindings.jar com.msdfgen.examples.PerformanceExample
```

## FreeType Support

The `AdvancedSDF` and `PerformanceExample` examples support FreeType font loading. This requires the native library to be compiled with FreeType support:

```bash
cd native/msdfgen-jni
MSDFGEN_USE_FREETYPE=ON ./build-natives.sh
```

Without FreeType, both examples gracefully fall back to manual shape demos or skip font-related benchmarks.

See [docs/FREETYPE_INTEGRATION.md](../docs/FREETYPE_INTEGRATION.md) for details.

## Common Patterns

### Shape Construction (No FreeType)
```java
Shape shape = Shape.create();
try {
    Contour contour = shape.addContour();
    Segment edge = Segment.createLinear();
    edge.setPoint(0, x1, y1);
    edge.setPoint(1, x2, y2);
    contour.addEdge(edge);
    // ... more edges ...
    shape.normalize();
    shape.edgeColoringSimple(3.0);
} finally {
    shape.free();
}
```

### MSDF Generation
```java
Bitmap bitmap = Bitmap.allocMsdf(width, height);
try {
    Transform transform = Transform.autoFrame(shape, width, height, pxRange);
    Generator.generateMsdf(bitmap, shape, transform);
    float[] pixels = bitmap.getPixelData();
} finally {
    bitmap.free();
}
```

### FreeType Font Loading
```java
if (FreeTypeIntegration.isAvailable()) {
    FreeTypeIntegration ft = FreeTypeIntegration.create();
    try {
        FreeTypeIntegration.Font font = ft.loadFont("font.ttf");
        try {
            FreeTypeIntegration.GlyphData glyph = font.loadGlyph('A');
            Shape shape = glyph.getShape();
            try {
                // shape is ready for MSDF generation
            } finally {
                shape.free();
            }
        } finally {
            font.destroy();
        }
    } finally {
        ft.destroy();
    }
}
```

### Pixel Data Interpretation

MSDF bitmap pixels are `float` values, typically in the 0..1 range after distance mapping. For a 3-channel MSDF:
- Array layout: `[y * width * 3 + x * 3 + channel]`
- Channel 0 = Red, 1 = Green, 2 = Blue
- To reconstruct the glyph boundary: compute `median(R, G, B)` and threshold at 0.5

### Common Pitfalls

1. **Always free native objects** - `Shape`, `Bitmap`, `Font`, and `FreeTypeIntegration` all hold native memory. Use try/finally.
2. **Free in reverse order** - Free shapes and bitmaps before fonts, fonts before FreeType instance.
3. **Don't use freed objects** - Calling methods on freed objects throws `IllegalStateException`.
4. **Shape must be normalized** - Call `shape.normalize()` before generation.
5. **Edge coloring is required for MSDF** - Call `shape.edgeColoringSimple(3.0)` before `generateMsdf()`.
