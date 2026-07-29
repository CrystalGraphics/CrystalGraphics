package com.crystalgraphics.text.render;

import com.crystalgraphics.api.render.CgViewFrustum;
import com.crystalgraphics.api.text.CgTextLayout;
import org.joml.Matrix4f;

/**
 * Decides whether a whole text layout is off-screen, so the draw can be skipped before any glyph
 * resolution or quad building happens.
 *
 * <p>The geometry is {@link CgViewFrustum}'s; this class is only the text-specific <em>policy</em>
 * around it — what box to test, how much slack to allow, and when to refuse to cull at all. Kept
 * separate from {@code CgTextRenderer} because none of that is renderer logic, and separate from
 * {@code CgViewFrustum} because none of it is general (a mesh has no "layout height" to derive a
 * margin from).</p>
 *
 * <p>One instance per renderer, reused. Not static: two renderers can be mid-draw with different
 * projections, and a shared frustum would let one cull against the other's view.</p>
 *
 * <h3>Cull per draw, not per glyph</h3>
 * <p>A per-glyph test costs a test to save building a quad that already costs 0.136 us, and the
 * measured waste was never per-glyph — it was two 3363-glyph paragraphs parked at y=2112 in a
 * 600px-tall viewport, resolved and submitted in full every frame because the only visibility test
 * in the pipeline was {@code hasGeometry()}, which asks "does this glyph have pixels", not "is it
 * on screen".</p>
 */
final class CgTextCuller {

    /**
     * Slack around the layout box, as a multiple of the layout's own height.
     *
     * <p>A layout's width/height is the <em>text box</em>, and glyphs are not obliged to stay
     * inside it: bearings and overshoot push past the edges, descenders and underlines sit below
     * the last baseline, synthetic italic shears the run sideways. Culling exactly on the box would
     * clip those at the viewport edge — a rare, position-dependent visual bug of exactly the kind
     * that survives review and surfaces in a screenshot months later.
     *
     * <p>A full layout-height of slack in every direction dwarfs any of those, and costs nothing
     * worth measuring: the case actually worth culling clears the margin by orders of magnitude.
     * This buys not having to be right about every overhang in the engine.
     */
    private static final float MARGIN_FACTOR = 1.0f;

    private final CgViewFrustum frustum = new CgViewFrustum();
    private final Matrix4f localToClip = new Matrix4f();

    /**
     * Whether {@code layout}, drawn at {@code (x, y)} under {@code modelView}, is entirely outside
     * the view.
     *
     * <p><strong>Fails open</strong> in every uncertain case — no projection, a degenerate or NaN
     * layout box. A culler that wrongly hides text produces a blank screen with no error; one that
     * wrongly keeps it merely does the work it was going to do anyway. Those are not comparable
     * costs, so the tie always goes to drawing.</p>
     *
     * @param projection the active projection, or {@code null} to skip culling entirely
     * @param modelView  the draw's pose; combined with {@code projection} to get local → clip,
     *                   because the box below is in the layout's own local space
     */
    boolean isCulled(CgTextLayout layout, float x, float y, Matrix4f projection, Matrix4f modelView) {
        if (projection == null || modelView == null) return false;

        float w = layout.totalWidth();
        float h = layout.totalHeight();
        if (!(w > 0f) || !(h > 0f)) return false;

        frustum.set(localToClip.set(projection).mul(modelView));

        float margin = h * MARGIN_FACTOR;
        return !frustum.testRect(x - margin, y - margin, x + w + margin, y + h + margin);
    }
}
