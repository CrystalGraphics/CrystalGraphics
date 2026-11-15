package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.easing.CgSpringInterp;

/**
 * Feet planted on the ground under them, the pelvis lowered so they reach it: Unreal's Foot Placement
 * ({@code FAnimNode_FootPlacement}, the AnimationWarping plugin's AnimNode_FootPlacement.cpp). Each frame every foot's IK
 * bone is swept down onto the ground (in Minecraft, its blocks: a stair's step, a slab's top), aligned to its slope, held
 * where it planted while slow enough, and the pelvis is moved so every leg reaches; a leg IK node after it bends the
 * legs to the IK feet.
 *
 * <pre>{@code
 * CgAnimNode placed = CgFootPlacementNode.of(locomotion, "pelvis", "ik_foot_root")
 *         .leg("foot_l", "ik_foot_l", "ball_l").leg("foot_r", "ik_foot_r", "ball_r");
 * CgAnimNode legs = CgLegIkNode.of(placed).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
 * anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick), component::worldTransform));
 * }</pre>
 *
 * <ul>
 *   <li>It needs a ground ({@code CgAnimInstance.ground}): with none it plants on the character's own plane, as
 *       Unreal's does with no movement component.</li>
 *   <li>Its sweeps run on the game thread, before the worker, from where the feet were last frame.</li>
 *   <li>Distances are the skeleton's units (metres): Unreal's centimetre defaults divided by 100.</li>
 *   <li>A foot's speed is its ball's own motion (Unreal's {@code Graph} mode): there are no speed curves.</li>
 * </ul>
 */
public final class CgFootPlacementNode extends CgSkeletalControlNode {

    /** {@code EFootPlacementLockType}. */
    public enum LockType { UNLOCKED, PIVOT_AROUND_BALL, PIVOT_AROUND_ANKLE, LOCK_ROTATION }

    /** {@code EPelvisHeightMode}: Unreal's own node reads only {@code AllLegs}. */
    public enum MovementCompensation { COMPONENT_SPACE, WORLD_SPACE, SUDDEN_MOTION_ONLY }

    /** One leg: {@code FFootPlacemenLegDefinition}. */
    public record Leg(String fkFootBone, String ikFootBone, String ballBone, int bonesInLimb) {
        public Leg {
            Objects.requireNonNull(fkFootBone, "fkFootBone");
            Objects.requireNonNull(ikFootBone, "ikFootBone");
            Objects.requireNonNull(ballBone, "ballBone");
        }
    }

    /** {@code FFootPlacementPlantSettings}, in metres. */
    public record Plant(float speedThreshold, float distanceToGround, LockType lockType, float unplantRadius,
                        float replantRadiusRatio, float unplantAngle, float replantAngleRatio, float maxExtensionRatio,
                        float minExtensionRatio, float separatingDistance, float unalignmentSpeedThreshold,
                        float ankleTwistReduction, boolean reconstructWorldPlantFromVelocity, boolean adjustHeelBeforePlanting) {
        public static final Plant DEFAULT = new Plant(0.6f, 0.1f, LockType.PIVOT_AROUND_BALL, 0.35f, 0.35f, 45f, 0.5f,
                0.5f, 0.2f, 0f, 2f, 0.75f, false, false);
    }

    /** {@code FFootPlacementPelvisSettings}, in metres. */
    public record Pelvis(float maxOffset, float linearStiffness, float linearDamping, float horizontalRebalancingWeight,
                         float maxOffsetHorizontal, float heelLiftRatio, MovementCompensation compensation,
                         boolean interpolation, boolean disableOffsetInAir) {
        public static final Pelvis DEFAULT = new Pelvis(0.5f, 350f, 1f, 0.3f, 0.1f, 0.5f,
                MovementCompensation.SUDDEN_MOTION_ONLY, true, true);
    }

    /** {@code FFootPlacementInterpolationSettings}. */
    public record Interpolation(float unplantLinearStiffness, float unplantLinearDamping, float unplantAngularStiffness,
                                float unplantAngularDamping, float floorLinearStiffness, float floorLinearDamping,
                                float floorAngularStiffness, float floorAngularDamping, boolean floorInterpolation,
                                boolean separationInterpolation) {
        public static final Interpolation DEFAULT = new Interpolation(250f, 1f, 450f, 1f, 1000f, 1f, 450f, 1f, true, true);
    }

    /** {@code FFootPlacementTraceSettings}, in metres; the sweep is down the component's -Y. */
    public record Trace(float startOffset, float endOffset, float sweepRadius, float maxGroundPenetration, boolean enabled) {
        public static final Trace DEFAULT = new Trace(-0.75f, 1f, 0.05f, 0.1f, true);
    }

