package com.crystalgraphics.api.text;

import lombok.Builder;

import java.util.List;
import java.util.Set;

/**
 * A styled range within a {@link CgStyledText}'s plain text.
 *
 * <p>{@code fontFamilyOverride}/{@code fontFeatures}/{@code decorations} default to an empty
 * collection when unset (via the builder or a {@code null} constructor argument) rather than
 * {@code null}, so consumers never need a null-check before iterating them. {@code argbColor}
 * of {@code 0} means "inherit the draw's default color" — not literal transparent black, since
 * a span never has a reason to render fully invisible.</p>
 *
 * @param start              start index (inclusive) into the owning {@code CgStyledText}'s plain text
 * @param end                end index (exclusive); must be {@code > start}
 * @param bold               bold weight
 * @param italic             italic style
 * @param decorations        underline/strikethrough/overline, any combination; empty = none
 * @param argbColor          override color, {@code 0} = inherit the draw's default color
 * @param fontFamilyOverride font family names to try, in order, before the paragraph's own family; empty = inherit
 * @param fontFeatures       OpenType feature tags to enable for this span's shaping; empty = none
 * @param baselineShift      vertical baseline offset in logical pixels; {@code 0} = none (super/subscript positioning)
 */
@Builder
public record CgStyleSpan(
        int start,
        int end,
        boolean bold,
        boolean italic,
        Set<CgTextDecoration> decorations,
        int argbColor,
        List<String> fontFamilyOverride,
        List<CgFontFeature> fontFeatures,
        float baselineShift
) {

    public CgStyleSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid span range: start=" + start + ", end=" + end);
        }
        decorations = decorations == null || decorations.isEmpty() ? Set.of() : Set.copyOf(decorations);
        fontFamilyOverride = fontFamilyOverride == null ? List.of() : List.copyOf(fontFamilyOverride);
        fontFeatures = fontFeatures == null ? List.of() : List.copyOf(fontFeatures);
    }
}
