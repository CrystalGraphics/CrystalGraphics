package io.github.somehussar.crystalgraphics.gl.text;

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
import io.github.somehussar.crystalgraphics.gl.text.atlas.CgGlyphAtlasPage;
import io.github.somehussar.crystalgraphics.gl.text.atlas.CgPagedGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;

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
 * Render-thread font cache for glyph atlas allocation.
 *
 * <p>Each {@link CgFontKey} gets a bitmap atlas and an MSDF atlas on demand.
 * Bitmap glyphs are rasterized through FreeType and uploaded into the bitmap
 * atlas. MSDF glyphs are generated through {@link CgMsdfGenerator} and stored
 * in the MSDF atlas, with bitmap fallback when MSDF is unavailable or skipped.</p>
 *
 * <h3>Physical Raster Space Only</h3>
 * <p>All glyph metrics stored in atlas regions ({@link CgAtlasRegion}) are in
 * <strong>physical raster space</strong> — bearings, widths, and heights are
 * captured at the effective raster size, not in logical layout units. The
 * registry must never normalize these values into logical space; that
 * responsibility belongs exclusively to the renderer boundary
 * ({@code CgTextRenderer.appendQuads}).</p>
 *
 * <h3>Effective-Size-Aware Lookup</h3>
 * <p>When text is rendered under a PoseStack transform, the effective physical
 * raster size may differ from the base {@code CgFontKey.targetPx}. The registry
 * supports this via {@link CgRasterFontKey}-keyed atlas maps that allow the same
 * logical font to have multiple atlas buckets at different raster sizes. The
 * original {@link CgFontKey}-keyed accessors remain for backward compatibility
 * and identity-scale rendering.</p>
 *
 * <p>The registry also wires font disposal to atlas cleanup. It is not thread
 * safe and must only be used on the render thread.</p>
 */
public class CgFontRegistry {

    private static final Logger LOGGER = Logger.getLogger(CgFontRegistry.class.getName());
    private static final int DEFAULT_BITMAP_ATLAS_SIZE = 1024;

    private final int atlasSize;
    private final CgMsdfAtlasConfig msdfAtlasConfig;
    private final Map<CgFontKey, CgGlyphAtlas> bitmapAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();
    private final Map<CgFontKey, CgGlyphAtlas> msdfAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();
    // Effective-size-aware atlas maps keyed by CgRasterFontKey
    private final Map<CgRasterFontKey, CgGlyphAtlas> rasterBitmapAtlases = new HashMap<CgRasterFontKey, CgGlyphAtlas>();
    private final Map<CgRasterFontKey, CgGlyphAtlas> rasterMsdfAtlases = new HashMap<CgRasterFontKey, CgGlyphAtlas>();
    // Paged atlas maps — the active path for new allocations
    private final Map<CgRasterFontKey, CgPagedGlyphAtlas> pagedBitmapAtlases = new HashMap<CgRasterFontKey, CgPagedGlyphAtlas>();
    private final Map<CgMsdfAtlasKey, CgPagedGlyphAtlas> pagedMsdfAtlases = new HashMap<CgMsdfAtlasKey, CgPagedGlyphAtlas>();
    private final Set<CgFontKey> registeredFonts = new HashSet<CgFontKey>();
    private final CgMsdfGenerator msdfGenerator = new CgMsdfGenerator();

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