    /** Reads {@code kind: foot_placement}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgFootPlacementNode n = of(source(c, at, m), c.string(at, m, "pelvis_bone"), c.string(at, m, "ik_foot_root_bone"))
                .alpha(alpha(c, at, m));
        if (!(m.get("legs") instanceof List<?> legs) || legs.isEmpty()) throw new CgAssetException(at + ".legs: none");
        for (int i = 0; i < legs.size(); i++) {
            String a = at + ".legs[" + i + "]";
            if (!(legs.get(i) instanceof Map<?, ?> l)) throw new CgAssetException(a + ": a map");
            n = n.leg(new Leg(c.string(a, l, "fk_foot_bone"), c.string(a, l, "ik_foot_bone"), c.string(a, l, "ball_bone"),
                    (int) number(c, a, l, "bones_in_limb", 2)));
        }
        Plant p = Plant.DEFAULT;
        if (m.get("plant") instanceof Map<?, ?> pm) {
            String a = at + ".plant";
            p = new Plant(number(c, a, pm, "speed_threshold", p.speedThreshold), number(c, a, pm, "distance_to_ground", p.distanceToGround),
                    choice(c, a, pm, "lock_type", LockType.class, p.lockType), number(c, a, pm, "unplant_radius", p.unplantRadius),
                    number(c, a, pm, "replant_radius_ratio", p.replantRadiusRatio), number(c, a, pm, "unplant_angle", p.unplantAngle),
                    number(c, a, pm, "replant_angle_ratio", p.replantAngleRatio), number(c, a, pm, "max_extension_ratio", p.maxExtensionRatio),
                    number(c, a, pm, "min_extension_ratio", p.minExtensionRatio), number(c, a, pm, "separating_distance", p.separatingDistance),
                    number(c, a, pm, "unalignment_speed_threshold", p.unalignmentSpeedThreshold),
                    number(c, a, pm, "ankle_twist_reduction", p.ankleTwistReduction),
                    Boolean.TRUE.equals(pm.get("reconstruct_world_plant_from_velocity")), Boolean.TRUE.equals(pm.get("adjust_heel_before_planting")));
        }
        Pelvis v = Pelvis.DEFAULT;
        if (m.get("pelvis") instanceof Map<?, ?> pm) {
            String a = at + ".pelvis";
            v = new Pelvis(number(c, a, pm, "max_offset", v.maxOffset), number(c, a, pm, "linear_stiffness", v.linearStiffness),
                    number(c, a, pm, "linear_damping", v.linearDamping), number(c, a, pm, "horizontal_rebalancing_weight", v.horizontalRebalancingWeight),
                    number(c, a, pm, "max_offset_horizontal", v.maxOffsetHorizontal), number(c, a, pm, "heel_lift_ratio", v.heelLiftRatio),
                    choice(c, a, pm, "movement_compensation", MovementCompensation.class, v.compensation),
                    !Boolean.FALSE.equals(pm.get("interpolation")), !Boolean.FALSE.equals(pm.get("disable_offset_in_air")));
        }
        Interpolation s = Interpolation.DEFAULT;
        if (m.get("interpolation") instanceof Map<?, ?> im) {
            String a = at + ".interpolation";
            s = new Interpolation(number(c, a, im, "unplant_linear_stiffness", s.unplantLinearStiffness),
                    number(c, a, im, "unplant_linear_damping", s.unplantLinearDamping),
                    number(c, a, im, "unplant_angular_stiffness", s.unplantAngularStiffness),
                    number(c, a, im, "unplant_angular_damping", s.unplantAngularDamping),
                    number(c, a, im, "floor_linear_stiffness", s.floorLinearStiffness), number(c, a, im, "floor_linear_damping", s.floorLinearDamping),
                    number(c, a, im, "floor_angular_stiffness", s.floorAngularStiffness), number(c, a, im, "floor_angular_damping", s.floorAngularDamping),
                    !Boolean.FALSE.equals(im.get("floor_interpolation")), !Boolean.FALSE.equals(im.get("separation_interpolation")));
        }
        Trace t = Trace.DEFAULT;
        if (m.get("trace") instanceof Map<?, ?> tm) {
            String a = at + ".trace";
            t = new Trace(number(c, a, tm, "start_offset", t.startOffset), number(c, a, tm, "end_offset", t.endOffset),
                    number(c, a, tm, "sweep_radius", t.sweepRadius), number(c, a, tm, "max_ground_penetration", t.maxGroundPenetration),
                    !Boolean.FALSE.equals(tm.get("enabled")));
        }
        return n.settings(p, v, s, t);
    };

    private final String pelvisBone, ikFootRootBone;
    private final List<Leg> legs;
    private final Plant plant;
    private final Pelvis pelvis;
    private final Interpolation interp;
    private final Trace trace;

    private CgFootPlacementNode(CgAnimNode source, CgControlAlpha alpha, String pelvisBone, String ikFootRootBone, List<Leg> legs,
                                Plant plant, Pelvis pelvis, Interpolation interp, Trace trace) {
        super(source, alpha);
        this.pelvisBone = Objects.requireNonNull(pelvisBone, "pelvisBone");
        this.ikFootRootBone = Objects.requireNonNull(ikFootRootBone, "ikFootRootBone");
        this.legs = List.copyOf(legs);
        this.plant = Objects.requireNonNull(plant, "plant");
        this.pelvis = Objects.requireNonNull(pelvis, "pelvis");
        this.interp = Objects.requireNonNull(interp, "interp");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** Feet placed under {@code pelvisBone}, their ground plane at rest the IK foot root's. */
    public static CgFootPlacementNode of(CgAnimNode source, String pelvisBone, String ikFootRootBone) {
        return new CgFootPlacementNode(source, CgControlAlpha.FULL, pelvisBone, ikFootRootBone, List.of(), Plant.DEFAULT,
                Pelvis.DEFAULT, Interpolation.DEFAULT, Trace.DEFAULT);
    }

    public CgFootPlacementNode alpha(CgControlAlpha alpha) {
        return new CgFootPlacementNode(source, alpha, pelvisBone, ikFootRootBone, legs, plant, pelvis, interp, trace);
    }

    /** A leg of two bones above its foot. */
    public CgFootPlacementNode leg(String fkFootBone, String ikFootBone, String ballBone) {
        return leg(new Leg(fkFootBone, ikFootBone, ballBone, 2));
    }

    public CgFootPlacementNode leg(Leg leg) {
        List<Leg> next = new ArrayList<>(legs);
        next.add(Objects.requireNonNull(leg, "leg"));
        return new CgFootPlacementNode(source, alpha, pelvisBone, ikFootRootBone, next, plant, pelvis, interp, trace);
    }

    public CgFootPlacementNode settings(Plant plant, Pelvis pelvis, Interpolation interp, Trace trace) {
        return new CgFootPlacementNode(source, alpha, pelvisBone, ikFootRootBone, legs, plant, pelvis, interp, trace);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        if (legs.isEmpty()) throw new IllegalArgumentException(this + ": no legs");
        return new Placement(g.skeleton());
    }

    /** One leg's runtime: {@code FLegRuntimeData}. */
    private static final class LegData {
        int fk, ik, ball, hip;
        float limbLength, footLength;
        // input pose
        final Matrix4f footFk = new Matrix4f(), foot = new Matrix4f(), ballTm = new Matrix4f(), hipTm = new Matrix4f(),
                ballToFoot = new Matrix4f(), footToBall = new Matrix4f();
        float speed, distanceToPlant, alignmentAlpha;
        final Vector3f lastBall = new Vector3f();
        // plant
        int plantType, lastPlantType;     // 0 unplanted, 1 planted, 2 replanted
        final Vector3f planeNormalRS = new Vector3f(0, 1, 0);
        float planeWRS;                   // the plane in root space: n . p = w
        final Quaternionf twistCorrection = new Quaternionf();
        float timeSinceFullyUnaligned;
        boolean canReachTarget, wantsToPlant;
        // feet kept between frames, the world's in doubles
        final Vector3d alignedPosWS = new Vector3d(), unalignedPosWS = new Vector3d();
        final Quaternionf alignedRotWS = new Quaternionf(), unalignedRotWS = new Quaternionf();
        final Matrix4f alignedRS = new Matrix4f(), unalignedRS = new Matrix4f();
        // interpolation
        final Matrix4f unalignedOffset = new Matrix4f();
        final Vector3f separatingOffset = new Vector3f();
        final CgSpringInterp.VectorSpring separatingSpring = new CgSpringInterp.VectorSpring(),
                plantTranslationSpring = new CgSpringInterp.VectorSpring();
        final CgSpringInterp.QuaternionSpring plantRotationSpring = new CgSpringInterp.QuaternionSpring(),
                groundRotationSpring = new CgSpringInterp.QuaternionSpring();
        final CgSpringInterp.FloatSpring groundHeightSpring = new CgSpringInterp.FloatSpring();
        // this frame's sweep, found on the game thread
        boolean swept, sweepFound;
        final Vector3d sweepPoint = new Vector3d();
        final Vector3f sweepNormal = new Vector3f();
    }

