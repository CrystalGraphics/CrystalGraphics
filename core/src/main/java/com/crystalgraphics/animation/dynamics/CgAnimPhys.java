package com.crystalgraphics.animation.dynamics;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A small rigid-body solver for bones: bodies with mass and inertia, linear and angular limits solved as impulses, and
 * springs. It behaves as the AnimPhysics solver Unreal's AnimDynamics runs on, which Unreal adapted from Stan Melax's
 * Sandbox physics (github.com/melax/sandbox, © 2014, MIT; see {@code THIRD-PARTY.md}). Positions are the simulation
 * space's; momentum, impulses and torque its units. {@code CgAnimDynamicsNode} drives it for a skeleton.
 *
 * <pre>{@code
 * CgAnimPhys phys = new CgAnimPhys(64, 32);                     // room for 64 linear, 32 angular limits a step
 * CgAnimPhys.Body[] bodies = {CgAnimPhys.box(0.05f, 0.2f, 0.05f, new Vector3f(0f, 1.9f, 0f))};
 * Vector3f zero = new Vector3f(), down = new Vector3f(0f, -1f, 0f);
 * // each step: this step's limits, then the update
 * phys.begin();
 * phys.constrainPositionNailed(dt, null, new Vector3f(0f, 2f, 0f), bodies[0], new Vector3f(0f, 0.1f, 0f));
 * phys.update(dt, bodies, 1, new CgAnimPhys.Spring[0], 0, down, zero, zero, zero, zero, 4, 1);
 * }</pre>
 *
 * <ul>
 *   <li>Limits are rebuilt every step from the pools: {@link #begin()} empties them; nothing is allocated after.</li>
 *   <li>A null body is the world, fixed.</li>
 * </ul>
 */
public final class CgAnimPhys {

    /** {@code AnimPhysicsConstants}. */
    public static final float JOINT_BIAS = 0.3f, LINEAR_DAMPING = 0.7f, ANGULAR_DAMPING = 0.7f, SPRING_LINEAR = 12f, SPRING_ANGULAR = 4f;
    /** {@code UPhysicsSettings::DefaultGravityZ}, 980 cm/s², in metres. */
    public static final float GRAVITY = 9.8f;
    /** The simulation-space forces' weights: {@code p.AnimDynamics.SimSpace*Alpha}. */
    public static final float CORIOLIS_ALPHA = 0.5f, CENTRIFUGAL_ALPHA = 1f, EULER_ALPHA = 1f;

    /** {@code AnimPhysTwistAxis}. */
    public enum Axis { X, Y, Z }

    /** {@code AnimPhysCollisionType}. */
    public enum Collision { COM, CUSTOM_SPHERE, INNER_SPHERE, OUTER_SPHERE }

    /** One rigid body: {@code FAnimPhysRigidBody}. A box, its centre of mass at its middle. */
    public static final class Body {
        public final Vector3f position = new Vector3f(), linearMomentum = new Vector3f(), angularMomentum = new Vector3f();
        public final Quaternionf orientation = new Quaternionf();
        public float mass = 1f, inverseMass = 1f;
        public final Matrix3f inverseTensorWithoutMass = new Matrix3f(), inverseWorldTensor = new Matrix3f(), inertia = new Matrix3f();
        public final Vector3f nextPosition = new Vector3f(), previousPosition = new Vector3f(), startPosition = new Vector3f();
        public final Quaternionf nextOrientation = new Quaternionf(), previousOrientation = new Quaternionf(), startOrientation = new Quaternionf();
        public boolean linearDampingOverridden, angularDampingOverridden, gravityOverridden;
        public float linearDamping, angularDamping, gravityScale = 1f;
        public final Vector3f gravityOverride = new Vector3f();
        public Collision collision = Collision.COM;
        public float sphereRadius;
        /** The box's half extents, for collision radii. */
        public final Vector3f halfExtents = new Vector3f();
        private final Vector3f spin = new Vector3f();

        /** {@code Spin()}: angular velocity. */
        public Vector3f spin() {
            return inverseWorldTensor.transform(spin.set(angularMomentum));
        }

        /** {@code GetPose() * p}: a body-space point in the simulation's space. */
        public Vector3f transform(Vector3fc p, Vector3f out) {
            return orientation.transform(out.set(p)).add(position);
        }
    }

    /**
     * A box body of {@code extents} at {@code position}: {@code FAnimPhysShape::MakeBox} and the body's inertia, a solid box's
     * (the solver's tetrahedral sum gives exactly this for a box about its centre).
     */
    public static Body box(float ex, float ey, float ez, Vector3fc position) {
        Body b = new Body();
        if (ex * ex + ey * ey + ez * ez <= 1e-8f) ex = ey = ez = 1f;
        b.halfExtents.set(ex / 2f, ey / 2f, ez / 2f);
        // CalculateInertia over unit density, normalised by volume: (y2 + z2) / 12 and so on
        b.inertia.zero();
        b.inertia.m00 = (ey * ey + ez * ez) / 12f;
        b.inertia.m11 = (ex * ex + ez * ez) / 12f;
        b.inertia.m22 = (ex * ex + ey * ey) / 12f;
        b.inertia.invert(b.inverseTensorWithoutMass);
        b.inverseWorldTensor.set(b.inverseTensorWithoutMass).scale(b.inverseMass);
        b.position.set(position);
        b.startPosition.set(position);
        b.previousPosition.set(position);
        b.nextPosition.set(position);
        return b;
    }

    /** {@code ScaleRigidBodyMass}. */
    public static void scaleMass(Body b, float scale) {
        b.mass *= scale;
        b.linearMomentum.mul(scale);
        b.inverseMass *= 1f / scale;
        b.angularMomentum.mul(scale);
        b.inverseWorldTensor.scale(1f / scale);
        b.inertia.scale(scale);
    }

    // ── limits, pooled ──

    private static final class Linear {
        Body b0, b1;
        final Vector3f first = new Vector3f(), second = new Vector3f(), normal = new Vector3f(), w0 = new Vector3f(), w1 = new Vector3f();
        float targetSpeed, targetSpeedWithoutBias, minForce, maxForce, sumImpulses, inverseInertiaImpulse;
    }

    private static final class Angular {
        Body b0, b1;
        final Vector3f axis = new Vector3f();
        float torque, targetSpin, minTorque, maxTorque, spinToTorque;
    }

    private final Linear[] linear;
    private final Angular[] angular;
    private int linearCount, angularCount;
    private final Vector3f t0 = new Vector3f(), t1 = new Vector3f(), t2 = new Vector3f(), t3 = new Vector3f(), t4 = new Vector3f();
    private final Quaternionf q0 = new Quaternionf(), q1 = new Quaternionf(), q2 = new Quaternionf(), q3 = new Quaternionf(), q4 = new Quaternionf();
    private final Matrix3f m0 = new Matrix3f(), m1 = new Matrix3f();

    /** A solver with room for {@code linearLimits} and {@code angularLimits} a step. */
    public CgAnimPhys(int linearLimits, int angularLimits) {
        linear = new Linear[linearLimits];
        angular = new Angular[angularLimits];
        for (int i = 0; i < linear.length; i++) linear[i] = new Linear();
        for (int i = 0; i < angular.length; i++) angular[i] = new Angular();
    }

    /** Empties the limits: before a step's constraints are added. */
    public void begin() {
        linearCount = 0;
        angularCount = 0;
    }

    private void addLinear(Body b0, Body b1, Vector3fc first, Vector3fc second, float nx, float ny, float nz, float target,
                           float targetWithoutBias, float minForce, float maxForce) {
        if (linearCount == linear.length) throw new IllegalStateException("more than " + linear.length + " linear limits a step");
        Linear l = linear[linearCount++];
        l.b0 = b0;
        l.b1 = b1;
        l.first.set(first);
        l.second.set(second);
        l.normal.set(nx, ny, nz);
        l.targetSpeed = target;
        l.targetSpeedWithoutBias = targetWithoutBias;
        l.minForce = Math.min(minForce, maxForce);
        l.maxForce = Math.max(minForce, maxForce);
        l.sumImpulses = 0f;
        // UpdateCachedData
        if (b0 != null) b0.orientation.transform(l.w0.set(first)); else l.w0.set(first);
        if (b1 != null) b1.orientation.transform(l.w1.set(second)); else l.w1.set(second);
        float k = 0f;
        if (b0 != null) k += b0.inverseMass + b0.inverseWorldTensor.transform(t0.set(l.w0).cross(l.normal)).cross(l.w0).dot(l.normal);
        if (b1 != null) k += b1.inverseMass + b1.inverseWorldTensor.transform(t0.set(l.w1).cross(l.normal)).cross(l.w1).dot(l.normal);
        l.inverseInertiaImpulse = 1f / k;
    }

    private void addAngular(Body b0, Body b1, float ax, float ay, float az, float targetSpin, float minTorque, float maxTorque) {
        if (angularCount == angular.length) throw new IllegalStateException("more than " + angular.length + " angular limits a step");
        Angular a = angular[angularCount++];
        a.b0 = b0;
        a.b1 = b1;
        a.axis.set(ax, ay, az);
        a.torque = 0f;
        a.targetSpin = targetSpin;
        a.minTorque = minTorque;
        a.maxTorque = maxTorque;
        float k = 0f;
        if (b0 != null) k += a.axis.dot(b0.inverseWorldTensor.transform(t0.set(a.axis)));
        if (b1 != null) k += a.axis.dot(b1.inverseWorldTensor.transform(t0.set(a.axis)));
        a.spinToTorque = 1f / k;
    }

    private void iterLinear(Linear l, float dt) {
        Vector3f v0 = l.b0 != null ? l.b0.spin().cross(l.w0, t1).fma(l.b0.inverseMass, l.b0.linearMomentum) : t1.zero();
        Vector3f v1 = l.b1 != null ? l.b1.spin().cross(l.w1, t2).fma(l.b1.inverseMass, l.b1.linearMomentum) : t2.zero();
        float delta = v1.sub(v0).dot(l.normal);
        float impulse = (-l.targetSpeed - delta) * l.inverseInertiaImpulse;
        impulse = Math.min(l.maxForce * dt - l.sumImpulses, impulse);
        impulse = Math.max(l.minForce * dt - l.sumImpulses, impulse);
        if (l.b0 != null) applyImpulse(l.b0, l.w0, t3.set(l.normal).mul(-impulse));
        if (l.b1 != null) applyImpulse(l.b1, l.w1, t3.set(l.normal).mul(impulse));
        l.sumImpulses += impulse;
    }

    private void iterAngular(Angular a, float dt) {
        if (a.targetSpin == -Float.MAX_VALUE) return;
        float current = (a.b1 != null ? a.b1.spin().dot(a.axis) : 0f) - (a.b0 != null ? a.b0.spin().dot(a.axis) : 0f);
        float delta = (a.targetSpin - current) * a.spinToTorque;
        delta = Math.min(delta, a.maxTorque * dt - a.torque);
        delta = Math.max(delta, a.minTorque * dt - a.torque);
        if (a.b0 != null) a.b0.angularMomentum.fma(-delta, a.axis);
        if (a.b1 != null) a.b1.angularMomentum.fma(delta, a.axis);
        a.torque += delta;
    }

    /** {@code ApplyImpulse}. */
    static void applyImpulse(Body b, Vector3fc at, Vector3fc impulse) {
        b.linearMomentum.add(impulse);
        b.angularMomentum.add(at.y() * impulse.z() - at.z() * impulse.y(), at.z() * impulse.x() - at.x() * impulse.z(),
                at.x() * impulse.y() - at.y() * impulse.x());
    }

    // ── constraints ──

    /** {@code ConstrainAlongDirection}: their separation along {@code axis} kept within {@code min}..{@code max}. */
    public void constrainAlongDirection(float dt, Body b0, Vector3fc p0, Body b1, Vector3fc p1, Vector3fc axis, float min, float max) {
        Vector3f w0 = b0 != null ? b0.transform(p0, t0) : t0.set(p0);
        Vector3f w1 = b1 != null ? b1.transform(p1, t1) : t1.set(p1);
        float distance = t2.set(w1).sub(w0).dot(axis);
        if (Math.abs(min - max) < 1e-8f) {
            addLinear(b0, b1, p0, p1, axis.x(), axis.y(), axis.z(), distance / dt, distance / dt, -Float.MAX_VALUE, Float.MAX_VALUE);
        } else {
            float s0 = (distance - min) / dt, s1 = (distance - max) / dt;
            addLinear(b0, b1, p0, p1, axis.x(), axis.y(), axis.z(), s0, s0, 0f, Float.MAX_VALUE);
            addLinear(b0, b1, p0, p1, axis.x(), axis.y(), axis.z(), s1, s1, -Float.MAX_VALUE, 0f);
        }
    }

    /** {@code ConstrainPositionNailed}: the two points held together. */
    public void constrainPositionNailed(float dt, Body b0, Vector3fc p0, Body b1, Vector3fc p1) {
        Vector3f w0 = b0 != null ? b0.transform(p0, t0) : t0.set(p0);
        Vector3f w1 = b1 != null ? b1.transform(p1, t1) : t1.set(p1);
        Vector3f speeds = t2.set(w1).sub(w0).div(dt);
        float sx = speeds.x, sy = speeds.y, sz = speeds.z;
        addLinear(b0, b1, p0, p1, 1, 0, 0, sx, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
        addLinear(b0, b1, p0, p1, 0, 1, 0, sy, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
        addLinear(b0, b1, p0, p1, 0, 0, 1, sz, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /** {@code ConstrainPositionPrismatic}: the second point kept within a box in the frame {@code prism} turns. */
    public void constrainPositionPrismatic(float dt, Body b0, Vector3fc p0, Body b1, Vector3fc p1, Quaternionf prism, Vector3fc min, Vector3fc max) {
        Vector3f w0 = b0 != null ? b0.transform(p0, t0) : t0.set(p0);
        Vector3f w1 = b1 != null ? b1.transform(p1, t1) : t1.set(p1);
        Vector3f local = prism.transformInverse(t2.set(w1).sub(w0));
        Vector3f target = t3.set(local);
        for (int c = 0; c < 3; c++) {
            if (local.get(c) < min.get(c)) target.setComponent(c, min.get(c));
            else if (local.get(c) > max.get(c)) target.setComponent(c, max.get(c));
        }
        prism.transform(target).add(w0);
        if (target.distanceSquared(w1) <= 1e-8f) return;
        Vector3f speeds = t4.set(w1).sub(target).div(dt);
        float sx = speeds.x, sy = speeds.y, sz = speeds.z;
        if (Math.abs(sx) > 1e-8f) addLinear(b0, b1, p0, p1, 1, 0, 0, sx, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
        if (Math.abs(sy) > 1e-8f) addLinear(b0, b1, p0, p1, 0, 1, 0, sy, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
        if (Math.abs(sz) > 1e-8f) addLinear(b0, b1, p0, p1, 0, 0, 1, sz, 0f, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /**
     * {@code ConstrainAngularRange}: the second body's turn from the first's joint frame kept within Euler limits
     * (degrees), split into swing and twist about {@code twistAxis}.
     */
    public void constrainAngularRange(float dt, Body b0, Body b1, Quaternionf jointFrame, Axis twistAxis, Vector3fc minDeg, Vector3fc maxDeg, float bias) {
        Quaternionf frame0 = b0 != null ? b0.orientation.mul(jointFrame, q0) : q0.set(jointFrame);
        Quaternionf frame1 = b1 != null ? q1.set(b1.orientation) : q1.identity();
        Quaternionf rot = q2.set(frame0).conjugate().mul(frame1);
        Vector3f worldTwist = t0.zero(), rotTwist = t1;
        switch (twistAxis) {
            case X -> { worldTwist.set(1, 0, 0); rot.transform(rotTwist.set(1, 0, 0)); }
            case Y -> { worldTwist.set(0, 1, 0); rot.transform(rotTwist.set(0, 1, 0)); }
            case Z -> { worldTwist.set(0, 0, 1); rot.transform(rotTwist.set(0, 0, 1)); }
        }
        Quaternionf swing = findBetween(worldTwist, rotTwist, q3);
        Quaternionf twist = q4.set(swing).conjugate().mul(rot);
        if (swing.w < 0f) swing.set(-swing.x, -swing.y, -swing.z, -swing.w);
        if (twist.w < 0f) twist.set(-twist.x, -twist.y, -twist.z, -twist.w);
        float min0, max0, min1, max1, swing0, swing1, twistAmount;
        Vector3f axis0 = t2, axis1 = t3, frameTwist = t4;
        switch (twistAxis) {
            case X -> {
                min0 = minDeg.y(); max0 = maxDeg.y(); min1 = minDeg.z(); max1 = maxDeg.z();
                swing0 = swing.y; swing1 = swing.z; twistAmount = twist.x;
                frame1.transform(axis0.set(0, 1, 0)); frame1.transform(axis1.set(0, 0, 1)); frame1.transform(frameTwist.set(1, 0, 0));
            }
            case Y -> {
                min0 = minDeg.x(); max0 = maxDeg.x(); min1 = minDeg.z(); max1 = maxDeg.z();
                swing0 = swing.x; swing1 = swing.z; twistAmount = twist.y;
                frame1.transform(axis0.set(1, 0, 0)); frame1.transform(axis1.set(0, 0, 1)); frame1.transform(frameTwist.set(0, 1, 0));
            }
            default -> {
                min0 = minDeg.x(); max0 = maxDeg.x(); min1 = minDeg.y(); max1 = maxDeg.y();
                swing0 = swing.x; swing1 = swing.y; twistAmount = twist.z;
                frame1.transform(axis0.set(1, 0, 0)); frame1.transform(axis1.set(0, 1, 0)); frame1.transform(frameTwist.set(0, 0, 1));
            }
        }
        min0 = (float) Math.toRadians(min0);
        max0 = (float) Math.toRadians(max0);
        min1 = (float) Math.toRadians(min1);
        max1 = (float) Math.toRadians(max1);
        float full = (float) Math.toRadians(360);
        if (min0 == max0) {
            addAngular(b0, b1, axis0.x, axis0.y, axis0.z, 2f * (-swing0 + (float) Math.sin(min0 / 2f)) / dt, -Float.MAX_VALUE, Float.MAX_VALUE);
        } else if (max0 - min0 < full) {
            addAngular(b0, b1, axis0.x, axis0.y, axis0.z, 2f * (-swing0 + (float) Math.sin(min0 / 2f)) / dt, 0f, Float.MAX_VALUE);
            addAngular(b0, b1, -axis0.x, -axis0.y, -axis0.z, 2f * (swing0 - (float) Math.sin(max0 / 2f)) / dt, 0f, Float.MAX_VALUE);
        }
        if (min1 == max1) {
            // Unreal's own: the bias and the angle itself, not its half-sine, on this axis
            addAngular(b0, b1, axis1.x, axis1.y, axis1.z, bias * 2f * (-swing1 + min1) / dt, -Float.MAX_VALUE, Float.MAX_VALUE);
        } else if (max1 - min1 < full) {
            addAngular(b0, b1, axis1.x, axis1.y, axis1.z, 2f * (-swing1 + (float) Math.sin(min1 / 2f)) / dt, 0f, Float.MAX_VALUE);
            addAngular(b0, b1, -axis1.x, -axis1.y, -axis1.z, 2f * (swing1 - (float) Math.sin(max1 / 2f)) / dt, 0f, Float.MAX_VALUE);
        }
        addAngular(b0, b1, frameTwist.x, frameTwist.y, frameTwist.z, bias * 2f * -twistAmount / dt, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /** {@code ConstrainConeAngle}: the second body's normal within {@code limitDeg} of the first's; 0 is a hinge. */
    public void constrainConeAngle(float dt, Body b0, Vector3fc n0, Body b1, Vector3fc n1, float limitDeg, float bias) {
        Vector3f w0 = b0 != null ? b0.orientation.transform(t0.set(n0)) : t0.set(n0);
        Vector3f w1 = b1.orientation.transform(t1.set(n1));
        Vector3f axis = w1.cross(w0, t2);
        if (axis.lengthSquared() > 1e-16f) axis.normalize(); else axis.zero();
        float angle = (float) Math.acos(Math.max(0f, Math.min(1f, w0.dot(b1.orientation.transform(t3.set(n1))))));
        float delta = angle - (float) Math.toRadians(limitDeg);
        float target = limitDeg == 0f ? bias : delta / dt;
        addAngular(b0, b1, axis.x, axis.y, axis.z, target, limitDeg > 0f ? 0f : -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /** {@code ConstrainPlanar}: the body kept on the positive side of a plane through {@code point} facing {@code normal}. */
    public void constrainPlanar(float dt, Body b, Vector3fc point, Vector3fc normal) {
        float distance = t0.set(b.position).sub(point).dot(normal);
        if (b.collision != Collision.COM) distance -= b.sphereRadius;
        float target = distance / dt;
        addLinear(null, b, point, t1.zero(), normal.x(), normal.y(), normal.z(), target, target, 0f, Float.MAX_VALUE);
    }

    /** {@code ConstrainSphericalInner}: the body kept inside a sphere. */
    public void constrainSphericalInner(float dt, Body b, Vector3fc centre, float radius) {
        Vector3f toBody = t0.set(b.position).sub(centre);
        float toLimit = toBody.length() - radius;
        if (b.collision != Collision.COM) toLimit += b.sphereRadius;
        if (toBody.lengthSquared() > 1e-16f) toBody.normalize(); else toBody.zero();
        float target = toLimit / dt;
        addLinear(null, b, centre, t1.zero(), toBody.x, toBody.y, toBody.z, target, target, -Float.MAX_VALUE, 0f);
    }

    /** {@code ConstrainSphericalOuter}: the body kept outside a sphere. */
    public void constrainSphericalOuter(float dt, Body b, Vector3fc centre, float radius) {
        Vector3f toBody = t0.set(b.position).sub(centre);
        float toLimit = toBody.length() - radius;
        if (b.collision != Collision.COM) toLimit -= b.sphereRadius;
        if (toBody.lengthSquared() > 1e-16f) toBody.normalize(); else toBody.zero();
        float target = toLimit / dt;
        addLinear(null, b, centre, t1.zero(), toBody.x, toBody.y, toBody.z, target, target, 0f, Float.MAX_VALUE);
    }

    /** {@code FQuat::FindBetween}: the shortest turn from {@code a} to {@code b}, any lengths. */
    static Quaternionf findBetween(Vector3fc a, Vector3fc b, Quaternionf out) {
        float norm = (float) Math.sqrt(a.lengthSquared() * b.lengthSquared());
        float w = norm + a.dot(b);
        if (w >= 1e-6f * norm) {
            out.set(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x(), w);
        } else if (Math.abs(a.x()) > Math.abs(a.z())) {
            out.set(-a.y(), a.x(), 0f, 0f);
        } else {
            out.set(0f, -a.z(), a.y(), 0f);
        }
        return out.normalize();
    }

    // ── the step ──

    /** A spring between two bodies' anchors: {@code FAnimPhysSpring}. */
    public static final class Spring {
        public Body body0, body1;
        public final Vector3f anchor0 = new Vector3f(), anchor1 = new Vector3f(), angularTarget = new Vector3f();
        public final Quaternionf targetOrientationOffset = new Quaternionf();
        public Axis angularTargetAxis = Axis.X;
        public float linearConstant = SPRING_LINEAR, angularConstant = SPRING_ANGULAR;
        public boolean applyLinear, applyAngular;
    }

    private void applySpring(Spring s, float dt) {
        if (s.applyLinear) {
            Vector3f p0 = s.body0 != null ? s.body0.transform(s.anchor0, t0) : t0.set(s.anchor0);
            Vector3f p1 = s.body1 != null ? s.body1.transform(s.anchor1, t1) : t1.set(s.anchor1);
            Vector3f o0 = s.body0 != null ? s.body0.orientation.transform(t2.set(s.anchor0)) : t2.set(s.anchor0);
            Vector3f o1 = s.body1 != null ? s.body1.orientation.transform(t3.set(s.anchor1)) : t3.set(s.anchor1);
            Vector3f d = p1.sub(p0);
            float force = -s.linearConstant * d.length() * dt;
            if (d.lengthSquared() > 1e-16f) d.normalize(); else d.zero();
            Vector3f impulse = d.mul(force);
            if (s.body0 != null) applyImpulse(s.body0, o0, t4.set(impulse).negate());
            if (s.body1 != null) applyImpulse(s.body1, o1, impulse);
        }
        if (s.applyAngular && s.body1 != null) {
            Vector3f axis = t0;
            switch (s.angularTargetAxis) {
                case X -> s.body1.orientation.transform(axis.set(1, 0, 0));
                case Y -> s.body1.orientation.transform(axis.set(0, 1, 0));
                case Z -> s.body1.orientation.transform(axis.set(0, 0, 1));
            }
            Vector3f target = s.targetOrientationOffset.transform(t1.set(s.angularTarget));
            Quaternionf to = findBetween(axis, target, q0);
            float angle = 2f * (float) Math.acos(Math.max(-1f, Math.min(1f, to.w)));
            float sin = (float) Math.sqrt(Math.max(0f, 1f - to.w * to.w));
            Vector3f rotAxis = sin > 1e-6f ? t2.set(to.x / sin, to.y / sin, to.z / sin) : t2.set(1, 0, 0);
            Vector3f added = rotAxis.mul(-s.angularConstant * angle * dt);
            if (s.body0 != null) s.body0.angularMomentum.add(added);
            s.body1.angularMomentum.sub(added);
        }
    }

    /**
     * {@code PhysicsUpdate}: one step of {@code dt} over the bodies, the limits added since {@link #begin()} and the
     * springs; the simulation space's own motion as external accelerations.
     */
    public void update(float dt, Body[] bodies, int bodyCount, Spring[] springs, int springCount, Vector3fc gravityDir,
                       Vector3fc externalForce, Vector3fc externalLinearAcc, Vector3fc externalAngularAcc,
                       Vector3fc externalAngularVelocity, int preIterations, int postIterations) {
        for (int i = 0; i < bodyCount; i++) initializeVelocity(dt, bodies[i], gravityDir);
        if (externalForce.lengthSquared() > 1e-8f) {
            for (int i = 0; i < bodyCount; i++) bodies[i].linearMomentum.fma(dt, externalForce);
        }
        if (externalLinearAcc.lengthSquared() > 1e-8f) {
            for (int i = 0; i < bodyCount; i++) {
                Body b = bodies[i];
                if (b.inverseMass > 1e-4f) b.linearMomentum.fma(dt / b.inverseMass, externalLinearAcc);
            }
        }
        if (externalAngularVelocity.lengthSquared() > 1e-8f || externalAngularAcc.lengthSquared() > 1e-8f) {
            for (int i = 0; i < bodyCount; i++) {
                Body b = bodies[i];
                Vector3f v = t0.set(b.linearMomentum).mul(b.inverseMass);
                Vector3f wxr = t1.set(externalAngularVelocity).cross(b.position);
                Vector3f wxwxr = t2.set(externalAngularVelocity).cross(wxr);
                Vector3f wxv = t3.set(externalAngularVelocity).cross(v);
                Vector3f axr = t4.set(externalAngularAcc).cross(b.position);
                // Coriolis, centrifugal and Euler accelerations, each by its weight
                Vector3f acc = wxv.mul(CORIOLIS_ALPHA * 2f).fma(CENTRIFUGAL_ALPHA, wxwxr).fma(EULER_ALPHA, axr);
                b.linearMomentum.fma(-dt / b.inverseMass, acc);
                b.angularMomentum.fma(-dt, b.inertia.transform(t0.set(externalAngularAcc)));
            }
        }
        for (int i = 0; i < springCount; i++) applySpring(springs[i], dt);
        for (int it = 0; it < preIterations; it++) {
            for (int i = 0; i < linearCount; i++) iterLinear(linear[i], dt);
            for (int i = 0; i < angularCount; i++) iterAngular(angular[i], dt);
        }
        for (int i = 0; i < bodyCount; i++) nextPose(dt, bodies[i]);
        for (int i = 0; i < linearCount; i++) linear[i].targetSpeed = linear[i].targetSpeedWithoutBias;
        for (int i = 0; i < angularCount; i++) {
            Angular a = angular[i];
            a.targetSpin = a.minTorque < 0 ? 0 : Math.min(a.targetSpin, 0f);
        }
        for (int it = 0; it < postIterations; it++) {
            for (int i = 0; i < linearCount; i++) iterLinear(linear[i], dt);
            for (int i = 0; i < angularCount; i++) iterAngular(angular[i], dt);
        }
        for (int i = 0; i < bodyCount; i++) updatePose(bodies[i]);
    }

    /** {@code InitializeBodyVelocity}: damping, gravity, and the world tensor for this step. */
    private void initializeVelocity(float dt, Body b, Vector3fc gravityDir) {
        float linearDamping = b.linearDampingOverridden ? b.linearDamping : LINEAR_DAMPING;
        float angularDamping = b.angularDampingOverridden ? b.angularDamping : ANGULAR_DAMPING;
        b.linearMomentum.mul((float) Math.pow(1f - linearDamping, dt));
        b.angularMomentum.mul((float) Math.pow(1f - angularDamping, dt));
        if (b.gravityOverridden) b.linearMomentum.fma(dt * b.mass, b.gravityOverride);
        else b.linearMomentum.fma(dt * GRAVITY * b.mass * b.gravityScale, gravityDir);
        worldTensor(b);
    }

    /** The inverse inertia in the simulation's space for this step. */
    private void worldTensor(Body b) {
        Matrix3f r = m0.rotation(b.orientation);
        Matrix3f inv = m1.set(b.inverseTensorWithoutMass).scale(b.inverseMass);
        // Unreal's row-vector R * I * R^T, which in column form is R^T * I * R
        b.inverseWorldTensor.set(r).transpose().mul(inv).mul(r);
    }

    /** {@code CalculateNextPose}, the orientation by Runge-Kutta. */
    private void nextPose(float dt, Body b) {
        b.nextPosition.set(b.position).fma(b.inverseMass * dt, b.linearMomentum);
        Matrix3f inv = m1.set(b.inverseTensorWithoutMass).scale(b.inverseMass);
        Quaternionf o = b.orientation;
        Quaternionf d1 = diffQ(o, inv, b.angularMomentum, q1);
        Quaternionf d2 = diffQ(add(o, d1, dt / 2f, q0), inv, b.angularMomentum, q2);
        Quaternionf d3 = diffQ(add(o, d2, dt / 2f, q0), inv, b.angularMomentum, q3);
        Quaternionf d4 = diffQ(add(o, d3, dt, q0), inv, b.angularMomentum, q4);
        b.nextOrientation.set(o.x + d1.x * dt / 6f + d2.x * dt / 3f + d3.x * dt / 3f + d4.x * dt / 6f,
                o.y + d1.y * dt / 6f + d2.y * dt / 3f + d3.y * dt / 3f + d4.y * dt / 6f,
                o.z + d1.z * dt / 6f + d2.z * dt / 3f + d3.z * dt / 3f + d4.z * dt / 6f,
                o.w + d1.w * dt / 6f + d2.w * dt / 3f + d3.w * dt / 3f + d4.w * dt / 6f).normalize();
    }

    private static Quaternionf add(Quaternionf a, Quaternionf d, float s, Quaternionf out) {
        return out.set(a.x + d.x * s, a.y + d.y * s, a.z + d.z * s, a.w + d.w * s);
    }

    private final Quaternionf dq = new Quaternionf(), spinQ = new Quaternionf();
    private final Matrix3f dm = new Matrix3f(), dr = new Matrix3f();
    private final Vector3f dv = new Vector3f();

    /** {@code DiffQ}: the orientation's rate under its angular momentum. */
    private Quaternionf diffQ(Quaternionf orient, Matrix3f inverseTensor, Vector3f angularMomentum, Quaternionf out) {
        Quaternionf n = dq.set(orient).normalize();
        Matrix3f r = dr.rotation(n);
        Matrix3f applied = dm.set(r).transpose().mul(inverseTensor).mul(r);
        Vector3f half = applied.transform(dv.set(angularMomentum)).mul(0.5f);
        return spinQ.set(half.x, half.y, half.z, 0f).mul(n, out);
    }

    /** {@code UpdatePose}. */
    private void updatePose(Body b) {
        b.previousPosition.set(b.position);
        b.previousOrientation.set(b.orientation);
        b.position.set(b.nextPosition);
        b.orientation.set(b.nextOrientation);
        worldTensor(b);
    }
}
