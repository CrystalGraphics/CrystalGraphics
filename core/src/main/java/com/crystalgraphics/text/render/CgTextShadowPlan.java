package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgFontVariation;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.context.CgTextScaleResolver;
import com.crystalgraphics.text.shadow.CgMaskBlurFilter;
import com.crystalgraphics.text.shadow.CgShadowCell;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each glyph of one draw paints in each of its text shadows, and the cells those need.
 *
 * <pre>{@code
 * boolean deferred = plan.plan(shadows, placements, baked, glyphCount, fontKey, effectivePx, ...);
 * byte kind = plan.kind(shadow, glyph);                    // NONE, PLAIN, FIELD_OUTER, CELL or FIELD_INSET
 * CgGlyphPlacement painted = plan.placement(shadow, glyph);  // the glyph's own, or its cell
 * }</pre>
 *
 * <p>A sharp shadow of text with no stroke and no spread is the glyph itself in another colour, in the
 * text's own batch. A sharp shadow the distance field can still describe is a threshold on that field,
 * also in the text's batch. Everything else is a {@link CgShadowCell}: coverage built on a worker, blurred
 * with Skia's mask blur, and stored in the bitmap atlas, which is the one extra call a blurred shadow of
 * field text costs.</p>
 *
 * <p><b>A cell still building draws the cell that glyph's shadow last had.</b> Almost any change to a
 * shadow's blur, growth or size is a new cell, and one that painted nothing until a worker delivered it
 * made a dragged slider flicker the shadow off at every step. The previous cell, stretched over the glyph
 * at its own raster size, is a frame or two out of date and never absent.</p>
 *
 * <p><b>A blurred inset reads the field when the field can hold it.</b> An inset cell carries the glyph's
 * own outline as its clip, and a cell is a bitmap at the raster size, which says nothing about the size on
 * screen for world text (always distance-field, rasterised around the font's own size) and stops growing at
 * {@link CgTextScaleResolver#MAX_EFFECTIVE_PX} for UI text. Seen larger than that, the clip stair-steps
 * and its soft edge darkens the canvas just outside the crisp glyph. From an MTSDF field the clip is the
 * glyph's own edge at any size, and the hole is a Gaussian falloff of its true distance, exact along a
 * straight edge. The blur has to fit the field's reach: when it does not, world text and a capped UI raster
 * clamp it to fit, as a stroke is clamped, and other UI text keeps the exact cell.</p>
 */
final class CgTextShadowPlan {

    /** Nothing to paint for this glyph in this shadow: no geometry, transparent, or not built yet. */
    static final byte NONE = 0;
    /** The glyph's own placement in the shadow's colour: a sharp shadow of unstroked, unspread text. */
    static final byte PLAIN = 1;
    /** The glyph's distance field, thresholded around the stroke and the spread (text.shader kind -1). */
    static final byte FIELD_OUTER = 2;
    /** A worker-built cell from the bitmap atlas, blurred or grown or both (text.shader kind -3). */
    static final byte CELL = 3;
    /** The glyph's distance field, the canvas shadowing into it (text.shader kind -2). */
    static final byte FIELD_INSET = 4;

    private final CgFontRegistry registry;

    /** One byte per (shadow, glyph): which kind that glyph paints in that shadow. */
    private byte[] kinds = new byte[0];
    /** The placement each (shadow, glyph) paints. */
    private CgGlyphPlacement[] placements = new CgGlyphPlacement[0];
    /** Texels of spread per shadow, for the field kinds. */
    private float[] spreadTexels = new float[0];
    /** Per (shadow, glyph): the blur of a {@link #FIELD_INSET}, in atlas texels; 0 for a sharp one. */
    private float[] insetSigmaTexels = new float[0];
    private int glyphCount;

    /**
     * One glyph's shadow across frames, whatever blur, growth or size it is at: everything a slider can
     * move is left out, so the next cell and the last one share it.
     */
    private record Lineage(String fontPath, int faceIndex, CgFontStyle style, List<CgFontVariation> variations,
                           int glyphId, boolean bold, boolean italic, int shadow, boolean inset) {
    }

    private static final int MAX_LINEAGES = 4096;

    /** The last cell each lineage resolved to, least recently used first. Render thread only. */
    private final Map<Lineage, CgGlyphKey> lastCells = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Lineage, CgGlyphKey> eldest) {
            return size() > MAX_LINEAGES;
        }
    };

    CgTextShadowPlan(CgFontRegistry registry) {
        this.registry = registry;
    }

    /** Whether a local sigma blurs at all once the pose maps it to device pixels. */
    static boolean blurs(float sigmaLocal, float devicePerLocal) {
        return (double) sigmaLocal * devicePerLocal >= CgMaskBlurFilter.NO_BLUR_SIGMA;
    }

    /**
     * Plans every shadow of {@code shadows} over the draw's resolved glyphs.
     *
     * @param strokeWidthTexels the stroke the draw paints, 0 for none; a shadow's shape includes it
     * @param strokeAlign       the stroke's align code, as the renderer passes it to text.shader
     * @param worldText         a perspective draw, whose cells are stretched by however close the camera is
     * @return whether a cell was asked for and not built yet, so the draw is provisional
     */
    boolean plan(CgTextShadowList shadows, CgGlyphPlacement[] glyphs, CgBakedGlyphs baked, int glyphCount,
                 CgFontKey fontKey, int effectiveTargetPx, float strokeWidthTexels, float strokeAlign,
                 boolean worldText, long frame) {
        int n = shadows.count();
        int slots = n * glyphCount;
        if (kinds.length < slots) {
            int capacity = Math.max(slots, kinds.length * 2);
            kinds = new byte[capacity];
            placements = new CgGlyphPlacement[capacity];
            insetSigmaTexels = new float[capacity];
        }
        if (spreadTexels.length < n) spreadTexels = new float[Math.max(n, spreadTexels.length * 2)];
        this.glyphCount = glyphCount;

        int baseTargetPx = fontKey.getTargetPx();
        // Whether a cell can be magnified on screen: world text's raster never follows its size there, and a
        // UI raster at the cap may be drawn at any size above it.
        boolean rasterCapped = worldText || effectiveTargetPx >= CgTextScaleResolver.MAX_EFFECTIVE_PX;
        // Device pixels per local pixel, as the raster tier sees it.
        float devicePerLocal = effectiveTargetPx / (float) Math.max(1, baseTargetPx);
        float atlasScalePx = registry.getResolvedMsdfConfig(fontKey).atlasScalePx();
        float texelsPerLocal = atlasScalePx / Math.max(1, baseTargetPx);

        // Where the stroke puts its ring, in local px, for a shadow that includes it.
        boolean stroked = strokeWidthTexels > 0f;
        float strokeLocal = stroked ? strokeWidthTexels / texelsPerLocal : 0f;
        float outwardLocal = !stroked ? 0f
                : strokeAlign == CgTextRenderer.ALIGN_CENTER ? strokeLocal * 0.5f
                : strokeAlign == CgTextRenderer.ALIGN_INSET ? 0f : strokeLocal;
        float inwardLocal = strokeLocal - outwardLocal;

        boolean anyDeferred = false;
        for (int s = 0; s < n; s++) {
            int row = s * glyphCount;
            if (!shadows.casts(s)) {
                Arrays.fill(kinds, row, row + glyphCount, NONE);
                continue;
            }
            boolean inset = shadows.inset(s);
            float spreadLocal = shadows.spread(s);
            spreadTexels[s] = spreadLocal * texelsPerLocal;
            // Skia: sigma mapped by the transform; CgShadowCell caps it.
            double deviceSigma = (double) shadows.sigma(s) * devicePerLocal;
            boolean blurred = blurs(shadows.sigma(s), devicePerLocal);
            float sigmaTexels = shadows.sigma(s) * texelsPerLocal;

            CgShadowCell cell = null;
            for (int g = 0; g < glyphCount; g++) {
                CgGlyphPlacement p = glyphs[g];
                int slot = row + g;
                placements[slot] = null;
                insetSigmaTexels[slot] = 0f;
                if (p == null || !p.hasGeometry() || !shadows.appliesTo(s, g)) {
                    kinds[slot] = NONE;
                    continue;
                }
                byte kind;
                if (!blurred) {
                    float reachTexels = p.pxRange() * 0.5f - 1f;
                    float wantTexels = inset
                            ? inwardLocal * texelsPerLocal + spreadTexels[s]
                            : outwardLocal * texelsPerLocal + spreadTexels[s];
                    if (!inset && spreadLocal == 0f && !stroked) {
                        kind = PLAIN;
                    } else if (p.isDistanceField() && wantTexels <= reachTexels
                            // A spread reads the true distance, which only MTSDF's alpha carries.
                            && (spreadLocal == 0f || p.isMtsdf())) {
                        kind = inset ? FIELD_INSET : FIELD_OUTER;
                    } else {
                        kind = CELL;
                    }
                } else if (inset && p.isMtsdf()) {
                    float reachTexels = p.pxRange() * 0.5f - 1f;
                    float shrinkTexels = inwardLocal * texelsPerLocal + spreadTexels[s];
                    // Three sigma past the shrunk edge is where the Gaussian is spent.
                    float fitSigma = (reachTexels - shrinkTexels) / 3f;
                    if (sigmaTexels <= fitSigma) {
                        kind = FIELD_INSET;
                        insetSigmaTexels[slot] = sigmaTexels;
                    } else if (rasterCapped && fitSigma > 0f) {
                        kind = FIELD_INSET;
                        insetSigmaTexels[slot] = fitSigma;
                    } else {
                        kind = CELL;
                    }
                } else {
                    kind = CELL;
                }

                if (kind != CELL) {
                    kinds[slot] = kind;
                    placements[slot] = p;
                    continue;
                }
                if (cell == null) {
                    cell = inset
                            ? CgShadowCell.forInsetShadow(deviceSigma, (inwardLocal + spreadLocal) * devicePerLocal,
                                    inwardLocal * devicePerLocal, shadows.x(s) * devicePerLocal,
                                    shadows.y(s) * devicePerLocal)
                            : CgShadowCell.forOuterShadow(deviceSigma, (outwardLocal + spreadLocal) * devicePerLocal,
                                    effectiveTargetPx, registry.maxShadowCellPx());
                }
                CgFontKey glyphFont = baked.fontKeys()[g];
                CgGlyphPlacement resolved = registry.resolveShadowCell(baked.fonts()[g], glyphFont,
                        baked.glyphIds()[g], baked.syntheticBold()[g], baked.syntheticItalic()[g],
                        effectiveTargetPx, cell, frame);
                Lineage lineage = new Lineage(glyphFont.getFontPath(), glyphFont.getFaceIndex(), glyphFont.getStyle(),
                        glyphFont.getVariations(), baked.glyphIds()[g], baked.syntheticBold()[g],
                        baked.syntheticItalic()[g], s, inset);
                if (resolved != null) {
                    kinds[slot] = resolved.hasGeometry() ? CELL : NONE;
                    placements[slot] = resolved;
                    lastCells.put(lineage, resolved.key());
                    continue;
                }
                // Still asked for, so a repaint comes back for the real one.
                anyDeferred = true;
                CgGlyphKey previous = lastCells.get(lineage);
                CgGlyphPlacement stale = previous == null ? null : registry.peekShadowCell(previous, frame);
                if (stale != null && stale.hasGeometry()) {
                    kinds[slot] = CELL;
                    placements[slot] = stale;
                } else {
                    kinds[slot] = NONE;
                }
            }
        }
        return anyDeferred;
    }

    byte kind(int shadow, int glyph) {
        return kinds[shadow * glyphCount + glyph];
    }

    CgGlyphPlacement placement(int shadow, int glyph) {
        return placements[shadow * glyphCount + glyph];
    }

    float spreadTexels(int shadow) {
        return spreadTexels[shadow];
    }

    float insetSigmaTexels(int shadow, int glyph) {
        return insetSigmaTexels[shadow * glyphCount + glyph];
    }
}
