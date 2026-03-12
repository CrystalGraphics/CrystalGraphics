# FreeType + HarfBuzz Java Bindings — Comprehensive Gap Analysis Report

**Date**: 2026-03-12  
**Scope**: API completeness, JNI correctness, memory model, integration layer, platform coverage  
**Reference**: LWJGL 3.x FreeType/HarfBuzz bindings, FreeType 2.13.2 API, HarfBuzz 8.3.0 API  
**Risk Assessment**: HIGHEST (two-library JNI coordination)

---

## Executive Summary

The FreeType + HarfBuzz Java bindings provide a **focused, production-viable subset** of both libraries' APIs, targeting text rendering in Minecraft 1.7.10 mods. The implementation is architecturally sound with a clean JNI layer and proper lifecycle management. However, several issues of varying severity have been identified, most critically around **memory management for `newFaceFromMemory()`** and the **lack of Java-side finalizer safety nets**.

**Critical Issues Found**: 3  
**High Severity Issues**: 5  
**Medium Severity Issues**: 6  
**Low Severity / Enhancement**: 8  

---

## 1. FreeType API Completeness Audit

### 1.1 Covered API Surface

| FreeType Function | Java Binding | JNI Implementation | Status |
|---|---|---|---|
| `FT_Init_FreeType` | `FreeTypeLibrary.create()` | `nInitFreeType` | ✅ Correct |
| `FT_Done_FreeType` | `FreeTypeLibrary.destroy()` | `nDoneFreeType` | ✅ Correct |
| `FT_New_Face` | `newFace(path, index)` | `nNewFace` | ✅ Correct |
| `FT_New_Memory_Face` | `newFaceFromMemory(data, index)` | `nNewFaceFromMemory` | ⚠️ See §1.3 |
| `FT_Done_Face` | `FTFace.destroy()` | `nDoneFace` | ⚠️ See §1.3 |
| `FT_Set_Pixel_Sizes` | `setPixelSizes(w, h)` | `nSetPixelSizes` | ✅ Correct |
| `FT_Set_Char_Size` | `setCharSize(w, h, hDPI, vDPI)` | `nSetCharSize` | ✅ Correct |
| `FT_Get_Char_Index` | `getCharIndex(charCode)` | `nGetCharIndex` | ✅ Correct |
| `FT_Load_Glyph` | `loadGlyph(index, flags)` | `nLoadGlyph` | ✅ Correct |
| `FT_Load_Char` | `loadChar(charCode, flags)` | `nLoadChar` | ✅ Correct |
| `FT_Render_Glyph` | `renderGlyph(mode)` | `nRenderGlyph` | ✅ Correct |
| `FT_Get_Kerning` | `getKerning(l, r, mode)` | `nGetKerning` | ✅ Correct |
| `FT_HAS_KERNING` | `hasKerning()` | `nHasKerning` | ✅ Correct |
| `FT_Library_Version` | `getVersion()` | `nGetVersion` | ✅ Correct |
| Glyph metrics access | `getGlyphMetrics()` | `nGetGlyphMetrics` | ✅ Correct |
| Glyph bitmap access | `getGlyphBitmap()` | `nGetGlyphBitmap` | ✅ Correct |
| Face info fields | `getFamilyName()`, `getStyleName()`, etc. | Various | ✅ Correct |

### 1.2 Missing APIs (vs. LWJGL 3.x — Intentionally Scoped Out)

The following are present in LWJGL 3.x but **intentionally omitted** as they are not needed for the text rendering use case:

