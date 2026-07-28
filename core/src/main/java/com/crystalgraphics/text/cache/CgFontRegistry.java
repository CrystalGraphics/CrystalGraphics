package com.crystalgraphics.text.cache;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import com.crystalgraphics.text.msdf.CgMsdfGenerator;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.util.profiling.CgProfiler;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-thread glyph cache &mdash; the central hub for atlas allocation,
 * glyph lookup, and generation scheduling.
 *
 * <h3>Pipeline Role</h3>
 * <p>{@code CgFontRegistry} is the <em>supply side</em> of the text rendering
 * pipeline.  The renderer ({@link CgTextRenderer})
 * calls into the registry to convert a {@link CgGlyphKey} into an atlas-resident
 * {@link CgGlyphPlacement}. The registry owns atlas textures, generation
 * scheduling, and frame-tick bookkeeping; the renderer only consumes the
 * placement data and texture IDs.</p>
 *
 * <h3>Reading Order</h3>
 * <p>This class is organized in pipeline order.  A reader tracing the glyph
 * resolution path should read top-to-bottom through these sections:</p>
 * <ol>
 *   <li><strong>Construction &amp; lifecycle</strong> &mdash; registry creation, config, disposal</li>
 *   <li><strong>Frame tick &amp; async drain</strong> &mdash; per-frame budget reset and completed-glyph upload</li>
 *   <li><strong>Authoritative  glyph path</strong> &mdash; {@link #ensureGlyph} and its pre-queue helper,
 *       the main entry point for the multi-page atlas system</li>
 *   <li><strong>Key transformation helpers</strong> &mdash; methods that convert a caller-visible
 *       {@link CgGlyphKey} into the internal atlas/cache key used for lookup</li>
 *   <li><strong> bitmap rasterization</strong> &mdash; FreeType bitmap path for  atlases</li>
 *   <li><strong> MSDF generation</strong> &mdash; MSDF path for  atlases (with bitmap fallback)</li>
 *   <li><strong>Async job submission</strong> &mdash; submitting bitmap/MSDF jobs to the background executor</li>
 *   <li><strong>Async commit</strong> &mdash; draining completed results and uploading into atlas pages</li>
 *   <li><strong>Atlas inspection / enumeration</strong> &mdash; diagnostic utilities for the debug harness</li>
 *   <li><strong>Font registration &amp; atlas cleanup</strong> &mdash; dispose listener wiring and release helpers</li>
 *   <li><strong>Low-level utilities</strong> &mdash; bitmap buffer normalization, font state restore</li>
 * </ol>
 *
 * <h3>Physical Raster Space Only</h3>
 * <p>All glyph metrics stored in atlas placements ({@link CgGlyphPlacement}) are in
 * <strong>physical raster space</strong> &mdash; bearings, widths, and heights are
 * captured at the effective raster size, not in logical layout units.  The
 * registry must never normalize these values into logical space; that
 * responsibility belongs exclusively to the renderer boundary
 * ({@code CgTextRenderer.appendQuads}).</p>
 *
 * <h3>Effective-Size-Aware Lookup</h3>
 * <p>When text is rendered under a PoseStack transform, the effective physical
 * raster size may differ from the base {@code CgFontKey.targetPx}.  The registry
 * supports this via {@link CgRasterFontKey}-keyed  atlas maps that allow the
 * same logical font to have multiple atlas buckets at different raster sizes.</p>
 *
 * <p>The registry also wires font disposal to atlas cleanup.  It is not thread
 * safe and must only be used on the render thread.</p>
 *
 * <h3>Singleton, like every other GPU-resource registry</h3>
 * <p>The default-config registry is a shared singleton, accessed via {@link #get()} —
 * matching {@code CgTextureManager}, {@code CgMaterialRegistry}, {@code CgMeshRegistry},
 * and every other GPU-resource-owning registry in this codebase. It is torn down by
 * {@code CgGraphicsLifecycle.destroyContext()} calling {@link #releaseAll()}, and
 * remains usable immediately afterward (a fresh GL context can initialize right away —
 * see {@link #releaseAll()}'s javadoc for how the background executor survives that).
 * Consumers needing a non-default atlas page size or {@link CgMsdfAtlasConfig} (e.g.
 * harness scenes testing atlas packing at specific sizes) still construct their own
 * instance via {@link #CgFontRegistry(int, CgMsdfAtlasConfig)} — only the
 * default-config path is a singleton.</p>
 *
 * <p><strong>Resolved:</strong> {@link #tickFrame(long)} still takes a caller-supplied
 * frame number, but {@code CgTextRenderer.draw(...)} no longer exposes a {@code frame}
 * parameter — it reads {@code CgGraphicsLifecycle.getCurrentFrame()} internally, the
 * single authoritative per-real-frame counter incremented by
 * {@code CgGraphicsLifecycle.tickFrame()} (see that class's javadoc). All production
 * draw call sites therefore stamp glyphs from the same clock. {@link #tickFrame(long)}
 * itself remains a public entry point for harness code that intentionally drives a
 * synthetic, faster-than-real-time clock to force MSDF convergence before a screenshot
 * capture (e.g. {@code AtlasDumpScene}, {@code TextScene2D}, {@code WorldTextRenderHelper}).</p>
 *
 * @see CgRasterFontKey
 * @see CgMsdfAtlasKey
 * @see CgGlyphGenerationExecutor
 */
public class CgFontRegistry {

    private static final Logger LOGGER = Logger.getLogger(CgFontRegistry.class.getName());
    private static final int DEFAULT_BITMAP_ATLAS_SIZE = 1024;

    // ────────────────────────────────────────────────────────────────────
    //  § 1. Construction & lifecycle

    //  atlas maps — the active/authoritative path for new allocations.
    // Two global atlases, one per texture format.
    //
    // Every font shares these. There is exactly one atlas per tier because a CgTexture2DArray has
    // a single internal format across all its layers and the two tiers need different ones (R8 for
    // bitmap coverage, RGBA8 for distance fields). That format split is the ONLY reason there are
    // two atlases rather than one.
    //
    // These used to be keyed per font (CgRasterFontKey / CgMsdfAtlasKey), giving every font its own
    // texture array. A UI font with forty glyphs then owned an entire page and filled a fraction of
    // it, and mixed-font text cost a texture rebind (hence a batch flush) per font. Sharing fixes
    // both: pages fill densely no matter how few glyphs any one font contributes, and a run
    // spanning several faces batches as one.
    //
    // Glyph identity still carries the font -- CgGlyphKey holds the full CgFontKey -- so glyphs
    // from different faces cannot collide inside a shared page.
    //
    // Deliberate consequence: MSDF config is now necessarily registry-wide. One atlas cannot hold
    // two atlas scales, so resolveMsdfAtlasConfig is a registry-level setting and no longer a
    // per-font extension point.
    private CgGlyphAtlas BITMAP_ATLAS, MSDF_ATLAS;

    private final Set<CgFontKey> registeredFonts = new HashSet<>();
    
    private final CgMsdfGenerator msdfGenerator = new CgMsdfGenerator();
    // Not final — releaseAll() replaces this with a fresh instance so the shared
    // singleton stays usable after a GL context is destroyed and recreated (see
    // CgGraphicsLifecycle). CgGlyphGenerationExecutor.shutdown() is permanent —
    // a fresh instance is the only way back to a submittable state.
    private CgGlyphGenerationExecutor glyphGenerationExecutor = new CgGlyphGenerationExecutor();

    // ────────────────────────────────────────────────────────────────────

    private final int atlasSize;
    private final CgMsdfAtlasConfig msdfAtlasConfig;

    /**
     * Per-frame async-commit upload budget, in estimated pixel-data bytes rather
     * than a flat glyph count — an MSDF/MTSDF float upload is 3-4x the bytes of
     * a bitmap upload for the same pixel dimensions (see {@link #estimateUploadBytes}),
     * so a count-based budget under- or over-commits depending on glyph mix.
     * ~1 MiB/frame is roughly the old 32-bitmap-glyph budget at a typical ~64x64
     * cell size (32 * 64*64 = 131072 B), sized up to also give MSDF/MTSDF results
     * reasonable per-frame throughput at the same glyph count.
     */
    private static final long MAX_COMMIT_BYTES_PER_FRAME = 1024L * 1024L;

    /**
     * Hard cap on the number of individual GL upload calls per frame tick,
     * independent of the byte budget above — bounds per-frame driver-call
     * overhead even for a queue of many tiny glyphs (e.g. punctuation) that
     * would otherwise pass the byte budget for a very long time.
     */
    private static final int MAX_COMMIT_COUNT_PER_FRAME = 256;

    /**
     * Wall-clock ceiling for one frame's async-commit drain, in nanoseconds.
     *
     * <p>The byte and count budgets above bound <em>how much</em> is uploaded, but not <em>how
     * long</em> it takes, and those turn out to be only loosely related: measured GL upload
     * throughput for the same ~1 MiB of glyph data varied from 613 MB/s down to 15 MB/s
     * — a 40x spread — depending on whether the driver stalled writing into a texture the
     * previous frame was still sampling. At the low end, a drain that respected every existing
     * budget still blew 30-65 ms, producing exactly the kind of isolated hitch the budgets
     * were introduced to prevent.
     *
     * <p>A time budget bounds the thing that actually matters. It is checked between commits
     * (a single commit is never interrupted), so an unusually expensive individual upload can
     * still overshoot slightly; the remainder simply stays queued for the next frame, which is
     * already the normal steady state during warmup.</p>
     */
    private static final long MAX_COMMIT_NANOS_PER_FRAME = 2_000_000L; // 2ms

    /**
     * The shared default-config registry, matching every other GPU-resource registry
     * in this codebase ({@code CgTextureManager}, {@code CgMaterialRegistry},
     * {@code CgMeshRegistry}, etc.) — accessed via {@link #get()}, torn down via
     * {@code CgGraphicsLifecycle.destroyContext()} calling {@link #releaseAll()}.
     *
     * <p>Consumers that need a <em>differently configured</em> registry (custom atlas
     * page size or {@link CgMsdfAtlasConfig} — used by harness scenes to test atlas
     * packing at specific sizes) should still construct their own instance via
     * {@link #CgFontRegistry(int, CgMsdfAtlasConfig)}; that constructor stays public
     * for exactly that purpose. Only the default-config path is a singleton.</p>
     */
    private static final CgFontRegistry INSTANCE = new CgFontRegistry();

    /** Returns the shared default-config font registry. See {@link #INSTANCE}. */
    public static CgFontRegistry get() {
        return INSTANCE;
    }

    private CgFontRegistry() {
        this(DEFAULT_BITMAP_ATLAS_SIZE, CgMsdfAtlasConfig.defaultConfig());
    }

    public CgFontRegistry(int atlasSize) {
        this(atlasSize, CgMsdfAtlasConfig.defaultConfig());
    }

    public CgFontRegistry(int atlasSize, CgMsdfAtlasConfig msdfAtlasConfig) {
        this.atlasSize = atlasSize;
        if (msdfAtlasConfig == null) {
            throw new IllegalArgumentException("msdfAtlasConfig must not be null");
        }
        this.msdfAtlasConfig = msdfAtlasConfig;
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 2. Frame tick & async drain
    //
    //  Called once per render frame.  Resets the MSDF per-frame generation
    //  budget, drains completed async glyph results from the executor, and
    //  ticks every atlas's LRU / eviction clock.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Advances all atlas clocks, resets the per-frame MSDF budget, and
     * uploads up to {@value #MAX_COMMIT_BYTES_PER_FRAME} bytes (capped at
     * {@value #MAX_COMMIT_COUNT_PER_FRAME} individual uploads) of completed
     * async glyph results into their target atlases.
     *
     * <p>Must be called exactly once per render frame, before any
     * {@code ensureGlyph*} or {@code queueGlyph*} calls for that frame.</p>
     */
    public void tickFrame(long frame) {
        // NOTE (profiling): this runs from CgGraphicsLifecycle.onFrameRendered(), which the
        // harness calls AFTER scene.render() -- i.e. after TextScene3D's CgProfiler.endFrame().
        // These scopes therefore land in the NEXT frame's report, one frame late. Magnitude is
        // still accurate; only the frame attribution is shifted by one.
        try (CgProfiler.Scope ignored = CgProfiler.scope("registry.tickFrame")) {
            // 1. Drain completed async results first so they are available
            //    to ensureGlyph* calls later in the same frame.
            try (CgProfiler.Scope drain = CgProfiler.scope("drainCompletedGlyphs")) {
                    drainCompletedGlyphs(frame, MAX_COMMIT_BYTES_PER_FRAME, MAX_COMMIT_COUNT_PER_FRAME, MAX_COMMIT_NANOS_PER_FRAME);
            }

            // 2. Tick every  atlas family.
            try (CgProfiler.Scope atlasTick = CgProfiler.scope("atlasTick")) {
                for (CgGlyphAtlas atlas : liveAtlases())
                    atlas.tickFrame(frame);
            }

            // 3. Reset the MSDF generator's per-frame budget counter.
            msdfGenerator.tickFrame();
        }
    }

    /**
     * Blocks until all pending async glyph jobs have completed, or until
     * {@code timeoutMs} elapses.
     *
     * @return {@code true} if the executor reached idle before the timeout
     */
    public boolean awaitAsyncGlyphs(long timeoutMs) {
        return glyphGenerationExecutor.awaitIdle(timeoutMs);
    }

    /** Returns the number of glyph generation jobs currently in-flight. */
    public int getPendingAsyncGlyphCount() {
        return glyphGenerationExecutor.getPendingJobCount();
    }

    /**
     * Sum of every live atlas's {@link CgGlyphAtlas#getContentGeneration()} — bumped
     * whenever <em>any</em> atlas gains a glyph (or records one as empty).
     *
     * <p>This is the invalidation signal for {@code CgGlyphPlacementCache}: a cached resolve
     * that had to fall back to bitmap for some glyphs is potentially stale exactly when new
     * content lands (its MSDF upgrade may have just become available), and is <em>not</em>
     * stale at any other time. It replaces that cache's former {@code REFRESH_FRAMES} timer,
     * which polled on a fixed 300-frame cadence — simultaneously too slow to pick an upgrade
     * up promptly (up to ~5s of stale bitmap-tier text) and expensive enough to cost a full
     * re-resolve of the entire layout every 2.5s forever, even once fully converged.</p>
     *
     * <p>Summing is deliberate and safe: the counters are monotonic, so the sum is monotonic,
     * and any individual bump changes it. It is coarse — a change to an unrelated font's atlas
     * also invalidates — but only ever causes a redundant re-resolve, never a missed one, and
     * it stays completely still once every atlas in play has converged, which is the case that
     * actually matters for steady-state cost.</p>
     *
     * @see #getAtlasEvictionGeneration()
     */
    public long getAtlasContentGeneration() {
        long sum = 0;
        for (CgGlyphAtlas atlas : liveAtlases()) sum += atlas.getContentGeneration();
        return sum;
    }

    /**
     * Sum of every live atlas's {@link CgGlyphAtlas#getEvictionGeneration()} — bumped
     * only when a page is actually evicted, which invalidates <em>every</em> placement that
     * referenced it (the freed layer index is immediately reused by a different page).
     *
     * <p>Separate from {@link #getAtlasContentGeneration()} because the two invalidate
     * different populations: new content only concerns a consumer still holding bitmap
     * placements it would like upgraded, whereas an eviction concerns everyone, including a
     * consumer whose placements are already uniformly distance-field. On the default unbounded
     * atlases nothing is ever evicted, so this stays {@code 0} for the process lifetime and
     * fully-converged cache entries need no revalidation at all.</p>
     */
    public long getAtlasEvictionGeneration() {
        long sum = 0;
        for (CgGlyphAtlas atlas : liveAtlases()) sum += atlas.getEvictionGeneration();
        return sum;
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 3. Authoritative  glyph path
    //
    //  This is the PRIMARY entry point for the multi-page atlas system.
    //  The renderer calls ensureGlyph() to obtain a CgGlyphPlacement
    //  for each visible glyph; queueGlyph() pre-queues glyphs that
    //  are likely to be needed (reducing frame spikes).
    //
    //  Pipeline:
    //    CgGlyphKey → key transformation →  atlas lookup →
    //    [cache hit: return placement] →
    //    [cache miss: rasterize/generate → allocate into atlas → return placement]
    // ────────────────────────────────────────────────────────────────────

    /**
     * Ensures a glyph is available in the  atlas and returns its
     * {@link CgGlyphPlacement}.
     *
     * <p>This is the authoritative path for the multi-page atlas system.
     * MSDF glyphs are generated via {@link CgMsdfGenerator} with upstream-parity
     * layout; bitmap glyphs are rasterized via FreeType.  Falls back from MSDF
     * to bitmap when MSDF generation is skipped or unavailable.</p>
     *
     * <h4>Key transformation</h4>
     * <p>The caller-visible {@link CgGlyphKey} is transformed into an internal
     * atlas key before lookup.  For MSDF, the font key is rewritten to the
     * atlas-scale size ({@code CgMsdfAtlasConfig.atlasScalePx}) because all MSDF
     * glyphs for a font share one atlas regardless of requested render size.
     * For bitmap, the key is rewritten to embed the effective raster size and
     * sub-pixel bucket.  See § 4 (key transformations) for details.</p>
     *
     * <h4>Synchronous — unlike {@link #resolveGlyph}</h4>
     * <p>This is the "make the glyph exist now" entry point: it generates an MSDF inline
     * (within {@code CgMsdfGenerator.MAX_PER_FRAME}) rather than settling for the bitmap
     * fallback and waiting on the async executor. That inline generation costs ~30 ms per
     * glyph, which is exactly why the per-frame render path deliberately does <em>not</em> come
     * through here — see {@link #resolveGlyph}. Use this for deterministic, non-realtime
     * convergence (atlas dumps, prewarm/parity tooling, tests), where a stall does not matter
     * and "the glyph is definitely present when this returns" is the property that does.</p>
     */
    public CgGlyphPlacement ensureGlyph(CgFont font,
                                      CgGlyphKey key,
                                      int effectiveTargetPx,
                                      int subPixelBucket,
                                      long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot ensureGlyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        CgRasterFontKey rasterFontKey = new CgRasterFontKey(key.getFontKey(), effectiveTargetPx);

        if (key.isMsdf()) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(key.getFontKey());
            CgMsdfAtlasKey msdfAtlasKey = toMsdfAtlasKey(key.getFontKey(), config);
            CgGlyphKey atlasKey = toMsdfAtlasGlyphKey(key, config);
            return ensureMsdfGlyph(font, atlasKey, msdfAtlasKey, effectiveTargetPx, subPixelBucket, currentFrame, true);
        } else {
            CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                    new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket,
                            key.isSyntheticBold(), key.isSyntheticItalic()));
            return ensureBitmapGlyph(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket, currentFrame);
        }
    }

    /**
     * Combined replacement for calling {@link #queueGlyph} and {@link #ensureGlyph}
     * separately for the same glyph in the same frame — which is exactly what
     * {@code CgResolvedGlyphs} used to do, and which meant every visible glyph paid for the
     * atlas-key transformation (font-key rewrite, MSDF config resolution, a fresh
     * {@link CgGlyphKey}/{@link CgMsdfAtlasKey} allocation) <strong>twice</strong> per frame —
     * once in each call — even though both calls need the exact same transformed key. That
     * duplication kept per-frame cost high even after {@code CgGlyphAtlas.get()} became
     * {@code O(1)}: the win from a cheap lookup was being paid right back by doing the (much
     * more expensive) key transformation and allocation twice instead of once.
     *
     * <p>Transforms the key exactly once, checks the atlas exactly once. On a cache hit (the
     * common, steady-state case) returns immediately — no job submission, no generation
     * attempt, just the one transform and the one {@code O(1)} lookup. On a miss, preserves
     * the exact prior combined behavior: submits an async job (what {@link #queueGlyph}
     * used to do) and attempts synchronous generation within the small per-frame budget (what
     * {@link #ensureGlyph} used to do), including the MSDF→bitmap fallback.</p>
     *
     * <h4>Never generates MSDF synchronously — that is {@link #ensureGlyph}'s job</h4>
     * <p>On an MSDF miss this submits the async job and then takes the bitmap fallback for
     * <em>this</em> frame, rather than blocking to generate the distance field inline. The
     * inline path costs ~30 ms per glyph and is capped at
     * {@code CgMsdfGenerator.MAX_PER_FRAME} (4), so a frame that has to touch it spends ~120 ms
     * regardless of how few glyphs actually needed it — measured as the entire remaining cost
     * of a warmup refresh once redundant lookups were eliminated. The background executor
     * produces exactly the same glyphs off the render thread and is what converges the atlas in
     * practice anyway; paying for four of them inline only buys those four glyphs a slightly
     * earlier upgrade, at the price of a visible hitch.
     *
     * <p>The bitmap fallback makes this safe: a glyph awaiting its distance field still renders
     * this frame, just on the bitmap tier, and upgrades on a later frame once the async result
     * lands (mixed tiers within one draw are fine — see
     * {@code CgResolvedGlyphs#resolvePlacements}).</p>
     *
     * @return the placement if cached, or a bitmap fallback placement; {@code null} only if the
     *         glyph could not be resolved on either tier
     */
    public CgGlyphPlacement resolveGlyph(CgFont font,
                                      CgGlyphKey key,
                                      int effectiveTargetPx,
                                      int subPixelBucket,
                                      long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot resolve glyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        CgRasterFontKey rasterFontKey = new CgRasterFontKey(key.getFontKey(), effectiveTargetPx);

        if (key.isMsdf()) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(key.getFontKey());
            CgMsdfAtlasKey msdfAtlasKey = toMsdfAtlasKey(key.getFontKey(), config);
            CgGlyphKey atlasKey = toMsdfAtlasGlyphKey(key, config);
            CgGlyphAtlas atlas = getMsdfAtlas(msdfAtlasKey.getConfig());

            CgGlyphPlacement cached = atlas.get(atlasKey, currentFrame);
            if (cached != null) {
                return cached;
            }
            submitMsdfGlyphJob(font, atlasKey, msdfAtlasKey);
            return ensureMsdfGlyph(font, atlasKey, msdfAtlasKey, effectiveTargetPx, subPixelBucket, currentFrame,
                    false);
        }

        CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket,
                        key.isSyntheticBold(), key.isSyntheticItalic()));
        CgGlyphAtlas atlas = getBitmapAtlas();

        CgGlyphPlacement cached = atlas.get(atlasKey, currentFrame);
        if (cached != null) {
            return cached;
        }
        submitBitmapGlyphJob(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket);
        return ensureBitmapGlyph(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket, currentFrame);
    }

    /**
     * Pre-queues a glyph for async generation if it is not already in the
     *  atlas.
     *
     * <p>The renderer calls this during the pre-queue pass
     * ({@code CgResolvedGlyphs.flattenAndPrequeue}) to submit glyph generation jobs to the
     * background executor <em>before</em> the synchronous {@code ensureGlyph}
     * calls.  This reduces frame spikes by spreading generation work across
     * multiple frames.</p>
     *
     * <p>If the glyph is already cached in the  atlas, this is a no-op.
     * Otherwise a background job is submitted via
     * {@link CgGlyphGenerationExecutor}.</p>
     */
    public void queueGlyph(CgFont font,
                        CgGlyphKey key,
                        int effectiveTargetPx,
                        int subPixelBucket,
                        long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot queue glyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        CgRasterFontKey rasterFontKey = new CgRasterFontKey(key.getFontKey(), effectiveTargetPx);
        if (key.isMsdf()) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(key.getFontKey());
            CgMsdfAtlasKey msdfAtlasKey = toMsdfAtlasKey(key.getFontKey(), config);
            CgGlyphKey atlasKey = toMsdfAtlasGlyphKey(key, config);
            CgGlyphAtlas atlas = getMsdfAtlas(msdfAtlasKey.getConfig());
            if (atlas.get(atlasKey, currentFrame) == null) {
                submitMsdfGlyphJob(font, atlasKey, msdfAtlasKey);
            }
            return;
        }

        CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket,
                        key.isSyntheticBold(), key.isSyntheticItalic()));
        CgGlyphAtlas atlas = getBitmapAtlas();
        if (atlas.get(atlasKey, currentFrame) == null) {
            submitBitmapGlyphJob(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 4. Key transformation helpers
    //
    //  These methods convert a caller-visible CgGlyphKey (which carries the
    //  logical font key and glyph ID) into the internal atlas/cache key used
    //  for  atlas lookup.  This is one of the most non-obvious
    //  representation changes in the codebase — understanding WHY a single
    //  logical glyph becomes different atlas keys depending on the atlas
    //  family is essential to following the cache pipeline.
    //
    //  MSDF atlas keys:
    //    The font key is rewritten to use atlasScalePx (from CgMsdfAtlasConfig)
    //    instead of the requested targetPx, because all MSDF glyphs for a
    //    given font/config share a single atlas family at a fixed scale.
    //    The sub-pixel bucket is forced to 0 because MSDF rendering does not
    //    use sub-pixel offsets.
    //
    //  Bitmap atlas keys:
    //    The font key is rewritten to use effectiveTargetPx (from the
    //    CgRasterFontKey) because bitmap glyphs are rasterized at the
    //    actual draw-time pixel size.  The sub-pixel bucket is preserved.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Builds the atlas-family key for a  MSDF atlas.
     *
     * <p>Groups all MSDF glyphs that share the same base font identity and
     * generation config into a single atlas family, regardless of requested
     * render size.</p>
     */
    CgMsdfAtlasKey toMsdfAtlasKey(CgFontKey baseFontKey, CgMsdfAtlasConfig config) {
        return new CgMsdfAtlasKey(baseFontKey, config);
    }

    /**
     * Rewrites a caller-visible {@link CgGlyphKey} into the MSDF atlas glyph key.
     *
     * <p>The font key's targetPx is replaced with {@code config.atlasScalePx},
     * and the sub-pixel bucket is zeroed, because MSDF glyphs are resolution-
     * independent and do not use sub-pixel positioning.</p>
     */
    CgGlyphKey toMsdfAtlasGlyphKey(CgGlyphKey requestedKey, CgMsdfAtlasConfig config) {
        CgFontKey atlasFontKey = requestedKey.getFontKey().withTargetPx(config.atlasScalePx());
        return new CgGlyphKey(atlasFontKey, requestedKey.getGlyphId(), true, 0,
                requestedKey.isSyntheticBold(), requestedKey.isSyntheticItalic());
    }

    /**
     * Rewrites a {@link CgRasterGlyphKey} into a {@link CgGlyphKey} for
     * bitmap atlas lookup.
     *
     * <p>The font key's targetPx is replaced with the effective raster size
     * from the raster key, preserving the sub-pixel bucket.</p>
     */
    CgGlyphKey toBitmapAtlasGlyphKey(CgRasterGlyphKey rasterGlyphKey) {
        CgFontKey atlasFontKey = rasterGlyphKey.rasterFontKey()
                .getBaseFontKey()
                .withTargetPx(rasterGlyphKey.rasterFontKey().getEffectiveTargetPx());
        return new CgGlyphKey(
                atlasFontKey,
                rasterGlyphKey.glyphId(),
                rasterGlyphKey.msdf(),
                rasterGlyphKey.subPixelBucket(),
                rasterGlyphKey.syntheticBold(),
                rasterGlyphKey.syntheticItalic());
    }

    /**
     * Resolves the MSDF atlas configuration for a given base font key.
     *
     * <p>Currently returns the registry-wide default config.  This hook exists
     * so that per-font config overrides can be added without changing callers.</p>
     */
    public CgMsdfAtlasConfig resolveMsdfAtlasConfig(CgFontKey baseFontKey) {
        return msdfAtlasConfig;
    }

    /**
     * Public convenience alias for {@link #resolveMsdfAtlasConfig}.
     *
     * <p>Used by the debug harness to inspect the effective MSDF config for
     * a given font key.</p>
     */
    public CgMsdfAtlasConfig getResolvedMsdfConfig(CgFontKey baseFontKey) {
        return resolveMsdfAtlasConfig(baseFontKey);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 5.  bitmap rasterization
    //
    //  Rasterizes a glyph via FreeType at the effective target pixel size
    //  and allocates it into the  bitmap atlas. Placement metrics
    //  (bearing, width, height) MUST come from this same effectiveTargetPx
    //  render, matching the bitmap pixels just captured -- CgResolvedGlyphs
    //  #logicalMetricScale expects raw plane bounds in raster-time
    //  (effective-px) units and itself normalizes them back to logical/
    //  base-px space by multiplying by (baseTargetPx / effectiveTargetPx).
    //  This used to re-measure metrics at basePx instead (intended to avoid
    //  hinting-rounding drift), but hinting is a non-linear, per-glyph,
    //  per-size grid-fit -- a glyph hinted at 18px and one hinted at 22px
    //  are not simply scaled versions of each other, so sizing/positioning
    //  the 18px-rendered bitmap's quad using 22px-hinted metrics stretched
    //  or cropped its content inconsistently per glyph (worse for synthetic
    //  bold/italic, whose embolden/shear amplifies the mismatch further) --
    //  visible only when effectiveTargetPx != basePx, i.e. any non-1.0 UI
    //  scale still in the bitmap raster tier.
    // ────────────────────────────────────────────────────────────────────

    private CgGlyphPlacement ensureBitmapGlyph(CgFont font, CgGlyphKey atlasKey,
                                                      CgRasterFontKey rasterFontKey,
                                                      int effectiveTargetPx,
                                                      int subPixelBucket,
                                                      long currentFrame) {
        CgGlyphAtlas atlas = getBitmapAtlas();
        CgGlyphPlacement cached = atlas.get(atlasKey, currentFrame);
        if (cached != null) {
            CgProfiler.count("glyph.bitmap.atlasHit");
            return cached;
        }
        // Deliberately uncapped, unlike the MSDF sync path's CgMsdfGenerator.MAX_PER_FRAME budget
        // -- see the CgProfiler instrumentation added here specifically to quantify how much
        // render-thread time this uncapped fallback costs during MSDF atlas warmup, when most
        // glyphs land here every frame until their real MSDF result completes asynchronously.
        CgProfiler.count("glyph.bitmap.syncRasterized");

        FTFace face = font.getFtFace();
        boolean synthesize = atlasKey.isSyntheticBold() || atlasKey.isSyntheticItalic();
        try {
            try (CgProfiler.Scope ignored = CgProfiler.scope("freetype.rasterize")) {
                face.setPixelSizes(0, effectiveTargetPx);

                int loadFlags = FTLoadFlags.FT_LOAD_DEFAULT;
                boolean subBucket = subPixelBucket > 0 && effectiveTargetPx < CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX;
                // Embolden/shear operate on FT_Outline — a bitmap-only glyph (e.g. an emoji/color
                // strike) has no outline to transform, so this is best-effort like real browsers'
                // synthesis: no outline means no visible faux-style, not a hard failure.
                if (subBucket || synthesize) loadFlags = FTLoadFlags.FT_LOAD_NO_BITMAP;
                
                // Bytecode/autohinting grid-fits stems assuming an UPRIGHT glyph; shearing (or even
                // embolden's point-shift) after that hinting has already snapped stems to whole
                // pixels breaks the per-height grid-fit consistency, producing broken/jagged stems
                // at small sizes. Disabling hinting for synthesized glyphs avoids this — the same
                // reason production font engines skip/reduce hinting for synthetic oblique.
                if (synthesize) loadFlags |= FTLoadFlags.FT_LOAD_NO_HINTING;
                

                loadGlyphOrFallback(face, atlasKey.getGlyphId(), loadFlags);
                applySyntheticStyle(face, atlasKey, effectiveTargetPx);

                if (subBucket) face.outlineTranslate(subPixelBucket * 16L, 0L);
                

                face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

                FTBitmap bitmap = face.getGlyphBitmap();
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                if (width == 0 || height == 0) {
                    // A space/control/blank glyph. Record the verdict instead of returning a
                    // bare null: null is indistinguishable from "not generated yet", so every
                    // later resolve would re-run this whole MSDF-attempt + FreeType path to
                    // rediscover the same nothing. See CgGlyphAtlas#emptyGlyphs.
                    CgProfiler.count("glyph.bitmap.markedEmpty");
                    return atlas.markEmpty(atlasKey);
                }

                byte[] pixels = normalizeBitmapBuffer(bitmap);
                // Bearing/size MUST come from this bitmap's own left/top/width/height, NOT from
                // FTGlyphMetrics (the outline's sub-pixel-precise bounding box) -- see
                // CgWorkerFontContext#generateBitmap's javadoc-comment for the full explanation.
                // FreeType hints/grid-fits the outline during rendering (a per-glyph, per-size,
                // non-linear adjustment) and bakes the result into bitmap.left/top/width/height,
                // but does not update FT_Glyph_Metrics to match. This used to also re-measure at
                // basePx when effectiveTargetPx != basePx (matching CgWorkerFontContext's old,
                // now-fixed bug) -- dead code here specifically, since toBitmapAtlasGlyphKey
                // already rewrites atlasKey's font key to effectiveTargetPx, so that condition
                // was always false -- but the FTGlyphMetrics-vs-bitmap mismatch itself was real
                // and is what this fixes.
                float bearingX = bitmap.getLeft();
                float bearingY = bitmap.getTop();
                float metricsWidth = width;
                float metricsHeight = height;
                return atlas.allocateBitmap(atlasKey, pixels, width, height,
                        bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
            }
        } catch (FreeTypeException e) {
            LOGGER.log(Level.WARNING, "Failed to rasterize glyph at effective size " + effectiveTargetPx + ": " + atlasKey, e);
            return null;
        } finally {
            restoreFontShapingState(font);
        }
    }

    /**
     * Synthetic-oblique shear magnitude, matching Chromium/Skia's fake-italic matrix
     * ({@code SkScalerContext_FreeType}, magnitude {@code 0.25}). The sign is positive here,
     * not Skia's {@code -0.25}: Skia's constant is expressed in its own Y-down screen-space
     * convention, while {@code FTFace.outlineShear} operates directly on FreeType's outline,
     * which is Y-up (font design space, ascenders positive) — the same visual rightward lean
     * needs the opposite sign under the flipped Y axis. Confirmed empirically: {@code -0.25}
     * produced a backslash-direction (leaning left) lean instead of the correct forward lean.
     */
    private static final double SYNTHETIC_ITALIC_SKEW = 0.25;

    /**
     * Applies {@code atlasKey}'s synthetic bold/italic flags (see {@link CgGlyphKey#isSyntheticBold()}/
     * {@link CgGlyphKey#isSyntheticItalic()}) to the glyph currently loaded on {@code face}, at
     * {@code pixelSizePx}. Must be called after {@code loadGlyphOrFallback} (with
     * {@code FT_LOAD_NO_BITMAP}) and before {@code renderGlyph}/reading metrics. A no-op for a
     * bitmap-only glyph (no outline to transform) — matches how real browsers silently skip
     * synthesis for color/bitmap-strike glyphs rather than failing the whole draw.
     *
     * @param pixelSizePx the pixel size {@code face} was just set to — embolden strength is
     *                    derived from this, per Skia's {@code strength = pixelSize26_6 / 24}
     */
    private void applySyntheticStyle(FTFace face, CgGlyphKey atlasKey, int pixelSizePx) {
        if (!atlasKey.isSyntheticBold() && !atlasKey.isSyntheticItalic()) {
            return;
        }
        try {
            if (atlasKey.isSyntheticBold()) {
                long strength = Math.round(pixelSizePx * 64.0 / 24.0);
                face.outlineEmbolden(strength);
            }
            if (atlasKey.isSyntheticItalic()) {
                face.outlineShear(SYNTHETIC_ITALIC_SKEW);
            }
        } catch (IllegalStateException e) {
            LOGGER.log(Level.FINE, "Skipping synthetic bold/italic for glyph with no outline: " + atlasKey, e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 6.  MSDF generation
    //
    //  Generates an MSDF glyph via CgMsdfGenerator and allocates it into
    //  the  MSDF atlas.  Falls back to the bitmap  path when
    //  MSDF generation is unavailable (no msdfFont handle) or skipped
    //  (prepareGlyphWithinBudget returns null).
    // ────────────────────────────────────────────────────────────────────

    private CgGlyphPlacement ensureMsdfGlyph(CgFont font, CgGlyphKey atlasKey,
                                                   CgMsdfAtlasKey msdfAtlasKey,
                                                   int effectiveTargetPx,
                                                   int subPixelBucket,
                                                   long currentFrame,
                                                   boolean allowSyncGeneration) {
        CgGlyphAtlas atlas = getMsdfAtlas(msdfAtlasKey.getConfig());
        CgGlyphPlacement cached = atlas.get(atlasKey, currentFrame);
        if (cached != null) {
            CgProfiler.count("glyph.msdf.atlasHit");
            return cached;
        }

        FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
        // Never duplicate work a background worker already has in flight. Generating a glyph costs
        // 13 ms on average and 32 ms for dense CJK, all of it on the render thread, and
        // commitGeneratedGlyph discards whichever copy arrives second — so the duplicate buys
        // nothing but a dropped frame. Falling through to the bitmap path shows the glyph
        // immediately and lets the worker's result replace it a few frames later.
        if (msdfFont != null && allowSyncGeneration
                && glyphGenerationExecutor.isPending(
                        CgGlyphGenerationJob.msdf(font.getKey(), font.getFontBytes(),
                                atlasKey, msdfAtlasKey, msdfAtlasKey.getConfig()))) {
            CgProfiler.count("glyph.msdf.syncSkippedAlreadyPending");
            allowSyncGeneration = false;
        }
        if (msdfFont != null && allowSyncGeneration) {
            try {
                CgGlyphGenerationResult generated;
                try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.generate")) {
                    generated = msdfGenerator.prepareGlyphWithinBudget(
                            atlasKey,
                            font.getKey(),
                            msdfFont,
                            msdfAtlasKey);
                }
                if (generated != null) {
                    commitGeneratedGlyph(generated, currentFrame);
                    CgGlyphPlacement placement = atlas.get(atlasKey, currentFrame);
                    if (placement != null) {
                        CgProfiler.count("glyph.msdf.syncGenerated");
                        return placement;
                    }
                } else {
                    // CgMsdfGenerator.MAX_PER_FRAME already reached this frame, or a real
                    // generation failure -- either way this glyph falls through to the
                    // uncapped bitmap path below, every frame, until its atlas-committed
                    // MSDF result (async or a future sync attempt) actually lands.
                    CgProfiler.count("glyph.msdf.syncBudgetExhaustedOrFailed");
                }
            } finally {
                restoreFontShapingState(font);
            }
        }

        CgProfiler.count("glyph.msdf.fellBackToBitmap");
        // Fall back to bitmap via  atlas
        CgRasterFontKey bitmapRasterKey = new CgRasterFontKey(font.getKey(), effectiveTargetPx);
        CgGlyphKey bitmapAtlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(bitmapRasterKey, atlasKey.getGlyphId(), false, subPixelBucket,
                        atlasKey.isSyntheticBold(), atlasKey.isSyntheticItalic()));
        return ensureBitmapGlyph(font, bitmapAtlasKey, bitmapRasterKey,
                effectiveTargetPx, subPixelBucket, currentFrame);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 7. Async job submission
    //
    //  Submits glyph generation work to the background executor.  These
    //  methods are called by queueGlyph() when a glyph is not yet
    //  in the atlas.  Results are collected by drainCompletedGlyphs()
    //  during the next frame tick.
    // ────────────────────────────────────────────────────────────────────

    private void submitBitmapGlyphJob(CgFont font,
                                      CgGlyphKey atlasKey,
                                      CgRasterFontKey rasterFontKey,
                                      int effectiveTargetPx,
                                      int subPixelBucket) {
        CgGlyphGenerationJob job = CgGlyphGenerationJob.bitmap(
                font.getKey(),
                font.getFontBytes(),
                atlasKey,
                rasterFontKey,
                effectiveTargetPx,
                subPixelBucket);
        glyphGenerationExecutor.submit(job);
    }

    private void submitMsdfGlyphJob(CgFont font,
                                    CgGlyphKey atlasKey,
                                    CgMsdfAtlasKey msdfAtlasKey) {
        CgGlyphGenerationJob job = CgGlyphGenerationJob.msdf(
                font.getKey(),
                font.getFontBytes(),
                atlasKey,
                msdfAtlasKey,
                msdfAtlasKey.getConfig());
        glyphGenerationExecutor.submit(job);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 8. Async commit — draining completed results
    //
    //  Polls the executor's completed-results queue and uploads each
    //  result's pixel data into the appropriate  atlas.  Called by
    //  tickFrame() at the start of each render frame.
    // ────────────────────────────────────────────────────────────────────

    private void drainCompletedGlyphs(long frame, long maxBytes, int maxCommits, long maxNanos) {
        long start = System.nanoTime();
        long bytesCommitted = 0;
        int committed = 0;
        while (committed < maxCommits && bytesCommitted < maxBytes) {
            // Time check before polling, so an already-dequeued result is never dropped and a
            // fully drained queue costs one nanoTime() call, not a wasted poll.
            if (System.nanoTime() - start >= maxNanos) {
                CgProfiler.count("asyncCommit.timeBudgetHit");
                break;
            }
            CgGlyphGenerationResult result = glyphGenerationExecutor.pollCompleted();
            if (result == null) {
                break;
            }
            commitGeneratedGlyph(result, frame);
            committed++;
            bytesCommitted += estimateUploadBytes(result);
        }
        CgProfiler.count("asyncCommit.glyphsUploaded", committed);
        CgProfiler.count("asyncCommit.bytesUploaded", bytesCommitted);
    }

    /**
     * Estimates the GPU upload size of a glyph result's pixel data, in bytes.
     * Bitmap uploads are {@code GL_R8} (1 byte/pixel); MSDF/MTSDF uploads are
     * {@code GL_FLOAT} (4 bytes/channel, 3 or 4 channels) — roughly 3-4x the
     * bytes of a bitmap upload at the same pixel dimensions. Used to keep the
     * per-frame commit budget ({@link #MAX_COMMIT_BYTES_PER_FRAME}) meaningful
     * across a mixed bitmap/MSDF/MTSDF workload instead of a flat glyph count
     * that treats every result as the same upload cost.
     */
    private static long estimateUploadBytes(CgGlyphGenerationResult result) {
        long pixels = (long) result.getWidth() * result.getHeight();
        switch (result.getAtlasType()) {
            case MTSDF:
                return pixels * 4L /* channels */ * 4L /* bytes per float */;
            case MSDF:
                return pixels * 3L * 4L;
            case BITMAP:
            default:
                return pixels;
        }
    }

    /**
     * Uploads a single completed glyph result into the correct  atlas.
     *
     * <p>Skips the upload if the glyph has already been committed (race with
     * synchronous ensure path) or if the result represents an empty geometry
     * glyph (e.g. space character).</p>
     */
    private void commitGeneratedGlyph(CgGlyphGenerationResult result, long frame) {
        if (result.isDistanceField()) {
            CgGlyphAtlas atlas = getMsdfAtlas(result.getMsdfAtlasKey().getConfig());
            if (atlas.get(result.getAtlasKey(), frame) != null) {
                return;
            }
            if (result.isEmptyGeometry()) {
                // Record rather than drop — see the matching case in ensureBitmapGlyph.
                atlas.markEmpty(result.getAtlasKey());
                return;
            }
            atlas.allocateMsdf(
                    result.getAtlasKey(),
                    result.getMsdfData(),
                    result.getWidth(),
                    result.getHeight(),
                    result.getBearingX(),
                    result.getBearingY(),
                    result.getPlaneLeft(),
                    result.getPlaneBottom(),
                    result.getPlaneRight(),
                    result.getPlaneTop(),
                    result.getMetricsWidth(),
                    result.getMetricsHeight(),
                    result.getPxRange(),
                    frame);
            return;
        }

        CgGlyphAtlas atlas = getBitmapAtlas();
        if (atlas.get(result.getAtlasKey(), frame) != null) {
            return;
        }
        if (result.isEmptyGeometry()) {
            return;
        }
        atlas.allocateBitmap(
                result.getAtlasKey(),
                result.getBitmapData(),
                result.getWidth(),
                result.getHeight(),
                result.getBearingX(),
                result.getBearingY(),
                result.getMetricsWidth(),
                result.getMetricsHeight(),
                frame);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 9.  atlas accessor helpers
    //
    //  Lazily create  atlas instances keyed by raster font key (bitmap)
    //  or MSDF atlas key (MSDF).  These are package-private — only the
    //  registry and tests should call them directly.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the reserved opaque-white texel for whichever atlas would hold glyphs of
     * {@code fontKey} at this raster tier — the same key transformation
     * {@link #ensureGlyph} uses, so a decoration line drawn alongside this font's glyphs
     * samples from the exact atlas/page those glyphs are already on, needing no extra material
     * transition. Used by {@code CgTextRenderer} to draw underline/strikethrough quads.
     *
     * @param effectiveTargetPx ignored when {@code msdf} is {@code true} (MSDF atlases are
     *                          keyed by font identity only, not raster size — see
     *                          {@link #toMsdfAtlasKey})
     */
    public CgGlyphAtlas.WhiteTexel getDecorationWhiteTexel(CgFontKey fontKey, int effectiveTargetPx, boolean msdf) {
        if (msdf) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(fontKey);
            CgMsdfAtlasKey msdfAtlasKey = toMsdfAtlasKey(fontKey, config);
            return getMsdfAtlas(config).reserveWhiteTexel(config.pxRange());
        }
        CgRasterFontKey rasterFontKey = new CgRasterFontKey(fontKey, effectiveTargetPx);
        // Bitmap CgGlyphPlacements always carry pxRange=0f (unused for that tier) — matching
        // that here is what lets a decoration's batch key equal a bitmap glyph's exactly.
        return getBitmapAtlas().reserveWhiteTexel(0f);
    }

    /** The one bitmap atlas, shared by every font at every raster size. Created on first use. */
    CgGlyphAtlas getBitmapAtlas() {
        if (BITMAP_ATLAS == null) {
            BITMAP_ATLAS = CgGlyphAtlas.createForRegistry(atlasSize, atlasSize, CgGlyphAtlas.Type.BITMAP);
        }
        return BITMAP_ATLAS;
    }

    /**
     * The one distance-field atlas, shared by every font. Created on first use from {@code config},
     * which is registry-wide -- see the field declaration for why per-font configs cannot exist
     * once the atlas is shared.
     */
    CgGlyphAtlas getMsdfAtlas(CgMsdfAtlasConfig config) {
        if (MSDF_ATLAS == null) {
            MSDF_ATLAS = CgGlyphAtlas.createForRegistry(config.pageSize(), config.pageSize(), config.resolveAtlasType(),
                    config.spacingPx());
        }
        return MSDF_ATLAS;
    }

    /** Live atlases, for the few operations that apply to both tiers. */
    private List<CgGlyphAtlas> liveAtlases() {
        List<CgGlyphAtlas> live = new ArrayList<>(2);
        if (BITMAP_ATLAS != null) live.add(BITMAP_ATLAS);
        if (MSDF_ATLAS != null) live.add(MSDF_ATLAS);
        return live;
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 11. Atlas inspection / enumeration
    //
    //  Diagnostic utilities used by the debug harness and integration demo
    //  to enumerate populated atlas pages.  These are NOT part of the main
    //  rendering pipeline — they iterate atlas maps by base font key to
    //  find populated atlases for visualization/debugging.
    // ────────────────────────────────────────────────────────────────────

    // NOTE ON THE FONT-KEY PARAMETERS BELOW
    //
    // These used to filter atlas families by font. With one shared atlas per tier a page holds
    // glyphs from many fonts at once, so "this font's pages" is no longer a thing that exists --
    // the question cannot be answered at page granularity, by construction rather than by
    // omission. The parameters are kept so existing diagnostic callers still compile, and are
    // ignored; every method now reports the whole tier.
    //
    // If a genuinely per-font view is ever needed again it has to come from CgGlyphKey (which
    // still carries the full CgFontKey) by walking a page's slot map, not from atlas identity.

    /**
     * First populated page of the shared bitmap atlas.
     *
     * @param key ignored -- see the note above this method group
     * @return a populated bitmap atlas page, or {@code null} if none exists
     */
    public CgGlyphAtlasPage findPopulatedBitmapPage(CgFontKey key) {
        return BITMAP_ATLAS == null ? null : BITMAP_ATLAS.getFirstPopulatedPage();
    }

    /**
     * First populated page of the shared distance-field atlas.
     *
     * @param key ignored -- see the note above this method group
     * @return a populated MSDF atlas page, or {@code null} if none exists
     */
    public CgGlyphAtlasPage findPopulatedMsdfPage(CgFontKey key) {
        return MSDF_ATLAS == null ? null : MSDF_ATLAS.getFirstPopulatedPage();
    }

    /**
     * Every populated page of the shared bitmap atlas.
     *
     * @param key ignored -- see the note above this method group
     */
    public List<CgGlyphAtlasPage> findAllPopulatedBitmapPages(CgFontKey key) {
        return populatedPagesOf(BITMAP_ATLAS);
    }

    /**
     * Every populated page of the shared distance-field atlas.
     *
     * @param key ignored -- see the note above this method group
     */
    public List<CgGlyphAtlasPage> findAllPopulatedMsdfPages(CgFontKey key) {
        return populatedPagesOf(MSDF_ATLAS);
    }

    /**
     * Populated bitmap pages, reported under a single bucket.
     *
     * <p>The bucket key is no longer a real raster size. Bitmap glyphs of every size now share one
     * atlas, so a page routinely mixes them and cannot be labelled with one number; the single
     * entry is keyed by {@link #atlasSize} purely so existing dump code has something to group by.
     * Treat the label as "the shared bitmap atlas", not as a pixel size.
     *
     * @param key ignored -- see the note above this method group
     */
    public Map<Integer, List<CgGlyphAtlasPage>> findAllPopulatedBitmapPagesBySize(CgFontKey key) {
        List<CgGlyphAtlasPage> pages = populatedPagesOf(BITMAP_ATLAS);
        if (pages.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, List<CgGlyphAtlasPage>> result = new LinkedHashMap<>();
        result.put(atlasSize, pages);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Populated distance-field pages, reported under their generation scale.
     *
     * <p>Unlike the bitmap case this label stays truthful: the shared MSDF atlas has exactly one
     * {@link com.crystalgraphics.text.msdf.CgMsdfAtlasConfig#atlasScalePx()} by construction, so
     * every page really was generated at it.
     *
     * @param key ignored -- see the note above this method group
     */
    public Map<Integer, List<CgGlyphAtlasPage>> findAllPopulatedMSDFPagesBySize(CgFontKey key) {
        List<CgGlyphAtlasPage> pages = populatedPagesOf(MSDF_ATLAS);
        if (pages.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, List<CgGlyphAtlasPage>> result = new LinkedHashMap<>();
        result.put(msdfAtlasConfig.atlasScalePx(), pages);
        return Collections.unmodifiableMap(result);
    }

    private static List<CgGlyphAtlasPage> populatedPagesOf(CgGlyphAtlas atlas) {
        if (atlas == null || atlas.isDeleted()) {
            return Collections.emptyList();
        }
        List<CgGlyphAtlasPage> result = new ArrayList<CgGlyphAtlasPage>();
        for (CgGlyphAtlasPage page : atlas.getPages()) {
            if (page.getSlotCount() > 0 && !page.isDeleted()) {
                result.add(page);
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 12. Font registration & atlas cleanup
    //
    //  Manages the lifecycle link between CgFont instances and their atlas
    //  resources.  When a font is disposed, all associated  atlases are
    //  released, and pending async jobs for that font are cleared.
    // ────────────────────────────────────────────────────────────────────

    private void registerFont(final CgFont font) {
        final CgFontKey fontKey = font.getKey();
        if (registeredFonts.add(fontKey)) {
            font.setDisposeListener(() -> {
                releaseFontAtlases(fontKey);
                registeredFonts.remove(fontKey);
            });
        }
    }

    /**
     * Releases all atlas resources associated with the given font key.
     *
     * <p>Clears any pending/failed async jobs for the font, then deletes
     * and removes all  atlases.</p>
     */
    public void releaseFontAtlases(CgFontKey key) {
        glyphGenerationExecutor.clearFont(key);
        releaseatlasesForFont(key);
    }

    /**
     * Releases all atlas resources across all fonts and resets the registry back to
     * a freshly-constructed, immediately reusable state.
     *
     * <p>Called by {@code CgGraphicsLifecycle.destroyContext()} for the shared
     * {@link #get()} instance. Since that instance is a permanent static singleton
     * (unlike the old per-caller construction model, where a fresh
     * {@code new CgFontRegistry()} naturally came with a fresh executor), this method
     * must leave the registry usable again immediately — a new GL context can be
     * initialized right after {@code destroyContext()} returns. The background
     * generation executor is shut down (permanently — {@code CgGlyphGenerationExecutor}
     * cannot be restarted) and replaced with a fresh instance, matching how
     * {@code CgGraphicsLifecycle} resets other backend caches in place rather than
     * requiring a new object.</p>
     */
    public void releaseAll() {
        for (CgGlyphAtlas atlas : liveAtlases()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        BITMAP_ATLAS = null;
        MSDF_ATLAS = null;

        registeredFonts.clear();
        glyphGenerationExecutor.shutdown();
        glyphGenerationExecutor = new CgGlyphGenerationExecutor();
    }

    /**
     * Releasing a single font's atlas storage is <strong>intentionally a no-op</strong> now that
     * atlases are shared.
     *
     * <p>A disposed font's glyphs sit in pages alongside other fonts' glyphs, so there is no
     * texture that belongs to it and no page that is solely its own. Reclaiming its space eagerly
     * would require glyph-granular removal, which in turn requires a packing strategy that can free
     * individual rects -- reintroducing fragmentation for no real benefit.
     *
     * <p>The space is reclaimed lazily instead: the font's glyphs stop being touched, the pages
     * holding them drift to the bottom of the atlas LRU, and they are evicted whole once page
     * pressure arrives. This is the same policy Skia uses for its shared glyph cache, and it is why
     * eviction here is page-granular rather than per-glyph.
     *
     * <p>Note that registry atlases are currently created with
     * {@link CgGlyphAtlas#UNBOUNDED_PAGES}, so nothing is evicted yet and the memory is simply
     * retained. Sharing is what makes enabling eviction later a single decision in one place rather
     * than a per-font policy.
     */
    private void releaseatlasesForFont(CgFontKey baseKey) {
        // Deliberately empty -- see javadoc.
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 13. Low-level utilities
    // ────────────────────────────────────────────────────────────────────

    private void loadGlyphOrFallback(FTFace face, int glyphIndex, int loadFlags) throws FreeTypeException {
        try {
            face.loadGlyph(glyphIndex, loadFlags);
        } catch (FreeTypeException e) {
            LOGGER.fine("Glyph " + glyphIndex + " not found, falling back to .notdef");
            face.loadGlyph(0, loadFlags);
        }
    }

    /**
     * Restores the font's FreeType face to the base size used for text shaping.
     *
     * <p>Glyph rasterization temporarily changes the face's pixel size to the
     * effective target size.  This must be restored before the shaper is used
     * again, because FreeType faces carry mutable size state.</p>
     */
    private void restoreFontShapingState(CgFont font) {
        try {
            font.restoreBaseFontSizeForShaping();
        } catch (FreeTypeException e) {
            throw new IllegalStateException("Failed to restore base font size for shaping: "
                    + font.getKey(), e);
        }
    }

    /**
     * Normalizes FreeType bitmap data into a tightly-packed byte array.
     *
     * <p>FreeType bitmaps may have pitch (row stride) larger than width due to
     * alignment, or negative pitch for bottom-up storage.  This method produces
     * a width×height byte array with no padding, suitable for GL upload.</p>
     */
    private byte[] normalizeBitmapBuffer(FTBitmap bitmap) {
        byte[] source = bitmap.getBuffer();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int pitch = bitmap.getPitch();
        if (pitch == width) {
            return source;
        }

        byte[] packed = new byte[width * height];
        int absPitch = Math.abs(pitch);
        for (int row = 0; row < height; row++) {
            int srcRow = pitch >= 0 ? row : (height - 1 - row);
            System.arraycopy(source, srcRow * absPitch, packed, row * width, width);
        }
        return packed;
    }
}
