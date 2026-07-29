package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.render.CgGlyphPlacementCache;
import com.crystalgraphics.util.profiling.CgProfiler;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global, bounded LRU cache of {@code CgTextLayoutEngine.shape(...)/wrap(...)} results — i.e. shaped
 * text (HarfBuzz + BiDi + UAX#14 line breaking already applied) — keyed by <em>content</em>,
 * not identity. Used by {@code CgTextRenderer.layout(String, ...)} so a caller using
 * {@code Draw.text(String)} with repeated content (the common case — most UI text is the same
 * label/button text every frame, even when the caller rebuilds the {@code String} fresh each
 * time) skips shaping entirely on a hit, instead of paying that cost on every single call.
 *
 * <h3>Content-addressed, not identity-addressed — deliberately</h3>
 * <p>Keying on the {@code String}'s content (via its own, already-cached {@code hashCode()}/
 * {@code equals()}) rather than object identity means two calls that both pass {@code "OK"}
 * hit the same entry even from completely different, freshly-built {@code String} instances —
 * which an identity-keyed cache could never do. This is the same pattern production text
 * engines use (e.g. Blink's {@code ShapeResultCache}): a global, bounded, LRU-evicted cache
 * sized by <em>distinct content ever shaped</em>, not by session length or caller count. See
 * {@link CgGlyphPlacementCache}'s javadoc for the sibling cache this deliberately isn't — that
 * one is keyed by layout <em>identity</em>, because it caches the much more expensive
 * per-glyph atlas resolution of an already-built {@link CgTextLayout}, not the shaping step
 * that produces one.</p>
 *
 * <h3>Bounded via real LRU, not weak references or a reference queue</h3>
 * <p>A {@link LinkedHashMap} in access-order mode with {@link LinkedHashMap#removeEldestEntry}
 * evicts the least-recently-used entry once over {@link #CAPACITY}, which is simpler and
 * doesn't depend on GC timing.</p>
 *
 * <h3>Not instantiable — every member is static</h3>
 * <p>There is exactly one cache, shared by every {@code CgTextRenderer} instance in the
 * process — matching {@link CgGlyphPlacementCache} and {@code CgGlyphAtlas}'s own global
 * scope.</p>
 *
 * <h3>Bounded by entries AND bytes</h3>
 * <p>Evicts LRU against whichever of {@link #CAPACITY} and {@code MAX_BYTES} is reached first,
 * because a layout's real cost scales with its glyph count and entries differ by orders of
 * magnitude — one long paragraph is worth hundreds of short labels, and a flat entry count treats
 * them identically. Same dual-budget shape as Skia's {@code SkStrikeCache}.</p>
 *
 * <h3>Invalidated on font hot-reload</h3>
 * <p>{@link #clear()} is wired into the reload path (F3+T) by {@code CgAssetReloader}. Without it
 * a cached layout keeps referencing pre-reload glyph ids and metrics until it happens to be
 * evicted, so a reloaded font takes effect for some text and not other text, for reasons invisible
 * to the user.</p>
 */
public final class CgTextLayoutCache {

    private static final int CAPACITY = 512;

    private static final Map<Key, CgTextLayout> MAP = new LinkedHashMap<>(CAPACITY * 4 / 3, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Key, CgTextLayout> eldest) {
            boolean evict = size() > CAPACITY;
            // Keep the running byte total honest when the count budget evicts — this is
            // the one eviction path that does not go through trimToByteBudget.
            if (evict) cachedBytes -= estimateBytes(eldest.getValue());
            return evict;
        }
    };

    private CgTextLayoutCache() {}

    /**
     * Builds the lookup key for one {@code layout(...)} call. Callers should build this once
     * and reuse it for both {@link #get} and {@link #put} rather than rebuilding it twice.
     */
    public static Key key(String text, Object fontOrFamily, float maxWidth, float maxHeight) {
        return new Key(text, fontOrFamily, maxWidth, maxHeight);
    }

    /** @return the cached layout if present, else {@code null} */
    public static CgTextLayout get(Key key) {
        // Hit rate is reported because it decides how much any further shaping optimisation is
        // worth: a hit skips shaping entirely, so a benchmark that misses every time (every label's
        // text changing each frame) measures a workload real UI rarely produces. Without this
        // number there is no way to tell which regime a given scene is in.
        CgTextLayout layout = MAP.get(key);
        if (layout != null) {
            CgProfiler.count("layoutCache.hit");
        } else {
            CgProfiler.count("layoutCache.miss");
        }
        return layout;
    }

    public static void put(Key key, CgTextLayout layout) {
        CgTextLayout replaced = MAP.put(key, layout);
        if (replaced != null) cachedBytes -= estimateBytes(replaced);
        cachedBytes += estimateBytes(layout);
        trimToByteBudget();
        CgProfiler.sample("layoutCache.size", MAP.size());
        CgProfiler.sample("layoutCache.bytes", cachedBytes);
    }

    /** Running estimate of the bytes held by all cached layouts; maintained incrementally. */
    private static long cachedBytes;

    /**
     * Byte ceiling, evicted against alongside {@link #CAPACITY}.
     *
     * <p>Same reasoning as {@code CgGlyphPlacementCache.MAX_BYTES}: a {@link CgTextLayout}'s cost
     * is dominated by {@code CgBakedGlyphs}' parallel per-glyph arrays, so entries differ in size
     * by orders of magnitude and a flat entry count bounds nothing useful. One 3363-glyph
     * paragraph is worth hundreds of short labels.
     *
     * <p>Skia's {@code SkStrikeCache} pairs 2 MB with 2048 entries; Blink's {@code FrameShapeCache}
     * holds 32,768 shaped entries for a whole web page. 16 MB against 512 entries here sits between
     * them, sized for a game UI rather than a document.
     */
    private static final long MAX_BYTES = 16L * 1024L * 1024L;

    /**
     * Rough heap footprint of a cached layout.
     *
     * <p>Counts the baked per-glyph arrays, which dominate: {@code glyphIds}, {@code penX},
     * {@code penY}, {@code offsetX}, {@code argbColor} (4 bytes each), {@code fontKeys} and
     * {@code fonts} (references), {@code justifiable} and {@code syntheticBold} (1 byte each) —
     * roughly 30 bytes per glyph. Font and font-key objects themselves are shared and deliberately
     * not charged here, for the same reason placements are not charged in the placement cache:
     * they are pointed at by many entries and counting them would evict far too eagerly.
     *
     * <p>Only has to be proportional to real cost, not accurate — it exists to rank entries for
     * eviction, not to report memory.
     */
    private static long estimateBytes(CgTextLayout layout) {
        int glyphs = layout.baked() != null ? layout.baked().glyphCount() : 0;
        return 256L + (long) glyphs * 30L;
    }

    /**
     * Evicts least-recently-used layouts until {@link #MAX_BYTES} is satisfied, never emptying the
     * cache — a single layout can exceed the budget alone, and evicting it on insert would mean the
     * most expensive layout to build is the one that is never cached.
     */
    private static void trimToByteBudget() {
        if (cachedBytes <= MAX_BYTES) return;
        Iterator<Map.Entry<Key, CgTextLayout>> it = MAP.entrySet().iterator();
        while (cachedBytes > MAX_BYTES && MAP.size() > 1 && it.hasNext()) {
            Map.Entry<Key, CgTextLayout> eldest = it.next();
            cachedBytes -= estimateBytes(eldest.getValue());
            it.remove();
            CgProfiler.count("layoutCache.evictedForBytes");
        }
    }

    /**
     * Drops every cached layout. Call when anything a layout was built <em>from</em> changes
     * underneath it — currently only a font hot-reload (F3+T).
     *
     * <p>Without this a cached {@link CgTextLayout} keeps referencing pre-reload glyph ids and
     * metrics until it happens to be evicted, so reloaded fonts appear to take effect for some
     * text and not others, depending on nothing the user can see. Blink solves the same problem in
     * {@code FrameShapeCache} with an explicit generation hook ({@code DidSwitchFrame}); this is
     * the blunter version of that, which is adequate because reloads are rare and manual.</p>
     *
     * <p>{@code CgGlyphPlacementCache} does <strong>not</strong> need a matching call: its entries
     * are keyed by {@link CgTextLayout} identity, so once the layouts here are gone nothing can
     * look them up again, and its own eviction reclaims them.</p>
     */
    public static void clear() {
        int dropped = MAP.size();
        MAP.clear();
        cachedBytes = 0L;
        CgProfiler.count("layoutCache.cleared", dropped);
    }

    /** Current estimated footprint, for diagnostics and tests. */
    public static long estimatedBytes() {
        return cachedBytes;
    }

    /** Entry count, for diagnostics and tests. */
    public static int size() {
        return MAP.size();
    }

    /**
     * Cache key. {@code text} compares/hashes by content (plain {@code String.equals}/
     * {@code hashCode}, already cached internally by {@code String} itself after first use);
     * {@code fontOrFamily} compares/hashes by <strong>identity</strong> (overriding the
     * record's default component-wise behavior) — {@link CgFont}/{@link CgFontFamily}
     * instances are loaded once and reused for the lifetime of a font registration (see
     * {@code CgFontRegistry}), so identity is both cheaper than a value comparison and the
     * semantically correct notion of "same font" here.
     */
    public record Key(String text, Object fontOrFamily, float maxWidth, float maxHeight) {
        @Override
        public int hashCode() {
            int h = text.hashCode();
            h = 31 * h + System.identityHashCode(fontOrFamily);
            h = 31 * h + Float.floatToIntBits(maxWidth);
            h = 31 * h + Float.floatToIntBits(maxHeight);
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return maxWidth == k.maxWidth && maxHeight == k.maxHeight
                    && fontOrFamily == k.fontOrFamily
                    && text.equals(k.text);
        }
    }
}