| FreeType Function | Category | Need for Minecraft Text Rendering |
|---|---|---|
| `FT_Open_Face` | Advanced face loading | Low — file/memory covers all cases |
| `FT_Attach_File/Stream` | Font attachment | Very Low — TTC support via faceIndex |
| `FT_Select_Size` | Bitmap strike selection | Low — vector rendering preferred |
| `FT_Get_Glyph` / `FT_Glyph_*` | Glyph object management | Low — slot-based access sufficient |
| `FT_Outline_*` | Outline decomposition | Low — bitmap rendering used |
| `FT_Stroker_*` | Outline stroking | Low — not needed for text |
| `FT_Bitmap_*` | Bitmap manipulation | Low — raw buffer access sufficient |
| `FT_Get_Name_Index` | Glyph name lookup | Very Low |
| `FT_Select_Charmap` | Charmap selection | Low — default Unicode charmap used |
| `FT_Get_FSType_Flags` | Embedding flags | Very Low |
| `FT_Get_Sfnt_Table` | SFNT table access | Low |
| `FT_Property_Set/Get` | Module property control | Very Low |
| `FT_Set_Transform` | Glyph transformation | Medium — could be useful for rotated text |
| `FT_Get_Advance` | Quick advance retrieval | Medium — would optimize text measurement |
| SVG, Color, Palette APIs | Color emoji / SVG | Low for MC 1.7.10 |

**Assessment**: The omissions are **reasonable and intentional** for the target use case. The `FT_Set_Transform` and `FT_Get_Advance` functions would be the most valuable additions if the API were expanded.

### 1.3 CRITICAL: Memory Leak in `newFaceFromMemory()` — `malloc` Without `free`

**Severity**: 🔴 CRITICAL

In `freetype_jni.cpp` line 65:
```cpp
FT_Byte *persistentData = (FT_Byte *)malloc(dataLen);
memcpy(persistentData, data, dataLen);
```

The `persistentData` buffer is `malloc`'d and must live for the FT_Face's lifetime. However, `nDoneFace` (line 230) only calls `FT_Done_Face(face)` — it does **NOT** free the `persistentData` buffer.

**Impact**: Every `newFaceFromMemory()` call **leaks `dataLen` bytes** of native memory. A typical TTF font is 200KB-2MB. Loading 10 fonts from memory leaks 2-20MB permanently.

**Root Cause**: FreeType's `FT_New_Memory_Face` does not take ownership of the buffer — the caller must free it after `FT_Done_Face`. The JNI layer has no mechanism to track the `persistentData` pointer.

**Proposed Fix**: 
```cpp
// Option A: Store persistentData alongside face pointer using a map
static std::unordered_map<FT_Face, FT_Byte*> memoryFaceBuffers;

// In nNewFaceFromMemory:
memoryFaceBuffers[face] = persistentData;

// In nDoneFace:
auto it = memoryFaceBuffers.find(face);
if (it != memoryFaceBuffers.end()) {
    free(it->second);
    memoryFaceBuffers.erase(it);
}
FT_Done_Face(face);
```

```cpp
// Option B: Pass both pointers back to Java as a long[2]
// Java stores the buffer pointer and frees it on destroy
```

---

## 2. HarfBuzz API Completeness Audit

### 2.1 Covered API Surface

