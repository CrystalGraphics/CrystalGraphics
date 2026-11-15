package com.crystalgraphics.animation.ik;

import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRootMotionParams;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * The body let lag behind the entity: the skeleton's root kept where it stands in the world while the entity turns or
 * moves under it, then eased back. A character turning on the spot keeps its feet where they are and swings round after,
 * instead of snapping with the entity's yaw; a sudden stop or shove eases out. Turn in place is built on it.
 *
 * <pre>{@code
 * // turn in place: the body holds while the entity yaws, never more than 90 degrees off, and eases round in 0.2 s halves
 * CgAnimNode body = CgOffsetRootBoneNode.of(idle).rotation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.2f, 90f)
 *         .translation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.1f, 0.5f);
 * }</pre>
 *
 * <pre>{@code
 * // a turn clip consumes the offset: the body turns by the clip's root rotation while the entity's own is held off
 * CgOffsetRootBoneNode.of(turn).rotation(CgOffsetRootBoneNode.Mode.HOLD_AND_CONSUME, 0.2f, 120f)
 * }</pre>
 *
 * <ul>
 *   <li>It reads the instance's placement ({@code CgAnimInstance.placedBy}): an instance standing nowhere never lags.</li>
 *   <li>Root motion from everything moves the held root as the clips move it; without any, the
 *       clips move it not.</li>
 *   <li>A jump past {@code teleportDistance} (3 m) carries the offset along rather than easing across it.</li>
 *   <li>The root is bone 0; its scale is the input's.</li>
 * </ul>
 */
public final class CgOffsetRootBoneNode extends CgAnimNode {

    /** What the held root does each frame, for translation or rotation. */
    public enum Mode {
        /** Moves only by the clips' root motion: the entity's motion builds the offset up. */
        ACCUMULATE,
        /** As {@link #ACCUMULATE}, then eased back toward the entity over its half-life. */
        INTERPOLATE,
        /** Follows the entity, keeping the offset, and moves by the clips' root motion too. */
        HOLD_AND_CONSUME,
        /** As {@link #HOLD_AND_CONSUME}, the clips' motion taken only where it shrinks the offset. */
        HOLD_SHRINK_AND_CONSUME,
        /** Follows the entity, keeping the offset, the clips' motion ignored. */
        HOLD,
        /** Follows the entity and eases back toward it, the clips' motion ignored. */
        RELEASE;

        boolean consumes() {
            return this == ACCUMULATE || this == INTERPOLATE || this == HOLD_AND_CONSUME || this == HOLD_SHRINK_AND_CONSUME;
        }

        boolean follows() {
            return this != ACCUMULATE && this != INTERPOLATE;
        }

        boolean eases() {
            return this == INTERPOLATE || this == RELEASE;
        }
    }

    /** Reads {@code kind: offset_root_bone}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> of(CgSkeletalControlNode.source(c, at, m))
            .translation(CgSkeletalControlNode.choice(c, at, m, "translation_mode", Mode.class, Mode.INTERPOLATE),
                    CgSkeletalControlNode.number(c, at, m, "translation_half_life", 0.1f),
                    CgSkeletalControlNode.number(c, at, m, "max_translation_error", -1f))
            .rotation(CgSkeletalControlNode.choice(c, at, m, "rotation_mode", Mode.class, Mode.INTERPOLATE),
                    CgSkeletalControlNode.number(c, at, m, "rotation_half_life", 0.2f),
                    CgSkeletalControlNode.number(c, at, m, "max_rotation_error", -1f))
            .clampToVelocity(CgSkeletalControlNode.number(c, at, m, "translation_speed_ratio", 0f),
                    CgSkeletalControlNode.number(c, at, m, "rotation_speed_ratio", 0f))
            .onGround(!Boolean.FALSE.equals(m.get("on_ground")))
            .teleportDistance(CgSkeletalControlNode.number(c, at, m, "teleport_distance", 3f));

    private final CgAnimNode source;
    private final Mode translationMode, rotationMode;
    private final float translationHalfLife, rotationHalfLife, maxTranslationError, maxRotationError;
    private final float translationSpeedRatio, rotationSpeedRatio, teleportDistance;
    private final boolean onGround;

    private CgOffsetRootBoneNode(CgAnimNode source, Mode translationMode, Mode rotationMode, float translationHalfLife,
                                 float rotationHalfLife, float maxTranslationError, float maxRotationError, float translationSpeedRatio,
                                 float rotationSpeedRatio, boolean onGround, float teleportDistance) {
        this.source = Objects.requireNonNull(source, "source");
        this.translationMode = Objects.requireNonNull(translationMode, "translationMode");
        this.rotationMode = Objects.requireNonNull(rotationMode, "rotationMode");
        this.translationHalfLife = translationHalfLife;
        this.rotationHalfLife = rotationHalfLife;
        this.maxTranslationError = maxTranslationError;
        this.maxRotationError = maxRotationError;
        this.translationSpeedRatio = translationSpeedRatio;
        this.rotationSpeedRatio = rotationSpeedRatio;
        this.onGround = onGround;
        this.teleportDistance = teleportDistance;
    }

    /** Over {@code source}, both modes {@link Mode#INTERPOLATE} at half-lives of 0.1 s and 0.2 s, unbounded. */
    public static CgOffsetRootBoneNode of(CgAnimNode source) {
        return new CgOffsetRootBoneNode(source, Mode.INTERPOLATE, Mode.INTERPOLATE, 0.1f, 0.2f, -1f, -1f, 0f, 0f, true, 3f);
    }

