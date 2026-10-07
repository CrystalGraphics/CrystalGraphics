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
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;
import java.util.Iterator;
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
 *
 * <p><b>A plan is kept for a cached layout.</b> A placement-cache hit hands every draw of a layout the same placements
 * array, so a plan with no cell still building is kept for that array and reused while the shadows, size, stroke and
 * atlas evictions match, as {@link CgGlyphPlacementCache} reuses placements: a kept plan stamps no atlas page.</p>
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

    private static final int KEPT_HIT = CgTrace.name("shadowPlan.kept"), PLANNED = CgTrace.name("shadowPlan.planned");
    /** A backstop, as the layout and placement caches' counts: {@link #MAX_KEPT_SLOTS} is the bound. */
    private static final int MAX_KEPT = 32_768;
    /** (shadow, glyph) slots every kept plan holds together, about 9 bytes each. */
    private static final int MAX_KEPT_SLOTS = 1 << 18;

    private final CgFontRegistry registry;

    // What the accessors read: the scratch plan below, or a kept one's arrays.
    /** One byte per (shadow, glyph): which kind that glyph paints in that shadow. */
    private byte[] kinds;
    /** The placement each (shadow, glyph) paints. */
    private CgGlyphPlacement[] placements;
    /** Texels of spread per shadow, for the field kinds. */
    private float[] spreadTexels;
    /** Per (shadow, glyph): the blur of a {@link #FIELD_INSET}, in atlas texels; 0 for a sharp one. */
    private float[] insetSigmaTexels;
    private int glyphCount;

    private byte[] scratchKinds = new byte[0];
    private CgGlyphPlacement[] scratchPlacements = new CgGlyphPlacement[0];
    private float[] scratchSpread = new float[0];
    private float[] scratchInset = new float[0];

    /** Kept plans by the cached placements array they were made from, compared by identity. */
    private final Map<CgGlyphPlacement[], Kept> kept = new LinkedHashMap<CgGlyphPlacement[], Kept>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CgGlyphPlacement[], Kept> eldest) {
            if (size() <= MAX_KEPT) return false;
            keptSlots -= eldest.getValue().kinds.length;
            return true;
        }
    };
    private int keptSlots;

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
        show(scratchKinds, scratchPlacements, scratchSpread, scratchInset, 0);
    }

    /** Whether a local sigma blurs at all once the pose maps it to device pixels. */
    static boolean blurs(float sigmaLocal, float devicePerLocal) {
        return (double) sigmaLocal * devicePerLocal >= CgMaskBlurFilter.NO_BLUR_SIGMA;
    }

    /**
     * Plans every shadow of {@code shadows} over the draw's resolved glyphs.
     *
     * @param stable            {@code glyphs} is a placement-cache entry's own array, which a plan may be kept for
     * @param strokeWidthTexels the stroke the draw paints, 0 for none; a shadow's shape includes it
     * @param strokeAlign       the stroke's align code, as the renderer passes it to text.shader
     * @param worldText         a perspective draw, whose cells are stretched by however close the camera is
     * @return whether a cell was asked for and not built yet, so the draw is provisional
     */
    boolean plan(CgTextShadowList shadows, CgGlyphPlacement[] glyphs, boolean stable, CgBakedGlyphs baked,
                 int glyphCount, CgFontKey fontKey, int effectiveTargetPx, float strokeWidthTexels, float strokeAlign,
                 boolean worldText, long frame) {
        Kept k = null;
        long evictions = 0L;
        if (stable) {
            evictions = registry.getAtlasEvictionGeneration();
            k = kept.get(glyphs);
            if (k != null && k.matches(shadows, baked, glyphCount, fontKey, effectiveTargetPx, strokeWidthTexels,
                    strokeAlign, worldText, evictions)) {
                show(k.kinds, k.placements, k.spreadTexels, k.insetSigmaTexels, glyphCount);
                CgTrace.add(CgChannels.TEXT, KEPT_HIT, 1);
                return false;
            }
        }
        CgTrace.add(CgChannels.TEXT, PLANNED, 1);
        boolean deferred = build(shadows, glyphs, baked, glyphCount, fontKey, effectiveTargetPx, strokeWidthTexels,
                strokeAlign, worldText, frame);
        if (stable && !deferred) {
            if (k == null) {
                k = new Kept();
                kept.put(glyphs, k);
            }
            keptSlots += k.store(shadows, baked, glyphCount, fontKey, effectiveTargetPx, strokeWidthTexels, strokeAlign,
                    worldText, evictions, scratchKinds, scratchPlacements, scratchSpread, scratchInset);
            trimKept();
        }
        return deferred;
    }

    private void show(byte[] kinds, CgGlyphPlacement[] placements, float[] spreadTexels, float[] insetSigmaTexels,
                      int glyphCount) {
        this.kinds = kinds;
        this.placements = placements;
        this.spreadTexels = spreadTexels;
        this.insetSigmaTexels = insetSigmaTexels;
        this.glyphCount = glyphCount;
    }

    /** Drops the least recently drawn plans past {@link #MAX_KEPT_SLOTS}, never the one just kept. */
    private void trimKept() {
        Iterator<Kept> it = kept.values().iterator();
        while (keptSlots > MAX_KEPT_SLOTS && kept.size() > 1 && it.hasNext()) {
            keptSlots -= it.next().kinds.length;
            it.remove();
        }
    }

    private boolean build(CgTextShadowList shadows, CgGlyphPlacement[] glyphs, CgBakedGlyphs baked, int glyphCount,
                          CgFontKey fontKey, int effectiveTargetPx, float strokeWidthTexels, float strokeAlign,
                          boolean worldText, long frame) {
        int n = shadows.count();
        int slots = n * glyphCount;
        if (scratchKinds.length < slots) {
            int capacity = Math.max(slots, scratchKinds.length * 2);
            scratchKinds = new byte[capacity];
            scratchPlacements = new CgGlyphPlacement[capacity];
            scratchInset = new float[capacity];
        }
        if (scratchSpread.length < n) scratchSpread = new float[Math.max(n, scratchSpread.length * 2)];
        byte[] kinds = scratchKinds;
        CgGlyphPlacement[] placements = scratchPlacements;
        float[] spreadTexels = scratchSpread;
        float[] insetSigmaTexels = scratchInset;
        show(kinds, placements, spreadTexels, insetSigmaTexels, glyphCount);

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

    /**
     * One draw's plan, kept for the cached placements array it was made from, with what it was made for. Its arrays
     * are reused when the same array plans again with other shadows.
     */
    private static final class Kept {
        CgBakedGlyphs baked;
        CgFontKey fontKey;
        int glyphCount, effectiveTargetPx, shadowCount;
        float strokeWidthTexels, strokeAlign;
        boolean worldText, scoped;
        long evictionGeneration;
        float[] x = new float[0], y = new float[0], sigma = new float[0], spread = new float[0];
        boolean[] inset = new boolean[0], casts = new boolean[0];
        int[] scope = new int[0];
        /** Each glyph's scope when a shadow is scoped, as {@link CgTextShadowList#glyphScope} answers it. */
        int[] glyphScopes = new int[0];
        byte[] kinds = new byte[0];
        CgGlyphPlacement[] placements = new CgGlyphPlacement[0];
        float[] spreadTexels = new float[0], insetSigmaTexels = new float[0];

        boolean matches(CgTextShadowList s, CgBakedGlyphs baked, int glyphCount, CgFontKey fontKey,
                        int effectiveTargetPx, float strokeWidthTexels, float strokeAlign, boolean worldText,
                        long evictionGeneration) {
            if (baked != this.baked || glyphCount != this.glyphCount || effectiveTargetPx != this.effectiveTargetPx
                    || worldText != this.worldText || evictionGeneration != this.evictionGeneration
                    || strokeWidthTexels != this.strokeWidthTexels || strokeAlign != this.strokeAlign
                    || s.count() != shadowCount || fontKey != this.fontKey && !fontKey.equals(this.fontKey)) {
                return false;
            }
            for (int i = 0; i < shadowCount; i++) {
                if (s.x(i) != x[i] || s.y(i) != y[i] || s.sigma(i) != sigma[i] || s.spread(i) != spread[i]
                        || s.inset(i) != inset[i] || s.casts(i) != casts[i] || s.scopeOf(i) != scope[i]) {
                    return false;
                }
            }
            if (!scoped) return true;
            for (int g = 0; g < glyphCount; g++) {
                if (s.glyphScope(g) != glyphScopes[g]) return false;
            }
            return true;
        }

        /** Takes the plan just built and what it was built for; returns the slots its arrays grew by. */
        int store(CgTextShadowList s, CgBakedGlyphs baked, int glyphCount, CgFontKey fontKey, int effectiveTargetPx,
                  float strokeWidthTexels, float strokeAlign, boolean worldText, long evictionGeneration,
                  byte[] kinds, CgGlyphPlacement[] placements, float[] spreadTexels, float[] insetSigmaTexels) {
            this.baked = baked;
            this.fontKey = fontKey;
            this.glyphCount = glyphCount;
            this.effectiveTargetPx = effectiveTargetPx;
            this.strokeWidthTexels = strokeWidthTexels;
            this.strokeAlign = strokeAlign;
            this.worldText = worldText;
            this.evictionGeneration = evictionGeneration;
            int n = s.count();
            shadowCount = n;
            if (x.length < n) {
                x = new float[n];
                y = new float[n];
                sigma = new float[n];
                spread = new float[n];
                inset = new boolean[n];
                casts = new boolean[n];
                scope = new int[n];
                this.spreadTexels = new float[n];
            }
            scoped = false;
            for (int i = 0; i < n; i++) {
                x[i] = s.x(i);
                y[i] = s.y(i);
                sigma[i] = s.sigma(i);
                spread[i] = s.spread(i);
                inset[i] = s.inset(i);
                casts[i] = s.casts(i);
                scope[i] = s.scopeOf(i);
                scoped |= scope[i] >= 0;
            }
            if (scoped) {
                if (glyphScopes.length < glyphCount) glyphScopes = new int[glyphCount];
                for (int g = 0; g < glyphCount; g++) glyphScopes[g] = s.glyphScope(g);
            }
            System.arraycopy(spreadTexels, 0, this.spreadTexels, 0, n);
            int slots = n * glyphCount, grown = 0;
            if (this.kinds.length < slots) {
                grown = slots - this.kinds.length;
                this.kinds = new byte[slots];
                this.placements = new CgGlyphPlacement[slots];
                this.insetSigmaTexels = new float[slots];
            }
            System.arraycopy(kinds, 0, this.kinds, 0, slots);
            System.arraycopy(placements, 0, this.placements, 0, slots);
            System.arraycopy(insetSigmaTexels, 0, this.insetSigmaTexels, 0, slots);
            return grown;
        }
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
