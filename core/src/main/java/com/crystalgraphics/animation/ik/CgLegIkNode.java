package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * Legs of any number of bones reaching their IK feet: Unreal's Leg IK ({@code FAnimNode_LegIK}, AnimNode_LegIK.cpp). Each
 * leg is turned about its hip toward its IK foot, then stretched or bent so the foot lands on it (an exact two-bone solve
 * for three joints, FABRIK with an even pull for more), its knee twisted back to where the foot points, and the foot
 * given the IK foot's rotation. The IK foot is a bone an earlier node places: a foot placement, a modify bone.
 *
 * <pre>{@code
 * // both legs onto the IK foot bones a foot placement node set
 * CgLegIkNode.of(placed).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r")
 *
 * // a digitigrade leg of four bones, its knees never folding past 20 degrees, softened short of full reach
 * CgLegIkNode.of(placed).leg(CgLegIkNode.Leg.of("ik_foot_l", "foot_l").bones(3).rotationLimit(20f).hinge(CgLegIkNode.Axis.X))
 *         .soft(0.95f, 1f)
 * }</pre>
 *
 * <ul>
 *   <li>A leg needs three joints at least: the foot, a knee, a hip.</li>
 *   <li>The precision is a distance in the skeleton's units: 0.0001, Unreal's 0.01 cm.</li>
 *   <li>A twist offset curve is refused at load: the graph has no curves to read it from.</li>
 * </ul>
 */
public final class CgLegIkNode extends CgSkeletalControlNode {

    /** {@code EAxis}: a bone's own axis. */
    public enum Axis { NONE, X, Y, Z }

    /** One leg: Unreal's {@code FAnimLegIKDefinition}. */
    public record Leg(String ikFootBone, String fkFootBone, int bonesInLimb, float minRotationAngle, Axis footForwardAxis,
                      Axis hingeAxis, boolean rotationLimit, boolean kneeTwistCorrection) {

        public Leg {
            Objects.requireNonNull(ikFootBone, "ikFootBone");
            Objects.requireNonNull(fkFootBone, "fkFootBone");
            Objects.requireNonNull(footForwardAxis, "footForwardAxis");
            Objects.requireNonNull(hingeAxis, "hingeAxis");
        }

        /** Unreal's defaults: two bones above the foot, the foot's +y forward, knee twist corrected. */
        public static Leg of(String ikFootBone, String fkFootBone) {
            return new Leg(ikFootBone, fkFootBone, 2, 15f, Axis.Y, Axis.NONE, false, true);
        }

        /** How many bones above the foot the leg has. */
        public Leg bones(int bonesInLimb) {
            return new Leg(ikFootBone, fkFootBone, bonesInLimb, minRotationAngle, footForwardAxis, hingeAxis, rotationLimit, kneeTwistCorrection);
        }

        /** Knees kept from folding past {@code degrees} (0 to 90). */
        public Leg rotationLimit(float degrees) {
            return new Leg(ikFootBone, fkFootBone, bonesInLimb, degrees, footForwardAxis, hingeAxis, true, kneeTwistCorrection);
        }

        /** The hip's axis the knee hinges about, which keeps the bend's direction while the leg is straight. */
        public Leg hinge(Axis axis) {
            return new Leg(ikFootBone, fkFootBone, bonesInLimb, minRotationAngle, footForwardAxis, axis, rotationLimit, kneeTwistCorrection);
        }

        /** The foot's axis that points forward, for its knee's twist. */
        public Leg footForward(Axis axis) {
            return new Leg(ikFootBone, fkFootBone, bonesInLimb, minRotationAngle, axis, hingeAxis, rotationLimit, kneeTwistCorrection);
        }

        public Leg kneeTwistCorrection(boolean on) {
            return new Leg(ikFootBone, fkFootBone, bonesInLimb, minRotationAngle, footForwardAxis, hingeAxis, rotationLimit, on);
        }
    }

