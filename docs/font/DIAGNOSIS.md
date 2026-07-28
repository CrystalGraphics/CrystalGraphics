# Text/Font Stack — Engineering Diagnosis

> ## ⚠️ Historical record — read the naming note before the findings
>
> This is a dated diagnosis with its findings annotated as fixed. It is **not** a current-state
> reference; for that see `architecture.md` and `api-guide.md`.
>
> **Two atlas classes existed when this was written**: a paged one whose name carried the word
> "Paged", and a legacy single-page LRU atlas holding the plain `CgGlyphAtlas` name. The legacy
> one has since been retired (it survives as `CgOldGlyphAtlas`, no callers) and the paged one took
> the plain name. A later repo-wide rename collapsed both identifiers in this document into
> `CgGlyphAtlas`, so **sentences below that appear to compare the class with itself are comparing
> the paged atlas against the retired one** — for example "`CgGlyphAtlas` (legacy) does LRU
> eviction… `CgGlyphAtlas` (current) does no eviction at all". Read the first as the retired class
> and the second as today's.
>
> **Also since superseded:** the eviction section's claim that `createForPagedRegistry` defaults to
> `DEFAULT_MAX_PAGES = 32` is no longer true — registry atlases are created with
> `UNBOUNDED_PAGES` and never evict. And atlases are no longer keyed per font at all: two exist
> process-wide, one per texture format, shared by every font.

**Scope:** `com.crystalgraphics.text.*` (`layout`, `cache`, `atlas`, `atlas/packing`, `msdf`, `render`) plus `api/font`, `api/text`.
**Method:** full read of `CgTextRenderer`, `CgFontRegistry` (1387 lines), `CgOldGlyphAtlas`, `CgGlyphAtlas`, `CgGlyphAtlasPage`, `CgMsdfGenerator`, `CgResolvedGlyphs`, `CgTextLayoutEngine`, `CgGlyphGenerationExecutor`, `MaxRectsPacker`, `CgFontKey`/`CgGlyphKey`, plus every package `AGENTS.md`.

## Verdict up front

The instinct is correct, but it's aimed at the wrong layer. **`text/render`** (the newest code — `CgTextRenderer`, `CgResolvedGlyphs`, `CgTextRenderContext`) is genuinely well-designed: single-walk flattening, packed-long sort keys instead of key objects, grow-only scratch arrays, a real three-space model, a fluent `Draw` API that replaced a 13-overload matrix. That part reads like it was written once, deliberately, by someone who knew what they wanted.

**`text/cache` and `text/atlas`** were where the "ad-hoc, stitched together" feeling was 100% justified — `CgFontRegistry` carried **three parallel implementations of the same glyph-rasterization pipeline** (legacy single-page, effective-size-aware single-page, paged) that were never consolidated after the paged system became authoritative, and `CgGlyphAtlas`/`CgGlyphAtlasPage` were near-duplicate classes that had already drifted. **This has been fixed** — see A1/A2 below. This was classic incremental-feature-addition-without-cleanup: every new capability (paging, effective-size raster, MSDF) was added *alongside* the old path instead of *replacing* it, and the old path was never deleted because something still depended on it (the debug harness) — until that dependency was migrated to the paged equivalents and the legacy path was deleted outright.

So: this used to be "two architectures, superimposed, with the old one never torn out." As of the A1/A2 fix, it's one.

---

## Findings

### A. Architecture / Duplication

**A1. `CgFontRegistry` carries a full second (and third) copy of glyph rasterization — HIGH — ✅ FIXED**
`CgFontRegistry.java`:
- `ensureBitmapGlyphPaged` (§5, ~L460–528) — paged path, FreeType rasterize + re-measure-at-base-size
- `ensureBitmapGlyph` (§10, ~L831–878) — legacy single-page, **same FreeType calls, same re-measure logic, same sub-pixel-bucket branch**, different atlas type
- `ensureBitmapGlyphAtEffectiveSize` (§10, ~L905–976) — a **third** copy of the same body, this time keyed by `CgRasterFontKey`

All three do: `setPixelSizes` → compute `loadFlags` from `subPixelBucket`/`SUB_PIXEL_BUCKET_MAX_PX` → `loadGlyphOrFallback` → optional `outlineTranslate` → `renderGlyph` → read `FTBitmap` → `normalizeBitmapBuffer` → re-measure at base px if `effectiveTargetPx != basePx` → build the region/placement. This is ~70 lines copy-pasted three times with only the destination atlas type and return type differing. The equivalent exists for MSDF (`ensureMsdfGlyphPaged` / `ensureMsdfGlyph` / `ensureMsdfGlyphAtEffectiveSize`).

*Why it matters:* every FreeType-rasterization bugfix (e.g. a hinting-rounding fix, a sub-pixel-bucket edge case) has to be applied in up to three places, and there's no compiler check that they stay in sync. The package's own `AGENTS.md` even documents this by carving the class into "§10 Legacy single-page path... **COMPATIBILITY / TRANSITION CODE**" — the maintainers know it's there, and it's been left as a `@deprecated`-annotated dead weight rather than migrated/removed. This is precisely what "stitched together in an ad-hoc fashion" looks like from the inside: nobody deleted the old implementation when the new one landed.

