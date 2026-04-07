package io.github.somehussar.crystalgraphics.text.layout;

import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgFontSource;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal base implementation of the text layout pipeline.
 *
 * <p>The concrete shaping/fallback bridge is supplied by a subclass so the real
 * algorithm can live in the {@code text} package while package-private font
 * internals remain encapsulated inside {@code api/font}.</p>
 */
public abstract class CgTextLayoutEngine {

    private final CgLineBreaker lineBreaker = new CgLineBreaker();

    public CgTextLayout layout(String text, CgFont font, float maxWidth, float maxHeight) {
        return layout(text, CgFontFamily.of(font), maxWidth, maxHeight,
                font != null ? font.getKey().getTargetPx() : 0);
    }

    public CgTextLayout layout(String text, CgFont font,
                               float maxWidth, float maxHeight, float logicalPx) {
        return layout(text, CgFontFamily.of(font), maxWidth, maxHeight, logicalPx);
    }

    public CgTextLayout layout(String text, CgFontFamily family, float maxWidth, float maxHeight) {
        return layout(text, family, maxWidth, maxHeight,
                family != null ? family.getTargetPx() : 0);
    }

    public CgTextLayout layout(String text, CgFontFamily family,
                               float maxWidth, float maxHeight, float logicalPx) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");

        CgFontMetrics metrics = family.getLayoutMetrics();
        float targetPx = family.getTargetPx();

        float scale = (logicalPx > 0 && targetPx > 0) ? targetPx / logicalPx : 1.0f;
        float inverseScale = (logicalPx > 0 && targetPx > 0) ? logicalPx / targetPx : 1.0f;

        if (text.isEmpty()) {
            List<List<CgShapedRun>> emptyLines = new ArrayList<>();
            return new CgTextLayout(emptyLines, 0, 0, metrics, resolvedFontsByKey(family));
        }

        float targetMaxWidth = maxWidth > 0 ? maxWidth * scale : maxWidth;
        float targetMaxHeight = maxHeight > 0 ? maxHeight * scale : maxHeight;

        List<String> paragraphs = splitParagraphs(text);

        List<List<CgShapedRun>> allLines = new ArrayList<>();
        float totalHeight = 0.0f;
        float lineHeight = metrics.getLineHeight();

        for (String paragraph : paragraphs) {
            if (targetMaxHeight > 0 && totalHeight + lineHeight > targetMaxHeight) {
                break;
            }

            if (paragraph.isEmpty()) {
                allLines.add(new ArrayList<>());
                totalHeight += lineHeight;
                continue;
            }

            List<CgShapedRun> shapedRuns = splitAndShapeRuns(paragraph, family);
            float remainingHeight = targetMaxHeight > 0 ? targetMaxHeight - totalHeight : 0;
            List<List<CgShapedRun>> paraLines = lineBreaker.breakLines(
                    shapedRuns, targetMaxWidth, remainingHeight, metrics, createRunReshaper(family));

            allLines.addAll(paraLines);
            totalHeight += paraLines.size() * lineHeight;
        }

        float totalWidth = 0;
        for (List<CgShapedRun> line : allLines) {
            float lineWidth = 0;
            for (CgShapedRun run : line) {
                lineWidth += run.getTotalAdvance();
            }
            if (lineWidth > totalWidth) {
                totalWidth = lineWidth;
            }
        }
        totalHeight = allLines.size() * lineHeight;

        return new CgTextLayout(allLines, totalWidth * inverseScale, totalHeight * inverseScale, metrics, resolvedFontsByKey(family));
    }

    protected List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<String>();
        int len = text.length();
        int start = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                paragraphs.add(text.substring(start, i));
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            } else if (c == '\n') {
                paragraphs.add(text.substring(start, i));
                start = i + 1;
            }
        }

        paragraphs.add(text.substring(start));
        return paragraphs;
    }

    private List<CgShapedRun> splitAndShapeRuns(String text, CgFontFamily family) {
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        int runCount = bidi.getRunCount();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>(runCount);

        for (int i = 0; i < runCount; i++) {
            int start = bidi.getRunStart(i);
            int end = bidi.getRunLimit(i);
            int level = bidi.getRunLevel(i);
            boolean rtl = (level % 2) != 0;
            collectShapedRuns(text, start, end, rtl, family, runs);
        }

        return runs;
    }

    protected abstract void collectShapedRuns(String text, int start, int end, boolean rtl, CgFontFamily family, List<CgShapedRun> out);
    
    protected abstract RunReshaper createRunReshaper(CgFontFamily family);

    private static Map<CgFontKey, CgFont> resolvedFontsByKey(CgFontFamily family) {
        Map<CgFontKey, CgFont> resolved = new LinkedHashMap<CgFontKey, CgFont>();
        for (CgFontSource source : family.getAllSources()) {
            if (source.hasLoadedFont()) {
                resolved.put(source.getKey(), source.requireFont());
            }
        }
        return Collections.unmodifiableMap(resolved);
    }
}
