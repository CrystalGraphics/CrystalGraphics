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
 * {@code CgPagedGlyphAtlas} (one atlas per font, shared globally via the {@code CgFontRegistry}
 * singleton) and {@code CgTextRenderer.SHAPE_CACHE}'s own global scope — every cache in this
 * pipeline is global for the same reason.</p>
 */
final class CgGlyphPlacementCache {

    /** Entries are per-<em>layout</em>, not per-glyph, so this comfortably covers hundreds of
     * simultaneously visible distinct texts (e.g. every label across every open UI window)
     * even though it's a modest, fixed number — see class javadoc. */
    private static final int CAPACITY = 1024;

    /**
     * Force a fresh resolve at least this often even on an otherwise-matching entry — ~5s at
     * 60 FPS, cheap insurance rather than a hot-path cost. Bounds how long a warmup-era
     * bitmap-fallback result could survive after the real distance-field glyph becomes ready,
     * and keeps atlas-page LRU bookkeeping fresh for a bounded, actively-evicting atlas
     * (irrelevant for the default unbounded one, but harmless).
     */
    private static final long REFRESH_FRAMES = 300;

    private static final Map<Key, Entry> MAP =
            new LinkedHashMap<Key, Entry>(CAPACITY * 4 / 3, 0.75f, true) {
                @Override
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
    static Key key(CgTextLayout layout, float x, float y, boolean wantMsdf, CgFontKey fontKey, int rgba) {
        return new Key(layout, x, y, wantMsdf, fontKey, rgba);
    }

    /**
     * @return the cached entry if present and not stale for {@code effectiveTargetPx}/
     *         {@code frame} (see {@link Entry#matches}), else {@code null}
     */
    static Entry get(Key key, int effectiveTargetPx, long frame) {
        Entry entry = MAP.get(key);
        return entry != null && entry.matches(effectiveTargetPx, frame) ? entry : null;
    }

    static void put(Key key, Entry entry) {
        MAP.put(key, entry);
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
    record Key(CgTextLayout layout, float x, float y, boolean wantMsdf, CgFontKey fontKey, int rgba) {
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
    record Entry(boolean distanceField, int effectiveTargetPx, long builtFrame, int glyphCount,
                 float[] glyphX, float[] glyphY, int[] argbColor, CgGlyphPlacement[] placements) {
        boolean matches(int effectiveTargetPx, long frame) {
            return (distanceField || this.effectiveTargetPx == effectiveTargetPx)
                    && (frame - builtFrame) < REFRESH_FRAMES;
        }
    }
}
