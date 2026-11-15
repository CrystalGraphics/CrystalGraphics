package com.crystalgraphics.animation.ik;

import java.util.List;
import java.util.Objects;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * One bone lagging behind where the animation puts it on a damped spring in the world: Unreal's Spring Controller
 * ({@code FAnimNode_SpringBone}, AnimNode_SpringBone.cpp). Stepped at 120 Hz in fixed steps, so a tail, an antenna or
 * a ponytail sways the same at any frame rate.
 *
 * <pre>{@code
 * // a ponytail's tip trailing the head's motion
 * CgSpringBoneNode.of(body, "ponytail_03").spring(50f, 4f)
 *
 * // an antenna that only swings sideways, turning its parent with it, never more than 10 cm off
 * CgSpringBoneNode.of(body, "antenna").translate(true, false, true).rotate(true, false, true).limit(0.1f)
 * }</pre>
 *
 * <ul>
 *   <li>Stiffness and damping are Unreal's (per second squared, per second); distances are the skeleton's units.</li>
 *   <li>Past {@link #errorReset} (2.56, Unreal's 256 cm) from its target the bone snaps back: a teleport.</li>
 *   <li>The owner's velocity, carried into each step, is zero unless {@link #ownerVelocity} sets it, as Unreal's is
 *       unless overridden.</li>
 * </ul>
 */
public final class CgSpringBoneNode extends CgSkeletalControlNode {

    /** Unreal's fixed step: 120 a second. */
    private static final float FIXED_STEP = 1f / 120f;

    /** Reads {@code kind: spring_bone}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgSpringBoneNode n = of(source(c, at, m), c.string(at, m, "bone")).alpha(alpha(c, at, m))
                .spring(number(c, at, m, "stiffness", 50f), number(c, at, m, "damping", 4f))
                .errorReset(number(c, at, m, "error_reset", 2.56f));
        boolean[] t = flags(at + ".translate", m.get("translate"), true), r = flags(at + ".rotate", m.get("rotate"), false);
        n = n.translate(t[0], t[1], t[2]).rotate(r[0], r[1], r[2]);
        if (m.get("max_displacement") != null) n = n.limit(number(c, at, m, "max_displacement", 0f));
        float[] v = CgControlAlpha.numbers(c, at + ".owner_velocity", m.get("owner_velocity"), 3);
        if (v != null) n = n.ownerVelocity(v[0], v[1], v[2]);
        return n;
    };

    private final String bone;
    private final float stiffness, damping, errorReset, maxDisplacement;
    private final boolean limit, tx, ty, tz, rx, ry, rz, overrideVelocity;
    private final Vector3f ownerVelocity;

    private CgSpringBoneNode(CgAnimNode source, CgControlAlpha alpha, String bone, float stiffness, float damping, float errorReset,
                             boolean limit, float maxDisplacement, boolean tx, boolean ty, boolean tz, boolean rx, boolean ry,
                             boolean rz, boolean overrideVelocity, Vector3f ownerVelocity) {
        super(source, alpha);
        this.bone = Objects.requireNonNull(bone, "bone");
        this.stiffness = stiffness;
        this.damping = damping;
        this.errorReset = errorReset;
        this.limit = limit;
        this.maxDisplacement = maxDisplacement;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.overrideVelocity = overrideVelocity;
        this.ownerVelocity = new Vector3f(ownerVelocity);
    }

    /** {@code bone} on Unreal's default spring: stiffness 50, damping 4, every axis moving, none turning. */
    public static CgSpringBoneNode of(CgAnimNode source, String bone) {
        return new CgSpringBoneNode(source, CgControlAlpha.FULL, bone, 50f, 4f, 2.56f, false, 0f, true, true, true, false,
                false, false, false, new Vector3f());
    }

    private CgSpringBoneNode with(CgControlAlpha alpha, float stiffness, float damping, float errorReset, boolean limit, float max,
                                  boolean tx, boolean ty, boolean tz, boolean rx, boolean ry, boolean rz, boolean overrideV, Vector3f v) {
        return new CgSpringBoneNode(source, alpha, bone, stiffness, damping, errorReset, limit, max, tx, ty, tz, rx, ry, rz, overrideV, v);
    }

    public CgSpringBoneNode alpha(CgControlAlpha alpha) {
        return with(alpha, stiffness, damping, errorReset, limit, maxDisplacement, tx, ty, tz, rx, ry, rz, overrideVelocity, ownerVelocity);
    }

    public CgSpringBoneNode spring(float stiffness, float damping) {
        return with(alpha, stiffness, damping, errorReset, limit, maxDisplacement, tx, ty, tz, rx, ry, rz, overrideVelocity, ownerVelocity);
    }

    /** How far from its target the bone may get before it snaps back. */
    public CgSpringBoneNode errorReset(float distance) {
        return with(alpha, stiffness, damping, distance, limit, maxDisplacement, tx, ty, tz, rx, ry, rz, overrideVelocity, ownerVelocity);
    }

    /** Never further than {@code maxDisplacement} from its target. */
    public CgSpringBoneNode limit(float maxDisplacement) {
        return with(alpha, stiffness, damping, errorReset, true, maxDisplacement, tx, ty, tz, rx, ry, rz, overrideVelocity, ownerVelocity);
    }

    /** Which world axes it lags on; the others follow the animation. */
    public CgSpringBoneNode translate(boolean x, boolean y, boolean z) {
        return with(alpha, stiffness, damping, errorReset, limit, maxDisplacement, x, y, z, rx, ry, rz, overrideVelocity, ownerVelocity);
    }

    /** Which Euler axes of its parent's turn toward it the bone takes. */
    public CgSpringBoneNode rotate(boolean x, boolean y, boolean z) {
        return with(alpha, stiffness, damping, errorReset, limit, maxDisplacement, tx, ty, tz, x, y, z, overrideVelocity, ownerVelocity);
    }

    /** The owner's velocity, blocks a second, carried into each step. */
    public CgSpringBoneNode ownerVelocity(float x, float y, float z) {
        return with(alpha, stiffness, damping, errorReset, limit, maxDisplacement, tx, ty, tz, rx, ry, rz, true, new Vector3f(x, y, z));
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int b = bone(g.skeleton(), bone, this);
        int parent = g.skeleton().parent(b);
        return new Control(1) {
            private final Vector3d location = new Vector3d(), velocity = new Vector3d(), target = new Vector3d(),
                    old = new Vector3d(), error = new Vector3d(), local = new Vector3d();
            private final Matrix4d world = new Matrix4d();
            private final Vector3f p = new Vector3f(), parentP = new Vector3f(), toTarget = new Vector3f(), toCurrent = new Vector3f(),
                    euler = new Vector3f(), t = new Vector3f(), s = new Vector3f();
            private final Quaternionf turn = new Quaternionf(), q = new Quaternionf();
            private float remaining;
            private boolean started;

            @Override
            protected void initialize(CgAnimGraphInstance g) {
                remaining = 0f;
                started = false;
            }

            @Override
            protected void update(CgAnimGraphInstance g, float dt) {
                remaining += dt;
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                Matrix4f m = out[0].set(pose.component(b));
                if (!tx && !ty && !tz) return 0;
                world.set(g.placement()).transformPosition(target.set(m.m30(), m.m31(), m.m32()));
                if (!started) {
                    // Unreal's first step starts at the world's origin and resets past its error threshold
                    location.set(target);
                    velocity.zero();
                    started = true;
                }
                while (remaining > FIXED_STEP) {
                    if (overrideVelocity) location.fma(FIXED_STEP, old.set(ownerVelocity));
                    if (target.distanceSquared(location) > (double) errorReset * errorReset) {
                        location.set(target);
                        velocity.zero();
                    }
                    error.set(target).sub(location);
                    // acceleration = stiffness * error - damping * velocity, scaled down past the step's damping cut-off
                    double ax = stiffness * error.x - damping * velocity.x, ay = stiffness * error.y - damping * velocity.y,
                            az = stiffness * error.z - damping * velocity.z;
                    double cutOff = 1.0 / FIXED_STEP, safety = damping > cutOff ? cutOff / damping : 1.0;
                    velocity.add(ax * FIXED_STEP * safety, ay * FIXED_STEP * safety, az * FIXED_STEP * safety);
                    double speed = velocity.length();
                    if (speed * FIXED_STEP > errorReset) velocity.mul(errorReset / (speed * FIXED_STEP));
                    old.set(location);
                    location.fma(FIXED_STEP, velocity);
                    if (!tx) location.x = target.x;
                    if (!ty) location.y = target.y;
                    if (!tz) location.z = target.z;
                    if (limit && location.distanceSquared(target) > (double) maxDisplacement * maxDisplacement) {
                        location.sub(target).normalize(maxDisplacement).add(target);
                    }
                    velocity.set(location).sub(old).div(FIXED_STEP);
                    remaining -= FIXED_STEP;
                }
                world.invertAffine().transformPosition(local.set(location));
                p.set((float) local.x, (float) local.y, (float) local.z);
                m.setTranslation(p);
                if ((rx || ry || rz) && parent >= 0) {
                    pose.component(parent).getTranslation(parentP);
                    world.set(g.placement()).invertAffine().transformPosition(local.set(target));
                    toTarget.set((float) local.x, (float) local.y, (float) local.z).sub(parentP);
                    toCurrent.set(p).sub(parentP);
                    if (toTarget.lengthSquared() > 1e-16f && toCurrent.lengthSquared() > 1e-16f) {
                        CgIkMath.findBetweenNormals(toTarget.normalize(), toCurrent.normalize(), turn);
                        CgIkMath.eulerZYX(turn, euler);
                        turn.rotationZYX(rz ? euler.z : 0f, ry ? euler.y : 0f, rx ? euler.x : 0f);
                        m.getTranslation(t);
                        m.getScale(s);
                        m.translationRotateScale(t, turn.mul(m.getNormalizedRotation(q), q).normalize(), s);
                    }
                }
                bones[0] = b;
                return 1;
            }
        };
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("spring_bone");
        writeBase(out);
        out.put("bone", bone);
        if (stiffness != 50f) out.put("stiffness", stiffness);
        if (damping != 4f) out.put("damping", damping);
        if (errorReset != 2.56f) out.put("error_reset", errorReset);
        if (limit) out.put("max_displacement", maxDisplacement);
        if (!tx || !ty || !tz) out.put("translate", List.of(tx, ty, tz));
        if (rx || ry || rz) out.put("rotate", List.of(rx, ry, rz));
        if (overrideVelocity) out.put("owner_velocity", List.of(ownerVelocity.x, ownerVelocity.y, ownerVelocity.z));
    }

    @Override
    public String toString() {
        return "spring_bone(" + bone + ")";
    }
}