    /** The node's runtime in one instance. */
    private final class Placement extends Control {
        private final int pelvisIndex, ikRootIndex;
        private final LegData[] data;
        // pelvis
        private final Matrix4f pelvisFk = new Matrix4f(), ikRoot = new Matrix4f(), rootToComponent = new Matrix4f();
        private final Vector3f footMidpoint = new Vector3f(), pelvisOffset = new Vector3f();
        private final CgSpringInterp.VectorSpring pelvisSpring = new CgSpringInterp.VectorSpring();
        // character
        private final Vector3d componentPos = new Vector3d(), lastComponentPos = new Vector3d(), velocity = new Vector3d(),
                floorPoint = new Vector3d();
        private final Vector3f smoothGroundNormal = new Vector3f(0, 1, 0), moveDelta = new Vector3f(), floorNormal = new Vector3f();
        private final CgSpringInterp.QuaternionSpring groundNormalSpring = new CgSpringInterp.QuaternionSpring();
        private boolean onGround, firstUpdate = true;
        private float cachedDelta;
        // the component's turn and scale (its world less its translation), its inverse, and the approach direction
        private final Matrix4f ctw = new Matrix4f(), wtc = new Matrix4f();
        private final Vector3f approachCS = new Vector3f(0, -1, 0), approachWS = new Vector3f();
        // scratch
        private final Matrix4f m0 = new Matrix4f(), m1 = new Matrix4f(), m2 = new Matrix4f(), m3 = new Matrix4f();
        private final Vector3f v0 = new Vector3f(), v1 = new Vector3f(), v2 = new Vector3f(), v3 = new Vector3f(), v4 = new Vector3f();
        private final Quaternionf q0 = new Quaternionf(), q1 = new Quaternionf(), q2 = new Quaternionf(), q3 = new Quaternionf();
        private final Vector3d d0 = new Vector3d();

        Placement(CgSkeleton skeleton) {
            super(legs.size() + 1);
            pelvisIndex = bone(skeleton, pelvisBone, CgFootPlacementNode.this);
            ikRootIndex = bone(skeleton, ikFootRootBone, CgFootPlacementNode.this);
            data = new LegData[legs.size()];
            Vector3f t = new Vector3f();
            for (int i = 0; i < data.length; i++) {
                Leg leg = legs.get(i);
                LegData d = data[i] = new LegData();
                d.fk = bone(skeleton, leg.fkFootBone, CgFootPlacementNode.this);
                d.ik = bone(skeleton, leg.ikFootBone, CgFootPlacementNode.this);
                d.ball = bone(skeleton, leg.ballBone, CgFootPlacementNode.this);
                // FindChainLengthRootBoneIndex: the ref pose's bone lengths up to the hip
                int b = d.fk;
                for (int n = Math.max(leg.bonesInLimb, 1); n > 0 && skeleton.parent(b) >= 0; n--) {
                    d.limbLength += skeleton.restTranslation(b, t).length();
                    b = skeleton.parent(b);
                }
                d.hip = b;
                d.footLength = skeleton.restTranslation(d.ball, t).length();
            }
        }

        @Override
        protected void initialize(CgAnimGraphInstance g) {
            firstUpdate = true;
            pelvisOffset.zero();
            pelvisSpring.reset();
            for (LegData d : data) {
                d.unalignedOffset.identity();
                d.separatingOffset.zero();
                d.separatingSpring.reset();
                d.plantTranslationSpring.reset();
                d.plantRotationSpring.reset();
                d.groundRotationSpring.reset();
                d.groundHeightSpring.reset();
                d.swept = false;
            }
        }

        @Override
        protected boolean hasPreUpdate() {
            return true;
        }

        /** The game thread's sweeps: each foot from where its unaligned foot stood last frame. */
        @Override
        protected void preUpdate(CgAnimGraphInstance g) {
            CgAnimGround ground = g.ground();
            for (LegData d : data) {
                d.swept = !firstUpdate && trace.enabled;
                if (!d.swept) continue;
                approach(g, approachWS);
                Vector3d start = d0.set(d.unalignedPosWS).fma(trace.startOffset, approachWS);
                d.sweepFound = ground.sweep(start.x, start.y, start.z, approachWS.x, approachWS.y, approachWS.z,
                        trace.endOffset - trace.startOffset, trace.sweepRadius, d.sweepPoint, d.sweepNormal);
            }
            onGroundNow = ground.onGround();
            hasFloor = ground.floor(floorPoint, floorNormal);
            ground.velocity(velocity);
        }

        private boolean onGroundNow = true, hasFloor;

        /** The component's down, in the world: its turn applied to -Y. */
        private Vector3f approach(CgAnimGraphInstance g, Vector3f out) {
            Matrix4f r = m3.set(g.placement());
            return r.transformDirection(out.set(0, -1, 0)).normalize();
        }

        @Override
        protected void update(CgAnimGraphInstance g, float dt) {
            cachedDelta += dt;
        }

        @Override
        protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
            float dt = cachedDelta;
            // the context: the component's turn and scale, its origin in doubles, the approach in both spaces
            ctw.set(g.placement()).setTranslation(0f, 0f, 0f);
            ctw.invertAffine(wtc);
            componentPos.set(g.placement().m30(), g.placement().m31(), g.placement().m32());
            ctw.transformDirection(approachWS.set(approachCS)).normalize();
            // GatherPelvisDataFromInputs
            pelvisFk.set(pose.component(pelvisIndex));
            ikRoot.set(pose.component(ikRootIndex));
            rootToComponent.set(pose.component(0));
            for (LegData d : data) gather(pose, d, dt);
            processCharacterState(dt);
            footMidpoint.zero();
            for (LegData d : data) footMidpoint.add(d.foot.getTranslation(v0).div(data.length));
            for (LegData d : data) align(d, dt);
            Matrix4f pelvisCS = solvePelvis(m0);
            if (pelvis.interpolation) {
                // in root space: the spring follows the root, not the component
                Matrix4f targetRS = m1.set(rootToComponent).invertAffine().mul(pelvisCS);
                Vector3f pelvisRS = m2.set(rootToComponent).invertAffine().transformPosition(pelvisFk.getTranslation(v0));
                Vector3f desired = targetRS.getTranslation(v1).sub(pelvisRS);
                if (desired.lengthSquared() > pelvis.maxOffset * pelvis.maxOffset) desired.normalize(pelvis.maxOffset);
                pelvisSpring.interp(pelvisOffset, desired, pelvis.linearStiffness, pelvis.linearDamping, dt, pelvisOffset);
                targetRS.setTranslation(pelvisRS.add(pelvisOffset));
                rootToComponent.mul(targetRS, pelvisCS);
            }
            int n = 0;
            bones[n] = pelvisIndex;
            out[n++].set(pelvisCS);
            for (LegData d : data) {
                finalizeFoot(d, pelvisCS, out[n]);
                bones[n++] = d.ik;
            }
            sort(bones, out, n);
            cachedDelta = 0f;
            lastComponentPos.set(componentPos);
            firstUpdate = false;
            return n;
        }

