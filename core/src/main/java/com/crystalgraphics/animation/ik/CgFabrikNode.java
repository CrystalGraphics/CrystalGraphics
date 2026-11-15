package com.crystalgraphics.animation.ik;

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

/**
 * A chain of any length reaching its tip for a point: Unreal's FABRIK ({@code FAnimNode_Fabrik}, AnimNode_Fabrik.cpp,
 * over {@code AnimationCore::SolveFabrik}, FABRIK.cpp). Positions are solved forward and backward along the chain until
 * the tip is within the precision, then each bone turns from its old direction to its new.
 *
 * <pre>{@code
 * // a tail of six bones reaching a point the game sets
 * CgFabrikNode.of(body, "tail_01", "tail_06", CgIkTarget.world(TAIL_X, TAIL_Y, TAIL_Z))
 *
 * // an arm to a hand-hold bone, the hand taking the hold's rotation, 5 mm precision
 * CgFabrikNode.of(body, "clavicle_r", "hand_r", CgIkTarget.of(CgBoneSpace.BONE, "hold", 0f, 0f, 0f))
 *         .tipRotation(CgFabrikNode.TipRotation.COPY_FROM_TARGET).precision(0.005f, 10)
 * }</pre>
 *
 * <ul>
 *   <li>Out of reach, the chain straightens toward the target.</li>
 *   <li>A bone of zero length rides the bone above it.</li>
 *   <li>The precision is a distance in the skeleton's units; 0.01, Unreal's centimetre in metres.</li>
 * </ul>
 */
public final class CgFabrikNode extends CgSkeletalControlNode {

    /** Unreal's {@code EBoneRotationSource}: the tip's rotation once solved. */
    public enum TipRotation { KEEP_LOCAL, COPY_FROM_TARGET, KEEP_COMPONENT }

