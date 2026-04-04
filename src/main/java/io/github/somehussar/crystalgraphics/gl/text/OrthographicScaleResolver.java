package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.PoseStack;
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
 * not available in the pose matrix alone. A future
 * {@code PerspectiveScaleResolver} would handle that case.</p>
 *
 * @see CgTextScaleResolver
 */
final class OrthographicScaleResolver implements CgTextScaleResolver {

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
    static float extractMaxScale(Matrix4f matrix) {
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
