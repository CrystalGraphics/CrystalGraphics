package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.crystalgraphics.animation.dynamics.CgAnimPhys;
import com.crystalgraphics.animation.dynamics.CgAnimPhys.Axis;
import com.crystalgraphics.animation.dynamics.CgAnimPhys.Collision;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A bone or a chain of bones simulated as rigid boxes on joints, hanging from the animated pose: Unreal's AnimDynamics
 * ({@code FAnimNode_AnimDynamics}, AnimNode_AnimDynamics.cpp) over its AnimPhysics solver ({@link CgAnimPhys}). Hair,
 * tails, cloth strips, pouches and earrings swing, settle under gravity and lag behind the body's motion, inside the
 * limits each joint is given.
 *
 * <pre>{@code
 * // a strand of hair from hair_01 to hair_04, each joint swinging up to 30 degrees
 * CgAnimDynamicsNode.of(pose, "hair_01").chain("hair_04")
 *         .body(CgAnimDynamicsNode.BodyDefinition.DEFAULT
 *                 .withJointOffset(0f, -0.05f, 0f)
 *                 .withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-30f, 30f)));
 *
 * // a single earring in world space, kept out of the head's sphere
 * CgAnimDynamicsNode.of(pose, "earring_l").space(CgAnimDynamicsNode.SimulationSpace.WORLD)
 *         .spherical(new CgAnimDynamicsNode.SphericalLimit("head", new Vector3f(), 0.12f, CgAnimDynamicsNode.SphereLimitType.OUTER));
 * }</pre>
 *
 * <ul>
 *   <li>The chain's first body is nailed to its animated bone; each body after it hangs from the one before.</li>
 *   <li>A chain bone with no definition of its own takes the only definition given, or the default with several,
 *       as Unreal's do.</li>
 *   <li>Distances are the skeleton's (metres): Unreal's centimetre defaults divided by 100. An angular spring constant
 *       scales with the box's inertia: Unreal's divided by 10,000.</li>
 *   <li>Component space (the default) keeps float precision anywhere in a world; world space trades it for motion that
 *       follows the world, and lags with any teleport.</li>
 *   <li>No wind and no scaled bodies: a bone's scale passes through, its body stays the size defined.</li>
 * </ul>
 */
public final class CgAnimDynamicsNode extends CgSkeletalControlNode {

    /** {@code AnimPhysLinearConstraintType}. */
    public enum LinearLimit { FREE, LIMITED }

    /** {@code AnimPhysAngularConstraintType}. */
    public enum AngularType { ANGULAR, CONE }

    /** {@code AnimPhysSimSpaceType}, without {@code Actor}: a component here is its actor. */
    public enum SimulationSpace { COMPONENT, WORLD, ROOT_RELATIVE, BONE_RELATIVE }

    /** {@code ESphericalLimitType}. */
    public enum SphereLimitType { INNER, OUTER }

    private static final Vector3fc ZERO = new Vector3f();

    /** {@code FAnimPhysConstraintSetup}: how a body moves against the one it hangs from. Angles in degrees. */
    public record ConstraintSetup(LinearLimit linearX, LinearLimit linearY, LinearLimit linearZ, Vector3fc linearMin,
                                  Vector3fc linearMax, AngularType angularType, Axis twistAxis, Axis angularTargetAxis,
                                  float coneAngle, Vector3fc angularMin, Vector3fc angularMax, Vector3fc angularTarget) {
        /** Unreal's: every axis limited to nothing, no turn. A rigid joint. */
        public static final ConstraintSetup DEFAULT = new ConstraintSetup(LinearLimit.LIMITED, LinearLimit.LIMITED, LinearLimit.LIMITED,
                ZERO, ZERO, AngularType.ANGULAR, Axis.X, Axis.X, 0f, ZERO, ZERO, ZERO);

        public ConstraintSetup {
            Objects.requireNonNull(linearX, "linearX");
            Objects.requireNonNull(linearY, "linearY");
            Objects.requireNonNull(linearZ, "linearZ");
            linearMin = new Vector3f(linearMin);
            linearMax = new Vector3f(linearMax);
            Objects.requireNonNull(angularType, "angularType");
            Objects.requireNonNull(twistAxis, "twistAxis");
            Objects.requireNonNull(angularTargetAxis, "angularTargetAxis");
            angularMin = new Vector3f(angularMin);
            angularMax = new Vector3f(angularMax);
            angularTarget = new Vector3f(angularTarget);
        }

        /** Every axis turning from {@code min} to {@code max} degrees. */
        public ConstraintSetup withAngularLimits(float min, float max) {
            return new ConstraintSetup(linearX, linearY, linearZ, linearMin, linearMax, AngularType.ANGULAR, twistAxis,
                    angularTargetAxis, coneAngle, new Vector3f(min), new Vector3f(max), angularTarget);
        }

        /** Each axis turning within its own range, in degrees, twisting about {@code twist}. */
        public ConstraintSetup withAngularLimits(Vector3fc min, Vector3fc max, Axis twist) {
            return new ConstraintSetup(linearX, linearY, linearZ, linearMin, linearMax, AngularType.ANGULAR, twist,
                    angularTargetAxis, coneAngle, min, max, angularTarget);
        }

        /** A cone of {@code degrees} around the bone's X; 0 is a hinge. */
        public ConstraintSetup withCone(float degrees) {
            return new ConstraintSetup(linearX, linearY, linearZ, linearMin, linearMax, AngularType.CONE, twistAxis,
                    angularTargetAxis, degrees, angularMin, angularMax, angularTarget);
        }

        /** The joint sliding from {@code min} to {@code max} on each limited axis. */
        public ConstraintSetup withLinear(LinearLimit x, LinearLimit y, LinearLimit z, Vector3fc min, Vector3fc max) {
            return new ConstraintSetup(x, y, z, min, max, angularType, twistAxis, angularTargetAxis, coneAngle, angularMin,
                    angularMax, angularTarget);
        }

        /** Where an angular spring turns {@code axis} of the body. */
        public ConstraintSetup withAngularTarget(Axis axis, Vector3fc target) {
            return new ConstraintSetup(linearX, linearY, linearZ, linearMin, linearMax, angularType, twistAxis, axis, coneAngle,
                    angularMin, angularMax, target);
        }

        boolean fullyLocked() {
            return linearX != LinearLimit.FREE && linearMin.x() == linearMax.x() && linearY != LinearLimit.FREE
                    && linearMin.y() == linearMax.y() && linearZ != LinearLimit.FREE && linearMin.z() == linearMax.z();
        }
    }