    /** Reads {@code kind: leg_ik}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgLegIkNode n = of(source(c, at, m)).alpha(alpha(c, at, m))
                .precision(number(c, at, m, "reach_precision", 0.0001f), (int) number(c, at, m, "max_iterations", 12))
                .soft(number(c, at, m, "soft_percent_length", 1f), number(c, at, m, "soft_alpha", 1f));
        if (!(m.get("legs") instanceof List<?> legs) || legs.isEmpty()) throw new CgAssetException(at + ".legs: none");
        for (int i = 0; i < legs.size(); i++) {
            String a = at + ".legs[" + i + "]";
            if (!(legs.get(i) instanceof Map<?, ?> l)) throw new CgAssetException(a + ": a map");
            if (l.get("twist_offset_curve") != null) throw new CgAssetException(a + ".twist_offset_curve: curves are not read by a graph yet");
            n = n.leg(new Leg(c.string(a, l, "ik_foot_bone"), c.string(a, l, "fk_foot_bone"), (int) number(c, a, l, "bones_in_limb", 2),
                    number(c, a, l, "min_rotation_angle", 15f), choice(c, a, l, "foot_forward_axis", Axis.class, Axis.Y),
                    choice(c, a, l, "hinge_axis", Axis.class, Axis.NONE), Boolean.TRUE.equals(l.get("rotation_limit")),
                    !Boolean.FALSE.equals(l.get("knee_twist_correction"))));
        }
        return n;
    };

    /** {@code a.AnimNode.LegIK.TargetReachStepPercent}, {@code PullDistribution}: Unreal's console defaults. */
    private static final float REACH_STEP = 0.7f, PULL_DISTRIBUTION = 0.5f;
    /** Average pull runs while the slop is past a centimetre: Unreal's {@code Slop > 1.f}. */
    private static final float AVERAGE_PULL_SLOP = 0.01f;

    private final List<Leg> legs;
    private final float precision, softPercentLength, softAlpha;
    private final int maxIterations;

    private CgLegIkNode(CgAnimNode source, CgControlAlpha alpha, List<Leg> legs, float precision, int maxIterations,
                        float softPercentLength, float softAlpha) {
        super(source, alpha);
        this.legs = List.copyOf(legs);
        this.precision = precision;
        this.maxIterations = maxIterations;
        this.softPercentLength = softPercentLength;
        this.softAlpha = softAlpha;
    }

    public static CgLegIkNode of(CgAnimNode source) {
        return new CgLegIkNode(source, CgControlAlpha.FULL, List.of(), 0.0001f, 12, 1f, 1f);
    }

    public CgLegIkNode alpha(CgControlAlpha alpha) {
        return new CgLegIkNode(source, alpha, legs, precision, maxIterations, softPercentLength, softAlpha);
    }

    /** A leg with Unreal's defaults. */
    public CgLegIkNode leg(String ikFootBone, String fkFootBone) {
        return leg(Leg.of(ikFootBone, fkFootBone));
    }

    public CgLegIkNode leg(Leg leg) {
        List<Leg> next = new ArrayList<>(legs);
        next.add(Objects.requireNonNull(leg, "leg"));
        return new CgLegIkNode(source, alpha, next, precision, maxIterations, softPercentLength, softAlpha);
    }

    /** How close a foot must come, and the most FABRIK passes. */
    public CgLegIkNode precision(float precision, int maxIterations) {
        return new CgLegIkNode(source, alpha, legs, precision, maxIterations, softPercentLength, softAlpha);
    }

    /** Soft IK: past {@code percentLength} of its reach the foot eases toward full length instead of snapping straight. */
    public CgLegIkNode soft(float percentLength, float alpha) {
        return new CgLegIkNode(source, this.alpha, legs, precision, maxIterations, percentLength, alpha);
    }

    /** One leg's runtime: Unreal's {@code FAnimLegIKData} with its {@code FIKChain}, kept between frames. */
    private final class LegData {
        final Leg def;
        final int ikFoot, n;
        final int[] bones;                   // foot first, hip last
        final Matrix4f[] fk;
        final Matrix4f ikFootTm = new Matrix4f();
        final Vector3f[] links, forward, backward, axisZ;
        final float[] lengths;
        final Vector3f realBend = new Vector3f(), baseBend = new Vector3f(), hinge = new Vector3f();
        float total;