| HarfBuzz Function | Java Binding | JNI Implementation | Status |
|---|---|---|---|
| `hb_buffer_create` | `HBBuffer.create()` | `nCreate` | ✅ Correct |
| `hb_buffer_destroy` | `HBBuffer.destroy()` | `nDestroy` | ✅ Correct |
| `hb_buffer_add_utf8` | `addUTF8(text)` | `nAddUTF8` | ✅ Correct |
| `hb_buffer_add_codepoints` | `addCodepoints(int[])` | `nAddCodepoints` | ✅ Correct |
| `hb_buffer_set_direction` | `setDirection(dir)` | `nSetDirection` | ✅ Correct |
| `hb_buffer_get_direction` | `getDirection()` | `nGetDirection` | ✅ Correct |
| `hb_buffer_set_script` | `setScript(script)` | `nSetScript` | ✅ Correct |
| `hb_buffer_get_script` | `getScript()` | `nGetScript` | ✅ Correct |
| `hb_buffer_set_language` | `setLanguage(lang)` | `nSetLanguage` | ✅ Correct |
| `hb_buffer_guess_segment_properties` | `guessSegmentProperties()` | `nGuessSegmentProperties` | ✅ Correct |
| `hb_buffer_get_length` | `getLength()` | `nGetLength` | ✅ Correct |
| `hb_buffer_get_glyph_infos` | `getGlyphInfos()` | `nGetGlyphInfos` | ✅ Correct |
| `hb_buffer_get_glyph_positions` | `getGlyphPositions()` | `nGetGlyphPositions` | ✅ Correct |
| `hb_buffer_reset` | `reset()` | `nReset` | ✅ Correct |
| `hb_buffer_clear_contents` | `clearContents()` | `nClearContents` | ✅ Correct |
| `hb_font_create` | via `HBFont.createFromFile()` | `nCreateFromFile` | ✅ Correct |
| `hb_font_destroy` | `HBFont.destroy()` | `nDestroy` | ✅ Correct |
| `hb_font_set_scale` | `setScale(x, y)` | `nSetScale` | ✅ Correct |
| `hb_font_get_scale` | `getScale()` | `nGetScale` | ✅ Correct |
| `hb_font_set_ppem` | `setPpem(x, y)` | `nSetPpem` | ✅ Correct |
| `hb_shape` | `HBShape.shape(font, buf)` | `nShape` | ✅ Correct |
| `hb_feature_from_string` | via `shape(font, buf, features)` | In `nShape` | ✅ Correct |

### 2.2 Missing APIs (Intentionally Scoped Out)

| HarfBuzz Function | Category | Need |
|---|---|---|
| `hb_buffer_serialize_*` | Buffer serialization | Low — debug only |
| `hb_buffer_set_cluster_level` | Cluster control | Low |
| `hb_buffer_set_content_type` | Content type | Low |
| `hb_font_get_ppem` | PPEM getter | Medium — useful for symmetry |
| `hb_font_get_face` | Face access | Low |
| `hb_face_*` (many) | Face management | Low — covered via integration |
| `hb_blob_*` (many) | Blob management | Low — internal only |
| `hb_set_*` | Set operations | Low |
| `hb_unicode_*` | Unicode functions | Low |
| `hb_ot_*` | OpenType layout | Medium — useful for advanced typog. |
| `hb_shape_plan_*` | Shape plan caching | Medium — optimization for repeated shaping |

**Assessment**: Coverage is excellent for the target use case. `hb_shape_plan_*` would be the most impactful addition for performance.

### 2.3 HBFont.createFromFile Default Scale Issue

**Severity**: 🟡 MEDIUM

In `harfbuzz_jni.cpp` line 141:
```cpp
hb_font_set_scale(font, 64 * 16, 64 * 16);
```

The default scale is hardcoded to `64 * 16 = 1024`. This is a reasonable default but:
1. It's not documented in the Java API
2. Users might expect no scale transformation
3. When used via `FreeTypeHarfBuzzIntegration`, this is overridden by `syncFontMetrics`, so only affects standalone HarfBuzz usage

**Recommendation**: Document the default scale or allow configuration.

---

## 3. CRITICAL: Integration Layer Audit (FreeType ↔ HarfBuzz)

### 3.1 `hb_ft_font_create()` — Ownership Semantics

The integration uses `hb_ft_font_create(face, NULL)` in `integration.cpp` line 6.

**HarfBuzz `hb_ft_font_create` semantics**:
- The second parameter is `hb_destroy_func_t` — a destroy callback for the FT_Face
- Passing `NULL` means HarfBuzz will **NOT** destroy the FT_Face when the hb_font is destroyed
- HarfBuzz internally calls `hb_ft_font_set_funcs()` which sets up callback functions that **call into FT_Face**
- The FT_Face **must remain valid** for the entire lifetime of the hb_font

**This is correct behavior** — the Java side explicitly documents "destroy HBFont BEFORE FTFace".

### 3.2 CRITICAL: No Enforcement of Destruction Order