> **Fixed.** Confirmed zero external callers anywhere in the repo (including tests) for `ensureGlyph`, `ensureGlyphAtEffectiveSize`, `getBitmapAtlas`/`getMsdfAtlas` (all overloads) — the paged path (`ensureGlyphPaged`/`queueGlyphPaged`) was already the sole path real rendering code used. Deleted the full §10 legacy block (all three rasterization copies, both MSDF `queueOrGenerate` overloads in `CgMsdfGenerator`, the legacy/raster atlas maps, `releaseRasterAtlasesForFont`, and the `toAtlasGlyphKey` alias). The three debug-harness call sites that *did* depend on the legacy enumeration API (`AtlasDumpScene`, `TextScene2D`, `CgFontDemo`) were migrated to new paged-equivalent finders (`findPopulatedPagedBitmapPage`/`findPopulatedPagedMsdfPage`, alongside the pre-existing `findAllPopulatedPagedBitmapPages`/`findAllPopulatedPagedMsdfPages`) before deletion. `CgFontRegistry` dropped from 1387 lines to 909. Verified via full `core` test suite (574 tests, only the 2 pre-existing unrelated `CgVertexAttributeInjectionTest` failures remain) and manual harness verification (Text Scene 3D rendered correctly post-fix).

**A2. `CgGlyphAtlas` and `CgGlyphAtlasPage` are ~90% duplicate classes that have already drifted — HIGH — ✅ FIXED**
Compare `CgGlyphAtlas.java` and `CgGlyphAtlasPage.java`:
- Both redeclare the **exact same 16 raw GL constants** (`GL_TEXTURE_2D = 0x0DE1`, `GL_R8 = 0x8229`, …) as private statics instead of using the project's own `CgTextureType`/`CgGL` (the codebase's own `AGENTS.md` calls `CgTextureType` "the single source of truth" for ~42 format constants — these two classes ignore it and hand-roll their own copy of a subset).
- Both have byte-for-byte identical `uploadBitmap`/`uploadMsdf` bodies (`CgGlyphAtlas.java:484-523` vs `CgGlyphAtlasPage.java:352-391`).
- Both have near-identical texture-creation code in `create()`.
- **They've already diverged incorrectly.** `CgGlyphAtlas`'s constructor (`CgGlyphAtlas.java:115-123`) correctly sizes the initial MSDF upload buffer per type: `MTSDF → 64*64*4`, plain `MSDF → 64*64*3`. `CgGlyphAtlasPage`'s constructor (`CgGlyphAtlasPage.java:93-101`) has the same `if/else if/else` shape but the `else` (MSDF) branch was copy-pasted to `64*64*4` too — a stray 33% over-allocation that's harmless today only because both branches get correctly resized on first real upload via the `capacity() < required` check. It's a live demonstration of the actual cost of copy-paste-and-diverge: the two "copies" are already lying to each other about a constant.

*Why it matters:* this isn't two unrelated classes with similar responsibilities — `CgGlyphAtlasPage` is what `CgGlyphAtlas` should have become when paging was added, and instead it was written next to it. A new engineer reading the atlas package has to hold both mental models simultaneously to know which one is live for their code path.

> **Fixed as part of A1's deletion.** `CgGlyphAtlas` no longer has any instance behavior — its constructor, `create()`/`createForTest()`, `getOrAllocate`/`getOrAllocateMsdf`, `evictAndInsert`, the duplicated GL-constant block, and both upload methods were all deleted along with `CgGlyphAtlasTest.java` (their only remaining caller). The class now holds only the `Type` enum (`BITMAP`/`MSDF`/`MTSDF`), kept under the same name/location specifically to avoid a repo-wide rename of every `CgGlyphAtlas.Type` reference in `CgGlyphAtlasPage`, `CgGlyphAtlas`, `CgFontRegistry`, and the msdf/cache packages. The duplicate GL constants and upload logic this finding flagged now exist in exactly one place: `CgGlyphAtlasPage`.

**A3. Two independent storage models with incompatible eviction semantics — HIGH — ✅ FIXED**
`CgGlyphAtlas` (legacy) does **LRU eviction**: `evictAndInsert` (`CgGlyphAtlas.java:454-480`) finds the coldest slot, calls `packer.remove()`, and overwrites that texture region on the next allocation. `CgGlyphAtlas`/`CgGlyphAtlasPage` (current) does **no eviction at all** — `tickFrame` is a literal no-op (`CgGlyphAtlas.java:323-325`, `"Reserved for future per-page maintenance (e.g., page GC)"`), and a full page just causes a new page to be allocated forever.

*Why it matters, concretely:* any UI text that continuously varies at fine sub-pixel granularity (a scrolling list, a smoothly moving HUD element) generates a new `(font, glyph, subPixelBucket 0-3)` combination on every distinct fractional pixel offset it passes through. With no eviction, a long play session with animated/scrolling text will monotonically grow atlas pages — this is a slow, real texture-memory leak in the *authoritative* path, not the deprecated one. The legacy path guards against exactly this failure mode and the replacement dropped the guard without a stated remediation plan beyond a comment.

