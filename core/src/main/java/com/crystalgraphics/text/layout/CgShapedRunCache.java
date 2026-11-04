package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontSource;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextDirection;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A paragraph's shaped runs, kept by its text and fonts: Slate's {@code FShapedTextCache}. Text laid out again with
 * one line changed shapes that line alone, since every paragraph of it is looked up here first.
 *
 * <pre>{@code
 * // CgTextLayoutEngine.shape, per paragraph: a HUD whose clip time changes each frame shapes that line only
 * List<CgShapedRun> runs = CgShapedRunCache.runs(paragraph, family, direction, tabStop,
 *         () -> shapeParagraphText(paragraph, family, direction, tabStop));
 * }</pre>
 *
 * <ul>
 *   <li>Keyed by the family's declared fonts ({@link CgFontKey}s, by value) and its fallback by identity, so a
 *       family rebuilt each call ({@code CgFontFamily.of(font)}) still hits.</li>
 *   <li>Kept only where every run is a declared font's: a paragraph reaching a font the fallback discovered
 *       shapes each time, since what the fallback answers can change as it finds fonts.</li>
 *   <li>A hit whose font has been disposed is a miss. The runs handed out are shared: never change them.</li>
 *   <li>Bounded by entries and bytes like {@link CgTextLayoutCache}, and cleared with it on a font reload.</li>
 * </ul>
 */
public final class CgShapedRunCache {

    private static final int CAPACITY = 32_768;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;

    private static final Map<Key, List<CgShapedRun>> MAP = new LinkedHashMap<>(1024, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Key, List<CgShapedRun>> eldest) {
            boolean evict = size() > CAPACITY;
            if (evict) cachedBytes -= estimateBytes(eldest.getValue());
            return evict;
        }
    };
    private static long cachedBytes;

    private CgShapedRunCache() {}

    /** {@code paragraph}'s runs in {@code family}, shaped by {@code shape} on a miss. */
    public static List<CgShapedRun> runs(String paragraph, CgFontFamily family, CgTextDirection direction,
                                         float tabStopWidth, Supplier<List<CgShapedRun>> shape) {
        Key key = new Key(paragraph, familyKey(family), direction, tabStopWidth);
        synchronized (CgShapedRunCache.class) {
            List<CgShapedRun> cached = MAP.get(key);
            if (cached != null && live(cached)) {
                CgTrace.add(CgChannels.TEXT, "shapeCache.hit", 1);
                return cached;
            }
        }
        CgTrace.add(CgChannels.TEXT, "shapeCache.miss", 1);
        List<CgShapedRun> shaped = shape.get();
        if (!declaredOnly(shaped, key.family.fonts())) return shaped;
        List<CgShapedRun> kept = Collections.unmodifiableList(shaped);
        synchronized (CgShapedRunCache.class) {
            List<CgShapedRun> replaced = MAP.put(key, kept);
            if (replaced != null) cachedBytes -= estimateBytes(replaced);
            cachedBytes += estimateBytes(kept);
            trimToByteBudget();
        }
        return kept;
    }

    /** Drops every entry: a font reload changed what a paragraph shapes to. */
    public static synchronized void clear() {
        MAP.clear();
        cachedBytes = 0L;
    }

    /** Entry count, for tests. */
    public static synchronized int size() {
        return MAP.size();
    }

    /**
     * What shaping in {@code family} depends on: its declared fonts by value and its fallback by identity. Two families
     * built from the same fonts are one key, which a family rebuilt each call ({@code CgFontFamily.of(font)}) needs.
     */
    public static FamilyKey familyKey(CgFontFamily family) {
        List<CgFontSource> sources = family.getAllSources();
        List<CgFontKey> keys = new ArrayList<>(sources.size());
        for (CgFontSource source : sources) keys.add(source.getKey());
        return new FamilyKey(keys, family.getFallback());
    }

    /** {@link #familyKey}'s answer: fonts compared by value, the fallback by identity. */
    public record FamilyKey(List<CgFontKey> fonts, Object fallback) {
        @Override
        public int hashCode() {
            return 31 * fonts.hashCode() + System.identityHashCode(fallback);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FamilyKey k && fallback == k.fallback && fonts.equals(k.fonts);
        }
    }

    private static boolean live(List<CgShapedRun> runs) {
        for (CgShapedRun run : runs) {
            if (run.resolvedFont() != null && run.resolvedFont().isDisposed()) return false;
        }
        return true;
    }

    private static boolean declaredOnly(List<CgShapedRun> runs, List<CgFontKey> declared) {
        for (CgShapedRun run : runs) {
            if (run.fontKey() != null && !declared.contains(run.fontKey())) return false;
        }
        return true;
    }

    private static long estimateBytes(List<CgShapedRun> runs) {
        long bytes = 64L;
        for (CgShapedRun run : runs) bytes += 96L + (run.glyphIds() != null ? run.glyphIds().length * 24L : 0L);
        return bytes;
    }

    private static void trimToByteBudget() {
        Iterator<Map.Entry<Key, List<CgShapedRun>>> it = MAP.entrySet().iterator();
        while (cachedBytes > MAX_BYTES && MAP.size() > 1 && it.hasNext()) {
            cachedBytes -= estimateBytes(it.next().getValue());
            it.remove();
        }
    }

    private record Key(String text, FamilyKey family, CgTextDirection direction, float tabStop) {
    }
}