    /** {@code FAnimPhysBodyDefinition}: one bone's box, in metres. {@code bone} null is the node's bound bone. */
    public record BodyDefinition(@Nullable String bone, Vector3fc boxExtents, Vector3fc localJointOffset,
                                 ConstraintSetup constraint, Collision collision, float sphereRadius) {
        /** Unreal's: a 10 cm box on its bone, a rigid joint, colliding at its centre. */
        public static final BodyDefinition DEFAULT = new BodyDefinition(null, new Vector3f(0.1f), ZERO, ConstraintSetup.DEFAULT,
                Collision.COM, 0.1f);

        public BodyDefinition {
            boxExtents = new Vector3f(boxExtents);
            localJointOffset = new Vector3f(localJointOffset);
            Objects.requireNonNull(constraint, "constraint");
            Objects.requireNonNull(collision, "collision");
        }

        public BodyDefinition withBone(String bone) {
            return new BodyDefinition(bone, boxExtents, localJointOffset, constraint, collision, sphereRadius);
        }

        public BodyDefinition withBox(float x, float y, float z) {
            return new BodyDefinition(bone, new Vector3f(x, y, z), localJointOffset, constraint, collision, sphereRadius);
        }

        /** The body's centre from its bone, in the bone's space. */
        public BodyDefinition withJointOffset(float x, float y, float z) {
            return new BodyDefinition(bone, boxExtents, new Vector3f(x, y, z), constraint, collision, sphereRadius);
        }

        public BodyDefinition withConstraint(ConstraintSetup constraint) {
            return new BodyDefinition(bone, boxExtents, localJointOffset, constraint, collision, sphereRadius);
        }

        /** What the limits collide: its centre, a sphere of {@code radius}, or the box's inner or outer sphere. */
        public BodyDefinition withCollision(Collision collision, float radius) {
            return new BodyDefinition(bone, boxExtents, localJointOffset, constraint, collision, radius);
        }
    }

    /** {@code FAnimPhysSphericalLimit}: a sphere at {@code offset} in {@code drivingBone}'s space (the sim space's, null). */
    public record SphericalLimit(@Nullable String drivingBone, Vector3fc offset, float radius, SphereLimitType type) {
        public SphericalLimit {
            offset = new Vector3f(offset);
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * {@code FAnimPhysPlanarLimit}: the bodies kept above a plane through {@code point} facing {@code normal}, in
     * {@code drivingBone}'s space (the sim space's, null). Unreal's plane transform, as its point and its up axis.
     */
    public record PlanarLimit(@Nullable String drivingBone, Vector3fc point, Vector3fc normal) {
        public PlanarLimit {
            point = new Vector3f(point);
            normal = new Vector3f(normal).normalize();
        }
    }

    /**
     * The node's forces and solver: gravity, damping, springs, iterations. A null override takes the solver's own
     * ({@link CgAnimPhys#LINEAR_DAMPING}, {@link CgAnimPhys#JOINT_BIAS}); a null spring constant is no spring.
     */
    public record Physics(float gravityScale, @Nullable Vector3fc gravityOverride, boolean gravityOverrideInSimSpace,
                          @Nullable Float linearDamping, @Nullable Float angularDamping, @Nullable Float angularBias,
                          @Nullable Float linearSpring, @Nullable Float angularSpring, int preIterations, int postIterations,
                          Vector3fc externalForce) {
        /** Unreal's: full gravity, the solver's damping, no springs, 4 and 1 iterations. */
        public static final Physics DEFAULT = new Physics(1f, null, false, null, null, null, null, null, 4, 1, ZERO);

        public Physics {
            gravityOverride = gravityOverride == null ? null : new Vector3f(gravityOverride);
            externalForce = new Vector3f(externalForce);
        }

        public Physics withGravityScale(float scale) {
            return new Physics(scale, gravityOverride, gravityOverrideInSimSpace, linearDamping, angularDamping, angularBias,
                    linearSpring, angularSpring, preIterations, postIterations, externalForce);
        }

        public Physics withDamping(@Nullable Float linear, @Nullable Float angular) {
            return new Physics(gravityScale, gravityOverride, gravityOverrideInSimSpace, linear, angular, angularBias,
                    linearSpring, angularSpring, preIterations, postIterations, externalForce);
        }

        /** Springs pulling each body back to its joint ({@code linear}) and its target ({@code angular}). */
        public Physics withSprings(@Nullable Float linear, @Nullable Float angular) {
            return new Physics(gravityScale, gravityOverride, gravityOverrideInSimSpace, linearDamping, angularDamping,
                    angularBias, linear, angular, preIterations, postIterations, externalForce);
        }
    }

    /** {@code FAnimPhysSimSpaceSettings}: how much of the sim space's own turning the bodies feel. */
    public record SimSpaceSettings(float angularAlpha, float maxAngularVelocity, float maxAngularAcceleration,
                                   Vector3fc externalAngularVelocity) {
        public static final SimSpaceSettings DEFAULT = new SimSpaceSettings(0f, 10000f, 10000f, ZERO);

        public SimSpaceSettings {
            externalAngularVelocity = new Vector3f(externalAngularVelocity);
        }
    }

