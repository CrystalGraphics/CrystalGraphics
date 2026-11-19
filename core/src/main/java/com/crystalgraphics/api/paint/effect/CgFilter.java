package com.crystalgraphics.api.paint.effect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * What a layer's picture goes through before it is composited: CSS's {@code filter}, a chain of blurs, drop shadows,
 * colour adjustments and, past CSS, a material of your own. A value: chained once, kept, shared.
 *
 * <pre>{@code
 * static final CgFilter SOFT = CgFilter.blur(4f);
 * static final CgFilter MUTED = CgFilter.saturate(0.3f).then(CgFilter.brightness(0.8f));
 * static final CgFilter LIFTED = CgFilter.dropShadow(0, 4, 12, 0x80000000);
 * static final CgFilter SMEAR = CgFilter.blur(0f, 6f);                                  // down only: a motion blur
 * static final CgFilter GLOW = CgFilter.material(silhouette, "_MainTex");                // the picture through a shader
 *
 * try (CgCanvasLayer layer = canvas.layer().filter(MUTED.then(SOFT))) {
 *     ...                                                                                // drawn, then filtered
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Applied in order, each to the result of the one before, as CSS chains its functions.</li>
 *   <li>Blur and shadow lengths are in the units the layer is drawn in; a blur is a Gaussian's sigma, a drop shadow's
 *       blur CSS's radius (two sigma).</li>
 *   <li>Colour adjustments next to each other fold into one matrix, so a chain of them costs one pass.</li>
 *   <li>A blur or shadow reaches past the layer's bounds by three sigma; the layer grows to hold it.</li>
 * </ul>
 */
public final class CgFilter {

    /** One step of a chain. */
    public enum Kind {
        /** A Gaussian blur: {@link Op#values()} is sigma across, sigma down. */
        BLUR,
        /** A blurred, offset, coloured copy of the picture's alpha under it: dx, dy, CSS blur radius, colour as bits. */
        DROP_SHADOW,
        /** A 4x5 colour matrix, row-major, on straight RGBA in 0..1: twenty values. */
        COLOR_MATRIX,
        /** The picture drawn through {@link Op#material()}, its texture parameter taking the picture. */
        MATERIAL
    }

    /**
     * One step: its kind and numbers, and for {@link Kind#MATERIAL} its material and the texture parameter that takes
     * the picture.
     */
    public record Op(Kind kind, float[] values, CgMaterialInterface material, String textureParameter) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Op op && kind == op.kind && Arrays.equals(values, op.values)
                    && material == op.material && Objects.equals(textureParameter, op.textureParameter);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * kind.hashCode() + Arrays.hashCode(values)) + System.identityHashCode(material);
        }
    }

    /** No change: the picture as drawn. */
    public static final CgFilter NONE = new CgFilter(Collections.emptyList());

    private final List<Op> ops;

    private CgFilter(List<Op> ops) {
        this.ops = ops;
    }

    /** A Gaussian blur of {@code sigma} on both axes. */
    public static CgFilter blur(float sigma) {
        return blur(sigma, sigma);
    }

    /** A Gaussian blur of {@code sigmaX} across and {@code sigmaY} down: one axis at 0 blurs along the other only. */
    public static CgFilter blur(float sigmaX, float sigmaY) {
        nonNegative(sigmaX, "sigmaX");
        nonNegative(sigmaY, "sigmaY");
        return of(new Op(Kind.BLUR, new float[]{sigmaX, sigmaY}, null, null));
    }

    /** CSS's {@code drop-shadow(dx dy blur colour)}: the picture's shape, offset, blurred and coloured, under it. */
    public static CgFilter dropShadow(float dx, float dy, float blur, int argb) {
        nonNegative(blur, "blur");
        // CSS's blur radius is two sigma
        return dropShadow(dx, dy, blur * 0.5f, blur * 0.5f, argb);
    }

    /** A drop shadow blurred by a sigma per axis, as SVG's {@code feDropShadow stdDeviation} gives it. */
    public static CgFilter dropShadow(float dx, float dy, float sigmaX, float sigmaY, int argb) {
        nonNegative(sigmaX, "sigmaX");
        nonNegative(sigmaY, "sigmaY");
        return of(new Op(Kind.DROP_SHADOW, new float[]{dx, dy, sigmaX, sigmaY, Float.intBitsToFloat(argb)}, null, null));
    }

    /** CSS's {@code opacity(amount)}: 0 clear, 1 unchanged. */
    public static CgFilter opacity(float amount) {
        float a = clamp01(amount);
        return matrix(new float[]{1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, a, 0});
    }

    /** CSS's {@code brightness(amount)}: 0 black, 1 unchanged, above 1 brighter. */
    public static CgFilter brightness(float amount) {
        float b = Math.max(0f, amount);
        return matrix(new float[]{b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code contrast(amount)}: 0 grey, 1 unchanged. */
    public static CgFilter contrast(float amount) {
        float c = Math.max(0f, amount), o = 0.5f - 0.5f * c;
        return matrix(new float[]{c, 0, 0, 0, o, 0, c, 0, 0, o, 0, 0, c, 0, o, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code saturate(amount)}: 0 grey, 1 unchanged, above 1 more vivid. */
    public static CgFilter saturate(float amount) {
        float s = Math.max(0f, amount);
        return matrix(new float[]{
                0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0, 0,
                0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0, 0,
                0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0, 0,
                0, 0, 0, 1, 0});
    }

    /** CSS's {@code grayscale(amount)}: 1 fully grey. */
    public static CgFilter grayscale(float amount) {
        return saturate(1f - clamp01(amount));
    }

    /** CSS's {@code sepia(amount)}: 1 fully sepia. */
    public static CgFilter sepia(float amount) {
        float k = 1f - clamp01(amount);
        return matrix(new float[]{
                0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k, 0, 0,
                0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k, 0, 0,
                0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k, 0, 0,
                0, 0, 0, 1, 0});
    }

    /** CSS's {@code invert(amount)}: 1 fully inverted. */
    public static CgFilter invert(float amount) {
        float i = clamp01(amount), d = 1f - 2f * i;
        return matrix(new float[]{d, 0, 0, 0, i, 0, d, 0, 0, i, 0, 0, d, 0, i, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code hue-rotate(degrees)}, the Filter Effects spec's matrix. */
    public static CgFilter hueRotate(float degrees) {
        double r = Math.toRadians(degrees);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        return matrix(new float[]{
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0, 0,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0, 0,
                0, 0, 0, 1, 0});
    }

    /** A 4x5 colour matrix, row-major: each output channel is a weighted sum of R, G, B, A plus an offset, in 0..1. */
    public static CgFilter matrix(float[] matrix4x5) {
        if (matrix4x5.length != 20) throw new IllegalArgumentException("a colour matrix is 20 values, not " + matrix4x5.length);
        for (float v : matrix4x5) {
            if (!Float.isFinite(v)) throw new IllegalArgumentException("a colour matrix's values must be finite");
        }
        return of(new Op(Kind.COLOR_MATRIX, matrix4x5.clone(), null, null));
    }

    /**
     * The picture drawn through {@code material}, a UI-domain material whose sampler {@code textureParameter} takes
     * it: a silhouette, a dissolve, a distortion of your own.
     *
     * <ul>
     *   <li>The canvas draws an instance of it per layer, so one filter serves any number of layers in a frame; set its
     *       other values on {@code material} itself.</li>
     * </ul>
     */
    public static CgFilter material(CgMaterialInterface material, String textureParameter) {
        return of(new Op(Kind.MATERIAL, new float[0], Objects.requireNonNull(material, "material"),
                Objects.requireNonNull(textureParameter, "textureParameter")));
    }

    /** This chain followed by {@code next}. Colour matrices that meet fold into one. */
    public CgFilter then(CgFilter next) {
        if (next.ops.isEmpty()) return this;
        if (ops.isEmpty()) return next;
        List<Op> joined = new ArrayList<>(ops.size() + next.ops.size());
        joined.addAll(ops);
        for (Op op : next.ops) {
            Op last = joined.get(joined.size() - 1);
            if (op.kind == Kind.COLOR_MATRIX && last.kind == Kind.COLOR_MATRIX) {
                joined.set(joined.size() - 1, new Op(Kind.COLOR_MATRIX, compose(op.values, last.values), null, null));
            } else {
                joined.add(op);
            }
        }
        return new CgFilter(Collections.unmodifiableList(joined));
    }

    /** Its steps, in order. */
    public List<Op> ops() {
        return ops;
    }

    /** Whether it changes nothing. */
    public boolean isNone() {
        return ops.isEmpty();
    }

    /** How far past the picture's bounds it draws: three sigma of its widest blur or shadow, plus a shadow's offset. */
    public float reach() {
        float reach = 0f;
        for (Op op : ops) {
            if (op.kind == Kind.BLUR) reach += 3f * Math.max(op.values[0], op.values[1]);
            if (op.kind == Kind.DROP_SHADOW) {
                reach = Math.max(reach, 3f * Math.max(op.values[2], op.values[3])
                        + Math.max(Math.abs(op.values[0]), Math.abs(op.values[1])));
            }
        }
        return reach;
    }

    private static CgFilter of(Op op) {
        return new CgFilter(Collections.singletonList(op));
    }

    /** {@code outer} applied after {@code inner}: outer x [inner; 0 0 0 0 1]. */
    private static float[] compose(float[] outer, float[] inner) {
        float[] r = new float[20];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                float sum = col == 4 ? outer[row * 5 + 4] : 0f;
                for (int k = 0; k < 4; k++) sum += outer[row * 5 + k] * inner[k * 5 + col];
                r[row * 5 + col] = sum;
            }
        }
        return r;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static void nonNegative(float v, String name) {
        if (!(v >= 0f) || Float.isInfinite(v)) throw new IllegalArgumentException(name + " must be finite and >= 0: " + v);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgFilter f && ops.equals(f.ops);
    }

    @Override
    public int hashCode() {
        return ops.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("CgFilter[");
        for (int i = 0; i < ops.size(); i++) b.append(i == 0 ? "" : ", ").append(ops.get(i).kind);
        return b.append(']').toString();
    }
}