    /** Translation's mode, its ease's half-life in seconds, and the most the root may stand off, metres (negative: no bound). */
    public CgOffsetRootBoneNode translation(Mode mode, float halfLife, float maxError) {
        return new CgOffsetRootBoneNode(source, mode, rotationMode, halfLife, rotationHalfLife, maxError, maxRotationError,
                translationSpeedRatio, rotationSpeedRatio, onGround, teleportDistance);
    }

    /** Rotation's mode, its ease's half-life in seconds, and the most the root may turn off, degrees (negative: no bound). */
    public CgOffsetRootBoneNode rotation(Mode mode, float halfLife, float maxErrorDegrees) {
        return new CgOffsetRootBoneNode(source, translationMode, mode, translationHalfLife, halfLife, maxTranslationError, maxErrorDegrees,
                translationSpeedRatio, rotationSpeedRatio, onGround, teleportDistance);
    }

    /**
     * The ease held to these fractions of the clips' own motion this frame (0: unheld), so the body never catches up
     * faster than it visibly steps or turns.
     */
    public CgOffsetRootBoneNode clampToVelocity(float translationRatio, float rotationRatio) {
        return new CgOffsetRootBoneNode(source, translationMode, rotationMode, translationHalfLife, rotationHalfLife, maxTranslationError,
                maxRotationError, translationRatio, rotationRatio, onGround, teleportDistance);
    }

    /** Whether the held root stays on the entity's ground plane (+y up). */
    public CgOffsetRootBoneNode onGround(boolean on) {
        return new CgOffsetRootBoneNode(source, translationMode, rotationMode, translationHalfLife, rotationHalfLife, maxTranslationError,
                maxRotationError, translationSpeedRatio, rotationSpeedRatio, on, teleportDistance);
    }

    /** A jump of the entity past this, metres, carries the offset along. */
    public CgOffsetRootBoneNode teleportDistance(float metres) {
        return new CgOffsetRootBoneNode(source, translationMode, rotationMode, translationHalfLife, rotationHalfLife, maxTranslationError,
                maxRotationError, translationSpeedRatio, rotationSpeedRatio, onGround, metres);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(source);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
    }