    /** How much of the component's own motion the bodies feel, outside world space; the clamp in metres a second squared. */
    public record ComponentMotion(Vector3fc linearAccScale, Vector3fc linearVelScale, Vector3fc appliedLinearAccClamp) {
        public static final ComponentMotion DEFAULT = new ComponentMotion(ZERO, ZERO, new Vector3f(1000f));

        public ComponentMotion {
            linearAccScale = new Vector3f(linearAccScale);
            linearVelScale = new Vector3f(linearVelScale);
            appliedLinearAccClamp = new Vector3f(appliedLinearAccClamp);
        }
    }

    /** Reads {@code kind: anim_dynamics}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgAnimDynamicsNode n = of(source(c, at, m), c.string(at, m, "bone")).alpha(alpha(c, at, m));
        if (m.get("chain_end") != null) n = n.chain(c.string(at, m, "chain_end"));
        SimulationSpace space = choice(c, at, m, "simulation_space", SimulationSpace.class, SimulationSpace.COMPONENT);
        n = n.space(space, space == SimulationSpace.BONE_RELATIVE ? c.string(at, m, "relative_space_bone") : null);
        for (String unsupported : List.of("wind", "retargeting")) {
            if (m.get(unsupported) != null) throw new CgAssetException(at + "." + unsupported + ": not supported");
        }
        if (m.get("bodies") instanceof List<?> bodies) {
            for (int i = 0; i < bodies.size(); i++) {
                String a = at + ".bodies[" + i + "]";
                if (!(bodies.get(i) instanceof Map<?, ?> b)) throw new CgAssetException(a + ": a map");
                n = n.body(readBody(c, a, b));
            }
        } else if (m.get("bodies") != null) {
            throw new CgAssetException(at + ".bodies: a list");
        }
        Physics p = Physics.DEFAULT;
        float[] go = vector(c, at, m, "gravity_override", (float[]) null);
        float[] ef = vector(c, at, m, "external_force", 0f, 0f, 0f);
        p = new Physics(number(c, at, m, "gravity_scale", 1f), go == null ? null : vec(go),
                Boolean.TRUE.equals(m.get("gravity_override_in_sim_space")), optional(c, at, m, "linear_damping"),
                optional(c, at, m, "angular_damping"), optional(c, at, m, "angular_bias"), optional(c, at, m, "linear_spring"),
                optional(c, at, m, "angular_spring"), (int) number(c, at, m, "pre_iterations", p.preIterations),
                (int) number(c, at, m, "post_iterations", p.postIterations), vec(ef));
        ComponentMotion cm = ComponentMotion.DEFAULT;
        if (m.get("component_motion") instanceof Map<?, ?> mm) {
            String a = at + ".component_motion";
            cm = new ComponentMotion(vec(vector(c, a, mm, "linear_acc_scale", 0f, 0f, 0f)),
                    vec(vector(c, a, mm, "linear_vel_scale", 0f, 0f, 0f)),
                    vec(vector(c, a, mm, "applied_linear_acc_clamp", 1000f, 1000f, 1000f)));
        }
        SimSpaceSettings ss = SimSpaceSettings.DEFAULT;
        if (m.get("sim_space_settings") instanceof Map<?, ?> sm) {
            String a = at + ".sim_space_settings";
            ss = new SimSpaceSettings(number(c, a, sm, "angular_alpha", 0f), number(c, a, sm, "max_angular_velocity", 10000f),
                    number(c, a, sm, "max_angular_acceleration", 10000f),
                    vec(vector(c, a, sm, "external_angular_velocity", 0f, 0f, 0f)));
        }
        n = n.physics(p).motion(cm, ss);
        if (m.get("spherical_limits") instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                String a = at + ".spherical_limits[" + i + "]";
                if (!(l.get(i) instanceof Map<?, ?> s)) throw new CgAssetException(a + ": a map");
                n = n.spherical(new SphericalLimit(s.get("driving_bone") == null ? null : c.string(a, s, "driving_bone"),
                        vec(vector(c, a, s, "offset", 0f, 0f, 0f)), number(c, a, s, "radius", 0f),
                        choice(c, a, s, "type", SphereLimitType.class, SphereLimitType.OUTER)));
            }
        }
        if (m.get("planar_limits") instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                String a = at + ".planar_limits[" + i + "]";
                if (!(l.get(i) instanceof Map<?, ?> s)) throw new CgAssetException(a + ": a map");
                n = n.planar(new PlanarLimit(s.get("driving_bone") == null ? null : c.string(a, s, "driving_bone"),
                        vec(vector(c, a, s, "point", 0f, 0f, 0f)), vec(vector(c, a, s, "normal", 0f, 1f, 0f))));
            }
        }
        return n;
    };

    private static BodyDefinition readBody(CgAnimNodeKind.Context c, String a, Map<?, ?> b) {
        BodyDefinition d = BodyDefinition.DEFAULT;
        ConstraintSetup k = ConstraintSetup.DEFAULT;
        if (b.get("constraint") instanceof Map<?, ?> cm) {
            String ca = a + ".constraint";
            k = new ConstraintSetup(choice(c, ca, cm, "linear_x", LinearLimit.class, k.linearX),
                    choice(c, ca, cm, "linear_y", LinearLimit.class, k.linearY), choice(c, ca, cm, "linear_z", LinearLimit.class, k.linearZ),
                    vec(vector(c, ca, cm, "linear_min", 0f, 0f, 0f)), vec(vector(c, ca, cm, "linear_max", 0f, 0f, 0f)),
                    choice(c, ca, cm, "angular_type", AngularType.class, k.angularType),
                    choice(c, ca, cm, "twist_axis", Axis.class, k.twistAxis),
                    choice(c, ca, cm, "angular_target_axis", Axis.class, k.angularTargetAxis), number(c, ca, cm, "cone_angle", 0f),
                    vec(vector(c, ca, cm, "angular_min", 0f, 0f, 0f)), vec(vector(c, ca, cm, "angular_max", 0f, 0f, 0f)),
                    vec(vector(c, ca, cm, "angular_target", 0f, 0f, 0f)));
        } else if (b.get("constraint") != null) {
            throw new CgAssetException(a + ".constraint: a map");
        }
        return new BodyDefinition(b.get("bone") == null ? null : c.string(a, b, "bone"),
                vec(vector(c, a, b, "box_extents", 0.1f, 0.1f, 0.1f)), vec(vector(c, a, b, "local_joint_offset", 0f, 0f, 0f)), k,
                choice(c, a, b, "collision", Collision.class, d.collision), number(c, a, b, "sphere_radius", d.sphereRadius));
    }

    private static Vector3f vec(float[] v) {
        return new Vector3f(v[0], v[1], v[2]);
    }

    private static @Nullable Float optional(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key) {
        return m.get(key) == null ? null : number(c, at, m, key, 0f);
    }

    private final String bone;
    private final @Nullable String chainEnd, relativeBone;
    private final SimulationSpace space;
    private final List<BodyDefinition> bodies;
    private final Physics physics;
    private final ComponentMotion componentMotion;
    private final SimSpaceSettings simSpace;
    private final List<SphericalLimit> sphericalLimits;
    private final List<PlanarLimit> planarLimits;

    private CgAnimDynamicsNode(CgAnimNode source, CgControlAlpha alpha, String bone, @Nullable String chainEnd, SimulationSpace space,
                               @Nullable String relativeBone, List<BodyDefinition> bodies, Physics physics, ComponentMotion componentMotion,
                               SimSpaceSettings simSpace, List<SphericalLimit> sphericalLimits, List<PlanarLimit> planarLimits) {
        super(source, alpha);
        this.bone = Objects.requireNonNull(bone, "bone");
        this.chainEnd = chainEnd;
        this.space = Objects.requireNonNull(space, "space");
        if (space == SimulationSpace.BONE_RELATIVE) Objects.requireNonNull(relativeBone, "relativeBone");
        this.relativeBone = space == SimulationSpace.BONE_RELATIVE ? relativeBone : null;
        this.bodies = List.copyOf(bodies);
        this.physics = Objects.requireNonNull(physics, "physics");
        this.componentMotion = Objects.requireNonNull(componentMotion, "componentMotion");
        this.simSpace = Objects.requireNonNull(simSpace, "simSpace");
        this.sphericalLimits = List.copyOf(sphericalLimits);
        this.planarLimits = List.copyOf(planarLimits);
    }

    /** {@code bone} alone, on Unreal's defaults: a rigid 10 cm box in component space. */
    public static CgAnimDynamicsNode of(CgAnimNode source, String bone) {
        return new CgAnimDynamicsNode(source, CgControlAlpha.FULL, bone, null, SimulationSpace.COMPONENT, null, List.of(),
                Physics.DEFAULT, ComponentMotion.DEFAULT, SimSpaceSettings.DEFAULT, List.of(), List.of());
    }