        /** {@code GatherLegDataFromInputs}. */
        private void gather(CgAnimPose pose, LegData d, float dt) {
            d.lastBall.set(d.ballTm.getTranslation(v0));
            d.footFk.set(pose.component(d.fk));
            Matrix4f ballCS = m1.set(pose.component(d.ball));
            d.foot.set(pose.component(d.ik));
            d.hipTm.set(pose.component(d.hip));
            // BallToFoot = FootFK relative to the ball; FootToBall the reverse
            ballCS.invertAffine(d.ballToFoot).mul(d.footFk);
            d.footFk.invertAffine(d.footToBall).mul(ballCS);
            d.foot.mul(d.footToBall, d.ballTm);
            if (firstUpdate) {
                Matrix4f invRoot = m2.set(rootToComponent).invertAffine();
                invRoot.mul(d.foot, d.alignedRS);
                d.unalignedRS.set(d.alignedRS);
                toWorld(d.foot, d.alignedPosWS, d.alignedRotWS);
                d.unalignedPosWS.set(d.alignedPosWS);
                d.unalignedRotWS.set(d.alignedRotWS);
                Vector3f rootRS = invRoot.transformPosition(ikRoot.getTranslation(v0));
                d.planeNormalRS.set(approachCS).negate();
                d.planeWRS = d.planeNormalRS.dot(rootRS);
                d.plantType = d.lastPlantType = 0;
                d.lastBall.set(d.ballTm.getTranslation(v0));
            }
            // Graph mode: the ball's own speed
            float moved = d.ballTm.getTranslation(v0).distance(d.lastBall);
            d.speed = dt > 0f ? moved / dt : 0f;
            d.distanceToPlant = targetPlantPlaneDistance(d);
            float range = plant.unalignmentSpeedThreshold - plant.speedThreshold;
            float pct = range != 0f ? (d.speed - plant.unalignmentSpeedThreshold) / (plant.speedThreshold - plant.unalignmentSpeedThreshold) : 0f;
            d.alignmentAlpha = Math.max(0f, Math.min(1f, pct));
        }

        /** {@code CalcTargetPlantPlaneDistance}: the nearer of the foot and the ball to the IK root's plane. */
        private float targetPlantPlaneDistance(LegData d) {
            Vector3f n = ikRoot.transformDirection(v1.set(0, 1, 0)).normalize();
            float w = n.dot(ikRoot.getTranslation(v2));
            float foot = distanceAlong(d.foot.getTranslation(v3), n, w, approachCS);
            float ball = distanceAlong(d.ballTm.getTranslation(v4), n, w, approachCS);
            return Math.min(foot, ball);
        }

        /** {@code ProcessCharacterState}. */
        private void processCharacterState(float dt) {
            if (firstUpdate) {
                lastComponentPos.set(componentPos);
                smoothGroundNormal.set(approachWS).negate();
                groundNormalSpring.reset();
            }
            boolean wasOnGround = onGround;
            onGround = onGroundNow;
            moveDelta.zero();
            boolean grounded = !pelvis.disableOffsetInAir || (onGround && wasOnGround);
            if (grounded && pelvis.compensation != MovementCompensation.COMPONENT_SPACE) {
                // positions relative to this frame's origin: the last one is the move back
                Vector3f last = v0.set((float) (lastComponentPos.x - componentPos.x), (float) (lastComponentPos.y - componentPos.y),
                        (float) (lastComponentPos.z - componentPos.z));
                Vector3f adjustedLast;
                if (pelvis.compensation == MovementCompensation.SUDDEN_MOTION_ONLY) {
                    Vector3f floorN = hasFloor ? floorNormal : v1.set(approachWS).negate();
                    CgIkMath.findBetweenNormals(smoothGroundNormal, floorN, q0);
                    groundNormalSpring.interp(q1.identity(), q0, interp.floorAngularStiffness, 1f, dt, q0);
                    q0.transform(smoothGroundNormal).normalize();
                    adjustedLast = Math.abs(approachWS.dot(smoothGroundNormal)) > 1e-5f
                            ? planeIntersection(v2.zero(), approachWS, smoothGroundNormal, smoothGroundNormal.dot(last), v2)
                            : v2.zero();
                } else {
                    adjustedLast = v2.set(last);
                }
                // (location - adjusted last) times -approach, componentwise: the move along the up axis
                Vector3f moveWS = v3.set(adjustedLast).negate().mul(-approachWS.x, -approachWS.y, -approachWS.z);
                moveDelta.sub(moveWS);
                if (moveWS.lengthSquared() > 1e-8f) {
                    Vector3f moveCS = wtc.transformDirection(v4.set(moveWS));
                    pelvisOffset.sub(moveCS);
                    Vector3f moveRS = m1.set(rootToComponent).invertAffine().transformDirection(v1.set(moveCS));
                    for (LegData d : data) d.planeWRS -= d.planeNormalRS.dot(moveRS);
                }
            }
            moveDelta.add((float) (componentPos.x - lastComponentPos.x), (float) (componentPos.y - lastComponentPos.y),
                    (float) (componentPos.z - lastComponentPos.z));
        }

