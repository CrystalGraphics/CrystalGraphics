package com.crystalgraphics.text.render.context;

import com.crystalgraphics.api.PoseStack;
import org.joml.Matrix4f;
/**
 * Orthographic/UI implementation of {@link CgTextScaleResolver}.
 *
 * <p>Extracts cumulative scale from the pose matrix by measuring the lengths
 * of the X and Y basis vectors, then applies {@code max(|sx|, |sy|)} as the
 * conservative scale factor — matching browser behavior for transformed text
 * where the raster size uses the dominant axis scale.</p>
 *
 * <h3>Quantization</h3>
 * <p>The raw effective size ({@code baseTargetPx × maxScale}) is quantized to
 * the nearest integer via {@code Math.round()}, then clamped to
 * [{@link #MIN_EFFECTIVE_PX}, {@link #MAX_EFFECTIVE_PX}].</p>
 *
 * <h3>Hysteresis</h3>
 * <p>To prevent frame-to-frame raster/cache thrash when the scale animates
 * near a quantization boundary, a deadband of ±{@link #HYSTERESIS_BAND}
 * (0.75) is applied. Switching from the current stabilized size N to N+1
 * requires {@code rawEffective >= N + 0.75}, and switching to N-1 requires
 * {@code rawEffective <= N - 0.75}.</p>
 *
 * <h3>Not for World-Space Text</h3>
 * <p>This resolver assumes the pose matrix encodes only model-view transforms
 * for screen-aligned 2D text. For perspective/world-space text, the on-screen
 * pixel size depends on view distance, FOV, and viewport dimensions — factors
 * not available in the pose matrix alone. {@link PerspectiveScaleResolver} handles
 * that case using a fixed raster multiplier or a caller-supplied projected-size
 * hint from {@link ProjectedSizeEstimator}.</p>
 *
 * @see CgTextScaleResolver
 */
public final class OrthographicScaleResolver implements CgTextScaleResolver {

