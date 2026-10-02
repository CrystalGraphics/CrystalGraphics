package com.crystalgraphics.vfx.look;

import java.util.Arrays;

/**
 * A value over time: keys at increasing times, each segment eased its own way. Immutable, so one curve serves every
 * look and effect that reads it.
 *
 * <pre>{@code
 * CgVfxCurve grow = CgVfxCurve.start(0f, 0.1f)                 // time, value
 *         .to(0.85f, 1.25f, CgVfxEasing.OUT_CUBIC)               // eased from the key before
 *         .to(1f, 1f, CgVfxEasing.IN_OUT_QUADRATIC)
 *         .build();
 * float size = grow.at(0.5f);
 * CgVfxCurve still = CgVfxCurve.constant(1f);
 * }</pre>
 *
 * <ul>
 *   <li>Before the first key the curve holds the first value, after the last it holds the last.</li>
 *   <li>Keys must come in increasing time; equal times make a step.</li>
 * </ul>
 */
public final class CgVfxCurve {

    private final float[] times;
    private final float[] values;
    private final CgVfxEasing[] easings;

    private CgVfxCurve(float[] times, float[] values, CgVfxEasing[] easings) {
        this.times = times;
        this.values = values;
        this.easings = easings;
    }

    public static CgVfxCurve constant(float value) {
        return new CgVfxCurve(new float[]{0f}, new float[]{value}, new CgVfxEasing[]{CgVfxEasing.LINEAR});
    }

    public static Builder start(float time, float value) {
        return new Builder(time, value);
    }

    public float at(float time) {
        if (time <= times[0]) return values[0];
        int last = times.length - 1;
        if (time >= times[last]) return values[last];
        int i = 1;
        while (times[i] < time) i++;
        float span = times[i] - times[i - 1];
        float t = span > 0f ? (time - times[i - 1]) / span : 1f;
        return values[i - 1] + (values[i] - values[i - 1]) * easings[i].apply(t);
    }

    /** The time of the last key. */
    public float end() {
        return times[times.length - 1];
    }

    public static final class Builder {

        private float[] times = new float[4];
        private float[] values = new float[4];
        private CgVfxEasing[] easings = new CgVfxEasing[4];
        private int count;

        private Builder(float time, float value) {
            add(time, value, CgVfxEasing.LINEAR);
        }

        /** A key at {@code time}, reached from the one before along {@code easing}. */
        public Builder to(float time, float value, CgVfxEasing easing) {
            if (time < times[count - 1]) throw new IllegalArgumentException("Key at " + time + " comes before " + times[count - 1]);
            add(time, value, easing);
            return this;
        }

        public CgVfxCurve build() {
            return new CgVfxCurve(Arrays.copyOf(times, count), Arrays.copyOf(values, count), Arrays.copyOf(easings, count));
        }

        private void add(float time, float value, CgVfxEasing easing) {
            if (count == times.length) {
                times = Arrays.copyOf(times, count * 2);
                values = Arrays.copyOf(values, count * 2);
                easings = Arrays.copyOf(easings, count * 2);
            }
            times[count] = time;
            values[count] = value;
            easings[count] = easing;
            count++;
        }
    }
}