        /** {@code ProcessFootAlignment}. */
        private void align(LegData d, float dt) {
            if (plant.reconstructWorldPlantFromVelocity) {
                // back by the character's velocity, from where root space put them
                fromRoot(d.alignedRS, d.alignedPosWS, d.alignedRotWS);
                d.alignedPosWS.fma(-dt, velocity);
                fromRoot(d.unalignedRS, d.unalignedPosWS, d.unalignedRotWS);
                d.unalignedPosWS.fma(-dt, velocity);
            }
            Matrix4f inputRS = m1.set(rootToComponent).invertAffine().mul(d.foot);
            d.lastPlantType = d.plantType;
            determinePlantType(d);
            boolean planted = d.plantType != 0;
            if (planted) {
                Matrix4f plantedCS = m2;
                switch (plant.lockType) {
                    case UNLOCKED -> plantedCS.set(d.foot);
                    case PIVOT_AROUND_BALL -> {
                        // the ball pinned where the last plant put it, the foot turning about it
                        Vector3f pinnedBall = v0;
                        worldToCS(d.unalignedPosWS, d.unalignedRotWS, m3);
                        m3.mul(d.footToBall).getTranslation(pinnedBall);
                        Matrix4f ball = m3.set(d.ballTm).setTranslation(pinnedBall);
                        ball.mul(d.ballToFoot, plantedCS);
                    }
                    case PIVOT_AROUND_ANKLE -> {
                        plantedCS.set(d.foot);
                        worldToCS(d.unalignedPosWS, d.unalignedRotWS, m3);
                        plantedCS.setTranslation(m3.getTranslation(v0));
                    }
                    case LOCK_ROTATION -> worldToCS(d.unalignedPosWS, d.unalignedRotWS, plantedCS);
                }
                Matrix4f plantedRS = m3.set(rootToComponent).invertAffine().mul(plantedCS);
                // the planted foot kept at the input's height along the approach
                Vector3f p = plantedRS.getTranslation(v0), inputP = inputRS.getTranslation(v1);
                p.fma(-approachCS.dot(v2.set(p).sub(inputP)), approachCS);
                plantedRS.setTranslation(p);
                // UnalignedFootOffset = Planted relative to the input: input^-1 * planted
                m2.set(inputRS).invertAffine().mul(plantedRS, d.unalignedOffset);
                d.plantTranslationSpring.reset();
                d.plantRotationSpring.reset();
                d.timeSinceFullyUnaligned = 0f;
            } else {
                // the offset sprung back to nothing
                Vector3f t = d.unalignedOffset.getTranslation(v0);
                d.plantTranslationSpring.interp(t, v1.zero(), interp.unplantLinearStiffness, interp.unplantLinearDamping, dt, t);
                Quaternionf r = d.unalignedOffset.getNormalizedRotation(q0);
                d.plantRotationSpring.interp(r, q1.identity(), interp.unplantAngularStiffness, interp.unplantAngularDamping, dt, r);
                d.unalignedOffset.translationRotate(t.x, t.y, t.z, r);
                if (d.timeSinceFullyUnaligned > 0f || Math.abs(d.alignmentAlpha) < 1e-8f) d.timeSinceFullyUnaligned += dt;
            }
            if (plant.replantRadiusRatio >= 1f) {
                Vector3f t = d.unalignedOffset.getTranslation(v0);
                if (t.length() > plant.unplantRadius) d.unalignedOffset.setTranslation(t.normalize(plant.unplantRadius));
            }
            if (plant.replantAngleRatio >= 1f) {
                Quaternionf r = d.unalignedOffset.getNormalizedRotation(q0);
                if (r.w < 0f) r.set(-r.x, -r.y, -r.z, -r.w);
                float angle = 2f * (float) Math.acos(Math.min(1f, r.w)), max = (float) Math.toRadians(plant.unplantAngle);
                if (Math.abs(angle) > max) {
                    float s = (float) Math.sqrt(Math.max(0f, 1f - r.w * r.w));
                    if (s > 1e-6f) r.fromAxisAngleRad(r.x / s, r.y / s, r.z / s, max);
                }
                Vector3f t = d.unalignedOffset.getTranslation(v1);
                d.unalignedOffset.translationRotate(t.x, t.y, t.z, r);
            }
            Matrix4f unalignedRS = inputRS.mul(d.unalignedOffset, m2);
            if (plant.separatingDistance > 0f) {
                Vector3f footP = unalignedRS.getTranslation(v0);
                Vector3f normal = d.foot.getTranslation(v1).sub(footMidpoint);
                normal.y = 0f;   // GetSafeNormal2D: on the ground plane, +Y up
                if (normal.lengthSquared() > 1e-8f) normal.normalize(); else normal.zero();
                Vector3f center = v2.set(footMidpoint).fma(plant.separatingDistance, normal);
                float dist = distanceAlong(footP, normal, normal.dot(center), v3.set(normal).negate());
                if (d.plantType == 0) {
                    Vector3f offset = v4.zero();
                    if (dist < 0f) offset.set(normal).mul(-dist).negate();
                    if (interp.separationInterpolation) {
                        d.separatingSpring.interp(d.separatingOffset, offset, interp.floorLinearStiffness, interp.floorLinearDamping, dt, d.separatingOffset);
                    } else {
                        d.separatingOffset.set(offset);
                    }
                    unalignedRS.setTranslation(footP.add(d.separatingOffset));
                } else {
                    d.separatingOffset.zero();
                    d.separatingSpring.reset();
                }
            }
            // no lock-alpha curve: the unaligned foot is its own
            d.unalignedRS.set(unalignedRS);
            worldToCS(d.alignedPosWS, d.alignedRotWS, m3);
            ctw.transformPosition(m3.getTranslation(lastAlignedWS));
            rootToComponent.mul(d.unalignedRS, unalignedCS);
            toWorld(unalignedCS, d.unalignedPosWS, d.unalignedRotWS);
            // the plant plane, from root space into the component's world frame
            planeWorld(d, planeN, planeP);
            ctw.transformPosition(unalignedCS.getTranslation(footWS));
            updatePlantingPlane(d, dt);
            // back into root space
            Matrix4f invRoot = m3.set(rootToComponent).invertAffine();
            Vector3f nCS = wtc.transformDirection(v3.set(planeN)).normalize(), pCS = wtc.transformPosition(v4.set(planeP));
            invRoot.transformDirection(d.planeNormalRS.set(nCS)).normalize();
            d.planeWRS = d.planeNormalRS.dot(invRoot.transformPosition(pCS));
            // AlignPlantToGround, in the component's world frame
            Matrix4f footWorld = m2.set(ctw).mul(unalignedCS);
            alignToGround(d, footWorld);
            Matrix4f alignedCS = wtc.mul(footWorld, m1);
            m3.set(rootToComponent).invertAffine().mul(alignedCS, d.alignedRS);
            toWorld(alignedCS, d.alignedPosWS, d.alignedRotWS);
        }

        /** {@code DeterminePlantType}: planted, replanted or not, by speed, height and how far the plant has drifted. */
        private void determinePlantType(LegData d) {
            boolean wasPlanted = d.plantType != 0, wanted = d.wantsToPlant;
            d.wantsToPlant = plant.lockType != LockType.UNLOCKED && d.distanceToPlant < plant.distanceToGround && d.speed < plant.speedThreshold;
            d.plantType = 0;
            if (!d.wantsToPlant) return;
            // the drift on the ground: the plane across the component's up
            Vector3f fkWS = ctw.transformPosition(d.foot.getTranslation(v0));
            Vector3f lastWS = local(d.alignedPosWS, v1);
            Vector3f drift = lastWS.sub(fkWS);
            float drift2D = drift.x * drift.x + drift.z * drift.z;
            float unplant2 = plant.unplantRadius * plant.unplantRadius, replant2 = unplant2 * plant.replantRadiusRatio * plant.replantRadiusRatio;
            float cosUnplant = (float) Math.cos(Math.toRadians(plant.unplantAngle / 2f));
            float cosReplant = (float) Math.cos(Math.toRadians(plant.unplantAngle * plant.replantAngleRatio / 2f));
            if (wasPlanted) {
                boolean moved = plant.replantRadiusRatio < 1f && drift2D > unplant2;
                boolean turned = plant.replantAngleRatio < 1f && Math.abs(d.twistCorrection.w) < cosUnplant;
                if (!moved && !turned) d.plantType = d.lastPlantType;
            } else if (!wanted) {
                d.plantType = 1;
            } else if (drift2D <= replant2 && Math.abs(d.twistCorrection.w) >= cosReplant) {
                d.plantType = 2;
            }
        }

        /** The plant plane in the component's world frame (origin at the component), from root space. */
        private void planeWorld(LegData d, Vector3f normal, Vector3f point) {
            Vector3f pRS = v4.set(d.planeNormalRS).mul(d.planeWRS);
            Vector3f nCS = rootToComponent.transformDirection(normal.set(d.planeNormalRS)).normalize();
            Vector3f pCS = rootToComponent.transformPosition(point.set(pRS));
            ctw.transformDirection(nCS).normalize();
            ctw.transformPosition(pCS);
        }

        // align's own values, each kept apart from the shared scratch
        private final Vector3f planeN = new Vector3f(), planeP = new Vector3f(), footWS = new Vector3f(), lastAlignedWS = new Vector3f(),
                impact = new Vector3f(), floorLocal = new Vector3f(), curr = new Vector3f(), lastI = new Vector3f(),
                prevI = new Vector3f(), lastN = new Vector3f(), lastP = new Vector3f();
        private final Matrix4f unalignedCS = new Matrix4f();
        private final Vector3f s0 = new Vector3f(), s1 = new Vector3f(), s2 = new Vector3f(), s3 = new Vector3f();

