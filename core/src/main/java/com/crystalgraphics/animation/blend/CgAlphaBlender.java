package com.crystalgraphics.animation.blend;

import java.util.Objects;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;

/**
 * A value blended from where it is to where it is wanted over a time, along a curve: Unreal's {@code FAlphaBlend}
 * (AlphaBlend.cpp), what a montage's weight rides. Retarget it any time; it carries on from the value it holds.
 *
 * <pre>{@code
 * CgAlphaBlender w = new CgAlphaBlender(0f);           // at 0, nothing to blend
 * w.blendTime(0.25f);
 * w.valueRange(w.value(), 1f);                         // up to 1 over a quarter second
 * w.update(dt);                                        // each frame
 * w.desired(0f);                                       // later: back down from wherever it got to
 * }</pre>
 *
 * <ul>
 *   <li>A new range or time takes effect at the next {@link #update}, as Unreal's deferred resets do: the value holds
 *       until then.</li>
 *   <li>A time of 0 jumps to the desired value at that update.</li>
 *   <li>The curve maps the linear alpha, as {@code AlphaToBlendOption}; {@link CgAlphaBlend}'s options are curves.</li>
 * </ul>
 */
public final class CgAlphaBlender {

    private CgEasing curve = CgEasings.LINEAR;
    private float blendTime, begin, desired = 1f, alpha, value, remaining;
    private boolean resetAlpha, resetTime;

    /** {@code FAlphaBlend(NewBlendTime)}: from 0 to 1, at 0 where {@code blendTime} is above 0, else at 1. */
    public CgAlphaBlender(float blendTime) {
        this.blendTime = Math.max(blendTime, 0f);
        if (this.blendTime <= 0f) {
            alpha(1f);
            remaining = 0f;
        } else {
            alpha(0f);
            remaining = this.blendTime * Math.abs(1f - alpha);
        }
    }

    /** {@code SetBlendOption}: the curve from alpha to blend. */
    public CgAlphaBlender curve(CgEasing curve) {
        this.curve = Objects.requireNonNull(curve, "curve");
        return this;
    }

    public CgEasing curve() {
        return curve;
    }

    /** {@code SetBlendTime}: the blend restarts its time at the next update. */
    public CgAlphaBlender blendTime(float seconds) {
        blendTime = Math.max(seconds, 0f);
        resetTime = true;
        return this;
    }

    public float blendTime() {
        return blendTime;
    }

    /** {@code SetValueRange}: blends from {@code begin} to {@code desired}, its alpha found again from its value. */
    public CgAlphaBlender valueRange(float begin, float desired) {
        this.begin = begin;
        this.desired = desired;
        resetAlpha = true;
        return this;
    }

    /** {@code SetDesiredValue}: from the value it holds to {@code desired}. */
    public CgAlphaBlender desired(float desired) {
        return valueRange(value, desired);
    }

    public float desired() {
        return desired;
    }

    /** {@code GetBlendedValue}. */
    public float value() {
        return value;
    }

    /** {@code GetAlpha}: the linear alpha. */
    public float alpha() {
        return alpha;
    }

    /** {@code GetBlendTimeRemaining}. */
    public float remaining() {
        return remaining;
    }

    /** {@code IsComplete}: at the desired value. */
    public boolean complete() {
        return begin + (desired - begin) * (float) curve.ease(1.0) == value;
    }

    /** {@code Update}: on by {@code seconds}; the time past the blend's end, else 0. */
    public float update(float seconds) {
        if (resetAlpha) {
            if (begin == desired) {
                alpha(1f);
            } else {
                alpha((value - begin) / (desired - begin));
            }
            resetAlpha = false;
        }
        if (resetTime) {
            if (blendTime <= 0f) {
                remaining = 0f;
                alpha(1f);
            } else {
                remaining = blendTime * Math.abs(1f - alpha);
            }
            resetTime = false;
        }
        if (complete()) return 0f;
        if (remaining > seconds) {
            float lerp = alpha + (1f - alpha) / remaining * seconds;
            remaining -= seconds;
            alpha(lerp);
            return 0f;
        }
        float overshoot = seconds - remaining;
        remaining = 0f;
        alpha(1f);
        return overshoot;
    }

    /** {@code SetAlpha}: the value at linear {@code a}. */
    private void alpha(float a) {
        alpha = Math.max(0f, Math.min(1f, a));
        value = begin + (desired - begin) * (float) curve.ease(alpha);
    }
}
