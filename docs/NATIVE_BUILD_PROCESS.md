# Native Library Build Process

This document explains how native libraries (JNI bindings) are built and distributed for CrystalGraphics.

## Overview

CrystalGraphics includes two native libraries that require C++ compilation:
- **MSDFgen**: Multi-channel signed distance field generation
- **FreeType/HarfBuzz**: Font rendering and text shaping

These libraries are **pre-built and committed to git**, so:
- ✅ Developers can clone and immediately run `gradle runClient`
- ✅ Players download the JAR with natives already included
- ✅ Nobody needs CMake, GCC, Visual Studio, or any C++ toolchain

## How It Works

### For End Users (Players)

1. Download the mod JAR: `crystalgraphics-1.0.0.jar`
2. Add to `.minecraft/mods/`
3. Launch Minecraft
4. Done ✓

The JAR contains:
- Java classes for the mod
- Native libraries for all 6 platforms (Windows/Linux/macOS × 64-bit/ARM64)
- When Minecraft loads the mod, the native libraries are automatically extracted to a temp directory and loaded

**No C++ tools needed.**

### For Developers (You)

```bash
git clone <repo>
cd CrystalGraphics
gradle runClient
```

The `gradle runClient` task will use the pre-built natives from `src/main/resources/natives/`.

**No C++ tools needed.** (Unless you're modifying the C++ code)

## Building Natives (If You Modify C++ Code)

### Option 1: GitHub Actions (Recommended)

Push your changes to the `font-bindings` branch. GitHub Actions automatically:
1. Builds natives on Linux (Ubuntu), macOS, and Windows runners
2. Cross-compiles for aarch64 (ARM64)
3. Uploads all artifacts

Then manually trigger the "commit natives" step to commit them back to the repo.

**Triggered automatically on**:
- Push to any changes in `msdfgen-java-bindings/native/**`
- Push to any changes in `freetype-harfbuzz-java-bindings/native/**`
- Manual workflow dispatch from GitHub Actions UI

### Option 2: Build Locally

If you have the C++ toolchain installed, you can build locally:

#### Linux (x64 + aarch64)
```bash
cd freetype-harfbuzz-java-bindings/native/freetype-harfbuzz-jni
chmod +x build-natives.sh
./build-natives.sh
# Outputs to: freetype-harfbuzz-java-bindings/src/main/resources/natives/linux-x64/
```

Same for MSDFgen:
```bash
cd msdfgen-java-bindings/native/msdfgen-jni
chmod +x build-natives.sh
./build-natives.sh
```

#### macOS (x64 + aarch64)
```bash
cd freetype-harfbuzz-java-bindings/native/freetype-harfbuzz-jni
chmod +x build-natives.sh
./build-natives.sh
```

#### Windows (x64 + x86)
```bash
cd freetype-harfbuzz-java-bindings\native\freetype-harfbuzz-jni
build-natives.bat
```

### Requirements (If Building Locally)

**All Platforms**:
- CMake 3.15+
- C++ compiler (GCC 9+, Clang 10+, MSVC 2019+, Apple Clang)
- Java 8 JDK (for JNI headers)

**Linux**:
```bash
sudo apt-get install cmake build-essential libfreetype6-dev libharfbuzz-dev
```

**macOS**:
```bash
brew install cmake freetype harfbuzz
```

**Windows**:
- Visual Studio 2019+ (or CMake with MinGW/LLVM)
- FreeType + HarfBuzz (downloaded automatically by CI workflow)

## Platform Support

| Platform | MSDFgen | FreeType/HarfBuzz |
|----------|---------|-------------------|
| Windows x64 (Intel) | ✅ | ✅ |
| Windows x86 (32-bit) | ✅ | ✅ |
| Linux x64 (Intel) | ✅ | ✅ |
| Linux ARM64 (aarch64) | ✅ | ✅ |
| macOS x64 (Intel) | ✅ | ✅ |
| macOS ARM64 (Apple Silicon) | ✅ | ✅ |

All 6 platforms are built automatically via GitHub Actions.

## File Structure

```
CrystalGraphics/
├── .github/workflows/
│   └── build-natives.yml              ← GitHub Actions CI/CD
├── msdfgen-java-bindings/
│   ├── native/msdfgen-jni/
│   │   ├── src/cpp/                   ← C++ source (JNI bindings)
│   │   ├── build-natives.sh           ← Build script
│   │   └── build-natives.bat
│   └── src/main/resources/natives/
│       ├── windows-x64/msdfgen-jni.dll
│       ├── windows-x86/msdfgen-jni.dll
│       ├── linux-x64/libmsdfgen-jni.so
│       ├── linux-aarch64/libmsdfgen-jni.so
│       ├── macos-x64/libmsdfgen-jni.dylib
│       └── macos-aarch64/libmsdfgen-jni.dylib
└── freetype-harfbuzz-java-bindings/
    ├── native/freetype-harfbuzz-jni/
    │   ├── src/cpp/                   ← C++ source (JNI + FT/HB libs)
    │   ├── build-natives.sh
    │   └── build-natives.bat
    └── src/main/resources/natives/
        ├── windows-x64/freetype_harfbuzz_jni.dll
        ├── windows-x86/freetype_harfbuzz_jni.dll
        ├── linux-x64/libfreetype_harfbuzz_jni.so
        ├── linux-aarch64/libfreetype_harfbuzz_jni.so
        ├── macos-x64/libfreetype_harfbuzz_jni.dylib
        └── macos-aarch64/libfreetype_harfbuzz_jni.dylib
```

## How Natives Get Into the JAR

1. **Build time** (GitHub Actions or local):
   - C++ source → CMake → Compiler → `.dll`/`.so`/`.dylib`
   - Placed in `src/main/resources/natives/{platform}/`

2. **Gradle build**:
   - `gradle build` packages resources into JAR
   - JAR now contains natives for all platforms in `/natives/` directory

3. **Runtime** (when Minecraft loads mod):
   - NativeLoader extracts native to temp directory
   - JVM loads native from temp using `System.load()`
   - Native library ready for use

## Troubleshooting

### "UnsatisfiedLinkError" at Runtime

**Cause**: Native library not found or not loaded

**Solutions**:
1. Check that natives are in the JAR: `jar tf crystalgraphics-1.0.0.jar | grep natives`
2. Check NativeLoader debug output: Set system property `-Djava.library.path=...` 
3. Verify platform detection: Check console for `[DEBUG]` messages in NativeLoader

### Build Fails in GitHub Actions

1. Check workflow logs: https://github.com/[owner]/CrystalGraphics/actions
2. Common issues:
   - Missing dependencies (auto-installed on CI)
   - CMake version too old (CI has latest)
   - Cross-compilation failure (check aarch64 jobs)

### Local Build Fails

1. Install all required tools (see "Requirements" section)
2. Check CMakeLists.txt for correct paths
3. Ensure JDK 8+ is installed: `java -version`
4. Run with verbose output: `./build-natives.sh --verbose`

## Contributing

If you modify the C++ code:

1. **Push to `font-bindings` branch**
   ```bash
   git push origin HEAD:font-bindings
   ```

2. **GitHub Actions automatically builds** for all 6 platforms

3. **Check workflow results**: https://github.com/[owner]/CrystalGraphics/actions

4. **Download and test artifacts** or merge PR and trigger commit workflow

5. **Natives are committed** back to repo automatically (or manually)

That's it! No local C++ compilation needed.

## References

- [LWJGL Native Loading](https://www.lwjgl.org/guide) - Pattern we use for native extraction
- [JNI Specification](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/) - JNI standards
- [GitHub Actions](https://github.com/features/actions) - CI/CD platform
