package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable key identifying a specific font registration.
 *
 * <p>A {@code CgFontKey} uniquely identifies a font by its path (absolute filesystem
 * path or resource ID), style variant, target pixel size, and optional variable-font
 * axis coordinates. Two keys with identical fields are considered equal, making this
 * type safe for use as a {@link java.util.Map} key or {@link java.util.Set} element.</p>
 *
 * <p>The {@code targetPx} field specifies the render size in pixels (e.g., 12, 32, 48).
 * Different pixel sizes produce separate atlas buckets since glyph bitmaps are
 * size-dependent.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * CgFontKey key = new CgFontKey("/fonts/NotoSans-Regular.ttf", CgFontStyle.REGULAR, 12);
 * CgFontKey same = new CgFontKey("/fonts/NotoSans-Regular.ttf", CgFontStyle.REGULAR, 12);
 * assert key.equals(same);  // true — value equality
 * </pre>
 *
 * @see CgFontStyle
 * @see CgGlyphKey
 */
@Value
public class CgFontKey {

    /** Absolute path or resource ID of the font file. */
    String fontPath;

    /** Style variant (regular, bold, italic, bold-italic). */
    CgFontStyle style;

    /** Target render size in pixels (e.g., 12, 32, 48). */
    int targetPx;

    List<CgFontVariation> variations;

    public CgFontKey(String fontPath, CgFontStyle style, int targetPx) {
        this(fontPath, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public CgFontKey(String fontPath,
                     CgFontStyle style,
                     int targetPx,
                     List<CgFontVariation> variations) {
        if (fontPath == null) {
            throw new IllegalArgumentException("fontPath must not be null");
        }
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        this.fontPath = fontPath;
        this.style = style;
        this.targetPx = targetPx;
        this.variations = canonicalizeVariations(variations);
    }

    public boolean hasVariations() {
        return !variations.isEmpty();
    }

    public CgFontKey withTargetPx(int newTargetPx) {
        return new CgFontKey(fontPath, style, newTargetPx, variations);
    }

    private static List<CgFontVariation> canonicalizeVariations(List<CgFontVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return Collections.emptyList();
        }
        List<CgFontVariation> sorted = new ArrayList<CgFontVariation>(variations.size());
        for (CgFontVariation variation : variations) {
            if (variation == null) {
                throw new IllegalArgumentException("variations must not contain null entries");
            }
            sorted.add(variation);
        }
        Collections.sort(sorted, new Comparator<CgFontVariation>() {
            @Override
            public int compare(CgFontVariation left, CgFontVariation right) {
                return left.getTag().compareTo(right.getTag());
            }
        });
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).getTag().equals(sorted.get(i).getTag())) {
                throw new IllegalArgumentException(
                        "Duplicate variation axis tag in font key: " + sorted.get(i).getTag());
            }
        }
        return Collections.unmodifiableList(sorted);
    }
}
