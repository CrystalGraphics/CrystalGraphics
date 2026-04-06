package io.github.somehussar.crystalgraphics.api.font;

import lombok.Getter;
import lombok.ToString;

import java.util.function.IntPredicate;

/**
 * One concrete font source inside a family/fallback chain.
 *
 * <p>A source usually wraps one loaded {@link CgFont}, but tests may provide a
 * synthetic coverage probe without a live font handle. Rendering requires a live
 * font; pure resolution tests do not.</p>
 */
@Getter
@ToString
public final class CgFontSource {

    private final CgFont font;
    private final CgFontKey key;
    private final CgFontMetrics metrics;
    private final String sourceLabel;
    private final IntPredicate coverageProbe;

    public CgFontSource(CgFont font) {
        this(font, font != null ? font.getKey().getFontPath() : null);
    }

    public CgFontSource(CgFont font, String sourceLabel) {
        if (font == null || font.isDisposed()) {
            throw new IllegalArgumentException("font must not be null or disposed");
        }
        this.font = font;
        this.key = font.getKey();
        this.metrics = font.getMetrics();
        this.sourceLabel = sourceLabel != null ? sourceLabel : font.getKey().getFontPath();
        this.coverageProbe = new IntPredicate() {
            @Override
            public boolean test(int value) {
                return font.canDisplayCodePoint(value);
            }
        };
    }

    /**
     * Package-private synthetic source constructor used by pure-Java tests.
     */
    CgFontSource(CgFontKey key,
                 CgFontMetrics metrics,
                 String sourceLabel,
                 IntPredicate coverageProbe) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (coverageProbe == null) {
            throw new IllegalArgumentException("coverageProbe must not be null");
        }
        this.font = null;
        this.key = key;
        this.metrics = metrics;
        this.sourceLabel = sourceLabel != null ? sourceLabel : key.getFontPath();
        this.coverageProbe = coverageProbe;
    }

    public boolean hasLoadedFont() {
        return font != null && !font.isDisposed();
    }

    public CgFont requireFont() {
        if (!hasLoadedFont()) {
            throw new IllegalStateException("Font source has no live CgFont handle: " + key);
        }
        return font;
    }

    public boolean canDisplayCodePoint(int codePoint) {
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
            return false;
        }
        return coverageProbe.test(codePoint);
    }
}