**Severity**: 🔴 CRITICAL

While the documentation says "destroy HBFont before FTFace", there is **NO programmatic enforcement**:

```java
// This compiles and runs - but causes USE-AFTER-FREE:
FTFace face = ft.newFace("font.ttf", 0);
HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
face.destroy();       // FT_Face freed
HBShape.shape(hbFont, buffer);  // SEGFAULT: hb_font callbacks read freed FT_Face
hbFont.destroy();     // Double-free or crash
```

**Impact**: Undefined behavior, segmentation faults, heap corruption. In a Minecraft mod context, this would crash the game client.

**Proposed Fix**: Track the FT_Face ↔ HBFont relationship in Java:
```java
// In FTFace:
private final List<WeakReference<HBFont>> dependentHBFonts = new ArrayList<>();

public void destroy() {
    for (WeakReference<HBFont> ref : dependentHBFonts) {
        HBFont font = ref.get();
        if (font != null && !font.isDestroyed()) {
            throw new IllegalStateException(
                "Cannot destroy FTFace while HBFont references it. " +
                "Destroy HBFont first.");
        }
    }
    // ... proceed with destruction
}
```

### 3.3 `syncFontMetrics` Scale Calculation

**Severity**: 🟡 MEDIUM

In `integration.cpp` line 18:
```cpp
hb_font_set_scale(hbFont, (int)(face->size->metrics.x_scale), (int)(face->size->metrics.y_scale));
```

`FT_Size_Metrics.x_scale` is a 16.16 fixed-point value representing the scaling factor. This is **not the correct scale for HarfBuzz**. HarfBuzz expects scale in font units per EM (typically 26.6 fixed-point or pixels).

The correct pattern used by HarfBuzz's own `hb_ft_font_set_funcs` sets scale from:
```cpp
int x_scale = face->size->metrics.x_ppem * 64;  // 26.6 fixed-point
int y_scale = face->size->metrics.y_ppem * 64;
```

**Impact**: Glyph positions may be scaled incorrectly after `syncFontMetrics()`. The existing test (`testSyncMetricsAfterSizeChange`) only verifies that 32px > 16px, not that the actual values are correct.

**Proposed Fix**:
```cpp
hb_font_set_scale(hbFont, 
    face->size->metrics.x_ppem * 64, 
    face->size->metrics.y_ppem * 64);
```

### 3.4 No Reference Counting Between Libraries

**Severity**: 🟡 HIGH

HarfBuzz does reference-count its own objects (`hb_font_reference()`, `hb_font_destroy()`), but when created via `hb_ft_font_create(face, NULL)`, it does **not** increment the FT_Face reference count. FreeType has no internal reference counting on FT_Face objects.

**Impact**: Creating multiple HBFonts from the same FT_Face works, but destroying the FT_Face invalidates ALL HBFonts simultaneously with no warning.

**This is inherent to the design** and documented. No fix needed, but tests should validate this behavior.

### 3.5 Integration Layer Error Handling

In `integration.cpp` line 7:
```cpp
if (hbFont == NULL) {
    return 0;
}
```

`hb_ft_font_create()` in practice **never returns NULL** — it allocates internally and returns a valid pointer (or the HarfBuzz immutable "empty" font on OOM). The NULL check is defensive but unlikely to trigger.

**Assessment**: Acceptable defensive code.

---

## 4. JNI Layer Deep Dive

### 4.1 FreeType JNI Correctness Matrix

