# Integration Guide: CrystalGraphics + msdfgen-java-bindings

## Adding to CrystalGraphics

### 1. Add Dependency

In CrystalGraphics `dependencies.gradle` or `build.gradle`:

```groovy
implementation("com.msdfgen:msdfgen-java-bindings:1.0.0-SNAPSHOT")
```

Or for JitPack builds:

```groovy
repositories {
    maven { url "https://jitpack.io" }
}
dependencies {
    implementation("com.github.crystalgraphics:msdfgen-java-bindings:main-SNAPSHOT")
}
```

### 2. MSDF Font Atlas Generation

The primary use case is generating MSDF font atlases for high-quality text rendering.

```java
import com.crystalgraphics.msdfgen.*;

public class MsdfFontAtlas {

    /**
     * Generate an MSDF bitmap for a single glyph shape.
     * In a real implementation, the shape would come from FreeType
     * glyph outlines converted to msdfgen Shapes.
     */
    public static float[] generateGlyphMsdf(MSDFShape glyphShape, int size, double pxRange) {
        glyphShape.normalize();
        glyphShape.edgeColoringSimple(3.0);

        MSDFBitmap bitmap = MSDFBitmap.allocMsdf(size, size);
        try {
            MSDFTransform transform = MSDFTransform.autoFrame(glyphShape, size, size, pxRange);
            MSDFGenerator.generateMsdf(bitmap, glyphShape, transform);
            return bitmap.getPixelData();
        } finally {
            bitmap.free();
        }
    }

    /**
     * Upload MSDF data to an OpenGL texture.
     * Uses LWJGL 2.9.3 GL calls (compatible with CrystalGraphics).
     */
    public static int uploadToGlTexture(float[] msdfPixels, int width, int height) {
        // Convert float[0..1] to byte[0..255] for GL_UNSIGNED_BYTE upload
        // Or use GL_FLOAT format if your hardware supports it
        java.nio.FloatBuffer buffer = java.nio.FloatBuffer.wrap(msdfPixels);

        // Using LWJGL 2.9 API:
        // int texId = GL11.glGenTextures();
        // GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        // GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB,
        //     width, height, 0, GL11.GL_RGB, GL11.GL_FLOAT, buffer);
        // GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        // GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // return texId;

        return 0; // Placeholder
    }
}
```

### 3. GLSL Shader for MSDF Rendering

Use this fragment shader in your CrystalGraphics shader pipeline:

```glsl
#version 120

uniform sampler2D u_msdfTexture;
uniform vec4 u_textColor;
uniform float u_pxRange;

varying vec2 v_texCoord;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 msd = texture2D(u_msdfTexture, v_texCoord).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = u_pxRange * (sd - 0.5);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    gl_FragColor = vec4(u_textColor.rgb, u_textColor.a * opacity);
}
```

Note: Uses `#version 120` and `texture2D` for OpenGL 2.1 compatibility (Minecraft 1.7.10).

### 4. Building Shapes from Font Outlines

Without the FreeType extension, you need to construct shapes manually from font data:

```java
public static MSDFShape buildSquare(double x, double y, double size) {
    MSDFShape shape = MSDFShape.create();
    MSDFContour contour = shape.addContour();

    double x2 = x + size;
    double y2 = y + size;

    addLine(contour, x, y, x2, y);
    addLine(contour, x2, y, x2, y2);
    addLine(contour, x2, y2, x, y2);
    addLine(contour, x, y2, x, y);

    return shape;
}

private static void addLine(MSDFContour contour, double x1, double y1, double x2, double y2) {
    MSDFSegment seg = MSDFSegment.createLinear();
    seg.setPoint(0, x1, y1);
    seg.setPoint(1, x2, y2);
    contour.addEdge(seg);
    seg.free();
}
```

### 5. Memory Management

All native objects (MSDFShape, MSDFBitmap, MSDFContour, MSDFSegment) allocate native memory.
Always use try-finally to ensure cleanup:

```java
MSDFShape shape = MSDFShape.create();
try {
    // ... build shape ...

    MSDFBitmap bitmap = MSDFBitmap.allocMsdf(32, 32);
    try {
        MSDFGenerator.generateMsdf(bitmap, shape, transform);
        float[] pixels = bitmap.getPixelData();
        // use pixels...
    } finally {
        bitmap.free();
    }
} finally {
    shape.free();
}
```

### 6. Thread Safety

- MSDFShape/MSDFContour/MSDFSegment construction is NOT thread-safe (single-threaded build)
- SDF generation is CPU-bound but can be parallelized per-glyph
- NativeLoader.load() is synchronized and safe to call from any thread
- MSDFBitmap pixel data extraction creates a Java-side copy (safe after extraction)
