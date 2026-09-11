package com.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.text.font.ScriptFallbacks;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ordered family/fallback chain for shaping and rendering.
 *
 * <p>The primary source is always consulted first. Fallback sources are checked
 * in deterministic order. The current implementation requires all sources in a
 * family to share the same logical target pixel size so one layout can be drawn
 * consistently.</p>
 *
 * <pre>{@code
 * CgFontFamily family = CgFontFamily.of(latin, arabic);
 *
 * // and whatever neither covers comes from the installed fonts, as in any application
 * CgFontFamily anything = family.withFallback(CgSystemFonts.get().fallback(Locale.getDefault()));
 * }</pre>
 *
 * <p>A {@link CgFontFallback} is asked only for a character no declared source covers, once per
 * code point. A font it supplies joins {@link #getDiscoveredSources()} and is tried before it is
 * asked again — except by a Han character in text that shows its language, which is asked about in
 * that language first: 日 in 日本語です is Japanese whatever drew 日 before.
 * {@link #getLayoutMetrics()} stays the declared sources', so discovering a tall script never moves a
 * line box already laid out.</p>
 */
@Getter
@ToString
public final class CgFontFamily {

    private static final Logger LOGGER = Logger.getLogger(CgFontFamily.class.getName());
    private static final CgFontSource[] NO_SOURCES = new CgFontSource[0];

    private final String familyId;
    private final CgFontSource primarySource;
    private final List<CgFontSource> fallbackSources;
    private final List<CgFontSource> allSources;
    private final Map<CgFontKey, CgFontSource> sourcesByKey;
    private final CgFontMetrics layoutMetrics;
    private final int targetPx;
    /** Asked for a character no declared source covers; {@code null} for none. */
    private final CgFontFallback fallback;

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private volatile CgFontSource[] discovered = NO_SOURCES;
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private final Map<CgFontKey, CgFontSource> discoveredByKey = new ConcurrentHashMap<CgFontKey, CgFontSource>();
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private final Set<Long> uncovered = ConcurrentHashMap.newKeySet();

    public CgFontFamily(CgFontSource primarySource, List<CgFontSource> fallbackSources) {
        this(null, primarySource, fallbackSources, null);
    }

    public CgFontFamily(String familyId, CgFontSource primarySource, List<CgFontSource> fallbackSources) {
        this(familyId, primarySource, fallbackSources, null);
    }

    public CgFontFamily(String familyId, CgFontSource primarySource, List<CgFontSource> fallbackSources,
                        CgFontFallback fallback) {
        if (primarySource == null) {
            throw new IllegalArgumentException("primarySource must not be null");
        }

        this.familyId = familyId;
        this.primarySource = primarySource;
        this.targetPx = primarySource.getKey().getTargetPx();

        List<CgFontSource> ordered = new ArrayList<CgFontSource>();
        ordered.add(primarySource);

        List<CgFontSource> fallbacks = fallbackSources != null
                ? new ArrayList<CgFontSource>(fallbackSources)
                : Collections.<CgFontSource>emptyList();
        for (CgFontSource source : fallbacks) {
            if (source == null) {
                throw new IllegalArgumentException("fallbackSources must not contain null entries");
            }
            if (source.getKey().getTargetPx() != targetPx) {
                throw new IllegalArgumentException(
                        "All family sources must share the same targetPx. Expected "
                                + targetPx + ", got " + source.getKey().getTargetPx()
                                + " for " + source.getKey());
            }
            ordered.add(source);
        }

        this.fallbackSources = Collections.unmodifiableList(fallbacks);
        this.allSources = Collections.unmodifiableList(ordered);
        this.sourcesByKey = Collections.unmodifiableMap(indexSources(ordered));
        this.layoutMetrics = combineMetrics(ordered);
        this.fallback = fallback;
    }

    public static CgFontFamily of(CgFont primary, CgFont... fallbacks) {
        if (primary == null) {
            throw new IllegalArgumentException("primary must not be null");
        }
        List<CgFontSource> fallbackSources = new ArrayList<CgFontSource>();
        if (fallbacks != null) {
            for (CgFont fallback : fallbacks) {
                if (fallback == null) {
                    throw new IllegalArgumentException("fallbacks must not contain null entries");
                }
                fallbackSources.add(new CgFontSource(fallback));
            }
        }
        return new CgFontFamily(new CgFontSource(primary), fallbackSources);
    }

    /** These sources, asking {@code fallback} for any character none of them covers. */
    public CgFontFamily withFallback(CgFontFallback fallback) {
        return new CgFontFamily(familyId, primarySource, fallbackSources, fallback);
    }

    /**
     * This family at {@code targetPx}: every declared source re-sized, the same fallback. Returns
     * this family when it is already that size.
     */
    public CgFontFamily atSize(int targetPx) {
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        if (targetPx == this.targetPx) {
            return this;
        }
        List<CgFontSource> sized = new ArrayList<CgFontSource>(fallbackSources.size());
        for (CgFontSource source : fallbackSources) {
            sized.add(new CgFontSource(source.requireFont().atSize(targetPx), source.getSourceLabel()));
        }
        return new CgFontFamily(familyId,
                new CgFontSource(primarySource.requireFont().atSize(targetPx), primarySource.getSourceLabel()),
                sized, fallback);
    }

    /** What {@link #getFallback()} has supplied so far, in the order it was found. */
    public List<CgFontSource> getDiscoveredSources() {
        return Collections.unmodifiableList(Arrays.asList(discovered));
    }

    public CgFont getPrimaryFont() {
        return primarySource.requireFont();
    }

    public CgFont resolveLoadedFont(CgFontKey key) {
        CgFontSource source = sourcesByKey.get(key);
        if (source == null) {
            source = discoveredByKey.get(key);
        }
        if (source == null) {
            throw new IllegalArgumentException("Font key is not part of this family: " + key);
        }
        return source.requireFont();
    }

    /**
     * Retrieves the native HarfBuzz font handle for {@code key} — used by
     * {@link com.crystalgraphics.text.layout.CgTextLayoutEngine} to re-shape a run fragment
     * during line-break splitting. Public because the layout engine lives in {@code text/layout},
     * not this package.
     */
    public HBFont requireShapingFont(CgFontKey key) {
        return resolveLoadedFont(key).getHbFontInternal();
    }

    public CgFontSource resolveSourceForCodePoint(int codePoint) {
        return resolveSourceForCodePoint(codePoint, null);
    }

    CgFontSource resolveSourceForCodePoint(int codePoint, CgFontSource previousSource) {
        if (isContinuationCodePoint(codePoint) && previousSource != null) {
            if (isStickyContinuationCodePoint(codePoint) || previousSource.canDisplayCodePoint(codePoint)) {
                return previousSource;
            }
        }

        for (CgFontSource source : allSources) {
            if (source.canDisplayCodePoint(codePoint)) {
                return source;
            }
        }
        for (CgFontSource source : discovered) {
            if (source.canDisplayCodePoint(codePoint)) {
                return source;
            }
        }
        CgFontSource found = discover(codePoint, null);
        if (found != null) {
            return found;
        }
        return previousSource != null ? previousSource : primarySource;
    }

    /**
     * Splits {@code text[start,end)} into font-fallback segments, resolving which family
     * source displays each grapheme cluster. Used by
     * {@link com.crystalgraphics.text.layout.CgTextLayoutEngine} to collect and shape runs.
     * Public because the layout engine lives in {@code text/layout}, not this package.
     */
    public List<ResolvedFontRun> resolveRuns(String text, int start, int end) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (start < 0 || end > text.length() || start > end) {
            throw new IllegalArgumentException(
                    "Invalid range: start=" + start + ", end=" + end + ", text.length=" + text.length());
        }
        if (start == end) {
            return Collections.emptyList();
        }

        List<ResolvedFontRun> resolved = new ArrayList<ResolvedFontRun>();
        TextLanguage textLanguage = new TextLanguage(text);
        CgFontSource activeSource = null;
        int segmentStart = start;
        int index = start;
        while (index < end) {
            int clusterEnd = advanceCluster(text, index, end);
            CgFontSource source = resolveSourceForCluster(text, index, clusterEnd, activeSource, textLanguage);
            if (activeSource == null) {
                activeSource = source;
                segmentStart = index;
            } else if (!activeSource.getKey().equals(source.getKey())) {
                resolved.add(new ResolvedFontRun(activeSource, segmentStart, index));
                activeSource = source;
                segmentStart = index;
            }
            index = clusterEnd;
        }

        if (activeSource != null) {
            resolved.add(new ResolvedFontRun(activeSource, segmentStart, end));
        }
        return resolved;
    }

    private static Map<CgFontKey, CgFontSource> indexSources(List<CgFontSource> orderedSources) {
        Map<CgFontKey, CgFontSource> indexed = new LinkedHashMap<CgFontKey, CgFontSource>();
        for (CgFontSource source : orderedSources) {
            CgFontSource previous = indexed.put(source.getKey(), source);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate font source key in family: " + source.getKey());
            }
        }
        return indexed;
    }

    private static CgFontMetrics combineMetrics(List<CgFontSource> sources) {
        List<CgFontMetrics> metricsList = new ArrayList<CgFontMetrics>(sources.size());
        for (CgFontSource source : sources) {
            metricsList.add(source.getMetrics());
        }
        return combineMetrics(metricsList);
    }

    /**
     * Combines multiple faces' metrics into one "max over every dimension" metrics — the
     * same reduction a family uses to compute its own {@link #getLayoutMetrics()}, exposed
     * here for reuse when a single line mixes runs shaped through different families (e.g.
     * a bold run alongside a regular one) and needs the max over all of them.
     *
     * @throws IllegalArgumentException if {@code metricsList} is empty
     */
    public static CgFontMetrics combineMetrics(Iterable<CgFontMetrics> metricsList) {
        float ascender = 0.0f;
        float descender = 0.0f;
        float lineGap = 0.0f;
        float lineHeight = 0.0f;
        float xHeight = 0.0f;
        float capHeight = 0.0f;
        boolean any = false;
        for (CgFontMetrics metrics : metricsList) {
            any = true;
            ascender = Math.max(ascender, metrics.getAscender());
            descender = Math.max(descender, metrics.getDescender());
            lineGap = Math.max(lineGap, metrics.getLineGap());
            lineHeight = Math.max(lineHeight, metrics.getLineHeight());
            xHeight = Math.max(xHeight, metrics.getXHeight());
            capHeight = Math.max(capHeight, metrics.getCapHeight());
        }
        if (!any) {
            throw new IllegalArgumentException("metricsList must not be empty");
        }
        return new CgFontMetrics(ascender, descender, lineGap, lineHeight, xHeight, capHeight);
    }

    private CgFontSource resolveSourceForCluster(String text,
                                                 int clusterStart,
                                                 int clusterEnd,
                                                 CgFontSource previousSource,
                                                 TextLanguage textLanguage) {
        int firstCodePoint = Character.codePointAt(text, clusterStart);

        for (CgFontSource source : allSources) {
            if (!source.canDisplayCodePoint(firstCodePoint)) {
                continue;
            }
            if (clusterFitsSource(text, clusterStart, clusterEnd, source)) {
                return source;
            }
        }
        // Han follows what its own text says before anything an earlier text discovered: 日 in 日本語です
        // is Japanese even when a Chinese face drew 日 somewhere else first.
        Locale language = fallback != null && ScriptFallbacks.isHan(firstCodePoint) ? textLanguage.get() : null;
        if (language != null) {
            CgFontSource found = discover(firstCodePoint, language);
            if (found != null) {
                return found;
            }
        }
        for (CgFontSource source : discovered) {
            if (source.canDisplayCodePoint(firstCodePoint)
                    && clusterFitsSource(text, clusterStart, clusterEnd, source)) {
                return source;
            }
        }
        // A face that draws the base but not every mark still beats the primary, which draws neither.
        CgFontSource found = discover(firstCodePoint, null);
        if (found != null) {
            return found;
        }

        return previousSource != null ? previousSource : primarySource;
    }

    /**
     * Asks the fallback once per code point and language, remembering a {@code null} so a missing
     * glyph costs one question.
     */
    private CgFontSource discover(int codePoint, Locale language) {
        if (fallback == null || !ScriptFallbacks.isFallbackCandidate(codePoint)) {
            return null;
        }
        int languageId = language == null ? 0 : language.getLanguage().hashCode();
        Long key = Long.valueOf(((long) languageId << 32) | (codePoint & 0xFFFFFFFFL));
        if (uncovered.contains(key)) {
            return null;
        }
        CgFont font;
        try {
            CgFontStyle style = primarySource.getKey().getStyle();
            font = language == null
                    ? fallback.fontFor(codePoint, style, targetPx)
                    : fallback.fontFor(codePoint, style, targetPx, language);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Font fallback failed for U+" + Integer.toHexString(codePoint), e);
            font = null;
        }
        if (font == null || font.isDisposed() || !font.isSizeBound() || font.getTargetPx() != targetPx
                || !font.canDisplayCodePoint(codePoint)) {
            uncovered.add(key);
            return null;
        }
        return adopt(font);
    }

    private synchronized CgFontSource adopt(CgFont font) {
        CgFontKey key = font.getKey();
        CgFontSource known = sourcesByKey.get(key);
        if (known == null) {
            known = discoveredByKey.get(key);
        }
        if (known != null) {
            return known;
        }
        CgFontSource source = new CgFontSource(font);
        discoveredByKey.put(key, source);
        CgFontSource[] grown = Arrays.copyOf(discovered, discovered.length + 1);
        grown[grown.length - 1] = source;
        discovered = grown;
        return source;
    }

    /** The language a text's own letters show, found on first need and once per {@link #resolveRuns} call. */
    private static final class TextLanguage {

        private final String text;
        private boolean known;
        private Locale language;

        TextLanguage(String text) {
            this.text = text;
        }

        Locale get() {
            if (!known) {
                language = ScriptFallbacks.languageShownBy(text);
                known = true;
            }
            return language;
        }
    }

    private static boolean clusterFitsSource(String text,
                                             int clusterStart,
                                             int clusterEnd,
                                             CgFontSource source) {
        int index = clusterStart;
        boolean first = true;
        while (index < clusterEnd) {
            int codePoint = Character.codePointAt(text, index);
            if (!first && isStickyContinuationCodePoint(codePoint)) {
                index += Character.charCount(codePoint);
                continue;
            }
            if (!source.canDisplayCodePoint(codePoint)) {
                return false;
            }
            first = false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static int advanceCluster(String text, int start, int end) {
        int index = start;
        index += Character.charCount(Character.codePointAt(text, index));
        while (index < end) {
            int codePoint = Character.codePointAt(text, index);
            if (!isContinuationCodePoint(codePoint)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static boolean isContinuationCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT
                || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF);
    }

    private static boolean isStickyContinuationCodePoint(int codePoint) {
        return codePoint == 0x200C
                || codePoint == 0x200D
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    /**
     * One font-fallback segment of a {@link #resolveRuns} result — a {@code [start,end)}
     * sub-range and the family source that displays it. Public because
     * {@link com.crystalgraphics.text.layout.CgTextLayoutEngine} (in {@code text/layout})
     * consumes {@link #resolveRuns}'s return value directly.
     */
    public static final class ResolvedFontRun {

        private final CgFontSource source;
        private final int start;
        private final int end;

        ResolvedFontRun(CgFontSource source, int start, int end) {
            if (source == null) {
                throw new IllegalArgumentException("source must not be null");
            }
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid range: start=" + start + ", end=" + end);
            }
            this.source = source;
            this.start = start;
            this.end = end;
        }

        public CgFontSource getSource() {
            return source;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public CgFontKey getFontKey() {
            return source.getKey();
        }

        public HBFont requireHbFont() {
            return source.requireFont().getHbFontInternal();
        }
    }
}