| JNI Method | Pointer Cast | String Handling | Array Handling | Error Prop. | Status |
|---|---|---|---|---|---|
| `nInitFreeType` | ✅ `intptr_t` | N/A | N/A | ✅ | ✅ |
| `nDoneFreeType` | ✅ | N/A | N/A | N/A | ✅ |
| `nNewFace` | ✅ | ✅ Release | N/A | ✅ Returns 0 | ✅ |
| `nNewFaceFromMemory` | ✅ | N/A | ✅ JNI_ABORT | ✅ Returns 0 | ⚠️ Leak |
| `nGetVersion` | ✅ | N/A | ✅ | N/A | ✅ |
| `nSetPixelSizes` | ✅ | N/A | N/A | ✅ Returns err | ✅ |
| `nSetCharSize` | ✅ | N/A | N/A | ✅ Returns err | ✅ |
| `nGetCharIndex` | ✅ | N/A | N/A | N/A | ✅ |
| `nLoadGlyph` | ✅ | N/A | N/A | ✅ Returns err | ✅ |
| `nLoadChar` | ✅ | N/A | N/A | ✅ Returns err | ✅ |
| `nRenderGlyph` | ✅ | N/A | N/A | ✅ Returns err | ✅ |
| `nGetGlyphMetrics` | ✅ | N/A | ✅ | N/A | ✅ |
| `nGetGlyphBitmap` | ✅ | N/A | ✅ Copy | N/A | ✅ |
| `nGetFamilyName` | ✅ | ✅ NewStringUTF | N/A | ✅ NULL safe | ✅ |
| `nGetStyleName` | ✅ | ✅ NewStringUTF | N/A | ✅ NULL safe | ✅ |
| `nGetKerning` | ✅ | N/A | ✅ | ✅ Err → zeros | ✅ |
| `nHasKerning` | ✅ | N/A | N/A | N/A | ✅ |
| `nDoneFace` | ✅ | N/A | N/A | N/A | ⚠️ No free |

### 4.2 HarfBuzz JNI Correctness Matrix

| JNI Method | Pointer Cast | String Handling | Array Handling | Error Prop. | Status |
|---|---|---|---|---|---|
| `nCreate` | ✅ | N/A | N/A | ✅ alloc check | ✅ |
| `nDestroy` | ✅ | N/A | N/A | N/A | ✅ |
| `nAddUTF8` | ✅ | N/A | ✅ JNI_ABORT | N/A | ✅ |
| `nAddCodepoints` | ✅ | N/A | ✅ JNI_ABORT | N/A | ✅ |
| `nSetDirection` | ✅ | N/A | N/A | N/A | ✅ |
| `nGetDirection` | ✅ | N/A | N/A | N/A | ✅ |
| `nSetScript` | ✅ | N/A | N/A | N/A | ✅ |
| `nGetScript` | ✅ | N/A | N/A | N/A | ✅ |
| `nSetLanguage` | ✅ | ✅ Release | N/A | N/A | ✅ |
| `nGuessSegmentProperties` | ✅ | N/A | N/A | N/A | ✅ |
| `nGetLength` | ✅ | N/A | N/A | N/A | ✅ |
| `nGetGlyphInfos` | ✅ | N/A | ✅ obj array | N/A | ✅ |
| `nGetGlyphPositions` | ✅ | N/A | ✅ obj array | N/A | ✅ |
| `nReset` | ✅ | N/A | N/A | N/A | ✅ |
| `nClearContents` | ✅ | N/A | N/A | N/A | ✅ |
| `nCreateFromFile` | ✅ | ✅ Release | N/A | ✅ length check | ✅ |
| `nDestroy` (font) | ✅ | N/A | N/A | N/A | ✅ |
| `nSetScale` | ✅ | N/A | N/A | N/A | ✅ |
| `nGetScale` | ✅ | N/A | ✅ | N/A | ✅ |
| `nSetPpem` | ✅ | N/A | N/A | N/A | ✅ |
| `nShape` | ✅ | ✅ per-feature | ✅ free features | N/A | ✅ |

### 4.3 Integration JNI

| JNI Method | Pointer Cast | Status |
|---|---|---|
| `nCreateHBFontFromFTFace` | ✅ `intptr_t` both ways | ✅ |
| `nSyncFontMetrics` | ✅ | ⚠️ Scale issue (§3.3) |

### 4.4 JNI Issues

