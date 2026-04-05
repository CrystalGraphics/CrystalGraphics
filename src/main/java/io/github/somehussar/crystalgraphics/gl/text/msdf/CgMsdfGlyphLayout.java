package io.github.somehussar.crystalgraphics.gl.text.msdf;

/**
 * Upstream-parity MSDF glyph box computation.
 *
 * <p>This class ports the {@code GlyphGeometry.wrapBox()} algorithm from
 * {@code msdf-atlas-gen} to Java 8. It computes the atlas box dimensions,
 * projection transform, and plane/atlas bounds for a single MSDF glyph
 * using the same mathematical model as the upstream C++ implementation.</p>
 *
 * <h3>Key Differences from CgMsdfGenerator Cell Model</h3>
 * <ul>
 *   <li>Box dimensions are <strong>glyph-specific</strong>, not a fixed cell
 *       size bucket. Narrow glyphs get narrow boxes; wide glyphs get wide boxes.</li>
 *   <li>Distance range, padding, and miter limit are explicit parameters that
 *       participate in box sizing, not shader-only constants.</li>
 *   <li>Origin pixel alignment is configurable and affects box dimensions
 *       and translate computation.</li>
 *   <li>Plane bounds are computed from the box transform, not from bearings.</li>
 * </ul>
 *
 * <h3>Upstream Algorithm (wrapBox)</h3>
 * <ol>
 *   <li>Start with raw shape bounds (l, b, r, t) from msdfgen.</li>
 *   <li>Expand bounds by the distance range (range.lower) to account for
 *       SDF field extent.</li>
 *   <li>Apply miter limit to further expand bounds at sharp corners.</li>
 *   <li>Apply inner and outer padding.</li>
 *   <li>Compute pixel-space box width/height from the expanded bounds at
 *       the given scale, with optional pixel-grid alignment.</li>
 *   <li>Compute translate vector to position the glyph within its box.</li>
 * </ol>
 *
 * <p>Instances are immutable after construction via the {@link #compute} factory.</p>
 *
 * @see io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator
 */
public final class CgMsdfGlyphLayout {

    // ── Glyph attributes (inputs) ─────────────────────────────────────

    /** The scale factor (glyphAttributes.scale * geometryScale). */
    private final double scale;

    /** Pixel range for the SDF field. */
    private final double pxRange;

    /** Miter limit for bounds expansion at sharp corners. */
    private final double miterLimit;

    /** Whether the origin is pixel-aligned on the X axis. */
    private final boolean pxAlignOriginX;

    /** Whether the origin is pixel-aligned on the Y axis. */
    private final boolean pxAlignOriginY;

    // ── Computed box results ──────────────────────────────────────────

    /** Atlas box width in pixels. */
    private final int boxWidth;

    /** Atlas box height in pixels. */
    private final int boxHeight;

    /** X translation for the projection (shape space to pixel space). */
    private final double translateX;

    /** Y translation for the projection (shape space to pixel space). */
    private final double translateY;

    /**
     * Range in shape units for the SDF generation transform.
     * This is {@code pxRange / (2.0 * scale)} — half the pixel range
     * converted to shape coordinate space.
     */
    private final double rangeInShapeUnits;

    // ── Plane bounds (font-unit space) ────────────────────────────────

    /** Left plane bound (font units, relative to pen origin). */
    private final double planeLeft;

    /** Bottom plane bound (font units, relative to pen origin). */
    private final double planeBottom;

    /** Right plane bound (font units, relative to pen origin). */
    private final double planeRight;

    /** Top plane bound (font units, relative to pen origin). */
    private final double planeTop;

    /** Whether this layout represents a whitespace/empty glyph. */
    private final boolean empty;

