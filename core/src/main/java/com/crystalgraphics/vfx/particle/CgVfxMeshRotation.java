package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgKeyframes;

import java.util.ArrayList;
import java.util.List;

/**
 * How an emitter's mesh particles are turned: Cascade's {@code MeshRotation}, {@code MeshRotationRate},
 * {@code MeshRotationRateOverLife} and {@code MeshRotationRateMultiplyLife}, the spec's {@code initial_mesh_orientation}
 * and {@code mesh_rotation_rate}. Each particle's rotator is its start, rolled from its seed between two ends, plus its
 * rate integrated over its age; drawn as Unreal's {@code RotatorToQuat} of it, through Unreal's axes
 * ({@code CgUnits.rotatorToHost}).
 *
 * <pre>{@code
 * // turned at random about every axis, spinning about its yaw a turn a second
 * float t = (float) (2 * Math.PI);
 * int[] perChannel = {0, 1, 2}, once = {0, 0, 0};
 * new CgVfxMeshRotation(new float[3], new float[]{t, t, t}, perChannel, new float[]{0f, 0f, t}, new float[]{0f, 0f, t}, once, false);
 *
 * // the same spin slowing to nothing over its life: Cascade's MeshRotationRateMultiplyLife
 * CgKeyframes one = CgKeyframes.constant(1f), slowing = CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.LINEAR).build();
 * spinning.over(new CgVfxMeshRotation.Step(true, new CgKeyframes[]{one, one, slowing}));
 * }</pre>
 *
 * <ul>
 *   <li>Rotators are Unreal's (roll, pitch, yaw), radians, about its x, y and z, whatever the asset's axes: Cascade sums
 *       them as Euler angles and draws {@code FQuat::MakeFromEuler} of the sum.</li>
 *   <li>Each axis takes one of three rolls ({@code CgVfxLifeRoll.rolls}): all the first for one roll mixing all three,
 *       its own per channel, a locked pair one between them.</li>
 *   <li>Its rate each step is the rolled rate, then each step of {@code steps} in order by the particle's life:
 *       Cascade resets the rate to its base every tick and runs its update modules on it
 *       ({@code ParticleEmitterInstances.cpp:3301}, {@code ParticleModules.cpp:1619-1742}).</li>
 *   <li>An emitter without one draws its meshes turned at random by its seed, as it always has.</li>
 * </ul>
 *
 * @param from           the start's low end
 * @param to             its high end
 * @param rolls          which roll each axis of the start takes
 * @param rateFrom       the rate's low end, radians a second
 * @param rateTo         its high end
 * @param rateRolls      which roll each axis of the rate takes
 * @param inheritParent  whether it turns with its instance as well ({@code bInheritParent})
 * @param steps          what its rate becomes over life, in order; empty for the rolled rate alone
 */
public record CgVfxMeshRotation(float[] from, float[] to, int[] rolls, float[] rateFrom, float[] rateTo,
                                int[] rateRolls, boolean inheritParent, List<Step> steps) {

    /** Unreal's default: unturned, still. */
    public static final CgVfxMeshRotation NONE = new CgVfxMeshRotation(new float[3], new float[3], new int[3], new float[3],
            new float[3], new int[3], false);

    /**
     * A change to the rate by the particle's life, per rotator axis: added, radians a second ({@code OverLife}), or
     * multiplying it ({@code MultiplyLife}).
     */
    public record Step(boolean scale, CgKeyframes[] axes) {
    }

    public CgVfxMeshRotation {
        steps = List.copyOf(steps);
    }

    public CgVfxMeshRotation(float[] from, float[] to, int[] rolls, float[] rateFrom, float[] rateTo, int[] rateRolls,
                             boolean inheritParent) {
        this(from, to, rolls, rateFrom, rateTo, rateRolls, inheritParent, List.of());
    }

    /** The same start with a rate of {@code from} to {@code to}, its steps kept. */
    public CgVfxMeshRotation rate(float[] from, float[] to, int[] rolls) {
        return new CgVfxMeshRotation(this.from, this.to, this.rolls, from, to, rolls, inheritParent, steps);
    }

    /** The same with {@code step} after its others. */
    public CgVfxMeshRotation over(Step step) {
        List<Step> more = new ArrayList<>(steps);
        more.add(step);
        return new CgVfxMeshRotation(from, to, rolls, rateFrom, rateTo, rateRolls, inheritParent, more);
    }

    /** {@code rolls} as Look reads them from a vec4's w: {@code 1 + x + 3y + 9z}, each axis's roll. */
    public static float rollCode(int[] rolls) {
        return 1 + rolls[0] + 3 * rolls[1] + 9 * rolls[2];
    }

    /** Whether any step scales the rate: a rate rolled after one would scale apart from the one before it. */
    public boolean scaled() {
        for (Step s : steps) if (s.scale) return true;
        return false;
    }

    /**
     * Its rate's integrals over life at {@code texels} even points from 0 to 1, a vec4 each: the rolled rate's
     * multiplier into {@code scale}, the steps' own rate into {@code added}. Its rotation at life {@code t} is then
     * {@code life * (roll * scale(t) + added(t))}.
     */
    public void integrals(float[] scale, int scaleAt, float[] added, int addedAt, int texels) {
        // Trapezoids at eight points a texel, so a curve's keys between texels still count.
        int sub = 8;
        float dt = 1f / ((texels - 1) * sub);
        for (int c = 0; c < 3; c++) {
            float mPrev = multiplier(c, 0f), aPrev = added(c, 0f), mSum = 0f, aSum = 0f;
            for (int i = 0; i < texels; i++) {
                for (int k = 1; i > 0 && k <= sub; k++) {
                    float t = (i - 1 + k / (float) sub) / (texels - 1), m = multiplier(c, t), a = added(c, t);
                    mSum += 0.5f * (m + mPrev) * dt;
                    aSum += 0.5f * (a + aPrev) * dt;
                    mPrev = m;
                    aPrev = a;
                }
                scale[scaleAt + 4 * i + c] = mSum;
                added[addedAt + 4 * i + c] = aSum;
            }
        }
    }

    /** What the rolled rate on axis {@code c} is multiplied by at life {@code t}. */
    private float multiplier(int c, float t) {
        float m = 1f;
        for (Step s : steps) if (s.scale) m *= s.axes[c].at(t);
        return m;
    }

    /** What the steps add to axis {@code c}'s rate at life {@code t}, each scaled by the steps after it. */
    private float added(int c, float t) {
        float a = 0f;
        for (Step s : steps) a = s.scale ? a * s.axes[c].at(t) : a + s.axes[c].at(t);
        return a;
    }
}
