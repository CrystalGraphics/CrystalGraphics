# macOS Compatibility

## Apple Silicon (M1/M2/M3) Support

The native library is built as a native ARM64 binary for Apple Silicon Macs. No Rosetta 2 translation is needed.

### Build Requirements

- macOS 11.0+ (Big Sur or later)
- Xcode Command Line Tools (`xcode-select --install`)
- CMake 3.15+
- JDK 8+ with `JAVA_HOME` set

### Building on Apple Silicon

```bash
cd native/msdfgen-jni
./build-natives.sh
```

The build script automatically detects the host architecture and builds accordingly:
- On ARM64 Mac: builds `macos-aarch64` native
- On Intel Mac: builds `macos-x64` native

### Cross-Compilation

To build for the other architecture from your Mac:

```bash
# Build Intel binary on M1/M2/M3 Mac
CMAKE_OSX_ARCHITECTURES=x86_64 ./build-natives.sh

# Build ARM64 binary on Intel Mac (requires Xcode 12+)
CMAKE_OSX_ARCHITECTURES=arm64 ./build-natives.sh
```

### Universal Binary

To build a fat binary that works on both architectures:

```bash
cmake -S . -B build-universal \
    -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 \
    -DMSDFGEN_SOURCE_DIR=./msdfgen
cmake --build build-universal --config Release
```

## Code Signing

### Development

For local development and testing, no code signing is needed. macOS will allow loading unsigned JNI libraries from temp directories.

### Distribution

For distribution (especially via download or installer), the native library should be ad-hoc signed:

```bash
codesign --sign - --force libmsdfgen-jni.dylib
```

For notarized distribution (App Store or Gatekeeper-approved):

```bash
codesign --sign "Developer ID Application: Your Name (TEAM_ID)" \
    --options runtime \
    --timestamp \
    libmsdfgen-jni.dylib
```

### Hardened Runtime

When distributing with hardened runtime enabled, you need the `com.apple.security.cs.allow-jit` and `com.apple.security.cs.allow-unsigned-executable-memory` entitlements. Create an `entitlements.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key><true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
</dict>
</plist>
```

Then sign with:

```bash
codesign --sign "Developer ID Application: ..." \
    --options runtime \
    --entitlements entitlements.plist \
    --timestamp \
    libmsdfgen-jni.dylib
```

## Testing Matrix

| Mac Model | Chip | macOS Version | Status |
|-----------|------|---------------|--------|
| MacBook Pro 2020+ | M1 | 11.0+ | Primary target |
| MacBook Pro 2021+ | M1 Pro/Max | 12.0+ | Supported |
| Mac Studio 2022+ | M1 Ultra | 12.0+ | Supported |
| MacBook Air 2022+ | M2 | 13.0+ | Supported |
| MacBook Pro 2023+ | M2 Pro/Max | 13.0+ | Supported |
| MacBook Pro 2023+ | M3/Pro/Max | 14.0+ | Supported |
| MacBook Air 2024+ | M3 | 14.0+ | Supported |
| Intel Macs | i5/i7/i9 | 10.15+ | Supported via x64 binary |

## Troubleshooting

### "library not found" on macOS

Ensure the native library is either:
1. Embedded in the JAR at `natives/macos-aarch64/libmsdfgen-jni.dylib` (or `macos-x64`)
2. Specified via `-Dmsdfgen.library.path=/path/to/libmsdfgen-jni.dylib`
3. In a directory on `java.library.path`

### Gatekeeper blocks unsigned library

For development, run:
```bash
xattr -d com.apple.quarantine libmsdfgen-jni.dylib
```

For production, properly code-sign the library (see above).

### Rosetta 2 performance

If running an x64 JVM on Apple Silicon, the x64 native library will work via Rosetta 2 but with ~20-30% performance overhead. For best performance, use an ARM64 JVM with the ARM64 native library.
