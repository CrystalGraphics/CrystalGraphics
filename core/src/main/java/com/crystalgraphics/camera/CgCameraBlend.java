package com.crystalgraphics.camera;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * How a rig blends in over the camera below it: Gameplay Cameras' blend nodes ({@code Nodes/Blends/}). {@code pop} cuts,
 * and the timed ones run a factor from 0 to 1 over their time on the viewer's clock.
 *
 * <pre>{@code
 * CgCameraBlend in = CgCameraBlend.of(Map.of("kind", "smooth", "time", 0.5, "type", "smoother_step"));
 * CgCameraBlend.Running r = in.start(0.5f);
 * r.advance(dt);                     // each frame, before reading
 * float f = r.factor();              // SmootherStep(t / 0.5)
 * }</pre>
 *
 * <ul>
 *   <li>A kind or easing type it does not play fails at load, naming it: {@code orbit} and
 *       {@code location_rotation} are not played yet.</li>
 *   <li>The time is read once, when the blend starts, as {@code FSimpleFixedTimeBlendCameraNodeEvaluator} reads it.</li>
 * </ul>
 */
public final class CgCameraBlend {

    /** The blend kinds played. */
    public enum Kind { POP, LINEAR, SMOOTH, EASING }

    /** A cut: {@code UPopBlendCameraNode}, full and finished from its first frame. */
    public static final CgCameraBlend POP = new CgCameraBlend(Kind.POP, 0, null);

    private final Kind kind;
    /** The time as written: a number, or a rig parameter's binding the rig resolves. */
    @Nullable
    private final Object time;
    private final int type;

    private CgCameraBlend(Kind kind, @Nullable Object time, int type) {
        this.kind = kind;
        this.time = time;
        this.type = type;
    }

    private CgCameraBlend(Kind kind, @Nullable Object time, @Nullable String type) {
        this(kind, time, type == null ? 0 : typeIndex(kind, type));
    }

    /** A blend as a rig file writes it ({@code { kind, time, type }}); null or absent is {@link #POP}. */
    public static CgCameraBlend of(@Nullable Object written) {
        if (written == null) return POP;
        if (!(written instanceof Map<?, ?> m)) throw new IllegalArgumentException("a blend as " + written + ": a map");
        String kind = String.valueOf(m.get("kind"));
        Object time = m.containsKey("time") ? m.get("time") : 1.0;
        String type = m.get("type") == null ? null : String.valueOf(m.get("type"));
        return switch (kind) {
            case "pop" -> POP;
            case "linear" -> new CgCameraBlend(Kind.LINEAR, time, (String) null);
            case "smooth" -> new CgCameraBlend(Kind.SMOOTH, time, type == null ? "smooth_step" : type);
            case "easing" -> new CgCameraBlend(Kind.EASING, time, type == null ? "linear" : type);
            default -> throw new IllegalArgumentException("a camera blend of kind " + kind + " is not played yet");
        };
    }

    public Kind kind() {
        return kind;
    }

    /** Its time as written: a number, a binding, or null for a pop. */
    @Nullable
    public Object time() {
        return time;
    }

    /** It running for {@code seconds}. */
    public Running start(float seconds) {
        return new Running(this, kind == Kind.POP ? 0f : Math.max(0f, seconds));
    }

    /** The factor at {@code t}, a fraction of its time from 0 to 1. */
    float factor(float t) {
        return switch (kind) {
            case POP -> 1f;
            case LINEAR -> t;
            // SmoothBlendCameraNode.cpp; SmoothStep and SmootherStep from Math/Interpolation.h.
            case SMOOTH -> type == 0 ? t * t * (3f - 2f * t) : t * t * t * (t * (t * 6f - 15f) + 10f);
            case EASING -> easing(EASINGS[type], t);
        };
    }

    private static final String[] SMOOTHS = {"smooth_step", "smoother_step"};
    private static final String[] EASINGS = {"linear", "sin_in", "sin_out", "sin_in_out", "quad_in", "quad_out",
            "quad_in_out", "cubic", "cubic_in", "cubic_out", "cubic_in_out", "hermite_cubic_in_out", "quart_in", "quart_out",
            "quart_in_out", "quint_in", "quint_out", "quint_in_out", "expo_in", "expo_out", "expo_in_out", "circ_in",
            "circ_out", "circ_in_out"};

    private static int typeIndex(Kind kind, String type) {
        String[] names = kind == Kind.SMOOTH ? SMOOTHS : EASINGS;
        for (int i = 0; i < names.length; i++) if (names[i].equals(type.toLowerCase(Locale.ROOT))) return i;
        throw new IllegalArgumentException("a " + kind.name().toLowerCase(Locale.ROOT) + " blend of type " + type + " is not played");
    }

