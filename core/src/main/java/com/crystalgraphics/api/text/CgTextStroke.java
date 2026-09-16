package com.crystalgraphics.api.text;

/**
 * An outline drawn around a draw's glyphs, resolved from the distance field at draw time.
 *
 * <pre>{@code
 * renderer.draw().text("Crystal").family(family).at(x, y).color(0xFFFFFFFF)
 *         .stroke(CgTextStroke.of(0.06f, 0xFF101418))          // 6% of em, near-black
 *         .submit();
 *
 * CgTextStroke.of(0.06f, argb)
 *         .withAlign(CgStrokeAlign.CENTER)                      // webkit's behaviour
 *         .withRounded(true)                                    // round joins, not mitred
 *         .withStrokeOverFill(true);                            // stroke painted on top
 * }</pre>
 *
 * <h3>Width is in em, and that is the only unit that survives</h3>
 *
 * <p>A glyph is rasterised at whatever effective size the pose asks for, and the same draw can be
 * re-rasterised at a different one next frame. A width in pixels would mean a different fraction of
 * the letterform each time; a width in em is the same outline at every size, which is what an author
 * writing {@code 0.06em} means and what {@code text-stroke: 1px} has to be converted into.</p>
 *
 * <h3>It is bounded by the field, and the bound is small</h3>
 *
 * <p>The stroke is read out of the stored distance field, which carries real distance for
 * {@code (pxRange - 1) / 2} atlas texels either side of the outline and SATURATES past that. A
 * bilinear tap reads half a texel either side, so the usable reach stops a further texel short of
 * saturation. <b>The range is per FACE</b>, so the reach is too: a face carrying a dense script
 * keeps {@code pxRange 12} — 4.5 of 80 texels, 0.056 em, 3.6px on 64px text — because eight bits
 * cannot quantise a wider one without merging strokes a kanji keeps apart. Every other face is
 * banded at 24: <b>0.131 em, 8.4px on 64px text</b>, past the 0.083 em Godot allows for the same
 * technique.
 *
 * <p>Ask {@code CgFontRegistry.maxStrokeWidthEm(family)} for the one that applies.
 * @see CgLatinRangeHeadroomTest, CgFontBandingTest
 * {@link #MAX_FIELD_WIDTH_EM} is that number, and a wider outline clamps to it rather than
 * getting wider.</p>
 *
 * <p><b>The shader clamps, and it has to.</b> Past saturation the field reports the same distance
 * everywhere, so an unclamped threshold never finds an outer edge and the glyph's whole padded cell
 * fills with stroke colour — a solid rectangle, not a thick outline. The clamp also stops a full
 * pixel short of saturation, because a saturated field has no gradient and therefore no antialiasing
 * ramp. Asking for more than the field holds is safe; it simply stops getting wider.</p>
 *
 * <p><b>There is no wider stroke, and path stroking is not the way to one.</b> A FreeType stroker
 * binding was built and measured against this path: its offset borders self-intersect in tight
 * concavities with no boolean cleanup, missing the true dilation by a full radius, where
 * thresholding an exact field IS the dilation. It was removed rather than shipped as a worse
 * outline. Widening the stored range is the route, not replacing the stroke with geometry.</p>
 *
 *
 * <h3>Mitred joins, and where a round one would come from</h3>
 *
 * <p>The outline is thresholded from the same median-of-3 the fill reads, which keeps a corner
 * sharp — a mitre, matching {@code -webkit-text-stroke}, which is a path stroke and takes Skia's
 * default join. MTSDF's fourth channel carries true distance and would ROUND a corner instead;
 * msdf-atlas-gen lists exactly that among the effects it is for. Measured on the reconstructed
 * band, the two disagree over 0.5% of an A's outline, 0.7% of an E's and 0% of an o's: they part
 * company at corners and nowhere else.</p>
 *
 * <p>So a join setting is a feature that exists to be built, not a difference too small to see.
 * It would have to read alpha for BOTH of the ring's edges, since mixing channels across the two
 * would open a hairline where the ring meets the fill at every corner.
 * @see CgStrokeFieldRangeTest#medianAndTrueDistanceDisagreeAtCorners</p>
 * <p><b>Every size that can carry one gets one.</b> A stroke pulls its draw onto the
 * distance-field tier, down to the size where the field can still antialias — 15px at the
 * shipping pairing, {@code CgMsdfAtlasConfig.minAntialiasablePx()}. Below that the glyph is a bitmap,
 * which carries coverage rather than distance, and the stroke is dropped: the outline would be drawn
 * on a fill that the field cannot resolve. That floor is msdfgen's own rule about its shader
 * (screenPxRange under 2 and the antialiasing fails), not a taste.</p>
 *
 * <p><b>Weight and slant cost it nothing.</b> Synthetic oblique is sheared on the shape before the
 * field is generated, so the field is the slanted letter's own and the outline is uniform rather than
 * elliptical. Synthetic bold is a bias on the field, which moves its contour and both of its
 * saturation ends together — measured, the reach is the same 5.50 texels with the bias and
 * without. @see CgSyntheticBoldReachTest</p>
 */