    private CgMsdfGlyphLayout(double scale, double pxRange, double miterLimit,
                               boolean pxAlignOriginX, boolean pxAlignOriginY,
                               int boxWidth, int boxHeight,
                               double translateX, double translateY,
                               double rangeInShapeUnits,
                               double planeLeft, double planeBottom,
                               double planeRight, double planeTop,
                               boolean empty) {
        this.scale = scale;
        this.pxRange = pxRange;
        this.miterLimit = miterLimit;
        this.pxAlignOriginX = pxAlignOriginX;
        this.pxAlignOriginY = pxAlignOriginY;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
        this.translateX = translateX;
        this.translateY = translateY;
        this.rangeInShapeUnits = rangeInShapeUnits;
        this.planeLeft = planeLeft;
        this.planeBottom = planeBottom;
        this.planeRight = planeRight;
        this.planeTop = planeTop;
        this.empty = empty;
    }

    /**
     * Computes an MSDF glyph layout using upstream-parity box math.
     *
     * <p>This implements the same algorithm as {@code GlyphGeometry::wrapBox()}
     * in msdf-atlas-gen. The glyph shape bounds are expanded by the SDF range,
     * miter limit, and padding, then converted to pixel-space box dimensions
     * with optional origin pixel alignment.</p>
     *
     * <h4>Parameters Mirror Upstream GlyphAttributes</h4>
     * <ul>
     *   <li>{@code targetPx} → upstream {@code scale} (pixels per EM)</li>
     *   <li>{@code pxRange} → upstream {@code range} (total SDF range in pixels)</li>
     *   <li>{@code miterLimit} → upstream {@code miterLimit}</li>
     *   <li>{@code pxAlignOriginX/Y} → upstream {@code pxAlignOriginX/Y}</li>
     * </ul>
     *
     * @param shapeLeft    left bound of the glyph shape (EM-normalised)
     * @param shapeBottom  bottom bound of the glyph shape (EM-normalised)
     * @param shapeRight   right bound of the glyph shape (EM-normalised)
     * @param shapeTop     top bound of the glyph shape (EM-normalised)
     * @param targetPx     target pixel size (scale factor, pixels per EM)
     * @param pxRange      SDF pixel range (total, e.g. 4.0)
     * @param miterLimit   miter limit for bounds expansion (0 to disable)
     * @param pxAlignOriginX whether to pixel-align origin on X axis
     * @param pxAlignOriginY whether to pixel-align origin on Y axis
     * @return the computed layout (never null; check {@link #isEmpty()} for whitespace)
     */
    public static CgMsdfGlyphLayout compute(double shapeLeft, double shapeBottom,
                                             double shapeRight, double shapeTop,
                                             int targetPx, double pxRange,
                                             double miterLimit,
                                             boolean pxAlignOriginX,
                                             boolean pxAlignOriginY) {
        double scale = targetPx;

        // Empty/whitespace glyph — no visible box
        if (shapeLeft >= shapeRight || shapeBottom >= shapeTop) {
            return new CgMsdfGlyphLayout(
                    scale, pxRange, miterLimit, pxAlignOriginX, pxAlignOriginY,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, true);
        }

        // Upstream wrapBox: expand bounds by range (half-range inward from each side)
        // range.lower is negative (inner edge), range.upper is positive (outer edge)
        // In upstream: range = pxRange / geometryScale, but we use EM-normalised
        // shapes (geometryScale = 1), so range = pxRange / scale in shape units.
        // However, upstream uses the full Range object; for symmetric pxRange,
        // range.lower = -pxRange/(2*scale), range.upper = +pxRange/(2*scale).
        double halfRange = pxRange / 2.0;
        double rangeLower = -halfRange / scale; // negative value

        double l = shapeLeft + rangeLower;   // expand left (rangeLower is negative, so l decreases)
        double b = shapeBottom + rangeLower; // expand bottom
        double r = shapeRight - rangeLower;  // expand right (subtracting negative = adding)
        double t = shapeTop - rangeLower;    // expand top

        // Note: miter limit expansion would go here if we had access to the
        // shape contour data for boundMiters(). For now we skip this step —
        // our msdfgen bindings don't expose boundMiters, and the bounds from
        // getBounds() are already reasonably conservative.
        // TODO: Add miter bounding if msdfgen bindings expose it.

        // No inner/outer padding in current CrystalGraphics config
        // (upstream: l -= fullPadding.l, etc.)

        // Compute box dimensions
        int boxWidth;
        int boxHeight;
        double translateX;
        double translateY;

        if (pxAlignOriginX) {
            // Upstream: pixel-aligned origin
            int sl = (int) Math.floor(scale * l - 0.5);
            int sr = (int) Math.ceil(scale * r + 0.5);
            boxWidth = sr - sl;
            translateX = -sl / scale;
        } else {
            // Upstream: centered translate
            double w = scale * (r - l);
            boxWidth = (int) Math.ceil(w) + 1;
            translateX = -l + 0.5 * (boxWidth - w) / scale;
        }

        if (pxAlignOriginY) {
            int sb = (int) Math.floor(scale * b - 0.5);
            int st = (int) Math.ceil(scale * t + 0.5);
            boxHeight = st - sb;
            translateY = -sb / scale;
        } else {
            double h = scale * (t - b);
            boxHeight = (int) Math.ceil(h) + 1;
            translateY = -b + 0.5 * (boxHeight - h) / scale;
        }

        double rangeInShapeUnits = halfRange / scale;

        // Compute plane bounds (upstream getQuadPlaneBounds)
        // In upstream with geometryScale=1 and no outer padding:
        // l = -translateX + 0.5/scale
        // b = -translateY + 0.5/scale
        // r = -translateX + (boxWidth - 0.5)/scale
        // t = -translateY + (boxHeight - 0.5)/scale
        double invScale = 1.0 / scale;
        double planeLeft = -translateX + 0.5 * invScale;
        double planeBottom = -translateY + 0.5 * invScale;
        double planeRight = -translateX + (boxWidth - 0.5) * invScale;
        double planeTop = -translateY + (boxHeight - 0.5) * invScale;

        return new CgMsdfGlyphLayout(
                scale, pxRange, miterLimit, pxAlignOriginX, pxAlignOriginY,
                boxWidth, boxHeight, translateX, translateY, rangeInShapeUnits,
                planeLeft, planeBottom, planeRight, planeTop, false);
    }

