package com.crystalgraphics.api.font;

import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.text.layout.CgReshapeContext;
import com.crystalgraphics.text.layout.CgTextLayoutEngine;
import com.crystalgraphics.text.layout.CgTextShaper;
import com.crystalgraphics.text.layout.RunReshaper;

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
 * <h3>Not the public entry point</h3>
 * <p>Callers should use {@link CgTextLayoutRequest} (e.g.
 * {@code CgTextLayoutRequest.obtain("Hello, world!", font).maxWidth(400).build()}),
 * not this class directly — {@code CgTextLayoutRequest} holds the single shared instance
 * of this builder internally. This class exists so {@code CgTextLayoutRequest} (which
 * lives in {@code api/text} and cannot see package-private {@code api/font} members) has
 * a concrete {@link CgTextLayoutEngine} to delegate {@code shape()}/{@code shapeStyled()}
 * calls to.</p>
 *
 * @see CgTextLayoutEngine  the base algorithm (BiDi, line breaking, shape/wrap split)
 * @see CgTextLayoutRequest the public entry point
 * @see CgFontFamily        font-fallback resolution and glyph coverage
 * @see CgTextShaper         stateless HarfBuzz shaping delegate
 * @see CgTextLayout  layout result type
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
            CgFont resolvedFont = resolvedRun.getSource().requireFont();
            CgShapedRun run = shaper.shape(text,
                    resolvedRun.getStart(),
                    resolvedRun.getEnd(),
                    resolvedRun.getFontKey(),
                    resolvedFont,
                    rtl,
                    hbFont);
            out.add(run);
        }
    }

    /**
     * Creates a reshaper that the line breaker can call when it needs to split
     * an already-shaped run at a word or wrap boundary.
     *
     * <p>Resolves the fragment's font <em>by the run's own style</em> —
     * {@code group.resolve(run.getFontKey().getStyle())} — rather than always against
     * whichever single family the reshaper was built with, so a bold run's forced
     * line-break re-shapes against the bold face and not the regular one. Uses the
     * package-private {@link CgFontFamily#requireShapingFont} to obtain the native
     * {@code HBFont} for the fragment's font key, then re-shapes through HarfBuzz so
     * glyph clusters remain correct.</p>
     */
    @Override
    protected RunReshaper createRunReshaper(final CgFontFamilyGroup group) {
        // Line breaking may need to split an already-shaped run at a word or
        // wrap boundary. Re-shaping the sub-range preserves correct HarfBuzz
        // shaping for the fragment instead of slicing glyph arrays blindly.
        return new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgReshapeContext context, CgShapedRun run, int subStart, int subEnd) {
                CgFontFamily family = group.resolve(run.fontKey().getStyle());
                HBFont hbFont = family.requireShapingFont(run.fontKey());
                CgFont resolvedFont = family.resolveLoadedFont(run.fontKey());
                CgShapedRun fragment = shaper.shape(context.sourceText(), subStart, subEnd,
                        run.fontKey(), resolvedFont, run.rtl(), hbFont);
                // shaper.shape() only knows glyph/advance/cluster data -- it has no idea this
                // sub-range came from a run carrying rich styling (a colored/decorated/bold
                // span the line breaker had to split). Without copying these over, any styled
                // run that genuinely needs splitting across lines silently reverts to
                // plain/unstyled for the split-off fragment(s).
                return fragment
                        .argbColor(run.argbColor())
                        .decorations(run.decorations())
                        .fontFeatures(run.fontFeatures())
                        .baselineShift(run.baselineShift())
                        .syntheticBold(run.syntheticBold())
                        .syntheticItalic(run.syntheticItalic());
            }
        };
    }
}