        LegData(Leg def, CgSkeleton skeleton) {
            this.def = def;
            ikFoot = bone(skeleton, def.ikFootBone, CgLegIkNode.this);
            int foot = bone(skeleton, def.fkFootBone, CgLegIkNode.this);
            int[] found = new int[Math.max(def.bonesInLimb, 1) + 1];
            int count = 0;
            for (int b = foot; b >= 0 && count < found.length; b = skeleton.parent(b)) found[count++] = b;
            if (count < 3) throw new IllegalArgumentException(CgLegIkNode.this + ": " + def.fkFootBone + " needs a knee and a hip above it");
            n = count;
            bones = Arrays.copyOf(found, n);
            fk = new Matrix4f[n];
            links = new Vector3f[n];
            forward = new Vector3f[n];
            backward = new Vector3f[n];
            axisZ = new Vector3f[n];
            lengths = new float[n];
            for (int i = 0; i < n; i++) {
                fk[i] = new Matrix4f();
                links[i] = new Vector3f();
                forward[i] = new Vector3f();
                backward[i] = new Vector3f();
                axisZ[i] = new Vector3f();
            }
        }
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        if (legs.isEmpty()) throw new IllegalArgumentException(this + ": no legs");
        LegData[] data = new LegData[legs.size()];
        int total = 0;
        for (int i = 0; i < data.length; i++) {
            data[i] = new LegData(legs.get(i), g.skeleton());
            total += data[i].n;
        }
        return new Control(total) {
            private final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(),
                    t = new Vector3f(), s = new Vector3f();
            private final Quaternionf q = new Quaternionf(), delta = new Quaternionf(), r = new Quaternionf();

            @Override
            protected void initialize(CgAnimGraphInstance g) {
                for (LegData leg : data) {
                    leg.realBend.zero();
                    leg.baseBend.zero();
                }
            }

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                int count = 0;
                for (LegData leg : data) {
                    for (int i = 0; i < leg.n; i++) leg.fk[i].set(pose.component(leg.bones[i]));
                    leg.ikFootTm.set(pose.component(leg.ikFoot));
                    orientTowardsIk(leg);
                    reach(leg);
                    if (leg.def.kneeTwistCorrection) adjustKneeTwist(leg);
                    // the foot takes the IK foot's rotation
                    Matrix4f foot = leg.fk[0];
                    foot.getTranslation(t);
                    foot.getScale(s);
                    foot.translationRotateScale(t, leg.ikFootTm.getNormalizedRotation(q), s);
                    for (int i = 0; i < leg.n; i++) insert(bones, out, count++, leg.bones[i], leg.fk[i]);
                }
                return count;
            }

            /** Into the ascending output: Unreal sorts its bone transforms by index. */
            private void insert(int[] bones, Matrix4f[] out, int at, int bone, Matrix4f tm) {
                int i = at;
                while (i > 0 && bones[i - 1] > bone) {
                    bones[i] = bones[i - 1];
                    out[i].set(out[i - 1]);
                    i--;
                }
                bones[i] = bone;
                out[i].set(tm);
            }

            /** {@code RotateLegByQuat}: every bone turned about the hip. */
            private void rotateLeg(LegData leg, Quaternionf turn) {
                leg.fk[leg.n - 1].getTranslation(c);
                for (int i = 0; i < leg.n; i++) {
                    Matrix4f m = leg.fk[i];
                    m.getTranslation(t);
                    m.getNormalizedRotation(q);
                    m.getScale(s);
                    turn.transform(t.sub(c)).add(c);
                    m.translationRotateScale(t, turn.mul(q, r).normalize(), s);
                }
            }