        /**
         * {@code UpdatePlantingPlaneInterpolation}: the sweep's plane, sprung in height and in turn. Reads and writes
         * {@link #planeN} and {@link #planeP}, from {@link #footWS} and {@link #lastAlignedWS}.
         */
        private void updatePlantingPlane(LegData d, float dt) {
            lastN.set(planeN);
            lastP.set(planeP);
            // FindPlantTraceImpact: the sweep's walkable hit, else the floor's plane under the foot, facing up the approach
            boolean found = d.swept && d.sweepFound;
            if (found) {
                local(d.sweepPoint, impact);
                planeN.set(d.sweepNormal);
            } else {
                if (hasFloor) local(floorPoint, floorLocal); else floorLocal.zero();
                planeN.set(approachWS).negate();
                planeIntersection(footWS, approachWS, planeN, planeN.dot(floorLocal), impact);
            }
            planeP.set(impact);
            if (!onGround) {
                // in the air: the IK root's plane
                ctw.transformPosition(ikRoot.getTranslation(planeP));
                planeN.set(approachWS).negate();
            }
            if (interp.floorInterpolation && !firstUpdate) {
                planeIntersection(footWS, approachWS, planeN, planeN.dot(planeP), curr);
                planeIntersection(lastAlignedWS, approachWS, lastN, lastN.dot(lastP), lastI);
                planeIntersection(footWS, approachWS, lastN, lastN.dot(lastP), prevI);
                // Unreal's Z is our Y: up
                float lastDelta = lastI.y - curr.y, prevDelta = prevI.y - curr.y;
                float adjustedPrev = Math.abs(lastDelta) < Math.abs(prevDelta) ? lastI.y : prevI.y;
                if (onGround) {
                    float groundDelta = curr.y - adjustedPrev;
                    adjustedPrev += groundDelta > 0f ? Math.min(Math.abs(moveDelta.y), groundDelta) : Math.max(-Math.abs(moveDelta.y), groundDelta);
                }
                curr.y = d.groundHeightSpring.interp(adjustedPrev, curr.y, interp.floorLinearStiffness, interp.floorLinearDamping, dt);
                if (trace.maxGroundPenetration >= 0f && found) {
                    float dist = distanceAlong(curr, planeN, planeN.dot(impact), approachCS);
                    float penetration = -dist - trace.maxGroundPenetration;
                    if (penetration > 0f) curr.fma(-penetration, approachCS);
                }
                CgIkMath.findBetweenNormals(lastN, planeN, q0);
                d.groundRotationSpring.interp(q1.identity(), q0, interp.floorAngularStiffness, interp.floorAngularDamping, dt, q0);
                q0.transform(planeN.set(lastN)).normalize();
                planeP.set(curr);
            }
        }

        /**
         * {@code AlignPlantToGround}: the foot onto the plane ({@link #planeN}, {@link #planeP}) at the input's height above
         * the IK root, turned with the plane.
         */
        private void alignToGround(LegData d, Matrix4f footWorld) {
            Matrix4f inputWS = m3.set(ctw).mul(d.foot);
            Matrix4f rootWS = m1.set(ctw).mul(ikRoot);
            Vector3f rootN = rootWS.transformDirection(v0.set(0, 1, 0)).normalize();
            float rootW = rootN.dot(rootWS.getTranslation(v1));
            float above = distanceAlong(inputWS.getTranslation(v2), rootN, rootW, approachWS);
            Vector3f corrected = planeIntersection(footWorld.getTranslation(v3), approachWS, planeN, planeN.dot(planeP), v3)
                    .fma(-above, approachWS);
            CgIkMath.findBetweenNormals(rootN, planeN, q0);
            Quaternionf aligned = q0.mul(inputWS.getNormalizedRotation(q1), q1);
            Quaternionf current = footWorld.getNormalizedRotation(q2);
            Quaternionf delta = q3.set(aligned).conjugate().mul(current);
            Vector3f refNormal = aligned.transformInverse(v4.set(planeN));
            CgIkMath.swingTwist(delta, refNormal.normalize(), q0, d.twistCorrection);
            Quaternionf alignedRot = aligned.mul(d.twistCorrection, q1);
            Quaternionf toUnaligned = q3.set(alignedRot).conjugate().mul(current);
            Vector3f footToBall = d.footToBall.getTranslation(v4);
            if (footToBall.lengthSquared() > 1e-12f) footToBall.normalize();
            Quaternionf ankleTwist = q2;
            CgIkMath.swingTwist(toUnaligned, footToBall, q0, ankleTwist);
            Quaternionf reduced = q0.identity().slerp(ankleTwist, plant.ankleTwistReduction);
            footWorld.translationRotate(corrected.x, corrected.y, corrected.z, alignedRot.mul(reduced));
        }

        /** {@code SolvePelvis}: the offset that lets every leg reach, between the nearest and the average reach. */
        private Matrix4f solvePelvis(Matrix4f out) {
            Matrix4f rebalanced = m1.set(pelvisFk);
            Vector3f offsetDelta = v3.zero();
            if (pelvis.horizontalRebalancingWeight != 0f) {
                Vector3f average = v0.zero();
                for (LegData d : data) {
                    Vector3f aligned = rootToComponent.transformPosition(d.alignedRS.getTranslation(v1));
                    average.add(aligned.sub(d.foot.getTranslation(v2)).div(data.length));
                }
                offsetDelta.set(average).fma(-approachCS.dot(average), approachCS).mul(pelvis.horizontalRebalancingWeight);
                rebalanced.setTranslation(rebalanced.getTranslation(v0).add(offsetDelta));
            }
            float maxMin = Float.MAX_VALUE, desiredMin = Float.MAX_VALUE, desiredAvg = 0f, minMax = -Float.MAX_VALUE;
            for (LegData d : data) {
                Vector3f target = rootToComponent.transformPosition(d.alignedRS.getTranslation(v1));
                offsetRange(d, target, rebalanced);
                desiredAvg += rangeDesired / data.length;
                desiredMin = Math.min(desiredMin, rangeDesired);
                maxMin = Math.min(maxMin, rangeMax);
                minMax = Math.max(minMax, rangeMin);
            }
            float minToAvg = desiredAvg - desiredMin, minToMax = maxMin - desiredMin;
            desiredMin -= 0.0005f;   // Unreal's 0.05 cm
            float divisor = minToAvg + minToMax;
            float offset = Math.abs(divisor) < 1e-8f ? desiredMin : desiredMin + (minToAvg * minToMax) / divisor;
            offset = Math.max(minMax, Math.min(maxMin, offset));
            offsetDelta.fma(-offset, approachCS);
            return out.set(pelvisFk).setTranslation(pelvisFk.getTranslation(v0).add(offsetDelta));
        }

        private float rangeMax, rangeMin, rangeDesired;

