package com.crystalgraphics.animation.ik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgRefPoseNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgBuiltInCompilers;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.sun.management.ThreadMXBean;

/**
 * Foot placement on a dedicated server's classpath: a character standing over a step plants each foot on the surface
 * under it, the leg IK after it bends the legs there, the node round-trips, and a warm frame allocates nothing.
 */
public class CgFootPlacementTest {

    /** A biped: a pelvis a block up, two legs of thigh, calf and foot, a ball ahead of each foot, and the IK feet. */
    private static final CgSkeleton BIPED = CgSkeleton.builder()
            .bone("root", null, new Vector3f())
            .bone("ik_foot_root", "root", new Vector3f())
            .bone("ik_foot_l", "ik_foot_root", new Vector3f(0.2f, 0.05f, 0f))
            .bone("ik_foot_r", "ik_foot_root", new Vector3f(-0.2f, 0.05f, 0f))
            .bone("pelvis", "root", new Vector3f(0, 1f, 0))
            .bone("thigh_l", "pelvis", new Vector3f(0.2f, 0, 0))
            .bone("calf_l", "thigh_l", new Vector3f(0, -0.5f, 0.02f))
            .bone("foot_l", "calf_l", new Vector3f(0, -0.45f, -0.02f))
            .bone("ball_l", "foot_l", new Vector3f(0, -0.05f, 0.12f))
            .bone("thigh_r", "pelvis", new Vector3f(-0.2f, 0, 0))
            .bone("calf_r", "thigh_r", new Vector3f(0, -0.5f, 0.02f))
            .bone("foot_r", "calf_r", new Vector3f(0, -0.45f, -0.02f))
            .bone("ball_r", "foot_r", new Vector3f(0, -0.05f, 0.12f))
            .build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
        int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(4, 1f).end();
        int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(7, 1f).end();
        int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(11, 1f).end();
        m.triangle(p, q, r);
    }), BIPED).build();

    /** A step: 0.25 up where x is positive (the left foot's side), the floor elsewhere. */
    private static final CgAnimGround STEP = new CgAnimGround() {
        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public boolean floor(Vector3d point, Vector3f normal) {
            point.set(0, 0, 0);
            normal.set(0, 1, 0);
            return true;
        }

        @Override
        public void velocity(Vector3d out) {
            out.zero();
        }

        @Override
        public boolean sweep(double x, double y, double z, float dx, float dy, float dz, double distance, double radius,
                             Vector3d point, Vector3f normal) {
            double h = x > 0 ? 0.25 : 0;
            if (dy >= 0 || y < h || y - distance > h) return false;
            point.set(x, h, z);
            normal.set(0, 1, 0);
            return true;
        }
    };

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        CgBuiltInCompilers.register();
    }

    private static CgAnimNode placedLegs() {
        CgAnimNode placed = CgFootPlacementNode.of(CgRefPoseNode.create(), "pelvis", "ik_foot_root")
                .leg("foot_l", "ik_foot_l", "ball_l").leg("foot_r", "ik_foot_r", "ball_r");
        return CgLegIkNode.of(placed).leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
    }

    private static Vector3f at(CgAnimInstance anim, String bone) {
        return anim.pose().component(BIPED.indexOf(bone), new Matrix4f()).getTranslation(new Vector3f());
    }

    @Test
    public void eachFootPlantsOnTheGroundUnderIt() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:feet", BIPED).output(placedLegs()).build())
                .ground(STEP);
        for (int f = 0; f < 240; f++) anim.tick(1 / 60f);
        assertEquals("the left IK foot on the step, at its rest height above it", 0.30f, at(anim, "ik_foot_l").y, 0.02f);
        assertEquals("the right IK foot on the floor", 0.05f, at(anim, "ik_foot_r").y, 0.02f);
        assertTrue("the left foot follows its IK foot", at(anim, "foot_l").distance(at(anim, "ik_foot_l")) < 0.02f);
        assertTrue("the right foot follows its IK foot", at(anim, "foot_r").distance(at(anim, "ik_foot_r")) < 0.02f);
    }

    @Test
    public void withNoGroundTheFeetStayOnTheCharactersPlane() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:feet", BIPED).output(placedLegs()).build());
        for (int f = 0; f < 120; f++) anim.tick(1 / 60f);
        assertEquals(0.05f, at(anim, "ik_foot_l").y, 0.02f);
        assertEquals(0.05f, at(anim, "ik_foot_r").y, 0.02f);
    }

    @Test
    public void itWritesReadsAndPlaysTheSame() {
        CgAnimNode node = CgLegIkNode.of(CgFootPlacementNode.of(CgRefPoseNode.create(), "pelvis", "ik_foot_root")
                .leg("foot_l", "ik_foot_l", "ball_l").leg(new CgFootPlacementNode.Leg("foot_r", "ik_foot_r", "ball_r", 2))
                .settings(new CgFootPlacementNode.Plant(0.5f, 0.12f, CgFootPlacementNode.LockType.PIVOT_AROUND_ANKLE, 0.3f, 0.35f,
                                45f, 0.5f, 0.5f, 0.2f, 0.05f, 2f, 0.75f, false, true), CgFootPlacementNode.Pelvis.DEFAULT,
                        CgFootPlacementNode.Interpolation.DEFAULT, CgFootPlacementNode.Trace.DEFAULT))
                .leg("ik_foot_l", "foot_l").leg("ik_foot_r", "foot_r");
        CgAnimGraph java = CgAnimGraph.builder("test:feet", BIPED).output(node).build();
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(java), CgBodyKinds.ANIM_GRAPH);
        CgAnimGraph data = CgAnimGraphCodec.compile(CgAssetData.of("test:feet", CgBodyKinds.ANIM_GRAPH,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), null).graph(BIPED, id -> null);
        assertEquals("written again, the same file", yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(data), CgBodyKinds.ANIM_GRAPH));
        CgAnimInstance a = new CgAnimInstance(MESH).graph(java).ground(STEP), b = new CgAnimInstance(MESH).graph(data).ground(STEP);
        for (int f = 0; f < 60; f++) {
            a.tick(1 / 60f);
            b.tick(1 / 60f);
        }
        for (int bone = 0; bone < BIPED.boneCount(); bone++) {
            assertTrue(BIPED.name(bone), a.pose().component(bone, new Matrix4f()).equals(b.pose().component(bone, new Matrix4f()), 1e-5f));
        }
    }

    @Test
    public void aWarmFrameAllocatesNothing() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:feet", BIPED).output(placedLegs()).build())
                .ground(STEP).placedBy(out -> out.translation(5, 0, 0));
        for (int f = 0; f < 20000; f++) anim.tick(1 / 60f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) anim.tick(1 / 60f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }
}