**Issue J1: `nGetGlyphBitmap` local ref management** — Severity: LOW
The bitmap JNI code creates a local ref to `jBuffer`, uses it in `NewObject`, then deletes it. This is correct. However, `env->NewByteArray(0)` when `bufferSize == 0` creates a zero-length array — safe but slightly wasteful.

**Issue J2: `nGetKerning` silently swallows errors** — Severity: LOW
If `FT_Get_Kerning` fails, the method returns `{0, 0}` without propagating the error. This is acceptable for kerning (missing kerning ≈ no kerning) but inconsistent with other methods.

**Issue J3: Feature parsing in `nShape`** — Severity: LOW
If `hb_feature_from_string` fails for an invalid feature string, the feature struct is left uninitialized. HarfBuzz may ignore it or produce unexpected behavior.

---

## 5. Memory Management Pattern Analysis

### 5.1 FreeType Memory Model

```
Allocation                    Deallocation              Status
──────────────────────────────────────────────────────────────
FT_Init_FreeType → library    FT_Done_FreeType          ✅ Paired
FT_New_Face → face            FT_Done_Face              ✅ Paired
malloc(persistentData)        ???                       ❌ LEAKED
Bitmap buffer (glyph slot)    Auto (next load/render)   ✅ Internal
```

### 5.2 HarfBuzz Memory Model

```
Allocation                    Deallocation              Status
──────────────────────────────────────────────────────────────
hb_buffer_create → buffer     hb_buffer_destroy         ✅ Paired (refcounted)
hb_font_create → font         hb_font_destroy           ✅ Paired (refcounted)
hb_blob_create_from_file      hb_blob_destroy           ✅ Internal to createFromFile
hb_face_create → face          hb_face_destroy           ✅ Internal to createFromFile
malloc(features) in nShape    free(features) in nShape  ✅ Paired
```

### 5.3 Integration Memory Model

```
Allocation                    Deallocation              Status
──────────────────────────────────────────────────────────────
hb_ft_font_create → hbFont    hb_font_destroy           ✅ Paired
FT_Face internal reference    NOT incremented           ✅ By design (NULL destroy_func)
```

### 5.4 Lifecycle Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    CORRECT LIFECYCLE                         │
│                                                             │
│  1. FreeTypeLibrary.create()     →  FT_Library allocated    │
│  2. ft.newFace(path, 0)          →  FT_Face allocated       │
│  3. face.setPixelSizes(0, 24)    →  FT_Face configured      │
│  4. createHBFontFromFTFace(face) →  hb_font_t allocated     │
│     └─ hb_font internally holds pointer to FT_Face          │
│  5. HBBuffer.create()            →  hb_buffer_t allocated   │
│  6. buffer.addUTF8(text)         →  No new allocation       │
│  7. HBShape.shape(hbFont, buf)   →  temp features malloc/free│
│  8. buffer.getGlyphInfos()       →  Java objects created    │
│  9. buffer.destroy()             →  hb_buffer_t freed       │
│ 10. hbFont.destroy()             →  hb_font_t freed         │
│     └─ Does NOT free FT_Face (NULL destroy_func)            │
│ 11. face.destroy()               →  FT_Face freed           │
│ 12. ft.destroy()                 →  FT_Library freed        │
│                                                             │
│  INVARIANT: Steps 9-12 must happen in this order            │
│  VIOLATION: Destroying face before hbFont → USE-AFTER-FREE  │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Resource Cleanup Validation

### 6.1 Cleanup Correctness Matrix

| Resource Type | Create | Destroy | Paired? | Enforced? | Idempotent? |
|---|---|---|---|---|---|
| FT_Library | `create()` | `destroy()` | ✅ | ❌ | ✅ (ptr = 0 check) |
| FT_Face (file) | `newFace()` | `destroy()` | ✅ | ❌ | ✅ |
| FT_Face (memory) | `newFaceFromMemory()` | `destroy()` | ⚠️ Buffer leak | ❌ | ✅ |
| hb_buffer_t | `create()` | `destroy()` | ✅ | ❌ | ✅ |
| hb_font_t (file) | `createFromFile()` | `destroy()` | ✅ | ❌ | ✅ |
| hb_font_t (FT) | `createHBFontFromFTFace()` | `destroy()` | ✅ | ❌ | ✅ |

