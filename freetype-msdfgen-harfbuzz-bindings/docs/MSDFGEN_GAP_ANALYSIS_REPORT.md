# MSDFgen Java Bindings - Comprehensive Gap Analysis Report

**Date**: March 12, 2026  
**Version**: 1.0.0-SNAPSHOT  
**Reference**: LWJGL 3.x MSDFGen bindings (generated from msdfgen-c.h)  
**Binding Target**: LWJGL 2.9.3 / Java 8 compatible JNI

---

## 1. Executive Summary

The msdfgen-java-bindings project provides a JNI-based port of the MSDFgen C API
for use with LWJGL 2.9.3 and Minecraft 1.7.10 modding. This report compares it
line-by-line against the LWJGL 3.x reference bindings and the msdfgen-c.h C API
to identify gaps, correctness issues, and risk areas.

**Overall Assessment**: The bindings cover the core API surface well but have
several gaps that range from minor missing convenience functions to significant
missing API methods. The JNI layer is sound but has a few areas needing attention.

### Severity Summary

| Severity | Count | Description |
|----------|-------|-------------|
| CRITICAL | 3 | Missing APIs that limit functionality; potential crash risks |
| WARNING  | 8 | Missing functions, semantic differences, or safety concerns |
| INFO     | 6 | Minor deviations, style differences, or enhancement opportunities |

---

## 2. API Completeness Matrix

### 2.1 Bitmap API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_bitmap_alloc` | `msdf_bitmap_alloc(type,w,h,bitmap)` | `nBitmapAlloc(type,w,h,handleOut)` | PRESENT - Different signature | INFO |
| `msdf_bitmap_get_channel_count` | `msdf_bitmap_get_channel_count(bitmap,count*)` | `nBitmapGetChannelCount(handle,type)` | PRESENT - Simplified | INFO |
| `msdf_bitmap_get_pixels` | `msdf_bitmap_get_pixels(bitmap,pixels**)` returns pointer | `nBitmapGetPixels(handle,type,w,h,floatArr)` copies data | PRESENT - Data copy vs pointer | INFO |
| `msdf_bitmap_get_byte_size` | `msdf_bitmap_get_byte_size(bitmap,size*)` | `nBitmapGetByteSize(handle,type,w,h)` | PRESENT | OK |
| `msdf_bitmap_free` | `msdf_bitmap_free(bitmap)` | `nBitmapFree(handle,type)` | PRESENT | OK |

**Notes**:
- Our binding copies pixel data into a Java float array, while LWJGL3 returns a native pointer.
  This is the correct approach for JNI (safer, no dangling pointer risk), but involves a memory copy.
- Channel count is computed on Java side via `MsdfConstants.channelCountForType()` rather than
  querying native. This is correct since the type→channel mapping is deterministic.

