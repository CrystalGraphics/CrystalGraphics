package com.crystalgraphics.render.ui.svg;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * A <b>resolved</b> SVG document: the picture, with every question about SVG already answered.
 *
 * <h3>What "resolved" means, and why it is the whole point</h3>
 *
 * <p>Ported from <b>usvg</b> (Apache-2.0/MIT), the resolution half of the {@code resvg} project. usvg's
 * one idea is that SVG-the-format and SVG-the-picture are different problems, and that a renderer should
 * never see the first. It parses a file and hands back a tree in which:</p>
 *
 * <ul>
 *   <li>every shape is a <b>path</b> — {@code rect}, {@code circle}, {@code polygon} and friends are gone;</li>
 *   <li>every coordinate is <b>absolute</b> — group transforms are flattened into the points themselves,
 *       so there is no transform left to apply;</li>
 *   <li>every style is <b>concrete</b> — inheritance, {@code style=""}, presentation attributes and
 *       {@code currentColor} have been collapsed into one fill and one stroke;</li>
 *   <li>{@code use}, {@code symbol} and {@code defs} have been <b>expanded</b>, so nothing refers to
 *       anything;</li>
 *   <li>paint servers are resolved to <b>user space</b> — an {@code objectBoundingBox} gradient has had
 *       its box folded in and is stated in the same coordinates as the path it paints.</li>
 * </ul>
 *
 * <p>The payoff is that everything downstream — tessellation, gradient banding, submission — reads a
 * scene and never a document. {@link CgSvgTessellator} takes contours and paint and has no idea SVG exists;
 * it could tessellate a font glyph unchanged. That separation is what usvg exists to provide and it is
 * the reason it is worth porting rather than inventing.</p>
 *
 * <h3>The one thing NOT flattened, deliberately</h3>
 *
 * <p>A gradient keeps a {@link Gradient#transform() transform} instead of having it folded into the ramp,
 * and usvg keeps {@code gradientTransform} for the same reason: a gradient is not a set of points, so a
 * transform cannot be applied "to it" the way one is applied to a contour. Under a skew or a non-uniform
 * scale a radial gradient stops being radial, and the only faithful representation of that is the matrix
 * itself. Folding it is correct for the common case and silently wrong for the interesting one.</p>
 *
 * <h3>Paint order is list order</h3>
 *
 * <p>SVG paints in document order with no z-index, so this is a flat list rather than a tree: the group
 * structure has served its purpose (inheritance and transforms) by the time a scene exists, and keeping
 * it would mean every consumer re-walking a hierarchy to recover an order that is already known.</p>
 *
 * @see CgSvgResolver the walk that produces one
 * @see CgSvgTessellator the consumer that turns one into triangles
 */
public record CgSvgScene(List<Node> nodes, float width, float height) {

    public CgSvgScene(List<Node> nodes, float width, float height) {
        // Unmodifiable because a scene is reachable from a cached CgSvg, so it is shared by every
        // consumer drawing that icon -- the same reason CgSvg's own lists are.
        this.nodes = Collections.unmodifiableList(nodes);
        this.width = width;
        this.height = height;
    }

    /** Every path in the document, in paint order. */
    @Override
    public List<Node> nodes() {
        return nodes;
    }

    /** The viewBox width, in the coordinates {@link Node#contours()} already use. */
    @Override
    public float width() {
        return width;
    }

    /** The viewBox height, in the coordinates {@link Node#contours()} already use. */
    @Override
    public float height() {
        return height;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * One resolved path — usvg's {@code Path}.
     *
     * <p>A node carries both a fill and a stroke rather than being split into two, because SVG paints
     * them from one element in a fixed order (fill first, then stroke) and separating them would make
     * that order something a consumer has to remember rather than something the data states.</p>
     *
     * @param contours subpaths of {@code [x, y]} points, <b>absolute and already flattened</b>. The
     *                 {@code closed} flag is kept rather than resolved because the two consumers want
     *                 opposite things from it: a fill closes every contour whether or not the path said
     *                 {@code Z} (SVG's own rule, and why an unclosed subpath still paints solid), while a
     *                 stroke must <em>not</em> draw the closing segment of an open one
     * @param fill     null when the element does not fill
     * @param stroke   null when the element does not stroke
     * @param shadow   the drop shadow of the filtered element this node is part of; null when there is none
     */
    public record Node(List<CgSvgPath.Polyline> contours, @Nullable Fill fill, @Nullable Stroke stroke,
                       @Nullable DropShadow shadow) {
    }

    /**
     * A {@code filter} that is a drop shadow, resolved to absolute space: the shadow of everything one filtered
     * element draws, painted under it. usvg's {@code DropShadow} primitive, and what the classic chain
     * (blur of {@code SourceAlpha}, offset, flood, {@code in} composite, merge under {@code SourceGraphic})
     * reduces to.
     *
     * <p>The nodes of one filtered element share one instance, and that is what groups them: the shadow is of
     * their union, drawn once before the first of them. {@code id} keeps two elements with equal shadows
     * apart, so the second one's shadow still falls on the first.</p>
     *
     * @param dx       offset in absolute units, the element's transform applied
     * @param dy       offset in absolute units
     * @param sigmaX   Gaussian standard deviation along x, in absolute units
     * @param sigmaY   along y
     * @param argb     {@code flood-color} with {@code flood-opacity} folded into its alpha
     * @param id       unique per filtered element in the document
     */
    public record DropShadow(float dx, float dy, float sigmaX, float sigmaY, int argb, int id) {
    }

    /**
     * @param evenOdd {@code fill-rule: evenodd} rather than SVG's default {@code nonzero}. Carried rather
     *                than resolved here because it is an input to tessellation, not to paint
     */
    public record Fill(Paint paint, boolean evenOdd) {
    }

    /**
     * @param halfWidth half of {@code stroke-width}, already scaled by the element's transform — a stroke
     *                  is a length, so flattening the transform into the points has to reach it too
     * @param cap       packed start/end cap, start in bits 0-1, end in 2-3
     */
    public record Stroke(Paint paint, float halfWidth, int cap) {
    }

    /**
     * What to paint with: exactly one of a colour or a gradient.
     *
     * <p>A sealed pair rather than a record with a nullable gradient, because "solid" and "gradient" take
     * genuinely different paths through tessellation — a gradient decides where the mesh is <em>cut</em>,
     * which a colour has no opinion about — and a nullable field makes that a runtime question at every
     * use site instead of a compile-time one at the seam.</p>
     */
    public sealed

    interface Paint permits Solid, Gradient {

        /**
         * The paint was {@code currentColor}, so the consumer's tint decides it at draw time.
         *
         * <p><b>Late-bound on purpose.</b> A document is parsed once and cached, and the same icon is
         * routinely drawn in two colours in one frame — a selected file-tree row and an unselected one.
         * Resolving it here would mean one cache entry per tint.</p>
         */
        boolean currentColor();
    }

    /**
     * @param argb fill/stroke colour with {@code fill-opacity} and the inherited {@code opacity} already
     *             multiplied into the alpha. Folded here rather than kept beside it because every consumer
     *             wants the product and none wants the factors
     */
    public record Solid(int argb, boolean currentColor) implements Paint {

        public boolean opaque() {
            return (argb >>> 24) == 0xFF;
        }
    }

    /**
     * @param gradient  the ramp and its geometry, in the space {@code transform} maps from
     * @param transform maps the gradient's own space to the scene's absolute space — the element transform
     *                  composed with any {@code gradientTransform}. See the class note on why this survives
     * @param alpha     {@code fill-opacity * opacity}, to be multiplied into every stop as it is sampled.
     *                  Not pre-applied to the ramp because the ramp is shared with any other element
     *                  referencing the same paint server, which may carry a different opacity
     * @param argb      the ramp reduced to one colour, for a consumer that is drawing the whole icon in a
     *                  single tint. Not a fallback for failure — {@code renderMonochrome} is a real mode,
     *                  and a gradient has to have an answer for it
     */
    public record Gradient(CgSvgGradient gradient, CgSvgTransform transform, float alpha, int argb,
                           boolean currentColor) implements Paint {
    }
}