    /** {@code EasingBlendCameraNode.cpp}'s {@code EvaluateEasing}: Sequencer's built-in easings. */
    private static float easing(String type, float f) {
        float in = f * 2f, out = (f - 0.5f) * 2f;
        return switch (type) {
            case "sin_in" -> sinIn(f);
            case "sin_out" -> sinOut(f);
            case "sin_in_out" -> in < 1f ? 0.5f * sinIn(in) : 0.5f + 0.5f * sinOut(out);
            case "quad_in" -> powIn(f, 2);
            case "quad_out" -> powOut(f, 2);
            case "quad_in_out" -> in < 1f ? 0.5f * powIn(in, 2) : 0.5f + 0.5f * powOut(out, 2);
            // CubicInterp(0, 0, 1, 0, f) and SmoothStep(0, 1, f): both the Hermite step.
            case "cubic", "hermite_cubic_in_out" -> clamp(f * f * (3f - 2f * f));
            case "cubic_in" -> powIn(f, 3);
            case "cubic_out" -> powOut(f, 3);
            case "cubic_in_out" -> in < 1f ? 0.5f * powIn(in, 3) : 0.5f + 0.5f * powOut(out, 3);
            case "quart_in" -> powIn(f, 4);
            case "quart_out" -> powOut(f, 4);
            case "quart_in_out" -> in < 1f ? 0.5f * powIn(in, 4) : 0.5f + 0.5f * powOut(out, 4);
            case "quint_in" -> powIn(f, 5);
            case "quint_out" -> powOut(f, 5);
            case "quint_in_out" -> in < 1f ? 0.5f * powIn(in, 5) : 0.5f + 0.5f * powOut(out, 5);
            case "expo_in" -> expIn(f);
            case "expo_out" -> 1f - expIn(1f - f);
            case "expo_in_out" -> in < 1f ? 0.5f * expIn(in) : 0.5f + 0.5f * (1f - expIn(1f - out));
            case "circ_in" -> circIn(f);
            case "circ_out" -> 1f - circIn(1f - f);
            case "circ_in_out" -> in < 1f ? 0.5f * circIn(in) : 0.5f + 0.5f * (1f - circIn(1f - out));
            default -> f;
        };
    }

    private static float sinOut(float t) {
        return (float) Math.sin(0.5 * Math.PI * t);
    }

    private static float sinIn(float t) {
        return 1f - sinOut(1f - t);
    }

    private static float powIn(float t, int p) {
        return (float) Math.pow(t, p);
    }

    private static float powOut(float t, int p) {
        return 1f - (float) Math.pow(1f - t, p);
    }

    private static float expIn(float t) {
        return (float) Math.pow(2, 10 * (t - 1f));
    }

    private static float circIn(float t) {
        return 1f - (float) Math.sqrt(1 - t * t);
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    @Override
    public String toString() {
        return kind == Kind.POP ? "pop" : kind.name().toLowerCase(Locale.ROOT) + " over " + time;
    }

    /**
     * A blend under way: {@code FSimpleFixedTimeBlendCameraNodeEvaluator}. Its time runs on the viewer's clock and
     * stops at its total, where it is finished.
     */
    public static final class Running {
        private final CgCameraBlend blend;
        private float total, current;
        private boolean reversed;

        Running(CgCameraBlend blend, float total) {
            this.blend = blend;
            this.total = total;
        }

        /** Runs it the other way, 1 to 0: an exit's ({@code OnSetReversed}). */
        public Running reversed() {
            reversed = true;
            return this;
        }

        /**
         * Shortens it to the share of {@code interrupted} that had run, so a blend broken off at 70% is undone in 70% of
         * this one's time ({@code OnInitializeFromInterruption}).
         */
        public Running interrupting(@Nullable Running interrupted) {
            if (interrupted != null && interrupted.blend.kind != Kind.POP) total *= interrupted.timeFactor();
            return this;
        }

        public void advance(float dt) {
            current = Math.min(total, current + dt);
        }

        /** Its share of its time, 0 to 1; 1 with no time. */
        public float timeFactor() {
            return total > 0f ? current / total : 1f;
        }

        /** The blend factor this frame, clamped to [0, 1] and reversed for an exit ({@code FSimpleBlendCameraNodeEvaluator::OnRun}). */
        public float factor() {
            float f = Math.max(0f, Math.min(1f, blend.factor(timeFactor())));
            return reversed ? 1f - f : f;
        }

        /** Whether it has covered what is below it: {@code bIsBlendFull}. */
        public boolean full() {
            return reversed ? factor() <= 0f : factor() >= 1f;
        }

        /** Whether its time has run out: {@code bIsBlendFinished}. */
        public boolean finished() {
            return current >= total;
        }

        public CgCameraBlend blend() {
            return blend;
        }
    }
}