            /** {@code RotateLegByDeltaNormals}. */
            private void rotateLegBetween(LegData leg, Vector3f from, Vector3f to) {
                if (from.lengthSquared() == 0f || from.distanceSquared(to) <= 1e-8f) return;
                rotateLeg(leg, CgIkMath.findBetweenNormals(from, to, delta));
            }

            /** {@code OrientLegTowardsIK}. */
            private void orientTowardsIk(LegData leg) {
                leg.fk[leg.n - 1].getTranslation(c);
                safeNormal(leg.fk[0].getTranslation(a).sub(c));
                safeNormal(leg.ikFootTm.getTranslation(b).sub(c));
                rotateLegBetween(leg, a, b);
            }

            /** {@code DoLegReachIK}: the chain solved, then each bone turned to its link and moved to it. */
            private void reach(LegData leg) {
                leg.fk[0].getTranslation(a);
                leg.ikFootTm.getTranslation(b);
                boolean soft = softPercentLength < 1f && softAlpha > 0f;
                boolean atGoal = a.distance(b) <= precision;
                if (atGoal && !leg.def.rotationLimit && !soft) return;
                // InitializeFromLegData
                leg.total = 0f;
                for (int i = 0; i < leg.n - 1; i++) {
                    leg.fk[i].getTranslation(leg.links[i]);
                    leg.lengths[i] = leg.links[i].distance(leg.fk[i + 1].getTranslation(c));
                    leg.total += leg.lengths[i];
                }
                leg.fk[leg.n - 1].getTranslation(leg.links[leg.n - 1]);
                leg.lengths[leg.n - 1] = 0f;
                if (leg.def.hingeAxis != Axis.NONE) axis(leg.fk[leg.n - 1], leg.def.hingeAxis, leg.hinge);
                else leg.hinge.zero();
                reachTarget(leg, b);
                for (int i = leg.n - 2; i >= 0; i--) {
                    Matrix4f parent = leg.fk[i + 1];
                    safeNormal(leg.fk[i].getTranslation(a).sub(parent.getTranslation(c)));
                    safeNormal(d.set(leg.links[i]).sub(leg.links[i + 1]));
                    CgIkMath.findBetweenNormals(a, d, delta);
                    parent.getTranslation(t);
                    parent.getNormalizedRotation(q);
                    parent.getScale(s);
                    parent.translationRotateScale(t, delta.mul(q, r).normalize(), s);
                }
                for (int i = leg.n - 2; i >= 0; i--) leg.fk[i].setTranslation(leg.links[i]);
            }

            /** {@code FIKChain::ReachTarget}. */
            private void reachTarget(LegData leg, Vector3f target) {
                Vector3f root = leg.links[leg.n - 1];
                Vector3f goal = c.set(target);
                boolean soft = softPercentLength < 1f && softAlpha > 0f;
                if (soft) soften(root, leg.total, goal);
                boolean reachable = root.distanceSquared(target) < leg.total * leg.total;
                if (leg.n <= 2 || (!reachable && !soft)) {
                    safeNormal(d.set(target).sub(root));
                    for (int i = leg.n - 2; i >= 0; i--) leg.links[i].set(d).mul(leg.lengths[i]).add(leg.links[i + 1]);
                } else if (leg.n == 3) {
                    twoBone(leg, goal);
                } else {
                    fabrik(leg, goal);
                }
            }

            /** {@code SoftenIKEffectorPosition} (SoftIK.cpp). */
            private void soften(Vector3f root, float total, Vector3f effector) {
                if (Math.abs(1f - softPercentLength) < 1e-4f || total <= 1e-4f) return;
                d.set(effector).sub(root);
                float length = d.length();
                if (length > 0f) d.div(length);
                float softDistance = total * (1f - Math.min(1f, softPercentLength)), hard = total - softDistance;
                float over = length - hard;
                if (over <= 1e-4f || softDistance <= 1e-4f) return;
                float softened = hard + softDistance * (1f - (float) Math.exp(-over / softDistance));
                float alphaNow = Math.max(0f, Math.min(1f, softAlpha));
                float l = alphaNow < 1f ? Math.min(length, total) + (softened - Math.min(length, total)) * softAlpha : softened;
                effector.set(d).mul(l).add(root);
            }

