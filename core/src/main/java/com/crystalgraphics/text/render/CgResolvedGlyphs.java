package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.atlas.CgPagedGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves one {@code CgTextRenderer.drawInternal} call's {@link CgTextLayout} into flat,
 * per-glyph atlas-placement data — font key/font, glyph id, subpixel bucket, pen position,
 * and finally each glyph's {@link CgGlyphPlacement}. Pure layout/atlas-cache concern: nothing
 * here touches GL, {@code CgQuadRenderer}, or {@code CgMaterial} — that boundary starts once
 * {@code CgTextRenderer} reads {@link #glyphX}/{@link #glyphY}/{@link #placements} back out.
 *
 * <h3>Why a cache is needed even with an O(1) atlas</h3>
 * <p>{@link #resolve} checks {@link CgGlyphPlacementCache} before doing any bake-array pass
 * or atlas work — see that class's javadoc for the cache's own design (capacity, LRU
 * structure, global scope, key composition, staleness bound). Even after
 * {@code CgPagedGlyphAtlas.get()} became {@code O(1)} and
 * {@link CgFontRegistry#resolveGlyphPaged} collapsed the old two-call-per-glyph pattern into
 * one, resolving a static 1000-glyph layout fresh every frame still means ~1000 fresh
 * {@link CgGlyphKey} allocations (each hashing a nested {@link CgFontKey}) plus ~1000 hashmap
 * probes, every single frame, forever — {@code O(1)} describes the lookup's algorithmic
 * complexity, not its absolute cost, and that cost is not zero. A cache hit skips all of it:
 * no bake-array pass, no key allocation, no atlas probe, just repointing {@link #glyphX}/
 * {@link #glyphY}/{@link #placements} at the cached entry's own arrays.</p>
 *
 * <h3>Decoration resolution</h3>
 * <p>{@link #resolveDecorations} is the same kind of concern as glyph resolution — turning a
 * baked, atlas-agnostic description ({@link CgTextDecorationRect}) into something with a real
 * atlas texel and screen-space geometry — so it lives here rather than in {@code CgTextRenderer},
 * which only ever sees the already-resolved {@link ResolvedDecoration} list. It stays a
 * genuinely separate output from {@link #placements}, never merged into one array or one
 * cache — see that method's javadoc for why.</p>
 */
final class CgResolvedGlyphs {

    private final CgFontRegistry registry;

    // ── Scratch arrays: always the write target of a fresh resolve. Never shrink. ──
    private CgFontKey[] scratchFontKeys = new CgFontKey[0];
    private CgFont[] scratchFonts = new CgFont[0];
    private int[] scratchGlyphIds = new int[0];
    private int[] scratchSubPixel = new int[0];
    private CgGlyphKey[] scratchGlyphKeys = new CgGlyphKey[0];
    private float[] scratchGlyphX = new float[0];
    private float[] scratchGlyphY = new float[0];
    private int[] scratchArgbColor = new int[0];
    private CgGlyphPlacement[] scratchPlacements = new CgGlyphPlacement[0];

    /** Pen position for glyph {@code i} of the most recent {@link #resolve} call, valid for
     * {@code [0, <returned glyphCount>)} — read by {@code CgTextRenderer}. Points at either the
     * scratch arrays (fresh resolve) or a cache entry's own arrays (cache hit). */
    float[] glyphX = new float[0];
    float[] glyphY = new float[0];
    /** Effective per-glyph color for the most recent {@link #resolve} call — already resolved
     * as {@code span override != 0 ? span override : draw's default rgba}, read by
     * {@code CgTextRenderer}. */
    int[] argbColor = new int[0];
    /** Resolved atlas placement for glyph {@code i} of the most recent {@link #resolve} call. */
    CgGlyphPlacement[] placements = new CgGlyphPlacement[0];

    CgResolvedGlyphs(CgFontRegistry registry) {
        this.registry = registry;
    }

    private void ensureCapacity(int count) {
        if (count <= scratchFontKeys.length) return;
        int newCap = Math.max(count, scratchFontKeys.length * 2);
        scratchFontKeys = Arrays.copyOf(scratchFontKeys, newCap);
        scratchFonts = Arrays.copyOf(scratchFonts, newCap);
        scratchGlyphIds = Arrays.copyOf(scratchGlyphIds, newCap);
        scratchSubPixel = Arrays.copyOf(scratchSubPixel, newCap);
        scratchGlyphX = Arrays.copyOf(scratchGlyphX, newCap);
        scratchGlyphY = Arrays.copyOf(scratchGlyphY, newCap);
        scratchArgbColor = Arrays.copyOf(scratchArgbColor, newCap);
        scratchPlacements = Arrays.copyOf(scratchPlacements, newCap);
        scratchGlyphKeys = Arrays.copyOf(scratchGlyphKeys, newCap);
    }

    /**
     * Resolves {@code layout} into {@link #glyphX}/{@link #glyphY}/{@link #argbColor}/
     * {@link #placements}, either by reusing a matching {@link CgGlyphPlacementCache} entry
     * or by performing a fresh translate-only bake read + atlas-resolve and caching the
     * result. See that class's javadoc.
     *
     * @param rgba the draw's default color — also part of the cache key, since two draws of
     *             the same layout/position with different default colors must not share a
     *             cache entry (see {@link CgGlyphPlacementCache.Key#rgba()})
     * @return the number of glyphs resolved (may be 0 for an all-whitespace layout)
     */
    int resolve(CgTextLayout layout, float x, float y, long frame,
               CgTextRenderContext context, int effectiveTargetPx, boolean wantMsdf,
               CgFontKey fontKey, int rgba) {
        CgGlyphPlacementCache.Key key = CgGlyphPlacementCache.key(layout, x, y, wantMsdf, fontKey, rgba);
        CgGlyphPlacementCache.Entry hit = CgGlyphPlacementCache.get(key, effectiveTargetPx, frame);
        if (hit != null) {
            this.glyphX = hit.glyphX();
            this.glyphY = hit.glyphY();
            this.argbColor = hit.argbColor();
            this.placements = hit.placements();
            return hit.glyphCount();
        }

        int glyphCount = flatten(layout, x, y, context, effectiveTargetPx, wantMsdf, rgba);
        boolean distanceField = false;
        if (glyphCount > 0) {
            distanceField = resolvePlacements(glyphCount, frame, effectiveTargetPx, wantMsdf);
        }

        this.glyphX = scratchGlyphX;
        this.glyphY = scratchGlyphY;
        this.argbColor = scratchArgbColor;
        this.placements = scratchPlacements;

        CgGlyphPlacementCache.put(key, new CgGlyphPlacementCache.Entry(
                distanceField, effectiveTargetPx, frame, glyphCount,
                Arrays.copyOf(scratchGlyphX, glyphCount),
                Arrays.copyOf(scratchGlyphY, glyphCount),
                Arrays.copyOf(scratchArgbColor, glyphCount),
                Arrays.copyOf(scratchPlacements, glyphCount)));

        return glyphCount;
    }

    /**
     * Single translate-only pass over {@code layout}'s already-baked {@link CgBakedGlyphs}:
     * every glyph's font key/font/id/subpixel bucket/pen position/effective color/
     * {@link CgGlyphKey} into this instance's scratch arrays. Pen positions only need
     * {@code x, y} added — everything else (line breaking, BiDi, per-line height) was
     * already folded in once by the layout engine's bake step, not re-derived here.
     *
     * <p>{@link #ensurePlacements}'s MSDF→bitmap fallback retry re-runs only the flat
     * atlas-lookup loop over these already-flattened arrays — never this pass again.</p>
     *
     * @return the number of glyphs flattened (may be 0 for an all-whitespace layout)
     */
    private int flatten(CgTextLayout layout, float x, float y,
                        CgTextRenderContext context, int effectiveTargetPx, boolean wantMsdf, int rgba) {
        CgBakedGlyphs baked = layout.baked();
        int glyphCount = baked.glyphCount();
        ensureCapacity(glyphCount);

        for (int i = 0; i < glyphCount; i++) {
            CgFontKey fontKey = baked.fontKeys()[i];
            int glyphId = baked.glyphIds()[i];
            int subPixel = resolveSubPixelBucket(context, fontKey, effectiveTargetPx, baked.offsetX()[i]);
            int overrideColor = baked.argbColor()[i];
            scratchFontKeys[i] = fontKey;
            scratchFonts[i] = baked.fonts()[i];
            scratchGlyphIds[i] = glyphId;
            scratchSubPixel[i] = subPixel;
            scratchGlyphKeys[i] = new CgGlyphKey(fontKey, glyphId, wantMsdf, subPixel,
                    baked.syntheticBold()[i], baked.syntheticItalic()[i]);
            scratchGlyphX[i] = x + baked.penX()[i];
            scratchGlyphY[i] = y + baked.penY()[i];
            scratchArgbColor[i] = overrideColor != 0 ? overrideColor : rgba;
        }

        return glyphCount;
    }

    /**
     * Resolves atlas placements for the {@code glyphCount} glyphs already flattened by
     * {@link #flatten}, into {@link #scratchPlacements}.
     *
     * <p>If a draw prefers distance fields but any visible glyph had to fall back to
     * bitmap this frame, reruns just this atlas-lookup loop in bitmap mode so one draw
     * never mixes bitmap and distance-field quality tiers — no re-walk of the layout tree.</p>
     *
     * @return {@code true} if the final result is uniformly distance-field (i.e. {@code wantMsdf}
     *         was requested and no glyph fell back to bitmap) — see {@link CgGlyphPlacementCache.Entry#distanceField}
     */
    private boolean resolvePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf) {
        boolean usedBitmapFallback = ensurePlacements(glyphCount, frame, effectiveTargetPx, wantMsdf);
        if (wantMsdf && usedBitmapFallback) {
            ensurePlacements(glyphCount, frame, effectiveTargetPx, false);
            return false;
        }
        return wantMsdf;
    }

    /**
     * @return true if any glyph in an MSDF-targeted pass fell back to bitmap
     */
    private boolean ensurePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf) {
        boolean usedBitmapFallback = false;
        for (int i = 0; i < glyphCount; i++) {
            // The primary pass always runs with the same wantMsdf that flatten() built
            // scratchGlyphKeys[i] with (see CgTextRenderer.drawInternal), so it can reuse
            // that key instead of allocating a second one for the same glyph this frame.
            // Only the rarer bitmap-fallback retry (mode differs) needs a fresh key.
            CgGlyphKey glyphKey = scratchGlyphKeys[i].isMsdf() == wantMsdf
                    ? scratchGlyphKeys[i]
                    : new CgGlyphKey(scratchFontKeys[i], scratchGlyphIds[i], wantMsdf, scratchSubPixel[i],
                            scratchGlyphKeys[i].isSyntheticBold(), scratchGlyphKeys[i].isSyntheticItalic());
            CgGlyphPlacement placement = registry.resolveGlyphPaged(scratchFonts[i], glyphKey, effectiveTargetPx, scratchSubPixel[i], frame);
            scratchPlacements[i] = placement;
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

    /**
     * Pre-resolved, single-draw-call-lifetime view of one {@link CgTextDecorationRect} — its
     * atlas texel already looked up and its quad geometry already computed, so
     * {@code CgTextRenderer.submitSortedQuads} can sort/emit it exactly like a
     * {@link CgGlyphPlacement} without the two ever being the same type or living in the same
     * cache. {@code isDistanceField}/{@code pxRange} come straight off the atlas's own
     * {@link CgPagedGlyphAtlas.WhiteTexel} (see that record's javadoc) — always equal to what a
     * real glyph from this same atlas would report, which is what lets a decoration interleave
     * into that font's existing batch instead of forcing a separate one. Built fresh every draw
     * call in {@link #resolveDecorations}; never stored anywhere.
     */
    record ResolvedDecoration(int atlasTextureId, int atlasPageIndex, boolean isDistanceField, float pxRange,
                              float u0, float v0, float u1, float v1,
                              float qx, float qy, float w, float h, int rgba) {
    }

    /**
     * Resolves each decoration rect's atlas white texel (see
     * {@link CgFontRegistry#getDecorationWhiteTexel}) and computes its screen-space quad geometry
     * up front, so the renderer can fold decorations into the very same sort pass as glyphs —
     * same batch-key format, same material transitions, no separate flush. Skips a rect with no
     * font key or no resolvable texel (e.g. an atlas that's momentarily out of room).
     *
     * <p>{@code x0}/{@code x1}/{@code y} are layout-origin-relative logical pixels, the same
     * space as {@link CgBakedGlyphs#penX()}/{@code penY()} — translated by the draw's own
     * {@code x}/{@code y} exactly like glyph pen positions are in {@link #flatten}, and
     * otherwise drawn at 1:1 logical scale (no raster-tier rescaling — a flat rectangle has no
     * atlas-resolution dependency).</p>
     */
    List<ResolvedDecoration> resolveDecorations(CgTextDecorationRect[] decorations, float x, float y,
                                                int drawRgba, int effectiveTargetPx, boolean wantMsdf) {
        if (decorations.length == 0) return List.of();
        List<ResolvedDecoration> resolved = new ArrayList<>(decorations.length);
        for (CgTextDecorationRect seg : decorations) {
            CgFontKey segFontKey = seg.fontKey();
            if (segFontKey == null) continue;

            CgPagedGlyphAtlas.WhiteTexel texel = registry.getDecorationWhiteTexel(segFontKey, effectiveTargetPx, wantMsdf);
            if (texel == null) continue;

            int rgba = seg.argbColor() != 0 ? seg.argbColor() : drawRgba;
            float qx = x + seg.x0();
            float qy = y + seg.y() - seg.thickness() / 2f;
            resolved.add(new ResolvedDecoration(texel.atlasTextureId(), texel.atlasPageIndex(),
                    texel.isDistanceField(), texel.pxRange(),
                    texel.u0(), texel.v0(), texel.u1(), texel.v1(),
                    qx, qy, seg.x1() - seg.x0(), seg.thickness(), rgba));
        }
        return resolved;
    }
}