        /** {@code FindPelvisOffsetRangeForLimb}: how far the pelvis may move along the approach for this leg to reach. */
        private void offsetRange(LegData d, Vector3f plantTarget, Matrix4f pelvisCS) {
            Matrix4f hipToPelvis = m2.set(pelvisFk).invertAffine().mul(d.hipTm);
            Vector3f hip = m3.set(pelvisCS).mul(hipToPelvis).getTranslation(s0);
            float desiredExt = d.foot.getTranslation(s1).distance(d.hipTm.getTranslation(s2));
            float maxExt = maxExtension(desiredExt, d.limbLength);
            Vector3f up = s1.set(approachCS).negate();
            float planeW = up.dot(plantTarget);
            float hipHeight = up.dot(hip) - planeW;
            Vector3f desiredTarget = r0.set(plantTarget), maxTarget = r1.set(plantTarget);
            if (hipHeight > 0f) {
                Vector3f fkProj = project(d.foot.getTranslation(r2), up, planeW, r2);
                Vector3f hipProj = project(r3.set(hip), up, planeW, r3);
                Vector3f toFk = r4.set(fkProj).sub(plantTarget);
                float toFkDist = toFk.length();
                if (toFkDist > 1e-8f) toFk.div(toFkDist);
                Vector3f lifted = r5.set(plantTarget).fma(Math.min(toFkDist, d.footLength) * pelvis.heelLiftRatio, toFk);
                Vector3f footToHipDir = r6.set(hipProj).sub(lifted);
                if (footToHipDir.lengthSquared() > 1e-16f) footToHipDir.normalize(); else footToHipDir.zero();
                float targetOffset = hipProj.distance(lifted), initialOffset = hipProj.distance(fkProj);
                orthogonalLimit(maxExt, lifted, footToHipDir, targetOffset, hipHeight, initialOffset, maxTarget);
                orthogonalLimit(desiredExt, lifted, footToHipDir, targetOffset, hipHeight, initialOffset, desiredTarget);
            }
            Vector3f lineStart = r2.set(hip).fma(-trace.endOffset, approachCS);
            Vector3f maxAt = sphereDistToLine(maxTarget, maxExt, lineStart, approachCS, r3);
            rangeMax = -approachCS.dot(maxAt.sub(hip));
            Vector3f desiredAt = sphereDistToLine(desiredTarget, desiredExt, lineStart, approachCS, r4);
            rangeDesired = -approachCS.dot(desiredAt.sub(hip));
            float minExt = Math.min(desiredExt, d.limbLength * plant.minExtensionRatio);
            Vector3f minAt = r5.set(desiredTarget).fma(-minExt, approachCS);
            rangeMin = -approachCS.dot(minAt.sub(hip)) - d.distanceToPlant;
        }

        private final Vector3f r0 = new Vector3f(), r1 = new Vector3f(), r2 = new Vector3f(), r3 = new Vector3f(),
                r4 = new Vector3f(), r5 = new Vector3f(), r6 = new Vector3f();

        /** {@code FindPlantLocationAdjustedByOrtogonalLimit}, into {@code out}. */
        private void orthogonalLimit(float legLength, Vector3f foot, Vector3f footToHipDir, float targetOffset, float hipHeight,
                                     float initialOffset, Vector3f out) {
            float minHeight = hipHeight - pelvis.maxOffsetHorizontal;
            float maxFootOffset = (float) Math.sqrt(Math.max(0f, legLength * legLength - minHeight * minHeight));
            float clamped = Math.max(initialOffset, maxFootOffset);
            out.set(foot);
            if (targetOffset > clamped) out.fma(targetOffset - clamped, footToHipDir);
        }

        /** {@code FinalizeFootAlignment}: the aligned foot, its heel lifted or pulled in so the leg never hyperextends. */
        private void finalizeFoot(LegData d, Matrix4f pelvisCS, Matrix4f out) {
            Matrix4f hipToPelvis = m2.set(pelvisFk).invertAffine().mul(d.hipTm);
            Vector3f hip = m3.set(pelvisCS).mul(hipToPelvis).getTranslation(s0);
            Matrix4f foot = rootToComponent.mul(d.alignedRS, out);
            Vector3f ball = m3.set(foot).mul(d.footToBall).getTranslation(s1);
            Vector3f inputHipToFoot = d.foot.getTranslation(s2).sub(d.hipTm.getTranslation(s3));
            Vector3f toFootDir = foot.getTranslation(v0).sub(hip);
            if (toFootDir.lengthSquared() > 1e-16f) toFootDir.normalize(); else toFootDir.zero();
            if (inputHipToFoot.lengthSquared() > 1e-16f && toFootDir.lengthSquared() > 0f) {
                float desiredExt = inputHipToFoot.length(), maxExt = maxExtension(desiredExt, d.limbLength);
                float current = foot.getTranslation(v1).distance(hip);
                float remaining = current - maxExt;
                if (current > maxExt) {
                    boolean isPlanted = d.plantType != 0;
                    if (!isPlanted) d.canReachTarget = false;
                    boolean recentlyUnplanted = !isPlanted && d.timeSinceFullyUnaligned == 0f;
                    if (recentlyUnplanted || (isPlanted && d.canReachTarget) || plant.adjustHeelBeforePlanting) {
                        // the heel lifted toward the hip, the foot turning about its ball
                        float pull = Math.min(d.footLength, remaining) * d.alignmentAlpha;
                        remaining -= pull;
                        Vector3f footP = foot.getTranslation(v1);
                        Vector3f corrected = v2.set(footP).fma(-pull, toFootDir);
                        Vector3f before = v3.set(ball).sub(footP), after = v4.set(ball).sub(corrected);
                        if (before.lengthSquared() > 1e-16f && after.lengthSquared() > 1e-16f) {
                            CgIkMath.findBetweenNormals(before.normalize(), after.normalize(), q0);
                            foot.translationRotate(corrected.x, corrected.y, corrected.z, q0.mul(foot.getNormalizedRotation(q1)).normalize());
                        } else {
                            foot.setTranslation(corrected);
                        }
                    }
                    if (remaining > 0f) {
                        foot.setTranslation(sphereDistToLine(hip, maxExt, foot.getTranslation(v1), toFootDir, v2));
                    }
                } else {
                    d.canReachTarget = true;
                }
            }
            // never below the plant plane: the ball and the foot both held above it
            Vector3f plantN = v0, plantP = v1;
            planeWorld(d, plantN, plantP);
            wtc.transformDirection(plantN).normalize();
            wtc.transformPosition(plantP);
            float planeW = plantN.dot(plantP);
            float footDist = distanceAlong(ball, plantN, planeW, approachCS);
            float ballDist = distanceAlong(foot.getTranslation(v2), plantN, planeW, approachCS);
            float min = Math.min(footDist, ballDist) - Math.min(0f, d.distanceToPlant);
            if (min < 0f) foot.setTranslation(foot.getTranslation(v2).fma(min, approachCS));
            // never closer to the hip than the shortest extension
            float minExt = Math.min(Math.abs(inputHipToFoot.dot(approachCS)), d.limbLength * plant.minExtensionRatio);
            Vector3f hipPlaneP = v3.set(hip).fma(minExt, approachCS);
            float toHipPlane = approachCS.dot(foot.getTranslation(v2).sub(hipPlaneP));
            if (toHipPlane < 0f) foot.setTranslation(foot.getTranslation(v2).fma(-toHipPlane, approachCS));
        }

        private float maxExtension(float desired, float limb) {
            return desired > limb ? desired : desired + (limb - desired) * plant.maxExtensionRatio;
        }

        /** {@code m}, in the component's space, as an absolute world position and rotation. */
        private void toWorld(Matrix4f m, Vector3d pos, Quaternionf rot) {
            Vector3f p = ctw.transformPosition(m.getTranslation(s3));
            pos.set(componentPos).add(p.x, p.y, p.z);
            ctw.getNormalizedRotation(rot).mul(m.getNormalizedRotation(q3)).normalize();
        }

