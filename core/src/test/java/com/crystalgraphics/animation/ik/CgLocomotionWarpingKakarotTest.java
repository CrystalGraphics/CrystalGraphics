package com.crystalgraphics.animation.ik;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.blend.CgBlendSpacePlayerNode;
import com.crystalgraphics.animation.blend.CgBlendSpace;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgVirtualBone;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Kakarot's Goku running from a crawl to past his sprint, and on a speed potion: each of his three in-place run loops
 * given root motion from its feet, stride warped to the speed he moves at and his legs bent onto the warped feet.
 *
 * <p>His clips are drawn, not captured: a planted foot sweeps back faster than the body's even pace within a stride,
 * so even at its own speed a loop's foot moves a little over a stance. Warping keeps that, scaled with the stride; the
 * gate is what it adds: over every stance the clip authors, a planted foot moves at most its own slide, scaled, and
 * {@link #SLIDE} more.</p>
 */
public class CgLocomotionWarpingKakarotTest {

    private static final Path KK = Paths.get("src/main/resources/assets/kk");
    /** The foot-slide tolerance: how much more than the clip's own a planted foot may move in a stance, metres. */
    static final float SLIDE = 0.02f;
    private static final float DT = 1f / 240f;
    /** Each loop's speeds, over its own: a crawl, slower, its own, Speed II (+40%), twice. */
    private static final float[] FACTORS = {0.25f, 0.5f, 1f, 1.4f, 2f};
    /** The stride's range; past it the loop plays faster or slower instead, as a game pairs the two. */
    private static final float MIN_SCALE = 0.2f, MAX_SCALE = 1.5f;

    @Test
    public void gokusFeetStayPlantedFromACrawlToASprintAndOnASpeedPotion() {
        CgSkeletalMesh mesh = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb").toAbsolutePath().toString())
                .withVirtualBones(List.of(new CgVirtualBone("ik_foot_root", "root", "root"),
                        new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"), new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
        CgSkeleton s = mesh.skeleton();
        CgAnimVariable<Float> speed = CgAnimVariable.floating("speed"), rate = CgAnimVariable.floating("rate", 1f);
        float worst = 0f;
        for (int i = 1; i <= 3; i++) {
            CgAnimSequence raw = CgMeshLoader.sequences(KK.resolve("models/Art/ANM/cpl/C001A/FLD/C001A_FLD_Run0" + i + "_02LP.glb")
                    .toAbsolutePath().toString()).get(0);
            CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(raw, s, true, "root", "foot_l", "foot_r");
            float own = CgClipModifiers.speed(run, s);
            CgAnimNode player = CgSequencePlayerNode.of(run).loop().playRate(rate);
            CgAnimGraph plain = CgAnimGraph.builder("test:plain", s).variables(speed, rate).output(player).build();
            CgAnimNode warped = CgStrideWarpingNode.of(player, "pelvis", "ik_foot_root")
                    .foot("ik_foot_l", "foot_l", "thigh_l").foot("ik_foot_r", "foot_r", "thigh_r").locomotionSpeed(speed)
                    .scaleClamp(MIN_SCALE, MAX_SCALE);
            CgAnimGraph graph = CgAnimGraph.builder("test:warped", s).variables(speed, rate)
                    .output(CgLegIkNode.of(warped).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r")).build();
            float authored = slide(mesh, plain, speed, rate, own, 1f, raw);
            System.out.printf("Run0%d at its own %.2f m/s: its feet move %.1f cm a stance%n", i, own, authored * 100f);
            for (float factor : FACTORS) {
                float v = own * factor, stride = Math.max(MIN_SCALE, Math.min(MAX_SCALE, factor)), playRate = factor / stride;
                float moved = slide(mesh, graph, speed, rate, v, playRate, raw), allowed = authored * Math.max(1f, stride) + SLIDE;
                float unwarped = slide(mesh, plain, speed, rate, v, 1f, raw);
                System.out.printf("  %5.2f m/s (x%.2f, stride x%.2f at rate %.2f): %.1f cm, allowed %.1f; unwarped %.1f%n", v, factor, stride,
                        playRate, moved * 100f, allowed * 100f, unwarped * 100f);
                worst = Math.max(worst, moved - authored * Math.max(1f, stride));
            }
        }
        assertTrue("warping added " + worst * 100f + " cm to a stance", worst <= SLIDE);
    }

    @Test
    public void gokuStrafesOnHisRunLoopsHisLegsTurnedAndHisChestAhead() {
        CgSkeletalMesh mesh = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb").toAbsolutePath().toString())
                .withVirtualBones(List.of(new CgVirtualBone("ik_foot_root", "root", "root"),
                        new CgVirtualBone("ik_foot_l", "ik_foot_root", "foot_l"), new CgVirtualBone("ik_foot_r", "ik_foot_root", "foot_r")));
        CgSkeleton s = mesh.skeleton();
        CgAnimSequence[] loops = new CgAnimSequence[3];
        float[] speeds = new float[3];
        for (int i = 0; i < 3; i++) {
            CgAnimSequence raw = CgMeshLoader.sequences(KK.resolve("models/Art/ANM/cpl/C001A/FLD/C001A_FLD_Run0" + (i + 1) + "_02LP.glb")
                    .toAbsolutePath().toString()).get(0);
            CgAnimSequence clip = CgClipModifiers.rootMotionFromFeet(raw, s, true, "root", "foot_l", "foot_r");
            clip = CgClipModifiers.footSyncMarkers(clip, s, "root", "foot_l", "foot_l", true);
            loops[i] = CgClipModifiers.footSyncMarkers(clip, s, "root", "foot_r", "foot_r", true);
            speeds[i] = CgClipModifiers.speed(loops[i], s);
        }
        CgBlendSpace runs = CgBlendSpace.oneD("test:runs", "speed", 0f, speeds[2])
                .sample(loops[0], speeds[0]).sample(loops[1], speeds[1]).sample(loops[2], speeds[2]).build();
        CgAnimVariable<Float> speed = CgAnimVariable.floating("speed"), angle = CgAnimVariable.floating("angle");
        CgAnimNode strided = CgStrideWarpingNode.of(CgBlendSpacePlayerNode.of(runs, speed), "pelvis", "ik_foot_root")
                .foot("ik_foot_l", "foot_l", "thigh_l").foot("ik_foot_r", "foot_r", "thigh_r").locomotionSpeed(speed).scaleClamp(0.5f, 1.5f);
        CgAnimNode turned = CgOrientationWarpingNode.of(strided, "ik_foot_root").spine("spine_01", "spine_02", "spine_03")
                .ikFeet("ik_foot_l", "ik_foot_r").locomotionAngle(angle).flipBeyond(0f);
        CgAnimGraph graph = CgAnimGraph.builder("test:strafe", s).variables(speed, angle)
                .output(CgLegIkNode.of(turned).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r")).build();
        CgAnimInstance anim = new CgAnimInstance(mesh).graph(graph);
        anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);
        Matrix4f m = new Matrix4f(), step = new Matrix4f();
        Vector3f moved = new Vector3f(), t = new Vector3f();
        for (int f = 0; f < 240; f++) {
            anim.set(speed, 3f).set(angle, 90f).tick(1f / 60f);
            if (anim.montages().consumeRootMotion(1f, step) && f >= 120) moved.add(step.getTranslation(t));
        }
        Vector3f chest = anim.pose().component(s.indexOf("spine_03"), m).transformDirection(new Vector3f(0f, 0f, 1f));
        Vector3f rest = new Vector3f(0f, 0f, 1f);
        CgAnimPose restPose = new CgAnimPose(s);
        restPose.resolve();
        restPose.component(s.indexOf("spine_03"), m).transformDirection(rest);
        float chestTurn = (float) Math.toDegrees(Math.atan2(chest.x, chest.z) - Math.atan2(rest.x, rest.z));
        float travel = (float) Math.toDegrees(Math.atan2(moved.x, moved.z));
        System.out.printf("strafing at 90: travelling %.0f degrees off his facing, chest turned %.0f%n", travel, chestTurn);
        assertTrue("he travels sideways: " + travel, Math.abs(travel - 90f) < 5f);
        assertTrue("his chest stays near ahead: " + chestTurn, Math.abs(chestTurn) < 30f);
    }

    /**
     * Runs {@code v} m/s along +z for four seconds, the loop at {@code playRate}; answers the most a planted foot moved in the world over a stance,
     * each stance where {@code source} (the loop as authored) has that foot lowest and moving back.
     */
    private static float slide(CgSkeletalMesh mesh, CgAnimGraph graph, CgAnimVariable<Float> speed, CgAnimVariable<Float> rate, float v,
                               float playRate, CgAnimSequence source) {
        CgSkeleton s = mesh.skeleton();
        CgAnimInstance anim = new CgAnimInstance(mesh).graph(graph);
        anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);
        int[] feet = {s.indexOf("foot_l"), s.indexOf("foot_r")};
        CgAnimPose a = new CgAnimPose(s), b = new CgAnimPose(s);
        Matrix4f m = new Matrix4f(), step = new Matrix4f();
        Vector3f p = new Vector3f(), q = new Vector3f();
        float[] startX = new float[2], startZ = new float[2];
        boolean[] in = new boolean[2];
        float worst = 0f, length = source.duration();
        int frames = (int) (4f / DT);
        for (int f = 0; f < frames; f++) {
            anim.set(speed, v).set(rate, playRate).tick(DT);
            anim.montages().consumeRootMotion(1f, step);
            float t = (f + 1) * DT * playRate % length;
            a.rest();
            source.sample(a, t);
            a.resolve();
            b.rest();
            source.sample(b, Math.min(t + DT, length));
            b.resolve();
            for (int i = 0; i < 2; i++) {
                float hi = a.component(feet[i], m).getTranslation(p).y, other = a.component(feet[1 - i], m).getTranslation(q).y;
                float back = b.component(feet[i], m).getTranslation(q).z - p.z;
                boolean planted = hi < other && back < 0f;
                anim.pose().component(feet[i], m).getTranslation(p);
                float x = p.x, z = p.z + v * DT * (f + 1);
                if (planted && !in[i]) {
                    startX[i] = x;
                    startZ[i] = z;
                } else if (planted) {
                    worst = Math.max(worst, (float) Math.hypot(x - startX[i], z - startZ[i]));
                }
                in[i] = planted;
            }
        }
        return worst;
    }
}
