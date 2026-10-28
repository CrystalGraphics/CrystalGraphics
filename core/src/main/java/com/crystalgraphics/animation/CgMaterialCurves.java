package com.crystalgraphics.animation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * An instance's material curves this frame: each playing clip's curves that drive a material, summed by the clip's
 * weight in the pose, then set on the instance's materials. Unreal's material curve map
 * ({@code FAnimInstanceProxy::UpdateCurvesToEvaluationContext}) and its application
 * ({@code UpdateCurvesPostEvaluation}): a curve no clip keys this frame puts its parameter back to the material's
 * default. Owned by one {@link CgAnimInstance}; allocates only when it first meets a curve's name.
 */
final class CgMaterialCurves {

    private final Map<String, Integer> index = new HashMap<>();
    private String[] names = new String[0];
    private float[] values = new float[0];
    private boolean[] keyed = new boolean[0], wasKeyed = new boolean[0], layered = new boolean[0];
    private int count;

    /** Before a pose's clips are added: nothing keyed yet. */
    void begin() {
        boolean[] swap = wasKeyed;
        wasKeyed = keyed;
        keyed = swap;
        Arrays.fill(keyed, 0, count, false);
        Arrays.fill(values, 0, count, 0f);
    }

    /** {@code clip}'s material curves at {@code time}, weighted as its pose is. */
    void add(CgAnimSequence clip, float time, float weight) {
        for (int c = 0; c < clip.curveCount(); c++) {
            if (clip.curveDrives(c) != CgAnimSequence.DRIVES_MATERIAL) continue;
            int i = slot(clip.curveName(c));
            values[i] += clip.curveAt(c, time) * weight;
            keyed[i] = true;
        }
    }

    /** Before a layer's clips are added: the curves it keys replace the body's ({@code CurveBlendOption MaxWeight}). */
    void beginLayer() {
        Arrays.fill(layered, 0, count, false);
    }

    /** A layer's clip's material curves: the first a layer keys in a frame drops the body's value of that curve. */
    void addLayer(CgAnimSequence clip, float time, float weight) {
        for (int c = 0; c < clip.curveCount(); c++) {
            if (clip.curveDrives(c) != CgAnimSequence.DRIVES_MATERIAL) continue;
            int i = slot(clip.curveName(c));
            if (!layered[i]) {
                layered[i] = true;
                values[i] = 0f;
            }
            values[i] += clip.curveAt(c, time) * weight;
            keyed[i] = true;
        }
    }

    /** Sets each keyed curve's value on {@code instance}'s materials, and puts back what no clip keys any more. */
    void apply(CgAnimInstance instance) {
        for (int i = 0; i < count; i++) {
            if (keyed[i]) instance.scalar(names[i], values[i]);
            else if (wasKeyed[i]) instance.resetScalar(names[i]);
        }
    }

    private int slot(String name) {
        Integer at = index.get(name);
        if (at != null) return at;
        if (count == names.length) {
            int n = Math.max(4, count * 2);
            names = Arrays.copyOf(names, n);
            values = Arrays.copyOf(values, n);
            keyed = Arrays.copyOf(keyed, n);
            wasKeyed = Arrays.copyOf(wasKeyed, n);
            layered = Arrays.copyOf(layered, n);
        }
        names[count] = name;
        index.put(name, count);
        return count++;
    }
}
