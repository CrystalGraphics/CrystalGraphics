package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBFont;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered family/fallback chain for shaping and rendering.
 *
 * <p>The primary source is always consulted first. Fallback sources are checked
 * in deterministic order. The current implementation requires all sources in a
 * family to share the same logical target pixel size so one layout can be drawn
 * consistently.</p>
 */
@Getter
@ToString
public final class CgFontFamily {

    private final String familyId;
    private final CgFontSource primarySource;
    private final List<CgFontSource> fallbackSources;
    private final List<CgFontSource> allSources;
    private final Map<CgFontKey, CgFontSource> sourcesByKey;
    private final CgFontMetrics layoutMetrics;
    private final int targetPx;

    public CgFontFamily(CgFontSource primarySource, List<CgFontSource> fallbackSources) {
        this(null, primarySource, fallbackSources);
    }

    public CgFontFamily(String familyId, CgFontSource primarySource, List<CgFontSource> fallbackSources) {
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
        for (CgFontSource fallback : fallbacks) {
            if (fallback == null) {
                throw new IllegalArgumentException("fallbackSources must not contain null entries");
            }
            if (fallback.getKey().getTargetPx() != targetPx) {
                throw new IllegalArgumentException(
                        "All family sources must share the same targetPx. Expected "
                                + targetPx + ", got " + fallback.getKey().getTargetPx()
                                + " for " + fallback.getKey());
            }
            ordered.add(fallback);
        }

        this.fallbackSources = Collections.unmodifiableList(fallbacks);
        this.allSources = Collections.unmodifiableList(ordered);
        this.sourcesByKey = Collections.unmodifiableMap(indexSources(ordered));
        this.layoutMetrics = combineMetrics(ordered);
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

    public CgFont getPrimaryFont() {
        return primarySource.requireFont();
    }

    public CgFont resolveLoadedFont(CgFontKey key) {
        CgFontSource source = sourcesByKey.get(key);
        if (source == null) {
            throw new IllegalArgumentException("Font key is not part of this family: " + key);
        }
        return source.requireFont();
    }

    HBFont requireShapingFont(CgFontKey key) {
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
        return previousSource != null ? previousSource : primarySource;
    }

    List<ResolvedFontRun> resolveRuns(String text, int start, int end) {
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
        CgFontSource activeSource = null;
        int segmentStart = start;
        int index = start;
        while (index < end) {
            int clusterEnd = advanceCluster(text, index, end);
            CgFontSource source = resolveSourceForCluster(text, index, clusterEnd, activeSource);
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
        float ascender = 0.0f;
        float descender = 0.0f;
        float lineGap = 0.0f;
        float lineHeight = 0.0f;
        float xHeight = 0.0f;
        float capHeight = 0.0f;
        for (CgFontSource source : sources) {
            CgFontMetrics metrics = source.getMetrics();
            ascender = Math.max(ascender, metrics.getAscender());
            descender = Math.max(descender, metrics.getDescender());
            lineGap = Math.max(lineGap, metrics.getLineGap());
            lineHeight = Math.max(lineHeight, metrics.getLineHeight());
            xHeight = Math.max(xHeight, metrics.getXHeight());
            capHeight = Math.max(capHeight, metrics.getCapHeight());
        }
        return new CgFontMetrics(ascender, descender, lineGap, lineHeight, xHeight, capHeight);
    }

    private CgFontSource resolveSourceForCluster(String text,
                                                 int clusterStart,
                                                 int clusterEnd,
                                                 CgFontSource previousSource) {
        int firstCodePoint = Character.codePointAt(text, clusterStart);

        for (CgFontSource source : allSources) {
            if (!source.canDisplayCodePoint(firstCodePoint)) {
                continue;
            }
            if (clusterFitsSource(text, clusterStart, clusterEnd, source)) {
                return source;
            }
        }

        return previousSource != null ? previousSource : primarySource;
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

    static final class ResolvedFontRun {

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

        CgFontSource getSource() {
            return source;
        }

        int getStart() {
            return start;
        }

        int getEnd() {
            return end;
        }

        CgFontKey getFontKey() {
            return source.getKey();
        }

        HBFont requireHbFont() {
            return source.requireFont().getHbFontInternal();
        }
    }
}
