package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgTextDecorationRect;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.util.profiling.CgProfiler;

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
 * {@code CgGlyphAtlas.get()} became {@code O(1)} and
 * {@link CgFontRegistry#resolveGlyph} collapsed the old two-call-per-glyph pattern into
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
    /**
     * Set during {@link #resolvePlacements} when any glyph resolved to {@code null} — i.e. it is
     * queued for asynchronous generation and cannot be drawn yet.
     *
     * <p>Load-bearing for correctness, not performance. {@link CgGlyphPlacementCache} would happily
     * store an array containing nulls, and the next frame would then be a cache <em>hit</em> on
     * those nulls — so the glyph would never be re-requested and would stay invisible forever rather
     * than for a frame. That is exactly what happened the first time this was attempted.
     */
    private boolean hasDeferredGlyphs;

    int resolve(CgTextLayout layout, float x, float y, long frame,
                CgTextRenderContext context, int effectiveTargetPx, boolean wantMsdf,
                CgFontKey fontKey, int rgba, float posedOriginX) {
        hasDeferredGlyphs = false;
        long contentGeneration = registry.getAtlasContentGeneration();
        long evictionGeneration = registry.getAtlasEvictionGeneration();

        // Scoped because it runs on every draw whether or not it hits, so it is the one part of
        // resolve() that a perfectly-cached frame still pays in full.
        // ONLY WHERE BUCKETS CAN ACTUALLY APPLY, which is bitmap ortho text: the quad snap is
        // gated on !isWorldText() && !isDistanceField, and toMsdfAtlasGlyphKey rewrites a
        // distance-field glyph's bucket to 0 regardless.
        //
        // Keying on the phase everywhere would bust the cache once per frame for WORLD text, whose
        // posed origin moves with the camera -- the exact failure CgGlyphPlacementCache.Entry#matches
        // has its distance-field early-return to prevent, reintroduced one level up where matches()
        // cannot see it. Costs nothing to exclude: the bitmap/MSDF handoff is the same 32px as
        // CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX, so anything taking the MSDF path has no buckets anyway.
        boolean subPixelApplies = !context.isWorldText() && !wantMsdf;

        // The RAW phase drives the buckets and a coarser one keys the entry -- see posePhaseKey.
        // Keying on the raw fraction would mint a fresh entry for every sub-pixel step of a moving
        // element; keying on nothing (what it did before) hands a translated element the placements
        // built for its old position, which is why a transform's offset never reached the glyphs.
        float posePhase = subPixelApplies ? rawPosePhase(posedOriginX) : 0f;

        CgGlyphPlacementCache.Key key;
        CgGlyphPlacementCache.Entry hit;
        try (CgProfiler.Scope ignored = CgProfiler.scope("placementCache.lookup")) {
            key = CgGlyphPlacementCache.key(layout, x, y, wantMsdf, fontKey, rgba,
                    subPixelApplies ? posePhaseKey(posedOriginX) : 0);
            hit = CgGlyphPlacementCache.get(key, effectiveTargetPx, contentGeneration, evictionGeneration, frame);
        }
        if (hit != null) {
            CgProfiler.count("placementCache.hit");
            this.glyphX = hit.glyphX();
            this.glyphY = hit.glyphY();
            this.argbColor = hit.argbColor();
            this.placements = hit.placements();
            return hit.glyphCount();
        }
        // A miss here means CgGlyphPlacementCache.Entry.matches() rejected the existing entry
        // (if any) -- either this is the first draw of this layout/position/mode, or the prior
        // entry's distanceField was false (so effectiveTargetPx must match exactly -- see that
        // class's javadoc) and it drifted, or REFRESH_FRAMES elapsed. effectiveTargetPx is
        // sampled here specifically to see whether it's drifting frame-to-frame during MSDF
        // atlas warmup for world-space text (PerspectiveScaleResolver recomputes it every frame).
        CgProfiler.count("placementCache.miss");
        CgProfiler.sample("placementCache.miss.effectiveTargetPx", effectiveTargetPx);

        int glyphCount;
        try (CgProfiler.Scope ignored = CgProfiler.scope("flatten")) {
            glyphCount = flatten(layout, x, y, context, effectiveTargetPx, wantMsdf, rgba, posePhase);
        }
        // Salvage already-converged placements from the stale entry we're replacing, so a
        // refresh only re-queries the glyphs that can actually still improve. See
        // CgGlyphPlacementCache#getForUpgrade.
        CgGlyphPlacementCache.Entry upgradeFrom = CgGlyphPlacementCache.getForUpgrade(key, evictionGeneration);
        CgGlyphPlacement[] reusable = upgradeFrom != null && upgradeFrom.glyphCount() == glyphCount
                ? upgradeFrom.placements() : null;

        boolean distanceField = false;
        if (glyphCount > 0) {
            try (CgProfiler.Scope ignored = CgProfiler.scope("resolvePlacements")) {
                distanceField = resolvePlacements(glyphCount, frame, effectiveTargetPx, wantMsdf, reusable);
            }
        }
        // Whether the resulting cache entry (about to be put() below) will require an exact
        // effectiveTargetPx match on its next lookup -- see CgGlyphPlacementCache.Entry.matches().
        CgProfiler.sample("placementCache.miss.resultDistanceField", distanceField ? 1.0 : 0.0);

        this.glyphX = scratchGlyphX;
        this.glyphY = scratchGlyphY;
        this.argbColor = scratchArgbColor;
        this.placements = scratchPlacements;

        // Re-read AFTER resolving, not before: resolving is itself what commits newly generated
        // glyphs (and records empty ones), so it bumps the content generation. Stamping the
        // entry with the pre-resolve value would make it stale the instant it was written,
        // guaranteeing a miss on the very next frame and defeating the cache entirely. The
        // post-resolve value is the atlas state this entry's placements actually describe.
        // Scoped because it is four array copies per draw, and was the largest unattributed
        // remainder inside resolveGlyphs once its other children were measured.
        if (hasDeferredGlyphs) {
            // Do not cache a partially-resolved result. Re-resolving next frame is what lets the
            // deferred glyphs appear once their worker results land; caching would freeze them out.
            CgProfiler.count("placementCache.skippedDeferred");
            return glyphCount;
        }
        try (CgProfiler.Scope ignored = CgProfiler.scope("placementCache.put")) {
            CgGlyphPlacementCache.put(key, new CgGlyphPlacementCache.Entry(
                    distanceField, effectiveTargetPx,
                    registry.getAtlasContentGeneration(), registry.getAtlasEvictionGeneration(),
                    frame, glyphCount,
                    Arrays.copyOf(scratchGlyphX, glyphCount),
                    Arrays.copyOf(scratchGlyphY, glyphCount),
                    Arrays.copyOf(scratchArgbColor, glyphCount),
                    Arrays.copyOf(scratchPlacements, glyphCount)));
        }

        return glyphCount;
    }

    /**
     * Single translate-only pass over {@code layout}'s already-baked {@link CgBakedGlyphs}:
     * every glyph's font key/font/id/subpixel bucket/pen position/effective color/
     * {@link CgGlyphKey} into this instance's scratch arrays. Pen positions only need
     * {@code x, y} added — everything else (line breaking, BiDi, per-line height) was
     * already folded in once by the layout engine's bake step, not re-derived here.
     *
     * <p>Cheap relative to placement resolution (~0.07 ms vs ~100 ms for a 1757-glyph layout),
     * which is why a refresh re-runs this pass in full and economises on
     * {@link #ensurePlacements} instead.</p>
     *
     * @return the number of glyphs flattened (may be 0 for an all-whitespace layout)
     */
    private int flatten(CgTextLayout layout, float x, float y,
                        CgTextRenderContext context, int effectiveTargetPx, boolean wantMsdf, int rgba,
                        float posePhase) {
        CgBakedGlyphs baked = layout.baked();
        int glyphCount = baked.glyphCount();
        ensureCapacity(glyphCount);

        for (int i = 0; i < glyphCount; i++) {
            CgFontKey fontKey = baked.fontKeys()[i];
            int glyphId = baked.glyphIds()[i];
            int subPixel = resolveSubPixelBucket(context, fontKey, effectiveTargetPx,
                    posePhase, baked.penX()[i]);
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
     * {@link #flatten}, into {@link #scratchPlacements}. One pass — glyphs land on whichever
     * tier is actually available for them right now.
     *
     * <h3>Mixed tiers are allowed (this used to force a uniform downgrade)</h3>
     * <p>This previously re-ran the whole atlas-lookup loop in bitmap mode whenever <em>any</em>
     * visible glyph fell back, so that one draw never mixed bitmap and distance-field glyphs.
     * That cost far more than the extra pass it looks like:</p>
     * <ul>
     *   <li>It doubled every unconverged resolve outright ({@code 2 x glyphCount} registry
     *       round trips — measured at ~3559 for a 1757-glyph layout).</li>
     *   <li>Worse, it <strong>destroyed partial progress</strong>. The downgrade overwrote
     *       distance-field placements that had already converged, so the stored entry contained
     *       zero of them, so the next refresh had nothing to reuse and had to re-query
     *       everything again — permanently pinning each refresh at ~90-120 ms instead of letting
     *       it decay toward zero as warmup progressed. The incremental-reuse path in
     *       {@link #ensurePlacements} literally could not fire while this existed.</li>
     * </ul>
     *
     * <p>Allowing the mix is nearly free at draw time: {@code CgTextRenderer}'s sort key is
     * ordered {@code (mode, textureId, pxRange)}, and since the atlas texture-array migration
     * one atlas family is a single texture id with a single pxRange regardless of page count.
     * So a mixed draw costs exactly <strong>two</strong> batches — one bitmap, one
     * distance-field — for any number of glyphs, not one batch per glyph or per page.</p>
     *
     * <p>The visible trade is that a large layout now converges glyph-by-glyph during warmup
     * rather than flipping tier all at once.</p>
     *
     * @return {@code true} only if the result is uniformly distance-field — i.e. {@code wantMsdf}
     *         was requested and no visible glyph fell back. This is what marks a cache entry
     *         fully converged and therefore immune to further refreshes; a mixed result stays
     *         {@code false} so it keeps being revisited until every glyph has upgraded.
     *         See {@link CgGlyphPlacementCache.Entry#distanceField}.
     */
    private boolean resolvePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf,
                                       CgGlyphPlacement[] reusable) {
        boolean usedBitmapFallback = ensurePlacements(glyphCount, frame, effectiveTargetPx, wantMsdf, reusable);
        return wantMsdf && !usedBitmapFallback;
    }

    /**
     * @return true if any glyph in an MSDF-targeted pass fell back to bitmap
     */
    private boolean ensurePlacements(int glyphCount, long frame, int effectiveTargetPx, boolean wantMsdf,
                                      CgGlyphPlacement[] reusable) {
        boolean usedBitmapFallback = false;
        int reused = 0;
        for (int i = 0; i < glyphCount; i++) {
            // A glyph already on the distance-field tier cannot improve — reuse it rather than
            // paying another registry round trip (which, on a miss, costs an MSDF generation
            // attempt plus a bitmap-fallback rasterize). This is what makes a refresh cost
            // O(glyphs still awaiting upgrade) instead of O(entire layout).
            if (reusable != null) {
                CgGlyphPlacement prior = reusable[i];
                if (prior != null && prior.isDistanceField()) {
                    scratchPlacements[i] = prior;
                    reused++;
                    continue;
                }
            }
            // The primary pass always runs with the same wantMsdf that flatten() built
            // scratchGlyphKeys[i] with (see CgTextRenderer.drawInternal), so it can reuse
            // that key instead of allocating a second one for the same glyph this frame.
            // Only the rarer bitmap-fallback retry (mode differs) needs a fresh key.
            CgGlyphKey glyphKey = scratchGlyphKeys[i].isMsdf() == wantMsdf
                    ? scratchGlyphKeys[i]
                    : new CgGlyphKey(scratchFontKeys[i], scratchGlyphIds[i], wantMsdf, scratchSubPixel[i],
                            scratchGlyphKeys[i].isSyntheticBold(), scratchGlyphKeys[i].isSyntheticItalic());
            CgGlyphPlacement placement = registry.resolveGlyph(scratchFonts[i], glyphKey, effectiveTargetPx, scratchSubPixel[i], frame);
            scratchPlacements[i] = placement;
            // null means the glyph is being generated on a worker and is not drawable yet. Recorded
            // so resolve() can refuse to cache this array — see hasDeferredGlyphs.
            if (placement == null) hasDeferredGlyphs = true;
            // hasGeometry() guard: a known-empty glyph (space/control — see
            // CgGlyphAtlas#markEmpty) resolves through the bitmap atlas and so reports
            // isDistanceField()==false, but it draws nothing at all and therefore cannot make
            // a draw visually mix quality tiers. Without this guard a single space would drag
            // the entire layout back down to the bitmap tier and force a full second resolve
            // pass over every glyph.
            if (wantMsdf && placement != null && placement.hasGeometry() && !placement.isDistanceField()) usedBitmapFallback = true;
            
        }
        CgProfiler.count("resolvePlacements.reusedDistanceField", reused);
        CgProfiler.count("resolvePlacements.queried", glyphCount - reused);
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
     * Cache-key resolution for the pose's sub-pixel phase.
     *
     * <p>Finer than {@link #SUB_PIXEL_BUCKETS} on purpose. The key only decides which draws SHARE a
     * placement entry; the buckets inside it were computed from whichever raw phase built it, so a
     * coarse key hands a neighbouring phase stale buckets. At sixteenths that staleness is bounded
     * at {@code 1/16} of a pixel, for at most sixteen entries per layout — and the four raster
     * variants are unaffected either way, since they are keyed separately.</p>
     */
    private static final int POSE_PHASE_STEPS = 16;

    /** The pose's own sub-pixel phase, as the integer the cache key carries. */
    private static int posePhaseKey(float posedOriginX) {
        float fraction = posedOriginX - (float) Math.floor(posedOriginX);
        return (int) Math.floor(fraction * POSE_PHASE_STEPS);
    }

    /** The pose's raw sub-pixel phase, which is what the buckets themselves are chosen from. */
    private static float rawPosePhase(float posedOriginX) {
        return posedOriginX - (float) Math.floor(posedOriginX);
    }

    /**
     * Selects the sub-pixel bucket for a pen offset given IN DEVICE PIXELS.
     *
     * <p>Thresholds on the effective size rather than the base {@code targetPx}, because the
     * effective size is what decides whether a quarter-pixel shift is perceptible at all.</p>
     *
     * <p>The offset must already be in device space — see {@link #resolveSubPixelBucket}. A bucket is
     * a quarter of a PHYSICAL pixel of outline translation, so a fraction measured in any other
     * unit selects a shift for the wrong quantity.</p>
     */
    private static int selectSubPixelBucket(int effectiveTargetPx, float deviceXOffset) {
        if (effectiveTargetPx >= CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX) return 0;

        // THE REMAINDER OF THE SAME ROUNDING THE QUAD USES -- see CgGlyphKey.SUB_PIXEL_BUCKETS and
        // CgTextRenderer.pixelSnapDelta. A threshold table decided this independently of the quad's
        // floor, so a position landing a hair under an integer (7.9999997, which float arithmetic
        // produces constantly) took the top bucket while the quad floored down, putting the ink
        // 0.75 device px out. Rounding both to the quarter grid and splitting it makes that
        // impossible. Measured over a swept fractional translate: worst placement error 0.990 device
        // px before, 0.120 after, against a theoretical floor of 0.125 for four buckets.
        int quarters = Math.round(deviceXOffset * CgGlyphKey.SUB_PIXEL_BUCKETS);
        return Math.floorMod(quarters, CgGlyphKey.SUB_PIXEL_BUCKETS);
    }

    /**
     * The sub-pixel bucket for one glyph, converting the pen offset into device space on the way.
     *
     * <p>THE OFFSET ARRIVES IN LOGICAL UNITS AND THE BUCKET IS A DEVICE-SPACE SHIFT. A bucket
     * translates the outline by {@code bucket * 16} in FreeType 26.6 fixed point — 64 units to the
     * pixel, so a quarter of a PHYSICAL pixel (see {@code CgWorkerFontContext}). Text is shaped and
     * advanced in logical units, and the two only coincide at {@code uiScale} 1: at 2, a logical
     * fraction of 0.5 is a whole device pixel and needs no shift at all, while 0.25 needs half of
     * one. Bucketing the logical fraction therefore picks a shift for the wrong quantity.</p>
     *
     * <p>That mismatch used to be handled by bailing out whenever the raster size differed from the
     * font key's — which is EVERY ui scale except 1, so bitmap UI text on a scaled display had no
     * sub-pixel positioning at all and could only move in whole device pixels. Since bitmap glyphs
     * are also pixel-snapped ({@code CgTextRenderer.pixelSnapDelta}, because {@code GL_NEAREST} is
     * not invariant under sub-pixel translation), the result was text that visibly jumped a pixel at
     * a time while a window resized. Converting the offset instead keeps the buckets.</p>
     */
    private static int resolveSubPixelBucket(CgTextRenderContext context, CgFontKey fontKey,
                                             int effectiveTargetPx, float rawPosePhase, float penX) {
        if (context.isWorldText()) return 0;

        // WHERE THE GLYPH ACTUALLY LANDS, in device pixels, is the only thing that decides its
        // bucket -- and that is the ACCUMULATED pen plus whatever sub-pixel phase the pose carries.
        //
        // It used to be handed baked.offsetX(), the glyph's own HarfBuzz x_offset, on the reasoning
        // that the bucket wants "the glyph's local offset in isolation". That offset is ZERO for
        // essentially every Latin glyph, so the bucket was always 0 and the whole sub-pixel
        // mechanism never engaged for ordinary text -- while pixelSnapDelta went on flooring every
        // glyph to a whole device pixel. The fractional part lives in the accumulated advances
        // (penX) and in the pose, never in that offset.
        //
        // Device space, because a bucket is a quarter of a PHYSICAL pixel of outline translation
        // (CgWorkerFontContext: outlineTranslate(bucket * 16) in 26.6 fixed point, 64 to the pixel),
        // while text is shaped and advanced in logical units. The two only coincide at uiScale 1.
        int baseTargetPx = fontKey.getTargetPx();
        float toDevice = baseTargetPx > 0 ? effectiveTargetPx / (float) baseTargetPx : 1f;
        // RAW, not the key's quantised phase. Bucketing the quantised value stacks the key's
        // rounding on top of the bucket's own and lands 0.750 device px out at worst, against 0.200
        // from the raw one -- barely better than not bucketing at all.
        return selectSubPixelBucket(effectiveTargetPx, rawPosePhase + penX * toDevice);
    }

    /**
     * Pre-resolved, single-draw-call-lifetime view of one {@link CgTextDecorationRect} — its
     * atlas texel already looked up and its quad geometry already computed, so
     * {@code CgTextRenderer.submitSortedQuads} can sort/emit it exactly like a
     * {@link CgGlyphPlacement} without the two ever being the same type or living in the same
     * cache. {@code isDistanceField}/{@code pxRange} come straight off the atlas's own
     * {@link CgGlyphAtlas.WhiteTexel} (see that record's javadoc) — always equal to what a
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

            CgGlyphAtlas.WhiteTexel texel = registry.getDecorationWhiteTexel(segFontKey, effectiveTargetPx, wantMsdf);
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