### 6.2 Missing: Java Finalizer Safety Net

**Severity**: 🟠 HIGH

None of the native resource holders (`FreeTypeLibrary`, `FTFace`, `HBBuffer`, `HBFont`) implement `finalize()` or use `Cleaner`. If a user forgets to call `destroy()`, native memory is leaked permanently.

**Assessment**: While finalizers are discouraged in modern Java, for JNI bindings targeting Java 8 they are the **only available safety net**. The `Cleaner` API (Java 9+) is not available.

**Proposed Fix**: Add `@Override protected void finalize()` to all four classes with logging on first finalization to warn developers of leaked resources.

### 6.3 Missing: Double-Destroy Protection

All `destroy()` methods check `nativePtr != 0` before calling native code — this is correct and prevents double-free. ✅

### 6.4 Exception Safety

If an exception occurs during construction (e.g., `newFace` succeeds but a subsequent `setPixelSizes` throws), the partially-constructed face is leaked unless the caller uses try/finally. This is standard Java behavior and documented.

---

## 7. Native Library Loading Audit

### 7.1 Loading Strategy

| Strategy | Priority | Implementation | Status |
|---|---|---|---|
| System property `-Dfreetype.harfbuzz.native.path` | 1 | `System.load(file)` | ✅ |
| Classpath extraction `/natives/{platform}/` | 2 | Extract to temp, `System.load` | ✅ |
| System `java.library.path` | 3 | `System.loadLibrary` | ✅ |

### 7.2 Issues

**Issue N1: Single JNI library for both FreeType and HarfBuzz** — ✅ CORRECT
Both are compiled into `freetype_harfbuzz_jni.{dll,so,dylib}`. This simplifies loading and ensures both are available simultaneously. HarfBuzz `NativeLoader` delegates to FreeType `NativeLoader`.

**Issue N2: Temp file not cleaned up on Windows** — Severity: LOW
`deleteOnExit()` may not work reliably on Windows if the DLL is still loaded when the JVM exits. The DLL remains in the temp directory.

**Issue N3: No version validation** — Severity: LOW
The loader doesn't verify that the loaded library matches the expected FreeType/HarfBuzz version. A mismatched native library could cause subtle bugs.

### 7.3 Dependency Ordering

HarfBuzz depends on FreeType (HarfBuzz is built with `HB_HAVE_FREETYPE=ON`). Since both are statically linked into a single JNI library, **there is no dependency ordering issue**. ✅

---

## 8. Platform-Specific Validation

### 8.1 Platform Matrix

| Platform | Native Binary | NativeLoader Detection | Build Support | Status |
|---|---|---|---|---|
| Windows x86_64 | `freetype_harfbuzz_jni.dll` | `os.name.contains("win")` → "windows" | MSYS2/MSVC | ✅ |
| Linux x86_64 | `libfreetype_harfbuzz_jni.so` | `os.name.contains("linux")` → "linux" | GCC/Clang | ✅ |
| Linux aarch64 | `libfreetype_harfbuzz_jni.so` | `os.arch.equals("aarch64")` → "aarch64" | Cross-compile | ✅ |
| macOS x86_64 | `libfreetype_harfbuzz_jni.dylib` | `os.name.contains("mac")` → "macos" | Clang | ✅ |
| macOS aarch64 | `libfreetype_harfbuzz_jni.dylib` | `os.arch.equals("arm64")` → "aarch64" | Clang | ✅ |

### 8.2 Platform Issues

