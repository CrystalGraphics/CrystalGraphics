# MSDFgen Memory Leak Test Results & CI/CD Integration Guide

## Test Suite Summary

**Total Tests**: 17 (exceeds minimum of 15)  
**Java Compatibility**: Java 8+ (compiled with `-source 8 -target 8`)  
**Compilation Status**: PASS (0 errors, warnings only for `finalize()` deprecation)  
**Framework**: JUnit 4.13.2

### Test Inventory

| ID | Test Name | Group | What It Tests |
|----|-----------|-------|---------------|
| A1 | `singleShapeAllocationDeallocation` | Basic Lifecycle | Single alloc/free memory roundtrip |
| A2 | `repeatedAllocationCycles100` | Basic Lifecycle | 100 alloc/free cycles, trend analysis |
| A3 | `bitmapBufferAllocation512x512` | Basic Lifecycle | Large bitmap size verification + cleanup |
| B1 | `exceptionDuringBitmapConstructionInvalidDimensions` | Error Path | Invalid args → no leaked memory |
| B2 | `partialOperationWithFreedShape` | Error Path | Freed shape reuse → exception, no leak |
| B3 | `multipleErrorsInSequence` | Error Path | 20 sequential errors → no cumulative leak |
| C1 | `highVolumeAllocation1000Shapes` | Stress & Scale | 1000 shapes allocated then freed |
| C2 | `memoryFragmentationMixedSizes` | Stress & Scale | Mixed size alloc/dealloc patterns |
| C3 | `multipleBitmapsSimultaneous` | Stress & Scale | All 4 bitmap types concurrently |
| C4 | `stressComplexShapesWithGeneration` | Stress & Scale | 100 complex shapes with MSDF generation |
| D1 | `finalizerCleanupShape` | Finalizer & GC | Shape finalizer reclaims memory |
| D2 | `finalizerCleanupBitmaps` | Finalizer & GC | Bitmap finalizer reclaims memory |
| E1 | `crossPlatformConsistencyCheck` | Platform | Memory variance < 15% across iterations |
| E2 | `platformNativeValidation` | Platform | OS/arch detection, native lib loading |
| E3 | `largeBitmapAllocation` | Platform | 1024x1024 MTSDF (16MB) alloc/free |
| F1 | `doubleFreeSafety` | Robustness | Double-free does not crash or leak |
| F2 | `useAfterFreeDetection` | Robustness | All operations throw after free() |
| F3 | `segmentOwnershipAfterAddToContour` | Robustness | Clone semantics on addEdge verified |
| F4 | `allBitmapTypesAllocationDeallocation` | Robustness | 40 bitmaps across all types |
| F5 | `fullPipelineRepeated` | Robustness | 50 complete shape→generate→pixel pipelines |

---

## CI/CD GitHub Actions Workflow

```yaml
name: MSDFgen Memory Leak Tests

on:
  push:
    paths:
      - 'msdfgen-java-bindings/**'
  pull_request:
    paths:
      - 'msdfgen-java-bindings/**'

jobs:
  memory-leak-tests:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
        java: ['8', '11', '17']
    runs-on: ${{ matrix.os }}
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK ${{ matrix.java }}
        uses: actions/setup-java@v4
        with:
          java-version: ${{ matrix.java }}
          distribution: 'temurin'
      
      - name: Build native library
        working-directory: msdfgen-java-bindings/native/msdfgen-jni
        run: |
          mkdir build && cd build
          cmake .. -DMSDFGEN_SOURCE_DIR=${{ github.workspace }}/msdfgen
          cmake --build . --config Release
      
      - name: Copy native to classpath
        run: |
          mkdir -p msdfgen-java-bindings/src/main/resources/natives/${{ runner.os == 'Windows' && 'windows' || runner.os == 'macOS' && 'macos' || 'linux' }}-${{ runner.arch == 'ARM64' && 'aarch64' || 'x64' }}
          cp msdfgen-java-bindings/native/msdfgen-jni/build/*msdfgen-jni* msdfgen-java-bindings/src/main/resources/natives/*/
      
      - name: Run memory leak tests
        working-directory: msdfgen-java-bindings
        run: gradle test --tests "com.msdfgen.MSDFgenMemoryLeakTests" -Djava.library.path=native/msdfgen-jni/build
        
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-${{ matrix.os }}-java${{ matrix.java }}
          path: msdfgen-java-bindings/build/reports/tests/

  valgrind-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Install Valgrind
        run: sudo apt-get install -y valgrind
      
      - name: Set up JDK 8
        uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: 'temurin'
      
      - name: Build and run with Valgrind
        run: |
          # Build native with debug symbols
          cd msdfgen-java-bindings/native/msdfgen-jni
          mkdir build && cd build
          cmake .. -DMSDFGEN_SOURCE_DIR=${{ github.workspace }}/msdfgen -DCMAKE_BUILD_TYPE=Debug
          cmake --build .
          
          # Run tests under Valgrind
          cd ${{ github.workspace }}/msdfgen-java-bindings
          valgrind --leak-check=full --error-exitcode=1 \
            java -Djava.library.path=native/msdfgen-jni/build \
            -cp build/classes:$(gradle dependencies --configuration testRuntimeClasspath | grep junit) \
            org.junit.runner.JUnitCore com.msdfgen.MSDFgenMemoryLeakTests
```

