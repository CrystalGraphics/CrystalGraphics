package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgAnimDynamicsNode;
import com.crystalgraphics.animation.ik.CgBoneSpace;
import com.crystalgraphics.animation.ik.CgControlAlpha;
import com.crystalgraphics.animation.ik.CgIkTarget;
import com.crystalgraphics.animation.ik.CgModifyBoneNode;
import com.crystalgraphics.animation.ik.CgTwoBoneIkNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Feet on stairs and slabs, hair and sashes that swing and settle: the IK and dynamics nodes on a character with legs.
 * Each foot is traced down to the set's blocks on the game thread; the pelvis drops to the lower foot and a two-bone IK
 * per leg lands each on its block (the UE4 way, for a skeleton with no IK foot bones). AnimDynamics hangs every hair
 * strand and both sashes in world space, so turning or swaying a body swings them.
 */
final class IkStation implements AnimDemoStation {

    private static final String[] LEGS = {"l", "r"};
    /** Unreal's FInterpTo speed for foot offsets in its IK templates. */
    private static final float INTERP_SPEED = 15f;
    private static final float MAX_STEP = 0.6f;

    private final CgAnimVariable<Boolean> ik = CgAnimVariable.bool("ik", true), dynamics = CgAnimVariable.bool("dynamics", true);
    private final CgAnimVariable<Float> pelvis = CgAnimVariable.floating("pelvis");
    private final CgAnimVariable<Float>[][] target = targets();
    private final List<Stand> stands = new ArrayList<>();
    private AnimDemoSet set;
    private double groundY;
    private float turn;
    private boolean sway, ikOn = true, dynamicsOn = true;
    private float time;

    /** One body, standing at {@code baseY}, and its feet's smoothed offsets. */
    private final class Stand {
        final AnimDemoCast.Body body;
        final double x, baseY, z;
        final float[] offset = new float[2];
        final int[] foot = new int[2];
        private final Matrix4f m = new Matrix4f();
        private final Vector3f p = new Vector3f(), v = new Vector3f();
        private final Vector3d w = new Vector3d();
        private final Quaternionf q = new Quaternionf();

        Stand(AnimDemoCast.Body body, double x, double baseY, double z) {
            this.body = body;
            this.x = x;
            this.baseY = baseY;
            this.z = z;
            CgSkeleton s = body.cast.mesh.skeleton();
            for (int i = 0; i < 2; i++) foot[i] = s.indexOf("foot_" + LEGS[i]);
            body.instance.onUpdate(this::update);
        }

        /** Traces each foot to the blocks under it and hands the pelvis and the legs their targets. */
        void update(CgAnimInstance anim, float dt) {
            float a = Math.min(1f, dt * INTERP_SPEED);
            for (int i = 0; i < 2; i++) {
                anim.pose().component(foot[i], m).getTranslation(p);
                body.component.worldTransform().transformPosition(w.set(p.x, p.y, p.z));
                float hit = (float) (ground(w.x, w.z, baseY + MAX_STEP) - baseY);
                offset[i] += (hit - offset[i]) * a;
            }
            float drop = Math.min(0f, Math.min(offset[0], offset[1]));
            anim.set(pelvis, drop);
            for (int i = 0; i < 2; i++) {
                // the target in the foot's own space: up by its offset less the pelvis' drop, in the component
                anim.pose().component(foot[i], m).getNormalizedRotation(q).conjugate().transform(v.set(0f, offset[i] - drop, 0f));
                anim.set(target[i][0], v.x).set(target[i][1], v.y).set(target[i][2], v.z);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static CgAnimVariable<Float>[][] targets() {
        CgAnimVariable<Float>[][] out = new CgAnimVariable[2][3];
        for (int i = 0; i < 2; i++) {
            for (int c = 0; c < 3; c++) out[i][c] = CgAnimVariable.floating("foot_" + LEGS[i] + "_" + "xyz".charAt(c));
        }
        return out;
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
        AnimDemoCast cast = legged(casts);
        if (cast == null) return;
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

    /** The first cast whose skeleton has legs of two bones. */
    @Nullable
    private static AnimDemoCast legged(AnimDemoCasts casts) {
        for (AnimDemoCast c : casts.kitCasts()) {
            CgSkeleton s = c.mesh.skeleton();
            if (s.indexOf("calf_l") >= 0 && s.indexOf("calf_r") >= 0 && s.indexOf("pelvis") >= 0) return c;
        }
        return null;
    }

    private CgAnimGraph graph(CgSkeleton skeleton, AnimDemoCast.Clip clip) {
        CgAnimNode n = CgSequencePlayerNode.of(clip.sequence()).loop();
        CgControlAlpha ikAlpha = CgControlAlpha.bool(ik, 0.2f, 0.2f);
        n = CgModifyBoneNode.of(n, "pelvis").translation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.COMPONENT, null, pelvis, null)
                .alpha(ikAlpha);
        for (int i = 0; i < 2; i++) {
            String leg = LEGS[i];
            n = CgTwoBoneIkNode.of(n, "foot_" + leg, CgIkTarget.of(CgBoneSpace.BONE, "foot_" + leg, target[i][0], target[i][1], target[i][2]),
                    CgIkTarget.of(CgBoneSpace.BONE, "calf_" + leg, 0f, 0f, 0f)).alpha(ikAlpha);
        }
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
        List<CgAnimVariable<?>> vars = new ArrayList<>(List.of(ik, dynamics, pelvis));
        for (CgAnimVariable<Float>[] t : target) vars.addAll(List.of(t));
        return CgAnimGraph.builder("demo:ik_station", skeleton).variables(vars.toArray(new CgAnimVariable<?>[0])).output(n).build();
    }

    private static CgAnimNode dynamics(CgAnimNode n, String first, String last, CgAnimDynamicsNode.BodyDefinition body,
                                       CgControlAlpha alpha) {
        return CgAnimDynamicsNode.of(n, first).chain(last).body(body).space(CgAnimDynamicsNode.SimulationSpace.WORLD).alpha(alpha);
    }

    /** The top of the highest block under ({@code x}, {@code z}) no higher than {@code below}; the ground with none. */
    private double ground(double x, double z, double below) {
        double top = groundY;
        for (CgAABB b : set.bounds()) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ() && b.maxY() <= below) top = Math.max(top, b.maxY());
        }
        return top;
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
