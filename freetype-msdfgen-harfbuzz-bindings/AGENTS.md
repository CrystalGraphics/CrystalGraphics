# freetype-msdfgen-harfbuzz-bindings — Agent Knowledge Base

## What This Is

JNI bindings for **FreeType** (font loading), **HarfBuzz** (text shaping), and **MSDFgen** (SDF generation), compiled into a single native shared library (`freetype_msdfgen_harfbuzz_jni`) per platform using **Zig as a cross-compiler**.

## Build System Architecture

The build system is **library-agnostic**: all library definitions are in `gradle.properties`. The Gradle script reads the library list, compiles each one, and links all objects into a single shared library.

```
build.gradle.kts           — Java project config + apply(from = "native-build.gradle.kts")
native-build.gradle.kts    — Generic Zig-based cross-platform build (no hardcoded library names)
gradle.properties          — All library definitions, versions, targets, compiler flags
include/jni/               — Platform-independent JNI headers for cross-compilation
native/                    — Native source trees (local sources, overrides, git clones)
```

### Global Properties (gradle.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `nativeBuild.zig.version` | `0.11.0` | Zig compiler version |
| `nativeBuild.targets` | all 5 platforms | Comma-separated Zig target triples |
| `nativeBuild.libraryName` | `freetype_msdfgen_harfbuzz_jni` | Output library base name |
| `nativeBuild.cOptLevel` | `-O2` | C/C++ optimization level |
| `nativeBuild.cDebugLevel` | `-g0` | Debug info level |
| `nativeBuild.cppStandard` | `-std=c++11` | C++ standard |
| `nativeBuild.libraries` | `freetype,harfbuzz,msdfgen,jni` | Ordered library list (build order = dependency order) |

### Per-Library Properties

Each library is configured via `nativeBuild.lib.<name>.<property>`:

| Property | Required | Description |
|----------|----------|-------------|
| `type` | yes | `c` or `cpp` — selects `zig cc` or `zig c++` |
| `source` | yes | Source location (see source types below) |
| `sourceRoot` | download only | Directory name after extraction (e.g., `freetype-2.13.2`) |
| `srcDirs` | no | Comma-separated dirs to scan for source files (default: `.`) |
| `includes` | no | Comma-separated `-I` include dirs (relative to source root) |
| `defines` | no | Comma-separated `-D` defines (without `-D` prefix) |
| `files` | no | Explicit file list (if omitted, globs `*.c`/`*.cpp`/`*.cc` in srcDirs) |
| `includesFrom` | no | Comma-separated library names whose includes to inherit |
| `overrides` | no | Local dir whose files replace files in source root before compile |
| `objPrefix` | no | Prefix for `.o` filenames (default: `<name>_`) |
| `jniHeaders` | no | `true` to add platform JNI headers to include path |

### Source Types

| Syntax | Description | Example |
|--------|-------------|---------|
| `download:<url>` | Download + extract tar/tar.xz/tar.gz | `download:https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.xz` |
| `local:<path>` | Local directory (project-relative) | `local:native/msdfgen-jni/msdfgen` |
| `git:<url>@<tag>` | Git clone at tag (must already exist) | `git:https://github.com/Chlumsky/msdfgen.git@v1.13` |

### How to Add a New Library

1. Add the library name to `nativeBuild.libraries` list
2. Add `nativeBuild.lib.<name>.*` properties
3. If it depends on other libraries' headers, set `includesFrom=<dep1>,<dep2>`
4. Run `./gradlew buildNatives` — the new library is automatically compiled and linked

Example — adding a hypothetical `tinyxml2` library:
```properties
nativeBuild.libraries=freetype,harfbuzz,msdfgen,tinyxml2,jni
nativeBuild.lib.tinyxml2.type=cpp
nativeBuild.lib.tinyxml2.source=download:https://github.com/leethomason/tinyxml2/archive/refs/tags/10.0.0.tar.gz
nativeBuild.lib.tinyxml2.sourceRoot=tinyxml2-10.0.0
nativeBuild.lib.tinyxml2.files=tinyxml2.cpp
nativeBuild.lib.tinyxml2.includes=.
nativeBuild.lib.tinyxml2.objPrefix=tinyxml2_
# Then add tinyxml2 to jni's includesFrom if JNI code needs it:
nativeBuild.lib.jni.includesFrom=freetype,harfbuzz,msdfgen,tinyxml2
```

## Gradle Tasks

All tasks are in the `native build` group:

| Task | Description |
|------|-------------|
| `downloadZig` | Download Zig compiler (auto-skips if on PATH) |
| `downloadNativeDeps` | Download all libraries with `source=download:` |
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
export JAVA_HOME="/path/to/jdk-21"
./gradlew buildNatives
```

### Build single platform (faster)
```bash
./gradlew buildNatives -PnativeBuild.targets=x86_64-windows
./gradlew buildNatives -PnativeTargets=x86_64-linux-gnu   # legacy syntax also works
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

Libraries are compiled in the order listed in `nativeBuild.libraries`:

1. **freetype** — 42 C files with `-DFT2_BUILD_LIBRARY`
2. **harfbuzz** — 1 C++ unity build file with `-DHAVE_FREETYPE`
3. **msdfgen** — 28 C++ files from `core/` and `ext/` dirs
4. **jni** — 4 JNI C++ source files with all upstream includes
5. **link** — `zig c++ -shared` all 75 objects into one shared library

Typical build time: **~10-15 seconds** per target (warm), **~45 seconds** cold start.

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
Windows bsdtar interprets drive letters as remote hosts. The build script works around this by running `tar` from the archive's directory using relative filenames.

### Zig not found
1. `./gradlew downloadZig` — auto-downloads to `build/native-build/zig/`
2. Install Zig 0.11.0 and add to PATH
3. `-PzigExe=/path/to/zig`

### Linker errors for `FT_Trace_Disable` / `FT_Trace_Enable`
Ensure `src/base/ftdebug.c` is in the FreeType `files` list in `gradle.properties`.

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
| `gradle.properties` | All library definitions, versions, targets, compiler flags |
| `native-build.gradle.kts` | Generic Zig build tasks (library-agnostic) |
| `build.gradle.kts` | Java project config, applies native-build.gradle.kts |
| `include/jni/` | JNI headers for cross-compilation |
| `native/freetype-msdfgen-harfbuzz-jni/src/cpp/` | JNI C++ sources |
| `native/msdfgen-jni/msdfgen/` | MSDFgen source tree |
| `native/freetype-msdfgen-harfbuzz-jni/overrides/` | Local msdfgen patches |

## Configuration Cache

Native build tasks use `notCompatibleWithConfigurationCache()` + `doNotTrackState()` because they invoke
external commands (Zig compiler, tar). The build works correctly with `--configuration-cache` — cache
entries are properly discarded for native tasks while other tasks benefit from caching.
