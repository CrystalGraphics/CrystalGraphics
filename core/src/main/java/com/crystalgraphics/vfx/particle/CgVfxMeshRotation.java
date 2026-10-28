package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgKeyframes;
import javax.annotation.Nullable;

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
 *   <li>{@code toward} turns its mesh's axis toward its velocity at birth, the turn between them added to its start's
 *       rotator: Niagara's {@code OrientToAxis} with the vector bound to {@code Particles.Velocity}. A constant vector
 *       needs none: {@link #between} of it is added to both ends.</li>
 * </ul>
 *
 * <pre>{@code
 * // its +X (Unreal's) along its velocity at birth, rolled a turn about it as it is born
 * float t = (float) (2 * Math.PI);
 * new CgVfxMeshRotation(new float[3], new float[]{t, 0f, 0f}, new int[]{0, 1, 2}, new float[3], new float[3], new int[3],
 *         false, List.of(), new float[]{1f, 0f, 0f});
 * }</pre>
 *
 * @param from           the start's low end
 * @param to             its high end
 * @param rolls          which roll each axis of the start takes
 * @param rateFrom       the rate's low end, radians a second
 * @param rateTo         its high end
 * @param rateRolls      which roll each axis of the rate takes
 * @param inheritParent  whether it turns with its instance as well ({@code bInheritParent})
 * @param steps          what its rate becomes over life, in order; empty for the rolled rate alone
 * @param toward         the mesh axis turned toward its velocity at birth, Unreal's axes, a unit vector; null for none
 */
public record CgVfxMeshRotation(float[] from, float[] to, int[] rolls, float[] rateFrom, float[] rateTo,
                                int[] rateRolls, boolean inheritParent, List<Step> steps, @Nullable float[] toward) {

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
        this(from, to, rolls, rateFrom, rateTo, rateRolls, inheritParent, List.of(), null);
    }

    /** The same start with a rate of {@code from} to {@code to}, its steps kept. */
    public CgVfxMeshRotation rate(float[] from, float[] to, int[] rolls) {
        return new CgVfxMeshRotation(this.from, this.to, this.rolls, from, to, rolls, inheritParent, steps, toward);
    }

    /** The same with {@code step} after its others. */
    public CgVfxMeshRotation over(Step step) {
        List<Step> more = new ArrayList<>(steps);
        more.add(step);
        return new CgVfxMeshRotation(from, to, rolls, rateFrom, rateTo, rateRolls, inheritParent, more, toward);
    }

    /**
     * The rotator (roll, pitch, yaw, radians) turning {@code axis} onto {@code vector}, both in Unreal's axes: Unreal's
     * {@code FQuat::FindBetweenVectors} then {@code FQuat::Rotator} ({@code UnrealMath.cpp:1400-1452}, {@code :640-682}),
     * which Niagara adds to an oriented mesh's rotator. Opposite vectors turn about an axis square to {@code axis}; a
     * zero vector turns nothing.
     */
    public static float[] between(float[] axis, float[] vector) {
        float ax = axis[0], ay = axis[1], az = axis[2], bx = vector[0], by = vector[1], bz = vector[2];
        float norm = (float) Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        float w = norm + ax * bx + ay * by + az * bz, x, y, z;
        if (w >= 1e-6f * norm) {
            x = ay * bz - az * by;
            y = az * bx - ax * bz;
            z = ax * by - ay * bx;
        } else {
            // Opposite: about Y unless the axis lies nearest X, then about -X.
            w = 0f;
            boolean alongX = Math.abs(ax) > Math.abs(ay) && Math.abs(ax) > Math.abs(az);
            float cx = alongX ? 0f : -1f, cy = alongX ? 1f : 0f;
            x = -az * cy;
            y = az * cx;
            z = ax * cy - ay * cx;
        }
        float len = x * x + y * y + z * z + w * w;
        if (len < 1e-8f) return new float[3];
        float inv = (float) (1 / Math.sqrt(len));
        x *= inv;
        y *= inv;
        z *= inv;
        w *= inv;
        float singular = z * x - w * y, half = (float) (Math.PI / 2);
        if (singular < -0.4999995f) return new float[] {0f, -half, (float) (-2 * Math.atan2(x, w))};
        if (singular > 0.4999995f) return new float[] {0f, half, (float) (2 * Math.atan2(x, w))};
        return new float[] {(float) Math.atan2(-2 * (w * x + y * z), 1 - 2 * (x * x + y * y)), (float) Math.asin(2 * singular),
                (float) Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))};
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
