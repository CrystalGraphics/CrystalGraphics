# macOS Compatibility Guide

## Apple Silicon (M1/M2/M3/M4) Support

Native binaries are provided for both Intel and Apple Silicon Macs:
- `natives/macos-x86_64/libfreetype_harfbuzz_jni.dylib` — Intel Macs
- `natives/macos-aarch64/libfreetype_harfbuzz_jni.dylib` — Apple Silicon

The `NativeLoader` detects `os.arch` at runtime and loads the correct binary.

## Building on macOS

### Prerequisites
```bash
brew install cmake freetype harfbuzz pkg-config
```

### Native Build
```bash
cd native/freetype-harfbuzz-jni
chmod +x build-natives.sh
./build-natives.sh
```

### Cross-Compilation (Intel on Apple Silicon)
```bash
arch -x86_64 ./build-natives.sh
```

## Code Signing & Notarization

macOS Gatekeeper may block unsigned dylibs downloaded from the internet.

### For Development
If macOS blocks the library:
```bash
xattr -d com.apple.quarantine src/main/resources/natives/macos-aarch64/libfreetype_harfbuzz_jni.dylib
```

### For Distribution
Sign and notarize the dylib:
```bash
codesign --sign "Developer ID Application: Your Name" --timestamp \
    --options runtime libfreetype_harfbuzz_jni.dylib

# Create a zip for notarization
ditto -c -k --keepParent libfreetype_harfbuzz_jni.dylib jni.zip
xcrun notarytool submit jni.zip --apple-id "you@example.com" \
    --team-id "TEAMID" --password "@keychain:AC_PASSWORD" --wait
```

## Rosetta 2 Testing

To test the x86_64 binary under Rosetta 2 on Apple Silicon:
```bash
arch -x86_64 java -Dfreetype.harfbuzz.native.path=natives/macos-x86_64 -jar test.jar
```

## Known Issues

1. **JDK Version**: macOS ARM64 requires JDK 17+ for native aarch64 JVMs. JDK 8 on macOS ARM runs under Rosetta 2 (use x86_64 binary).
2. **@rpath**: The dylib uses `@loader_path` for rpath resolution, which works when extracted to temp dir by NativeLoader.
3. **Static Linking**: FreeType and HarfBuzz are statically linked into the JNI library, so no system installation is needed.

## Testing Matrix

| macOS Version | Chip | JDK | Binary | Status |
|--------------|------|-----|--------|--------|
| 13 Ventura | M1 | Azul Zulu 8 (x86_64) | macos-x86_64 | ✅ Rosetta 2 |
| 14 Sonoma | M2 | Azul Zulu 17 (aarch64) | macos-aarch64 | ✅ Native |
| 14 Sonoma | Intel | OpenJDK 8 | macos-x86_64 | ✅ Native |
| 15 Sequoia | M3 | Azul Zulu 21 (aarch64) | macos-aarch64 | ✅ Native |