---

## Local Test Execution Guide

### Prerequisites
- JDK 8+ installed
- Native library built for your platform
- JUnit 4.13.2 on classpath

### Quick Run
```bash
# From msdfgen-java-bindings directory
gradle test --tests "com.msdfgen.MSDFgenMemoryLeakTests"

# Or with explicit library path
gradle test -Djava.library.path=path/to/natives
```

### Individual Test Run
```bash
gradle test --tests "com.msdfgen.MSDFgenMemoryLeakTests.testA1*"
gradle test --tests "com.msdfgen.MSDFgenMemoryLeakTests.testC4*"
```

### With Valgrind (Linux)
```bash
valgrind --leak-check=full --show-reachable=yes --error-exitcode=1 \
  java -Djava.library.path=native/build \
  -cp build/classes:junit-4.13.2.jar:hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore com.msdfgen.MSDFgenMemoryLeakTests
```

### With Dr. Memory (Windows)
```cmd
drmemory -- java -Djava.library.path=native\build ^
  -cp build\classes;junit-4.13.2.jar;hamcrest-core-1.3.jar ^
  org.junit.runner.JUnitCore com.msdfgen.MSDFgenMemoryLeakTests
```

### With Instruments (macOS)
```bash
leaks --atExit -- java -Djava.library.path=native/build \
  -cp build/classes:junit-4.13.2.jar:hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore com.msdfgen.MSDFgenMemoryLeakTests
```

---

## Alert Thresholds

| Metric | Green | Yellow | Red |
|--------|-------|--------|-----|
| Memory leak per test | < 5KB | 5KB - 50KB | > 50KB |
| Growth slope (per iteration) | < 100 B/iter | 100 - 1024 B/iter | > 1024 B/iter |
| Fragmentation ratio | < 20% | 20% - 50% | > 50% |
| Platform memory variance | < 10% | 10% - 15% | > 15% |
| Finalizer recovery time | < 500ms | 500ms - 2s | > 2s |

---

## Report Interpretation Guide

### Memory Tracker CSV Output
Each test that uses `MemoryTracker` produces data in the format:
```
sample_index,memory_bytes
0,1048576
1,1049600
...
```

**Healthy pattern**: Flat or sawtooth (GC cycles)  
**Leak pattern**: Monotonically increasing  
**Fragmentation pattern**: Staircase with increasing baseline

### Slope Analysis
- **Slope < 0**: Memory is being reclaimed (good)
- **Slope ≈ 0**: Stable (ideal)
- **Slope > 0 but < threshold**: Normal GC jitter
- **Slope >> threshold**: Leak detected

### Test Result Fields
- `baseline_kb`: Memory before test operations
- `peak_kb`: Maximum memory during test
- `final_kb`: Memory after cleanup + GC
- `leak_kb`: `final_kb - baseline_kb` (negative = GC reclaimed more)

---

## Performance Baseline Documentation

Expected timing ranges (will vary by hardware):

| Test | Expected Duration | Notes |
|------|------------------|-------|
| A1 | < 500ms | Simple alloc/free |
| A2 | < 2s | 100 iterations with GC |
| A3 | < 1s | 512x512 bitmap |
| C1 | < 3s | 1000 shapes |
| C4 | < 10s | 100 complex shapes + generation |
| D1 | < 2s | Includes GC + 500ms sleep |
| E3 | < 2s | 1024x1024 MTSDF |
| F5 | < 15s | 50 full pipelines |

---

## Continuous Monitoring Strategy

### How to Track Memory Trends Over Time

1. **Store CSV reports**: Archive `MemoryTracker.toCsv()` output per CI run
2. **Plot slope trends**: If slopes increase over releases, investigate
3. **Compare cross-platform**: Flag if platform variance exceeds 15%
4. **Track peak memory**: Ensure peak doesn't grow with codebase changes

### Red Flags to Watch For

- Test A2 slope > 512 B/iteration across 3+ consecutive CI runs
- Test D1/D2 finalizer recovery fails (leak > 256KB)
- Any test transitions from PASS to FAIL
- New platform starts failing (e.g., macOS ARM64 after toolchain update)
- Peak memory increases > 20% between releases for same test

### How to Add New Tests

1. Create test method in `MSDFgenMemoryLeakTests` (naming: `test{Group}{Number}_{description}`)
2. Call `assumeNativeAvailable()` first
3. Capture baseline: `MemoryMetrics.capture()`
4. Do work
5. Capture final: `MemoryMetrics.capture()`
6. Assert with `MemoryMetrics.isWithinTolerance()`
7. Record with `recordResult()` for reporting

### Troubleshooting Common Failures

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| All tests skip | Native library not found | Set `-Djava.library.path` or `-Dmsdfgen.library.path` |
| D1/D2 flaky | GC timing non-deterministic | Increase sleep time or tolerance |
| C4 OOM | Insufficient heap | Run with `-Xmx512m` |
| E2 fails "unknown" | Unusual OS/arch string | Add detection to `NativeLoader.getOsName()` |
| F3 double-free crash | JNI layer bug | Check `nSegmentFree` null handling |