### 2.2 Shape API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_shape_alloc` | `msdf_shape_alloc(shape*)` | `nShapeAlloc()` returns handle | PRESENT | OK |
| `msdf_shape_get_bounds` | `msdf_shape_get_bounds(shape,bounds*)` | `nShapeGetBounds(shape,double[])` | PRESENT | OK |
| `msdf_shape_add_contour` | `msdf_shape_add_contour(shape,contour*)` | `nShapeAddContour(shape)` returns handle | PRESENT | OK |
| `msdf_shape_remove_contour` | `msdf_shape_remove_contour(shape,contour)` | `nShapeRemoveContour(shape,contour)` | STUB - Always returns ERR_FAILED | **WARNING** |
| `msdf_shape_get_contour_count` | `msdf_shape_get_contour_count(shape,count*)` | `nShapeGetContourCount(shape)` returns int | PRESENT | OK |
| `msdf_shape_get_contour` | `msdf_shape_get_contour(shape,index,contour*)` | `nShapeGetContour(shape,index)` returns handle | PRESENT | OK |
| `msdf_shape_get_edge_count` | `msdf_shape_get_edge_count(shape,count*)` | `nShapeGetEdgeCount(shape)` returns int | PRESENT | OK |
| `msdf_shape_get_y_axis_orientation` | `msdf_shape_get_y_axis_orientation(shape,int*)` | `nShapeGetYAxisOrientation(shape)` returns int | PRESENT | OK |
| `msdf_shape_set_y_axis_orientation` | `msdf_shape_set_y_axis_orientation(shape,int)` | `nShapeSetYAxisOrientation(shape,int)` | PRESENT | OK |
| `msdf_shape_normalize` | `msdf_shape_normalize(shape)` | `nShapeNormalize(shape)` | PRESENT | OK |
| `msdf_shape_validate` | `msdf_shape_validate(shape,result*)` | `nShapeValidate(shape)` returns int directly | PRESENT - Simplified | INFO |
| `msdf_shape_bound` | `msdf_shape_bound(shape,bounds*)` | `nShapeBound(shape,double[])` | PRESENT | OK |
| `msdf_shape_bound_miters` | `msdf_shape_bound_miters(shape,bounds*,border,miter,pol)` | `nShapeBoundMiters(shape,double[],border,miter,pol)` | PRESENT | OK |
| `msdf_shape_orient_contours` | `msdf_shape_orient_contours(shape)` | `nShapeOrientContours(shape)` | PRESENT | OK |
| `msdf_shape_edge_colors_simple` | `msdf_shape_edge_colors_simple(shape,angle)` | `nShapeEdgeColorsSimple(shape,angle)` | PRESENT | OK |
| `msdf_shape_edge_colors_ink_trap` | `msdf_shape_edge_colors_ink_trap(shape,angle)` | `nShapeEdgeColorsInkTrap(shape,angle)` | PRESENT | OK |
| `msdf_shape_edge_colors_by_distance` | `msdf_shape_edge_colors_by_distance(shape,angle)` | `nShapeEdgeColorsByDistance(shape,angle)` | PRESENT | OK |
| `msdf_shape_one_shot_distance` | `msdf_shape_one_shot_distance(shape,origin*,dist*)` | `nShapeOneShotDistance(shape,x,y)` returns double | PRESENT - Simplified | OK |
| `msdf_shape_free` | `msdf_shape_free(shape)` | `nShapeFree(shape)` | PRESENT | OK |

### 2.3 Contour API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_contour_alloc` | `msdf_contour_alloc(contour*)` | `nContourAlloc()` returns handle | PRESENT | OK |
| `msdf_contour_add_edge` | `msdf_contour_add_edge(contour,segment)` | `nContourAddEdge(contour,segment)` | PRESENT | OK |
| `msdf_contour_remove_edge` | `msdf_contour_remove_edge(contour,segment)` | `nContourRemoveEdge(contour,segment)` | STUB - Always returns ERR_FAILED | **WARNING** |
| `msdf_contour_get_edge_count` | `msdf_contour_get_edge_count(contour,count*)` | `nContourGetEdgeCount(contour)` returns int | PRESENT | OK |
| `msdf_contour_get_edge` | `msdf_contour_get_edge(contour,index,segment*)` | `nContourGetEdge(contour,index)` returns handle | PRESENT | OK |
| `msdf_contour_bound` | `msdf_contour_bound(contour,bounds*)` | `nContourBound(contour,double[])` | PRESENT | OK |
| `msdf_contour_bound_miters` | `msdf_contour_bound_miters(contour,bounds*,border,miter,pol)` | **MISSING** | **MISSING** | **WARNING** |
| `msdf_contour_get_winding` | `msdf_contour_get_winding(contour,winding*)` | `nContourGetWinding(contour)` returns int | PRESENT | OK |
| `msdf_contour_reverse` | `msdf_contour_reverse(contour)` | `nContourReverse(contour)` | PRESENT | OK |
| `msdf_contour_free` | `msdf_contour_free(contour)` | `nContourFree(contour)` | PRESENT | OK |

