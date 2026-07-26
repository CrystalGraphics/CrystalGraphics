package com.crystalgraphics.api.text;

import lombok.Builder;

import java.util.List;

/**
 * A styled range within a {@link CgStyledText}'s plain text.
 *
 * <p>{@code fontFamilyOverride}/{@code fontFeatures} default to an empty list when
 * unset (via the builder or a {@code null} constructor argument) rather than
 * {@code null}, so consumers never need a null-check before iterating them.
 * {@code decoration} defaults to {@link CgTextDecoration#NONE}. {@code argbColor}
 * of {@code 0} means "inherit the draw's default color" — not literal transparent
 * black, since a span never has a reason to render fully invisible.</p>
 *
 * @param start              start index (inclusive) into the owning {@code CgStyledText}'s plain text
 * @param end                end index (exclusive); must be {@code > start}
 * @param bold               bold weight
 * @param italic             italic style
 * @param decoration         underline/strikethrough/overline; {@link CgTextDecoration#NONE} by default
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
        CgTextDecoration decoration,
        int argbColor,
        List<String> fontFamilyOverride,
        List<CgFontFeature> fontFeatures,
        float baselineShift
) {

    public CgStyleSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid span range: start=" + start + ", end=" + end);
        }
        if (decoration == null) {
            decoration = CgTextDecoration.NONE;
        }
        fontFamilyOverride = fontFamilyOverride == null ? List.of() : List.copyOf(fontFamilyOverride);
        fontFeatures = fontFeatures == null ? List.of() : List.copyOf(fontFeatures);
    }
}
