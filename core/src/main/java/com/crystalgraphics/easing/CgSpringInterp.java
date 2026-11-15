package com.crystalgraphics.easing;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Springs that chase a target by stiffness and damping, remembering their speed between frames: Unreal's
 * {@code UKismetMathLibrary::FloatSpringInterp}, {@code VectorSpringInterp} and {@code QuaternionSpringInterp}
 * (KismetMathLibrary.cpp, over {@code GenericSpringInterp} and {@code FMath::SpringDamper}). Each state is one value's,
 * kept between frames, as Unreal's {@code F*SpringState}.
 *
 * <pre>{@code
 * private final CgSpringInterp.VectorSpring pelvis = new CgSpringInterp.VectorSpring();
 * pelvis.interp(offset, desired, 350f, 1f, dt, offset);                   // stiffness 350, critically damped
 *
 * private final CgSpringInterp.QuaternionSpring turn = new CgSpringInterp.QuaternionSpring();
 * turn.interp(current, target, 450f, 1f, dt, current);                    // in place
 * }</pre>
 *
 * <ul>
 *   <li>Damping is Unreal's critical damping ratio: 1 settles without overshoot, under 1 overshoots.</li>
 *   <li>{@code reset()} forgets the speed and the last target, as Unreal's {@code Reset}.</li>
 *   <li>Mass 1 and no target velocity unless asked, the values Unreal's animation nodes pass.</li>
 * </ul>
 */
public final class CgSpringInterp {

    private CgSpringInterp() {
    }

    /** {@code GenericSpringInterp}'s frequency: {@code sqrt(stiffness / mass) / 2pi}. */
    private static float frequency(float stiffness, float mass) {
        return (float) (Math.sqrt(stiffness / mass) / (2.0 * Math.PI));
    }

    /** {@code FFloatSpringState} and {@code FloatSpringInterp}. */
    public static final class FloatSpring {
        private final float[] state = new float[2];
        private float prevTarget;
        private boolean prevValid;

        public float interp(float current, float target, float stiffness, float damping, float dt) {
            return interp(current, target, stiffness, damping, dt, 1f, 0f);
        }

        public float interp(float current, float target, float stiffness, float damping, float dt, float mass, float targetVelocityAmount) {
            if (dt <= CgSmoothing.SMALL_NUMBER || Math.abs(mass) <= CgSmoothing.SMALL_NUMBER) return current;
            float targetRate = prevValid ? (target - prevTarget) * (targetVelocityAmount / dt) : 0f;
            state[0] = current;
            CgSmoothing.springDamperFrequency(state, target, targetRate, dt, frequency(stiffness, mass), damping);
            prevTarget = target;
            prevValid = true;
            return state[0];
        }

        public void reset() {
            state[1] = 0f;
            prevValid = false;
        }
    }

    /** {@code FVectorSpringState} and {@code VectorSpringInterp}: each axis its own spring, one damping case. */
    public static final class VectorSpring {
        private final FloatSpring x = new FloatSpring(), y = new FloatSpring(), z = new FloatSpring();

        /** {@code current} sprung toward {@code target}, into {@code out} (which may be {@code current}). */
        public Vector3f interp(Vector3fc current, Vector3fc target, float stiffness, float damping, float dt, Vector3f out) {
            return out.set(x.interp(current.x(), target.x(), stiffness, damping, dt),
                    y.interp(current.y(), target.y(), stiffness, damping, dt),
                    z.interp(current.z(), target.z(), stiffness, damping, dt));
        }

        public void reset() {
            x.reset();
            y.reset();
            z.reset();
        }
    }

    /**
     * {@code FQuaternionSpringState} and {@code QuaternionSpringInterp}: the quaternion's four components sprung, its
     * speed kept as an angular velocity, normalised after.
     */
    public static final class QuaternionSpring {
        private final float[] w = new float[2], x = new float[2], y = new float[2], z = new float[2];
        private final Vector3f angular = new Vector3f();
        private final Quaternionf velocity = new Quaternionf(), scratch = new Quaternionf();

        /** {@code current} sprung toward {@code target}, into {@code out} (which may be either). */
        public Quaternionf interp(Quaternionf current, Quaternionf target, float stiffness, float damping, float dt, Quaternionf out) {
            float tx = target.x, ty = target.y, tz = target.z, tw = target.w;
            if (tx * current.x + ty * current.y + tz * current.z + tw * current.w < 0f) {
                tx = -tx;
                ty = -ty;
                tz = -tz;
                tw = -tw;
            }
            float cx = current.x, cy = current.y, cz = current.z, cw = current.w;
            if (dt <= CgSmoothing.SMALL_NUMBER) return out.set(cx, cy, cz, cw);
            // 0.5 * Current * (AngularVelocity, 0)
            scratch.set(angular.x, angular.y, angular.z, 0f);
            velocity.set(cx, cy, cz, cw).mul(scratch);
            velocity.set(velocity.x * 0.5f, velocity.y * 0.5f, velocity.z * 0.5f, velocity.w * 0.5f);
            float f = frequency(stiffness, 1f);
            x[0] = cx; x[1] = velocity.x;
            y[0] = cy; y[1] = velocity.y;
            z[0] = cz; z[1] = velocity.z;
            w[0] = cw; w[1] = velocity.w;
            // no target velocity: Unreal's animation nodes pass TargetVelocityAmount 0
            CgSmoothing.springDamperFrequency(x, tx, 0f, dt, f, damping);
            CgSmoothing.springDamperFrequency(y, ty, 0f, dt, f, damping);
            CgSmoothing.springDamperFrequency(z, tz, 0f, dt, f, damping);
            CgSmoothing.springDamperFrequency(w, tw, 0f, dt, f, damping);
            out.set(x[0], y[0], z[0], w[0]).normalize();
            // Current.Inverse() * 2 * Velocity
            velocity.set(x[1] * 2f, y[1] * 2f, z[1] * 2f, w[1] * 2f);
            scratch.set(out).conjugate().mul(velocity);
            angular.set(scratch.x, scratch.y, scratch.z);
            return out;
        }

        public void reset() {
            angular.zero();
        }
    }
}