            /** {@code FIKChain::SolveTwoBoneIK}: law of cosines, the bend kept from the animation. */
            private void twoBone(LegData leg, Vector3f target) {
                Vector3f foot = leg.links[0], knee = leg.links[1], hip = leg.links[2];
                foot.set(target);
                Vector3f hipToFoot = a.set(foot).sub(hip);
                float upper = leg.lengths[1], span = hipToFoot.length(), lower = leg.lengths[0];
                float twoAB = 2f * upper * span;
                float cos = Math.abs(twoAB) > 1e-8f ? (upper * upper + span * span - lower * lower) / twoAB : 0f;
                float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, cos)));
                Vector3f dir = Math.abs(span) > 1e-8f ? hipToFoot.div(span) : hipToFoot.zero();
                Vector3f hipToKnee = b.set(knee).sub(hip);
                // the knee projected onto the hip-foot line; the bend is from there to the knee
                Vector3f bend = d.set(knee).sub(hip).sub(t.set(dir).mul(hipToKnee.dot(dir)));
                if (bend.lengthSquared() > 1e-8f) bend.normalize(); else bend.zero();
                if (leg.hinge.lengthSquared() > 0f && dir.lengthSquared() > 0f && Math.abs(upper) > 1e-8f) {
                    float kneeBend = s.set(hipToKnee).div(upper).dot(dir);
                    if (bend.lengthSquared() > 0f && kneeBend < 0.99f) {
                        leg.realBend.set(bend);
                        leg.hinge.cross(dir, leg.baseBend);
                    } else if (leg.realBend.lengthSquared() > 0f) {
                        leg.hinge.cross(dir, s);
                        CgIkMath.findBetweenNormals(leg.baseBend, s, delta).transform(bend.set(leg.realBend));
                    }
                }
                knee.set(hip).fma(upper * cos, dir).fma(upper * (float) Math.sin(angle), bend);
            }

            /** {@code FIKChain::SolveFABRIK}, its pull distributed and averaged. */
            private void fabrik(LegData leg, Vector3f target) {
                float reachPrecision = Math.max(precision, 1e-4f);
                Vector3f rootTarget = s.set(leg.links[leg.n - 1]);
                float slop = leg.links[0].distance(target);
                if (slop <= reachPrecision && !leg.def.rotationLimit) return;
                float minAngle = (float) Math.toRadians(Math.max(0f, Math.min(90f, leg.def.minRotationAngle)));
                if (leg.def.rotationLimit) {
                    planeNormal(leg, rootTarget, target, b);
                    for (int i = 1; i < leg.n - 1; i++) {
                        safeNormal(a.set(leg.links[i - 1]).sub(leg.links[i]));
                        Vector3f childY = b.cross(a, d);
                        safeNormal(t.set(leg.links[i + 1]).sub(leg.links[i]));
                        leg.axisZ[i].set(b);
                        if (t.dot(childY) <= 0f) leg.axisZ[i].negate();
                    }
                }
                // the limb moved to share the pull between the foot and the hip
                a.set(target).sub(leg.links[0]).mul(PULL_DISTRIBUTION).fma(1f - PULL_DISTRIBUTION, d.set(rootTarget).sub(leg.links[leg.n - 1]));
                for (int i = 0; i < leg.n; i++) leg.links[i].add(a);
                int iteration = 1, most = Math.max(maxIterations, 1);
                do {
                    float previous = slop;
                    if (leg.n > 3 && slop > AVERAGE_PULL_SLOP) {
                        for (int i = 0; i < leg.n; i++) {
                            leg.forward[i].set(leg.links[i]);
                            leg.backward[i].set(leg.links[i]);
                        }
                        forwardReach(leg, leg.forward, target, minAngle);
                        backwardReach(leg, leg.backward, rootTarget, minAngle);
                        for (int i = 0; i < leg.n; i++) leg.links[i].set(leg.forward[i]).add(leg.backward[i]).mul(0.5f);
                    } else {
                        forwardReach(leg, leg.links, target, minAngle);
                        backwardReach(leg, leg.links, rootTarget, minAngle);
                    }
                    slop = leg.links[0].distance(target) + leg.links[leg.n - 1].distance(rootTarget);
                    if (slop > previous) break;   // not getting closer: a deadlock
                } while (slop > reachPrecision && ++iteration < most);
                if (leg.links[leg.n - 1].distanceSquared(rootTarget) > 1e-8f) backwardReach(leg, leg.links, rootTarget, minAngle);
                if (slop <= reachPrecision) leg.links[0].set(target);
            }

            /** {@code FABRIK_ForwardReach}: the foot toward the target, a step at most, then each link after it. */
            private void forwardReach(LegData leg, Vector3f[] links, Vector3f target, float minAngle) {
                Vector3f dir = safeNormal(t.set(target).sub(links[0]));
                float displacement = target.distance(links[0]);
                for (int i = 1; i < leg.n; i++) {
                    float toParent = a.set(links[i]).sub(links[0]).dot(dir);
                    if (toParent > 0f) displacement = Math.min(displacement, toParent * REACH_STEP);
                }
                links[0].fma(displacement, dir);
                for (int i = 1; i < leg.n; i++) {
                    links[i].sub(links[i - 1]);
                    safeNormal(links[i]).mul(leg.lengths[i - 1]).add(links[i - 1]);
                    if (leg.def.rotationLimit) constrainForward(leg, links, i, minAngle);
                }
            }

            /** {@code FABRIK_BackwardReach}: the hip back toward its place, then each link below it. */
            private void backwardReach(LegData leg, Vector3f[] links, Vector3f rootTarget, float minAngle) {
                int last = leg.n - 1;
                Vector3f dir = safeNormal(t.set(rootTarget).sub(links[last]));
                float displacement = rootTarget.distance(links[last]);
                // Unreal's loop reads the hip's child every pass: one bound, the step toward the root
                float toChild = a.set(links[last - 1]).sub(links[last]).dot(dir);
                if (toChild > 0f) displacement = Math.min(displacement, toChild * REACH_STEP);
                links[last].fma(displacement, dir);
                for (int i = last; i >= 1; i--) {
                    links[i - 1].sub(links[i]);
                    safeNormal(links[i - 1]).mul(leg.lengths[i - 1]).add(links[i]);
                    if (leg.def.rotationLimit) constrainBackward(leg, links, i, minAngle);
                }
            }

            /** {@code FABRIK_ApplyLinkConstraints_Forward}. */
            private void constrainForward(LegData leg, Vector3f[] links, int i, float minAngle) {
                if (i <= 0 || i >= leg.n - 1) return;
                Vector3f childX = safeNormal(a.set(links[i - 1]).sub(links[i]));
                Vector3f childY = leg.axisZ[i].cross(childX, b);
                Vector3f parentX = safeNormal(d.set(links[i + 1]).sub(links[i]));
                float cos = parentX.dot(childX), sin = parentX.dot(childY);
                if (sin < 0f || cos > (float) Math.cos(minAngle)) {
                    if (cos > 0f) {
                        links[i + 1].set(links[i]).fma(leg.lengths[i] * (float) Math.cos(minAngle), childX)
                                .fma(leg.lengths[i] * (float) Math.sin(minAngle), childY);
                    } else {
                        links[i + 1].set(links[i]).fma(-leg.lengths[i], childX);
                    }
                }
            }

            /** {@code FABRIK_ApplyLinkConstraints_Backward}. */
            private void constrainBackward(LegData leg, Vector3f[] links, int i, float minAngle) {
                if (i <= 0 || i >= leg.n - 1) return;
                Vector3f parentX = safeNormal(a.set(links[i + 1]).sub(links[i]));
                Vector3f parentY = leg.axisZ[i].cross(parentX, b);
                Vector3f childX = safeNormal(d.set(links[i - 1]).sub(links[i]));
                float cos = childX.dot(parentX), sin = childX.dot(parentY);
                if (sin > 0f || cos > (float) Math.cos(minAngle)) {
                    if (cos > 0f) {
                        links[i - 1].set(links[i]).fma(leg.lengths[i - 1] * (float) Math.cos(minAngle), parentX)
                                .fma(-leg.lengths[i - 1] * (float) Math.sin(minAngle), parentY);
                    } else {
                        links[i - 1].set(links[i]).fma(-leg.lengths[i - 1], parentX);
                    }
                }
            }

            /** {@code FindPlaneNormal}: the first link off the root-target line gives the plane; up if all lie on it. */
            private void planeNormal(LegData leg, Vector3f root, Vector3f target, Vector3f out) {
                Vector3f x = safeNormal(q4.set(target).sub(root));
                for (int i = leg.n - 2; i >= 0; i--) {
                    safeNormal(y4.set(leg.links[i]).sub(root));
                    x.cross(y4, out);
                    if (out.lengthSquared() > 1e-8f) {
                        out.normalize();
                        return;
                    }
                }
                out.set(0f, 1f, 0f);
            }

            private final Vector3f q4 = new Vector3f(), y4 = new Vector3f();

            /** {@code AdjustKneeTwist}: the leg turned about the hip-to-foot line so the FK foot points as the IK foot. */
            private void adjustKneeTwist(LegData leg) {
                leg.fk[leg.n - 1].getTranslation(c);
                Vector3f z = safeNormal(leg.ikFootTm.getTranslation(d).sub(c));
                Vector3f fkX = axis(leg.fk[0], leg.def.footForwardAxis, a);
                Vector3f ikX = axis(leg.ikFootTm, leg.def.footForwardAxis, b);
                z.cross(fkX, t);
                t.cross(z, fkX);
                z.cross(ikX, t);
                t.cross(z, ikX);
                rotateLegBetween(leg, fkX, ikX);
            }
        };
    }

    /** {@code m}'s unit {@code axis} in the skeleton's space: {@code FTransform::GetUnitAxis}. */
    private static Vector3f axis(Matrix4f m, Axis axis, Vector3f out) {
        switch (axis) {
            case X -> out.set(m.m00(), m.m01(), m.m02());
            case Y -> out.set(m.m10(), m.m11(), m.m12());
            case Z -> out.set(m.m20(), m.m21(), m.m22());
            case NONE -> out.zero();
        }
        return safeNormal(out);
    }

    /** {@code GetSafeNormal}: zero where too short. */
    private static Vector3f safeNormal(Vector3f v) {
        float l = v.lengthSquared();
        return l > 1e-16f ? v.div((float) Math.sqrt(l)) : v.zero();
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("leg_ik");
        writeBase(out);
        List<Object> list = new ArrayList<>(legs.size());
        for (Leg l : legs) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ik_foot_bone", l.ikFootBone);
            e.put("fk_foot_bone", l.fkFootBone);
            if (l.bonesInLimb != 2) e.put("bones_in_limb", l.bonesInLimb);
            if (l.minRotationAngle != 15f) e.put("min_rotation_angle", l.minRotationAngle);
            if (l.footForwardAxis != Axis.Y) e.put("foot_forward_axis", id(l.footForwardAxis));
            if (l.hingeAxis != Axis.NONE) e.put("hinge_axis", id(l.hingeAxis));
            if (l.rotationLimit) e.put("rotation_limit", true);
            if (!l.kneeTwistCorrection) e.put("knee_twist_correction", false);
            list.add(e);
        }
        out.put("legs", list);
        if (precision != 0.0001f) out.put("reach_precision", precision);
        if (maxIterations != 12) out.put("max_iterations", maxIterations);
        if (softPercentLength != 1f) out.put("soft_percent_length", softPercentLength);
        if (softAlpha != 1f) out.put("soft_alpha", softAlpha);
    }

    @Override
    public String toString() {
        return "leg_ik(" + legs.size() + " legs)";
    }
}
