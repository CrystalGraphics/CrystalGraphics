# Building Native Libraries

## Prerequisites

All platforms need:
- **CMake** 3.14+
- **C/C++ compiler** (GCC, Clang, or MSVC)
- **JDK** with JNI headers (JDK 8+ recommended)
- **pkg-config** (Linux/macOS)
- **curl** (for downloading dependencies)

## Quick Build (Current Platform)

```bash
cd native/freetype-harfbuzz-jni
chmod +x build-natives.sh
./build-natives.sh
```

This will:
1. Download FreeType 2.13.2 and HarfBuzz 8.3.0 source
2. Build both as static libraries
3. Compile the JNI shared library
4. Install to `src/main/resources/natives/{platform}/`

## Platform-Specific Instructions

### Linux (x86_64)
```bash
sudo apt install cmake build-essential pkg-config default-jdk-headless
./build-natives.sh
```

### Linux (aarch64)
Native build on ARM64 hardware, or cross-compile:
```bash
sudo apt install cmake build-essential pkg-config default-jdk-headless \
    gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
export CC=aarch64-linux-gnu-gcc
export CXX=aarch64-linux-gnu-g++
./build-natives.sh
```

### macOS (Intel & Apple Silicon)
```bash
brew install cmake pkg-config
./build-natives.sh
# For x86_64 on Apple Silicon: arch -x86_64 ./build-natives.sh
```

### Windows (x86_64)
Use MSYS2/MinGW64 or Visual Studio Developer Command Prompt:
```bash
# MSYS2/MinGW64
pacman -S mingw-w64-x86_64-cmake mingw-w64-x86_64-gcc pkg-config
./build-natives.sh

# Visual Studio
cmake -S native/freetype-harfbuzz-jni/src/cpp -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```

## Dependency Versions

| Library | Version | Source |
|---------|---------|--------|
| FreeType | 2.13.2 | https://download.savannah.gnu.org/releases/freetype/ |
| HarfBuzz | 8.3.0 | https://github.com/harfbuzz/harfbuzz/releases/ |

## Build Reproducibility

For reproducible builds, pin these:
- **Compiler**: GCC 12+, Clang 15+, or MSVC 19.35+
- **CMake**: 3.14+
- **FreeType**: 2.13.2 (SHA256: `12991c4e55c506dd7f9b765933e62fd2be2e06d421505d7950a132e4f1bb484d`)
- **HarfBuzz**: 8.3.0 (SHA256: `109e55fa7a5cb955b75d77e75bc75d2a4b34d7cf27b0180cd4d8ef29b345eba4`)

## Output Location

Built libraries are placed at:
```
src/main/resources/natives/
├── windows-x86_64/freetype_harfbuzz_jni.dll
├── linux-x86_64/libfreetype_harfbuzz_jni.so
├── linux-aarch64/libfreetype_harfbuzz_jni.so
├── macos-x86_64/libfreetype_harfbuzz_jni.dylib
└── macos-aarch64/libfreetype_harfbuzz_jni.dylib
```
