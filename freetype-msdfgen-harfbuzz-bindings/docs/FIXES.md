# FreeType + HarfBuzz Java Bindings — Fixes Applied

**Date**: 2026-03-12
**Scope**: All CRITICAL + WARNING issues from GAP_ANALYSIS_REPORT.md

---

## CRITICAL Fixes (3/3)

### C1: Memory Leak in `newFaceFromMemory()` — FIXED

**File**: `freetype_jni.cpp`

**Problem**: `FT_New_Memory_Face()` requires a caller-managed buffer that must outlive the `FT_Face`. The buffer was `malloc`'d but never `free`'d when `FT_Done_Face()` was called. Every `newFaceFromMemory()` call leaked `dataLen` bytes.

**Fix**:
- Added `static std::map<FT_Face, FT_Byte*> memoryFaceBuffers` with `std::mutex` protection
- In `nNewFaceFromMemory()`: register `persistentData` in the map after successful face creation
- In `nDoneFace()`: lookup face in map, extract buffer, erase entry, call `FT_Done_Face`, then `free(buffer)`
- Thread-safe via `std::lock_guard<std::mutex>`

### C2: Cleanup Order Not Enforced — FIXED

**Files**: `FTFace.java`, `FreeTypeLibrary.java`, `FreeTypeHarfBuzzIntegration.java`

**Problem**: User could destroy `FTFace` while `HBFont` still held a reference, causing use-after-free/segfault.

**Fix**:
- `FTFace` tracks dependent `HBFont` objects via `List<WeakReference<Object>>`
- `FreeTypeHarfBuzzIntegration.createHBFontFromFTFace()` registers the HBFont as a dependent
- `FTFace.destroy()` checks `hasActiveDependents()` — throws `IllegalStateException` if any active HBFont exists
- `FreeTypeLibrary` tracks created faces via `List<WeakReference<FTFace>>`
- `FreeTypeLibrary.destroy()` checks `hasActiveFaces()` — throws `IllegalStateException` if any active face exists
- `FTFace.checkNotDestroyed()` also validates parent library is alive

### C3: Font Metrics Calculation Error — FIXED

**File**: `integration.cpp`

**Problem**: `syncFontMetrics` used `face->size->metrics.x_scale` (16.16 fixed-point) but HarfBuzz expects scale in 26.6 fixed-point format.

**Fix**: Changed from `x_scale`/`y_scale` to `x_ppem * 64` / `y_ppem * 64`, which produces correct 26.6 fixed-point values for HarfBuzz.

---

## WARNING Fixes

### H1: Finalizer Safety Net — FIXED

**Files**: `FreeTypeLibrary.java`, `FTFace.java`, `HBFont.java`, `HBBuffer.java`

Added `@Override protected void finalize()` to all four native resource classes. Finalizers:
- Print a warning to `System.err` when triggered (indicating developer forgot `destroy()`)
- Call the native cleanup method in a try/catch (best-effort during finalization)
- Set `nativePtr = 0` to prevent double-free

### H2: FTFace → FreeTypeLibrary Lifetime Tracking — FIXED

**Files**: `FreeTypeLibrary.java`, `FTFace.java`

- `FreeTypeLibrary` now tracks all created faces
- `FTFace.checkNotDestroyed()` validates parent library is still alive
- `FreeTypeLibrary.destroy()` refuses if active faces exist

### H4: Test `testIntegrationWithDestroyedFace` — FIXED

**File**: `IntegrationTest.java`

The test used `new byte[0]` which threw `IllegalArgumentException` at `newFaceFromMemory()`, not at `createHBFontFromFTFace()`. Fixed to load actual font data, create a real face, destroy it, then attempt to create an HBFont from the destroyed face.

### H5: Thread Safety — FIXED

**Files**: `FreeTypeLibrary.java`, `freetype_jni.cpp`

- `FreeTypeLibrary` uses an internal `lock` object for all JNI calls (`newFace`, `newFaceFromMemory`, `getVersion`, `destroy`)
- `memoryFaceBuffers` map in C++ uses `std::mutex`
- `dependentResources` and `faces` lists use `synchronized` blocks

### M3: Feature String Parsing — FIXED

**File**: `harfbuzz_jni.cpp`

`hb_feature_from_string` return value is now checked. Invalid feature strings are silently skipped instead of leaving uninitialized `hb_feature_t` structs in the array.

### M5: FT_Get_Advance Binding — ADDED

**Files**: `FTFace.java`, `freetype_jni.cpp`, `headers.h`

Added `FTFace.getAdvance(glyphIndex, loadFlags)` for fast text measurement without full glyph loading. Returns 16.16 fixed-point horizontal advance. Added `#include FT_ADVANCES_H` to headers.

### M6: HB_Font getPpem — ADDED

**Files**: `HBFont.java`, `harfbuzz_jni.cpp`

Added `HBFont.getPpem()` returning `int[2] = {xPpem, yPpem}` for API symmetry with `setPpem()`.

### Error Handling Improvements — FIXED

**File**: `freetype_jni.cpp`

`nNewFace` and `nNewFaceFromMemory` now throw `FreeTypeException` with the actual FreeType error code instead of silently returning 0.

---

## Compilation Status

- **Main sources**: 0 errors, 0 warnings (except Java 8 deprecation on finalize)
- **Test sources**: 0 errors, 0 warnings (except Java 8/runFinalization deprecation)
- **Native C++**: Requires CMake build with FreeType/HarfBuzz headers

## Files Modified

### Native (C++)
- `native/freetype-harfbuzz-jni/src/cpp/freetype_jni.cpp` — memory leak fix, error handling, FT_Get_Advance
- `native/freetype-harfbuzz-jni/src/cpp/harfbuzz_jni.cpp` — getPpem, feature parsing fix
- `native/freetype-harfbuzz-jni/src/cpp/integration.cpp` — scale factor fix
- `native/freetype-harfbuzz-jni/src/cpp/headers.h` — FT_ADVANCES_H include

### Java (Main)
- `FreeTypeLibrary.java` — lifecycle tracking, thread safety, finalizer
- `FTFace.java` — dependent tracking, library validation, FT_Get_Advance, finalizer
- `HBFont.java` — public constructor, getPpem, finalizer
- `HBBuffer.java` — finalizer
- `FreeTypeHarfBuzzIntegration.java` — register dependent on creation

### Java (Tests)
- `IntegrationTest.java` — fix H4 test, disambiguate NativeLoader import
- `IntegrationMemoryLeakTests.java` — update C2 test for enforcement behavior
- `FinalizerMemoryLeakTests.java` — update comments for finalizer addition
