package com.crystalgraphics.animation.ik;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * A limb of two bones reaching for a point, its middle joint bending toward another: Unreal's Two Bone IK
 * ({@code FAnimNode_TwoBoneIK}, AnimNode_TwoBoneIK.cpp, over {@code AnimationCore::SolveTwoBoneIK}). Named by its end
 * bone (a foot, a hand); the joint is its parent and the root its grandparent.
 *
 * <pre>{@code
 * // a foot planted on a point the game finds each frame, the knee bending forward
 * CgTwoBoneIkNode.of(locomotion, "foot_l", CgIkTarget.world(FOOT_L_X, FOOT_L_Y, FOOT_L_Z),
 *                 CgIkTarget.of(CgBoneSpace.BONE, "calf_l", 0f, 0f, 0.5f))
 *         .alpha(CgControlAlpha.bool(FEET_ON, 0.15f, 0.25f))
 *
 * // a hand reaching, its arm stretching up to 1.2 times past full reach
 * CgTwoBoneIkNode.of(body, "hand_r", reach, elbowHint).stretch(1f, 1.2f)
 * }</pre>
 *
 * <ul>
 *   <li>The end bone keeps its rotation in the skeleton's space unless {@link #keepRelativeRotation} (it rides the
 *       joint) or {@link #rotationFromEffector} (an effector in a bone's space gives it that bone's).</li>
 *   <li>Twist is allowed unless {@link #noTwist}: then each bone keeps its input twist about {@code twistAxis}.</li>
 * </ul>
 */
public final class CgTwoBoneIkNode extends CgSkeletalControlNode {

    private final String ikBone;
    private final CgIkTarget effector, jointTarget;
    private final boolean stretch, rotationFromEffector, keepRelative, allowTwist;
    private final float startStretch, maxStretch;
    private final Vector3f twistAxis;

    private CgTwoBoneIkNode(CgAnimNode source, CgControlAlpha alpha, String ikBone, CgIkTarget effector, CgIkTarget jointTarget,
                            boolean stretch, float startStretch, float maxStretch, boolean rotationFromEffector,
                            boolean keepRelative, boolean allowTwist, Vector3f twistAxis) {
        super(source, alpha);
        this.ikBone = Objects.requireNonNull(ikBone, "ikBone");
        this.effector = Objects.requireNonNull(effector, "effector");
        this.jointTarget = Objects.requireNonNull(jointTarget, "jointTarget");
        this.stretch = stretch;
        this.startStretch = startStretch;
        this.maxStretch = maxStretch;
        this.rotationFromEffector = rotationFromEffector;
        this.keepRelative = keepRelative;
        this.allowTwist = allowTwist;
        this.twistAxis = new Vector3f(twistAxis).normalize();
    }

    public static CgTwoBoneIkNode of(CgAnimNode source, String ikBone, CgIkTarget effector, CgIkTarget jointTarget) {
        return new CgTwoBoneIkNode(source, CgControlAlpha.FULL, ikBone, effector, jointTarget, false, 1f, 1.2f, false,
                false, true, new Vector3f(1f, 0f, 0f));
    }

    public CgTwoBoneIkNode alpha(CgControlAlpha alpha) {
        return new CgTwoBoneIkNode(source, alpha, ikBone, effector, jointTarget, stretch, startStretch, maxStretch,
                rotationFromEffector, keepRelative, allowTwist, twistAxis);
    }

    /** Lets the limbs lengthen from {@code start} of their reach up to {@code max} times their length. */
    public CgTwoBoneIkNode stretch(float start, float max) {
        return new CgTwoBoneIkNode(source, alpha, ikBone, effector, jointTarget, true, start, max, rotationFromEffector,
                keepRelative, allowTwist, twistAxis);
    }

    /** The end bone takes the effector's bone's rotation, where the effector is in a bone's space. */
    public CgTwoBoneIkNode rotationFromEffector() {
        return new CgTwoBoneIkNode(source, alpha, ikBone, effector, jointTarget, stretch, startStretch, maxStretch, true,
                keepRelative, allowTwist, twistAxis);
    }

    /** The end bone keeps its rotation relative to the joint, as it was in the input. */
    public CgTwoBoneIkNode keepRelativeRotation() {
        return new CgTwoBoneIkNode(source, alpha, ikBone, effector, jointTarget, stretch, startStretch, maxStretch,
                rotationFromEffector, true, allowTwist, twistAxis);
    }

    /** Each bone keeps its input twist about {@code axis}, in its own space ({@code bAllowTwist} off). */
    public CgTwoBoneIkNode noTwist(float x, float y, float z) {
        return new CgTwoBoneIkNode(source, alpha, ikBone, effector, jointTarget, stretch, startStretch, maxStretch,
                rotationFromEffector, keepRelative, false, new Vector3f(x, y, z));
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int end = bone(g.skeleton(), ikBone, this);
        int lower = g.skeleton().parent(end);
        int upper = lower < 0 ? -1 : g.skeleton().parent(lower);
        if (upper < 0) throw new IllegalArgumentException(this + ": " + ikBone + " has no grandparent");
        int effectorBone = effector.bone(g.skeleton(), this), jointBone = jointTarget.bone(g.skeleton(), this);
        boolean inBoneSpace = effector.space() == CgBoneSpace.PARENT_BONE || effector.space() == CgBoneSpace.BONE;
        return new Control(3) {
            private final Matrix4f effectorTm = new Matrix4f(), jointTm = new Matrix4f(), scratch = new Matrix4f();
            private final Vector3f root = new Vector3f(), joint = new Vector3f(), endPos = new Vector3f(),
                    target = new Vector3f(), desired = new Vector3f(), outJoint = new Vector3f(), outEnd = new Vector3f(),
                    d = new Vector3f(), n = new Vector3f(), b = new Vector3f(), oldDir = new Vector3f(), newDir = new Vector3f(),
                    t = new Vector3f(), s = new Vector3f();
            private final Quaternionf delta = new Quaternionf(), q = new Quaternionf(), swing = new Quaternionf(),
                    twist = new Quaternionf(), oldSwing = new Quaternionf(), oldTwist = new Quaternionf(), local = new Quaternionf(),
                    parentRotation = new Quaternionf(), inverseParent = new Quaternionf();

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                Matrix4f up = out[0].set(pose.component(upper)), lo = out[1].set(pose.component(lower)),
                        en = out[2].set(pose.component(end));
                up.getTranslation(root);
                lo.getTranslation(joint);
                en.getTranslation(endPos);
                effector.resolve(g, pose, effectorBone, effectorTm).getTranslation(desired);
                jointTarget.resolve(g, pose, jointBone, jointTm).getTranslation(target);
                float upperLength = joint.distance(root), lowerLength = endPos.distance(joint);
                CgIkMath.twoBone(root, joint, target, desired, upperLength, lowerLength, stretch, startStretch, maxStretch,
                        outJoint, outEnd, d, n, b);
                // Each bone turned from its old direction to its new (TwoBoneIK.cpp)
                turn(up, oldDir.set(joint).sub(root), newDir.set(outJoint).sub(root), root);
                turn(lo, oldDir.set(endPos).sub(joint), newDir.set(outEnd).sub(outJoint), outJoint);
                en.setTranslation(outEnd);
                if (!allowTwist) {
                    int parent = g.skeleton().parent(upper);
                    if (parent >= 0) removeTwist(pose.component(parent), up, pose, upper);
                    removeTwist(up, lo, pose, lower);
                }
                if (inBoneSpace && rotationFromEffector) {
                    en.getTranslation(t);
                    en.getScale(s);
                    effectorTm.getNormalizedRotation(q);
                    en.translationRotateScale(t, q, s);
                } else if (keepRelative) {
                    // EndBoneLocalTransform * LowerLimbCSTransform: the input's local, under the solved joint
                    scratch.set(pose.component(lower)).invertAffine().mulAffine(pose.component(end));
                    lo.mulAffine(scratch, en);
                }
                bones[0] = upper;
                bones[1] = lower;
                bones[2] = end;
                return 3;
            }

            /** {@code m}'s rotation turned by the rotation from {@code from} to {@code to}, its origin at {@code at}. */
            private void turn(Matrix4f m, Vector3f from, Vector3f to, Vector3f at) {
                if (from.lengthSquared() < 1e-12f || to.lengthSquared() < 1e-12f) {
                    m.setTranslation(at);
                    return;
                }
                CgIkMath.findBetweenNormals(from.normalize(), to.normalize(), delta);
                m.getNormalizedRotation(q);
                m.getScale(s);
                m.translationRotateScale(at, delta.mul(q, q), s);
            }

            /** {@code RemoveTwist}: the new swing about the twist axis, the input's twist kept, in the parent's space. */
            private void removeTwist(Matrix4fc parent, Matrix4f m, CgAnimPose pose, int bone) {
                parent.getNormalizedRotation(parentRotation);
                m.getNormalizedRotation(local).premul(inverseParent.set(parentRotation).conjugate());
                CgIkMath.swingTwist(local, twistAxis, swing, twist);
                swing.normalize();
                pose.rotation(bone, local);
                CgIkMath.swingTwist(local, twistAxis, oldSwing, oldTwist);
                oldTwist.normalize();
                m.getTranslation(t);
                m.getScale(s);
                m.translationRotateScale(t, parentRotation.mul(swing).mul(oldTwist).normalize(), s);
            }
        };
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        effector.reads(each);
        jointTarget.reads(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("two_bone_ik");
        writeBase(out);
        out.put("bone", ikBone).put("effector", effector.tree()).put("joint_target", jointTarget.tree());
        if (stretch) out.put("stretch", List.of(startStretch, maxStretch));
        if (rotationFromEffector) out.put("rotation_from_effector", true);
        if (keepRelative) out.put("keep_relative_rotation", true);
        if (!allowTwist) out.put("no_twist", List.of(twistAxis.x, twistAxis.y, twistAxis.z));
    }

    @Override
    public String toString() {
        return "two_bone_ik(" + ikBone + ")";
    }
}