    /** A damper's share of the gap closed in {@code dt}: half every {@code halfLife} seconds. */
    static float damper(float halfLife, float dt) {
        halfLife = Math.max(halfLife, dt);
        return Math.max(0f, Math.min(1f, 1f - (float) Math.exp(-0.69314718f * dt / (halfLife + 1e-8f))));
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime input = g.runtime(source);
        return new Runtime() {
            final Vector3d simT = new Vector3d(), compT = new Vector3d(), lastT = new Vector3d(), d = new Vector3d(), e = new Vector3d();
            final Quaterniond simR = new Quaterniond(), compR = new Quaterniond(), lastR = new Quaterniond(), qd = new Quaterniond(),
                    qe = new Quaterniond(), qa = new Quaterniond(), identity = new Quaterniond();
            final Matrix4d place = new Matrix4d(), inverse = new Matrix4d();
            final Vector3d scale = new Vector3d();
            final Matrix4f m = new Matrix4f();
            final Vector3f t = new Vector3f(), sc = new Vector3f();
            final Quaternionf rf = new Quaternionf(), in = new Quaternionf();
            boolean first = true;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                input.initialize(g);
                first = true;
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                input.update(g, weight);
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                input.evaluate(g, out);
                float dt = g.delta();
                Matrix4dc placement = g.placement();
                lastT.set(compT);
                lastR.set(compR);
                place.set(placement);
                place.getTranslation(compT);
                place.getNormalizedRotation(compR);
                place.invertAffine(inverse);
                // the mesh's units in the world: its step and offset scale by them
                double unit = place.getScale(scale).x;
                if (first) {
                    simT.set(compT);
                    simR.set(compR);
                    lastT.set(compT);
                    lastR.set(compR);
                    first = false;
                }
                if (lastT.distance(compT) > teleportDistance) {
                    // carry the offset along: the held root keeps its place relative to the entity
                    lastR.conjugate(qd).transform(d.set(simT).sub(lastT));
                    compR.transform(d);
                    simT.set(compT).add(d);
                    compR.mul(lastR.conjugate(qe).mul(simR, qe), simR);
                    lastT.set(compT);
                    lastR.set(compR);
                }

                // this frame's clip motion, in the mesh's space
                CgRootMotionParams motion = g.rootMotion();
                boolean graph = motion.hasRootMotion();
                if (graph) motion.get(m);
                else m.identity();
                m.getTranslation(t);
                m.getNormalizedRotation(rf);
                d.zero();
                qd.identity();
                if (translationMode.consumes()) d.set(t.x, t.y, t.z);
                if (rotationMode.consumes()) qd.set(rf.x, rf.y, rf.z, rf.w);

                if (rotationMode.follows()) simR.premul(lastR.conjugate(qe).mul(compR));
                if (translationMode.follows()) simT.add(compT).sub(lastT);

                if (translationMode == Mode.HOLD_SHRINK_AND_CONSUME) {
                    simR.conjugate(qe).transform(e.set(simT).sub(compT));
                    if (e.lengthSquared() > 1e-12) {
                        e.normalize();
                        d.set(e).mul(d.dot(e));
                    } else {
                        d.zero();
                    }
                }
                if (rotationMode == Mode.HOLD_SHRINK_AND_CONSUME) {
                    double off = yaw(qe.set(compR).mul(simR.conjugate(qa))), turn = yaw(qd);
                    turn = off >= 0 ? Math.max(0, Math.min(off, turn)) : Math.max(off, Math.min(0, turn));
                    qd.rotationY(turn);
                }
                // the clip's step taken in the held root's frame
                simT.add(simR.transform(d.mul(unit)));
                simR.mul(qd).normalize();
                if (onGround) simT.y = compT.y;

                if (translationMode.eases()) {
                    e.set(compT).sub(simT).mul(damper(translationHalfLife, dt));
                    if (translationSpeedRatio > 0f) {
                        double max = translationSpeedRatio * t.length() * unit;
                        if (e.length() > max) e.normalize(max);
                    }
                    simT.add(e);
                }
                if (rotationMode.eases()) {
                    qe.set(compR).mul(simR.conjugate(qa)).normalize();
                    if (qe.w < 0) qe.set(-qe.x, -qe.y, -qe.z, -qe.w);
                    identity.identity().slerp(qe, damper(rotationHalfLife, dt), qe);
                    if (rotationSpeedRatio > 0f) {
                        double max = rotationSpeedRatio * 2 * Math.acos(Math.min(1, Math.abs(rf.w)));
                        double angle = 2 * Math.acos(Math.min(1, Math.abs(qe.w)));
                        if (angle > max) identity.identity().slerp(qe, max / angle, qe);
                    }
                    simR.premul(qe).normalize();
                }
                if (maxTranslationError >= 0f) {
                    e.set(compT).sub(simT);
                    if (e.length() > maxTranslationError) simT.set(compT).sub(e.normalize(maxTranslationError));
                }
                if (maxRotationError >= 0f) {
                    compR.conjugate(qe).mul(simR).normalize();
                    if (qe.w < 0) qe.set(-qe.x, -qe.y, -qe.z, -qe.w);
                    double angle = 2 * Math.acos(Math.min(1, qe.w)), max = Math.toRadians(maxRotationError);
                    if (angle > max) {
                        identity.identity().slerp(qe, max / angle, qe);
                        compR.mul(qe, simR).normalize();
                    }
                }

                // the root: the input's, offset by the held root seen from the entity
                out.translation(0, t);
                out.rotation(0, in);
                out.scale(0, sc);
                inverse.transformDirection(e.set(simT).sub(compT));
                compR.conjugate(qe).mul(simR);
                rf.set((float) qe.x, (float) qe.y, (float) qe.z, (float) qe.w).mul(in).normalize();
                out.set(0, t.x + (float) e.x, t.y + (float) e.y, t.z + (float) e.z, rf.x, rf.y, rf.z, rf.w, sc.x, sc.y, sc.z);
            }
        };
    }

    /** The turn about +y a quaternion makes. */
    private static double yaw(Quaterniond q) {
        return 2 * Math.atan2(q.y, q.w);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("offset_root_bone");
        out.node("source", source);
        if (translationMode != Mode.INTERPOLATE) out.put("translation_mode", CgSkeletalControlNode.id(translationMode));
        if (rotationMode != Mode.INTERPOLATE) out.put("rotation_mode", CgSkeletalControlNode.id(rotationMode));
        if (translationHalfLife != 0.1f) out.put("translation_half_life", translationHalfLife);
        if (rotationHalfLife != 0.2f) out.put("rotation_half_life", rotationHalfLife);
        if (maxTranslationError != -1f) out.put("max_translation_error", maxTranslationError);
        if (maxRotationError != -1f) out.put("max_rotation_error", maxRotationError);
        if (translationSpeedRatio != 0f) out.put("translation_speed_ratio", translationSpeedRatio);
        if (rotationSpeedRatio != 0f) out.put("rotation_speed_ratio", rotationSpeedRatio);
        if (!onGround) out.put("on_ground", false);
        if (teleportDistance != 3f) out.put("teleport_distance", teleportDistance);
    }

    @Override
    public String toString() {
        return "offset_root_bone(" + translationMode + ", " + rotationMode + ")";
    }
}