        /** An absolute world position and rotation into the component's space. */
        private Matrix4f worldToCS(Vector3d pos, Quaternionf rot, Matrix4f out) {
            Vector3f p = wtc.transformPosition(local(pos, s3));
            Quaternionf r = wtc.getNormalizedRotation(q3).mul(rot).normalize();
            return out.translationRotate(p.x, p.y, p.z, r);
        }

        /** A root-space transform as an absolute world position and rotation. */
        private void fromRoot(Matrix4f rs, Vector3d pos, Quaternionf rot) {
            toWorld(m3.set(rootToComponent).mul(rs), pos, rot);
        }

        /** An absolute position relative to the component's origin. */
        private Vector3f local(Vector3d p, Vector3f out) {
            return out.set((float) (p.x - componentPos.x), (float) (p.y - componentPos.y), (float) (p.z - componentPos.z));
        }
    }

    /** {@code PointDirectionPlaneIntersection}: {@code point + dir * (w - point.n) / (dir.n)}; the point where parallel. */
    static Vector3f planeIntersection(Vector3f point, Vector3f dir, Vector3f n, float w, Vector3f out) {
        float dn = dir.dot(n);
        float t = Math.abs(dn) > 1e-12f ? (w - point.dot(n)) / dn : 0f;
        return out.set(point).fma(t, dir);
    }

    /** {@code GetDistanceToPlaneAlongDirection}: how far {@code p} stands above the plane against {@code approach}; 0 if parallel. */
    static float distanceAlong(Vector3f p, Vector3f n, float w, Vector3f approach) {
        float dn = approach.dot(n);
        if (Math.abs(dn) < 1e-8f) return 0f;
        // the plane met from p along -approach at p - approach * t; (p - that) . -approach
        float t = (w - p.dot(n)) / -dn;
        return -t * approach.lengthSquared();
    }

    /** {@code FVector::PointPlaneProject}. */
    static Vector3f project(Vector3f p, Vector3f n, float w, Vector3f out) {
        return out.set(p).fma(-(p.dot(n) - w), n);
    }

    /** {@code FMath::SphereDistToLine}: the point on the line nearest the sphere, or where the line enters it. */
    static Vector3f sphereDistToLine(Vector3f origin, float radius, Vector3f lineOrigin, Vector3f dir, Vector3f out) {
        float a = dir.dot(dir);
        float bx = lineOrigin.x - origin.x, by = lineOrigin.y - origin.y, bz = lineOrigin.z - origin.z;
        float b = 2f * (dir.x * bx + dir.y * by + dir.z * bz);
        float c = bx * bx + by * by + bz * bz - radius * radius;
        float disc = b * b - 4f * a * c;
        if (disc >= 0f && a > 1e-12f) {
            float t = (-b + (float) Math.sqrt(disc)) / (2f * a);
            return out.set(lineOrigin).fma(t, dir);
        }
        // no intersection: the point of the sphere's surface nearest the line
        float t = a > 1e-12f ? -(dir.x * bx + dir.y * by + dir.z * bz) / a : 0f;
        float nx = lineOrigin.x + dir.x * t - origin.x, ny = lineOrigin.y + dir.y * t - origin.y, nz = lineOrigin.z + dir.z * t - origin.z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 1e-12f) return out.set(lineOrigin).fma(t, dir);
        return out.set(origin).add(nx * radius / len, ny * radius / len, nz * radius / len);
    }

    /** Bones ascending, as Unreal sorts its bone transforms. */
    private static void sort(int[] bones, Matrix4f[] out, int n) {
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0 && bones[j - 1] > bones[j]; j--) {
                int b = bones[j];
                bones[j] = bones[j - 1];
                bones[j - 1] = b;
                Matrix4f t = out[j];
                out[j] = out[j - 1];
                out[j - 1] = t;
            }
        }
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("foot_placement");
        writeBase(out);
        out.put("pelvis_bone", pelvisBone).put("ik_foot_root_bone", ikFootRootBone);
        List<Object> list = new ArrayList<>(legs.size());
        for (Leg l : legs) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("fk_foot_bone", l.fkFootBone);
            e.put("ik_foot_bone", l.ikFootBone);
            e.put("ball_bone", l.ballBone);
            if (l.bonesInLimb != 2) e.put("bones_in_limb", l.bonesInLimb);
            list.add(e);
        }
        out.put("legs", list);
        if (!plant.equals(Plant.DEFAULT)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("speed_threshold", plant.speedThreshold);
            p.put("distance_to_ground", plant.distanceToGround);
            p.put("lock_type", id(plant.lockType));
            p.put("unplant_radius", plant.unplantRadius);
            p.put("replant_radius_ratio", plant.replantRadiusRatio);
            p.put("unplant_angle", plant.unplantAngle);
            p.put("replant_angle_ratio", plant.replantAngleRatio);
            p.put("max_extension_ratio", plant.maxExtensionRatio);
            p.put("min_extension_ratio", plant.minExtensionRatio);
            p.put("separating_distance", plant.separatingDistance);
            p.put("unalignment_speed_threshold", plant.unalignmentSpeedThreshold);
            p.put("ankle_twist_reduction", plant.ankleTwistReduction);
            p.put("reconstruct_world_plant_from_velocity", plant.reconstructWorldPlantFromVelocity);
            p.put("adjust_heel_before_planting", plant.adjustHeelBeforePlanting);
            out.put("plant", p);
        }
        if (!pelvis.equals(Pelvis.DEFAULT)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("max_offset", pelvis.maxOffset);
            p.put("linear_stiffness", pelvis.linearStiffness);
            p.put("linear_damping", pelvis.linearDamping);
            p.put("horizontal_rebalancing_weight", pelvis.horizontalRebalancingWeight);
            p.put("max_offset_horizontal", pelvis.maxOffsetHorizontal);
            p.put("heel_lift_ratio", pelvis.heelLiftRatio);
            p.put("movement_compensation", id(pelvis.compensation));
            p.put("interpolation", pelvis.interpolation);
            p.put("disable_offset_in_air", pelvis.disableOffsetInAir);
            out.put("pelvis", p);
        }
        if (!interp.equals(Interpolation.DEFAULT)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("unplant_linear_stiffness", interp.unplantLinearStiffness);
            p.put("unplant_linear_damping", interp.unplantLinearDamping);
            p.put("unplant_angular_stiffness", interp.unplantAngularStiffness);
            p.put("unplant_angular_damping", interp.unplantAngularDamping);
            p.put("floor_linear_stiffness", interp.floorLinearStiffness);
            p.put("floor_linear_damping", interp.floorLinearDamping);
            p.put("floor_angular_stiffness", interp.floorAngularStiffness);
            p.put("floor_angular_damping", interp.floorAngularDamping);
            p.put("floor_interpolation", interp.floorInterpolation);
            p.put("separation_interpolation", interp.separationInterpolation);
            out.put("interpolation", p);
        }
        if (!trace.equals(Trace.DEFAULT)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("start_offset", trace.startOffset);
            p.put("end_offset", trace.endOffset);
            p.put("sweep_radius", trace.sweepRadius);
            p.put("max_ground_penetration", trace.maxGroundPenetration);
            p.put("enabled", trace.enabled);
            out.put("trace", p);
        }
    }

    @Override
    public String toString() {
        return "foot_placement(" + legs.size() + " legs)";
    }
}