public record CgTextStroke(float widthEm, int argb, CgStrokeAlign align, boolean strokeOverFill) {

    /** No stroke. The default for every draw. */
    public static final CgTextStroke NONE = new CgTextStroke(0f, 0, CgStrokeAlign.OUTSET, false);

    /**
     * The widest stroke the NARROW band's distance field can describe CLEANLY, in em: half of
     * {@code CgMsdfAtlasConfig.DEFAULT_PX_RANGE}, less one texel the generator keeps back so the
     * field cannot bleed past its cell, less one more for the bilinear footprint — 4.5 of 80
     * texels. This is the band EVERY face has; most have more.
     *
     * <p><b>The last texel is not a rounding allowance.</b> A tap that straddles the saturation
     * shoulder averages a clipped texel with a live one, and a flat field turns eight-bit value
     * error into a level set that follows the texel grid: the outer edge scallops while the fill
     * beside it, thresholding where the field still has slope, stays smooth. {@code text.shader}
     * keeps that texel back per fragment, since the bound is screen-space.
     * @see CgStrokeFieldRangeTest</p>
     *
     * <p>Stated here rather than imported so this API type stays free of the msdf package, and not
     * enforced by this type — a caller that asks for more gets a clamped outline, not an
     * exception, because a stroke quietly reaching its limit is better than a crash in a paint
     * method. The CSS path DOES enforce it, in {@code UIText}, so the width a style resolves to is
     * the width that gets drawn and a declaration asking for more says so in the log once.</p>
     */
    public static final float MAX_FIELD_WIDTH_EM = 4.5f / 80f;

    /*
     * The number above is the NARROW band's, so it is the reach every face has. A face with no dense
     * script in it is banded wider and carries 0.131em; ask for that with
     * CgFontRegistry.maxStrokeWidthEm(family) rather than reading this, which gives away more than
     * half the reach a Latin label actually has. @see CgMsdfAtlasConfig#WIDE_PX_RANGE
     */

    public CgTextStroke {
        if (align == null) align = CgStrokeAlign.OUTSET;
        if (widthEm < 0f) widthEm = 0f;
    }

    /** An outset stroke, which is what an author asking for an outline almost always means. */
    public static CgTextStroke of(float widthEm, int argb) {
        return new CgTextStroke(widthEm, argb, CgStrokeAlign.OUTSET, false);
    }

    public CgTextStroke withAlign(CgStrokeAlign align) {
        return new CgTextStroke(widthEm, argb, align, strokeOverFill);
    }

    /** {@code true} paints the stroke over the fill; {@code false} puts it behind. */
    public CgTextStroke withStrokeOverFill(boolean strokeOverFill) {
        return new CgTextStroke(widthEm, argb, align, strokeOverFill);
    }

    /** Whether this would draw anything: a width and a non-transparent colour. */
    public boolean isVisible() {
        return widthEm > 0f && (argb >>> 24) != 0;
    }
}
