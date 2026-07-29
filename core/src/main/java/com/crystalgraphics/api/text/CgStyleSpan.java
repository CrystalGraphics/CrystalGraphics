package com.crystalgraphics.api.text;

import lombok.Builder;

import java.util.List;
import java.util.Set;

/**
 * A styled range within a {@link CgStyledText}'s plain text.
 *
 * <p>{@code fontFeatures}/{@code decorations} default to an empty
 * collection when unset (via the builder or a {@code null} constructor argument) rather than
 * {@code null}, so consumers never need a null-check before iterating them. {@code argbColor}
 * of {@code 0} means "inherit the draw's default color" — not literal transparent black, since
 * a span never has a reason to render fully invisible.</p>
 *
 * <h3>Every field here is consumed</h3>
 * <p>Three of them once were not — {@code fontFeatures}, {@code baselineShift} and a
 * {@code fontFamilyOverride} that no longer exists. All three were carried faithfully from here
 * onto {@code CgShapedRun} and then dropped at the last step, so setting one did nothing and said
 * nothing. The first two were wired up on 2026-07-29 (see {@code CgSpanEffectTest});
 * {@code fontFamilyOverride} was <strong>removed</strong> rather than implemented, because
 * per-span family resolution has no consumer asking for it and an API that advertises a
 * capability it does not have is worse than one that does not offer it.</p>
 *
 * <p>Per-span family selection is still available through {@code bold}/{@code italic} plus
 * {@code CgFontFamilyGroup}, and per-<em>codepoint</em> fallback is what
 * {@code CgFontFamily.resolveRuns} already does. Reinstate an override only alongside a real
 * implementation.</p>
 *
 * <h3>Any span splits the shaping run at its boundaries</h3>
 * <p>Worth knowing when comparing spanned against unspanned text: a span's start and end become run
 * boundaries, and separately-shaped runs lose the kerning that would have applied across them. So
 * adding even a colour-only span can change a string's measured width slightly. That is inherent to
 * per-span shaping, not a defect.</p>
 *
 * @param start              start index (inclusive) into the owning {@code CgStyledText}'s plain text
 * @param end                end index (exclusive); must be {@code > start}
 * @param bold               bold weight
 * @param italic             italic style
 * @param decorations        underline/strikethrough/overline, any combination; empty = none
 * @param argbColor          override color, {@code 0} = inherit the draw's default color
 * @param fontFeatures       OpenType feature tags applied at shaping time, e.g. {@code kern=0} or {@code smcp=1}; empty = none
 * @param baselineShift      vertical baseline offset in logical pixels, negative = up the screen; {@code 0} = none (super/subscript). Does not grow the line box.
 */
@Builder
public record CgStyleSpan(
        int start,
        int end,
        boolean bold,
        boolean italic,
        Set<CgTextDecoration> decorations,
        int argbColor,
        List<CgFontFeature> fontFeatures,
        float baselineShift
) {

    public CgStyleSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid span range: start=" + start + ", end=" + end);
        }
        decorations = decorations == null || decorations.isEmpty() ? Set.of() : Set.copyOf(decorations);
        fontFeatures = fontFeatures == null ? List.of() : List.copyOf(fontFeatures);
    }
}
