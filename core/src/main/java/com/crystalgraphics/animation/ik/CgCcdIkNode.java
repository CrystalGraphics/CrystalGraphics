package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.List;
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
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A chain reaching its tip for a point by turning one joint at a time: Unreal's CCD IK ({@code FAnimNode_CCDIK},
 * AnimNode_CCDIK.cpp, over {@code AnimationCore::SolveCCDIK}, CCDIK.cpp). Each pass turns every joint between the root
 * and the tip to point the tip at the target, each turn at most its joint's limit.
 *
 * <pre>{@code
 * // a tentacle curling toward a point, from its tip inward
 * CgCcdIkNode.of(body, "tentacle_01", "tentacle_08", CgIkTarget.world(GRAB_X, GRAB_Y, GRAB_Z))
 *
 * // a spine bending no more than 15 degrees a joint in all, from the root outward
 * CgCcdIkNode.of(body, "spine_01", "head", lookPoint).startFromTail(false).rotationLimits(true, 15f, 15f, 15f, 15f)
 * }</pre>
 *
 * <ul>
 *   <li>A joint's limit (degrees, 30 where unset, as Unreal's editor fills them) caps each pass's turn; with
 *       {@link #rotationLimits} enabled it caps the joint's whole turn over the passes.</li>
 *   <li>The root and the tip never turn: the joints between them do.</li>
 *   <li>The precision is a distance in the skeleton's units; 0.01, Unreal's centimetre in metres.</li>
 * </ul>
 */
public final class CgCcdIkNode extends CgSkeletalControlNode {

    /** Unreal's editor's limit for a joint it adds. */
    public static final float DEFAULT_LIMIT = 30f;

    /** Reads {@code kind: ccd_ik}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgCcdIkNode n = of(source(c, at, m), c.string(at, m, "root_bone"), c.string(at, m, "tip_bone"),
                CgIkTarget.read(c, at + ".effector", m.get("effector")))
                .alpha(alpha(c, at, m))
                .precision(number(c, at, m, "precision", 0.01f), (int) number(c, at, m, "max_iterations", 10))
                .startFromTail(!Boolean.FALSE.equals(m.get("start_from_tail")));
        Object limits = m.get("rotation_limits");
        float[] l = new float[0];
        if (limits != null) {
            if (!(limits instanceof List<?> list)) throw new CgAssetException(at + ".rotation_limits: a list of degrees");
            l = new float[list.size()];
            for (int i = 0; i < l.length; i++) l[i] = c.number(at + ".rotation_limits[" + i + "]", list.get(i));
        }
        return n.rotationLimits(Boolean.TRUE.equals(m.get("enable_rotation_limit")), l);
    };

    private final String rootBone, tipBone;
    private final CgIkTarget effector;
    private final float precision;
    private final int maxIterations;
    private final boolean startFromTail, limitsEnabled;
    private final float[] limits;

    private CgCcdIkNode(CgAnimNode source, CgControlAlpha alpha, String rootBone, String tipBone, CgIkTarget effector,
                        float precision, int maxIterations, boolean startFromTail, boolean limitsEnabled, float[] limits) {
        super(source, alpha);
        this.rootBone = Objects.requireNonNull(rootBone, "rootBone");
        this.tipBone = Objects.requireNonNull(tipBone, "tipBone");
        this.effector = Objects.requireNonNull(effector, "effector");
        if (!(precision > 0f)) throw new IllegalArgumentException("a precision above 0");
        this.precision = precision;
        this.maxIterations = maxIterations;
        this.startFromTail = startFromTail;
        this.limitsEnabled = limitsEnabled;
        this.limits = limits.clone();
    }

    public static CgCcdIkNode of(CgAnimNode source, String rootBone, String tipBone, CgIkTarget effector) {
        return new CgCcdIkNode(source, CgControlAlpha.FULL, rootBone, tipBone, effector, 0.01f, 10, true, false, new float[0]);
    }

    public CgCcdIkNode alpha(CgControlAlpha alpha) {
        return new CgCcdIkNode(source, alpha, rootBone, tipBone, effector, precision, maxIterations, startFromTail, limitsEnabled, limits);
    }

    /** How close the tip must come, and the most passes spent getting there. */
    public CgCcdIkNode precision(float precision, int maxIterations) {
        return new CgCcdIkNode(source, alpha, rootBone, tipBone, effector, precision, maxIterations, startFromTail, limitsEnabled, limits);
    }

    /** Each pass turns the joints from the tip inward (the default), or from the root outward. */
    public CgCcdIkNode startFromTail(boolean startFromTail) {
        return new CgCcdIkNode(source, alpha, rootBone, tipBone, effector, precision, maxIterations, startFromTail, limitsEnabled, limits);
    }

    /** Each link's limit in degrees, root first; a link past the list's end takes {@link #DEFAULT_LIMIT}. */
    public CgCcdIkNode rotationLimits(boolean enabled, float... degrees) {
        return new CgCcdIkNode(source, alpha, rootBone, tipBone, effector, precision, maxIterations, startFromTail, enabled, degrees);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int[] chain = chain(g.skeleton(), rootBone, tipBone, this);
        int effectorBone = effector.bone(g.skeleton(), this);
        int n = chain.length;
        float[] limitRadians = new float[n];
        for (int i = 0; i < n; i++) limitRadians[i] = (float) Math.toRadians(i < limits.length ? limits[i] : DEFAULT_LIMIT);
        return new Control(n) {
            private final int[] linkIndex = new int[n], riderOf = new int[n];
            private final Matrix4f[] transform = new Matrix4f[n], local = new Matrix4f[n];
            private final float[] angleDelta = new float[n];
            private final Matrix4f effectorTm = new Matrix4f(), scratch = new Matrix4f();
            private final Vector3f target = new Vector3f(), tipPos = new Vector3f(), at = new Vector3f(), toEnd = new Vector3f(),
                    toTarget = new Vector3f(), axis = new Vector3f(), a = new Vector3f(), b = new Vector3f(), t = new Vector3f(),
                    s = new Vector3f();
            private final Quaternionf q = new Quaternionf(), turn = new Quaternionf();

            {
                for (int i = 0; i < n; i++) {
                    transform[i] = new Matrix4f();
                    local[i] = new Matrix4f();
                }
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                effector.resolve(g, pose, effectorBone, effectorTm).getTranslation(target);
                int links = 0;
                for (int i = 0; i < n; i++) {
                    bones[i] = chain[i];
                    out[i].set(pose.component(chain[i]));
                    riderOf[i] = -1;
                    if (i > 0) {
                        out[i].getTranslation(a);
                        out[i - 1].getTranslation(b);
                        if (Math.abs(a.distance(b)) <= 1e-8f) {
                            riderOf[i] = links - 1;   // a zero-length bone takes its link's transform
                            continue;
                        }
                    }
                    linkIndex[links] = i;
                    transform[links].set(out[i]);
                    // the bone's own local, as the pose holds it
                    int parent = pose.skeleton().parent(chain[i]);
                    if (parent < 0) local[links].set(out[i]);
                    else pose.component(parent).invertAffine(local[links]).mulAffine(out[i]);
                    angleDelta[links] = 0f;
                    links++;
                }
                if (solve(links)) {
                    for (int l = 0; l < links; l++) out[linkIndex[l]].set(transform[l]);
                    for (int i = 1; i < n; i++) if (riderOf[i] >= 0) out[i].set(transform[riderOf[i]]);
                }
                return n;
            }

            /** {@code SolveCCDIK}: whether any link turned. */
            private boolean solve(int links) {
                if (links < 2) return false;
                int tip = links - 1;
                float distance = transform[tip].getTranslation(tipPos).distance(target);
                // moved is never reset between passes, as Unreal's bLocalUpdated: only a first pass that turns nothing stops early
                boolean updated = false, moved = false;
                int iteration = 0;
                while (distance > precision && iteration++ < maxIterations) {
                    if (startFromTail) {
                        for (int l = tip - 1; l > 0; l--) moved |= link(l, tip);
                    } else {
                        for (int l = 1; l < tip; l++) moved |= link(l, tip);
                    }
                    distance = transform[tip].getTranslation(tipPos).distance(target);
                    updated |= moved;
                    if (!moved) break;
                }
                return updated;
            }

            /** {@code UpdateChainLink}: link {@code l} turned to point the tip at the target. */
            private boolean link(int l, int tip) {
                transform[tip].getTranslation(tipPos);
                transform[l].getTranslation(at);
                toEnd.set(tipPos).sub(at);
                toTarget.set(target).sub(at);
                if (toEnd.lengthSquared() < 1e-16f || toTarget.lengthSquared() < 1e-16f) return false;
                toEnd.normalize();
                toTarget.normalize();
                float limit = limitRadians[l];   // by link, as Unreal indexes them
                float angle = Math.min((float) Math.acos(Math.max(-1f, Math.min(1f, toEnd.dot(toTarget)))), limit);
                boolean canRotate = Math.abs(angle) > 1e-8f && (!limitsEnabled || limit > angleDelta[l]);
                if (!canRotate) return false;
                if (limitsEnabled) {
                    if (limit < angleDelta[l] + angle) {
                        angle = limit - angleDelta[l];
                        if (angle <= 1e-8f) return false;
                    }
                    angleDelta[l] += angle;
                }
                toEnd.cross(toTarget, axis);
                if (axis.lengthSquared() <= 0f) return false;
                turn.fromAxisAngleRad(axis.normalize(), angle);
                Matrix4f m = transform[l];
                m.getTranslation(t);
                m.getNormalizedRotation(q);
                m.getScale(s);
                m.translationRotateScale(t, turn.mul(q, q).normalize(), s);
                if (l > 0) transform[l - 1].invertAffine(local[l]).mulAffine(m);
                // the links below ride it, each by its local
                for (int c = l + 1; c <= tip; c++) {
                    transform[c - 1].mulAffine(local[c], scratch);
                    scratch.getTranslation(t);
                    scratch.getNormalizedRotation(q);
                    scratch.getScale(s);
                    transform[c].translationRotateScale(t, q, s);
                }
                return true;
            }
        };
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        effector.reads(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("ccd_ik");
        writeBase(out);
        out.put("root_bone", rootBone).put("tip_bone", tipBone).put("effector", effector.tree());
        if (precision != 0.01f) out.put("precision", precision);
        if (maxIterations != 10) out.put("max_iterations", maxIterations);
        if (!startFromTail) out.put("start_from_tail", false);
        if (limitsEnabled) out.put("enable_rotation_limit", true);
        if (limits.length > 0) {
            List<Object> l = new ArrayList<>(limits.length);
            for (float f : limits) l.add(f);
            out.put("rotation_limits", l);
        }
    }

    @Override
    public String toString() {
        return "ccd_ik(" + rootBone + " -> " + tipBone + ")";
    }
}
