# msdfgen-java-bindings

Java JNI bindings for [MSDFgen](https://github.com/Chlumsky/msdfgen) (Multi-Channel Signed Distance Field Generator).

Designed for LWJGL 2.9.3 compatibility (no LWJGL 3.x dependency). Ships native binaries for all major platforms.

## Supported Platforms

| Platform | Architecture | Library |
|----------|-------------|---------|
| Windows  | x64         | `msdfgen-jni.dll` |
| Linux    | x64         | `libmsdfgen-jni.so` |
| Linux    | aarch64     | `libmsdfgen-jni.so` |
| macOS    | x64 (Intel) | `libmsdfgen-jni.dylib` |
| macOS    | aarch64 (M1/M2/M3) | `libmsdfgen-jni.dylib` |

## Quick Start

```java
import com.msdfgen.*;

// Create a simple square shape
Shape shape = Shape.create();
Contour contour = shape.addContour();

Segment top = Segment.createLinear();
top.setPoint(0, 0, 1);
top.setPoint(1, 1, 1);
contour.addEdge(top);

Segment right = Segment.createLinear();
right.setPoint(0, 1, 1);
right.setPoint(1, 1, 0);
contour.addEdge(right);

Segment bottom = Segment.createLinear();
bottom.setPoint(0, 1, 0);
bottom.setPoint(1, 0, 0);
contour.addEdge(bottom);

Segment left = Segment.createLinear();
left.setPoint(0, 0, 0);
left.setPoint(1, 0, 1);
contour.addEdge(left);

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

## Dependency

### Gradle (build.gradle.kts)
```kotlin
dependencies {
    implementation("com.msdfgen:msdfgen-java-bindings:1.0.0-SNAPSHOT")
}
```

### Maven (pom.xml)
```xml
<dependency>
    <groupId>com.msdfgen</groupId>
    <artifactId>msdfgen-java-bindings</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Architecture

### Binding Approach: JNI (not JNA)

We chose JNI over JNA for these reasons:

1. **Performance**: JNI has ~10x less call overhead than JNA. SDF generation is CPU-intensive; minimizing FFI overhead matters.
2. **Minecraft modding**: The JVM in MC 1.7.10 is Java 8 with no Panama/FFM. JNI is the standard native integration path.
3. **Static linking**: MSDFgen C++ code is compiled directly into the JNI shared library. No separate msdfgen.dll to distribute.
4. **LWJGL 2.9 uses JNI**: Consistent with the existing native integration pattern.

### Native Library Loading

`NativeLoader` loads the native library using a 3-step fallback:

1. **Explicit path** (`-Dmsdfgen.library.path=/path/to/lib`)
2. **Classpath extraction** (extracts from JAR to temp directory)
3. **System library path** (`java.library.path`)

### API Design

- `Shape` / `Contour` / `Segment` - vector shape construction (mirrors msdfgen C++ API)
- `Bitmap` - pixel data container (SDF/PSDF/MSDF/MTSDF types)
- `Generator` - SDF generation entry points
- `Transform` - projection + distance range configuration
- All native objects must be explicitly `free()`'d

## Building Native Libraries

See [docs/BUILD_NATIVES.md](docs/BUILD_NATIVES.md) for detailed instructions.

Quick build (current platform):
```bash
cd native/msdfgen-jni
./build-natives.sh
```

## Platform Notes

- [macOS Compatibility](docs/MACOS_COMPATIBILITY.md) - M1/M2/M3 support, code signing, testing
- [Build Natives](docs/BUILD_NATIVES.md) - Cross-compilation, toolchain requirements
- [Integration Guide](docs/INTEGRATION_GUIDE.md) - Using with CrystalGraphics/Minecraft 1.7.10

## License

MIT License (same as MSDFgen)
