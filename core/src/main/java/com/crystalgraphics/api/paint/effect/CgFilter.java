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
        MATERIAL,
        /**
         * Fluent's luminosity: toward the picture with a colour's brightness and its own hue, by an amount. r, g, b of
         * the colour (0..1, as written), then the amount. A backdrop's step: not linear, so no layer takes it.
         */
        LUMINOSITY
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

    /** The function a step was written as: CSS's, and past CSS a tint, a luminosity, a matrix and a material. */
    public enum Fn {
        BLUR, DROP_SHADOW, OPACITY, BRIGHTNESS, CONTRAST, SATURATE, GRAYSCALE, SEPIA, INVERT, HUE_ROTATE, TINT,
        LUMINOSITY, MATRIX, MATERIAL
    }

    /**
     * One step as written: its function, its arguments as given (a blur's two sigmas, a colour function's amount, a
     * hue's degrees, a drop shadow's offsets and sigmas, a matrix's twenty values), its colour where it has one (a tint,
     * a luminosity, a shadow), and the {@link Op} it runs as.
     */
    public record Step(Fn function, float[] args, int color, Op op) {

        /** Its first argument: the amount, the degrees, a blur's sigma across. */
        public float value() {
            return args.length == 0 ? 0f : args[0];
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Step t && function == t.function && Arrays.equals(args, t.args) && color == t.color
                    && op.equals(t.op);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * function.hashCode() + Arrays.hashCode(args)) + color;
        }
    }

    /** No change: the picture as drawn. */
    public static final CgFilter NONE = new CgFilter(Collections.emptyList());

    private final List<Step> steps;
    /** The steps as they run: colour matrices that meet folded into one. */
    private final List<Op> ops;

    private CgFilter(List<Step> steps) {
        this.steps = steps;
        List<Op> folded = new ArrayList<>(steps.size());
        for (Step step : steps) {
            Op op = step.op;
            Op last = folded.isEmpty() ? null : folded.get(folded.size() - 1);
            if (last != null && op.kind == Kind.COLOR_MATRIX && last.kind == Kind.COLOR_MATRIX) {
                folded.set(folded.size() - 1, new Op(Kind.COLOR_MATRIX, compose(op.values, last.values), null, null));
            } else {
                folded.add(op);
            }
        }
        this.ops = Collections.unmodifiableList(folded);
    }

    /** {@code steps} as a chain, in order: what an editor rebuilds a filter from. */
    public static CgFilter of(List<Step> steps) {
        return steps.isEmpty() ? NONE : new CgFilter(Collections.unmodifiableList(new ArrayList<>(steps)));
    }

    /** A Gaussian blur of {@code sigma} on both axes. */
    public static CgFilter blur(float sigma) {
        return blur(sigma, sigma);
    }

    /** A Gaussian blur of {@code sigmaX} across and {@code sigmaY} down: one axis at 0 blurs along the other only. */
    public static CgFilter blur(float sigmaX, float sigmaY) {
        nonNegative(sigmaX, "sigmaX");
        nonNegative(sigmaY, "sigmaY");
        return of(Fn.BLUR, new float[]{sigmaX, sigmaY}, 0, new Op(Kind.BLUR, new float[]{sigmaX, sigmaY}, null, null));
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
        return of(Fn.DROP_SHADOW, new float[]{dx, dy, sigmaX, sigmaY}, argb,
                new Op(Kind.DROP_SHADOW, new float[]{dx, dy, sigmaX, sigmaY, Float.intBitsToFloat(argb)}, null, null));
    }

    /** CSS's {@code opacity(amount)}: 0 clear, 1 unchanged. */
    public static CgFilter opacity(float amount) {
        float a = clamp01(amount);
        return as(Fn.OPACITY, amount, 0, new float[]{1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, a, 0});
    }

    /** CSS's {@code brightness(amount)}: 0 black, 1 unchanged, above 1 brighter. */
    public static CgFilter brightness(float amount) {
        float b = Math.max(0f, amount);
        return as(Fn.BRIGHTNESS, amount, 0, new float[]{b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code contrast(amount)}: 0 grey, 1 unchanged. */
    public static CgFilter contrast(float amount) {
        float c = Math.max(0f, amount), o = 0.5f - 0.5f * c;
        return as(Fn.CONTRAST, amount, 0, new float[]{c, 0, 0, 0, o, 0, c, 0, 0, o, 0, 0, c, 0, o, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code saturate(amount)}: 0 grey, 1 unchanged, above 1 more vivid. */
    public static CgFilter saturate(float amount) {
        float s = Math.max(0f, amount);
        return as(Fn.SATURATE, amount, 0, new float[]{
                0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0, 0,
                0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0, 0,
                0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0, 0,
                0, 0, 0, 1, 0});
    }

    /** CSS's {@code grayscale(amount)}: 1 fully grey. */
    public static CgFilter grayscale(float amount) {
        return as(Fn.GRAYSCALE, amount, 0, saturate(1f - clamp01(amount)).ops.get(0).values);
    }

    /** CSS's {@code sepia(amount)}: 1 fully sepia. */
    public static CgFilter sepia(float amount) {
        float k = 1f - clamp01(amount);
        return as(Fn.SEPIA, amount, 0, new float[]{
                0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k, 0, 0,
                0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k, 0, 0,
                0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k, 0, 0,
                0, 0, 0, 1, 0});
    }

    /** CSS's {@code invert(amount)}: 1 fully inverted. */
    public static CgFilter invert(float amount) {
        float i = clamp01(amount), d = 1f - 2f * i;
        return as(Fn.INVERT, amount, 0, new float[]{d, 0, 0, 0, i, 0, d, 0, 0, i, 0, 0, d, 0, i, 0, 0, 0, 1, 0});
    }

    /** CSS's {@code hue-rotate(degrees)}, the Filter Effects spec's matrix. */
    public static CgFilter hueRotate(float degrees) {
        double r = Math.toRadians(degrees);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        return as(Fn.HUE_ROTATE, degrees, 0, new float[]{
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0, 0,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0, 0,
                0, 0, 0, 1, 0});
    }

    /**
     * {@code argb} laid over the picture by its alpha, straight {@code 0xAARRGGBB}: Fluent's tint, past CSS. A colour
     * matrix, so it folds with its neighbours.
     */
    public static CgFilter tint(int argb) {
        float a = (argb >>> 24) / 255f, k = 1f - a;
        float r = (argb >>> 16 & 0xFF) / 255f * a, g = (argb >>> 8 & 0xFF) / 255f * a, b = (argb & 0xFF) / 255f * a;
        return as(Fn.TINT, 0f, argb, new float[]{k, 0, 0, 0, r, 0, k, 0, 0, g, 0, 0, k, 0, b, 0, 0, 0, 1, 0});
    }

    /**
     * Toward the picture taking {@code argb}'s brightness and keeping its own hue, by {@code amount} 0 to 1: Fluent
     * acrylic's luminosity layer, so a dark tint is a dark pane over anything. A backdrop filter's step alone.
     *
     * <pre>{@code
     * CgBackdropFilter acrylic = CgBackdropFilter.of(CgFilter.blur(30f).then(CgFilter.saturate(1.5f))
     *         .then(CgFilter.luminosity(0xFF1F1F23, 0.1f)).then(CgFilter.tint(0x401F1F23)));
     * }</pre>
     */
    public static CgFilter luminosity(int argb, float amount) {
        return of(Fn.LUMINOSITY, new float[]{clamp01(amount)}, argb, new Op(Kind.LUMINOSITY, new float[]{(argb >>> 16 & 0xFF) / 255f, (argb >>> 8 & 0xFF) / 255f,
                (argb & 0xFF) / 255f, clamp01(amount)}, null, null));
    }

    /** A 4x5 colour matrix, row-major: each output channel is a weighted sum of R, G, B, A plus an offset, in 0..1. */
    public static CgFilter matrix(float[] matrix4x5) {
        if (matrix4x5.length != 20) throw new IllegalArgumentException("a colour matrix is 20 values, not " + matrix4x5.length);
        for (float v : matrix4x5) {
            if (!Float.isFinite(v)) throw new IllegalArgumentException("a colour matrix's values must be finite");
        }
        return of(Fn.MATRIX, matrix4x5.clone(), 0, new Op(Kind.COLOR_MATRIX, matrix4x5.clone(), null, null));
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
        return of(Fn.MATERIAL, new float[0], 0, new Op(Kind.MATERIAL, new float[0], Objects.requireNonNull(material, "material"),
                Objects.requireNonNull(textureParameter, "textureParameter")));
    }

    /** This chain followed by {@code next}. Colour matrices that meet fold into one. */
    public CgFilter then(CgFilter next) {
        if (next.steps.isEmpty()) return this;
        if (steps.isEmpty()) return next;
        List<Step> joined = new ArrayList<>(steps.size() + next.steps.size());
        joined.addAll(steps);
        joined.addAll(next.steps);
        return new CgFilter(Collections.unmodifiableList(joined));
    }

    /** Its steps as written, in order: each function with its own arguments, nothing folded. */
    public List<Step> steps() {
        return steps;
    }

    /** Its steps as they run, in order: colour matrices that meet folded into one. */
    public List<Op> ops() {
        return ops;
    }

    /** Whether it changes nothing. */
    public boolean isNone() {
        return steps.isEmpty();
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

    private static CgFilter of(Fn fn, float[] args, int color, Op op) {
        return new CgFilter(Collections.singletonList(new Step(fn, args, color, op)));
    }

    /** A colour function written as {@code fn(arg)}, running as {@code matrix}. */
    private static CgFilter as(Fn fn, float arg, int color, float[] matrix) {
        return of(fn, new float[]{arg}, color, matrix(matrix).ops.get(0));
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
        return o instanceof CgFilter f && steps.equals(f.steps);
    }

    @Override
    public int hashCode() {
        return steps.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("CgFilter[");
        for (int i = 0; i < steps.size(); i++) b.append(i == 0 ? "" : ", ").append(steps.get(i).function);
        return b.append(']').toString();
    }
}