**Issue P1: macOS ARM64 + Java 8** — Severity: INFO (documented)
Java 8 does not have native ARM64 JVMs for macOS. Users must use Rosetta 2 with the x86_64 binary. This is correctly documented in `MACOS_COMPATIBILITY.md`.

**Issue P2: CMakeLists.txt source paths** — Severity: LOW
The `CMakeLists.txt` references `src/cpp/*.cpp` but is located *inside* `src/cpp/`. The relative paths may be incorrect depending on where CMake is invoked from. The `build-natives.sh` works around this by using `-S` flag.

---

## 9. Issue Summary by Severity

### 🔴 CRITICAL (Must Fix Before Production)

| ID | Issue | Component | Impact |
|---|---|---|---|
| C1 | `newFaceFromMemory()` leaks malloc'd buffer | FreeType JNI | Memory leak proportional to font size |
| C2 | No enforcement of HBFont-before-FTFace destruction order | Integration | Use-after-free / segfault |
| C3 | `syncFontMetrics` uses wrong scale values | Integration JNI | Incorrect glyph positioning |

### 🟠 HIGH (Should Fix)

| ID | Issue | Impact |
|---|---|---|
| H1 | No finalizer safety net on any native resource class | Permanent native memory leak on missed `destroy()` |
| H2 | `FTFace` doesn't track parent `FreeTypeLibrary` lifetime | Destroy library before face → crash |
| H3 | No tracking of FT_Face → HBFont relationships | Silent use-after-free |
| H4 | `testIntegrationWithDestroyedFace` test creates face from empty byte array — always throws before reaching test | Test doesn't test what it claims |
| H5 | No thread-safety documentation or guarantees | Race conditions in multi-mod environment |

### 🟡 MEDIUM

| ID | Issue | Impact |
|---|---|---|
| M1 | HBFont.createFromFile default scale undocumented | Unexpected scaling behavior |
| M2 | `nGetKerning` silently swallows errors | Missing error information |
| M3 | Feature string parsing failures undetected | Incorrect shaping |
| M4 | No `FT_Set_Transform` binding | Cannot do rotated/transformed text |
| M5 | No `FT_Get_Advance` binding | Slower text measurement |
| M6 | No `hb_font_get_ppem` binding | API asymmetry |

### 🟢 LOW / Enhancement

| ID | Issue | Impact |
|---|---|---|
| L1 | Temp DLL not cleaned on Windows | Disk space |
| L2 | No native library version validation | Potential mismatch |
| L3 | `nGetGlyphBitmap` creates zero-length array for empty bitmaps | Negligible waste |
| L4 | CMakeLists.txt relative path ambiguity | Build confusion |
| L5 | Missing `hb_shape_plan_*` for performance | Slower repeated shaping |
| L6 | No `FT_LOAD_CROP_BITMAP` flag | Minor feature gap |
| L7 | `FTRenderMode` missing `FT_RENDER_MODE_LIGHT` | Minor feature gap |
| L8 | `FTLoadFlags` missing `FT_LOAD_COMPUTE_METRICS` | Minor feature gap |

---

## 10. Recommendations

### Immediate Actions (Before Production)

1. **Fix C1**: Add buffer tracking map in JNI to free `persistentData` in `nDoneFace`
2. **Fix C2**: Add Java-side relationship tracking between FTFace and HBFont
3. **Fix C3**: Correct scale calculation in `syncFontMetrics` to use `x_ppem * 64`

### Short-Term Actions

4. Add `finalize()` methods to all native resource holders as safety net (H1)
5. Track FTFace → FreeTypeLibrary relationship to prevent library-first destruction (H2)
6. Fix integration test H4 to properly test destroyed face behavior
7. Document thread-safety guarantees (H5)

### Long-Term Enhancements

8. Add `FT_Set_Transform` for rotated/transformed text (M4)
9. Add `FT_Get_Advance` for faster text measurement (M5)
10. Consider `hb_shape_plan_*` for caching (L5)

---

*End of Gap Analysis Report*