### 2.4 Segment API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_segment_alloc` | `msdf_segment_alloc(type,segment*)` | `nSegmentAlloc(type)` returns handle | PRESENT | OK |
| `msdf_segment_get_type` | `msdf_segment_get_type(segment,type*)` | `nSegmentGetType(segment)` returns int | PRESENT | OK |
| `msdf_segment_get_point_count` | `msdf_segment_get_point_count(segment,count*)` | `nSegmentGetPointCount(segment)` returns int | PRESENT | OK |
| `msdf_segment_get_point` | `msdf_segment_get_point(segment,index,point*)` | `nSegmentGetPoint(segment,index,double[])` | PRESENT | OK |
| `msdf_segment_set_point` | `msdf_segment_set_point(segment,index,point*)` | `nSegmentSetPoint(segment,index,x,y)` | PRESENT | OK |
| `msdf_segment_get_color` | `msdf_segment_get_color(segment,color*)` | `nSegmentGetColor(segment)` returns int | PRESENT | OK |
| `msdf_segment_set_color` | `msdf_segment_set_color(segment,color)` | `nSegmentSetColor(segment,color)` | PRESENT | OK |
| `msdf_segment_get_direction` | `msdf_segment_get_direction(segment,param,dir*)` | `nSegmentGetDirection(segment,param,double[])` | PRESENT | OK |
| `msdf_segment_get_direction_change` | `msdf_segment_get_direction_change(segment,param,dc*)` | **MISSING** | **MISSING** | **WARNING** |
| `msdf_segment_point` | `msdf_segment_point(segment,param,point*)` | `nSegmentPoint(segment,param,double[])` | PRESENT | OK |
| `msdf_segment_bound` | `msdf_segment_bound(segment,bounds*)` | `nSegmentBound(segment,double[])` | PRESENT | OK |
| `msdf_segment_move_start_point` | `msdf_segment_move_start_point(segment,point*)` | `nSegmentMoveStartPoint(segment,x,y)` | PRESENT | OK |
| `msdf_segment_move_end_point` | `msdf_segment_move_end_point(segment,point*)` | `nSegmentMoveEndPoint(segment,x,y)` | PRESENT | OK |
| `msdf_segment_free` | `msdf_segment_free(segment)` | `nSegmentFree(segment)` | PRESENT | OK |

### 2.5 Generation API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_generate_sdf` | Yes | `nGenerateSdf(...)` | PRESENT | OK |
| `msdf_generate_psdf` | Yes | `nGeneratePsdf(...)` | PRESENT | OK |
| `msdf_generate_msdf` | Yes | `nGenerateMsdf(...)` | PRESENT | OK |
| `msdf_generate_mtsdf` | Yes | `nGenerateMtsdf(...)` | PRESENT | OK |
| `msdf_generate_sdf_with_config` | Yes | `nGenerateSdfWithConfig(...)` | PRESENT | OK |
| `msdf_generate_psdf_with_config` | Yes | **MISSING** | **MISSING** | **WARNING** |
| `msdf_generate_msdf_with_config` | Yes | `nGenerateMsdfWithConfig(...)` | PRESENT | OK |
| `msdf_generate_mtsdf_with_config` | Yes | `nGenerateMtsdfWithConfig(...)` | PRESENT | OK |
| `msdf_error_correction` | Yes | **MISSING** | **MISSING** | **CRITICAL** |
| `msdf_error_correction_fast_distance` | Yes | **MISSING** | **MISSING** | **CRITICAL** |
| `msdf_error_correction_fast_edge` | Yes | **MISSING** | **MISSING** | **CRITICAL** |
| `msdf_render_sdf` | Yes | **MISSING** | **MISSING** | **WARNING** |

### 2.6 FreeType Extension API

