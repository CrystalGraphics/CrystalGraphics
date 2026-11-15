package com.crystalgraphics.mc.model;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.animation.retarget.CgRetargetProcessor;
import com.crystalgraphics.animation.retarget.CgRetargeter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Minecraft's humanoid as the engine poses it: the game's pivots at rest, its part angles, a retarget onto it. */
public class CgVanillaHumanoidTest {

    private static final int S = CgVanillaHumanoid.STRIDE;

    @Test
    public void atRestEveryPartIsWhereTheGamePivotsIt() {
        float[] parts = new float[CgVanillaHumanoid.PARTS.length * S];
        CgVanillaHumanoid.parts(new CgAnimPose(CgVanillaHumanoid.SKELETON), parts);
        float[][] game = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9f, 12, 0}, {1.9f, 12, 0}};
        for (int i = 0; i < game.length; i++) {
            for (int k = 0; k < 3; k++) assertEquals(CgVanillaHumanoid.PARTS[i], game[i][k], parts[i * S + k], 1e-4f);
            for (int k = 3; k < 6; k++) assertEquals(CgVanillaHumanoid.PARTS[i] + " unturned", 0f, parts[i * S + k], 1e-5f);
        }
    }

    @Test
    public void aTurnIsWrittenAsTheGameTurnsAPart() {
        CgAnimPose pose = new CgAnimPose(CgVanillaHumanoid.SKELETON);
        int arm = CgVanillaHumanoid.SKELETON.indexOf("right_arm");
        Quaternionf turn = new Quaternionf().rotateX(-1.2f).rotateY(0.4f).rotateZ(0.3f);
        Vector3f t = pose.translation(arm, new Vector3f());
        pose.set(arm, t.x, t.y, t.z, turn.x, turn.y, turn.z, turn.w, 1f, 1f, 1f);
        float[] parts = new float[CgVanillaHumanoid.PARTS.length * S];
        CgVanillaHumanoid.parts(pose, parts);
        int o = CgVanillaHumanoid.RIGHT_ARM * S;
        // the game turns Z, then Y, then X, in its model's frame: ours scaled by (-1, -1, 1)
        Quaternionf game = new Quaternionf().rotationZYX(parts[o + 5], parts[o + 4], parts[o + 3]);
        Quaternionf expected = new Quaternionf(-turn.x, -turn.y, turn.z, turn.w);
        assertTrue(game + " vs " + expected, Math.abs(game.dot(expected)) > 0.99999f);
    }

    @Test
    public void aStandardHumanoidsRaisedArmRaisesTheVanillaArm() {
        CgRig rig = CgVanillaHumanoid.rig();
        assertNotNull(rig);
        assertNotNull("found by its names", CgRigs.detect(CgVanillaHumanoid.SKELETON, CgRigAdapters.VANILLA));
        CgSkeleton source = standard();
        CgRig from = CgRigs.detect(source, CgRigAdapters.CRYSTALGRAPHICS);
        CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(source, from, CgVanillaHumanoid.SKELETON, rig), source,
                CgVanillaHumanoid.SKELETON);
        CgAnimPose in = new CgAnimPose(source), out = new CgAnimPose(CgVanillaHumanoid.SKELETON);
        int upper = source.indexOf("upperarm_r");
        Quaternionf up = in.rotation(upper, new Quaternionf()).mul(new Quaternionf().rotateZ(-(float) Math.PI / 2f));
        Vector3f t = in.translation(upper, new Vector3f());
        in.set(upper, t.x, t.y, t.z, up.x, up.y, up.z, up.w, 1f, 1f, 1f);
        p.run(in, out);
        out.resolve();
        Vector3f shoulder = out.component(CgVanillaHumanoid.SKELETON.indexOf("right_arm"), new org.joml.Matrix4f()).getTranslation(new Vector3f());
        Vector3f hand = out.component(CgVanillaHumanoid.SKELETON.indexOf("right_hand"), new org.joml.Matrix4f()).getTranslation(new Vector3f());
        Vector3f dir = hand.sub(shoulder).normalize();
        assertEquals("the rigid arm points straight up: " + dir, 1f, dir.y, 0.01f);
    }

    /** A standard humanoid standing arms out, facing +z; its right arm along -x. */
    private static CgSkeleton standard() {
        CgSkeleton.Builder b = CgSkeleton.builder();
        b.bone("root", null, new Vector3f());
        b.bone("pelvis", "root", new Vector3f(0f, 1.0f, 0f));
        b.bone("spine_01", "pelvis", new Vector3f(0f, 0.1f, 0f));
        b.bone("spine_02", "spine_01", new Vector3f(0f, 0.15f, 0f));
        b.bone("spine_03", "spine_02", new Vector3f(0f, 0.15f, 0f));
        b.bone("head", "spine_03", new Vector3f(0f, 0.25f, 0f));
        for (String s : new String[] {"l", "r"}) {
            float x = s.equals("l") ? 1f : -1f;
            b.bone("upperarm_" + s, "spine_03", new Vector3f(0.17f * x, 0.1f, 0f));
            b.bone("lowerarm_" + s, "upperarm_" + s, new Vector3f(0.28f * x, 0f, 0f));
            b.bone("hand_" + s, "lowerarm_" + s, new Vector3f(0.25f * x, 0f, 0f));
            b.bone("thigh_" + s, "pelvis", new Vector3f(0.1f * x, -0.05f, 0f));
            b.bone("calf_" + s, "thigh_" + s, new Vector3f(0f, -0.45f, 0.02f));
            b.bone("foot_" + s, "calf_" + s, new Vector3f(0f, -0.42f, -0.02f));
        }
        return b.build();
    }
}
