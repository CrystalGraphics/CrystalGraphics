package com.crystalgraphics.api.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plain text plus a sorted, non-overlapping set of {@link CgStyleSpan}s — the public IR
 * markup parsers ({@code text.richtext.CgMarkupParser} implementations) produce and the
 * layout engine will eventually consume (BiDi ∩ style-span splitting, not yet wired —
 * that's a later phase).
 *
 * <p>{@code spans} is sorted by {@link CgStyleSpan#start()} and validated non-overlapping
 * at construction time regardless of the order the caller supplied them in.</p>
 *
 * @param plainText the text every span's {@code start}/{@code end} indexes into
 * @param spans     style spans, sorted ascending by start, non-overlapping, each within {@code plainText}'s bounds
 */
public record CgStyledText(String plainText, List<CgStyleSpan> spans) {

    public CgStyledText {
        if (plainText == null) {
            throw new IllegalArgumentException("plainText must not be null");
        }

        List<CgStyleSpan> sorted = new ArrayList<>(spans == null ? List.of() : spans);
        sorted.sort(Comparator.comparingInt(CgStyleSpan::start));

        for (int i = 0; i < sorted.size(); i++) {
            CgStyleSpan span = sorted.get(i);
            if (span.end() > plainText.length()) {
                throw new IllegalArgumentException(
                        "Span end " + span.end() + " exceeds text length " + plainText.length());
            }
            if (i > 0 && span.start() < sorted.get(i - 1).end()) {
                CgStyleSpan prev = sorted.get(i - 1);
                throw new IllegalArgumentException("Overlapping spans: [" + prev.start() + "," + prev.end()
                        + ") and [" + span.start() + "," + span.end() + ")");
            }
        }

        spans = List.copyOf(sorted);
    }

    /** Plain text with no style spans. */
    public static CgStyledText plain(String text) {
        return new CgStyledText(text, List.of());
    }
}
