package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBFont;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.text.layout.CgTextLayoutEngine;
import io.github.somehussar.crystalgraphics.text.layout.CgTextShaper;
import io.github.somehussar.crystalgraphics.text.layout.RunReshaper;

import java.util.List;

/**
 * Public entry-point for the text layout pipeline.
 *
 * <h3>Placement exception — why this class lives in {@code api/font}</h3>
 *
 * <p>The layout algorithm itself is defined by the base class
 * {@link CgTextLayoutEngine} in the internal {@code text} package. This
 * subclass supplies the concrete font-fallback resolution and HarfBuzz shaping
 * bridge that the engine delegates to via its two {@code protected abstract}
 * hooks.</p>
 *
 * <p>Those hooks require access to <strong>package-private</strong> members in
 * {@code api/font} that intentionally hide native HarfBuzz types from the
 * public API surface:</p>
 * <ul>
 *   <li>{@link CgFontFamily#resolveRuns(String, int, int)} — splits a text
 *       range into font-fallback segments (package-private)</li>
 *   <li>{@link CgFontFamily.ResolvedFontRun} — carries the resolved
 *       {@link CgFontKey} and its native {@code HBFont} handle
 *       (package-private inner class)</li>
 *   <li>{@link CgFontFamily#requireShapingFont(CgFontKey)} — retrieves the
 *       native {@code HBFont} for a key during line-break re-shaping
 *       (package-private)</li>
 * </ul>
 *
 * <p>Moving this class to {@code api/text} would require either promoting
 * those members to {@code public} (leaking native types) or introducing a
 * privileged accessor interface solely to bridge the two packages. Neither
 * option improves the API, so the builder stays here as a deliberate
 * placement exception: a <em>text</em> entry-point whose implementation
 * demands co-location with font-family internals.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * CgTextLayoutBuilder builder = new CgTextLayoutBuilder();
 * CgTextLayout layout = builder.layout("Hello, world!", font, 400, 0);
 * </pre>
 *
 * @see CgTextLayoutEngine  the base algorithm (BiDi, line breaking, scaling)
 * @see CgFontFamily        font-fallback resolution and glyph coverage
 * @see CgTextShaper         stateless HarfBuzz shaping delegate
 * @see io.github.somehussar.crystalgraphics.api.text.CgTextLayout  layout result type
 */
public class CgTextLayoutBuilder extends CgTextLayoutEngine {

    /** Stateless shaper used for both initial shaping and line-break re-shaping. */
    private final CgTextShaper shaper = new CgTextShaper();

    @Override
    protected List<String> splitParagraphs(String text) {
        return super.splitParagraphs(text);
    }

    /**
     * Resolves font-fallback runs for the given text range, then shapes each
     * run through HarfBuzz.
     *
     * <p>This is the primary bridge into {@code api/font} package-private
     * internals: {@link CgFontFamily#resolveRuns} splits the range by glyph
     * coverage across the family's primary and fallback sources, and each
     * {@link CgFontFamily.ResolvedFontRun} provides the native {@code HBFont}
     * handle required by the shaper.</p>
     */
    @Override
    protected void collectShapedRuns(String text,
                                     int start,
                                     int end,
                                     boolean rtl,
                                     CgFontFamily family,
                                     List<CgShapedRun> out) {
        List<CgFontFamily.ResolvedFontRun> resolvedRuns = family.resolveRuns(text, start, end);
        for (CgFontFamily.ResolvedFontRun resolvedRun : resolvedRuns) {
            HBFont hbFont = resolvedRun.requireHbFont();
            CgShapedRun run = shaper.shape(text,
                    resolvedRun.getStart(),
                    resolvedRun.getEnd(),
                    resolvedRun.getFontKey(),
                    rtl,
                    hbFont);
            out.add(run);
        }
    }

    /**
     * Creates a reshaper that the line breaker can call when it needs to split
     * an already-shaped run at a word or wrap boundary.
     *
     * <p>Uses the package-private {@link CgFontFamily#requireShapingFont} to
     * obtain the native {@code HBFont} for the fragment's font key, then
     * re-shapes through HarfBuzz so glyph clusters remain correct.</p>
     */
    @Override
    protected RunReshaper createRunReshaper(final CgFontFamily family) {
        // Line breaking may need to split an already-shaped run at a word or
        // wrap boundary. Re-shaping the sub-range preserves correct HarfBuzz
        // shaping for the fragment instead of slicing glyph arrays blindly.
        return new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgShapedRun run, int subStart, int subEnd) {
                HBFont hbFont = family.requireShapingFont(run.getFontKey());
                return shaper.shape(run.getSourceText(), subStart, subEnd,
                        run.getFontKey(), run.isRtl(), hbFont);
            }
        };
    }
}
