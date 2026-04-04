# Building Native Libraries

## Prerequisites

All platforms:
- CMake 3.15+
- JDK 8+ with `JAVA_HOME` set (for JNI headers)
- Git (to clone msdfgen source)

### Windows
- Visual Studio 2019+ with C++ Desktop workload, OR
- MinGW-w64 with GCC 8+

### Linux
- GCC 8+ or Clang 10+
- `build-essential` package (Ubuntu/Debian)

### macOS
- Xcode Command Line Tools (`xcode-select --install`)
- For Apple Silicon builds: Xcode 12+, macOS 11.0+ SDK

## Quick Build (Current Platform)

```bash
cd native/msdfgen-jni
./build-natives.sh    # Linux/macOS
build-natives.bat     # Windows
```

### With FreeType Support

```bash
cd native/msdfgen-jni
MSDFGEN_USE_FREETYPE=ON ./build-natives.sh    # Linux/macOS

# Windows
set MSDFGEN_USE_FREETYPE=ON
build-natives.bat
```

FreeType must be installed on the build system. See [FREETYPE_INTEGRATION.md](FREETYPE_INTEGRATION.md) for details.

This will:
1. Clone msdfgen v1.13 if not already present
2. Apply CrystalGraphics local source overrides to the cloned msdfgen tree
3. Build the JNI shared library with msdfgen statically linked
4. Copy the result to `src/main/resources/natives/<platform>/`

### Local Source Overrides

CrystalGraphics carries tracked native overrides under:

```text
native/msdfgen-jni/overrides/msdfgen/
```

These files are copied into the freshly cloned upstream `msdfgen` checkout before
the JNI library is built. This is required because the build scripts clone upstream
`msdfgen` on demand, so ad-hoc edits inside `native/msdfgen-jni/msdfgen/` would
otherwise be lost during CI/native rebuilds.

## Build Output Locations

| Platform | Output Path |
|----------|-------------|
| Windows x64 | `src/main/resources/natives/windows-x64/msdfgen-jni.dll` |
| Linux x64 | `src/main/resources/natives/linux-x64/libmsdfgen-jni.so` |
| Linux aarch64 | `src/main/resources/natives/linux-aarch64/libmsdfgen-jni.so` |
| macOS x64 | `src/main/resources/natives/macos-x64/libmsdfgen-jni.dylib` |
| macOS aarch64 | `src/main/resources/natives/macos-aarch64/libmsdfgen-jni.dylib` |

## Cross-Compilation

### Linux ARM64 from x64

Install the aarch64 cross-compiler:
```bash
sudo apt-get install gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
```

Then build:
```bash
cmake -S . -B build-aarch64 \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
    -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
    -DCMAKE_BUILD_TYPE=Release \
    -DMSDFGEN_SOURCE_DIR=./msdfgen
cmake --build build-aarch64
```

### macOS Cross-Architecture

See [MACOS_COMPATIBILITY.md](MACOS_COMPATIBILITY.md) for building Intel and ARM64 binaries.

## CI/CD Build

GitHub Actions workflow for all platforms:

```yaml
name: Build Natives
on: [push]
jobs:
  build:
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            platform: linux-x64
          - os: ubuntu-24.04-arm
            platform: linux-aarch64
          - os: windows-latest
            platform: windows-x64
          - os: macos-13
            platform: macos-x64
          - os: macos-latest
            platform: macos-aarch64
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: 'temurin'
      - name: Build native library
        working-directory: native/msdfgen-jni
        run: |
          chmod +x build-natives.sh 2>/dev/null || true
          ./build-natives.sh || build-natives.bat
      - uses: actions/upload-artifact@v4
        with:
          name: natives-${{ matrix.platform }}
          path: src/main/resources/natives/${{ matrix.platform }}/
```

## Reproducible Builds

### Exact Versions Used

| Component | Version |
|-----------|---------|
| msdfgen | v1.13 (git tag) |
| CMake | 3.15+ |
| C++ Standard | C++11 |
| JNI | Java 8 (1.8) |

### Compiler Flags

```
MSVC:    /W3 /O2
GCC:     -Wall -Wextra -O2
Clang:   -Wall -Wextra -O2
```

### Definitions

```
MSDFGEN_PUBLIC=        (no DLL export/import)
MSDFGEN_USE_CPP11      (enable move semantics in msdfgen)
MSDFGEN_USE_FREETYPE   (only when FreeType support is enabled)
MSDFGEN_EXTENSIONS     (only when FreeType support is enabled)
```
