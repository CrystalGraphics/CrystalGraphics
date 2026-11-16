package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRootMotionParams;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A forward run turned to run sideways: the legs and the root turned toward the direction the character moves, the
 * spine turned back so the chest still faces where it faces. One forward loop then covers every direction off it,
 * where a strafe needs a clip for each.
 *
 * <pre>{@code
 * // GRAPH: turned from where the clips' root motion goes toward where the character really moves
 * CgAnimNode turned = CgOrientationWarpingNode.of(run, "ik_foot_root").spine("spine_01", "spine_02", "spine_03")
 *         .ikFeet("ik_foot_l", "ik_foot_r").locomotionAngle(MOVE_ANGLE);    // degrees about +y off the facing
 * CgAnimNode legs = CgLegIkNode.of(turned).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
 * }</pre>
 *
 * <pre>{@code
 * // MANUAL: the angle given outright
 * CgOrientationWarpingNode.of(run, "ik_foot_root").spine(...).ikFeet(...).manual(TURN)
 * }</pre>
 *
 * <ul>
 *   <li>The angle is about the skeleton's +y, from its facing (+z): positive turns toward +x.</li>
 *   <li>{@code GRAPH} needs root motion from everything and turns it too, so the character moves where its feet go;
 *       below {@link #minRootMotionSpeed} its target is 0.</li>
 *   <li>The root takes {@code distribution} of the angle (half by default); the spine turns that much back, shared
 *       evenly down each chain; the IK foot root takes the rest, the IK feet keeping their own facing.</li>
 *   <li>It eases toward its target at {@code interpSpeed} (10 a second), never past {@code maxCorrection} degrees.</li>
 * </ul>
 */
public final class CgOrientationWarpingNode extends CgAnimNode {

    /** Reads {@code kind: orientation_warping}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgOrientationWarpingNode n = of(CgSkeletalControlNode.source(c, at, m), c.string(at, m, "ik_foot_root"))
                .alpha(CgSkeletalControlNode.alpha(c, at, m)).spine(strings(at, m, "spine_bones")).ikFeet(strings(at, m, "ik_foot_bones"));
        CgWarpingMode mode = CgSkeletalControlNode.choice(c, at, m, "mode", CgWarpingMode.class, CgWarpingMode.GRAPH);
        n = mode == CgWarpingMode.MANUAL ? n.manual(CgWarpInput.read(c, at, m, "orientation_angle", 0f))
                : n.locomotionAngle(CgWarpInput.read(c, at, m, "locomotion_angle", 0f));
        return n.distribution(CgSkeletalControlNode.number(c, at, m, "distribution", 0.5f))
                .interp(CgSkeletalControlNode.number(c, at, m, "interp_speed", 10f), CgSkeletalControlNode.number(c, at, m, "max_correction", 180f))
                .minRootMotionSpeed(CgSkeletalControlNode.number(c, at, m, "min_root_motion_speed", 0.1f))
                .flipBeyond(CgSkeletalControlNode.number(c, at, m, "flip_beyond", 90f));
    };

    private static List<String> strings(String at, Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof List<?> l) || l.isEmpty()) throw new CgAssetException(at + "." + key + ": none");
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof String s)) throw new CgAssetException(at + "." + key + ": bone names");
            out.add(s);
        }
        return out;
    }

    private final CgAnimNode source;
    private final CgControlAlpha alpha;
    private final String ikFootRoot;
    private final List<String> spine, ikFeet;
    private final CgWarpingMode mode;
    private final CgWarpInput angle;
    private final float distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond;

    private CgOrientationWarpingNode(CgAnimNode source, CgControlAlpha alpha, String ikFootRoot, List<String> spine, List<String> ikFeet,
                                     CgWarpingMode mode, CgWarpInput angle, float distribution, float interpSpeed, float maxCorrection,
                                     float minRootMotionSpeed, float flipBeyond) {
        this.source = Objects.requireNonNull(source, "source");
        this.alpha = Objects.requireNonNull(alpha, "alpha");
        this.ikFootRoot = Objects.requireNonNull(ikFootRoot, "ikFootRoot");
        this.spine = List.copyOf(spine);
        this.ikFeet = List.copyOf(ikFeet);
        this.mode = mode;
        this.angle = angle;
        this.distribution = distribution;
        this.interpSpeed = interpSpeed;
        this.maxCorrection = maxCorrection;
        this.minRootMotionSpeed = minRootMotionSpeed;
        this.flipBeyond = flipBeyond;
    }

    /** Orientation warping over {@code source}, {@code GRAPH} toward angle 0. */
    public static CgOrientationWarpingNode of(CgAnimNode source, String ikFootRoot) {
        return new CgOrientationWarpingNode(source, CgControlAlpha.FULL, ikFootRoot, List.of(), List.of(), CgWarpingMode.GRAPH,
                CgWarpInput.of(0f), 0.5f, 10f, 180f, 0.1f, 90f);
    }

    private CgOrientationWarpingNode with(CgControlAlpha alpha, List<String> spine, List<String> ikFeet, CgWarpingMode mode, CgWarpInput angle,
                                          float distribution, float interpSpeed, float maxCorrection, float minRootMotionSpeed, float flipBeyond) {
        return new CgOrientationWarpingNode(source, alpha, ikFootRoot, spine, ikFeet, mode, angle, distribution, interpSpeed, maxCorrection,
                minRootMotionSpeed, flipBeyond);
    }

    public CgOrientationWarpingNode alpha(CgControlAlpha alpha) {
        return with(alpha, spine, ikFeet, mode, angle, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** The spine bones turned back: chains of them share the turn evenly, a parent before its child. */
    public CgOrientationWarpingNode spine(String... bones) {
        return spine(List.of(bones));
    }

    public CgOrientationWarpingNode spine(List<String> bones) {
        return with(alpha, bones, ikFeet, mode, angle, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** The IK feet under the IK foot root: they keep their facing as it turns. */
    public CgOrientationWarpingNode ikFeet(String... bones) {
        return ikFeet(List.of(bones));
    }

    public CgOrientationWarpingNode ikFeet(List<String> bones) {
        return with(alpha, spine, bones, mode, angle, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** {@code GRAPH}: where the character moves, degrees about +y off its facing. */
    public CgOrientationWarpingNode locomotionAngle(CgAnimVariable<Float> degrees) {
        return locomotionAngle(CgWarpInput.of(degrees));
    }

    public CgOrientationWarpingNode locomotionAngle(CgWarpInput degrees) {
        return with(alpha, spine, ikFeet, CgWarpingMode.GRAPH, degrees, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** {@code MANUAL}: the angle to turn the legs by, degrees about +y. */
    public CgOrientationWarpingNode manual(CgAnimVariable<Float> degrees) {
        return manual(CgWarpInput.of(degrees));
    }

    public CgOrientationWarpingNode manual(CgWarpInput degrees) {
        return with(alpha, spine, ikFeet, CgWarpingMode.MANUAL, degrees, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** How much of the turn the root takes, 0 to 1 (the spine turns as much back); the IK foot root takes the rest. */
    public CgOrientationWarpingNode distribution(float alpha) {
        return with(this.alpha, spine, ikFeet, mode, angle, alpha, interpSpeed, maxCorrection, minRootMotionSpeed, flipBeyond);
    }

    /** The ease toward the target, a second (0 for at once), and the most it turns at all, degrees. */
    public CgOrientationWarpingNode interp(float speed, float maxCorrectionDegrees) {
        return with(alpha, spine, ikFeet, mode, angle, distribution, speed, maxCorrectionDegrees, minRootMotionSpeed, flipBeyond);
    }

    /** {@code GRAPH}: below this root motion speed, metres a second, the target is 0. */
    public CgOrientationWarpingNode minRootMotionSpeed(float metresPerSecond) {
        return with(alpha, spine, ikFeet, mode, angle, distribution, interpSpeed, maxCorrection, metresPerSecond, flipBeyond);
    }

    /**
     * {@code GRAPH}: a target past this many degrees turns the other way, taking the clip for a backward one (90 by
     * default; 0 for never). Characters with one forward loop for every direction set 0: at a strafe of 90 degrees the
     * clip's own drift tips the target either side and the legs would flip from frame to frame.
     */
    public CgOrientationWarpingNode flipBeyond(float degrees) {
        return with(alpha, spine, ikFeet, mode, angle, distribution, interpSpeed, maxCorrection, minRootMotionSpeed, degrees);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        alpha.reads(each);
        angle.reads(each);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(source);
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime input = g.runtime(source);
        CgSkeleton s = g.skeleton();
        int footRoot = CgSkeletalControlNode.bone(s, ikFootRoot, this);
        int[] spineBones = new int[spine.size()], feet = new int[ikFeet.size()];
        for (int i = 0; i < spineBones.length; i++) spineBones[i] = CgSkeletalControlNode.bone(s, spine.get(i), this);
        for (int i = 0; i < feet.length; i++) feet[i] = CgSkeletalControlNode.bone(s, ikFeet.get(i), this);
        Arrays.sort(spineBones);
        float[] weights = weights(s, spineBones);
        CgControlAlpha.State state = alpha.state();
        return new Runtime() {
            final Vector3f dir = new Vector3f(), forward = new Vector3f(), t = new Vector3f(), lastDir = new Vector3f();
            final Quaternionf q = new Quaternionf(), r = new Quaternionf(), parent = new Quaternionf(), inverse = new Quaternionf(),
                    lastRotation = new Quaternionf();
            final Matrix4f m = new Matrix4f();
            float actual, weight, counter;
            boolean first = true;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                input.initialize(g);
                state.reset();
                actual = 0f;
                counter = 0f;
                first = true;
                lastDir.zero();
                lastRotation.identity();
            }

            @Override
            public void update(CgAnimGraphInstance g, float w) {
                input.update(g, w);
                weight = state.apply(g, g.delta());
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                input.evaluate(g, out);
                float dt = g.delta(), target;
                float limit = (float) Math.toRadians(maxCorrection);
                if (mode == CgWarpingMode.GRAPH) {
                    CgRootMotionParams motion = g.rootMotion();
                    if (!motion.hasRootMotion()) return;
                    float a = (float) Math.toRadians(wrap(angle.get(g)));
                    forward.set((float) Math.sin(a), 0f, (float) Math.cos(a));
                    motion.get(m).getTranslation(t);
                    m.getNormalizedRotation(r);
                    t.y = 0f;
                    float speed = dt > 0f ? t.length() / dt : 0f;
                    if (speed < minRootMotionSpeed) {
                        target = 0f;
                    } else {
                        dir.set(t).normalize();
                        target = signedAngle(dir, forward);
                        // a hair's margin: a target of exactly the threshold, rounded over it, must not flip
                        if (flipBeyond > 0f && Math.abs(Math.toDegrees(target)) > flipBeyond + 1e-3f) {
                            target = unwind(target + (float) Math.PI);
                            dir.negate();
                        }
                        // the clip's own turn since last frame is not fought by the ease
                        if (lastDir.lengthSquared() > 1e-12f) {
                            lastRotation.transformInverse(t.set(lastDir));
                            float turned = signedAngle(dir, t);
                            if (Math.abs(turned) < (float) Math.toRadians(45f)) {
                                counter += turned;
                                float step = CgStrideWarpingNode.interpTo(0f, counter, dt, 45f);
                                actual = unwind(actual + step);
                                counter -= step;
                            }
                        }
                        lastDir.set(dir);
                        lastRotation.set(r);
                        // the character moves where the turned feet go
                        motion.get(m).getTranslation(t);
                        q.rotationY(target).transform(t);
                        m.setTranslation(t);
                        motion.set(m);
                    }
                } else {
                    target = (float) Math.toRadians(wrap(angle.get(g)));
                }
                if (interpSpeed > 0f && !first) {
                    float smooth = CgStrideWarpingNode.interpTo(actual, target, dt, interpSpeed);
                    actual = Math.max(actual - limit, Math.min(actual + limit, smooth));
                } else {
                    actual = target;
                }
                first = false;
                actual = Math.max(-limit, Math.min(limit, actual));
                float turn = actual * weight;
                if (!Float.isFinite(turn) || Math.abs(turn) < 1e-6f) return;

                float rootTurn = turn * distribution;
                out.resolve();
                turnBone(out, 0, rootTurn);
                for (int i = 0; i < spineBones.length; i++) turnBone(out, spineBones[i], -turn * distribution * weights[i]);
                float footTurn = turn * (1f - distribution);
                if (Math.abs(footTurn) > 1e-6f) {
                    turnBone(out, footRoot, footTurn);
                    for (int foot : feet) turnBone(out, foot, -footTurn);
                }
            }

            /** Bone {@code b} turned {@code radians} about the skeleton's +y in its own place: its local rotation changed. */
            private void turnBone(CgAnimPose pose, int b, float radians) {
                int p = s.parent(b);
                q.rotationY(radians);
                pose.rotation(b, r);
                if (p >= 0) {
                    pose.component(p, m).getNormalizedRotation(parent);
                    // local' = P⁻¹ · Q · P · local
                    parent.normalize().conjugate(inverse).mul(q).mul(parent, q);
                }
                q.mul(r, r).normalize();
                pose.translation(b, t);
                Vector3f sc = pose.scale(b, dir);
                pose.set(b, t.x, t.y, t.z, r.x, r.y, r.z, r.w, sc.x, sc.y, sc.z);
                pose.resolve();
            }
        };
    }

    /** Each spine bone's share of the turn: a chain splits what its parents in the list have not taken. */
    private static float[] weights(CgSkeleton s, int[] bones) {
        float[] w = new float[bones.length];
        for (int i = bones.length - 1; i >= 0; i--) {
            if (w[i] != 0f) continue;
            List<Integer> update = new ArrayList<>();
            update.add(i);
            float existing = 0f;
            for (int j = i - 1; j >= 0; j--) {
                if (!below(s, bones[i], bones[j])) continue;
                if (w[j] > 0f) existing += w[j];
                else update.add(j);
            }
            float share = (1f - existing) / update.size();
            for (int k : update) w[k] = share;
        }
        return w;
    }

    private static boolean below(CgSkeleton s, int bone, int ancestor) {
        for (int b = s.parent(bone); b >= 0; b = s.parent(b)) if (b == ancestor) return true;
        return false;
    }

    /** The signed angle about +y from {@code from} to {@code to}, both unit and flat. */
    static float signedAngle(Vector3f from, Vector3f to) {
        float cross = from.z * to.x - from.x * to.z, dot = from.x * to.x + from.z * to.z;
        return (float) Math.atan2(cross, dot);
    }

    static float unwind(float radians) {
        while (radians > Math.PI) radians -= (float) (2 * Math.PI);
        while (radians < -Math.PI) radians += (float) (2 * Math.PI);
        return radians;
    }

    private static float wrap(float degrees) {
        degrees %= 360f;
        if (degrees > 180f) degrees -= 360f;
        if (degrees < -180f) degrees += 360f;
        return degrees;
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("orientation_warping");
        out.node("source", source);
        if (!alpha.full()) out.put("alpha", alpha.tree());
        out.put("ik_foot_root", ikFootRoot).put("spine_bones", spine).put("ik_foot_bones", ikFeet);
        if (mode == CgWarpingMode.MANUAL) {
            out.put("mode", "manual");
            angle.write(out, "orientation_angle", 0f);
        } else {
            angle.write(out, "locomotion_angle", 0f);
        }
        if (distribution != 0.5f) out.put("distribution", distribution);
        if (interpSpeed != 10f) out.put("interp_speed", interpSpeed);
        if (maxCorrection != 180f) out.put("max_correction", maxCorrection);
        if (minRootMotionSpeed != 0.1f) out.put("min_root_motion_speed", minRootMotionSpeed);
        if (flipBeyond != 90f) out.put("flip_beyond", flipBeyond);
    }

    @Override
    public String toString() {
        return "orientation_warping(" + spine.size() + " spine bones)";
    }
}