    public CgAnimDynamicsNode alpha(CgControlAlpha alpha) {
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, componentMotion, simSpace,
                sphericalLimits, planarLimits);
    }

    /** Every bone from the bound bone down to {@code end} a body. */
    public CgAnimDynamicsNode chain(String end) {
        return new CgAnimDynamicsNode(source, alpha, bone, end, space, relativeBone, bodies, physics, componentMotion, simSpace,
                sphericalLimits, planarLimits);
    }

    /** The space the bodies move in; {@code relativeBone} for {@link SimulationSpace#BONE_RELATIVE} alone. */
    public CgAnimDynamicsNode space(SimulationSpace space, @Nullable String relativeBone) {
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, componentMotion, simSpace,
                sphericalLimits, planarLimits);
    }

    public CgAnimDynamicsNode space(SimulationSpace space) {
        return space(space, null);
    }

    /** A body's definition: for its {@code bone}, or with none named, the bound bone's (and the chain's, if alone). */
    public CgAnimDynamicsNode body(BodyDefinition body) {
        List<BodyDefinition> next = new ArrayList<>(bodies);
        next.add(Objects.requireNonNull(body, "body"));
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, next, physics, componentMotion, simSpace,
                sphericalLimits, planarLimits);
    }

    public CgAnimDynamicsNode physics(Physics physics) {
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, componentMotion, simSpace,
                sphericalLimits, planarLimits);
    }

    public CgAnimDynamicsNode motion(ComponentMotion component, SimSpaceSettings simSpace) {
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, component, simSpace,
                sphericalLimits, planarLimits);
    }

    public CgAnimDynamicsNode spherical(SphericalLimit limit) {
        List<SphericalLimit> next = new ArrayList<>(sphericalLimits);
        next.add(Objects.requireNonNull(limit, "limit"));
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, componentMotion, simSpace,
                next, planarLimits);
    }

    public CgAnimDynamicsNode planar(PlanarLimit limit) {
        List<PlanarLimit> next = new ArrayList<>(planarLimits);
        next.add(Objects.requireNonNull(limit, "limit"));
        return new CgAnimDynamicsNode(source, alpha, bone, chainEnd, space, relativeBone, bodies, physics, componentMotion, simSpace,
                sphericalLimits, next);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        return new Dynamics(g.skeleton());
    }

    /** The definition of each bone in {@code chain}: {@code UpdateChainPhysicsBodyDefinitions}. */
    private BodyDefinition[] definitions(CgSkeleton skeleton, int[] chain) {
        BodyDefinition prototype = bodies.size() == 1 ? bodies.get(0) : BodyDefinition.DEFAULT;
        BodyDefinition[] out = new BodyDefinition[chain.length];
        for (BodyDefinition d : bodies) {
            int at = d.bone() == null ? 0 : indexIn(chain, skeleton.indexOfIgnoringCase(d.bone()));
            if (at < 0) throw new IllegalArgumentException(this + ": body " + d.bone() + " is not in the chain");
            out[at] = d;
        }
        for (int i = 0; i < out.length; i++) if (out[i] == null) out[i] = prototype;
        return out;
    }

    private static int indexIn(int[] chain, int b) {
        for (int i = 0; i < chain.length; i++) if (chain[i] == b) return i;
        return -1;
    }

    /** The simulation: {@code FAnimNode_AnimDynamics}' runtime state. */
    private final class Dynamics extends Control {
        private final int[] chain;
        private final BodyDefinition[] defs;
        private final CgAnimPhys.Body[] phys;
        private final CgAnimPhys.Spring[] springs;
        private final Vector3f[] body0Offset, body1Offset;
        private final int[] sphereBones, planeBones;
        private final int relative;
        private final CgAnimPhys solver;
        private final float linearSpring, angularSpring, bias;
        private final boolean hasSprings;

        private float nextStep;
        private boolean reset;
        private boolean hasPrevious;
        private final Vector3d prevComponent = new Vector3d();
        private final Vector3f prevComponentVelocity = new Vector3f(), prevSimAngularVelocity = new Vector3f();
        private final Quaterniond prevSimRotation = new Quaterniond();

        // scratch
        private final Matrix4d compToSim = new Matrix4d(), simToComp = new Matrix4d(), simToWorld = new Matrix4d(),
                m = new Matrix4d(), tm = new Matrix4d();
        private final Quaterniond qd = new Quaterniond(), qd2 = new Quaterniond();
        private final Vector3d vd = new Vector3d();
        private final Quaternionf rot = new Quaternionf(), identity = new Quaternionf();
        private final Vector3f pos = new Vector3f(), v0 = new Vector3f(), v1 = new Vector3f(), axis = new Vector3f(),
                gravity = new Vector3f(), externalForce = new Vector3f(), linearAcc = new Vector3f(), angularVel = new Vector3f(),
                angularAcc = new Vector3f(), gravityOverride = new Vector3f(), scale = new Vector3f();

        Dynamics(CgSkeleton skeleton) {
            super(chainEnd == null ? 1 : chain(skeleton, bone, chainEnd, CgAnimDynamicsNode.this).length);
            chain = chainEnd == null ? new int[]{bone(skeleton, bone, CgAnimDynamicsNode.this)}
                    : chain(skeleton, bone, chainEnd, CgAnimDynamicsNode.this);
            defs = definitions(skeleton, chain);
            int n = chain.length;
            phys = new CgAnimPhys.Body[n];
            springs = new CgAnimPhys.Spring[n];
            body0Offset = new Vector3f[n];
            body1Offset = new Vector3f[n];
            for (int i = 0; i < n; i++) {
                springs[i] = new CgAnimPhys.Spring();
                body0Offset[i] = new Vector3f();
                body1Offset[i] = new Vector3f();
            }
            sphereBones = new int[sphericalLimits.size()];
            for (int i = 0; i < sphereBones.length; i++) {
                String d = sphericalLimits.get(i).drivingBone();
                sphereBones[i] = d == null ? -1 : bone(skeleton, d, CgAnimDynamicsNode.this);
            }
            planeBones = new int[planarLimits.size()];
            for (int i = 0; i < planeBones.length; i++) {
                String d = planarLimits.get(i).drivingBone();
                planeBones[i] = d == null ? -1 : bone(skeleton, d, CgAnimDynamicsNode.this);
            }
            relative = relativeBone == null ? -1 : bone(skeleton, relativeBone, CgAnimDynamicsNode.this);
            // per body: up to two limits an axis, then the node's planes and spheres; five angular limits at most
            solver = new CgAnimPhys(n * (6 + planarLimits.size() + sphericalLimits.size()), n * 5);
            linearSpring = physics.linearSpring() == null ? 0f : physics.linearSpring();
            angularSpring = physics.angularSpring() == null ? 0f : physics.angularSpring();
            hasSprings = physics.linearSpring() != null || physics.angularSpring() != null;
            bias = physics.angularBias() == null ? CgAnimPhys.JOINT_BIAS : physics.angularBias();
        }

        @Override
        protected void initialize(CgAnimGraphInstance g) {
            reset = true;
            hasPrevious = false;
            nextStep = 0f;
        }

        @Override
        protected void update(CgAnimGraphInstance g, float dt) {
            nextStep = dt;
        }

        @Override
        protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
            Matrix4dc placement = g.placement();
            spaces(placement, pose);
            if (reset) {
                initPhysics(pose);
                reset = false;
            }
            float dt = Math.min(nextStep, MAX_PHYSICS_DELTA);
            nextStep = 0f;
            if (dt > 0f) {
                simulate(placement, pose, dt);
            }
            for (int i = 0; i < chain.length; i++) {
                CgAnimPhys.Body b = phys[i];
                // the bone sits at the body's centre less its joint offset
                b.orientation.transform(pos.set(defs[i].localJointOffset())).negate().add(b.position);
                m.translationRotate(pos.x, pos.y, pos.z, qd.set(b.orientation));
                simToComp.mul(m, tm);
                pose.component(chain[i]).getScale(scale);
                out[i].set(tm).scale(scale);
                bones[i] = chain[i];
            }
            prevComponent.set(placement.m30(), placement.m31(), placement.m32());
            hasPrevious = true;
            return chain.length;
        }

        /** This frame's sim-space transforms: {@code GetSimToWorldSpaceTransform} and its component-space pair. */
        private void spaces(Matrix4dc placement, CgAnimPose pose) {
            switch (space) {
                case COMPONENT -> {
                    compToSim.identity();
                    simToWorld.set(placement);
                }
                case WORLD -> {
                    compToSim.set(placement);
                    simToWorld.identity();
                }
                case ROOT_RELATIVE, BONE_RELATIVE -> {
                    compToSim.set(pose.component(space == SimulationSpace.ROOT_RELATIVE ? 0 : relative)).invertAffine();
                    simToWorld.set(placement).mul(m.set(pose.component(space == SimulationSpace.ROOT_RELATIVE ? 0 : relative)));
                }
            }
            simToComp.set(compToSim).invertAffine();
        }

        /** {@code b}'s transform in the sim space, into {@link #pos} and {@link #rot}. */
        private void boneInSim(CgAnimPose pose, int b) {
            compToSim.mul(m.set(pose.component(b)), tm);
            tm.getTranslation(vd);
            pos.set((float) vd.x, (float) vd.y, (float) vd.z);
            rot.set(tm.getNormalizedRotation(qd));
        }

        /** A world direction in the sim space, without its scale: {@code TransformWorldVectorToSimSpace}. */
        private Vector3f worldToSim(Vector3f v) {
            if (space == SimulationSpace.WORLD) return v;
            simToWorld.getNormalizedRotation(qd2).conjugate();
            vd.set(v.x, v.y, v.z).rotate(qd2);
            return v.set((float) vd.x, (float) vd.y, (float) vd.z);
        }

        /** The same, with its scale: {@code TransformWorldVectorToSimSpaceScaled}. */
        private Vector3f worldToSimScaled(Vector3f v) {
            if (space == SimulationSpace.WORLD) return v;
            simToWorld.invertAffine(m).transformDirection(vd.set(v.x, v.y, v.z));
            return v.set((float) vd.x, (float) vd.y, (float) vd.z);
        }

        /** {@code InitPhysics} for {@code ResetPhysics}: a body on each bone, at rest where the pose puts it. */
        private void initPhysics(CgAnimPose pose) {
            Vector3f prevPos = v0.zero();
            Quaternionf prevRot = identity.identity();
            for (int i = 0; i < chain.length; i++) {
                BodyDefinition d = defs[i];
                boneInSim(pose, chain[i]);
                pos.add(rot.transform(v1.set(d.localJointOffset())));
                Vector3fc e = d.boxExtents();
                CgAnimPhys.Body b = phys[i] = CgAnimPhys.box(e.x(), e.y(), e.z(), pos);
                b.orientation.set(rot);
                b.previousOrientation.set(rot);
                b.nextOrientation.set(rot);
                b.startOrientation.set(rot);
                b.collision = d.collision();
                b.sphereRadius = switch (d.collision()) {
                    case CUSTOM_SPHERE -> d.sphereRadius();
                    case INNER_SPHERE -> Math.min(Math.abs(e.x()), Math.min(Math.abs(e.y()), Math.abs(e.z()))) / 2f;
                    case OUTER_SPHERE -> Math.max(Math.abs(e.x()), Math.max(Math.abs(e.y()), Math.abs(e.z()))) / 2f;
                    case COM -> 0f;
                };
                if (physics.linearDamping() != null) {
                    b.linearDampingOverridden = true;
                    b.linearDamping = physics.linearDamping();
                }
                if (physics.angularDamping() != null) {
                    b.angularDampingOverridden = true;
                    b.angularDamping = physics.angularDamping();
                }
                b.gravityOverridden = physics.gravityOverride() != null;
                if (i > 0) {
                    // the joint halfway between the two bodies, in each one's space
                    Vector3f half = v1.set(pos).sub(prevPos).mul(0.5f);
                    prevRot.transformInverse(body0Offset[i].set(half));
                    rot.transformInverse(body1Offset[i].set(half).negate());
                } else {
                    body0Offset[i].zero();
                    body1Offset[i].set(d.localJointOffset()).negate();
                }
                prevPos.set(pos);
                prevRot.set(rot);
            }
        }

        /** One step of {@code dt}: the forces of this frame, the limits, the solver. */
        private void simulate(Matrix4dc placement, CgAnimPose pose, float dt) {
            worldToSim(gravity.set(0f, -1f, 0f));
            externalForce.set(physics.externalForce());
            if (externalForce.lengthSquared() > 1e-8f) worldToSim(externalForce);
            spaceMotion(dt);
            linearAcc.zero();
            if (physics.gravityOverride() != null) {
                gravityOverride.set(physics.gravityOverride());
                if (!physics.gravityOverrideInSimSpace() && space != SimulationSpace.WORLD) worldToSim(gravityOverride);
            }
            if (space != SimulationSpace.WORLD) {
                Vector3f velocity = v0.zero();
                if (hasPrevious) velocity.set((float) (placement.m30() - prevComponent.x), (float) (placement.m31() - prevComponent.y),
                        (float) (placement.m32() - prevComponent.z)).div(dt);
                linearAcc.add(worldToSimScaled(v1.set(velocity).negate()).mul(componentMotion.linearVelScale()));
                v1.set(velocity).sub(prevComponentVelocity).div(dt).negate();
                prevComponentVelocity.set(velocity);
                linearAcc.add(worldToSimScaled(v1).mul(componentMotion.linearAccScale()));
                Vector3fc clamp = componentMotion.appliedLinearAccClamp();
                linearAcc.set(Math.max(-clamp.x(), Math.min(clamp.x(), linearAcc.x)),
                        Math.max(-clamp.y(), Math.min(clamp.y(), linearAcc.y)), Math.max(-clamp.z(), Math.min(clamp.z(), linearAcc.z)));
            }
            for (CgAnimPhys.Body b : phys) {
                b.gravityScale = physics.gravityScale();
                if (b.gravityOverridden) b.gravityOverride.set(gravityOverride);
            }
            limits(pose, dt);
            solver.update(dt, phys, phys.length, springs, hasSprings ? phys.length : 0, gravity, externalForce, linearAcc, angularAcc,
                    angularVel, physics.preIterations(), physics.postIterations());
        }

        /** {@code CalculateSimulationSpaceMotion}: the sim space's own turning, felt by the bodies. */
        private void spaceMotion(float dt) {
            angularVel.zero();
            angularAcc.zero();
            if (simSpace.angularAlpha() == 0f || dt < 1e-8f) return;
            if (space == SimulationSpace.WORLD) {
                angularVel.set(simSpace.externalAngularVelocity());
                return;
            }
            Quaterniond current = simToWorld.getNormalizedRotation(qd);
            if (hasPrevious) {
                // Chaos' CalculateAngularVelocity: the shortest turn from last frame's, as an axis times its angle over dt
                current.mul(prevSimRotation.conjugate(), qd2);
                prevSimRotation.conjugate();
                if (qd2.w < 0) qd2.set(-qd2.x, -qd2.y, -qd2.z, -qd2.w);
                double sin = Math.sqrt(qd2.x * qd2.x + qd2.y * qd2.y + qd2.z * qd2.z), angle = 2.0 * Math.atan2(sin, qd2.w);
                if (sin > 1e-12) angularVel.set((float) (qd2.x / sin), (float) (qd2.y / sin), (float) (qd2.z / sin)).mul((float) (angle / dt));
            }
            angularAcc.set(angularVel).sub(prevSimAngularVelocity).div(dt);
            prevSimAngularVelocity.set(angularVel);
            prevSimRotation.set(current);
            if (angularVel.length() > simSpace.maxAngularVelocity()) angularVel.normalize(simSpace.maxAngularVelocity());
            angularVel.add(simSpace.externalAngularVelocity());
            if (angularAcc.length() > simSpace.maxAngularAcceleration()) angularAcc.normalize(simSpace.maxAngularAcceleration());
            worldToSim(angularVel).mul(simSpace.angularAlpha());
            worldToSim(angularAcc).mul(simSpace.angularAlpha());
        }

        /** {@code UpdateLimits}: this step's joints, planes, spheres and springs. */
        private void limits(CgAnimPose pose, float dt) {
            solver.begin();
            for (int i = 0; i < chain.length; i++) {
                ConstraintSetup k = defs[i].constraint();
                CgAnimPhys.Body body = phys[i], prev = i > 0 ? phys[i - 1] : null;
                boneInSim(pose, chain[i]);
                // the joint's frame: the bone's own for the first body, the previous body's for the rest
                Vector3f shapePos = prev == null ? v0.set(pos) : v0.set(body0Offset[i]);
                Quaternionf shapeRot = prev == null ? rot : identity.identity();
                if (k.fullyLocked()) {
                    solver.constrainPositionNailed(dt, prev, shapePos, body, body1Offset[i]);
                } else {
                    if (k.linearX() != LinearLimit.FREE) solver.constrainAlongDirection(dt, prev, shapePos, body, body1Offset[i],
                            shapeRot.transform(axis.set(1f, 0f, 0f)), k.linearMin().x(), k.linearMax().x());
                    if (k.linearY() != LinearLimit.FREE) solver.constrainAlongDirection(dt, prev, shapePos, body, body1Offset[i],
                            shapeRot.transform(axis.set(0f, 1f, 0f)), k.linearMin().y(), k.linearMax().y());
                    if (k.linearZ() != LinearLimit.FREE) solver.constrainAlongDirection(dt, prev, shapePos, body, body1Offset[i],
                            shapeRot.transform(axis.set(0f, 0f, 1f)), k.linearMin().z(), k.linearMax().z());
                }
                if (k.angularType() == AngularType.ANGULAR) {
                    solver.constrainAngularRange(dt, prev, body, shapeRot, k.twistAxis(), k.angularMin(), k.angularMax(), bias);
                } else {
                    solver.constrainConeAngle(dt, prev, rot.transform(axis.set(1f, 0f, 0f)), body, v1.set(1f, 0f, 0f), k.coneAngle(), bias);
                }
                for (int p = 0; p < planarLimits.size(); p++) {
                    PlanarLimit l = planarLimits.get(p);
                    Vector3f point = v1.set(l.point()), normal = axis.set(l.normal());
                    if (planeBones[p] >= 0) {
                        compToSim.mul(m.set(pose.component(planeBones[p])), tm);
                        tm.transformPosition(vd.set(point.x, point.y, point.z));
                        point.set((float) vd.x, (float) vd.y, (float) vd.z);
                        tm.transformDirection(vd.set(normal.x, normal.y, normal.z)).normalize();
                        normal.set((float) vd.x, (float) vd.y, (float) vd.z);
                    }
                    solver.constrainPlanar(dt, body, point, normal);
                }
                for (int s = 0; s < sphericalLimits.size(); s++) {
                    SphericalLimit l = sphericalLimits.get(s);
                    Vector3f centre = v1.set(l.offset());
                    if (sphereBones[s] >= 0) {
                        compToSim.mul(m.set(pose.component(sphereBones[s])), tm);
                        tm.transformPosition(vd.set(centre.x, centre.y, centre.z));
                        centre.set((float) vd.x, (float) vd.y, (float) vd.z);
                    }
                    if (l.type() == SphereLimitType.INNER) solver.constrainSphericalInner(dt, body, centre, l.radius());
                    else solver.constrainSphericalOuter(dt, body, centre, l.radius());
                }
                if (hasSprings) {
                    CgAnimPhys.Spring sp = springs[i];
                    sp.body0 = prev;
                    sp.body1 = body;
                    sp.anchor0.set(shapePos);
                    sp.anchor1.zero();
                    sp.linearConstant = linearSpring;
                    sp.angularConstant = angularSpring;
                    sp.angularTarget.set(k.angularTarget());
                    if (sp.angularTarget.lengthSquared() > 1e-16f) sp.angularTarget.normalize();
                    sp.angularTargetAxis = k.angularTargetAxis();
                    sp.targetOrientationOffset.set(shapeRot);
                    sp.applyLinear = physics.linearSpring() != null;
                    sp.applyAngular = physics.angularSpring() != null;
                }
            }
        }
    }

    /** {@code UPhysicsSettings::MaxPhysicsDeltaTime}'s default: a frame's step is never longer. */
    private static final float MAX_PHYSICS_DELTA = 1f / 30f;

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("anim_dynamics");
        writeBase(out);
        out.put("bone", bone);
        if (chainEnd != null) out.put("chain_end", chainEnd);
        if (space != SimulationSpace.COMPONENT) out.put("simulation_space", id(space));
        if (relativeBone != null) out.put("relative_space_bone", relativeBone);
        if (!bodies.isEmpty()) {
            List<Object> list = new ArrayList<>(bodies.size());
            for (BodyDefinition d : bodies) list.add(bodyTree(d));
            out.put("bodies", list);
        }
        Physics p = physics;
        if (p.gravityScale() != 1f) out.put("gravity_scale", p.gravityScale());
        if (p.gravityOverride() != null) out.put("gravity_override", list(p.gravityOverride()));
        if (p.gravityOverrideInSimSpace()) out.put("gravity_override_in_sim_space", true);
        if (p.linearDamping() != null) out.put("linear_damping", p.linearDamping());
        if (p.angularDamping() != null) out.put("angular_damping", p.angularDamping());
        if (p.angularBias() != null) out.put("angular_bias", p.angularBias());
        if (p.linearSpring() != null) out.put("linear_spring", p.linearSpring());
        if (p.angularSpring() != null) out.put("angular_spring", p.angularSpring());
        if (p.preIterations() != 4) out.put("pre_iterations", p.preIterations());
        if (p.postIterations() != 1) out.put("post_iterations", p.postIterations());
        if (!p.externalForce().equals(ZERO)) out.put("external_force", list(p.externalForce()));
        if (!componentMotion.equals(ComponentMotion.DEFAULT)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("linear_acc_scale", list(componentMotion.linearAccScale()));
            e.put("linear_vel_scale", list(componentMotion.linearVelScale()));
            e.put("applied_linear_acc_clamp", list(componentMotion.appliedLinearAccClamp()));
            out.put("component_motion", e);
        }
        if (!simSpace.equals(SimSpaceSettings.DEFAULT)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("angular_alpha", simSpace.angularAlpha());
            e.put("max_angular_velocity", simSpace.maxAngularVelocity());
            e.put("max_angular_acceleration", simSpace.maxAngularAcceleration());
            e.put("external_angular_velocity", list(simSpace.externalAngularVelocity()));
            out.put("sim_space_settings", e);
        }
        if (!sphericalLimits.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (SphericalLimit l : sphericalLimits) {
                Map<String, Object> e = new LinkedHashMap<>();
                if (l.drivingBone() != null) e.put("driving_bone", l.drivingBone());
                e.put("offset", list(l.offset()));
                e.put("radius", l.radius());
                e.put("type", id(l.type()));
                list.add(e);
            }
            out.put("spherical_limits", list);
        }
        if (!planarLimits.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (PlanarLimit l : planarLimits) {
                Map<String, Object> e = new LinkedHashMap<>();
                if (l.drivingBone() != null) e.put("driving_bone", l.drivingBone());
                e.put("point", list(l.point()));
                e.put("normal", list(l.normal()));
                list.add(e);
            }
            out.put("planar_limits", list);
        }
    }

    private static Map<String, Object> bodyTree(BodyDefinition d) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (d.bone() != null) e.put("bone", d.bone());
        e.put("box_extents", list(d.boxExtents()));
        if (!d.localJointOffset().equals(ZERO)) e.put("local_joint_offset", list(d.localJointOffset()));
        if (!d.constraint().equals(ConstraintSetup.DEFAULT)) {
            ConstraintSetup k = d.constraint();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("linear_x", id(k.linearX()));
            c.put("linear_y", id(k.linearY()));
            c.put("linear_z", id(k.linearZ()));
            c.put("linear_min", list(k.linearMin()));
            c.put("linear_max", list(k.linearMax()));
            c.put("angular_type", id(k.angularType()));
            c.put("twist_axis", id(k.twistAxis()));
            c.put("angular_target_axis", id(k.angularTargetAxis()));
            c.put("cone_angle", k.coneAngle());
            c.put("angular_min", list(k.angularMin()));
            c.put("angular_max", list(k.angularMax()));
            c.put("angular_target", list(k.angularTarget()));
            e.put("constraint", c);
        }
        if (d.collision() != Collision.COM) e.put("collision", id(d.collision()));
        if (d.sphereRadius() != 0.1f) e.put("sphere_radius", d.sphereRadius());
        return e;
    }

    private static List<Float> list(Vector3fc v) {
        return List.of(v.x(), v.y(), v.z());
    }

    @Override
    public String toString() {
        return "anim_dynamics(" + bone + (chainEnd == null ? "" : ".." + chainEnd) + ")";
    }
}
