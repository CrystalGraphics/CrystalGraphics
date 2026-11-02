package com.crystalgraphics.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.frame.CgWorld;

/**
 * The pose's features on a dedicated server's classpath: mirroring, bone writes, layer weights and masks, hidden bones,
 * and an instance posed on the server's tick that holds while paused.
 */
public class CgPoseFeaturesTest {

    private static final float EPS = 1e-4f;

    /** root, spine, the two arms either side of it, and a hand below the left. */
    private static CgSkeletalMesh body() {
        CgSkeleton s = CgSkeleton.builder()
                .bone("root", null, new Vector3f())
                .bone("spine", "root", new Vector3f(0, 1, 0))
                .bone("l_arm", "spine", new Vector3f(1, 0, 0))
                .bone("r_arm", "spine", new Vector3f(-1, 0, 0))
                .bone("l_hand", "l_arm", new Vector3f(1, 0, 0))
                .build();
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(2, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(2, 1f).end();
            int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(4, 1f).end();
            m.triangle(p, q, r);
        });
        return CgSkeletalMesh.builder(tri, s).build();
    }

    private static float[] rotZ(float degrees) {
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(degrees));
        return new float[] {q.x, q.y, q.z, q.w};
    }

    private static float angleZ(CgPose pose, int bone) {
        Quaternionf q = pose.rotation(bone, new Quaternionf());
        return (float) Math.toDegrees(q.getEulerAnglesXYZ(new Vector3f()).z);
    }

    @Test
    public void mirroringSwapsTheArmsAndTwiceIsTheSamePose() {
        CgSkeleton s = body().skeleton();
        CgMirrorTable mirror = CgMirrorTable.byName(s, CgMirrorTable.Axis.X).build();
        assertEquals(s.indexOf("r_arm"), mirror.mirrorOf(s.indexOf("l_arm")));
        assertEquals("a centre bone mirrors onto itself", s.indexOf("spine"), mirror.mirrorOf(s.indexOf("spine")));
        assertEquals("l_hand has no partner, so it is left as it is", -1, mirror.mirrorOf(s.indexOf("l_hand")));

        CgPose pose = new CgPose(s);
        float[] z = rotZ(30);
        pose.set(s.indexOf("l_arm"), 1, 0, 0, z[0], z[1], z[2], z[3], 1, 1, 1);
        pose.mirror(mirror);
        assertEquals("the left arm's raise is the right's, mirrored", -30f, angleZ(pose, s.indexOf("r_arm")), 1e-3f);
        assertEquals(0f, angleZ(pose, s.indexOf("l_arm")), 1e-3f);
        assertEquals(-1f, pose.translation(s.indexOf("r_arm"), new Vector3f()).x, EPS);
        pose.mirror(mirror);
        assertEquals(30f, angleZ(pose, s.indexOf("l_arm")), 1e-3f);
    }

    @Test
    public void boneWritesReplaceInTheParentAndAddInComponentSpace() {
        CgSkeletalMesh mesh = body();
        CgAnimInstance body = new CgAnimInstance(mesh);
        Quaternionf quarter = new Quaternionf().rotateZ((float) Math.toRadians(90));
        body.write("spine").rotation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, quarter);
        // In component space, after the spine turned: +x of the component, whatever the parent's turn
        body.write("l_arm").translation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, 0.5f, 0, 0);
        body.evaluate();
        int arm = mesh.skeleton().indexOf("l_arm");
        Matrix4f m = body.pose().component(arm, new Matrix4f());
        // The spine at (0,1,0) turned 90° about z puts the arm's (1,0,0) at (0,2,0); the write then adds +x
        assertEquals(0.5f, m.m30(), EPS);
        assertEquals(2f, m.m31(), EPS);

        CgBoneWrite half = new CgAnimInstance(mesh).write("spine");
        CgAnimInstance faded = new CgAnimInstance(mesh);
        faded.write("spine").rotation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, quarter).alpha(0.5f);
        faded.evaluate();
        assertEquals("alpha 0.5 is half the turn", 45f, angleZ(faded.pose(), mesh.skeleton().indexOf("spine")), 1e-2f);
        assertEquals(1f, half.alpha(), 0f);
    }

    @Test
    public void aLayersWeightScalesItsBoneMask() {
        CgSkeletalMesh mesh = body();
        CgAnimSequence raise = CgAnimSequence.builder("raise", 30, 1)
                .track("l_arm", new float[] {1, 0, 0}, rotZ(40), new float[] {1, 1, 1}).build();
        CgAnimInstance body = new CgAnimInstance(mesh);
        CgAnimLayer arms = body.addLayer("Arms").bone("l_arm", 1f).weight(0.5f);
        arms.slot(raise, 0f, 1f);
        body.evaluate();
        assertEquals(20f, angleZ(body.pose(), mesh.skeleton().indexOf("l_arm")), 0.5f);

        CgAnimLayer branches = new CgAnimInstance(mesh).addLayer("Both").branch("spine", 2).branch("l_arm", 2);
        assertEquals("overlapping branches add, at most 1", 1f, branches.boneWeights[mesh.skeleton().indexOf("l_arm")], EPS);
        assertEquals(0.5f, branches.boneWeights[mesh.skeleton().indexOf("spine")], EPS);
    }

    @Test
    public void aHiddenBoneCollapsesInSkinningAloneAndABranchTakesItsChildren() {
        CgSkeletalMesh mesh = body();
        CgSkeleton s = mesh.skeleton();
        CgAnimInstance body = new CgAnimInstance(mesh);
        body.hide("l_arm", CgAnimInstance.Hide.BONE).evaluate();
        assertTrue("its own vertices go", collapsed(body.skinning()[s.indexOf("l_arm")]));
        assertTrue("its child stays", !collapsed(body.skinning()[s.indexOf("l_hand")]));
        assertEquals("the pose is untouched", 1f, body.pose().component(s.indexOf("l_arm")).m30(), EPS);

        body.hide("l_arm", CgAnimInstance.Hide.BRANCH).evaluate();
        assertTrue(collapsed(body.skinning()[s.indexOf("l_hand")]));
        assertNull("no owner palette while nothing is hidden from the owner", body.ownerSkinning());

        body.hide("l_arm", CgAnimInstance.Hide.NONE).hideFromOwner("spine", CgAnimInstance.Hide.BRANCH).evaluate();
        assertTrue(!collapsed(body.skinning()[s.indexOf("l_hand")]));
        assertNotNull(body.ownerSkinning());
        assertTrue(collapsed(body.ownerSkinning()[s.indexOf("l_hand")]));
    }

    private static boolean collapsed(Matrix4f m) {
        return Math.abs(m.m00()) + Math.abs(m.m11()) + Math.abs(m.m22()) + Math.abs(m.m01()) + Math.abs(m.m10()) < EPS;
    }

    @Test
    public void aServerPosesOnItsTickAndHoldsWhilePaused() {
        CgSkeletalMesh mesh = body();
        int keys = 31;
        float[] t = new float[keys * 3];
        for (int k = 0; k < keys; k++) {
            t[k * 3] = 1f + k / 30f;
        }
        CgAnimSequence slide = CgAnimSequence.builder("slide", 30, keys)
                .track("l_arm", t, rotZ(0), new float[] {1, 1, 1}).build();
        CgWorld server = new CgWorld(true);
        boolean[] paused = {false};
        server.paused(() -> paused[0]);
        CgAnimation animation = server.add(new CgAnimation());
        CgAnimInstance body = animation.add(new CgAnimInstance(mesh));
        body.play(CgAnimSection.of(slide).build());
        for (int i = 0; i < 10; i++) server.tick();
        double time = body.time();
        assertTrue("ten ticks moved it: " + time, time > 0.4 && time < 0.6);
        double[] hand = new double[3];
        body.socket(body.socket("l_hand"), hand, null);
        assertEquals("the hand rode the slide, read headless", 2f + (float) time, hand[0], 0.05);

        paused[0] = true;
        for (int i = 0; i < 5; i++) server.tick();
        assertEquals("paused, it holds its time", time, body.time(), 0.0);
        server.close();
        assertTrue("the world's end stops posing it", !body.posed());
    }
}
