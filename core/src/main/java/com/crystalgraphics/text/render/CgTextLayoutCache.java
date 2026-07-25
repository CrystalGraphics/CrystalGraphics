package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global, bounded LRU cache of {@code CgTextLayoutBuilder.layout(...)} results — i.e. shaped
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
 * process — matching {@link CgGlyphPlacementCache} and {@code CgPagedGlyphAtlas}'s own global
 * scope.</p>
 *
 * <h3>Known caveat</h3>
 * <p>A font hot-reload (F3+T) does not invalidate entries already in this cache — a cached
 * {@link CgTextLayout} could keep referencing pre-reload glyph ids/metrics until it's
 * naturally evicted. No production impact today (nothing currently wires a cache clear into
 * the reload path), flagged here as a known follow-up if font hot-reload while this cache is
 * warm ever becomes a real issue.</p>
 */
final class CgTextLayoutCache {

    private static final int CAPACITY = 512;

    private static final Map<Key, CgTextLayout> MAP =
            new LinkedHashMap<Key, CgTextLayout>(CAPACITY * 4 / 3, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, CgTextLayout> eldest) {
                    return size() > CAPACITY;
                }
            };

    private CgTextLayoutCache() {
    }

    /**
     * Builds the lookup key for one {@code layout(...)} call. Callers should build this once
     * and reuse it for both {@link #get} and {@link #put} rather than rebuilding it twice.
     */
    static Key key(String text, Object fontOrFamily, float maxWidth, float maxHeight) {
        return new Key(text, fontOrFamily, maxWidth, maxHeight);
    }

    /** @return the cached layout if present, else {@code null} */
    static CgTextLayout get(Key key) {
        return MAP.get(key);
    }

    static void put(Key key, CgTextLayout layout) {
        MAP.put(key, layout);
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
    record Key(String text, Object fontOrFamily, float maxWidth, float maxHeight) {
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