> **Fixed.** `CgGlyphAtlas` now supports a page budget (`DEFAULT_MAX_PAGES = 32` for `createForPagedRegistry`, unbounded — `UNBOUNDED_PAGES` — for `createForTest`, preserving the existing `testNoEviction_pagingInstead` test). When a new page would exceed the budget, the **coldest whole page** is evicted (lowest `CgGlyphAtlasPage.getLastTouchedFrame()`, an O(1)-maintained per-page recency stamp) — not per-slot LRU, so every glyph on a surviving page keeps its placement-stability guarantee; a glyph is only ever displaced by losing its entire page. Page indices are now assigned from a monotonic counter (not `pages.size()`) so an evicted index is never reused by a live page. Covered by two new tests (`testPageBudget_evictsColdestPageOnOverflow`, `testPageBudget_unboundedByDefaultForTest`).

**A4. Renderer-owned material state is global/static, coupling every `CgTextRenderer` instance — MEDIUM — INTENTIONALLY NOT FIXED**
`CgTextRenderer.TEXT_MATERIAL`, `TEXT_DATA_UBO`, and `ATLAS_TEXTURE_REF` are all `static` (`CgTextRenderer.java:141-185`), shared across every renderer instance. `transitionToMaterial` explicitly toggles `MSDF_MODE` on the shared material every batch transition specifically *because* another live renderer could have left it in a different state (see the class's own javadoc: "a stale keyword left on by a previous transition ... would otherwise silently persist"). This is a documented, defended design choice, not an oversight — but it means every draw call anywhere in the process is implicitly serialized through one shared GPU-resource singleton, and `syncProjection`'s comment about "another live instance may have overwritten [TEXT_DATA_UBO] since" confirms the same is true of projection state. This is workable single-threaded (render thread only) but is a real constraint that will bite the moment anything tries to pipeline or parallelize draw submission.

> **Confirmed with the project owner: left as-is.** Not a bug — a deliberate, already-documented tradeoff. Fixing it means redesigning for parallel draw submission, which nothing in this project needs today or has concrete plans for.

### B. Correctness / Robustness

**B1. `CgMsdfGenerator`'s "do not call `shape.free()`" comment is a real landmine, correctly handled but fragile — MEDIUM — ✅ FIXED**
`CgMsdfGenerator.java:138-145` documents a genuine native-memory double-free race: `MSDFShape.free()` is *only* safe to be called by its own finalizer thread because the `freed` flag isn't `volatile`. The comment is honest and the code obeys the constraint (never calls `shape.free()`). But this means correctness for one of the hottest allocation paths in the whole pipeline (every uncached glyph, every frame) depends on Java's GC finalizer thread running before native heap pressure builds up, with no explicit backpressure mechanism tying MSDF shape allocation rate to finalizer throughput. This is the kind of thing that works fine in every test you'll ever run and then produces a heap-corruption crash under sustained load in production (many unique glyphs, e.g. a chat log with mixed scripts). Worth a tracked follow-up (e.g. an explicit shape pool) rather than relying on finalization.

> **Fixed at the root, not just tracked.** `MSDFShape` (and the structurally identical `MSDFBitmap`, `MSDFContour`, `MSDFSegment`, all in `freetype-msdfgen-harfbuzz-bindings` — code this repo owns, not a third-party library) switched their plain `boolean freed` field to an `AtomicBoolean`, and `free()` now calls the native free via `compareAndSet(false, true)` instead of a check-then-set. This makes `free()` idempotent and race-free no matter which thread calls it or how many times, closing the double-free hole the original comment worked around. With the race gone, `CgMsdfGenerator.preparePagedGlyph` (the live paged-path method; the legacy method this finding originally cited was removed in the A1 cleanup) and the harness's `MsdfVerificationTool` now explicitly `free()` the shape in a `finally` block on every exit path, restoring the class's own documented intended usage (`FreeTypeMSDFIntegration`'s class javadoc always showed explicit `shape.free()`) instead of leaving it to whenever GC gets around to finalization — directly closing the "no explicit backpressure" gap, not just documenting it as a risk.

**B2. Silent fallback chains hide generation failures — LOW/MEDIUM — ✅ FIXED**
`ensureMsdfGlyphPaged` falls back to bitmap when `msdfFont == null` or generation returns `null`; `queueOrGenerate` returns `null` on `MSDFException` after only `LOGGER.log(Level.FINE, ...)` (`CgMsdfGenerator.java:133-136`) — `FINE` is below default logging thresholds almost everywhere, so a systematically failing MSDF path (e.g. a corrupt font, an msdfgen native crash pattern) degrades to "text looks blurrier than expected" with no visible signal unless someone already knows to raise the log level. Given `CgMsdfGenerator`'s own javadoc flags a *known* native crash history in this exact call path, silently swallowing exceptions here at FINE is under-alarmed for a failure mode the authors already know is real.

> **Fixed.** Both MSDF failure log sites in `CgMsdfGenerator` (glyph load failure in `preparePagedGlyph`, shape validation failure in `prepareShapeForMsdf`) now log at `WARNING`, matching the bitmap rasterization path's severity for the same class of failure (see D2).

### C. Performance

**C1. Two `CgGlyphKey` allocations per visible glyph per frame — MEDIUM — ✅ FIXED**
`CgResolvedGlyphs.flattenAndPrequeue` (`CgResolvedGlyphs.java:126-129`) allocates one `new CgGlyphKey(...)` per glyph for the pre-queue pass; `ensurePlacements` (`CgResolvedGlyphs.java:150-158`) allocates a **second** `new CgGlyphKey(...)` per glyph for the actual lookup — same glyph, same frame, two immutable Lombok `@Value` objects (which also means two `hashCode()` computations over `(CgFontKey, int, boolean, int)`, and `CgFontKey.hashCode()` itself hashes over a `List<CgFontVariation>`). For a modest HUD (a few hundred visible glyphs), that's 500+ short-lived allocations and hash computations every single frame, done specifically to key into `HashMap`s (`CgGlyphAtlas.slotMap`, `CgGlyphAtlasPage.slotMap`) that are then walked **linearly across every atlas page** (`CgGlyphAtlas.get`, `CgGlyphAtlas.java:181-190`, hot page first but O(pages) worst case) on top of that.

This is exactly the class of thing the rest of `text/render` was clearly written to avoid — `CgResolvedGlyphs`'s own javadoc brags about the flatten pass allocating nothing in steady state via grow-only scratch arrays, and the sort-key packing in `CgTextRenderer` was explicitly built to avoid "a per-glyph key object." The glyph *cache lookup* key wasn't given the same treatment. A single mutable scratch `CgGlyphKey` (or a raw long/tuple hash used directly against the map) reused across the loop, or restructuring `slotMap` to avoid boxing a key per lookup, would close this gap and make the pipeline allocation-free end-to-end for the steady-state case it already optimizes for everywhere else.

> **Fixed.** `CgResolvedGlyphs` now has a `glyphKeys[]` scratch array (grown the same way as its other per-glyph arrays). `flattenAndPrequeue`'s prequeue loop stores the key it builds into `glyphKeys[i]` instead of discarding it. `ensurePlacements`'s primary pass (the one `resolvePlacements` always calls first, with the exact same `wantMsdf` value the prequeue pass used) reuses `glyphKeys[i]` directly instead of allocating a second key; only the rarer MSDF→bitmap fallback retry pass (where `wantMsdf` genuinely differs) still builds a fresh key, since it must. Halves per-glyph key allocation/hashing for the common case.

**C2. `MaxRectsPacker.insert` is O(freeRects) per insert with an O(n²) prune every insert — LOW — ✅ FIXED**
`splitFreeRects` + `pruneContained` (`MaxRectsPacker.java:213-275`) run on every single `insert`, and `pruneContained` is a nested loop over the current free-rect list. Free-rect count grows with fragmentation, not just packed-rect count, so this is superlinear in the number of glyphs packed into a page over that page's lifetime. At current page sizes (1024², glyphs tens of pixels) this stays small in practice — flagging as low severity, but it's the kind of algorithm that quietly gets worse if page size or glyph density assumptions change later, with no defensive cap.

> **Fixed the two concrete complaints, not the whole algorithm.** A full spatial-index rewrite was out of scope for a LOW-severity, "revisit if assumptions change" finding — instead: (1) `freeRects.remove(i)` (an O(n) `ArrayList` shift) is now a swap-with-last-and-pop O(1) removal in both `splitFreeRects` and `pruneContained`, safe because neither method's correctness depends on free-rect array order (verified — all 12 `MaxRectsPackerTest` cases still pass unchanged); this removes the extra O(n) factor the "O(n²) per prune with O(n) removals" complaint was really about. (2) The literal "no defensive cap" gap is closed: a `FREE_RECT_WARN_THRESHOLD` (2048) now logs once per packer instance if free-rect fragmentation grows unexpectedly large, so a future page-size/density change that breaks the current assumptions is visible instead of silently slow.

**C3. `drainCompletedGlyphs` / `MAX_COMMITS_PER_FRAME` is a flat constant, not adaptive — LOW — ✅ FIXED**
`CgFontRegistry.MAX_COMMITS_PER_FRAME = 32` (`CgFontRegistry.java:145`) is a fixed per-frame upload budget regardless of frame time headroom, atlas type, or upload size (an MSDF float upload is 3-4x the bytes of a bitmap upload for the same pixel dimensions, and this budget doesn't distinguish them). Fine as a v1 knob, but it's a magic number with no documented derivation and no telemetry hook to justify or retune it.

> **Fixed the atlas-type-blindness specifically.** Replaced the flat glyph-count budget with a byte-based one: `MAX_COMMIT_BYTES_PER_FRAME` (1 MiB) plus a `MAX_COMMIT_COUNT_PER_FRAME` (256) safety cap on GL call count. `estimateUploadBytes` computes each result's real upload cost from `CgGlyphGenerationResult.getAtlasType()` (1 byte/pixel bitmap, 12 bitmap/MSDF, 16 bytes/pixel MTSDF), so a frame with mostly-MSDF results now gets a proportionally smaller glyph count than a frame with mostly-bitmap results, instead of always committing exactly 32 regardless of type. Frame-time-adaptive budgeting (the "regardless of frame time headroom" half of the finding) is a larger change (needs a frame-timing signal threaded in) and was left out as a separate, bigger undertaking than this pass's scope.

### D. Code Quality / Consistency

**D1. `queueGlyphPagedPublic` is a pure pass-through wrapper that exists only to route around Java package-privacy — LOW — ✅ FIXED**
`CgFontRegistry.java:349-355` — a public method whose entire body is `queueGlyphPaged(font, key, effectiveTargetPx, subPixelBucket, currentFrame);`, added "so that code outside the cache package (e.g. the debug harness) can pre-queue glyphs." This is a symptom of the same root cause as A1/A2: production API surface growing to accommodate test/harness access patterns instead of the harness depending on the real internal API (module-level visibility, a test-only accessor, or just making `queueGlyphPaged` public outright, since `queueGlyphPagedPublic` proves there's no actual encapsulation benefit being preserved).

> **Fixed.** `queueGlyphPaged` was already `public` — the wrapper added zero encapsulation. Deleted the wrapper; its one caller (`AtlasDumpScene`) now calls `queueGlyphPaged` directly.

**D2. Inconsistent logging levels for structurally similar failures. — ✅ FIXED**
Rasterization failures in the paged bitmap path log at `WARNING` (`CgFontRegistry.java:523`, `:971`); MSDF generation failures in `CgMsdfGenerator` log at `FINE` (`CgMsdfGenerator.java:134`, `:323`). Both represent "we could not produce this glyph" — the severity split isn't argued for anywhere, it's just what each author happened to pick when they wrote that call site.

> **Fixed as part of B2** — both MSDF failure sites now log at `WARNING`, matching the bitmap path.

**D3. `§` section-comment banners in `CgFontRegistry` are a workaround for the file being too big to navigate, not a design.**
The file is genuinely well *organized* — the section banner comment scheme is a real, if unusual, effort to keep a large class legible. It carried three pipelines' worth of responsibility at 1387 lines when this was first written (A1); with the legacy paths now deleted (909 lines), the remaining sections map much more directly to one pipeline. The banner scheme itself is still worth revisiting if the file grows again, but the underlying cause (A1) is resolved.

### E. Missing capabilities vs. production text engines

Comparison peer group: this is a **game/UI text renderer**, not a desktop OS text stack — the fair
comparison set is Skia's glyph pipeline (Chrome/Android/Flutter), Unity TextMeshPro, Godot 4's
TextServerAdvanced, and Valve's Slug library, not DirectWrite/CoreText/Pango+ICU. Against that group,
the pipeline *shape* (shape → layout → cache → atlas → batch) is genuinely competitive, not behind —
see "What's actually done well" below for the specific things it does as well as or better than that
peer group. The gaps below are real capability ceilings, not implementation debt like A–D.

**E1. No shaped-layout cache — the pipeline always re-shapes from raw text — HIGH**
Grepped the whole `text/` tree for any cache keyed by `(text, font, constraints)`: there is none.
`CgTextRenderer.Draw.submit()` (`CgTextRenderer.java:719-761`, `drawInternal`) calls `layout(draw.text,
resolvedFont/Family, draw.constraints)` — the full HarfBuzz shape → BiDi segmentation → UAX #14 line
break → visual reorder pipeline — on **every single call** built from `.text(...)` rather than a
prebuilt `.layout(...)`. Production engines cache the shaped/laid-out result and only re-run shaping
when the string or font actually changes: Skia's `SkTextBlob`, Pango's layout cache, TextMeshPro's
`TMP_Text` component all do this. Right now a HUD label whose text is unchanged frame-to-frame pays
full shape+layout cost every single frame unless the *caller* manually builds a `CgTextLayout` once
and holds it via `Draw.layout(...)` — the fast path exists, but it's opt-in by the caller rather than
something the renderer does for you (e.g. an internal cache keyed by `(text, resolvedFontKey,
constraints)` with invalidation on any of those three changing). This is the single biggest
architectural gap versus the peer group — not implementation debt sitting on a good foundation
(A–D), but a genuinely missing capability. It also compounds directly with C1 (double `CgGlyphKey`
allocation per glyph per frame): today a static HUD label pays full re-shape *and* full
per-glyph-key-allocation cost every frame, when a shape cache alone would eliminate the former
entirely for the common case.

**E2. No color glyph support (COLR/CPAL, emoji bitmap strikes) — LOW (scope-dependent) — CONFIRMED OUT OF SCOPE**
The rasterization pipeline is grayscale-bitmap-or-distance-field only (`CgGlyphAtlas.Type` is
`BITMAP`/`MSDF`/`MTSDF` — no RGBA color-glyph channel anywhere in `text/cache`/`text/msdf`). Skia,
DirectWrite, and HarfBuzz+FreeType-with-`FT_LOAD_COLOR` stacks all support color/emoji glyphs. Likely
fine to leave out of scope given the Minecraft-adjacent UI context (vanilla MC doesn't render color
emoji either), but worth naming as a known ceiling rather than an oversight, in case a future feature
(custom emoji, colored icon fonts) needs it.

> Confirmed with the project owner: this is a new feature, not a defect, and not needed right now.

**E3. No justification or hyphenation — LOW (scope-dependent) — CONFIRMED OUT OF SCOPE**
`CgLineBreaker` stops at UAX #14 break opportunities (real `BreakIterator`-driven word/grapheme
boundaries — see "What's actually done well") with no soft-hyphen insertion and no justified-spacing
distribution across a line. Most engines in the actual peer group (TextMeshPro, Godot, Slug-based UIs)
skip this too, so it's a ceiling shared with peers, not a gap behind them — noted for completeness,
not urgency.

> Confirmed with the project owner: this is a new feature, not a defect, and not needed right now.

**E4. Shaping/layout is single-threaded, with no independent mitigation — MEDIUM**
Only *rasterization* is backgrounded (`CgGlyphGenerationExecutor`); BiDi/HarfBuzz/line-breaking all
run synchronously on whichever thread calls `layout()`. For short, interned game-UI strings this is
fine on its own. The real exposure is that this combines with E1: a long dynamic string laid out fresh
every frame (chat log, scrolling combat text, anything without a caller-managed `CgTextLayout` cache)
has no offload path *and* no caching — the two gaps compound into the one pathological case that
actually matters (a first-class production engine would close at least one of the two).

---

## What's actually done well (don't lose this in a rewrite)

- **`CgTextRenderer`'s sort-key packing** (`packSortKey`/`submitSortedQuads`, `CgTextRenderer.java:767-877`) — packing (mode, textureId, pxRange, glyphIndex) into one `long` and using `Arrays.sort(long[], ...)` instead of a comparator over key objects is exactly right for a per-frame hot path, and it's the *right example* to generalize C1 from.
- **`CgResolvedGlyphs`'s two-phase flatten/resolve split** with grow-only scratch arrays and a documented "never re-walk the layout tree" invariant — this is the correct shape for a per-frame text pipeline and is genuinely closer to how production engines (e.g. Skia's glyph run cache) structure this step.
- **The three-space model** (logical / physical raster / composite) documented on `CgTextRenderer` and actually respected by `logicalMetricScale` — this is a real, non-obvious correctness property (UI scale shouldn't corrupt kerning) and it's implemented consistently, not just asserted in a comment.
- **Placement stability in the paged atlas** (`CgGlyphAtlasPage` never moves a glyph once allocated) is the right tradeoff versus LRU churn for a page-based model, and is explicitly chosen over the old evicting model for exactly that reason.
- **The async generation executor** (`CgGlyphGenerationExecutor`) — bounded queue with `AbortPolicy`, `ConcurrentHashMap`-based in-flight/failed tracking to avoid duplicate submission, per-font job cancellation on dispose, daemon threads with `allowCoreThreadTimeOut` — is competently built concurrent code, not ad-hoc.
- **The MSDF shape-double-free comment** (B1) — even though the underlying constraint is fragile, the fact that it's documented in this much technical detail instead of silently worked around shows the team caught and understood a genuinely nasty native-interop bug.
- Package boundaries per the `AGENTS.md` files are largely *honored* in the code, not just asserted in docs — `text/atlas` really doesn't know about fonts/fallback, `text/msdf` really doesn't touch GL draw state, `text/layout` really has no atlas/cache/GL imports. That discipline is uncommon and worth preserving through any refactor.
- **Real UAX #14 line breaking, not naive whitespace wrapping.** `CgLineBreaker` uses actual `BreakIterator.getLineInstance` word-boundary classes with a binary-searched best-fit, and falls back to `BreakIterator.getCharacterInstance` grapheme-cluster breaking (never severing a combining sequence or surrogate pair) when a single token is wider than the line on its own. A lot of game engines in the actual peer group (TextMeshPro-tier, most in-house UI text) still do dumber width-accumulation-on-whitespace breaking; this is more correct than that baseline.
- **Hybrid bitmap/MSDF raster-tier switching with explicit hysteresis** (`CgTextRenderContext.RasterHistory`, `previousEffectiveTargetPx`/`wasMsdf`) — most SDF-based engines (TextMeshPro, most SDF UI systems) commit to one rendering strategy per asset and eat the tradeoff (small text slightly soft, or large/rotated text blurry/aliased). Explicitly tracking per-font draw history to damp thrashing at the size threshold is solving a real "text flickers between raster modes near the boundary" problem that a naive threshold switch would have, and that a lot of shipped engines don't bother solving at all.
- **SSBO/TBO-indexed instancing (`CgQuadRenderer`) instead of vertex-attribute-divisor instancing** — indexed by `gl_InstanceID` into a growable structured buffer specifically to avoid the ~16-GL-attribute-slot ceiling as more per-glyph style properties (outline, gradient, etc.) get added later. This is the same approach Slug and other modern SDF-text renderers use, and is a better long-term bet than the vertex-attribute path most simpler engines start with and have to rip out once they hit the slot ceiling.

Peer-group calibration: measured against Skia's glyph pipeline, TextMeshPro, Godot 4's TextServerAdvanced, and Slug (the fair comparison set for a game/UI text renderer, not a desktop OS text stack like DirectWrite/Pango+ICU), the pipeline *shape* — shape → layout → cache → atlas → batch, and the specific technical choices at each stage above — is genuinely competitive, not behind. The problems in this document are implementation/maintenance debt sitting on top of that foundation (A–D), plus one real capability gap (E1, missing shape cache) — not a sign the overall design needs rethinking. See finding **E** below for where actual capability gaps against that peer group exist.

---

## Ranked remediation list

1. ✅ **DONE** — ~~Delete the legacy/effective-size single-page atlas paths in `CgFontRegistry` and `CgGlyphAtlas`, once the debug harness is migrated to the paged path.~~ (A1, A2) Migrated `AtlasDumpScene`/`TextScene2D`/`CgFontDemo` to paged-equivalent finders, then deleted the full legacy rasterization block. `CgFontRegistry` went from 1387 → 909 lines.
2. ✅ **DONE** — ~~Give the paged atlas an eviction or page-budget policy~~ — `CgGlyphAtlas` now evicts the coldest whole page on overflow past a configurable budget (`DEFAULT_MAX_PAGES = 32` in production). (A3)
3. ✅ **DONE** — ~~Collapse `CgGlyphAtlas`/`CgGlyphAtlasPage`'s duplicated GL-constant and upload-buffer logic~~ — `CgGlyphAtlas` was stripped to just the shared `Type` enum; `CgGlyphAtlasPage` is now the only place the upload/GL-constant logic exists. (A2)
4. **Add a shaped-layout cache keyed by `(text, resolvedFontKey, constraints)`**, invalidated when any of those three change, so `Draw.submit()` built from raw `.text(...)` doesn't pay full HarfBuzz+BiDi+UAX#14 cost on every call for unchanged strings — the single biggest capability gap versus the peer group, not just implementation debt. (E1)
5. ✅ **DONE** — ~~Eliminate the double `CgGlyphKey` allocation per glyph per frame~~ in `CgResolvedGlyphs` — the primary resolve pass now reuses the key built during prequeue; only the rarer MSDF→bitmap fallback retry still allocates a second key. (C1)
6. ✅ **DONE** — ~~Raise MSDF generation failure logging from `FINE` to at least `WARNING`~~, matching the bitmap path — both MSDF failure sites now log at `WARNING`. (B2, D2)
7. ✅ **DONE** — ~~Remove `queueGlyphPagedPublic`~~ and just make `queueGlyphPaged` public — it already was; wrapper deleted, harness caller updated. (D1)
8. ✅ **DONE** — ~~Add a tracked follow-up for the `MSDFShape.free()` finalizer dependency~~ — fixed at the root instead of just tracked: `MSDFShape`/`MSDFBitmap`/`MSDFContour`/`MSDFSegment` (all in `freetype-msdfgen-harfbuzz-bindings`, code this repo owns) switched their racy `boolean freed` to an atomic, idempotent `free()`. The paged MSDF path and the harness verification tool now explicitly free shapes in a `finally` block instead of relying on GC finalizer timing. (B1)
9. ✅ **DONE** — ~~Lower priority: revisit `MAX_COMMITS_PER_FRAME`'s fixed budget (C3), `MaxRectsPacker`'s per-insert prune cost (C2)~~ — C3 is now byte-budgeted per atlas type; C2's O(n) removal cost is now O(1) plus a defensive free-rect-count warning. E2/E3 (color glyphs, justification/hyphenation) were explicitly left out of scope — confirmed with the project owner these are net-new features, not defects, and not needed right now.

**Remaining open items: only #4 (E1, the shaped-layout cache) — everything else in this list is done.** A4 (static/global `CgTextRenderer` material state) was explicitly left as-is: it's a deliberate, documented tradeoff for single-threaded rendering, not a bug, and nothing in this project parallelizes draw submission today.

Items 1–4 are the ones that will actually change how the codebase *feels* to work in and how it performs under real UI load — 1–3 are the direct cause of the "ad-hoc, stitched together" read (concentrated in `text/cache` + `text/atlas`), and 4 is the one place `text/render` itself has a real gap rather than just polish.

---

## TODO (scale-up): atlas storage → `GL_TEXTURE_2D_ARRAY`

**Decided direction, not yet started.** Glyph atlas pages should move from independent
`GL_TEXTURE_2D` textures (one GL texture id per `CgGlyphAtlasPage`) to a single
`GL_TEXTURE_2D_ARRAY` per atlas family (one for bitmap, one shared for MSDF/MTSDF), with each page
becoming a layer index instead of a separate texture. This directly attacks the batching cost A3/C1
gesture at: `CgTextRenderer.submitSortedQuads`'s sort key currently breaks batches on
`(mode, textureId, pxRange)`, and `textureId` is per-*page* today — text spanning N atlas pages costs
N draw calls purely because each page is its own GL texture, even when every glyph is otherwise
mode/format-identical. Collapsing all pages of a family into one array's layers turns that into a
fixed ~2-valued dimension (bitmap array vs. distance-field array), independent of page count.

Full investigation — GL storage model, growth strategy, format unification, `CgGlyphPlacement`/
`CgQuadRenderer`/shader changes, ownership inversion in `CgGlyphAtlasPage`/`CgGlyphAtlas`, harness
impact, and an ordered implementation sequence — is written up in
`docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md`, under **"Atlas texture array — investigated,
not started."** Key takeaways that bear directly on the remediation list above:

- **This makes fix #2 (give the paged atlas an eviction/budget policy) a hard prerequisite, not a
  nice-to-have.** A `GL_TEXTURE_2D_ARRAY`'s depth is fixed at allocation time — growing it means
  reallocating and copying every existing layer, unlike today's page list which just appends a cheap
  independent texture. Without some bound on live glyph/page count, the array either has to be
  sized speculatively large or will hit its layer cap and pay that reallocation cost routinely.
- **This makes fix #1 (delete the legacy single-page `CgGlyphAtlas`/`CgFontRegistry` paths) more
  urgent, not just cleaner.** The legacy evicting atlas has no page/layer concept at all — it's
  fundamentally incompatible with an array-backed model. Doing the array migration while the legacy
  path still exists means maintaining two increasingly divergent storage models simultaneously;
  retiring the legacy path first removes that duplication before it compounds further.
- The rectangle-packing algorithms (`text/atlas/packing/` — `MaxRectsPacker`, `CgGuillotinePacker`)
  need **zero changes** — they're already correctly ignorant of GL/texture concerns per their own
  `AGENTS.md`, and a bin is a bin whether it's backed by a standalone texture or an array layer. This
  is one of the places the existing package boundaries pay off exactly as designed.
- `sampler2DArray` is already a supported `CgMaterialProperty`/`.shader` property type
  (`CgMaterialProperty.Type.SAMPLER2D_ARRAY`) and sampler binding already dispatches generically
  through `CgTexture.bind(unit)` — this change would be the first real consumer of both, not new
  material-system plumbing.
- Adjacent, optional win once `CgQuadRenderer.INSTANCE_FORMAT`/`CgGlyphPlacement` are already being
  touched for the new `atlasLayer` field: promoting `_PxRange` from a per-batch material property to
  a per-instance field too would let one draw call span different pxRange configs as well, collapsing
  the sort key down to just `mode`. Worth deciding explicitly rather than doing by default.

---

## TODO (scale-up): inline rich-text formatting (bold/italic/underline/color spans)

**Decided direction, not yet started.** Let a single logical string carry mixed inline formatting —
`<b>bold</b>`, italic, inline color — authored via a small markup syntax, flowing through the
existing shape → layout → cache → render pipeline. Full design — the new `CgStyledText`/`CgStyleSpan`
IR, a `CgFontFamilyGroup` type to resolve style→font-face (the one genuinely new piece of API
surface this needs), exactly where BiDi-run splitting needs to intersect with style-span boundaries
in `CgTextLayoutEngine`, the `CgLineBreaker` per-line-metrics fix mixed-weight lines need, and the
render-side color plumbing — is written up in full in
`docs_research/CGTEXT_INLINE_RICHTEXT_FOUNDATIONS.md`. Key takeaways:

- **Explicitly scoped to inline formatting only.** Block-level markup (`<h1>`–`<h6>` margins, `<p>`
  spacing, `<li>` bullets/indentation) is deliberately out of scope — those are box-layout concerns
  that belong to CrystalGUI's `UIElement`/Taffy tree, not this text-shaping pipeline, matching how a
  browser separates inline layout from block box layout. A markup→`UIElement`-tree translator for
  block tags is a separate, later piece of work, not bundled with this.
- **The atlas/cache/MSDF layers need zero changes.** Bold/italic are just different `CgFontKey`s
  (`CgFontStyle` already has all four weight/style variants) — the glyph cache already keys on
  `CgFontKey` correctly. This is purely a `text/layout` + `text/render` change.
- **Per-glyph color is already a supported GPU capability, not a gap.** `CgQuadRenderer.INSTANCE_FORMAT`
  already carries a per-instance `vec4 color`, and `CgTextRenderer.submitSortedQuads` already submits
  color per glyph — the only real gap is that the CPU side currently reads one uniform `rgba` for the
  whole `Draw` instead of per-glyph. Small, contained plumbing fix once `CgShapedRun` carries an
  optional color field.
- **The layout-engine change is smaller than it first looks.** The abstract `collectShapedRuns` hook
  (`CgTextLayoutEngine`/`CgTextLayoutBuilder`'s bridge) needs **no signature change** — style
  resolution happens one layer up, in the engine's existing BiDi-run-splitting loop, which just needs
  to also split on style-span boundaries before calling the same hook it already calls today.
- Ties directly into **E1 (no shaped-layout cache)** from the findings above: once text can carry
  rich formatting, a cache keyed on `(text, resolvedFontKey, constraints)` needs to also key on the
  style-span list (or on the parsed `CgStyledText` itself) — worth designing E1's cache key with this
  in mind so it doesn't need re-keying later.
- A markup parser is intentionally a **separate, pluggable** piece (`CgMarkupParser` interface) with
  an open decision on default syntax — HTML-like tags vs. Minecraft's own `§`-formatting-code
  convention, which every player/mod-author already knows and is trivially unambiguous to parse. Not
  resolved in the design doc on purpose; deferred to whoever picks this up.
