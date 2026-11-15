package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgAnimDynamicsNode;
import com.crystalgraphics.animation.ik.CgControlAlpha;
import com.crystalgraphics.animation.ik.CgFootPlacementNode;
import com.crystalgraphics.animation.ik.CgLegIkNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgVirtualBone;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Feet on stairs and slabs, hair and sashes that swing and settle: the IK and dynamics nodes on a character with legs.
 * Unreal's own feet setup: a foot placement plants IK feet on the set's blocks and lowers the pelvis, then a leg IK bends
 * each leg to its IK foot, keeping its knee where the clip had it. Goku's skeleton has no IK foot bones, so it is given
 * virtual ones, following his feet in every clip. AnimDynamics hangs every hair strand and both sashes in world space,
 * so turning or swaying a body swings them.
 */
final class IkStation implements AnimDemoStation {

    /** Unreal's mannequin IK bones, as virtual bones: the IK feet follow the feet under an IK root at the root. */
    private static final List<CgVirtualBone> IK_BONES = List.of(
            new CgVirtualBone("ik_foot_root", "root", "root"),
            new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"),
            new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r"));

    private final CgAnimVariable<Boolean> ik = CgAnimVariable.bool("ik", true), dynamics = CgAnimVariable.bool("dynamics", true);
    private final List<Stand> stands = new ArrayList<>();
    private AnimDemoSet set;
    private double groundY;
    private float turn;
    private boolean sway, ikOn = true, dynamicsOn = true;
    private float time;

    /** One body, standing at {@code baseY} on the set's blocks. */
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
        AnimDemoCast legged = legged(casts);
        if (legged == null) return;
        AnimDemoCast cast = legged.withMesh(legged.mesh.withVirtualBones(IK_BONES));
        AnimDemoCast.Clip stand = cast.clip("FLD_Stand");
        if (stand == null && !cast.clips.isEmpty()) stand = cast.clips.get(0);
        if (stand == null) return;
        CgAnimGraph graph = graph(cast.mesh.skeleton(), stand);
        // a slab, one foot off its edge; a stair, a foot on each half; the flat floor
        set.slab(x - 3, y, z - 0.5, 0xFF9A9A9A);
        set.stairs(x - 0.5, y, z - 0.5, AnimDemoSet.Facing.EAST, 0xFF8A7A66);
        double[][] at = {{x - 2, y + 0.5}, {x, y + 1}, {x + 2.5, y}};
        for (double[] a : at) {
            AnimDemoCast.Body body = cast.spawn().at(a[0], a[1], z, 0f);
            body.instance.graph(graph);
            stands.add(new Stand(body, a[0], a[1], z));
        }
    }

    /** The first cast whose skeleton has legs of two bones, and Unreal's leg and pelvis names. */
    @Nullable
    private static AnimDemoCast legged(AnimDemoCasts casts) {
        for (AnimDemoCast c : casts.kitCasts()) {
            CgSkeleton s = c.mesh.skeleton();
            boolean legs = true;
            for (String bone : new String[] {"root", "pelvis", "calf_l", "calf_r", "foot_l", "foot_r", "ball_l", "ball_r"}) {
                legs &= s.indexOf(bone) >= 0;
            }
            if (legs) return c;
        }
        return null;
    }

    private CgAnimGraph graph(CgSkeleton skeleton, AnimDemoCast.Clip clip) {
        CgAnimNode n = CgSequencePlayerNode.of(clip.sequence()).loop();
        CgControlAlpha ikAlpha = CgControlAlpha.bool(ik, 0.2f, 0.2f);
        n = CgFootPlacementNode.of(n, "pelvis", "ik_foot_root").leg("foot_l", "ik_foot_l", "ball_l").leg("foot_r", "ik_foot_r", "ball_r")
                .alpha(ikAlpha);
        n = CgLegIkNode.of(n).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r").alpha(ikAlpha);
        CgControlAlpha dynAlpha = CgControlAlpha.bool(dynamics, 0.2f, 0.2f);
        // hair: stiff spikes, a few degrees a joint; sashes: cloth, swinging freely
        CgAnimDynamicsNode.BodyDefinition hair = CgAnimDynamicsNode.BodyDefinition.DEFAULT.withBox(0.06f, 0.02f, 0.02f)
                .withJointOffset(0.03f, 0f, 0f).withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-8f, 8f));
        CgAnimDynamicsNode.BodyDefinition sash = CgAnimDynamicsNode.BodyDefinition.DEFAULT.withBox(0.05f, 0.03f, 0.01f)
                .withJointOffset(0.025f, 0f, 0f).withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-35f, 35f));
        for (char strand = 'A'; strand <= 'K'; strand++) {
            String first = "c001p1c01s1_h" + strand + "_01", last = "c001p1c01s1_h" + strand + "_04";
            if (skeleton.indexOf(first) < 0 || skeleton.indexOf(last) < 0) continue;
            n = dynamics(n, first, last, hair, dynAlpha);
        }
        if (skeleton.indexOf("obi_left_05") >= 0) n = dynamics(n, "obi_left_01", "obi_left_05", sash, dynAlpha);
        if (skeleton.indexOf("obi_right_06") >= 0) n = dynamics(n, "obi_right_01", "obi_right_06", sash, dynAlpha);
        return CgAnimGraph.builder("demo:ik_station", skeleton).variables(ik, dynamics).output(n).build();
    }

    private static CgAnimNode dynamics(CgAnimNode n, String first, String last, CgAnimDynamicsNode.BodyDefinition body,
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
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (stands.isEmpty()) return false;
        switch (key) {
            case CgKeyCodes.KEY_UP -> {
                ikOn = !ikOn;
                for (Stand s : stands) s.body.instance.set(ik, ikOn);
            }
            case CgKeyCodes.KEY_DOWN -> {
                dynamicsOn = !dynamicsOn;
                for (Stand s : stands) s.body.instance.set(dynamics, dynamicsOn);
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
        if (stands.isEmpty()) return "\nNo character with two-bone legs loaded";
        return String.format(Locale.ROOT, "%nFeet IK [Up]: %s | Dynamics [Down]: %s%nTurn [Left/Right] | Sway [\\]: %s",
                ikOn ? "on" : "off", dynamicsOn ? "on" : "off", sway ? "on" : "off");
    }

    @Override
    public void delete() {
        for (Stand s : stands) s.body.remove();
        stands.clear();
    }
}
