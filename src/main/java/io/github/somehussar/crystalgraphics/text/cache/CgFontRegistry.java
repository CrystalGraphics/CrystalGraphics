package io.github.somehussar.crystalgraphics.text.cache;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.msdfgen.FreeTypeIntegration;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import io.github.somehussar.crystalgraphics.text.atlas.CgPagedGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfGenerator;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;

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
 * calls into the registry to convert a {@link CgGlyphKey} into atlas-resident
 * pixel data (either a {@link CgAtlasRegion} or a {@link CgGlyphPlacement}).
 * The registry owns atlas textures, generation scheduling, and frame-tick
 * bookkeeping; the renderer only consumes the placement data and texture IDs.</p>
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
 *   <li><strong>Legacy single-page path</strong> &mdash; pre-paged entry points retained for
 *       backward-compatible and identity-scale rendering</li>
 *   <li><strong>Atlas inspection / enumeration</strong> &mdash; diagnostic utilities for the debug harness</li>
 *   <li><strong>Font registration &amp; atlas cleanup</strong> &mdash; dispose listener wiring and release helpers</li>
 *   <li><strong>Low-level utilities</strong> &mdash; bitmap buffer normalization, font state restore</li>
 * </ol>
 *
 * <h3>Physical Raster Space Only</h3>
 * <p>All glyph metrics stored in atlas regions ({@link CgAtlasRegion}) are in
 * <strong>physical raster space</strong> &mdash; bearings, widths, and heights are
 * captured at the effective raster size, not in logical layout units.  The
 * registry must never normalize these values into logical space; that
 * responsibility belongs exclusively to the renderer boundary
 * ({@code CgTextRenderer.appendQuads}).</p>
 *
 * <h3>Effective-Size-Aware Lookup</h3>
 * <p>When text is rendered under a PoseStack transform, the effective physical
 * raster size may differ from the base {@code CgFontKey.targetPx}.  The registry
 * supports this via {@link CgRasterFontKey}-keyed atlas maps that allow the same
 * logical font to have multiple atlas buckets at different raster sizes.  The
 * original {@link CgFontKey}-keyed accessors remain for backward compatibility
 * and identity-scale rendering.</p>
 *
 * <p>The registry also wires font disposal to atlas cleanup.  It is not thread
 * safe and must only be used on the render thread.</p>
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

    // Legacy single-page atlas maps (keyed by base CgFontKey — identity scale)
    private final Map<CgFontKey, CgGlyphAtlas> bitmapAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();
    private final Map<CgFontKey, CgGlyphAtlas> msdfAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();

    // Effective-size-aware single-page atlas maps (keyed by CgRasterFontKey)
    private final Map<CgRasterFontKey, CgGlyphAtlas> rasterBitmapAtlases = new HashMap<CgRasterFontKey, CgGlyphAtlas>();
    private final Map<CgRasterFontKey, CgGlyphAtlas> rasterMsdfAtlases = new HashMap<CgRasterFontKey, CgGlyphAtlas>();

    // Paged atlas maps — the active/authoritative path for new allocations.
    // Bitmap paged atlases are keyed by CgRasterFontKey (effective-size-aware);
    // MSDF paged atlases are keyed by CgMsdfAtlasKey (size-agnostic, config-aware).
    private final Map<CgRasterFontKey, CgPagedGlyphAtlas> pagedBitmapAtlases = new HashMap<CgRasterFontKey, CgPagedGlyphAtlas>();
    private final Map<CgMsdfAtlasKey, CgPagedGlyphAtlas> pagedMsdfAtlases = new HashMap<CgMsdfAtlasKey, CgPagedGlyphAtlas>();

    private final Set<CgFontKey> registeredFonts = new HashSet<CgFontKey>();
    private final CgMsdfGenerator msdfGenerator = new CgMsdfGenerator();
    private final CgGlyphGenerationExecutor glyphGenerationExecutor = new CgGlyphGenerationExecutor();

    /** Maximum number of async glyph results committed (uploaded) per frame tick. */
    private static final int MAX_COMMITS_PER_FRAME = 32;

    public CgFontRegistry() {
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

        // 2. Tick every atlas family (legacy + raster + paged).
        for (CgGlyphAtlas atlas : bitmapAtlases.values()) {
            atlas.tickFrame(frame);
        }
        for (CgGlyphAtlas atlas : msdfAtlases.values()) {
            atlas.tickFrame(frame);
        }
        for (CgGlyphAtlas atlas : rasterBitmapAtlases.values()) {
            atlas.tickFrame(frame);
        }
        for (CgGlyphAtlas atlas : rasterMsdfAtlases.values()) {
            atlas.tickFrame(frame);
        }
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
     * ({@code prequeueVisibleGlyphs}) to submit glyph generation jobs to the
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

    /**
     * Public convenience wrapper for {@link #queueGlyphPaged}.
     *
     * <p><strong>Compatibility note:</strong> this method exists so that code
     * outside the cache package (e.g. the debug harness) can pre-queue glyphs
     * without reaching into package-private infrastructure.  New code should
     * prefer calling {@link #queueGlyphPaged} directly when visible.</p>
     */
    public void queueGlyphPagedPublic(CgFont font,
                                      CgGlyphKey key,
                                      int effectiveTargetPx,
                                      int subPixelBucket,
                                      long currentFrame) {
        queueGlyphPaged(font, key, effectiveTargetPx, subPixelBucket, currentFrame);
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
     * Alias for {@link #toBitmapAtlasGlyphKey} used by the legacy single-page
     * path.  Kept as a separate method to avoid coupling the legacy callers to
     * the bitmap-specific name.
     */
    private CgGlyphKey toAtlasGlyphKey(CgRasterGlyphKey rasterGlyphKey) {
        return toBitmapAtlasGlyphKey(rasterGlyphKey);
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

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
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
    //  § 10. Legacy single-page path
    //
    //  These entry points predate the paged atlas system.  They are retained
    //  for backward compatibility (identity-scale rendering, single-atlas
    //  consumers, and the debug harness).
    //
    //  New code should use ensureGlyphPaged() / queueGlyphPaged() instead.
    //
    //  ──── COMPATIBILITY / TRANSITION CODE ────
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the atlas region for a glyph, creating and uploading it if needed.
     *
     * <p><strong>Legacy path:</strong> uses single-page atlases keyed by base
     * {@link CgFontKey}.  Bitmap keys go through the bitmap rasterization path.
     * MSDF keys try the MSDF path first and fall back to bitmap when generation
     * is unavailable or intentionally skipped.</p>
     *
     * @deprecated Prefer {@link #ensureGlyphPaged} for new code.
     */
    public CgAtlasRegion ensureGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot ensureGlyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        return key.isMsdf() ? ensureMsdfGlyph(font, key, currentFrame) : ensureBitmapGlyph(font, key, currentFrame);
    }

    /**
     * Ensures a glyph is available at the given effective raster size.
     *
     * <p><strong>Legacy path:</strong> uses single-page raster-keyed atlases.
     * This is the effective-size-aware entry point used by the PoseStack-aware
     * renderer path before paged atlases were available.</p>
     *
     * @deprecated Prefer {@link #ensureGlyphPaged} for new code.
     */
    public CgAtlasRegion ensureGlyphAtEffectiveSize(CgFont font,
                                             CgGlyphKey key,
                                             int effectiveTargetPx,
                                             int subPixelBucket,
                                             long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot ensureGlyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        CgRasterFontKey rasterFontKey = new CgRasterFontKey(key.getFontKey(), effectiveTargetPx);
        CgRasterGlyphKey rasterGlyphKey = new CgRasterGlyphKey(
                rasterFontKey, key.getGlyphId(), key.isMsdf(), subPixelBucket);

        if (key.isMsdf()) {
            return ensureMsdfGlyphAtEffectiveSize(font, key, rasterFontKey, rasterGlyphKey,
                    effectiveTargetPx, subPixelBucket, currentFrame);
        } else {
            return ensureBitmapGlyphAtEffectiveSize(font, key, rasterFontKey, rasterGlyphKey,
                    effectiveTargetPx, subPixelBucket, currentFrame);
        }
    }

    // ── Legacy single-page atlas accessors ─────────────────────────────

    public CgGlyphAtlas getBitmapAtlas(CgFontKey key) {
        CgGlyphAtlas atlas = bitmapAtlases.get(key);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(atlasSize, atlasSize, CgGlyphAtlas.Type.BITMAP);
            bitmapAtlases.put(key, atlas);
        }
        return atlas;
    }

    public CgGlyphAtlas getMsdfAtlas(CgFontKey key) {
        CgGlyphAtlas atlas = msdfAtlases.get(key);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(atlasSize, atlasSize, resolveMsdfAtlasConfig(key).resolveAtlasType());
            msdfAtlases.put(key, atlas);
        }
        return atlas;
    }

    /**
     * Returns the bitmap atlas for the given effective-size-aware raster key,
     * creating it if needed.
     */
    public CgGlyphAtlas getBitmapAtlas(CgRasterFontKey rasterKey) {
        CgGlyphAtlas atlas = rasterBitmapAtlases.get(rasterKey);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(atlasSize, atlasSize, CgGlyphAtlas.Type.BITMAP);
            rasterBitmapAtlases.put(rasterKey, atlas);
        }
        return atlas;
    }

    /**
     * Returns the MSDF atlas for the given effective-size-aware raster key,
     * creating it if needed.
     */
    public CgGlyphAtlas getMsdfAtlas(CgRasterFontKey rasterKey) {
        CgGlyphAtlas atlas = rasterMsdfAtlases.get(rasterKey);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(
                    atlasSize,
                    atlasSize,
                    resolveMsdfAtlasConfig(rasterKey.getBaseFontKey()).resolveAtlasType());
            rasterMsdfAtlases.put(rasterKey, atlas);
        }
        return atlas;
    }

    // ── Legacy single-page glyph ensure (bitmap) ───────────────────────

    private CgAtlasRegion ensureBitmapGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        CgGlyphAtlas atlas = getBitmapAtlas(key.getFontKey());
        CgAtlasRegion cached = atlas.get(key, currentFrame);
        if (cached != null) {
            return cached;
        }

        FTFace face = font.getFtFace();
        try {
            face.setPixelSizes(0, key.getFontKey().getTargetPx());

            int loadFlags = FTLoadFlags.FT_LOAD_DEFAULT;
            boolean subBucket = key.getSubPixelBucket() > 0 && key.getFontKey()
                                                          .getTargetPx() < CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX;
            if (subBucket) {
                loadFlags = FTLoadFlags.FT_LOAD_NO_BITMAP;
            }

            loadGlyphOrFallback(face, key.getGlyphId(), loadFlags);

            if (subBucket) {
                face.outlineTranslate(key.getSubPixelBucket() * 16L, 0L);
            }

            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width == 0 || height == 0) {
                return new CgAtlasRegion(0, 0, 0, 0, 0, 0, 0, 0, key, 0, 0, 0, 0);
            }

            byte[] pixels = normalizeBitmapBuffer(bitmap);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            float bearingX = metrics.getHoriBearingX() / 64.0f;
            float bearingY = metrics.getHoriBearingY() / 64.0f;
            float metricsWidth = metrics.getWidth() / 64.0f;
            float metricsHeight = metrics.getHeight() / 64.0f;
            return atlas.getOrAllocate(key, pixels, width, height, bearingX, bearingY,
                    metricsWidth, metricsHeight, currentFrame);
        } catch (FreeTypeException e) {
            LOGGER.log(Level.WARNING, "Failed to rasterize glyph " + key, e);
            return null;
        } finally {
            restoreFontShapingState(font);
        }
    }

    // ── Legacy single-page glyph ensure (MSDF with bitmap fallback) ───

    private CgAtlasRegion ensureMsdfGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        CgGlyphAtlas msdfAtlas = getMsdfAtlas(key.getFontKey());
        CgAtlasRegion cached = msdfAtlas.get(key, currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(key.getFontKey());
            CgAtlasRegion region = msdfGenerator.queueOrGenerate(
                    key, msdfFont, msdfAtlas, config, currentFrame);
            if (region != null) {
                return region;
            }
        }

        CgGlyphKey bitmapKey = new CgGlyphKey(key.getFontKey(), key.getGlyphId(), false, key.getSubPixelBucket());
        return ensureBitmapGlyph(font, bitmapKey, currentFrame);
    }

    // ── Legacy effective-size single-page glyph ensure ─────────────────

    private CgAtlasRegion ensureBitmapGlyphAtEffectiveSize(CgFont font, CgGlyphKey key,
                                                             CgRasterFontKey rasterFontKey,
                                                             CgRasterGlyphKey rasterGlyphKey,
                                                             int effectiveTargetPx,
                                                             int subPixelBucket,
                                                            long currentFrame) {
        CgGlyphAtlas atlas = getBitmapAtlas(rasterFontKey);
        CgAtlasRegion cached = atlas.get(toAtlasGlyphKey(rasterGlyphKey), currentFrame);
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

            loadGlyphOrFallback(face, key.getGlyphId(), loadFlags);

            if (subBucket) {
                face.outlineTranslate(subPixelBucket * 16L, 0L);
            }

            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width == 0 || height == 0) {
                return new CgAtlasRegion(0, 0, 0, 0, 0, 0, 0, 0,
                        toAtlasGlyphKey(rasterGlyphKey), 0, 0, 0, 0);
            }

            byte[] pixels = normalizeBitmapBuffer(bitmap);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            float bearingX = metrics.getHoriBearingX() / 64.0f;
            float bearingY = metrics.getHoriBearingY() / 64.0f;

            // Compute placement metrics at base size to avoid hinting-rounding
            // drift when scaling back from effective size to logical space.
            // The glyph bitmap uses effectiveTargetPx for raster quality, but
            // placement extents must represent the true logical glyph dimensions.
            int basePx = key.getFontKey().getTargetPx();
            float metricsWidth;
            float metricsHeight;
            if (effectiveTargetPx != basePx) {
                face.setPixelSizes(0, basePx);
                loadGlyphOrFallback(face, key.getGlyphId(), FTLoadFlags.FT_LOAD_DEFAULT);
                FTGlyphMetrics baseMetrics = face.getGlyphMetrics();
                metricsWidth = baseMetrics.getWidth() / 64.0f;
                metricsHeight = baseMetrics.getHeight() / 64.0f;
                bearingX = baseMetrics.getHoriBearingX() / 64.0f;
                bearingY = baseMetrics.getHoriBearingY() / 64.0f;
            } else {
                metricsWidth = metrics.getWidth() / 64.0f;
                metricsHeight = metrics.getHeight() / 64.0f;
            }
            return atlas.getOrAllocate(toAtlasGlyphKey(rasterGlyphKey), pixels, width, height,
                    bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
        } catch (FreeTypeException e) {
            LOGGER.log(Level.WARNING, "Failed to rasterize glyph at effective size " + effectiveTargetPx + ": " + key, e);
            return null;
        } finally {
            restoreFontShapingState(font);
        }
    }

    private CgAtlasRegion ensureMsdfGlyphAtEffectiveSize(CgFont font, CgGlyphKey key,
                                                           CgRasterFontKey rasterFontKey,
                                                           CgRasterGlyphKey rasterGlyphKey,
                                                           int effectiveTargetPx,
                                                           int subPixelBucket,
                                                           long currentFrame) {
        CgGlyphAtlas msdfAtlas = getMsdfAtlas(rasterFontKey);
        CgAtlasRegion cached = msdfAtlas.get(toAtlasGlyphKey(rasterGlyphKey), currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            CgMsdfAtlasConfig config = resolveMsdfAtlasConfig(key.getFontKey());
            CgAtlasRegion region = msdfGenerator.queueOrGenerate(
                    toAtlasGlyphKey(rasterGlyphKey), msdfFont, msdfAtlas, config, currentFrame);
            if (region != null) {
                return region;
            }
        }

        CgGlyphKey bitmapKey = new CgGlyphKey(key.getFontKey(), key.getGlyphId(), false);
        return ensureBitmapGlyphAtEffectiveSize(font, bitmapKey, rasterFontKey,
                new CgRasterGlyphKey(rasterFontKey, bitmapKey.getGlyphId(), false, subPixelBucket),
                effectiveTargetPx, subPixelBucket, currentFrame);
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
     * Returns the first non-empty bitmap atlas that was populated during rendering
     * for the given base font key. Searches raster-keyed atlases (used by the
     * PoseStack-aware renderer path) and falls back to the identity-scale atlas.
     *
     * @return a populated bitmap atlas, or null if none exists
     */
    public CgGlyphAtlas findPopulatedBitmapAtlas(CgFontKey key) {
        // Check raster-keyed atlases first (populated by ensureGlyphAtEffectiveSize)
        for (Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry : rasterBitmapAtlases.entrySet()) {
            CgRasterFontKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgGlyphAtlas atlas = entry.getValue();
                if (!atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
                    return atlas;
                }
            }
        }
        // Fall back to identity-scale atlas
        CgGlyphAtlas atlas = bitmapAtlases.get(key);
        if (atlas != null && !atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
            return atlas;
        }
        return null;
    }

    /**
     * Returns the first non-empty MSDF atlas that was populated during rendering
     * for the given base font key. Searches raster-keyed atlases (used by the
     * PoseStack-aware renderer path) and falls back to the identity-scale atlas.
     *
     * @return a populated MSDF atlas, or null if none exists
     */
    public CgGlyphAtlas findPopulatedMsdfAtlas(CgFontKey key) {
        // Check raster-keyed atlases first (populated by ensureGlyphAtEffectiveSize)
        for (Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry : rasterMsdfAtlases.entrySet()) {
            CgRasterFontKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgGlyphAtlas atlas = entry.getValue();
                if (!atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
                    return atlas;
                }
            }
        }
        // Fall back to identity-scale atlas
        CgGlyphAtlas atlas = msdfAtlases.get(key);
        if (atlas != null && !atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
            return atlas;
        }
        return null;
    }

    /**
     * Returns all non-empty bitmap atlases for the given base font key.
     * Searches both raster-keyed atlases and the identity-scale atlas.
     * The returned list is ordered: raster-keyed first, then identity-scale.
     *
     * <p>Used by the harness multi-page dump path to enumerate every atlas page
     * that was populated during rendering.</p>
     *
     * @return unmodifiable list of populated bitmap atlases (may be empty, never null)
     */
    public List<CgGlyphAtlas> findAllPopulatedBitmapAtlases(CgFontKey key) {
        List<CgGlyphAtlas> result = new ArrayList<CgGlyphAtlas>();
        for (Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry : rasterBitmapAtlases.entrySet()) {
            CgRasterFontKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgGlyphAtlas atlas = entry.getValue();
                if (!atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
                    result.add(atlas);
                }
            }
        }
        CgGlyphAtlas identity = bitmapAtlases.get(key);
        if (identity != null && !identity.isDeleted() && identity.getTextureId() != 0
                && identity.getSlotCount() > 0 && !result.contains(identity)) {
            result.add(identity);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns all non-empty MSDF atlases for the given base font key.
     * Searches both raster-keyed atlases and the identity-scale atlas.
     * The returned list is ordered: raster-keyed first, then identity-scale.
     *
     * <p>Used by the harness multi-page dump path to enumerate every atlas page
     * that was populated during rendering.</p>
     *
     * @return unmodifiable list of populated MSDF atlases (may be empty, never null)
     */
    public List<CgGlyphAtlas> findAllPopulatedMsdfAtlases(CgFontKey key) {
        List<CgGlyphAtlas> result = new ArrayList<CgGlyphAtlas>();
        for (Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry : rasterMsdfAtlases.entrySet()) {
            CgRasterFontKey rk = entry.getKey();
            if (rk.getBaseFontKey().equals(key)) {
                CgGlyphAtlas atlas = entry.getValue();
                if (!atlas.isDeleted() && atlas.getTextureId() != 0 && atlas.getSlotCount() > 0) {
                    result.add(atlas);
                }
            }
        }
        CgGlyphAtlas identity = msdfAtlases.get(key);
        if (identity != null && !identity.isDeleted() && identity.getTextureId() != 0
                && identity.getSlotCount() > 0 && !result.contains(identity)) {
            result.add(identity);
        }
        return Collections.unmodifiableList(result);
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
    //  resources.  When a font is disposed, all associated atlases (legacy,
    //  raster-keyed, and paged) are released, and pending async jobs for
    //  that font are cleared.
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
     * and removes all legacy, raster-keyed, and paged atlases.</p>
     */
    public void releaseFontAtlases(CgFontKey key) {
        glyphGenerationExecutor.clearFont(key);
        CgGlyphAtlas bitmap = bitmapAtlases.remove(key);
        if (bitmap != null && !bitmap.isDeleted()) {
            bitmap.delete();
        }

        CgGlyphAtlas msdf = msdfAtlases.remove(key);
        if (msdf != null && !msdf.isDeleted()) {
            msdf.delete();
        }

        // Also release any effective-size-aware atlases for this base font
        releaseRasterAtlasesForFont(key);
        releasePagedAtlasesForFont(key);
    }

    /**
     * Releases all atlas resources across all fonts and shuts down the
     * background generation executor.
     *
     * <p>Called during renderer/system shutdown.</p>
     */
    public void releaseAll() {
        for (CgGlyphAtlas atlas : bitmapAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        bitmapAtlases.clear();

        for (CgGlyphAtlas atlas : msdfAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        msdfAtlases.clear();

        for (CgGlyphAtlas atlas : rasterBitmapAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        rasterBitmapAtlases.clear();

        for (CgGlyphAtlas atlas : rasterMsdfAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        rasterMsdfAtlases.clear();

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
    }

    private void releaseRasterAtlasesForFont(CgFontKey baseKey) {
        java.util.Iterator<Map.Entry<CgRasterFontKey, CgGlyphAtlas>> bitmapIt =
                rasterBitmapAtlases.entrySet().iterator();
        while (bitmapIt.hasNext()) {
            Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry = bitmapIt.next();
            if (entry.getKey().getBaseFontKey().equals(baseKey)) {
                if (!entry.getValue().isDeleted()) {
                    entry.getValue().delete();
                }
                bitmapIt.remove();
            }
        }

        java.util.Iterator<Map.Entry<CgRasterFontKey, CgGlyphAtlas>> msdfIt =
                rasterMsdfAtlases.entrySet().iterator();
        while (msdfIt.hasNext()) {
            Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry = msdfIt.next();
            if (entry.getKey().getBaseFontKey().equals(baseKey)) {
                if (!entry.getValue().isDeleted()) {
                    entry.getValue().delete();
                }
                msdfIt.remove();
            }
        }
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
