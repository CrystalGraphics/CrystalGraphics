package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgBlendListNode;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgAnimDynamicsNode;
import com.crystalgraphics.animation.ik.CgBoneSpace;
import com.crystalgraphics.animation.ik.CgControlAlpha;
import com.crystalgraphics.animation.ik.CgFootPlacementNode;
import com.crystalgraphics.animation.ik.CgIkTarget;
import com.crystalgraphics.animation.ik.CgLegIkNode;
import com.crystalgraphics.animation.ik.CgModifyBoneNode;
import com.crystalgraphics.animation.ik.CgTwoBoneIkNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgVirtualBone;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * IK on Goku and RPG-Core's humanoid, in three rows going back from the station's front.
 *
 * <ul>
 *   <li><b>Feet</b>, a row each: on a slab's edge, a stair and the floor. A foot placement plants
 *       IK feet on the set's blocks and lowers the pelvis, then a leg IK bends each leg to its IK foot.</li>
 *   <li><b>Punches</b>: a wall, and before it each character twice, 0.5 m and 0.25 m from it, punching. Each frame the
 *       game thread reads where the clip puts each fist, and once it would pass the wall a modify bone moves the IK hand
 *       onto the wall's face and a limb IK bends the arm to it.</li>
 * </ul>
 *
 * Neither skeleton has IK bones, so each is given virtual ones ({@link Rig#virtualBones}), following its feet and
 * hands in every clip. AnimDynamics hangs Goku's hair strands and sashes in world space, so turning or swaying him
 * swings them.
 */
final class IkStation implements AnimDemoStation {

    /** Rows of the station, back from its front along -z. */
    private static final double ROW = 5;
    /** The wall's distances from each puncher's feet, in metres. */
    private static final float[] REACH = {0.5f, 0.25f};
    /** How far the fist (from its wrist bone) stands off the wall, and how far before it the IK starts taking it. */
    private static final float FIST = 0.06f, BAND = 0.08f;
    private static final float PUNCH_GAP = 1.0f;

    /**
     * A character's bones as the station needs them: the standard humanoid names, or its own. A
     * {@code root} that is null is bone 0; a {@code ball} that is null is made a virtual bone on the foot, a foot with
     * no toe bone of its own.
     *
     * <p>Leg IK keeps the bend a limb has in its clips, so it cannot bend one its clips hold straight. Such a rig
     * names poles: each limb then takes two-bone IK, its joint bending toward a point that far along the
     * joint bone's own z.</p>
     */
    private record Rig(@Nullable String root, String pelvis, String footL, String footR, @Nullable String ballL, @Nullable String ballR,
                       String handL, String handR, String idle, String punch, float kneePole, float elbowPole) {

        String ball(boolean left) {
            String b = left ? ballL : ballR;
            return b != null ? b : left ? "ball_l" : "ball_r";
        }

        /** The standard IK bones, and the clip's own hands, as virtual bones of {@code skeleton}. */
        List<CgVirtualBone> virtualBones(CgSkeleton skeleton) {
            String root = this.root != null ? this.root : skeleton.name(0);
            List<CgVirtualBone> out = new ArrayList<>(List.of(
                    new CgVirtualBone("ik_foot_root", root, root),
                    new CgVirtualBone("ik_foot_l", "ik_foot_root", footL),
                    new CgVirtualBone("ik_foot_r", "ik_foot_root", footR),
                    new CgVirtualBone("ik_hand_root", root, root),
                    new CgVirtualBone("ik_hand_l", "ik_hand_root", handL),
                    new CgVirtualBone("ik_hand_r", "ik_hand_root", handR),
                    // where the clip puts each hand, which no IK moves: what the punches read
                    new CgVirtualBone("clip_hand_l", "ik_hand_root", handL),
                    new CgVirtualBone("clip_hand_r", "ik_hand_root", handR)));
            if (ballL == null) out.add(new CgVirtualBone("ball_l", footL, footL));
            if (ballR == null) out.add(new CgVirtualBone("ball_r", footR, footR));
            return out;
        }
    }

    private static final Rig STANDARD = new Rig("root", "pelvis", "foot_l", "foot_r", "ball_l", "ball_r", "hand_l", "hand_r",
            "FLD_Stand", "FLD_Punch", 0f, 0f);
    // its legs hang off "model", the body's pivot: that is its pelvis
    private static final Rig HUMANOID = new Rig(null, "model", "left_foot_locator", "right_foot_locator", null, null,
            "left_hand_locator", "right_hand_locator", "vanilla/idle", "melee/melee_0",
            // its clips hold its legs and arms straight; it faces -z: knees bend forward, elbows back
            -0.4f, 0.4f);

    private final CgAnimVariable<Boolean> ik = CgAnimVariable.bool("ik", true), dynamics = CgAnimVariable.bool("dynamics", true);
    private final List<Stand> stands = new ArrayList<>();
    private final List<Puncher> punchers = new ArrayList<>();
    private AnimDemoSet set;
    private double groundY;
    private float turn;
    private boolean sway, ikOn = true, dynamicsOn = true;
    private float time;

    /** One body standing at {@code baseY} on the set's blocks. */
    private final class Stand implements CgAnimGround {
        final AnimDemoCast.Body body;
        final double x, baseY, z;

        Stand(AnimDemoCast.Body body, double x, double baseY, double z) {
            this.body = body;
            this.x = x;
            this.baseY = baseY;
            this.z = z;
            body.instance.ground(this);
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public boolean floor(Vector3d point, Vector3f normal) {
            point.set(body.x, baseY, body.z);
            normal.set(0f, 1f, 0f);
            return true;
        }

        @Override
        public void velocity(Vector3d out) {
            out.zero();
        }

        /** A ball swept down onto the set's blocks and the ground: the top it first lands on. */
        @Override
        public boolean sweep(double px, double py, double pz, float dx, float dy, float dz, double distance, double radius,
                             Vector3d point, Vector3f normal) {
            if (dy > -0.99f) return false;
            double top = Double.NEGATIVE_INFINITY;
            if (groundY <= py && groundY >= py - distance) top = groundY;
            for (CgAABB b : set.bounds()) {
                if (px + radius < b.minX() || px - radius > b.maxX() || pz + radius < b.minZ() || pz - radius > b.maxZ()) continue;
                if (b.maxY() <= py && b.maxY() >= py - distance) top = Math.max(top, b.maxY());
            }
            if (top == Double.NEGATIVE_INFINITY) return false;
            point.set(px, top, pz);
            normal.set(0f, 1f, 0f);
            return true;
        }
    }

    /** One body punching the wall whose face is at {@code wallZ}, it standing on its +z side facing -z. */
    private static final class Puncher {
        final AnimDemoCast.Body body;
        final double wallZ;
        final float punchSeconds;
        final Hand left, right;
        final CgAnimVariable<Boolean> punching;
        float clock;

        Puncher(AnimDemoCast.Body body, double wallZ, float punchSeconds, CgAnimVariable<Boolean> punching, Hand left, Hand right) {
            this.body = body;
            this.wallZ = wallZ;
            this.punchSeconds = punchSeconds;
            this.punching = punching;
            this.left = left;
            this.right = right;
            body.instance.onUpdate((anim, dt) -> {
                left.aim(this);
                right.aim(this);
            });
        }

        void update(float dt) {
            clock = (clock + dt) % (punchSeconds + PUNCH_GAP);
            body.instance.set(punching, clock < punchSeconds);
        }
    }

    /** One hand of a puncher: the variables its IK hand is moved by, and where its clip's fist was. */
    private static final class Hand {
        final int clipBone;
        final CgAnimVariable<Float> x, y, z, alpha;
        final Matrix4f m = new Matrix4f();
        final Vector3f cs = new Vector3f();
        final Vector3d at = new Vector3d(), last = new Vector3d();
        boolean seen;

        Hand(int clipBone, String side) {
            this.clipBone = clipBone;
            x = CgAnimVariable.floating("hand_" + side + "_x");
            y = CgAnimVariable.floating("hand_" + side + "_y");
            z = CgAnimVariable.floating("hand_" + side + "_z");
            alpha = CgAnimVariable.floating("hand_" + side + "_alpha");
        }

        /**
         * The game thread's trace: the clip's fist from last frame's pose, a frame ahead at its speed, against the wall.
         * The IK hand goes onto the wall's face, taken in over {@link #BAND} before the fist would reach it.
         */
        void aim(Puncher p) {
            p.body.instance.pose().component(clipBone, m).getTranslation(cs);
            p.body.component.worldTransform().transformPosition(at.set(cs.x, cs.y, cs.z));
            double ahead = seen ? at.z + (at.z - last.z) : at.z;
            last.set(at);
            seen = true;
            double contact = p.wallZ + FIST;
            float a = (float) Math.max(0.0, Math.min(1.0, (contact + BAND - ahead) / BAND));
            p.body.instance.set(x, (float) at.x).set(y, (float) at.y).set(z, (float) contact).set(alpha, a);
        }
    }

    @Override
    public String name() {
        return "IK and dynamics";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        this.set = set;
        this.groundY = y;
        ikOn = true;
        dynamicsOn = true;
        List<AnimDemoCast> rigged = new ArrayList<>();
        List<Rig> rigs = new ArrayList<>();
        AnimDemoCast goku = rigged(casts.kitCasts(), STANDARD);
        if (goku != null) {
            rigged.add(goku);
            rigs.add(STANDARD);
        }
        AnimDemoCast humanoid = casts.humanoid() != null ? rigged(List.of(casts.humanoid()), HUMANOID) : null;
        if (humanoid != null) {
            rigged.add(humanoid);
            rigs.add(HUMANOID);
        }
        for (int i = 0; i < rigged.size(); i++) feet(rigged.get(i), rigs.get(i), x, y, z - i * ROW);
        // the wall, its face toward +z, thin so a fist through it shows behind; before it each character at each distance
        double wallZ = z - 2 * ROW;
        set.box(x - 4, y, wallZ - 0.02, x + 4.5, y + 2.5, wallZ, 0xFF8C8C96);
        double px = x - 3;
        for (int i = 0; i < rigged.size(); i++) {
            for (float reach : REACH) {
                punch(rigged.get(i), rigs.get(i), px, y, wallZ, reach);
                px += 1.6;
            }
        }
    }

    /** {@code rig}'s character, the first of {@code casts} with its bones, given its virtual bones; null for none. */
    @Nullable
    private static AnimDemoCast rigged(List<AnimDemoCast> casts, Rig rig) {
        for (AnimDemoCast c : casts) {
            CgSkeleton s = c.mesh.skeleton();
            boolean all = c.clip(rig.idle) != null && c.clip(rig.punch) != null;
            for (String bone : new String[] {rig.root != null ? rig.root : s.name(0), rig.pelvis, rig.footL, rig.footR, rig.handL, rig.handR}) {
                all &= s.indexOf(bone) >= 0;
            }
            if (all) return c.withMesh(c.mesh.withVirtualBones(rig.virtualBones(s)));
        }
        return null;
    }

    /** A slab, one foot off its edge; a stair, a foot on each half; the flat floor: a body on each. */
    private void feet(AnimDemoCast cast, Rig rig, double x, double y, double z) {
        CgAnimGraph graph = feetGraph(cast.mesh.skeleton(), rig, cast.clip(rig.idle));
        set.slab(x - 3, y, z - 0.5, 0xFF9A9A9A);
        set.stairs(x - 0.5, y, z - 0.5, AnimDemoSet.Facing.EAST, 0xFF8A7A66);
        double[][] at = {{x - 2, y + 0.5}, {x, y + 1}, {x + 2.5, y}};
        for (double[] a : at) {
            AnimDemoCast.Body body = cast.spawn().at(a[0], a[1], z, 0f);
            body.instance.graph(graph);
            stands.add(new Stand(body, a[0], a[1], z));
        }
    }

    private CgAnimGraph feetGraph(CgSkeleton skeleton, Rig rig, AnimDemoCast.Clip idle) {
        CgAnimNode n = CgSequencePlayerNode.of(idle.sequence()).loop();
        CgControlAlpha ikAlpha = CgControlAlpha.bool(ik, 0.2f, 0.2f);
        n = CgFootPlacementNode.of(n, rig.pelvis, "ik_foot_root")
                .leg(rig.footL, "ik_foot_l", rig.ball(true)).leg(rig.footR, "ik_foot_r", rig.ball(false)).alpha(ikAlpha);
        n = limbs(n, skeleton, "ik_foot_l", rig.footL, "ik_foot_r", rig.footR, rig.kneePole, true, ikAlpha);
        return CgAnimGraph.builder("demo:ik_feet", skeleton).variables(ik, dynamics).output(dynamics(n, skeleton)).build();
    }

    /** A body {@code reach} from the wall at {@code wallZ}, facing it, punching it every so often. */
    private void punch(AnimDemoCast cast, Rig rig, double x, double y, double wallZ, float reach) {
        CgSkeleton s = cast.mesh.skeleton();
        Hand left = new Hand(s.indexOf("clip_hand_l"), "l"), right = new Hand(s.indexOf("clip_hand_r"), "r");
        CgAnimVariable<Boolean> punching = CgAnimVariable.bool("punching");
        AnimDemoCast.Clip punch = cast.clip(rig.punch);
        CgAnimNode n = CgBlendListNode.byBool(punching, CgSequencePlayerNode.of(punch.sequence()),
                CgSequencePlayerNode.of(cast.clip(rig.idle).sequence()).loop()).blendTimes(0.1f, 0.25f).resetOnActivate();
        CgControlAlpha ikAlpha = CgControlAlpha.bool(ik, 0.2f, 0.2f);
        for (Hand h : new Hand[] {left, right}) {
            String bone = h == left ? "ik_hand_l" : "ik_hand_r";
            n = CgModifyBoneNode.of(n, bone).translation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.WORLD, h.x, h.y, h.z)
                    .alpha(CgControlAlpha.of(h.alpha));
        }
        n = limbs(n, s, "ik_hand_l", rig.handL, "ik_hand_r", rig.handR, rig.elbowPole, false, ikAlpha);
        CgAnimGraph graph = CgAnimGraph.builder("demo:ik_punch", s)
                .variables(ik, dynamics, punching, left.x, left.y, left.z, left.alpha, right.x, right.y, right.z, right.alpha)
                .output(dynamics(n, s)).build();
        AnimDemoCast.Body body = cast.spawn().at(x, y, wallZ + reach, (float) Math.PI);
        body.instance.graph(graph);
        punchers.add(new Puncher(body, wallZ, punch.sequence().duration(), punching, left, right));
    }

    /**
     * Both limbs reaching their IK bones: Leg IK, keeping the clip's bend, when {@code pole} is 0; else Two Bone IK, the
     * joint bending toward {@code pole} along the joint bone's own z. A foot's knee twists to the IK foot; a hand's
     * elbow does not.
     */
    private static CgAnimNode limbs(CgAnimNode n, CgSkeleton s, String ikL, String endL, String ikR, String endR, float pole,
                                    boolean feet, CgControlAlpha alpha) {
        if (pole == 0f) {
            return CgLegIkNode.of(n).leg(CgLegIkNode.Leg.of(ikL, endL).kneeTwistCorrection(feet))
                    .leg(CgLegIkNode.Leg.of(ikR, endR).kneeTwistCorrection(feet)).alpha(alpha);
        }
        for (String[] limb : new String[][] {{ikL, endL}, {ikR, endR}}) {
            String joint = s.name(s.parent(s.indexOf(limb[1])));
            n = CgTwoBoneIkNode.of(n, limb[1], CgIkTarget.of(CgBoneSpace.BONE, limb[0], 0f, 0f, 0f),
                    CgIkTarget.of(CgBoneSpace.BONE, joint, 0f, 0f, pole)).rotationFromEffector().alpha(alpha);
        }
        return n;
    }

    /** AnimDynamics on Goku's hair (stiff spikes, a few degrees a joint) and sashes (cloth, swinging freely). */
    private CgAnimNode dynamics(CgAnimNode n, CgSkeleton skeleton) {
        CgControlAlpha dynAlpha = CgControlAlpha.bool(dynamics, 0.2f, 0.2f);
        CgAnimDynamicsNode.BodyDefinition hair = CgAnimDynamicsNode.BodyDefinition.DEFAULT.withBox(0.06f, 0.02f, 0.02f)
                .withJointOffset(0.03f, 0f, 0f).withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-8f, 8f));
        CgAnimDynamicsNode.BodyDefinition sash = CgAnimDynamicsNode.BodyDefinition.DEFAULT.withBox(0.05f, 0.03f, 0.01f)
                .withJointOffset(0.025f, 0f, 0f).withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-35f, 35f));
        for (char strand = 'A'; strand <= 'K'; strand++) {
            String first = "c001p1c01s1_h" + strand + "_01", last = "c001p1c01s1_h" + strand + "_04";
            if (skeleton.indexOf(first) < 0 || skeleton.indexOf(last) < 0) continue;
            n = chain(n, first, last, hair, dynAlpha);
        }
        if (skeleton.indexOf("obi_left_05") >= 0) n = chain(n, "obi_left_01", "obi_left_05", sash, dynAlpha);
        if (skeleton.indexOf("obi_right_06") >= 0) n = chain(n, "obi_right_01", "obi_right_06", sash, dynAlpha);
        return n;
    }

    private static CgAnimNode chain(CgAnimNode n, String first, String last, CgAnimDynamicsNode.BodyDefinition body,
                                    CgControlAlpha alpha) {
        return CgAnimDynamicsNode.of(n, first).chain(last).body(body).space(CgAnimDynamicsNode.SimulationSpace.WORLD).alpha(alpha);
    }

    @Override
    public float[] view() {
        return new float[]{0f, 1.6f, 4.5f, 0f, -10f};
    }

    @Override
    public void update(float dt) {
        time += dt;
        double along = sway ? 0.4 * Math.sin(time * 4.0) : 0.0;
        for (Stand s : stands) s.body.at(s.x, s.baseY, s.z + along, turn);
        for (Puncher p : punchers) p.update(dt);
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (stands.isEmpty() && punchers.isEmpty()) return false;
        switch (key) {
            case CgKeyCodes.KEY_UP -> {
                ikOn = !ikOn;
                for (Stand s : stands) s.body.instance.set(ik, ikOn);
                for (Puncher p : punchers) p.body.instance.set(ik, ikOn);
            }
            case CgKeyCodes.KEY_DOWN -> {
                dynamicsOn = !dynamicsOn;
                for (Stand s : stands) s.body.instance.set(dynamics, dynamicsOn);
                for (Puncher p : punchers) p.body.instance.set(dynamics, dynamicsOn);
            }
            case CgKeyCodes.KEY_LEFT -> turn += (float) (Math.PI / 2);
            case CgKeyCodes.KEY_RIGHT -> turn -= (float) (Math.PI / 2);
            case CgKeyCodes.KEY_BACKSLASH -> sway = !sway;
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (stands.isEmpty()) return "\nNo character with legs loaded";
        return String.format(Locale.ROOT, "%nIK [Up]: %s | Dynamics [Down]: %s%nFeet: turn [Left/Right] | sway [\\]: %s"
                        + "%nRows back: Goku's feet, the humanoid's feet, punching a wall at 0.5 m and 0.25 m",
                ikOn ? "on" : "off", dynamicsOn ? "on" : "off", sway ? "on" : "off");
    }

    @Override
    public void delete() {
        for (Stand s : stands) s.body.remove();
        stands.clear();
        for (Puncher p : punchers) p.body.remove();
        punchers.clear();
    }
}
