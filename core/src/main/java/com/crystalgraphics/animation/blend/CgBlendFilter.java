package com.crystalgraphics.animation.blend;

import java.util.Arrays;

import com.crystalgraphics.easing.CgSmoothing;

/**
 * One blend space axis's input smoothing: Unreal's {@code FFIRFilterTimeBased} (AnimInterpFilter.cpp). The input a
 * blend space reads is this filter's output, so a speed that jumps eases the blend instead.
 *
 * <pre>{@code
 * CgBlendFilter speed = new CgBlendFilter();
 * speed.initialize(0.2f, CgBlendFilter.Type.SPRING_DAMPER, 1f, 0f, 8f, 0f, true);
 * float smoothed = speed.update(rawSpeed, dt);          // every frame
 * }</pre>
 *
 * <ul>
 *   <li>A window of 0 passes the input through.</li>
 *   <li>The windowed types keep each input with its time; their buffer grows to the window's frames once, then stays.</li>
 * </ul>
 */
public final class CgBlendFilter {

    /** {@code EFilterInterpolationType}. */
    public enum Type {
        /** {@code BSIT_Average}: the window's inputs averaged. */
        AVERAGE,
        /** {@code BSIT_Linear}: newer inputs weighed more, falling linearly over the window. */
        LINEAR,
        /** {@code BSIT_Cubic}: as linear, falling as {@code 1 - t³}. */
        CUBIC,
        /** {@code BSIT_EaseInOut}: the window's middle weighed most. */
        EASE_IN_OUT,
        /** {@code BSIT_ExponentialDecay}: decays toward the input, time constant {@code window / e}. */
        EXPONENTIAL_DECAY,
        /** {@code BSIT_SpringDamper}: a spring toward the input, by its damping ratio. */
        SPRING_DAMPER
    }

    private Type type = Type.SPRING_DAMPER;
    private float window, damping = 1f, min, max, maxSpeed, current, lastOutput;
    private boolean clamp;
    private float[] inputs = new float[0], times = new float[0];
    private int stack;
    private final float[] spring = new float[2];
    private int springed;

    /** {@code Initialize}: settings set and its history forgotten. */
    public void initialize(float window, Type type, float damping, float min, float max, float maxSpeed, boolean clamp) {
        this.window = window;
        this.type = type;
        this.damping = damping;
        this.min = min;
        this.max = max;
        this.maxSpeed = maxSpeed;
        this.clamp = clamp;
        inputs = new float[0];
        times = new float[0];
        stack = 0;
        springed = 0;
        current = lastOutput = 0f;
    }

    /** {@code UpdateAndGetFilteredData}: {@code input} taken in over {@code dt} seconds; answers the filtered value. */
    public float update(float input, float dt) {
        if (window <= 0f) return lastOutput = input;
        if (dt <= CgSmoothing.KINDA_SMALL_NUMBER) return lastOutput;
        current += dt;
        float result;
        switch (type) {
            case EXPONENTIAL_DECAY -> {
                if (springed != 1) {
                    spring[0] = input;
                    springed = 1;
                }
                float was = spring[0];
                spring[0] = CgSmoothing.exponential(spring[0], input, dt, window / (float) Math.E);
                if (maxSpeed > 0f) spring[0] = Math.max(was - maxSpeed * dt, Math.min(was + maxSpeed * dt, spring[0]));
                result = spring[0];
            }
            case SPRING_DAMPER -> {
                if (springed != 2) {
                    spring[0] = input;
                    spring[1] = 0f;
                    springed = 2;
                }
                float was = spring[0];
                CgSmoothing.springDamper(spring, input, 0f, dt, window / (float) Math.E, damping);
                if (maxSpeed > 0f) {
                    spring[0] = Math.max(was - maxSpeed * dt, Math.min(was + maxSpeed * dt, spring[0]));
                    spring[1] = Math.max(-maxSpeed, Math.min(maxSpeed, spring[1]));
                }
                if (clamp) {
                    if (spring[0] > max) {
                        spring[0] = max;
                        if (spring[1] > 0f) spring[1] = 0f;
                    }
                    if (spring[0] < min) {
                        spring[0] = min;
                        if (spring[1] < 0f) spring[1] = 0f;
                    }
                }
                result = spring[0];
            }
            default -> {
                refresh();
                stack = freeSlot();
                inputs[stack] = input;
                times[stack] = current;
                result = windowed();
                if (++stack > inputs.length - 1) stack = 0;
            }
        }
        return lastOutput = result;
    }

    /** {@code WrapToValue}: its state moved by whole {@code range}s to lie within half a range of {@code input}. */
    public void wrapToValue(float input, float range) {
        if (range <= 0f) return;
        float half = range / 2f;
        if (type == Type.EXPONENTIAL_DECAY || type == Type.SPRING_DAMPER) {
            if (springed != 0) spring[0] = wrap(spring[0], input - half, input + half);
        } else if (window > 0f) {
            float moved = wrap(lastOutput, input - half, input + half), shift = moved - lastOutput;
            if (shift != 0f) {
                lastOutput = moved;
                for (int i = 0; i < inputs.length; i++) inputs[i] += shift;
            }
        }
    }

    /** {@code SetToValue}: jumps to {@code value}, as a blend space player snapping its position. */
    public void setToValue(float value) {
        lastOutput = value;
        if (springed != 0) spring[0] = value;
        else if (inputs.length > 0) inputs[0] = value;
    }

    /** {@code FMath::Wrap}. */
    static float wrap(float x, float lo, float hi) {
        float size = hi - lo;
        if (size == 0f) return hi;
        float end = x;
        if (end < lo) end = hi - ((lo - end) % size);
        else if (end > hi) end = lo + ((end - hi) % size);
        return end;
    }

    private void refresh() {
        if (inputs.length == 0) {
            inputs = new float[10];
            times = new float[10];
            stack = 0;
        } else {
            // EnsureTimeIsValid: an entry older than the window is spent
            for (int i = 0; i < times.length; i++) if (current - times[i] > window) times[i] = 0f;
        }
    }

    /** {@code GetSafeCurrentStackIndex}: the next spent slot, growing by five when none is. */
    private int freeSlot() {
        if (times[stack] <= 0f) return stack;
        for (int i = 0; i < times.length; i++) {
            int at = (stack + i) % times.length;
            if (times[at] <= 0f) return at;
        }
        int at = times.length;
        inputs = Arrays.copyOf(inputs, at + 5);
        times = Arrays.copyOf(times, at + 5);
        return at;
    }

    private float windowed() {
        float sumC = 0f, sum = 0f;
        for (int i = 0; i < inputs.length; i++) {
            if (times[i] <= 0f) continue;
            float diff = current - times[i];
            if (diff > window) continue;
            float t = diff / window, c = switch (type) {
                case AVERAGE -> 1f;
                case LINEAR -> 1f - t;
                case CUBIC -> 1f - t * t * t;
                case EASE_IN_OUT -> 1f - 4f * (t - 0.5f) * (t - 0.5f);
                default -> 0f;
            };
            if (c > 0f) {
                sumC += c;
                sum += c * inputs[i];
            }
        }
        return sumC > 0f ? sum / sumC : 0f;
    }
}
