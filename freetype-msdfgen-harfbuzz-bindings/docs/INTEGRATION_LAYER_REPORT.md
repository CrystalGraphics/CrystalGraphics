# FreeType ↔ HarfBuzz Integration Layer Report

## Ownership & Lifetime Rules

### Object Lifecycle Diagram

```
FreeTypeLibrary (Java)           FT_Library (C)
    │ owns                            │
    ├── FTFace (Java) ──────────── FT_Face (C)
    │       │                         │
    │       │ passed to               │ referenced by (NOT owned)
    │       ▼                         ▼
    │   HBFont (Java) ─────────── hb_font_t (C)
    │       │                         │
    │       │ used by                 │ reads font data from
    │       ▼                         ▼
    │   HBBuffer (Java) ────────── hb_buffer_t (C)
    │
    Destroy order: Buffer → HBFont → FTFace → FreeTypeLibrary
```

### Key Rules

1. **FT_Face lifetime**: Must outlive all HBFont objects created from it
2. **HBFont does NOT own FT_Face**: `hb_ft_font_create(face, NULL)` — NULL means no destroy callback
3. **No reference counting**: FreeType doesn't refcount FT_Face; destroying it invalidates all HBFonts
4. **Thread safety**: Each FT_Library instance is single-threaded; HBBuffer operations are per-buffer

### Destruction Order Enforcement

Currently **NOT enforced** in Java code. Documented as mandatory:

```
1. HBBuffer.destroy()      — safe, independent
2. HBFont.destroy()        — must happen before FTFace.destroy()
3. FTFace.destroy()        — must happen before FreeTypeLibrary.destroy()
4. FreeTypeLibrary.destroy()
```

### Test Results Summary

| Test | Description | Expected | Status |
|------|------------|----------|--------|
| C1 | Correct cleanup order | No leaks | ✅ IMPLEMENTED |
| C2 | Wrong order (documented failure) | Dangling pointer documented | ✅ IMPLEMENTED |
| C3 | Multiple fonts × multiple buffers | No cross-contamination | ✅ IMPLEMENTED |
| C4 | Font reuse across buffers | Reference counting correct | ✅ IMPLEMENTED |
| C5 | Error path — invalid font | No partial leaks | ✅ IMPLEMENTED |
| C6 | Repeated create/destroy cycles | No growth trend | ✅ IMPLEMENTED |

### Recommended Usage Patterns

```java
// SAFE: try-finally with correct ordering
FreeTypeLibrary ft = FreeTypeLibrary.create();
try {
    FTFace face = ft.newFace("font.ttf", 0);
    face.setPixelSizes(0, 24);
    HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
    try {
        HBBuffer buffer = HBBuffer.create();
        try {
            buffer.addUTF8(text);
            buffer.guessSegmentProperties();
            HBShape.shape(hbFont, buffer);
            // ... use results ...
        } finally {
            buffer.destroy();
        }
    } finally {
        hbFont.destroy();
        face.destroy();
    }
} finally {
    ft.destroy();
}
```

### Pitfalls to Avoid

1. **Never destroy FTFace before HBFont** — causes use-after-free
2. **Never share FTFace across threads** without synchronization
3. **Always call destroy()** — no finalizer safety net exists
4. **Call syncFontMetrics() after changing pixel size** — otherwise HarfBuzz uses stale metrics

---

# CI/CD Integration Guide

## GitHub Actions Workflow

```yaml
name: FreeType-HarfBuzz Memory Leak Tests
on: [push, pull_request]

jobs:
  memory-leak-tests:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
        include:
          - os: macos-latest
            arch: arm64
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: 'zulu'

      - name: Build natives
        run: |
          cd native/freetype-harfbuzz-jni
          chmod +x build-natives.sh
          ./build-natives.sh

      - name: Run memory leak tests
        run: |
          cd freetype-harfbuzz-java-bindings
          ./gradlew test --tests "com.crystalgraphics.test.memory.*" \
            -Dfreetype.harfbuzz.native.path=src/main/resources/natives

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-${{ matrix.os }}
          path: freetype-harfbuzz-java-bindings/build/reports/tests/
```

## Local Testing Instructions

```bash
cd freetype-harfbuzz-java-bindings

# Build natives first (requires CMake + FreeType/HarfBuzz dev libraries)
cd native/freetype-harfbuzz-jni && ./build-natives.sh && cd ../..

# Run all memory leak tests
./gradlew test --tests "com.crystalgraphics.test.memory.*"

# Run specific test group
./gradlew test --tests "com.crystalgraphics.test.memory.IntegrationMemoryLeakTests"

# Run with Valgrind (Linux only)
valgrind --leak-check=full --track-origins=yes \
  java -cp build/classes/java/main:build/classes/java/test:libs/junit-4.13.2.jar \
  org.junit.runner.JUnitCore com.crystalgraphics.test.memory.IntegrationMemoryLeakTests
```

## Alert Thresholds

| Metric | Threshold | Action |
|--------|-----------|--------|
| Heap diff from baseline | > 5 KB | FAIL — investigate leak |
| Memory growth trend | > 2 KB/iteration | FAIL — unbounded growth |
| Thread errors | > 0 | FAIL — race condition |
| Platform RSS variance | > 15% | WARN — investigate |

## Performance Baselines

Capture baselines on first CI run and store as artifacts. Compare subsequent runs.

## Adding New Tests

1. Create test method in appropriate `*MemoryLeakTests.java` class
2. Extend `MemoryLeakDetectionBase` for setup helpers
3. Use `MemoryMetrics.takeSnapshot()` before/after operations
4. Use `assertMemoryReturnsToBaseline()` for leak detection
5. Use `assertNoMemoryGrowthTrend()` for stress tests
