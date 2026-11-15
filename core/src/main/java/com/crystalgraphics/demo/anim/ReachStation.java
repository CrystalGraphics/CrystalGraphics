package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgBoneSpace;
import com.crystalgraphics.animation.ik.CgControlAlpha;
import com.crystalgraphics.animation.ik.CgIkTarget;
import com.crystalgraphics.animation.ik.CgLookAtNode;
import com.crystalgraphics.animation.ik.CgModifyBoneNode;
import com.crystalgraphics.animation.ik.CgTwoBoneIkNode;
import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every character with a rig in a ring, reaching for an orb flying among them and watching it: arms by two-bone IK to
 * their rig's IK goals, heads by look-at. Each is set up from its rig alone (its arm chains, their bend, its head
 * chain and its adapter's facing), so the same code serves Goku and every RPG-Core body.
 *
 * <ul>
 *   <li>Left/Right: the orb's path (circle, figure eight, bobbing, still); Shift: its speed.</li>
 *   <li>Up: the hands that reach (both, left, right, none). Down: the heads' look-at.</li>
 *   <li>`: each rig over its body, with each limb's bend as an arrow from its joint.</li>
 *   <li>\: the orb held where it is.</li>
 * </ul>
 */
final class ReachStation implements AnimDemoStation {

    private static final double RING = 1.7;
    private static final String[] PATHS = {"circle", "figure eight", "bobbing", "still"};
    private static final String[] HANDS = {"both", "left", "right", "none"};
    private static final CgSphere ORB = CgSphere.of(0.1);

    private final CgAnimVariable<Float> orbX = CgAnimVariable.floating("orb_x"), orbY = CgAnimVariable.floating("orb_y"),
            orbZ = CgAnimVariable.floating("orb_z"), alphaL = CgAnimVariable.floating("reach_l"),
            alphaR = CgAnimVariable.floating("reach_r");
    private final CgAnimVariable<Boolean> look = CgAnimVariable.bool("look", true);
    private final List<Reacher> reachers = new ArrayList<>();
    private final CgPose pose = new CgPose();
    private final Vector3d orb = new Vector3d(), a = new Vector3d(), b = new Vector3d();
    private final Vector3f dir = new Vector3f();
    private double cx, cy, cz;
    private float clock, speed = 1f;
    private int path, hands;
    private boolean looking = true, overlay = true, held;

    /** One body: its rig, its arms' shoulders and reach, and its limbs' bends to draw. */
    private final class Reacher {
        final AnimDemoCast.Body body;
        final AnimDemoRigs.Drawn rig;
        final int shoulderL, shoulderR;
        final float reachL, reachR;
        final Vector3d shoulder = new Vector3d();

        Reacher(AnimDemoCast.Body body, AnimDemoRigs.Drawn rig) {
            this.body = body;
            this.rig = rig;
            CgSkeleton s = body.cast.mesh.skeleton();
            CgRig.Chain l = rig.rig.chain("arm_l"), r = rig.rig.chain("arm_r");
            shoulderL = l == null ? -1 : s.indexOf(l.start());
            shoulderR = r == null ? -1 : s.indexOf(r.start());
            reachL = l == null ? 0f : length(s, l);
            reachR = r == null ? 0f : length(s, r);
            body.instance.onUpdate((anim, dt) -> anim.set(orbX, (float) orb.x).set(orbY, (float) orb.y).set(orbZ, (float) orb.z)
                    .set(alphaL, hands == 0 || hands == 1 ? reach(shoulderL, reachL) : 0f)
                    .set(alphaR, hands == 0 || hands == 2 ? reach(shoulderR, reachR) : 0f)
                    .set(look, looking));
        }

        /** How far the hand reaches for the orb: fully within the arm's length, letting go a third past it. */
        private float reach(int bone, float length) {
            if (bone < 0 || length <= 0f) return 0f;
            double d = rig.world(body, bone, this.shoulder).distance(orb);
            return (float) Math.max(0.0, Math.min(1.0, (length * 1.33 - d) / (length * 0.33)));
        }
    }

    /** A chain's length at rest, start to end. */
    private static float length(CgSkeleton s, CgRig.Chain c) {
        Matrix4f[] rest = rest(s);
        return rest[s.indexOf(c.start())].getTranslation(new Vector3f()).distance(rest[s.indexOf(c.end())].getTranslation(new Vector3f()));
    }

    private static Matrix4f[] rest(CgSkeleton s) {
        Matrix4f[] rest = new Matrix4f[s.boneCount()];
        for (int i = 0; i < rest.length; i++) rest[i] = new Matrix4f();
        return s.restGlobal(rest);
    }

    @Override
    public String name() {
        return "Rig IK";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        cx = x;
        cy = y;
        cz = z - 1;
        List<AnimDemoCast> rigged = new ArrayList<>();
        List<AnimDemoRigs.Drawn> rigs = new ArrayList<>();
        for (AnimDemoCast c : casts.all()) {
            AnimDemoRigs.Drawn rig = AnimDemoRigs.of(c);
            if (rig == null || c.clips.isEmpty()) continue;
            AnimDemoCast withBones = c.withMesh(c.mesh.withVirtualBones(rig.rig.ikBones(c.mesh.skeleton())));
            rigged.add(withBones);
            rigs.add(new AnimDemoRigs.Drawn(rig.rig, withBones.mesh.skeleton()));
        }
        set.box(cx - RING - 0.8, y, cz - RING - 0.8, cx + RING + 0.8, y + 0.05, cz + RING + 0.8, 0xFF39404C);
        for (int i = 0; i < rigged.size(); i++) {
            double angle = Math.PI / 2 + 2 * Math.PI * i / rigged.size();   // the first nearest the camera
            double bx = cx + RING * Math.cos(angle), bz = cz + RING * Math.sin(angle);
            float yaw = (float) Math.atan2(cx - bx, cz - bz);
            AnimDemoCast cast = rigged.get(i);
            AnimDemoCast.Body body = cast.spawn().at(bx, y + 0.05, bz, yaw);
            body.instance.graph(graph(cast, rigs.get(i).rig));
            reachers.add(new Reacher(body, rigs.get(i)));
        }
    }

    /**
     * The cast's idle with its arms reaching for the orb and its head on it, from its rig alone: each arm chain's goal
     * moved onto the orb, the arm bent to it toward its rig's bend (or behind it, where the rig names none), and the
     * head's look axis its adapter's facing at rest.
     */
    private CgAnimGraph graph(AnimDemoCast cast, CgRig rig) {
        CgSkeleton s = cast.mesh.skeleton();
        Matrix4f[] rest = rest(s);
        Vector3fc facing = rig.adapter().facing();
        CgAnimNode n = CgSequencePlayerNode.of(idle(cast).sequence()).loop();
        for (String name : new String[] {"arm_l", "arm_r"}) {
            CgRig.Chain c = rig.chain(name);
            if (c == null || c.goal() == null) continue;
            CgAnimVariable<Float> alpha = name.equals("arm_l") ? alphaL : alphaR;
            n = CgModifyBoneNode.of(n, c.goal()).translation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.WORLD, orbX, orbY, orbZ)
                    .alpha(CgControlAlpha.of(alpha));
            int joint = s.parent(s.indexOf(c.end()));
            Vector3f bend = c.bend() != null ? new Vector3f(c.bend()) : new Vector3f(facing).negate();
            Vector3f pole = rest[joint].getTranslation(new Vector3f()).fma(0.5f, bend);
            n = CgTwoBoneIkNode.of(n, c.end(), CgIkTarget.of(CgBoneSpace.BONE, c.goal(), 0f, 0f, 0f),
                            CgIkTarget.of(CgBoneSpace.COMPONENT, null, pole.x, pole.y, pole.z))
                    .rotationFromEffector().alpha(CgControlAlpha.of(alpha));
        }
        CgRig.Chain head = rig.chain("head");
        if (head != null) {
            Quaternionf inv = rest[s.indexOf(head.end())].getNormalizedRotation(new Quaternionf()).conjugate();
            Vector3f aim = inv.transform(new Vector3f(facing)), up = inv.transform(new Vector3f(0f, 1f, 0f));
            n = CgLookAtNode.of(n, head.end(), CgIkTarget.world(orbX, orbY, orbZ)).lookAtAxis(aim.x, aim.y, aim.z)
                    .lookUpAxis(up.x, up.y, up.z).clamp(70f).alpha(CgControlAlpha.bool(look, 0.25f, 0.25f));
        }
        return CgAnimGraph.builder("demo:reach", s).variables(orbX, orbY, orbZ, alphaL, alphaR, look).output(n).build();
    }

    private static AnimDemoCast.Clip idle(AnimDemoCast cast) {
        for (String name : new String[] {"vanilla/idle", "FLD_Stand", "idle"}) {
            AnimDemoCast.Clip c = cast.clip(name);
            if (c != null) return c;
        }
        return cast.clips.get(0);
    }

    @Override
    public float[] view() {
        return new float[]{0f, 2.2f, 4.5f, 0f, -18f};
    }

    @Override
    public void update(float dt) {
        if (reachers.isEmpty()) return;
        if (!held) clock += dt * speed;
        double t = clock;
        switch (path) {
            case 0 -> orb.set(cx + 1.0 * Math.cos(t), cy + 1.3 + 0.25 * Math.sin(2 * t), cz + 1.0 * Math.sin(t));
            case 1 -> orb.set(cx + 1.3 * Math.sin(t), cy + 1.3 + 0.35 * Math.sin(3 * t), cz + 0.8 * Math.sin(2 * t));
            case 2 -> orb.set(cx + 0.7 * Math.cos(0.5 * t), cy + 1.2 + 0.7 * Math.sin(2.5 * t), cz + 0.7 * Math.sin(0.5 * t));
            default -> orb.set(cx, cy + 1.3, cz + RING * 0.45);
        }
        CgCollisionDebug.shape(ORB, pose.at(orb.x, orb.y, orb.z), 0xFFFFE070);
        if (!overlay) return;
        for (Reacher r : reachers) {
            r.rig.draw(r.body);
            CgSkeleton s = r.body.cast.mesh.skeleton();
            for (CgRig.Chain c : r.rig.rig.chains()) {
                if (c.bend() == null) continue;
                int joint = s.parent(s.indexOf(c.end()));
                r.rig.world(r.body, joint, a);
                r.body.component.worldTransform().transformDirection(dir.set(c.bend()));
                b.set(a).add(dir.x * 0.3, dir.y * 0.3, dir.z * 0.3);
                CgCollisionDebug.line(a.x, a.y, a.z, b.x, b.y, b.z, 0xFFFFFF60);
            }
            if (looking) {
                CgRig.Chain head = r.rig.rig.chain("head");
                if (head != null) {
                    r.rig.world(r.body, s.indexOf(head.end()), a);
                    CgCollisionDebug.line(a.x, a.y, a.z, orb.x, orb.y, orb.z, 0x40FFE070);
                }
            }
        }
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (reachers.isEmpty()) return false;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT -> {
                if (shift) speed = Math.min(3f, speed + 0.25f); else path = (path + 1) % PATHS.length;
            }
            case CgKeyCodes.KEY_LEFT -> {
                if (shift) speed = Math.max(0.25f, speed - 0.25f); else path = Math.floorMod(path - 1, PATHS.length);
            }
            case CgKeyCodes.KEY_UP -> hands = (hands + 1) % HANDS.length;
            case CgKeyCodes.KEY_DOWN -> looking = !looking;
            case CgKeyCodes.KEY_GRAVE -> overlay = !overlay;
            case CgKeyCodes.KEY_BACKSLASH -> held = !held;
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (reachers.isEmpty()) return "\nNo character with a rig loaded";
        return String.format(Locale.ROOT, "%nOrb [Left/Right]: %s, speed [Shift] x%.2f | held [\\]: %s"
                        + "%nHands reaching [Up]: %s | Heads looking [Down]: %s | Rigs and bends [`]: %s"
                        + "%n%d characters, each set up from its rig alone",
                PATHS[path], speed, held ? "yes" : "no", HANDS[hands], looking ? "on" : "off", overlay ? "on" : "off",
                reachers.size());
    }

    @Override
    public void delete() {
        for (Reacher r : reachers) r.body.remove();
        reachers.clear();
    }
}
