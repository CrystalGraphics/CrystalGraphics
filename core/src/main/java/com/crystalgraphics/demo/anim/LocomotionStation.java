package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.blend.CgBlendSpace;
import com.crystalgraphics.animation.blend.CgBlendSpacePlayerNode;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgControlAlpha;
import com.crystalgraphics.animation.ik.CgLegIkNode;
import com.crystalgraphics.animation.ik.CgOffsetRootBoneNode;
import com.crystalgraphics.animation.ik.CgOrientationWarpingNode;
import com.crystalgraphics.animation.ik.CgStrideWarpingNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgVirtualBone;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.demo.CgDemoStyles;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.render.scene.CgScene;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Goku running laps from a crawl to past his sprint, his feet held where they land. His three in-place run loops are
 * given root motion from their feet and foot sync markers, blended by speed.
 *
 * <ul>
 *   <li><b>Warped</b> and <b>plain</b> share the outer ring, opposite each other, at the same speed: one stride warped
 *       (the clip played faster or slower only past half or one and a half strides), the other playing the blend as it
 *       is. Each planted foot leaves a print; a red streak is how far it slid while down.</li>
 *   <li><b>Strafing</b>: the inner ring, always facing the middle, so he moves at right angles to where he faces.
 *       Orientation warping turns his legs onto the way he goes and his chest back.</li>
 *   <li><b>Turning in place</b>: in the middle, standing; every few seconds he is told to face elsewhere and his body
 *       holds, then swings round after his entity (at most 100 degrees behind).</li>
 * </ul>
 *
 * <p>Keys: Left/Right the speed, Up a speed potion (+40%), Down warping on/off, ` the footprints, \ a turn now.</p>
 */
final class LocomotionStation implements AnimDemoStation {

    private static final float[] SPEEDS = {0.3f, 0.6f, 1.15f, 2f, 3f, 4.5f, 6f, 7.1f, 9f, 11f, 13f};
    private static final double OUTER = 4.5, INNER = 2.4;
    /** Prints kept a foot, and how long each stays, seconds. */
    private static final int PRINTS = 24;
    private static final float PRINT_LIFE = 3f;

    private final CgAnimVariable<Float> speed = CgAnimVariable.floating("speed"), rate = CgAnimVariable.floating("rate", 1f),
            angle = CgAnimVariable.floating("move_angle");
    private final CgAnimVariable<Boolean> warp = CgAnimVariable.bool("warp", true);
    private final CgFont font = CgDefaultFont.bold(36);
    @Nullable
    private Runner warped, plain, strafer;
    @Nullable
    private AnimDemoCast.Body turner;
    private float[] loopSpeeds;
    private double x, y, z;
    private int speedIndex = 4;
    private boolean potion, warping = true, prints = true;
    private float turnClock, turnYaw, time;
    private String status = "";

    /** A body going round a ring: its angle, whether it faces the middle, and its feet's prints. */
    private final class Runner {
        final AnimDemoCast.Body body;
        final double radius;
        final boolean facesMiddle;
        final String label;
        final int colour;
        final Feet left, right;
        double theta;

        Runner(AnimDemoCast.Body body, double radius, double theta, boolean facesMiddle, String label, int colour) {
            this.body = body;
            this.radius = radius;
            this.theta = theta;
            this.facesMiddle = facesMiddle;
            this.label = label;
            this.colour = colour;
            CgSkeleton s = body.cast.mesh.skeleton();
            left = new Feet(s.indexOf("foot_l"));
            right = new Feet(s.indexOf("foot_r"));
            body.instance.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);
        }

        void move(float v, float dt) {
            theta += v / radius * dt;
            double px = x + radius * Math.cos(theta), pz = z + radius * Math.sin(theta);
            // the way it goes round, and the way it faces
            float mx = (float) -Math.sin(theta), mz = (float) Math.cos(theta);
            float yaw = facesMiddle ? (float) Math.atan2(-Math.cos(theta), -Math.sin(theta)) : (float) Math.atan2(mx, mz);
            body.at(px, y, pz, yaw);
            // where it moves, in degrees about +y off its facing
            float lx = mx * (float) Math.cos(yaw) - mz * (float) Math.sin(yaw), lz = mx * (float) Math.sin(yaw) + mz * (float) Math.cos(yaw);
            body.instance.set(angle, (float) Math.toDegrees(Math.atan2(lx, lz)));
            body.instance.montages().consumeRootMotion(1f, step);
        }

        void prints(float now) {
            left.sample(body, now, colour);
            right.sample(body, now, colour);
        }

        void draw(float now) {
            left.draw(now, colour);
            right.draw(now, colour);
            CgScene.client().labelOnce(label).at(body.x, y + 2.4, body.z).height(0.3f).font(font).style(CgDemoStyles.LABEL).submit();
        }

        void clear() {
            left.count = right.count = 0;
        }
    }

    private final Matrix4f step = new Matrix4f();

    /** One foot's prints: where it landed, where it was when it lifted, and when. */
    private final class Feet {
        final int bone;
        final double[] from = new double[PRINTS * 3], to = new double[PRINTS * 3];
        final float[] born = new float[PRINTS];
        final Matrix4f m = new Matrix4f();
        final Vector3d at = new Vector3d();
        int head, count;
        boolean down;

        Feet(int bone) {
            this.bone = bone;
        }

        /** A foot this close over the ground and moving this slowly in the world is down. */
        void sample(AnimDemoCast.Body body, float now, int colour) {
            body.instance.pose().component(bone, m);
            body.component.worldTransform().transformPosition(at.set(m.m30(), m.m31(), m.m32()));
            boolean low = at.y < y + 0.16;
            if (low && !down) {
                int i = head;
                from[i * 3] = to[i * 3] = at.x;
                from[i * 3 + 1] = to[i * 3 + 1] = y + 0.02;
                from[i * 3 + 2] = to[i * 3 + 2] = at.z;
                born[i] = now;
                head = (head + 1) % PRINTS;
                count = Math.min(count + 1, PRINTS);
            } else if (low) {
                int i = Math.floorMod(head - 1, PRINTS);
                to[i * 3] = at.x;
                to[i * 3 + 2] = at.z;
            }
            down = low;
        }

        void draw(float now, int colour) {
            for (int k = 0; k < count; k++) {
                int i = Math.floorMod(head - 1 - k, PRINTS);
                float age = now - born[i];
                if (age > PRINT_LIFE) continue;
                int alpha = (int) (255 * (1f - age / PRINT_LIFE));
                CgCollisionDebug.mark(from[i * 3], from[i * 3 + 1], from[i * 3 + 2], 0, 1, 0, alpha << 24 | colour & 0xFFFFFF);
                double dx = to[i * 3] - from[i * 3], dz = to[i * 3 + 2] - from[i * 3 + 2];
                if (dx * dx + dz * dz > 0.03 * 0.03) {
                    CgCollisionDebug.line(from[i * 3], from[i * 3 + 1], from[i * 3 + 2], to[i * 3], from[i * 3 + 1], to[i * 3 + 2],
                            alpha << 24 | 0xFF3030);
                }
            }
        }
    }

    @Override
    public String name() {
        return "Locomotion warping";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        time = 0f;
        AnimDemoCast goku = null;
        for (AnimDemoCast c : casts.kitCasts()) {
            if (c.clip("FLD_Run01_02LP") != null && c.clip("FLD_Stand") != null && c.mesh.skeleton().indexOf("spine_03") >= 0) goku = c;
        }
        if (goku == null) {
            status = "\nNo kit character with Kakarot's run loops loaded";
            return;
        }
        CgSkeleton raw = goku.mesh.skeleton();
        AnimDemoCast cast = goku.withMesh(goku.mesh.withVirtualBones(List.of(new CgVirtualBone("ik_foot_root", "root", "root"),
                new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"), new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r"))));
        CgSkeleton s = cast.mesh.skeleton();
        CgAnimSequence[] loops = new CgAnimSequence[3];
        loopSpeeds = new float[3];
        for (int i = 0; i < 3; i++) {
            CgAnimSequence clip = CgClipModifiers.rootMotionFromFeet(goku.clip("FLD_Run0" + (i + 1) + "_02LP").sequence(), s, true, "root",
                    "foot_l", "foot_r");
            clip = CgClipModifiers.footSyncMarkers(clip, s, "root", "foot_l", "foot_l", true);
            loops[i] = CgClipModifiers.footSyncMarkers(clip, s, "root", "foot_r", "foot_r", true);
            loopSpeeds[i] = CgClipModifiers.speed(loops[i], s);
        }
        CgBlendSpace runs = CgBlendSpace.oneD("demo:goku_runs", "speed", 0f, loopSpeeds[2])
                .sample(loops[0], loopSpeeds[0]).sample(loops[1], loopSpeeds[1]).sample(loops[2], loopSpeeds[2]).build();

        CgAnimNode blend = CgBlendSpacePlayerNode.of(runs, speed).playRate(rate);
        CgControlAlpha on = CgControlAlpha.bool(warp, 0.25f, 0.25f);
        CgAnimNode strided = CgStrideWarpingNode.of(blend, "pelvis", "ik_foot_root").foot("ik_foot_l", "foot_l", "thigh_l")
                .foot("ik_foot_r", "foot_r", "thigh_r").locomotionSpeed(speed).scaleClamp(0.5f, 1.5f).scaleInterp(8f, 8f).alpha(on);
        CgAnimNode turned = CgOrientationWarpingNode.of(strided, "ik_foot_root").spine("spine_01", "spine_02", "spine_03")
                .ikFeet("ik_foot_l", "ik_foot_r").locomotionAngle(angle).flipBeyond(0f).alpha(on);
        CgAnimNode legs = CgLegIkNode.of(turned).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
        CgAnimGraph warpedGraph = CgAnimGraph.builder("demo:locomotion_warped", s).variables(speed, rate, angle, warp).output(legs).build();
        // the same variables as the warped graph's: every runner is fed alike
        CgAnimGraph plainGraph = CgAnimGraph.builder("demo:locomotion_plain", s).variables(speed, rate, angle)
                .output(CgBlendSpacePlayerNode.of(runs, speed)).build();
        CgAnimNode standing = CgOffsetRootBoneNode.of(CgSequencePlayerNode.of(cast.clip("FLD_Stand").sequence()).loop())
                .rotation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.25f, 100f).translation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.1f, 0.5f);
        CgAnimGraph turnGraph = CgAnimGraph.builder("demo:turn_in_place", s).output(standing).build();

        warped = new Runner(spawn(cast, warpedGraph), OUTER, 0, false, "stride warped", 0xFF40E0FF);
        plain = new Runner(spawn(cast, plainGraph), OUTER, Math.PI, false, "plain", 0xFFFFB040);
        strafer = new Runner(spawn(cast, warpedGraph), INNER, Math.PI / 2, true, "strafing", 0xFF60FF80);
        turner = spawn(cast, turnGraph).at(x, y, z, 0f);
        turnYaw = 0f;
        turnClock = 0f;
        // the rings, a block's edge each side of the runners' line
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            set.box(x + OUTER * Math.cos(a) - 0.05, y - 0.01, z + OUTER * Math.sin(a) - 0.05, x + OUTER * Math.cos(a) + 0.05, y + 0.01,
                    z + OUTER * Math.sin(a) + 0.05, 0xFF5A6A7A);
            if (i % 2 == 0) set.box(x + INNER * Math.cos(a) - 0.05, y - 0.01, z + INNER * Math.sin(a) - 0.05, x + INNER * Math.cos(a) + 0.05,
                    y + 0.01, z + INNER * Math.sin(a) + 0.05, 0xFF4A7A5A);
        }
        status = "";
    }

    private static AnimDemoCast.Body spawn(AnimDemoCast cast, CgAnimGraph graph) {
        AnimDemoCast.Body body = cast.spawn();
        body.instance.graph(graph);
        return body;
    }

    @Override
    public float[] view() {
        return new float[]{0f, 6f, 9f, 0f, -30f};
    }

    private float speedNow() {
        return SPEEDS[speedIndex] * (potion ? 1.4f : 1f);
    }

    /** The blend's own speed at {@code v}: its loops' speeds between them, the nearest past either end. */
    private float authored(float v) {
        return Math.max(loopSpeeds[0], Math.min(loopSpeeds[2], v));
    }

    @Override
    public void update(float dt) {
        if (warped == null) return;
        time += dt;
        float v = speedNow(), own = authored(v), stride = Math.max(0.5f, Math.min(1.5f, v / own));
        // the stride takes what it can; the loop plays faster or slower for the rest
        float warpedRate = warping ? v / (own * stride) : 1f;
        for (Runner r : new Runner[]{warped, strafer}) {
            r.body.instance.set(speed, v).set(rate, warpedRate).set(warp, warping);
            r.move(v, dt);
        }
        plain.body.instance.set(speed, v);
        plain.move(v, dt);
        turnClock += dt;
        if (turnClock > 3f) turn();
        if (prints) {
            for (Runner r : new Runner[]{warped, plain, strafer}) {
                r.prints(time);
                r.draw(time);
            }
        } else {
            for (Runner r : new Runner[]{warped, plain, strafer}) {
                CgScene.client().labelOnce(r.label).at(r.body.x, y + 2.4, r.body.z).height(0.3f).font(font).style(CgDemoStyles.LABEL).submit();
            }
        }
        CgScene.client().labelOnce("turning in place").at(x, y + 2.4, z).height(0.3f).font(font).style(CgDemoStyles.LABEL).submit();
    }

    private void turn() {
        turnClock = 0f;
        turnYaw += (float) Math.toRadians(90 + 60 * Math.random()) * (Math.random() < 0.5 ? -1 : 1);
        turner.at(x, y, z, turnYaw);
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (warped == null) return false;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT -> speedIndex = Math.min(SPEEDS.length - 1, speedIndex + 1);
            case CgKeyCodes.KEY_LEFT -> speedIndex = Math.max(0, speedIndex - 1);
            case CgKeyCodes.KEY_UP -> potion = !potion;
            case CgKeyCodes.KEY_DOWN -> warping = !warping;
            case CgKeyCodes.KEY_GRAVE -> {
                prints = !prints;
                for (Runner r : new Runner[]{warped, plain, strafer}) r.clear();
            }
            case CgKeyCodes.KEY_BACKSLASH -> turn();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (warped == null) return status;
        return String.format(Locale.ROOT, "\nSpeed %.2f m/s%s (Left/Right; Up: speed potion +40%%)", speedNow(), potion ? " with Speed II" : "")
                + String.format(Locale.ROOT, "\nGoku's loops: %.2f, %.2f, %.2f m/s, in place, root motion from their feet",
                loopSpeeds[0], loopSpeeds[1], loopSpeeds[2])
                + "\nDown: warping " + (warping ? "ON (stride and orientation)" : "OFF")
                + "\n`: footprints " + (prints ? "on (a red streak is a slide)" : "off") + "   \\: turn the middle Goku now";
    }

    @Override
    public void delete() {
        for (Runner r : new Runner[]{warped, plain, strafer}) if (r != null) r.body.remove();
        if (turner != null) turner.remove();
        warped = plain = strafer = null;
        turner = null;
    }
}
