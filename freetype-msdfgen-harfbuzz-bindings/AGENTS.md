# freetype-msdfgen-harfbuzz-bindings — Agent Knowledge Base

## What This Is

JNI bindings for **FreeType** (font loading), **HarfBuzz** (text shaping), and **MSDFgen** (SDF generation), compiled into a single native shared library (`freetype_msdfgen_harfbuzz_jni`) per platform using **Zig as a cross-compiler**.

## Build System Architecture

```
build.gradle.kts           — Java project config + apply(from = "native-build.gradle.kts")
native-build.gradle.kts    — Zig-based cross-platform native compilation tasks
gradle.properties          — All configurable values (versions, targets, flags)
include/jni/               — Platform-independent JNI headers for cross-compilation
  ├── jni.h
  ├── win32/jni_md.h
  ├── linux/jni_md.h
  └── darwin/jni_md.h
native/
  ├── freetype-msdfgen-harfbuzz-jni/
  │   ├── src/cpp/           — JNI C++ source files (4 files)
  │   ├── overrides/         — msdfgen source overrides
  │   └── CMakeLists.txt     — Legacy CMake build (still works, but Zig preferred)
  └── msdfgen-jni/msdfgen/   — MSDFgen source (git clone of v1.13)
```

### Configuration Properties (gradle.properties)

All native build settings are externalized to `gradle.properties` with `nativeBuild.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `nativeBuild.zig.version` | `0.11.0` | Zig compiler version |
| `nativeBuild.freetype.version` | `2.13.2` | FreeType source version |
| `nativeBuild.harfbuzz.version` | `8.3.0` | HarfBuzz source version |
| `nativeBuild.targets` | all 5 platforms | Comma-separated Zig target triples |
| `nativeBuild.libraryName` | `freetype_msdfgen_harfbuzz_jni` | Output library base name |
| `nativeBuild.cOptLevel` | `-O2` | C/C++ optimization level |
| `nativeBuild.cDebugLevel` | `-g0` | Debug info level |
| `nativeBuild.cppStandard` | `-std=c++11` | C++ standard |

Override via command line: `-PnativeBuild.targets=x86_64-windows`

### Tree-sitter-ng Pattern

This build mirrors [tree-sitter-ng-v0.26.6](https://github.com/bonede/tree-sitter-ng):
- **Zig 0.11.0** used as a drop-in C/C++ cross-compiler (`zig cc`, `zig c++`)
- No CMake needed — Zig compiles all sources directly to `.o` files, then links
- `-target <triple>` flag enables cross-compilation to any supported platform
- JNI headers shipped locally (no JDK required on target platform)

## Gradle Tasks

All tasks are in the `native build` group:

| Task | Description |
|------|-------------|
| `downloadZig` | Download Zig 0.11.0 compiler (auto-skips if on PATH) |
| `downloadNativeDeps` | Download FreeType 2.13.2 + HarfBuzz 8.3.0 source tarballs |
| `buildNatives` | Cross-compile for all targets (depends on `downloadNativeDeps`) |
| `buildNativesLocal` | Prints command to build for current platform only |
| `cleanNatives` | Remove all native build artifacts and output binaries |

## How to Build

### Prerequisites
- **Java 17+** (for Gradle 9.x — the compiled library targets Java 8)
  - Set `JAVA_HOME` to JDK 17+ (e.g., `set JAVA_HOME=C:\Program Files\Java\jdk-21` on Windows)
- **Zig 0.11.0** (auto-downloaded by `downloadZig`, or install manually)
- **tar** (for extracting .tar.xz archives — available on Win10+, Linux, macOS)
- **msdfgen source** at `native/msdfgen-jni/msdfgen/` (clone v1.13)

### Build all platforms
```bash
# Set JAVA_HOME to JDK 17+ if needed
export JAVA_HOME="/path/to/jdk-21"

# Build for all 5 targets
./gradlew buildNatives

# Or from parent CrystalGraphics project:
./gradlew :freetype-msdfgen-harfbuzz-bindings:buildNatives
```

### Build single platform (faster)
```bash
# Windows x64 only (new property name)
./gradlew buildNatives -PnativeBuild.targets=x86_64-windows

# Linux x64 only (legacy property name also works)
./gradlew buildNatives -PnativeTargets=x86_64-linux-gnu

