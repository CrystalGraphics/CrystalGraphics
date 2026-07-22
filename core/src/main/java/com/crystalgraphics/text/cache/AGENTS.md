# text/cache — Agent Guide

## Package role

This package owns the **glyph supply side** of the text pipeline.

Its job is to take a public glyph request and answer with atlas-resident placement data, generating or rasterizing the glyph if needed.

## Reading order

1. `CgFontRegistry`
2. `CgRasterFontKey`
3. `CgRasterGlyphKey`
4. `CgMsdfAtlasKey`
5. `CgGlyphGenerationExecutor`
6. `CgGlyphGenerationJob`
7. `CgGlyphGenerationResult`
8. `CgWorkerFontContext`

## Class-by-class details

### `CgFontRegistry`

The central cache hub and the most important file in this package.

Main responsibilities:

- maintain atlas maps
- maintain paged atlas families
- transform public glyph requests into internal cache keys
- resolve paged glyph placements
- submit async generation work
- drain/commit async results
- keep legacy single-page compatibility paths alive
- own font-disposal → atlas-cleanup wiring

The class is intentionally organized in pipeline order. Read it top-to-bottom.

**Singleton, like every other GPU-resource registry.** `CgFontRegistry.get()` returns
the shared default-config instance — matching `CgTextureManager`, `CgMaterialRegistry`,
`CgMeshRegistry`, and every other GPU-resource registry in this codebase. The no-arg
constructor is private; only `get()` and the two public `(atlasSize[, config])`
constructors remain. `CgGraphicsLifecycle.destroyContext()` calls
`CgFontRegistry.get().releaseAll()` to tear it down, exactly like
`CgTextureManager.get().freeAll()`/`CgMaterialRegistry.get().deleteAll()`.

The `(int atlasSize)`/`(int atlasSize, CgMsdfAtlasConfig config)` constructors stay
public and are **not** dead API — `gl-debug-harness`'s `TextScene2D`/`AtlasDumpScene`/
`WorldTextRenderHelper` deliberately construct their own non-singleton instances via
these to test atlas packing at specific page sizes/configs. Only the parameterless
default-config path is a singleton; do not make the parameterized constructors private
or route them through `get()` — that would break real, intentional test usage.

`releaseAll()` must leave the shared instance immediately reusable, not permanently
dead — `CgGraphicsLifecycle` supports initializing a new GL context right after
`destroyContext()` returns. `CgGlyphGenerationExecutor.shutdown()` is irreversible, so
`releaseAll()` replaces the (non-final) `glyphGenerationExecutor` field with a fresh
instance at the end, rather than leaving the shared singleton stuck with a dead
executor forever after the first context teardown.

**Resolved**: `CgTextRenderer`'s draw path (`Draw.submit()` — see `text/render/AGENTS.md`,
there is no fixed-arity `draw(...)` method anymore) never takes a `frame` parameter — it
reads `CgGraphicsLifecycle.getCurrentFrame()` internally, the single authoritative
per-real-frame counter incremented by `CgGraphicsLifecycle.tickFrame()` (wired via
`CgLifecycleService.onFrameRendered()`). Every production draw call site now stamps
glyphs from that same shared clock. `tickFrame(long frame)` itself is still a public,
un-debounced entry point — harness scenes that need a synthetic, faster-than-real-time
clock to force MSDF convergence before a screenshot (`AtlasDumpScene`, `TextScene2D`,
`WorldTextRenderHelper`) still call it directly with their own frame numbers, independent
of the real per-frame counter. That is intentional, not a bug: those loops need many
synthetic ticks within a single real frame, which the debounced
`CgGraphicsLifecycle.tickFrame()` path cannot provide.

### `CgRasterFontKey`

Internal cache key describing a base font at one effective raster size.

This type exists because the same logical font may need separate atlas buckets at different effective physical sizes.

### `CgRasterGlyphKey`

Internal glyph request key layered on top of `CgRasterFontKey`.

Adds:

- glyph id
- distance-field intent
- sub-pixel bucket

### `CgMsdfAtlasKey`

Internal key describing a shared MSDF/MTSDF atlas family and its generation config.

Unlike bitmap raster keys, this key intentionally does not track every immediate effective draw size; it points at a shared distance-field family.

### `CgGlyphGenerationExecutor`

Background thread-pool owner for glyph-generation jobs.

### `CgGlyphGenerationJob`

Async work-item definition.

Encodes everything a worker needs to rasterize or generate one glyph independently.

### `CgGlyphGenerationResult`

Completed async payload ready for render-thread atlas commit.

### `CgWorkerFontContext`

Per-worker font state holder used so async glyph generation does not trample render-thread font objects.

### `package-info.java`

Package-level summary of cache/generation ownership.

## Internal flow summary

The authoritative path is:

1. renderer requests glyph placement from `CgFontRegistry`
2. registry transforms the public glyph key into the relevant internal cache key
3. registry looks in the paged atlas family
4. on hit, return placement
5. on miss, rasterize/generate synchronously or queue async work
6. commit results into atlas storage
7. renderer consumes resulting `CgGlyphPlacement`

## Key invariants

- this package owns glyph supply, not final drawing
- package-private helper/key types are intentionally close to `CgFontRegistry`
- if these helpers move, their owner probably needs to move too
- atlas storage is delegated to `text/atlas`
- MSDF generation math is delegated to `text/msdf`

## Common agent mistakes to avoid

- Do not split internal key/generation helpers away from `CgFontRegistry` in tiny moves.
- Do not let renderer batch/shader concerns creep into this package.
- Keep `CgFontRegistry` readable in pipeline order; it is still the main complexity hub here.
- Do not construct `CgFontRegistry` directly (`new CgFontRegistry()`) for production/game
  code — use `CgFontRegistry.get()`. The parameterized constructors are reserved for
  harness testing of custom atlas sizes/configs.
- Do not call `.releaseAll()` on the shared `get()` instance from an individual consumer's
  dispose/delete method — that would tear down the registry for every other consumer
  still using it. Only `CgGraphicsLifecycle.destroyContext()` should call it on the
  singleton. (Non-singleton test instances constructed via the parameterized
  constructors are fine to release themselves — they're not shared.)
- Do not make `glyphGenerationExecutor` `final` again — `releaseAll()` needs to replace
  it with a fresh instance after `shutdown()`, since the shared singleton must survive
  a GL context destroy/recreate cycle.
