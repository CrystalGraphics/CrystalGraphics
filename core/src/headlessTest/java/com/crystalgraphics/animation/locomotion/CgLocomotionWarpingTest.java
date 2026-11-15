package com.crystalgraphics.animation.locomotion;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.graph.CgTwoWayBlendNode;
import com.crystalgraphics.animation.ik.CgOffsetRootBoneNode;
import com.crystalgraphics.animation.ik.CgOrientationWarpingNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSyncMarker;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgBuiltInCompilers;
import com.crystalgraphics.asset.tree.CgAssetData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Locomotion on a walker drawn by hand: its feet sweep back half a metre each half second (1 m/s) in place. Root
 * motion from its feet, from every player at its weight, distance matching, orientation warping and the root held off
 * the entity, each against the sum it must give.
 */
public class CgLocomotionWarpingTest {

    private static final CgSkeleton WALKER = CgSkeleton.builder()
            .bone("root", null, new Vector3f())
            .bone("pelvis", "root", new Vector3f(0f, 1f, 0f))
            .bone("spine", "pelvis", new Vector3f(0f, 0.3f, 0f))
            .bone("thigh_l", "pelvis", new Vector3f(0.1f, 0f, 0f))
            .bone("foot_l", "thigh_l", new Vector3f(0f, -1f, 0f))
            .bone("thigh_r", "pelvis", new Vector3f(-0.1f, 0f, 0f))
            .bone("foot_r", "thigh_r", new Vector3f(0f, -1f, 0f))
            .bone("ik_foot_root", "root", new Vector3f())
            .bone("ik_foot_l", "ik_foot_root", new Vector3f(0.1f, 0f, 0f))
            .bone("ik_foot_r", "ik_foot_root", new Vector3f(-0.1f, 0f, 0f))
            .build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
    }), WALKER).build();
    private static final float DT = 1f / 60f;

    @BeforeClass
    public static void kinds() {
        CgBuiltInCompilers.register();
    }

    /** A loop of {@code frames} at 30 a second: each foot planted, sweeping back {@code stride} m, half the loop, then swinging. */
    private static CgAnimSequence walk(String name, int frames, float stride) {
        float[] l = new float[frames * 3], r = new float[frames * 3];
        for (int f = 0; f < frames; f++) {
            float phase = (float) f / (frames - 1);
            foot(l, f, phase, stride);
            foot(r, f, (phase + 0.49f) % 1f, stride);
        }
        float[] still = {0f, 0f, 0f, 1f}, one = {1f, 1f, 1f};
        return CgAnimSequence.builder(name, 30, frames).track("foot_l", l, still, one).track("foot_r", r, still, one).build();
    }

    private static void foot(float[] out, int f, float phase, float stride) {
        float z, lift;
        if (phase < 0.5f) {
            z = stride / 2f - stride * (phase / 0.5f);
            lift = 0f;
        } else {
            float swing = (phase - 0.5f) / 0.5f;
            z = -stride / 2f + stride * swing;
            lift = 0.1f * (float) Math.sin(Math.PI * swing);
        }
        out[f * 3] = 0f;
        out[f * 3 + 1] = -1f + lift;
        out[f * 3 + 2] = z;
    }

    @Test
    public void anInPlaceLoopTravelsAsItsPlantedFeetSayAndIsMarkedAtEachSwing() {
        CgAnimSequence walk = CgClipModifiers.rootMotionFromFeet(walk("walk", 31, 0.5f), WALKER, true, "root", "foot_l", "foot_r");
        assertEquals("half a metre each half second", 1f, CgClipModifiers.speed(walk, WALKER), 0.05f);
        walk = CgClipModifiers.footSyncMarkers(walk, WALKER, "root", "foot_l", "foot_l", true);
        walk = CgClipModifiers.footSyncMarkers(walk, WALKER, "root", "foot_r", "foot_r", true);
        int left = 0, right = 0;
        for (CgSyncMarker m : walk.settings().markers()) {
            if (m.name().equals("foot_l")) left++;
            else right++;
        }
        assertEquals("each foot passes the root once a loop: " + walk.settings().markers(), 1, left);
        assertEquals("" + walk.settings().markers(), 1, right);
    }

    @Test
    public void everyPlayersRootMotionJoinsAtItsWeight() {
        CgAnimSequence slow = CgClipModifiers.rootMotionFromFeet(walk("slow", 31, 0.5f), WALKER, true, "root", "foot_l", "foot_r");
        CgAnimSequence fast = CgClipModifiers.rootMotionFromFeet(walk("fast", 31, 1.5f), WALKER, true, "root", "foot_l", "foot_r");
        CgAnimNode blend = CgTwoWayBlendNode.of(CgSequencePlayerNode.of(slow).loop(), CgSequencePlayerNode.of(fast).loop(), 0.5f);
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:blend", WALKER).output(blend).build());
        anim.montages().rootMotionMode(CgRootMotionMode.FROM_EVERYTHING);
        Matrix4f step = new Matrix4f();
        Vector3f t = new Vector3f(), sum = new Vector3f();
        for (int f = 0; f < 60; f++) {
            anim.tick(DT);
            if (anim.montages().consumeRootMotion(1f, step)) sum.add(step.getTranslation(t));
        }
        assertEquals("halfway between 1 and 3 m/s for a second", 2f, sum.length(), 0.1f);
        int pelvis = WALKER.indexOf("pelvis");
        assertEquals("the players hold their roots", 1f, anim.pose().component(pelvis, step).getTranslation(t).y, 1e-4f);
        assertEquals(0f, t.z, 1e-4f);
    }

    @Test
    public void aStopIsPlacedByTheDistanceStillToGoAndAStartAdvancedByTheDistanceCovered() {
        CgAnimSequence walk = CgClipModifiers.rootMotionFromFeet(walk("walk", 31, 0.5f), WALKER, false, "root", "foot_l", "foot_r");
        CgAnimSequence stop = CgClipModifiers.distanceCurve(walk, WALKER, CgClipModifiers.DISTANCE, true);
        assertEquals("0.5 m to go is half a second from the end", 0.5f, CgDistanceMatching.timeAtDistance(stop, -0.5f, CgClipModifiers.DISTANCE), 0.05f);
        float t = CgDistanceMatching.timeAfterDistanceTravelled(stop, 0f, 0.25f, CgClipModifiers.DISTANCE, false);
        assertEquals("a quarter metre is a quarter second at 1 m/s", 0.25f, t, 0.05f);
        assertEquals("1 m/s at rate 1; 2 m/s at 2", 2f, CgDistanceMatching.playRateToMatchSpeed(walk, WALKER, 2f, 0f, 3f), 0.1f);

        CgAnimVariable<Float> travelled = CgAnimVariable.floating("travelled");
        CgAnimGraph graph = CgAnimGraph.builder("test:start", WALKER).variables(travelled)
                .output(CgSequenceEvaluatorNode.of(stop).advanceByDistance(travelled, 0.5f, 2f)).build();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(graph);
        for (int f = 0; f < 30; f++) anim.set(travelled, DT * 0.5f).tick(DT);
        int foot = WALKER.indexOf("foot_l");
        CgAnimInstance expected = new CgAnimInstance(MESH);
        expected.play(stop, false).position(0.25f).tick(0f);
        assertEquals("half a second at 0.5 m/s plays a quarter second", expected.pose().component(foot, new Matrix4f()).m32(),
                anim.pose().component(foot, new Matrix4f()).m32(), 0.02f);

        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(graph), CgBodyKinds.ANIM_GRAPH);
        CgAnimGraph read = CgAnimGraphCodec.compile(CgAssetData.of("test:start", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null),
                null).graph(WALKER, id -> stop);
        assertEquals("written again, the same file", yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(read), CgBodyKinds.ANIM_GRAPH));
    }

    @Test
    public void orientationWarpingTurnsTheFeetAndKeepsTheChestFacing() {
        CgAnimVariable<Float> angle = CgAnimVariable.floating("angle");
        CgAnimSequence walk = walk("walk", 31, 0.5f);
        CgAnimNode turned = CgOrientationWarpingNode.of(CgSequencePlayerNode.of(walk).loop(), "ik_foot_root").spine("spine")
                .ikFeet("ik_foot_l", "ik_foot_r").manual(angle).interp(0f, 180f);
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:turn", WALKER).variables(angle).output(turned).build());
        anim.set(angle, 90f).tick(DT);
        Matrix4f m = new Matrix4f();
        Vector3f facing = anim.pose().component(WALKER.indexOf("spine"), m).transformDirection(new Vector3f(0f, 0f, 1f));
        Vector3f rootF = anim.pose().component(0, new Matrix4f()).transformDirection(new Vector3f(0f, 0f, 1f));
        assertEquals("the chest still faces +z: " + facing + " root " + rootF, 1f, facing.z, 1e-3f);
        Vector3f ikFoot = anim.pose().component(WALKER.indexOf("ik_foot_l"), m).getTranslation(new Vector3f());
        assertEquals("the IK feet turned a quarter: +x goes to -z", -0.1f, ikFoot.z, 1e-3f);
        Vector3f footFacing = anim.pose().component(WALKER.indexOf("ik_foot_l"), m).transformDirection(new Vector3f(0f, 0f, 1f));
        assertEquals("an IK foot faces as the root turned, half the way", rootF.z, footFacing.z, 1e-3f);
    }

    @Test
    public void turningInPlaceTheBodyHoldsThenEasesRound() {
        float[] yaw = {0f};
        CgAnimNode body = CgOffsetRootBoneNode.of(CgSequencePlayerNode.of(walk("idle", 31, 0f)).loop())
                .rotation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.2f, 90f).translation(CgOffsetRootBoneNode.Mode.INTERPOLATE, 0.1f, -1f);
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:offset", WALKER).output(body).build())
                .placedBy(out -> out.rotationY(yaw[0]));
        anim.tick(DT);
        yaw[0] = (float) Math.toRadians(120);
        anim.tick(DT);
        Matrix4f m = new Matrix4f();
        Vector3f facing = anim.pose().component(0, m).transformDirection(new Vector3f(0f, 0f, 1f));
        float off = (float) Math.toDegrees(Math.atan2(facing.x, facing.z));
        assertTrue("held, at most 90 degrees off the entity: " + off, Math.abs(off + 90f) < 3f);
        for (int f = 0; f < 12; f++) anim.tick(DT);
        facing = anim.pose().component(0, m).transformDirection(new Vector3f(0f, 0f, 1f));
        float later = (float) Math.toDegrees(Math.atan2(facing.x, facing.z));
        assertTrue("a 0.2 s half-life halves it in 0.2 s: " + later, Math.abs(later) < 50f && Math.abs(later) > 35f);
    }

    @Test
    public void aStopIsPredictedWhereBrakingEndsIt() {
        Vector3f stop = CgMovementPrediction.stopLocation(new Vector3f(4f, 0f, 0f), 0f, 8f, new Vector3f());
        assertEquals("v^2 / 2a", 1f, stop.x, 1e-4f);
        Vector3f pivot = CgMovementPrediction.pivotLocation(new Vector3f(-10f, 0f, 0f), new Vector3f(5f, 0f, 0f), 0f, new Vector3f());
        assertEquals("v^2 / 2a", 1.25f, pivot.x, 1e-4f);
    }
}
