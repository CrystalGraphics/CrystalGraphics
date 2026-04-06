package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Text slice resolved to one concrete font source before shaping.
 */
@Value
final class CgResolvedFontRun {

    CgFontSource source;
    int start;
    int end;

    CgResolvedFontRun(CgFontSource source, int start, int end) {
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

    CgFontKey getFontKey() {
        return source.getKey();
    }
}