    /** Reads {@code kind: fabrik}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> of(source(c, at, m), c.string(at, m, "root_bone"), c.string(at, m, "tip_bone"),
            CgIkTarget.read(c, at + ".effector", m.get("effector")))
            .alpha(alpha(c, at, m))
            .tipRotation(choice(c, at, m, "tip_rotation", TipRotation.class, TipRotation.KEEP_LOCAL))
            .precision(number(c, at, m, "precision", 0.01f), (int) number(c, at, m, "max_iterations", 10));

    private final String rootBone, tipBone;
    private final CgIkTarget effector;
    private final TipRotation tipRotation;
    private final float precision;
    private final int maxIterations;

    private CgFabrikNode(CgAnimNode source, CgControlAlpha alpha, String rootBone, String tipBone, CgIkTarget effector,
                         TipRotation tipRotation, float precision, int maxIterations) {
        super(source, alpha);
        this.rootBone = Objects.requireNonNull(rootBone, "rootBone");
        this.tipBone = Objects.requireNonNull(tipBone, "tipBone");
        this.effector = Objects.requireNonNull(effector, "effector");
        this.tipRotation = Objects.requireNonNull(tipRotation, "tipRotation");
        if (!(precision > 0f)) throw new IllegalArgumentException("a precision above 0");
        this.precision = precision;
        this.maxIterations = maxIterations;
    }

    public static CgFabrikNode of(CgAnimNode source, String rootBone, String tipBone, CgIkTarget effector) {
        return new CgFabrikNode(source, CgControlAlpha.FULL, rootBone, tipBone, effector, TipRotation.KEEP_LOCAL, 0.01f, 10);
    }

    public CgFabrikNode alpha(CgControlAlpha alpha) {
        return new CgFabrikNode(source, alpha, rootBone, tipBone, effector, tipRotation, precision, maxIterations);
    }

    public CgFabrikNode tipRotation(TipRotation tipRotation) {
        return new CgFabrikNode(source, alpha, rootBone, tipBone, effector, tipRotation, precision, maxIterations);
    }

    /** How close the tip must come, and the most passes spent getting there. */
    public CgFabrikNode precision(float precision, int maxIterations) {
        return new CgFabrikNode(source, alpha, rootBone, tipBone, effector, tipRotation, precision, maxIterations);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int[] chain = chain(g.skeleton(), rootBone, tipBone, this);
        int effectorBone = effector.bone(g.skeleton(), this);
        int n = chain.length;
        return new Control(n) {
            // the links: bones of non-zero length, each with its transform index, length and solved position
            private final int[] linkIndex = new int[n], riderOf = new int[n];
            private final float[] linkLength = new float[n];
            private final Vector3f[] position = new Vector3f[n];
            private final Matrix4f effectorTm = new Matrix4f(), local = new Matrix4f();
            private final Vector3f target = new Vector3f(), oldDir = new Vector3f(), newDir = new Vector3f(),
                    a = new Vector3f(), b = new Vector3f(), t = new Vector3f(), s = new Vector3f();
            private final Quaternionf delta = new Quaternionf(), q = new Quaternionf();

            {
                for (int i = 0; i < n; i++) position[i] = new Vector3f();
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                effector.resolve(g, pose, effectorBone, effectorTm).getTranslation(target);
                for (int i = 0; i < n; i++) {
                    bones[i] = chain[i];
                    out[i].set(pose.component(chain[i]));
                }
                int links = 1;
                float maxReach = 0f;
                linkIndex[0] = 0;
                linkLength[0] = 0f;
                riderOf[0] = -1;
                out[0].getTranslation(position[0]);
                for (int i = 1; i < n; i++) {
                    out[i].getTranslation(a);
                    out[i - 1].getTranslation(b);
                    float length = a.distance(b);
                    if (Math.abs(length) > 1e-8f) {
                        linkIndex[links] = i;
                        linkLength[links] = length;
                        position[links].set(a);
                        riderOf[i] = -1;
                        links++;
                        maxReach += length;
                    } else {
                        riderOf[i] = links - 1;   // a zero-length bone: inherits its link's position and turn
                    }
                }
                if (solve(links, maxReach)) {
                    for (int l = 0; l < links; l++) out[linkIndex[l]].setTranslation(position[l]);
                    for (int i = 1; i < n; i++) if (riderOf[i] >= 0) out[i].setTranslation(position[riderOf[i]]);
                    // re-orient each link from its old direction to its child's new one
                    for (int l = 0; l < links - 1; l++) {
                        pose.component(chain[linkIndex[l + 1]]).getTranslation(a);
                        pose.component(chain[linkIndex[l]]).getTranslation(b);
                        oldDir.set(a).sub(b);
                        newDir.set(position[l + 1]).sub(position[l]);
                        if (oldDir.lengthSquared() < 1e-16f || newDir.lengthSquared() < 1e-16f) continue;
                        CgIkMath.findBetweenNormals(oldDir.normalize(), newDir.normalize(), delta);
                        rotate(out[linkIndex[l]]);
                        for (int i = 1; i < n; i++) if (riderOf[i] == l) rotate(out[i]);
                    }
                }
                Matrix4f tip = out[n - 1];
                switch (tipRotation) {
                    case KEEP_LOCAL -> {
                        // the input's local under the solved parent
                        local.set(pose.component(chain[n - 2])).invertAffine().mulAffine(pose.component(chain[n - 1]));
                        out[n - 2].mulAffine(local, tip);
                    }
                    case COPY_FROM_TARGET -> {
                        tip.getTranslation(t);
                        tip.getScale(s);
                        tip.translationRotateScale(t, effectorTm.getNormalizedRotation(q), s);
                    }
                    case KEEP_COMPONENT -> {
                    }
                }
                return n;
            }

            private void rotate(Matrix4f m) {
                m.getTranslation(t);
                m.getNormalizedRotation(q);
                m.getScale(s);
                m.translationRotateScale(t, delta.mul(q, q).normalize(), s);
            }

            /** {@code SolveFabrik}: whether any link moved. */
            private boolean solve(int links, float maxReach) {
                if (links < 2) return false;   // every bone below the root has zero length: nothing reaches
                Vector3f root = position[0];
                if (root.distanceSquared(target) > maxReach * maxReach) {
                    for (int l = 1; l < links; l++) {
                        Vector3f parent = position[l - 1];
                        position[l].set(target).sub(parent).normalize().mul(linkLength[l]).add(parent);
                    }
                    return true;
                }
                int tip = links - 1;
                float slop = position[tip].distance(target);
                if (slop <= precision) return false;
                position[tip].set(target);
                int iteration = 0;
                while (slop > precision && iteration++ < maxIterations) {
                    for (int l = tip - 1; l > 0; l--) {
                        Vector3f child = position[l + 1];
                        position[l].sub(child).normalize().mul(linkLength[l + 1]).add(child);
                    }
                    for (int l = 1; l < tip; l++) {
                        Vector3f parent = position[l - 1];
                        position[l].sub(parent).normalize().mul(linkLength[l]).add(parent);
                    }
                    slop = Math.abs(linkLength[tip] - position[tip - 1].distance(target));
                }
                Vector3f parent = position[tip - 1];
                position[tip].sub(parent).normalize().mul(linkLength[tip]).add(parent);
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
        out.kind("fabrik");
        writeBase(out);
        out.put("root_bone", rootBone).put("tip_bone", tipBone).put("effector", effector.tree());
        if (tipRotation != TipRotation.KEEP_LOCAL) out.put("tip_rotation", id(tipRotation));
        if (precision != 0.01f) out.put("precision", precision);
        if (maxIterations != 10) out.put("max_iterations", maxIterations);
    }

    @Override
    public String toString() {
        return "fabrik(" + rootBone + " -> " + tipBone + ")";
    }
}