    @Override
    public int resolveEffectiveTargetPx(int baseTargetPx,
                                        PoseStack.Pose pose,
                                        int previousEffectiveTargetPx) {
        float maxScale = extractMaxScale(pose.pose());
        float rawEffective = baseTargetPx * maxScale;

        int quantized = Math.round(rawEffective);
        quantized = Math.max(MIN_EFFECTIVE_PX, Math.min(MAX_EFFECTIVE_PX, quantized));

        // Apply hysteresis from the caller-provided previous value so resolver
        // instances remain stateless and safe to share.
        if (previousEffectiveTargetPx > 0) {
            if (quantized > previousEffectiveTargetPx && rawEffective < previousEffectiveTargetPx + HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
            if (quantized < previousEffectiveTargetPx && rawEffective > previousEffectiveTargetPx - HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
        }

        return quantized;
    }

    @Override
    public boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf) {
        if (effectiveTargetPx >= MSDF_ENTER_THRESHOLD) {
            return true;
        }
        if (effectiveTargetPx <= MSDF_EXIT_THRESHOLD) {
            return false;
        }
        // In the hysteresis band [32, 32] — retain previous backend choice
        return previouslyMsdf;
    }

    /**
     * Angular tolerance, in radians, for {@link #isAxisAligned}. A basis vector is treated as
     * lying on its axis when its off-axis components are within this fraction of its own length
     * — i.e. when it is tilted by less than ~0.006°.
     *
     * <p>Chosen to sit far above float noise and far below any deliberate rotation. A pure
     * translate/scale chain produces exact zeros or residue around 1e-7; the smallest rotation
     * a caller could plausibly mean is orders of magnitude larger (sin(0.1°) ≈ 1.7e-3). So this
     * threshold never misfires on an untransformed UI draw, and never lets a real rotation
     * through.</p>
     */
    private static final float AXIS_ALIGNMENT_EPSILON = 1.0e-4f;

    /**
     * Whether {@code matrix} maps the text quad's XY plane onto the screen without rotating it
     * off the pixel axes or shearing it — i.e. whether one source texel still maps to an
     * axis-aligned screen-space rectangle.
     *
     * <h3>What this gates</h3>
     * <p>Bitmap glyphs are pre-rasterized at a fixed size and orientation, so they only survive
     * resampling while the sampling grid stays parallel to the raster grid. Under an arbitrary
     * rotation or a shear, a bilinear fetch smears each texel across neighbouring pixels and the
     * glyph goes visibly soft and stair-stepped along its stems — the exact artifact distance
     * fields exist to avoid, since an MSDF reconstructs the outline analytically at whatever
     * orientation it is sampled. {@code CgTextRenderer.resolveDraw} therefore forces the
     * distance-field tier whenever this returns {@code false}, regardless of glyph size.</p>
     *
     * <h3>Quarter-turns are aligned</h3>
     * <p>Rotations by exact multiples of 90° (and axis flips, which are negative scales) permute
     * and negate the basis vectors without tilting them off-axis, so texel centres still land on
     * pixel centres and a bitmap stays exactly as crisp as it is at 0°. Those are accepted.
     * Non-uniform scale is likewise accepted — it is a resample the bitmap tier already performs
     * at every size.</p>
     *
     * <h3>Test</h3>
     * <p>The quad is authored in the XY plane, so only the X basis {@code (m00, m01, m02)} and
     * the Y basis {@code (m10, m11, m12)} matter. Each is required to lie along a coordinate
     * axis <em>within its own length</em> — a per-axis relative test, so an extreme non-uniform
     * scale cannot let a tilt on the short axis hide under the long axis's tolerance. Two
     * arrangements qualify:</p>
     * <ul>
     *   <li><b>Diagonal</b> — X along ±X and Y along ±Y (0° or 180°, plus flips)</li>
     *   <li><b>Anti-diagonal</b> — X along ±Y and Y along ±X (90° or 270°)</li>
     * </ul>
     * <p>A shear fails both by construction: it leaves one basis on its axis while tilting the
     * other, which is neither arrangement. A non-zero Z component on either basis is an
     * out-of-plane tilt and also fails.</p>
     *
     * @param matrix the pose matrix
     * @return {@code true} if bitmap glyphs may be used under this transform
     */
    public static boolean isAxisAligned(Matrix4f matrix) {
        float m00 = matrix.m00(), m01 = matrix.m01(), m02 = matrix.m02();
        float m10 = matrix.m10(), m11 = matrix.m11(), m12 = matrix.m12();

        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01 + m02 * m02);
        float sy = (float) Math.sqrt(m10 * m10 + m11 * m11 + m12 * m12);

        // A degenerate basis draws nothing at all; report aligned rather than forcing a tier
        // change on a quad with no visible area.
        if (sx <= 0f || sy <= 0f) return true;

        float tolX = AXIS_ALIGNMENT_EPSILON * sx;
        float tolY = AXIS_ALIGNMENT_EPSILON * sy;

        // Out-of-plane tilt disqualifies both arrangements, so test it once up front.
        if (Math.abs(m02) > tolX || Math.abs(m12) > tolY) return false;

        boolean diagonal = Math.abs(m01) <= tolX && Math.abs(m10) <= tolY;
        boolean antiDiagonal = Math.abs(m00) <= tolX && Math.abs(m11) <= tolY;
        return diagonal || antiDiagonal;
    }

    /**
     * Extracts the maximum absolute axis scale from a 4×4 matrix by computing
     * the lengths of the X and Y basis column vectors.
     *
     * <p>For an affine transformation matrix:
     * <pre>
     *   | m00 m10 m20 m30 |
     *   | m01 m11 m21 m31 |
     *   | m02 m12 m22 m32 |
     *   | m03 m13 m23 m33 |
     * </pre>
     * The X basis vector is (m00, m01, m02) and the Y basis vector is (m10, m11, m12).
     * Their lengths give the scale factors along each axis.</p>
     *
     * @param matrix the pose matrix
     * @return {@code max(|sx|, |sy|)}, always >= 0
     */
    public static float extractMaxScale(Matrix4f matrix) {
        // X basis vector length
        float sx = (float) Math.sqrt(
                matrix.m00() * matrix.m00() +
                matrix.m01() * matrix.m01() +
                matrix.m02() * matrix.m02());
        // Y basis vector length
        float sy = (float) Math.sqrt(
                matrix.m10() * matrix.m10() +
                matrix.m11() * matrix.m11() +
                matrix.m12() * matrix.m12());
        return Math.max(sx, sy);
    }
}
