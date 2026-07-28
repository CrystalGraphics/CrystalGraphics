package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgTextLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global, bounded LRU cache of resolved glyph placements, keyed per <em>draw</em> (layout +
 * position + mode + font) rather than per glyph. Used by {@link CgResolvedGlyphs#resolve} —
 * see that class's javadoc for the full rationale: why a cache is needed at all even with an
 * {@code O(1)} atlas lookup, and why this design (capacity, LRU structure, global scope)
 * replaces an earlier, undersized, non-scaling attempt at the same idea.
 *
 * <h3>Not instantiable — every member is static</h3>
 * <p>There is exactly one cache, shared by every {@link CgResolvedGlyphs} (and so every
 * {@code CgTextRenderer}) instance in the process. This is deliberate, not an oversight: the
 * cache is keyed by {@link CgTextLayout} identity, which is a property of the layout itself,
 * not of which renderer happens to be drawing it — two renderers drawing the same cached
 * layout with the same parameters should both benefit from the same entry, and a single
 * shared pool uses far less memory than N renderers each keeping their own. This matches
 * {@code CgGlyphAtlas} (two atlases process-wide, one per texture format, every font sharing
 * them via the {@code CgFontRegistry} singleton) and {@code CgTextRenderer.SHAPE_CACHE}'s own
 * global scope — every cache in this pipeline is global for the same reason.</p>
 */
public final class CgGlyphPlacementCache {

    /** Entries are per-<em>layout</em>, not per-glyph, so this comfortably covers hundreds of
     * simultaneously visible distinct texts (e.g. every label across every open UI window)
     * even though it's a modest, fixed number — see class javadoc. */
    private static final int CAPACITY = 1024;

    /**
     * Minimum frames between re-resolves of a <em>not-yet-converged</em> (non-distance-field)
     * entry, regardless of how many times the atlas changed in between.
     *
     * <p>This is a rate limiter, not a staleness policy — {@link Entry#matches} decides
     * <em>whether</em> an entry is stale from the atlas content generation; this only bounds
     * <em>how often</em> acting on that is allowed to cost a full re-resolve.
     *
     * <p>It exists because during warmup the async glyph drain commits new glyphs on nearly
     * every frame, so a purely generation-driven policy invalidates on nearly every frame, and
     * a large layout's re-resolve is 35-145 ms. Measured without this limiter: warmup collapsed
     * to 5-41 fps for ~11 s while steady state was perfect. The old fixed 300-frame timer this
     * replaced was accidentally providing exactly this rate limit — that was the one job it was
     * genuinely doing, and the reason removing it outright regressed warmup.
     *
     * <p>Deliberately far shorter than that old 300-frame timer. It can afford to be, because
     * unlike the timer it never applies once an entry converges to distance-field: a converged
     * layout is exempt entirely and re-resolves zero times, forever. So this only trades a
     * little warmup CPU for faster visual convergence (~1 s at 120 FPS instead of up to ~5 s),
     * and costs nothing in steady state.</p>
     */
    private static final long MIN_REFRESH_FRAMES_WHILE_UNCONVERGED = 120;

    private static final Map<Key, Entry> MAP = new LinkedHashMap<>(CAPACITY * 4 / 3, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                    return size() > CAPACITY;
                }
            };

    private CgGlyphPlacementCache() {
    }

    /**
     * Builds the lookup key for one draw. Callers should build this once and pass the same
     * instance to both {@link #get} and {@link #put} rather than rebuilding it twice.
     */
    public static Key key(CgTextLayout layout, float x, float y, boolean wantMsdf, CgFontKey fontKey, int rgba) {
        return new Key(layout, x, y, wantMsdf, fontKey, rgba);
    }

    /**
     * @return the cached entry if present and not stale for {@code effectiveTargetPx} and the
     *         current atlas generations (see {@link Entry#matches}), else {@code null}
     */
    public static Entry get(Key key, int effectiveTargetPx, long contentGeneration, long evictionGeneration, long frame) {
        Entry entry = MAP.get(key);
        return entry != null && entry.matches(effectiveTargetPx, contentGeneration, evictionGeneration, frame) ? entry : null;
    }

    public static void put(Key key, Entry entry) {
        MAP.put(key, entry);
    }

    /**
     * Returns a stale entry whose already-converged placements can still be salvaged, or
     * {@code null} if there is nothing safely reusable.
     *
     * <p>Used by {@link CgResolvedGlyphs#resolve} on a miss: a refresh triggered because the
     * atlas gained content is looking for glyphs that can now be <em>upgraded</em> from bitmap
     * to distance-field, but the glyphs already on distance-field cannot improve any further
     * and do not need re-querying. Reusing them turns a refresh from {@code O(all glyphs)} into
     * {@code O(glyphs still awaiting an upgrade)}, which decays to zero as warmup converges —
     * measured at ~100-128 ms per refresh for a 1757-glyph layout when everything is re-queried.
     *
     * <p>Two safety conditions, both necessary:</p>
     * <ul>
     *   <li><b>Eviction generation must match.</b> After an eviction a freed layer index is
     *       reused by another page, so the cached placements may point at someone else's
     *       glyphs — nothing from this entry is trustworthy.</li>
     *   <li><b>The entry must not already be fully distance-field.</b> Such an entry would have
     *       satisfied {@link Entry#matches} and never reached this path; if one does, there is
     *       nothing to upgrade and the caller should do a normal resolve.</li>
     * </ul>
     *
     * <p>Deliberately does <em>not</em> check {@code effectiveTargetPx}: only distance-field
     * placements are ever reused from the returned entry, and those are size-independent by
     * construction (see {@link Entry#matches}).</p>
     */
    public static Entry getForUpgrade(Key key, long evictionGeneration) {
        Entry entry = MAP.get(key);
        if (entry == null || entry.distanceField()) return null;
        return entry.builtEvictionGeneration() == evictionGeneration ? entry : null;
    }

    /**
     * Cache key. {@code layout} compares/hashes by <strong>identity</strong> (overriding the
     * record's default component-wise behavior) — deliberately not {@link CgTextLayout}'s own
     * generated {@code hashCode()}, a Lombok {@code @Value} type whose hash walks the entire
     * line/run/glyph tree, which would make a cache <em>lookup</em> cost as much as the
     * resolve it's avoiding. {@code fontKey} compares/hashes by value (a small type, cheap to
     * hash, and callers may legitimately hand back a fresh-but-equal instance rather than the
     * exact same one).
     *
     * <p>{@code rgba} — the draw's default color — is part of the key so two draws of the
     * same cached layout at the same position with two <em>different</em> default colors
     * don't incorrectly share an entry: {@link Entry#argbColor} is the already-resolved
     * per-glyph effective color (override color if the glyph's span had one, else this
     * {@code rgba}), so an entry built for one {@code rgba} is simply wrong for another.</p>
     */
    public record Key(CgTextLayout layout, float x, float y, boolean wantMsdf, CgFontKey fontKey, int rgba) {
        @Override
        public int hashCode() {
            int h = System.identityHashCode(layout);
            h = 31 * h + Float.floatToIntBits(x);
            h = 31 * h + Float.floatToIntBits(y);
            h = 31 * h + (wantMsdf ? 1 : 0);
            h = 31 * h + fontKey.hashCode();
            h = 31 * h + rgba;
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return layout == k.layout && x == k.x && y == k.y
                    && wantMsdf == k.wantMsdf && fontKey.equals(k.fontKey) && rgba == k.rgba;
        }
    }

    /**
     * Cache value. {@code effectiveTargetPx} only matters for a bitmap-actual-result entry
     * (it drives subpixel bucket and raster tier) — never for a distance-field one, since
     * {@code CgFontRegistry.toMsdfAtlasGlyphKey} hardcodes {@code subPixelBucket=0} and
     * rewrites the font key to the atlas's fixed {@code atlasScalePx}, ignoring
     * {@code effectiveTargetPx} entirely. {@link #matches} skips that check when
     * {@code distanceField} is true — this matters in practice, since world-space text
     * recomputes a projected-size hint every frame (see {@code PerspectiveScaleResolver}), so
     * {@code effectiveTargetPx} can shift on essentially every camera movement even though
     * distance-field output never changes; without this exclusion, panning/zooming the camera
     * would bust the cache on nearly every frame.
     *
     * <p>{@code argbColor} is the already-resolved effective color per glyph (span override,
     * or the draw's default {@code rgba} baked into {@link Key} — see that field's javadoc).</p>
     */
    public record Entry(boolean distanceField, int effectiveTargetPx, long builtContentGeneration,
                 long builtEvictionGeneration, long builtFrame, int glyphCount,
                 float[] glyphX, float[] glyphY, int[] argbColor, CgGlyphPlacement[] placements) {

        /**
         * Revision-based staleness — this entry is valid until something it was built against
         * actually changed, rather than until a fixed number of frames elapsed.
         *
         * <p>Three independent checks, each guarding a distinct way an entry can go wrong:</p>
         * <ol>
         *   <li><b>Eviction.</b> Any eviction anywhere invalidates unconditionally: a freed
         *       layer index is immediately reused by a different page, so every placement this
         *       entry holds may now point at someone else's glyphs. On the default unbounded
         *       atlases this never fires (see
         *       {@code CgFontRegistry#getAtlasEvictionGeneration()}).</li>
         *   <li><b>New atlas content, but only for a non-distance-field entry.</b> New content
         *       matters solely because a glyph that had to fall back to bitmap might now have
         *       its real MSDF result available. An entry that is already uniformly
         *       distance-field has nothing left to upgrade to, so it is immune by construction
         *       — which is what makes a converged layout cost <em>zero</em> re-resolves in
         *       steady state instead of one full re-resolve every 300 frames forever.</li>
         *   <li><b>{@code effectiveTargetPx}, again only for a non-distance-field entry.</b>
         *       Unchanged in spirit from before: bitmap placements are rasterized at a specific
         *       effective size, while {@code CgFontRegistry#toMsdfAtlasGlyphKey} rewrites the
         *       key to the atlas's fixed {@code atlasScalePx} and zeroes the sub-pixel bucket,
         *       so a distance-field entry is size-independent.</li>
         * </ol>
         *
         * <p>The content-generation check is additionally rate-limited by
         * {@link #MIN_REFRESH_FRAMES_WHILE_UNCONVERGED} — see that constant for why a purely
         * generation-driven policy collapses warmup framerate. The rate limit applies only to
         * the unconverged branch; a distance-field entry returns above it and is never
         * re-resolved on a timer at all.</p>
         */
        public boolean matches(int effectiveTargetPx, long contentGeneration, long evictionGeneration, long frame) {
            if (evictionGeneration != builtEvictionGeneration) return false;
            if (distanceField) return true;
            if (this.effectiveTargetPx != effectiveTargetPx) return false;
            if (contentGeneration == builtContentGeneration) return true;
            // Atlas content changed, so an upgrade may be available -- but honour the rate
            // limit before paying for a full re-resolve of the whole layout.
            return (frame - builtFrame) < MIN_REFRESH_FRAMES_WHILE_UNCONVERGED;
        }
    }
}