    /**
     * Convenience overload with default miter limit (0) and no pixel alignment.
     */
    public static CgMsdfGlyphLayout compute(double shapeLeft, double shapeBottom,
                                             double shapeRight, double shapeTop,
                                             int targetPx, double pxRange) {
        return compute(shapeLeft, shapeBottom, shapeRight, shapeTop,
                targetPx, pxRange, 0, false, false);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public double getScale() { return scale; }
    public double getPxRange() { return pxRange; }
    public double getMiterLimit() { return miterLimit; }
    public boolean isPxAlignOriginX() { return pxAlignOriginX; }
    public boolean isPxAlignOriginY() { return pxAlignOriginY; }

    public int getBoxWidth() { return boxWidth; }
    public int getBoxHeight() { return boxHeight; }
    public double getTranslateX() { return translateX; }
    public double getTranslateY() { return translateY; }
    public double getRangeInShapeUnits() { return rangeInShapeUnits; }

    public double getPlaneLeft() { return planeLeft; }
    public double getPlaneBottom() { return planeBottom; }
    public double getPlaneRight() { return planeRight; }
    public double getPlaneTop() { return planeTop; }

    public boolean isEmpty() { return empty; }

    @Override
    public String toString() {
        return "CgMsdfGlyphLayout{" +
                "box=" + boxWidth + "x" + boxHeight +
                ", scale=" + scale +
                ", pxRange=" + pxRange +
                ", translate=(" + translateX + "," + translateY + ")" +
                ", plane=[" + planeLeft + "," + planeBottom + "," + planeRight + "," + planeTop + "]" +
                ", empty=" + empty +
                '}';
    }
}