    /**
     * Returns the atlas region for a glyph, creating and uploading it if needed.
     *
     * <p>Bitmap keys go through the bitmap rasterization path. MSDF keys try the
     * MSDF path first and fall back to bitmap when generation is unavailable or
     * intentionally skipped.</p>
     */
    public CgAtlasRegion ensureGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot ensureGlyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        return key.isMsdf() ? ensureMsdfGlyph(font, key, currentFrame) : ensureBitmapGlyph(font, key, currentFrame);
    }

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
            atlas = CgGlyphAtlas.create(atlasSize, atlasSize, CgGlyphAtlas.Type.MSDF);
            msdfAtlases.put(key, atlas);
        }
        return atlas;
    }

    /**
     * Returns the bitmap atlas for the given effective-size-aware raster key,
     * creating it if needed.
     */
    CgGlyphAtlas getBitmapAtlas(CgRasterFontKey rasterKey) {
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
    CgGlyphAtlas getMsdfAtlas(CgRasterFontKey rasterKey) {
        CgGlyphAtlas atlas = rasterMsdfAtlases.get(rasterKey);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(atlasSize, atlasSize, CgGlyphAtlas.Type.MSDF);
            rasterMsdfAtlases.put(rasterKey, atlas);
        }
        return atlas;
    }

    /**
     * Ensures a glyph is available at the given effective raster size.
     *
     * <p>This is the effective-size-aware entry point used by the PoseStack-aware
     * renderer path. It rasterizes the glyph at {@code effectiveTargetPx} rather
     * than at the base {@code CgFontKey.targetPx}.</p>
     */
    CgAtlasRegion ensureGlyphAtEffectiveSize(CgFont font,
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

    /**
     * Paged-atlas entry point: ensures a glyph is available and returns a
     * {@link CgGlyphPlacement} directly from the paged atlas.
     *
     * <p>This is the primary path for the multi-page atlas system. MSDF glyphs
     * use upstream-parity layout via {@link CgMsdfGenerator#queueOrGeneratePaged}.
     * Bitmap glyphs are rasterized via FreeType and allocated into paged bitmap
     * atlases. Falls back to bitmap when MSDF generation is skipped.</p>
     */
    CgGlyphPlacement ensureGlyphPaged(CgFont font,
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
            return ensureMsdfGlyphPaged(font, atlasKey, msdfAtlasKey, effectiveTargetPx, currentFrame);
        } else {
            CgGlyphKey atlasKey = toBitmapAtlasGlyphKey(
                    new CgRasterGlyphKey(rasterFontKey, key.getGlyphId(), false, subPixelBucket));
            return ensureBitmapGlyphPaged(font, atlasKey, rasterFontKey, effectiveTargetPx, subPixelBucket, currentFrame);
        }
    }

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
                    CgGlyphAtlas.Type.MSDF,
                    atlasKey.getConfig().getSpacingPx());
            pagedMsdfAtlases.put(atlasKey, atlas);
        }
        return atlas;
    }

    public void tickFrame(long frame) {
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
        msdfGenerator.tickFrame();
    }

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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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
            if (rk.getFontPath().equals(key.getFontPath()) && rk.getStyle() == key.getStyle()) {
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

    public void releaseFontAtlases(CgFontKey key) {
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
    }

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

    private CgAtlasRegion ensureMsdfGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        CgGlyphAtlas msdfAtlas = getMsdfAtlas(key.getFontKey());
        CgAtlasRegion cached = msdfAtlas.get(key, currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            CgAtlasRegion region = msdfGenerator.queueOrGenerate(
                    key, msdfFont, msdfAtlas, 0f, 0f, currentFrame);
            if (region != null) {
                return region;
            }
        }

        CgGlyphKey bitmapKey = new CgGlyphKey(key.getFontKey(), key.getGlyphId(), false, key.getSubPixelBucket());
        return ensureBitmapGlyph(font, bitmapKey, currentFrame);
    }

    private void loadGlyphOrFallback(FTFace face, int glyphIndex, int loadFlags) throws FreeTypeException {
        try {
            face.loadGlyph(glyphIndex, loadFlags);
        } catch (FreeTypeException e) {
            LOGGER.fine("Glyph " + glyphIndex + " not found, falling back to .notdef");
            face.loadGlyph(0, loadFlags);
        }
    }

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
            CgAtlasRegion region = msdfGenerator.queueOrGenerate(
                    toAtlasGlyphKey(rasterGlyphKey), msdfFont, msdfAtlas, 0f, 0f, currentFrame);
            if (region != null) {
                return region;
            }
        }

        CgGlyphKey bitmapKey = new CgGlyphKey(key.getFontKey(), key.getGlyphId(), false);
        return ensureBitmapGlyphAtEffectiveSize(font, bitmapKey, rasterFontKey,
                new CgRasterGlyphKey(rasterFontKey, bitmapKey.getGlyphId(), false, subPixelBucket),
                effectiveTargetPx, subPixelBucket, currentFrame);
    }

    CgMsdfAtlasConfig resolveMsdfAtlasConfig(CgFontKey baseFontKey) {
        return msdfAtlasConfig;
    }

    CgMsdfAtlasKey toMsdfAtlasKey(CgFontKey baseFontKey, CgMsdfAtlasConfig config) {
        return new CgMsdfAtlasKey(baseFontKey, config);
    }

    CgGlyphKey toMsdfAtlasGlyphKey(CgGlyphKey requestedKey, CgMsdfAtlasConfig config) {
        CgFontKey atlasFontKey = new CgFontKey(
                requestedKey.getFontKey().getFontPath(),
                requestedKey.getFontKey().getStyle(),
                config.getAtlasScalePx());
        return new CgGlyphKey(atlasFontKey, requestedKey.getGlyphId(), true, 0);
    }

    CgGlyphKey toBitmapAtlasGlyphKey(CgRasterGlyphKey rasterGlyphKey) {
        CgFontKey atlasFontKey = new CgFontKey(
                rasterGlyphKey.getRasterFontKey().getFontPath(),
                rasterGlyphKey.getRasterFontKey().getStyle(),
                rasterGlyphKey.getRasterFontKey().getEffectiveTargetPx());
        return new CgGlyphKey(
                atlasFontKey,
                rasterGlyphKey.getGlyphId(),
                rasterGlyphKey.isMsdf(),
                rasterGlyphKey.getSubPixelBucket());
    }

    private CgGlyphKey toAtlasGlyphKey(CgRasterGlyphKey rasterGlyphKey) {
        return toBitmapAtlasGlyphKey(rasterGlyphKey);
    }

    private CgGlyphPlacement ensureMsdfGlyphPaged(CgFont font, CgGlyphKey atlasKey,
                                                   CgMsdfAtlasKey msdfAtlasKey,
                                                   int effectiveTargetPx,
                                                   long currentFrame) {
        CgPagedGlyphAtlas pagedAtlas = getPagedMsdfAtlas(msdfAtlasKey);
        CgGlyphPlacement cached = pagedAtlas.get(atlasKey, currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            CgGlyphPlacement placement = msdfGenerator.queueOrGeneratePaged(
                    atlasKey, msdfFont, pagedAtlas, msdfAtlasKey.getConfig(), currentFrame);
            if (placement != null) {
                return placement;
            }
        }

        // Fall back to bitmap via paged atlas
        CgRasterFontKey bitmapRasterKey = new CgRasterFontKey(font.getKey(), effectiveTargetPx);
        CgGlyphKey bitmapAtlasKey = toBitmapAtlasGlyphKey(
                new CgRasterGlyphKey(bitmapRasterKey, atlasKey.getGlyphId(), false, 0));
        return ensureBitmapGlyphPaged(font, bitmapAtlasKey, bitmapRasterKey,
                effectiveTargetPx, 0, currentFrame);
    }

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

    private void restoreFontShapingState(CgFont font) {
        try {
            font.restoreBaseFontSizeForShaping();
        } catch (FreeTypeException e) {
            throw new IllegalStateException("Failed to restore base font size for shaping: "
                    + font.getKey(), e);
        }
    }

    private void releaseRasterAtlasesForFont(CgFontKey baseKey) {
        java.util.Iterator<Map.Entry<CgRasterFontKey, CgGlyphAtlas>> bitmapIt =
                rasterBitmapAtlases.entrySet().iterator();
        while (bitmapIt.hasNext()) {
            Map.Entry<CgRasterFontKey, CgGlyphAtlas> entry = bitmapIt.next();
            if (entry.getKey().getFontPath().equals(baseKey.getFontPath())
                    && entry.getKey().getStyle() == baseKey.getStyle()) {
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
            if (entry.getKey().getFontPath().equals(baseKey.getFontPath())
                    && entry.getKey().getStyle() == baseKey.getStyle()) {
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
            if (entry.getKey().getFontPath().equals(baseKey.getFontPath())
                    && entry.getKey().getStyle() == baseKey.getStyle()) {
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
            if (entry.getKey().getFontPath().equals(baseKey.getFontPath())
                    && entry.getKey().getStyle() == baseKey.getStyle()) {
                if (!entry.getValue().isDeleted()) {
                    entry.getValue().delete();
                }
                pagedMsdfIt.remove();
            }
        }
    }

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
