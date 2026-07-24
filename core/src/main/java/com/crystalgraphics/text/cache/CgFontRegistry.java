package com.crystalgraphics.text.cache;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import com.crystalgraphics.text.atlas.CgPagedGlyphAtlas;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import com.crystalgraphics.text.msdf.CgMsdfGenerator;
import com.crystalgraphics.text.render.CgTextRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><strong>Authoritative paged glyph path</strong> &mdash; {@link #ensureGlyphPaged} and its pre-queue helper,
 *       the main entry point for the multi-page atlas system</li>
 *   <li><strong>Key transformation helpers</strong> &mdash; methods that convert a caller-visible
 *       {@link CgGlyphKey} into the internal atlas/cache key used for lookup</li>
 *   <li><strong>Paged bitmap rasterization</strong> &mdash; FreeType bitmap path for paged atlases</li>
 *   <li><strong>Paged MSDF generation</strong> &mdash; MSDF path for paged atlases (with bitmap fallback)</li>
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
 * supports this via {@link CgRasterFontKey}-keyed paged atlas maps that allow the
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
    // ────────────────────────────────────────────────────────────────────

    private final int atlasSize;
    private final CgMsdfAtlasConfig msdfAtlasConfig;

    // Paged atlas maps — the active/authoritative path for new allocations.
    // Bitmap paged atlases are keyed by CgRasterFontKey (effective-size-aware);
    // MSDF paged atlases are keyed by CgMsdfAtlasKey (size-agnostic, config-aware).
    private final Map<CgRasterFontKey, CgPagedGlyphAtlas> pagedBitmapAtlases = new HashMap<CgRasterFontKey, CgPagedGlyphAtlas>();
    private final Map<CgMsdfAtlasKey, CgPagedGlyphAtlas> pagedMsdfAtlases = new HashMap<CgMsdfAtlasKey, CgPagedGlyphAtlas>();

    private final Set<CgFontKey> registeredFonts = new HashSet<CgFontKey>();
    private final CgMsdfGenerator msdfGenerator = new CgMsdfGenerator();
    // Not final — releaseAll() replaces this with a fresh instance so the shared
    // singleton stays usable after a GL context is destroyed and recreated (see
    // CgGraphicsLifecycle). CgGlyphGenerationExecutor.shutdown() is permanent —
    // a fresh instance is the only way back to a submittable state.
    private CgGlyphGenerationExecutor glyphGenerationExecutor = new CgGlyphGenerationExecutor();

    /** Maximum number of async glyph results committed (uploaded) per frame tick. */
    private static final int MAX_COMMITS_PER_FRAME = 32;

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
     * uploads up to {@value #MAX_COMMITS_PER_FRAME} completed async glyph
     * results into their target atlases.
     *
     * <p>Must be called exactly once per render frame, before any
     * {@code ensureGlyph*} or {@code queueGlyph*} calls for that frame.</p>
     */
    public void tickFrame(long frame) {
        // 1. Drain completed async results first so they are available
        //    to ensureGlyph* calls later in the same frame.
        drainCompletedGlyphs(frame, MAX_COMMITS_PER_FRAME);

        // 2. Tick every paged atlas family.
        for (CgPagedGlyphAtlas atlas : pagedBitmapAtlases.values()) {
            atlas.tickFrame(frame);
        }
        for (CgPagedGlyphAtlas atlas : pagedMsdfAtlases.values()) {
            atlas.tickFrame(frame);
        }

        // 3. Reset the MSDF generator's per-frame budget counter.
        msdfGenerator.tickFrame();
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

    // ────────────────────────────────────────────────────────────────────
    //  § 3. Authoritative paged glyph path
    //
    //  This is the PRIMARY entry point for the multi-page atlas system.
    //  The renderer calls ensureGlyphPaged() to obtain a CgGlyphPlacement
    //  for each visible glyph; queueGlyphPaged() pre-queues glyphs that
    //  are likely to be needed (reducing frame spikes).
    //
    //  Pipeline:
    //    CgGlyphKey → key transformation → paged atlas lookup →
    //    [cache hit: return placement] →
    //    [cache miss: rasterize/generate → allocate into atlas → return placement]
    // ────────────────────────────────────────────────────────────────────

    /**
     * Ensures a glyph is available in the paged atlas and returns its
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
     */
    public CgGlyphPlacement ensureGlyphPaged(CgFont font,
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
            return ensureMsdfGlyphPaged(font, atlasKey, msdfAtlasKey, effectiveTargetPx, subPixelBucket, currentFrame);
        } else {
            CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                    new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket));
            return ensureBitmapGlyphPaged(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket, currentFrame);
        }
    }

    /**
     * Pre-queues a glyph for async generation if it is not already in the
     * paged atlas.
     *
     * <p>The renderer calls this during the pre-queue pass
     * ({@code CgResolvedGlyphs.flattenAndPrequeue}) to submit glyph generation jobs to the
     * background executor <em>before</em> the synchronous {@code ensureGlyphPaged}
     * calls.  This reduces frame spikes by spreading generation work across
     * multiple frames.</p>
     *
     * <p>If the glyph is already cached in the paged atlas, this is a no-op.
     * Otherwise a background job is submitted via
     * {@link CgGlyphGenerationExecutor}.</p>
     */
    public void queueGlyphPaged(CgFont font,
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
            CgPagedGlyphAtlas pagedAtlas = getPagedMsdfAtlas(msdfAtlasKey);
            if (pagedAtlas.get(atlasKey, currentFrame) == null) {
                submitMsdfGlyphJob(font, atlasKey, msdfAtlasKey);
            }
            return;
        }

        CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket));
        CgPagedGlyphAtlas pagedAtlas = getPagedBitmapAtlas(rasterFontKey);
        if (pagedAtlas.get(atlasKey, currentFrame) == null) {
            submitBitmapGlyphJob(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 4. Key transformation helpers
    //
    //  These methods convert a caller-visible CgGlyphKey (which carries the
    //  logical font key and glyph ID) into the internal atlas/cache key used
    //  for paged atlas lookup.  This is one of the most non-obvious
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
     * Builds the atlas-family key for a paged MSDF atlas.
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
        CgFontKey atlasFontKey = requestedKey.getFontKey().withTargetPx(config.getAtlasScalePx());
        return new CgGlyphKey(atlasFontKey, requestedKey.getGlyphId(), true, 0);
    }

    /**
     * Rewrites a {@link CgRasterGlyphKey} into a {@link CgGlyphKey} for
     * bitmap atlas lookup.
     *
     * <p>The font key's targetPx is replaced with the effective raster size
     * from the raster key, preserving the sub-pixel bucket.</p>
     */
    CgGlyphKey toBitmapAtlasGlyphKey(CgRasterGlyphKey rasterGlyphKey) {
        CgFontKey atlasFontKey = rasterGlyphKey.getRasterFontKey()
                .getBaseFontKey()
                .withTargetPx(rasterGlyphKey.getRasterFontKey().getEffectiveTargetPx());
        return new CgGlyphKey(
                atlasFontKey,
                rasterGlyphKey.getGlyphId(),
                rasterGlyphKey.isMsdf(),
                rasterGlyphKey.getSubPixelBucket());
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
    //  § 5. Paged bitmap rasterization
    //
    //  Rasterizes a glyph via FreeType at the effective target pixel size
    //  and allocates it into the paged bitmap atlas.  When effectiveTargetPx
    //  differs from the base font key's targetPx, placement metrics (bearing,
    //  width, height) are re-measured at the base size to avoid hinting-
    //  rounding drift when the renderer scales back to logical space.
    // ────────────────────────────────────────────────────────────────────

    private CgGlyphPlacement ensureBitmapGlyphPaged(CgFont font, CgGlyphKey atlasKey,
                                                      CgRasterFontKey rasterFontKey,
                                                      int effectiveTargetPx,
                                                      int subPixelBucket,
                                                      long currentFrame) {
        CgPagedGlyphAtlas pagedAtlas = getPagedBitmapAtlas(rasterFontKey);
        CgGlyphPlacement cached = pagedAtlas.get(atlasKey, currentFrame);
        if (cached != null) {
            return cached;
        }

        FTFace face = font.getFtFace();
        try {
            face.setPixelSizes(0, effectiveTargetPx);

            int loadFlags = FTLoadFlags.FT_LOAD_DEFAULT;
            boolean subBucket = subPixelBucket > 0
                    && effectiveTargetPx < CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX;
            if (subBucket) {
                loadFlags = FTLoadFlags.FT_LOAD_NO_BITMAP;
            }

            loadGlyphOrFallback(face, atlasKey.getGlyphId(), loadFlags);

            if (subBucket) {
                face.outlineTranslate(subPixelBucket * 16L, 0L);
            }

            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width == 0 || height == 0) {
                return null;
            }

            byte[] pixels = normalizeBitmapBuffer(bitmap);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            float bearingX = metrics.getHoriBearingX() / 64.0f;
            float bearingY = metrics.getHoriBearingY() / 64.0f;

            // When the effective raster size differs from the base font size,
            // re-measure placement metrics at base size to avoid hinting-
            // rounding drift when the renderer scales back to logical space.
            int basePx = atlasKey.getFontKey().getTargetPx();
            float metricsWidth;
            float metricsHeight;
            if (effectiveTargetPx != basePx) {
                face.setPixelSizes(0, basePx);
                loadGlyphOrFallback(face, atlasKey.getGlyphId(), FTLoadFlags.FT_LOAD_DEFAULT);
                FTGlyphMetrics baseMetrics = face.getGlyphMetrics();
                metricsWidth = baseMetrics.getWidth() / 64.0f;
                metricsHeight = baseMetrics.getHeight() / 64.0f;
                bearingX = baseMetrics.getHoriBearingX() / 64.0f;
                bearingY = baseMetrics.getHoriBearingY() / 64.0f;
            } else {
                metricsWidth = metrics.getWidth() / 64.0f;
                metricsHeight = metrics.getHeight() / 64.0f;
            }
            return pagedAtlas.allocateBitmap(atlasKey, pixels, width, height,
                    bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
        } catch (FreeTypeException e) {
            LOGGER.log(Level.WARNING, "Failed to rasterize glyph at effective size " + effectiveTargetPx + ": " + atlasKey, e);
            return null;
        } finally {
            restoreFontShapingState(font);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 6. Paged MSDF generation
    //
    //  Generates an MSDF glyph via CgMsdfGenerator and allocates it into
    //  the paged MSDF atlas.  Falls back to the bitmap paged path when
    //  MSDF generation is unavailable (no msdfFont handle) or skipped
    //  (preparePagedGlyphWithinBudget returns null).
    // ────────────────────────────────────────────────────────────────────

    private CgGlyphPlacement ensureMsdfGlyphPaged(CgFont font, CgGlyphKey atlasKey,
                                                   CgMsdfAtlasKey msdfAtlasKey,
                                                   int effectiveTargetPx,
                                                   int subPixelBucket,
                                                   long currentFrame) {
        CgPagedGlyphAtlas pagedAtlas = getPagedMsdfAtlas(msdfAtlasKey);
        CgGlyphPlacement cached = pagedAtlas.get(atlasKey, currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            try {
                CgGlyphGenerationResult generated = msdfGenerator.preparePagedGlyphWithinBudget(
                        atlasKey,
                        font.getKey(),
                        msdfFont,
                        msdfAtlasKey);
                if (generated != null) {
                    commitGeneratedGlyph(generated, currentFrame);
                    CgGlyphPlacement placement = pagedAtlas.get(atlasKey, currentFrame);
                    if (placement != null) {
                        return placement;
                    }
                }
            } finally {
                restoreFontShapingState(font);
            }
        }

        // Fall back to bitmap via paged atlas
        CgRasterFontKey bitmapRasterKey = new CgRasterFontKey(font.getKey(), effectiveTargetPx);
        CgGlyphKey bitmapAtlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(bitmapRasterKey, atlasKey.getGlyphId(), false, subPixelBucket));
        return ensureBitmapGlyphPaged(font, bitmapAtlasKey, bitmapRasterKey,
                effectiveTargetPx, subPixelBucket, currentFrame);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 7. Async job submission
    //
    //  Submits glyph generation work to the background executor.  These
    //  methods are called by queueGlyphPaged() when a glyph is not yet
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
    //  result's pixel data into the appropriate paged atlas.  Called by
    //  tickFrame() at the start of each render frame.
    // ────────────────────────────────────────────────────────────────────

    private void drainCompletedGlyphs(long frame, int maxCommits) {
        int committed = 0;
        while (committed < maxCommits) {
            CgGlyphGenerationResult result = glyphGenerationExecutor.pollCompleted();
            if (result == null) {
                break;
            }
            commitGeneratedGlyph(result, frame);
            committed++;
        }
    }

    /**
     * Uploads a single completed glyph result into the correct paged atlas.
     *
     * <p>Skips the upload if the glyph has already been committed (race with
     * synchronous ensure path) or if the result represents an empty geometry
     * glyph (e.g. space character).</p>
     */
    private void commitGeneratedGlyph(CgGlyphGenerationResult result, long frame) {
        if (result.isDistanceField()) {
            CgPagedGlyphAtlas atlas = getPagedMsdfAtlas(result.getMsdfAtlasKey());
            if (atlas.get(result.getAtlasKey(), frame) != null) {
                return;
            }
            if (result.isEmptyGeometry()) {
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

        CgPagedGlyphAtlas atlas = getPagedBitmapAtlas(result.getBitmapRasterKey());
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
    //  § 9. Paged atlas accessor helpers
    //
    //  Lazily create paged atlas instances keyed by raster font key (bitmap)
    //  or MSDF atlas key (MSDF).  These are package-private — only the
    //  registry and tests should call them directly.
    // ────────────────────────────────────────────────────────────────────

    CgPagedGlyphAtlas getPagedBitmapAtlas(CgRasterFontKey rasterKey) {
        CgPagedGlyphAtlas atlas = pagedBitmapAtlases.get(rasterKey);
        if (atlas == null) {
            atlas = CgPagedGlyphAtlas.createForPagedRegistry(atlasSize, atlasSize, CgGlyphAtlas.Type.BITMAP);
            pagedBitmapAtlases.put(rasterKey, atlas);
        }
        return atlas;
    }

    CgPagedGlyphAtlas getPagedMsdfAtlas(CgMsdfAtlasKey atlasKey) {
        CgPagedGlyphAtlas atlas = pagedMsdfAtlases.get(atlasKey);
        if (atlas == null) {
            atlas = CgPagedGlyphAtlas.createForPagedRegistry(
                    atlasKey.getConfig().getPageSize(),
                    atlasKey.getConfig().getPageSize(),
                    atlasKey.getConfig().resolveAtlasType(),
                    atlasKey.getConfig().getSpacingPx());
            pagedMsdfAtlases.put(atlasKey, atlas);
        }
        return atlas;
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 11. Atlas inspection / enumeration
    //
    //  Diagnostic utilities used by the debug harness and integration demo
    //  to enumerate populated atlas pages.  These are NOT part of the main
    //  rendering pipeline — they iterate atlas maps by base font key to
    //  find populated atlases for visualization/debugging.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the first non-empty paged bitmap atlas page that was populated
     * during rendering for the given base font key. Searches every raster-keyed
     * paged bitmap atlas family for that font.
     *
     * @return a populated bitmap atlas page, or null if none exists
     */
    public CgGlyphAtlasPage findPopulatedPagedBitmapPage(CgFontKey key) {
        for (Map.Entry<CgRasterFontKey, CgPagedGlyphAtlas> entry : pagedBitmapAtlases.entrySet()) {
            if (entry.getKey().getBaseFontKey().equals(key)) {
                CgGlyphAtlasPage page = entry.getValue().getFirstPopulatedPage();
                if (page != null) {
                    return page;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first non-empty paged MSDF atlas page that was populated
     * during rendering for the given base font key. Searches every paged MSDF
     * atlas family for that font.
     *
     * @return a populated MSDF atlas page, or null if none exists
     */
    public CgGlyphAtlasPage findPopulatedPagedMsdfPage(CgFontKey key) {
        for (Map.Entry<CgMsdfAtlasKey, CgPagedGlyphAtlas> entry : pagedMsdfAtlases.entrySet()) {
            if (entry.getKey().getBaseFontKey().equals(key)) {
                CgGlyphAtlasPage page = entry.getValue().getFirstPopulatedPage();
                if (page != null) {
                    return page;
                }
            }
        }
        return null;
    }

    /**
     * Returns all populated paged bitmap atlas pages for the given base font key.
     * Searches the paged bitmap atlas maps keyed by raster font key.
     *
     * @return unmodifiable list of populated bitmap atlas pages (may be empty, never null)
     */
    public List<CgGlyphAtlasPage> findAllPopulatedPagedBitmapPages(CgFontKey key) {
        List<CgGlyphAtlasPage> result = new ArrayList<CgGlyphAtlasPage>();
        for (Map.Entry<CgRasterFontKey, CgPagedGlyphAtlas> entry : pagedBitmapAtlases.entrySet()) {
            CgRasterFontKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgPagedGlyphAtlas pagedAtlas = entry.getValue();
                if (!pagedAtlas.isDeleted()) {
                    for (CgGlyphAtlasPage page : pagedAtlas.getPages()) {
                        if (page.getSlotCount() > 0 && !page.isDeleted()) {
                            result.add(page);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns all populated paged MSDF atlas pages for the given base font key.
     * Searches the paged MSDF atlas maps keyed by raster font key.
     *
     * @return unmodifiable list of populated MSDF atlas pages (may be empty, never null)
     */
    public List<CgGlyphAtlasPage> findAllPopulatedPagedMsdfPages(CgFontKey key) {
        List<CgGlyphAtlasPage> result = new ArrayList<CgGlyphAtlasPage>();
        for (Map.Entry<CgMsdfAtlasKey, CgPagedGlyphAtlas> entry : pagedMsdfAtlases.entrySet()) {
            CgMsdfAtlasKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgPagedGlyphAtlas pagedAtlas = entry.getValue();
                if (!pagedAtlas.isDeleted()) {
                    for (CgGlyphAtlasPage page : pagedAtlas.getPages()) {
                        if (page.getSlotCount() > 0 && !page.isDeleted()) {
                            result.add(page);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ────────────────────────────────────────────────────────────────────
    //  § 12. Font registration & atlas cleanup
    //
    //  Manages the lifecycle link between CgFont instances and their atlas
    //  resources.  When a font is disposed, all associated paged atlases are
    //  released, and pending async jobs for that font are cleared.
    // ────────────────────────────────────────────────────────────────────

    private void registerFont(final CgFont font) {
        final CgFontKey fontKey = font.getKey();
        if (registeredFonts.add(fontKey)) {
            font.setDisposeListener(new Runnable() {
                @Override
                public void run() {
                    releaseFontAtlases(fontKey);
                    registeredFonts.remove(fontKey);
                }
            });
        }
    }

    /**
     * Releases all atlas resources associated with the given font key.
     *
     * <p>Clears any pending/failed async jobs for the font, then deletes
     * and removes all paged atlases.</p>
     */
    public void releaseFontAtlases(CgFontKey key) {
        glyphGenerationExecutor.clearFont(key);
        releasePagedAtlasesForFont(key);
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
        for (CgPagedGlyphAtlas atlas : pagedBitmapAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        pagedBitmapAtlases.clear();

        for (CgPagedGlyphAtlas atlas : pagedMsdfAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        pagedMsdfAtlases.clear();

        registeredFonts.clear();
        glyphGenerationExecutor.shutdown();
        glyphGenerationExecutor = new CgGlyphGenerationExecutor();
    }

    private void releasePagedAtlasesForFont(CgFontKey baseKey) {
        java.util.Iterator<Map.Entry<CgRasterFontKey, CgPagedGlyphAtlas>> pagedBitmapIt =
                pagedBitmapAtlases.entrySet().iterator();
        while (pagedBitmapIt.hasNext()) {
            Map.Entry<CgRasterFontKey, CgPagedGlyphAtlas> entry = pagedBitmapIt.next();
            if (entry.getKey().getBaseFontKey().equals(baseKey)) {
                if (!entry.getValue().isDeleted()) {
                    entry.getValue().delete();
                }
                pagedBitmapIt.remove();
            }
        }

        java.util.Iterator<Map.Entry<CgMsdfAtlasKey, CgPagedGlyphAtlas>> pagedMsdfIt =
                pagedMsdfAtlases.entrySet().iterator();
        while (pagedMsdfIt.hasNext()) {
            Map.Entry<CgMsdfAtlasKey, CgPagedGlyphAtlas> entry = pagedMsdfIt.next();
            if (entry.getKey().getBaseFontKey().equals(baseKey)) {
                if (!entry.getValue().isDeleted()) {
                    entry.getValue().delete();
                }
                pagedMsdfIt.remove();
            }
        }
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
