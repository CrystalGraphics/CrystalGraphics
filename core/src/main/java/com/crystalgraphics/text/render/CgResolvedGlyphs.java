package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.cache.CgFontRegistry;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves one {@code CgTextRenderer.drawInternal} call's {@link CgTextLayout} into flat,
 * per-glyph atlas-placement data — font key/font, glyph id, subpixel bucket, pen position,
 * and finally each glyph's {@link CgGlyphPlacement}. Pure layout/atlas-cache concern: nothing
 * here touches GL, {@code CgQuadRenderer}, or {@code CgMaterial} — that boundary starts once
 * {@code CgTextRenderer} reads {@link #glyphX}/{@link #glyphY}/{@link #placements} back out.
 *
 * <p>Owned by a single {@code CgTextRenderer} instance (not shared/static) and reused across
 * draws: {@link #flattenAndPrequeue} grows the backing arrays via {@link #ensureCapacity} but
 * never shrinks them, so steady-state text (the common case — the same HUD/labels every
 * frame) allocates nothing per draw. Indices {@code [0, glyphCount)} from the most recent
 * {@link #flattenAndPrequeue} call stay valid until the next call overwrites them.</p>
 *
 * <h3>Two-phase resolution</h3>
 * <p>{@link #flattenAndPrequeue} walks the {@link CgTextLayout} line/run/glyph tree exactly
 * once — filling every field except {@link #placements} — and prequeues each glyph for async
 * atlas generation. {@link #resolvePlacements} then does the synchronous atlas lookup into
 * {@link #placements} as a separate step, so its MSDF→bitmap fallback retry (see its javadoc)
 * can re-run just the flat lookup loop without ever re-walking the layout tree.</p>
 */
final class CgResolvedGlyphs {

    private final CgFontRegistry registry;

    private CgFontKey[] fontKeys = new CgFontKey[0];
    private CgFont[] fonts = new CgFont[0];
    private int[] glyphIds = new int[0];
    private int[] subPixel = new int[0];

    /** Pen position for glyph {@code i}, valid for {@code [0, glyphCount)} — read by {@code CgTextRenderer}. */
    float[] glyphX = new float[0];
    float[] glyphY = new float[0];
    /** Resolved atlas placement for glyph {@code i}, filled by {@link #resolvePlacements}. */
    CgGlyphPlacement[] placements = new CgGlyphPlacement[0];

    CgResolvedGlyphs(CgFontRegistry registry) {
        this.registry = registry;
    }

    private void ensureCapacity(int count) {
        if (count <= fontKeys.length) return;
        int newCap = Math.max(count, fontKeys.length * 2);
        fontKeys = Arrays.copyOf(fontKeys, newCap);
        fonts = Arrays.copyOf(fonts, newCap);
        glyphIds = Arrays.copyOf(glyphIds, newCap);
        subPixel = Arrays.copyOf(subPixel, newCap);
        glyphX = Arrays.copyOf(glyphX, newCap);
        glyphY = Arrays.copyOf(glyphY, newCap);
        placements = Arrays.copyOf(placements, newCap);
    }

    private static CgFont resolveRunFont(CgTextLayout layout, CgFontFamily family, CgFontKey runFontKey) {
        CgFont resolvedFromLayout = layout.getResolvedFontsByKey().get(runFontKey);
        if (resolvedFromLayout != null) return resolvedFromLayout;

        return family.resolveLoadedFont(runFontKey);
    }

    private static int countGlyphs(List<List<CgShapedRun>> lines) {
        int total = 0;
        for (List<CgShapedRun> line : lines) for (CgShapedRun run : line) total += run.getGlyphIds().length;

        return total;
    }

    /**
     * Single walk of the layout tree: flattens every glyph's font key/font/id/subpixel
     * bucket/pen position into this instance's arrays, then prequeues each for async atlas
     * generation.
     *
     * <p>This is the only place the {@code CgTextLayout} line/run/glyph tree gets walked.
     * {@link #resolvePlacements}'s MSDF→bitmap fallback retry re-runs only the flat
     * atlas-lookup loop over these already-flattened arrays — never this walk again.</p>
     *
     * @return the number of glyphs flattened (may be 0 for an all-whitespace layout)
     */
    int flattenAndPrequeue(CgTextLayout layout, CgFontFamily family, float x, float y, long frame,
                           CgTextRenderContext context, int effectiveTargetPx, boolean wantMsdf, CgFontMetrics metrics) {
        List<List<CgShapedRun>> lines = layout.getLines();
        int totalGlyphs = countGlyphs(lines);
        ensureCapacity(totalGlyphs);

        int index = 0;
        float penY = y + metrics.getAscender();
        for (List<CgShapedRun> line : lines) {
            float penX = x;
            for (CgShapedRun run : line) {
                CgFontKey runFontKey = run.getFontKey();
                CgFont runFont = resolveRunFont(layout, family, runFontKey);
                int[] glyphIdsArr = run.getGlyphIds();
                float[] advancesX = run.getAdvancesX();
                float[] offsetsX = run.getOffsetsX();
                float[] offsetsY = run.getOffsetsY();
                for (int i = 0; i < glyphIdsArr.length; i++) {
                    fontKeys[index] = runFontKey;
                    fonts[index] = runFont;
                    glyphIds[index] = glyphIdsArr[i];
                    subPixel[index] = resolveSubPixelBucket(context, runFontKey, effectiveTargetPx, offsetsX[i]);
                    glyphX[index] = penX + offsetsX[i];
                    glyphY[index] = penY + offsetsY[i];
                    penX += advancesX[i];
                    index++;
                }
            }
            penY += metrics.getLineHeight();
        }

        // Latency-hiding only, not correctness — the ensure pass in resolvePlacements still
        // defines the frame's authoritative result. Queueing every glyph before any ensure
        // call gives worker threads a head start on the whole batch.
        for (int i = 0; i < totalGlyphs; i++) {
            CgGlyphKey glyphKey = new CgGlyphKey(fontKeys[i], glyphIds[i], wantMsdf, subPixel[i]);
            registry.queueGlyphPaged(fonts[i], glyphKey, effectiveTargetPx, subPixel[i], frame);
        }

        return totalGlyphs;
    }

    /**
     * Resolves atlas placements for the {@code glyphCount} glyphs already flattened by
     * {@link #flattenAndPrequeue}, into {@link #placements}.
     *
     * <p>If a draw prefers distance fields but any visible glyph had to fall back to
     * bitmap this frame, reruns just this atlas-lookup loop in bitmap mode so one draw
     * never mixes bitmap and distance-field quality tiers — no re-walk of the layout tree.</p>
     */
    void resolvePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf) {
        boolean usedBitmapFallback = ensurePlacements(glyphCount, frame, effectiveTargetPx, wantMsdf);
        if (wantMsdf && usedBitmapFallback) {
            ensurePlacements(glyphCount, frame, effectiveTargetPx, false);
        }
    }

    /** @return true if any glyph in an MSDF-targeted pass fell back to bitmap */
    private boolean ensurePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf) {
        boolean usedBitmapFallback = false;
        for (int i = 0; i < glyphCount; i++) {
            CgGlyphKey glyphKey = new CgGlyphKey(fontKeys[i], glyphIds[i], wantMsdf, subPixel[i]);
            CgGlyphPlacement placement = registry.ensureGlyphPaged(fonts[i], glyphKey, effectiveTargetPx, subPixel[i], frame);
            placements[i] = placement;
            if (wantMsdf && placement != null && !placement.isDistanceField()) usedBitmapFallback = true;
        }
        return usedBitmapFallback;
    }

    /**
     * Converts physical atlas metrics back into logical placement units.
     *
     * <p>Text is shaped and advanced in logical/base units, but glyphs may be rasterized at
     * a larger or smaller effective physical size due to UI scale. Placement must therefore
     * normalize raster-time bearings and extents back into logical space before combining
     * them with pen positions.</p>
     */
    static float logicalMetricScale(int baseTargetPx, int effectiveTargetPx) {
        if (baseTargetPx <= 0) throw new IllegalArgumentException("baseTargetPx must be > 0");
        if (effectiveTargetPx <= 0) throw new IllegalArgumentException("effectiveTargetPx must be > 0");

        return (float) baseTargetPx / (float) effectiveTargetPx;
    }

    /**
     * Selects the sub-pixel bucket based on the effective target pixel size.
     * Uses the effective size (not base targetPx) because the effective size
     * determines whether sub-pixel positioning is perceptible.
     */
    private static int selectSubPixelBucket(int effectiveTargetPx, float xOffset) {
        if (effectiveTargetPx >= CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX) return 0;

        float fractional = xOffset - (float) Math.floor(xOffset);
        if (fractional < 0.125f) return 0;
        if (fractional < 0.375f) return 1;
        if (fractional < 0.625f) return 2;
        if (fractional < 0.875f) return 3;

        return 0;
    }

    private static int resolveSubPixelBucket(CgTextRenderContext context, CgFontKey fontKey, int effectiveTargetPx, float xOffset) {
        if (context.isWorldText()) return 0;
        if (context.isScaledUiRaster(fontKey, effectiveTargetPx)) return 0;

        return selectSubPixelBucket(effectiveTargetPx, xOffset);
    }
}
