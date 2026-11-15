package com.crystalgraphics.animation.locomotion;

import com.crystalgraphics.animation.sync.CgAnimTickContext;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A start, stop or pivot played by distance rather than by time: the clip's time chosen so the ground its root covers
 * matches the ground the character covers, so a stop lands its last step where the character stops and a start never
 * slides off its first. Reads a clip's distance curve ({@link CgClipModifiers#distanceCurve}); a sequence evaluator
 * plays by it ({@code CgSequenceEvaluatorNode}).
 *
 * <pre>{@code
 * // a start: as far into the clip as the character has come this frame
 * t = CgDistanceMatching.timeAfterDistanceTravelled(start, t, travelledThisFrame, CgClipModifiers.DISTANCE, false);
 * // a stop: the clip's place for how far the character still has to go
 * t = CgDistanceMatching.timeAtDistance(stop, -distanceToStop, CgClipModifiers.DISTANCE);
 * // a loop: its rate for a speed
 * float rate = CgDistanceMatching.playRateToMatchSpeed(run, skeleton, speed, 0.8f, 1.2f);
 * }</pre>
 *
 * <ul>
 *   <li>A distance curve keys the distance to the clip's stop as a negative, rising to 0 there and on after it.</li>
 *   <li>A clip without the curve, or whose curve covers no distance, throws naming it: it is never played by time
 *       instead.</li>
 * </ul>
 */
public final class CgDistanceMatching {

    /** The step the travelled distance is walked in, seconds: 30 a second. */
    private static final float STEP = 1f / 30f;

    private CgDistanceMatching() {
    }

    /**
     * The clip's time {@code distance} along its curve, between the two keys around it, the keys rising. For a stop,
     * pass the distance still to go as a negative.
     *
     * @throws IllegalArgumentException where the clip has no such curve
     */
    public static float timeAtDistance(CgAnimSequence clip, float distance, String curve) {
        int c = curve(clip, curve);
        int keys = clip.frameCount();
        if (keys < 2) return 0f;
        int first = 1, count = keys - 2;
        while (count > 0) {
            int step = count / 2, middle = first + step;
            if (distance > clip.curveValue(c, middle)) {
                first = middle + 1;
                count -= step + 1;
            } else {
                count = step;
            }
        }
        float a = clip.curveValue(c, first - 1), b = clip.curveValue(c, first), diff = b - a;
        float alpha = Math.abs(diff) > 1e-8f ? (distance - a) / diff : 0f;
        return (float) (((first - 1) + alpha) / clip.frameRate());
    }

    /**
     * The clip's time once its root has covered {@code distance} more from {@code time}, its curve walked in 1/30 s
     * steps and the last one split; wraps where {@code looping}, else stops a step short of its end.
     *
     * @throws IllegalArgumentException where the clip has no such curve, or its curve covers no distance
     */
    public static float timeAfterDistanceTravelled(CgAnimSequence clip, float time, float distance, String curve, boolean looping) {
        int c = curve(clip, curve);
        float length = clip.duration();
        if (Math.abs(clip.curveValue(c, clip.frameCount() - 1) - clip.curveValue(c, 0)) < 1e-6f) {
            throw new IllegalArgumentException(clip.name() + ": its curve " + curve + " covers no distance");
        }
        float covered = 0f, walked = 0f, t = time;
        while (covered < distance && (looping || t + STEP < length)) {
            float step = clip.curveAt(c, t + STEP) - clip.curveAt(c, t);
            if (Math.abs(step) > 1e-8f) {
                if (covered + step < distance) {
                    t = CgAnimTickContext.advanceTime(looping, STEP, t, length);
                    covered += step;
                } else {
                    t = CgAnimTickContext.advanceTime(looping, (distance - covered) / step * STEP, t, length);
                    break;
                }
            } else {
                t += STEP;
            }
            walked += STEP;
            // a clip covering too little gives up rather than walking for ever
            if (walked >= length) break;
        }
        return t;
    }

    /**
     * The play rate at which the clip's root covers {@code speed} metres a second (its whole root motion over its
     * length), held within [{@code min}, {@code max}] where {@code 0 <= min < max}.
     *
     * @throws IllegalArgumentException where the clip has no length or covers no ground
     */
    public static float playRateToMatchSpeed(CgAnimSequence clip, CgSkeleton skeleton, float speed, float min, float max) {
        float length = clip.duration();
        if (!(length > 0f)) throw new IllegalArgumentException(clip.name() + " has no length");
        Vector3f t = clip.extractRootMotion(skeleton, 0f, length, false, new Matrix4f()).getTranslation(new Vector3f());
        float distance = (float) Math.sqrt(t.x * t.x + t.z * t.z);
        if (distance < 1e-6f) throw new IllegalArgumentException(clip.name() + " covers no ground");
        float rate = speed / (distance / length);
        return min >= 0f && min < max ? Math.max(min, Math.min(max, rate)) : rate;
    }

    static int curve(CgAnimSequence clip, String curve) {
        int c = clip.curve(curve);
        if (c < 0) throw new IllegalArgumentException(clip.name() + " has no distance curve " + curve);
        return c;
    }
}