# macOS (both architectures)
./gradlew buildNatives -PnativeBuild.targets=x86_64-macos,aarch64-macos
```

### Custom Zig path
```bash
./gradlew buildNatives -PzigExe=/path/to/zig
```

## Supported Targets

| Zig Target | Output Directory | Library Name |
|-----------|-----------------|-------------|
| `x86_64-windows` | `natives/windows-x64/` | `freetype_msdfgen_harfbuzz_jni.dll` |
| `x86_64-linux-gnu` | `natives/linux-x64/` | `libfreetype_msdfgen_harfbuzz_jni.so` |
| `x86_64-macos` | `natives/macos-x64/` | `libfreetype_msdfgen_harfbuzz_jni.dylib` |
| `aarch64-macos` | `natives/macos-aarch64/` | `libfreetype_msdfgen_harfbuzz_jni.dylib` |
| `aarch64-linux-gnu` | `natives/linux-aarch64/` | `libfreetype_msdfgen_harfbuzz_jni.so` |

## Build Phases (per target)

1. **FreeType** — Compile ~42 C files with `zig cc -DFT2_BUILD_LIBRARY`
2. **HarfBuzz** — Compile `harfbuzz.cc` (unity build) with `zig c++ -DHAVE_FREETYPE`
3. **MSDFgen** — Compile ~28 C++ files (core + ext) with msdfgen defines
4. **JNI** — Compile 4 JNI C++ source files with all include paths
5. **Link** — `zig c++ -shared` all object files into the output shared library

Typical build time: **~10-15 seconds** per target (with cached Gradle daemon), **~45 seconds** cold start.

## Output Structure (verified sizes)

```
src/main/resources/natives/
├── windows-x64/
│   └── freetype_msdfgen_harfbuzz_jni.dll       (2.3 MB)
├── linux-x64/
│   └── libfreetype_msdfgen_harfbuzz_jni.so     (4.9 MB)
├── linux-aarch64/
│   └── libfreetype_msdfgen_harfbuzz_jni.so     (5.0 MB)
├── macos-x64/
│   └── libfreetype_msdfgen_harfbuzz_jni.dylib  (2.9 MB)
└── macos-aarch64/
    └── libfreetype_msdfgen_harfbuzz_jni.dylib  (2.9 MB)
```

Total: ~18 MB across all platforms.

## Troubleshooting

### Gradle fails with "requires JVM 17 or later"
Set `JAVA_HOME` to a JDK 17+ installation:
```bash
# Windows
set JAVA_HOME=C:\Program Files\Java\jdk-21
# Linux/macOS  
export JAVA_HOME=/path/to/jdk-21
```

### `tar` fails on Windows with "Cannot connect to X:"
Windows bsdtar interprets drive letters as remote hosts. The build script works around this by running `tar` from the archive's directory using relative filenames. If manual extraction is needed, use Git Bash: `cd deps && tar xf freetype.tar.xz`

### Zig not found
Three options:
1. `./gradlew downloadZig` — auto-downloads to `build/native-build/zig/`
2. Install Zig 0.11.0 and add to PATH
3. `-PzigExe=/path/to/zig`

### Linker errors for `FT_Trace_Disable` / `FT_Trace_Enable`
Ensure `src/base/ftdebug.c` is in the FreeType source file list. This is required for FreeType 2.13.2.

### msdfgen source not found
Clone msdfgen v1.13:
```bash
cd native/msdfgen-jni
git clone --depth 1 --branch v1.13 https://github.com/Chlumsky/msdfgen.git
```

## Dependencies Statically Linked

- **FreeType 2.13.2** — compiled from source per target
- **HarfBuzz 8.3.0** — compiled from source per target (with FreeType integration)
- **MSDFgen v1.13** — compiled from source per target (with FreeType extension)
- **C++ stdlib** — Zig links its own bundled libc++/libunwind

No runtime dependencies other than the OS C runtime.

## Key Files

| File | Purpose |
|------|---------|
| `gradle.properties` | All configurable native build values (versions, targets, flags) |
| `native-build.gradle.kts` | All Zig build tasks and helpers |
| `build.gradle.kts` | Java project config, applies native-build.gradle.kts |
| `include/jni/` | JNI headers for cross-compilation |
| `native/freetype-msdfgen-harfbuzz-jni/src/cpp/` | JNI C++ sources |
| `native/msdfgen-jni/msdfgen/` | MSDFgen source tree |
| `native/freetype-msdfgen-harfbuzz-jni/overrides/` | Local msdfgen patches |

## Configuration Cache

Native build tasks use `notCompatibleWithConfigurationCache()` + `doNotTrackState()` because they invoke
external commands (Zig compiler, tar). The build works correctly with `--configuration-cache` — cache
entries are properly discarded for native tasks while other tasks benefit from caching.

To suppress the informational warnings, omit the `--configuration-cache` flag when running native tasks.