| C API Function | LWJGL3 | Our Binding | Status | Severity |
|---|---|---|---|---|
| `msdf_ft_set_load_callback` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_get_load_callback` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_init` | Yes | `nFreetypeInit()` | STUB - Returns 0 | **WARNING** |
| `msdf_ft_load_font` | Yes | `nLoadFont(handle,filename)` | STUB - Returns 0 | **WARNING** |
| `msdf_ft_adopt_font` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_load_font_data` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_font_load_glyph` | Yes | `nLoadGlyph(font,unicode,shapeOut)` | STUB - Returns 0 | **WARNING** |
| `msdf_ft_font_load_glyph_by_index` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_font_get_glyph_index` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_font_get_kerning` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_font_get_kerning_by_index` | Yes | **MISSING** | **MISSING** | INFO |
| `msdf_ft_font_destroy` | Yes | `nDestroyFont(fontHandle)` | STUB - No-op | INFO |
| `msdf_ft_deinit` | Yes | `nFreetypeDeinit(ftHandle)` | STUB - No-op | INFO |

---

## 3. Detailed Findings

### 3.1 CRITICAL Issues

#### C1: Missing Error Correction Functions (msdf_error_correction, *_fast_distance, *_fast_edge)

**Impact**: Error correction is essential for production-quality MSDF rendering. Without it, MSDF
bitmaps may have artifacts at intersections and corners. LWJGL3 exposes all three variants.

**Evidence**: The C API header defines:
```c
MSDF_API int msdf_error_correction(msdf_bitmap_t*, msdf_shape_const_handle, const msdf_transform_t*);
MSDF_API int msdf_error_correction_fast_distance(msdf_bitmap_t*, const msdf_transform_t*);
MSDF_API int msdf_error_correction_fast_edge(msdf_bitmap_t*, const msdf_transform_t*);
```

Our JNI layer has no corresponding implementation. The `nGenerateMsdfWithConfig` and
`nGenerateMtsdfWithConfig` methods accept error correction parameters, so inline correction
during generation is supported. But standalone post-generation error correction is missing.

**Risk**: High. Users performing multi-step rendering pipelines cannot apply error correction
as a separate pass. The `with_config` variants partially compensate but are less flexible.

**Proposed Fix**: Add three new native methods to `MsdfNative.java` and corresponding JNI
implementations. Estimated effort: 2-3 hours.

#### C2: JNI Header Missing Declarations for WithConfig Variants

**Impact**: The `msdfgen_jni.h` header file only declares 4 generation functions
(`nGenerateSdf`, `nGeneratePsdf`, `nGenerateMsdf`, `nGenerateMtsdf`) but the `.cpp`
implementation file has 7 generation functions (4 basic + 3 with config). The header
is incomplete - missing declarations for `nGenerateSdfWithConfig`,
`nGenerateMsdfWithConfig`, and `nGenerateMtsdfWithConfig`.

**Evidence**: Header has lines 57-60 declaring 4 functions. The .cpp defines 7.

**Risk**: Medium. This works because the `.cpp` file includes the JNI-generated headers
via `javah`/`javac -h`, and the `msdfgen_jni.h` is supplementary. But it creates
maintenance confusion and could cause linking issues with strict compilers.

**Proposed Fix**: Add missing declarations to `msdfgen_jni.h`. Effort: 30 minutes.

#### C3: Segment getType() Mapping May Be Off-By-One

**Impact**: The JNI implementation returns `seg->type() - 1` for segment type:
```cpp
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetType(...) {
    return seg->type() - 1;  // Line 358
}
```
The msdfgen C++ `EdgeSegment::type()` returns 1 for Linear, 2 for Quadratic, 3 for Cubic.
Our Java constants are 0-based (LINEAR=0, QUADRATIC=1, CUBIC=2). The `-1` offset
converts correctly.

Similarly, `nSegmentGetPointCount` returns `seg->type() + 1` which also maps correctly
(Linear=2 points, Quadratic=3, Cubic=4).

**However**: The `createSegment()` helper in JNI uses 0-based indices matching our Java
constants, which is correct. But `nSegmentSetPoint` uses `seg->type()` (1-based) for
bounds checking while accepting 0-based Java point indices - this is also correct since
point count = type + 1.

**Risk**: Low but fragile. The type mapping works by coincidence of msdfgen's internal
numbering. If msdfgen changes its internal type() return values, this breaks silently.

**Proposed Fix**: Add explicit constants in the JNI layer rather than relying on
arithmetic relationships. Effort: 1 hour.

### 3.2 WARNING Issues

#### W1: msdf_shape_remove_contour - Stub Implementation

The JNI implementation always returns `MSDF_ERR_FAILED`:
```cpp
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeRemoveContour(...) {
    return MSDF_ERR_FAILED;
}
```

The C API supports this. Our binding declares it but never works. The Java `Shape` class
does not expose `removeContour()` publicly, so this is currently dead code.

**Proposed Fix**: Either implement it or remove the native declaration. Effort: 2 hours
(implementation requires matching contour pointers in the vector).

#### W2: msdf_contour_remove_edge - Stub Implementation

Same issue as W1. Always returns `MSDF_ERR_FAILED`.

#### W3: msdf_contour_bound_miters - Missing Entirely

The C API and LWJGL3 both expose `msdf_contour_bound_miters`. Our binding has no
declaration in `MsdfNative.java` and no JNI implementation. The contour-level miter
bounding is useful for precise glyph layout.

**Proposed Fix**: Add native method and JNI implementation. Effort: 1 hour.

#### W4: msdf_segment_get_direction_change - Missing Entirely

The C API exposes this for computing curvature changes along segments. Useful for
advanced edge coloring and analysis. Not in our binding.

**Proposed Fix**: Add native method and JNI implementation. Effort: 1 hour.

#### W5: msdf_generate_psdf_with_config - Missing

We have `sdf_with_config`, `msdf_with_config`, and `mtsdf_with_config` but not
`psdf_with_config`. This is an asymmetric gap.

**Proposed Fix**: Add the missing variant. Effort: 30 minutes.

#### W6: msdf_render_sdf - Missing

This function renders an SDF bitmap to a regular bitmap. Useful for previewing/debugging.

**Proposed Fix**: Add native method and JNI implementation. Effort: 1 hour.

#### W7: FreeType Extension - All Stubs

All FreeType functions are either missing or stubbed (return 0/no-op). This means
font file loading is completely non-functional. The `nHasFreetypeSupport()` function
exists and the JNI correctly returns `JNI_FALSE` when `MSDFGEN_EXTENSIONS` is not
defined, which is accurate for the current build.

**Risk**: Medium. Font loading is a major use case for MSDF rendering. Users must
construct shapes manually from contour/segment primitives.

**Proposed Fix**: Build with FreeType support and implement the full extension API.
This is a significant effort (8-16 hours) requiring FreeType linkage in CMakeLists.txt.

#### W8: Bitmap getPixels Returns Copy, Not Direct Access

The LWJGL3 binding returns a pointer to the native pixel buffer via `PointerBuffer`.
Our binding copies the entire pixel buffer into a Java `float[]`. For a 4096x4096
MTSDF bitmap, this copies 256MB of data.

**Risk**: Performance. Copying is safer (no dangling pointer risk) but expensive
for large bitmaps.

**Proposed Fix**: Add an alternative `getPixelBuffer()` method returning a direct
`java.nio.ByteBuffer` pointing at the native memory (with appropriate lifetime
warnings). Effort: 2 hours.

### 3.3 INFO Issues

#### I1: Return Type Simplification Pattern

Our bindings simplify the C API pattern of "return error code, write result to pointer"
into "return result directly". For example:

- C: `int msdf_shape_validate(shape, int* result)` -> returns error code, writes result
- Ours: `int nShapeValidate(shape)` -> returns result directly (0 or 1)
- LWJGL3: Follows the C pattern exactly

This is a deliberate design choice that simplifies the Java API. The trade-off is
that error detection is conflated with the return value (0 could mean "invalid shape"
or "error"). In practice, the Java wrapper `Shape.validate()` handles this correctly.

#### I2: Bitmap Allocation Pattern Difference

- C/LWJGL3: Passes `msdf_bitmap_t*` struct (type+width+height+handle)
- Ours: Returns handle as `long`, passes type/width/height as separate params

This is fine - it avoids needing to define the struct on the Java side. Our
`JniBitmapWrapper` in the C++ layer manages the struct internally.

#### I3: Transform Struct Not Passed to Native

Our `Transform` class is pure Java - values are extracted and passed as individual
`double` parameters to generation functions. LWJGL3 passes a `MSDFGenTransform`
struct. Both approaches work; ours avoids JNI struct passing complexity.

#### I4: Missing MSDF_FALSE/MSDF_TRUE Constants

LWJGL3 defines `MSDF_FALSE = 0` and `MSDF_TRUE = 1`. Our constants file doesn't
have these. Minor - Java uses `boolean` natively.

#### I5: Missing MSDF_BITMAP_TYPE_MAX, MSDF_SEGMENT_TYPE_MAX

The C header defines max constants for validation. Our code doesn't have them.
Not needed since we validate in `channelCountForType()` with a switch default.

#### I6: Native Method Error Code Mismatch

The JNI layer only defines `MSDF_ERR_FAILED = 1` and `MSDF_ERR_INVALID_ARG = 2`.
The Java `MsdfResult` class defines 6 error codes (SUCCESS through ERR_INVALID_INDEX).
The JNI code only ever returns 0, 1, or 2, so ERR_INVALID_TYPE (3), ERR_INVALID_SIZE
(4), and ERR_INVALID_INDEX (5) are Java-only and never returned from native code.

---

## 4. JNI Layer Correctness Audit

### 4.1 Method Signatures

All native method signatures in `MsdfNative.java` correctly match the JNI naming
convention `Java_com_msdfgen_MsdfNative_n<MethodName>`. The parameter types map
correctly:

| Java Type | JNI Type | Status |
|-----------|----------|--------|
| `long` | `jlong` | Correct - used for native pointers |
| `int` | `jint` | Correct - used for types, indices, results |
| `double` | `jdouble` | Correct - used for coordinates |
| `boolean` | `jboolean` | Correct - used for overlapSupport |
| `float[]` | `jfloatArray` | Correct - used for pixel data |
| `double[]` | `jdoubleArray` | Correct - used for bounds, points |
| `long[]` | `jlongArray` | Correct - used for handle output |
| `String` | `jstring` | Correct - used for font filenames |

### 4.2 Pointer Safety

- All native methods check for null pointers before dereferencing
- `reinterpret_cast<Type*>(jlong)` is used consistently for handle conversion
- Bitmap wrapper adds indirection layer that properly manages typed bitmap deletion
- Segment clone on `contour_add_edge` prevents double-free (segment is cloned before adding)

### 4.3 Array Region Operations

- `SetDoubleArrayRegion`, `SetFloatArrayRegion`, `SetLongArrayRegion` used correctly
- No `GetPrimitiveArrayCritical` usage (safe but slower)
- `GetDoubleArrayRegion` used in `nShapeBoundMiters` to read input bounds

### 4.4 Exception Handling

**CONCERN**: No JNI exception checking after `env->Set*ArrayRegion()` calls. If the
array is too small, JNI will throw `ArrayIndexOutOfBoundsException` but the native
code continues executing. This could lead to undefined behavior in edge cases.

**Proposed Fix**: Check `env->ExceptionCheck()` after array operations and return
early if an exception occurred.

### 4.5 Thread Safety

No explicit synchronization in the JNI layer. MSDFgen C++ API is not thread-safe
for operations on the same shape. The Java wrappers don't synchronize either.

**Risk**: Low for typical use (shapes are usually created/modified on one thread),
but concurrent bitmap generation from the same shape could cause data races.

---

## 5. Memory Management Analysis

### 5.1 Ownership Model

| Object | Allocation | Deallocation | Finalizer | Ownership |
|--------|-----------|-------------|-----------|-----------|
| `Shape` | `new Shape()` in C++ | `delete` in `nShapeFree` | Yes | Always owned |
| `Contour` (from shape) | Part of Shape's vector | Shape destructor | Yes (only if owned) | NOT owned |
| `Contour` (standalone) | `new Contour()` in C++ | `delete` in `nContourFree` | Yes (if owned) | Owned |
| `Segment` (standalone) | `createSegment()` in C++ | `delete` in `nSegmentFree` | Yes (if owned) | Owned |
| `Segment` (from contour) | Part of contour's EdgeHolder | Contour destructor | Yes (only if owned) | NOT owned |
| `Bitmap` | `JniBitmapWrapper` + typed Bitmap | `freeBitmapWrapper` | Yes | Always owned |

### 5.2 Potential Leak Points

1. **Shape.addContour()**: Returns a `Contour(handle, false)` (not owned). This contour
   is owned by the shape and will be freed when the shape is freed. The Java Contour
   wrapper correctly marks `owned=false` so `free()` is a no-op. **CORRECT**.

2. **Contour.getEdge()**: Returns a `Segment(handle, false)` (not owned). Same pattern. **CORRECT**.

3. **Segment cloning in addEdge**: `contour->addEdge(EdgeHolder(segment->clone()))` clones
   the segment. The original segment is still owned by Java and must be freed separately.
   The clone is owned by the contour. **CORRECT but non-obvious**.

4. **Bitmap allocation**: `JniBitmapWrapper` allocates both the wrapper and the typed bitmap.
   `freeBitmapWrapper` frees both. **CORRECT**.

5. **Shape destructor**: When Shape is deleted, it destroys all its contours, which destroy
   their edge holders. All owned native memory is cleaned up. **CORRECT**.

### 5.3 Finalizer Analysis

All native-owning classes (`Shape`, `Bitmap`, `Contour`, `Segment`) implement `finalize()`:
```java
@Override
protected void finalize() throws Throwable {
    if (!freed) { free(); }
    super.finalize();
}
```

**Assessment**: This provides safety-net cleanup but has well-known limitations:
- Finalizers are not guaranteed to run
- No ordering guarantee (shape might be finalized before its contours)
- Performance impact from finalization queue

**Risk**: Medium. For production use, explicit `free()` calls should be mandatory.
The finalizer is only a safety net. Since contours obtained from shapes are `owned=false`,
the ordering issue doesn't cause double-free.

---

## 6. Native Library Loading Audit

### 6.1 Loading Strategy

1. Explicit path (`-Dmsdfgen.library.path`) - Correct
2. Classpath extraction to temp directory - Correct
3. System `java.library.path` - Correct

### 6.2 Platform Detection

| Property | Detection | Correctness |
|----------|-----------|-------------|
| Windows | `os.name` contains "win" | Correct |
| macOS | `os.name` contains "mac" or "darwin" | Correct |
| Linux | `os.name` contains "linux", "nix", or "nux" | Correct |
| x64 | `os.arch` = "amd64" or "x86_64" | Correct |
| ARM64 | `os.arch` = "aarch64" or "arm64" | Correct |
| x86 | `os.arch` = "x86", "i386", or "i686" | Correct |

### 6.3 Resource Path Convention

```
/natives/{os}-{arch}/{libname}
```
Examples:
- `/natives/windows-x64/msdfgen-jni.dll`
- `/natives/linux-aarch64/libmsdfgen-jni.so`
- `/natives/macos-aarch64/libmsdfgen-jni.dylib`

### 6.4 Thread Safety

`NativeLoader.load()` is `synchronized` and uses `volatile boolean loaded`. This is
correctly thread-safe for the double-checked locking pattern.

### 6.5 Temp File Cleanup

`deleteOnExit()` is called on both temp directory and extracted library file. This is
correct for JVM shutdown but may leave files if JVM crashes.

### 6.6 Issues

**CONCERN**: The `loadError` field stores the first failure and prevents all future
loading attempts. If the classpath extraction fails transiently (e.g., disk full),
subsequent attempts will fail even after the issue is resolved. This is acceptable
for a game mod (restart required anyway).

---

## 7. Platform-Specific Validation

### 7.1 Windows x64

- DLL naming: `msdfgen-jni.dll` (no `lib` prefix) - **Correct**
- CMake sets `PREFIX ""` and `SUFFIX ".dll"` - **Correct**
- MSVC compile flags: `/W3 /O2` - **Correct**
- No Windows-specific API usage in JNI code - **Correct**

### 7.2 Linux x64/ARM64

- SO naming: `libmsdfgen-jni.so` - **Correct**
- `CMAKE_POSITION_INDEPENDENT_CODE ON` - **Required for shared libraries, correct**
- GCC/Clang flags: `-Wall -Wextra -O2` - **Correct**
- No Linux-specific API usage in JNI code - **Correct**

### 7.3 macOS Intel/ARM64

- DYLIB naming: `libmsdfgen-jni.dylib` - **Correct**
- Universal binary support: `CMAKE_OSX_ARCHITECTURES "x86_64;arm64"` - **Correct**
- Minimum deployment target: macOS 11.0 - **Correct for ARM64 support**
- **CONCERN**: No code signing configuration. macOS Gatekeeper may block unsigned
  libraries. For Minecraft modding, this is typically handled by the launcher.

### 7.4 Symbol Visibility

- `MSDFGEN_PUBLIC=` (empty define) means all symbols are visible - **Correct for JNI**
- No explicit `__attribute__((visibility("default")))` on JNI exports, but
  `JNIEXPORT` macro handles this - **Correct**

---

## 8. Recommendations (Priority Order)

### Immediate (Before Production)

1. **Add error correction functions** (C1) - Critical for MSDF quality
2. **Complete JNI header declarations** (C2) - Maintenance hygiene
3. **Add missing `psdf_with_config`** (W5) - API symmetry

### Short-Term (Next Sprint)

4. **Add `contour_bound_miters`** (W3) - API completeness
5. **Add `segment_get_direction_change`** (W4) - API completeness
6. **Add `render_sdf`** (W6) - Useful for debugging
7. **Add JNI exception checking** (4.4) - Safety

### Medium-Term (Future Release)

8. **Implement FreeType extension** (W7) - Major feature
9. **Add direct ByteBuffer pixel access** (W8) - Performance
10. **Implement remove_contour/remove_edge** (W1, W2) - API completeness

---

## 9. Risk Assessment Matrix

| Issue | Likelihood | Impact | Risk Score | Mitigation |
|-------|-----------|--------|------------|------------|
| Missing error correction | High (any MSDF user) | High (visual artifacts) | **9/10** | Add API |
| FreeType stubs | Medium (font users) | High (no font loading) | **7/10** | Document limitation |
| Bitmap data copy perf | Low (large bitmaps only) | Medium (slow) | **4/10** | Add ByteBuffer alt |
| Finalizer unreliability | Low (explicit free usual) | Medium (leak) | **3/10** | Document requirement |
| Thread safety | Low (single-thread typical) | High (crash) | **3/10** | Document limitation |
| JNI exception unchecked | Very low (array size correct) | Medium (UB) | **2/10** | Add checks |
