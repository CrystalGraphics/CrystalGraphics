package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.sun.management.ThreadMXBean;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A standard humanoid (hips at 0.95 m, standing arms out, facing +z) retargeted onto GeckoLib's humanoid (hips at
 * 0.75 m, arms down, facing -z), on a dedicated server's classpath.
 */
public class CgRetargetTest {

    private static final CgSkeleton STANDARD = standard(), GECKO = gecko();
    private static final CgRig FROM = CgRigs.detect(STANDARD, CgRigAdapters.CRYSTALGRAPHICS),
            TO = CgRigs.detect(GECKO, CgRigAdapters.GECKOLIB);
    private static final float RATIO = 0.75f / 0.95f;

    private static CgSkeleton standard() {
        CgSkeleton.Builder b = CgSkeleton.builder();
        b.bone("root", null, new Vector3f());
        b.bone("pelvis", "root", new Vector3f(0f, 1.0f, 0f));
        b.bone("spine_01", "pelvis", new Vector3f(0f, 0.1f, 0f));
        b.bone("spine_02", "spine_01", new Vector3f(0f, 0.15f, 0f));
        b.bone("spine_03", "spine_02", new Vector3f(0f, 0.15f, 0f));
        b.bone("neck_01", "spine_03", new Vector3f(0f, 0.15f, 0f));
        b.bone("head", "neck_01", new Vector3f(0f, 0.1f, 0f));
        for (String s : new String[] {"l", "r"}) {
            float x = s.equals("l") ? 1f : -1f;
            b.bone("clavicle_" + s, "spine_03", new Vector3f(0.05f * x, 0.1f, 0f));
            b.bone("upperarm_" + s, "clavicle_" + s, new Vector3f(0.12f * x, 0f, 0f));
            b.bone("lowerarm_" + s, "upperarm_" + s, new Vector3f(0.28f * x, 0f, 0f));
            b.bone("hand_" + s, "lowerarm_" + s, new Vector3f(0.25f * x, 0f, 0f));
            b.bone("thigh_" + s, "pelvis", new Vector3f(0.1f * x, -0.05f, 0f));
            b.bone("calf_" + s, "thigh_" + s, new Vector3f(0f, -0.45f, 0.02f));
            b.bone("foot_" + s, "calf_" + s, new Vector3f(0f, -0.42f, -0.02f));
            b.bone("ball_" + s, "foot_" + s, new Vector3f(0f, -0.05f, 0.12f));
        }
        return b.build();
    }

    private static CgSkeleton gecko() {
        CgSkeleton.Builder b = CgSkeleton.builder();
        b.bone("geometry.custom_model", null, new Vector3f());
        b.bone("model", "geometry.custom_model", new Vector3f(0f, 0.75f, 0f));
        b.bone("body", "model", new Vector3f());
        b.bone("head", "body", new Vector3f(0f, 0.75f, 0f));
        for (String s : new String[] {"left", "right"}) {
            float x = s.equals("left") ? -1f : 1f;   // facing -z, its left is -x
            b.bone(s + "_arm", "body", new Vector3f(0.3125f * x, 0.6875f, 0f));
            b.bone(s + "_arm_2", s + "_arm", new Vector3f(0f, -0.3f, 0f));
            b.bone(s + "_hand_locator", s + "_arm_2", new Vector3f(0f, -0.3f, 0f));
            b.bone(s + "_leg", "model", new Vector3f(0.125f * x, 0f, 0f));
            b.bone(s + "_leg_2", s + "_leg", new Vector3f(0f, -0.375f, 0f));
            b.bone(s + "_foot_locator", s + "_leg_2", new Vector3f(0f, -0.25f, 0f));
        }
        return b.build();
    }

    private static CgRetargetProcessor processor() {
        return new CgRetargetProcessor(CgRetargeter.auto(STANDARD, FROM, GECKO, TO), STANDARD, GECKO);
    }

    private static Vector3f at(CgAnimPose pose, String bone) {
        return pose.resolve().component(pose.skeleton().indexOf(bone), new org.joml.Matrix4f()).getTranslation(new Vector3f());
    }

    private static void turn(CgAnimPose pose, String bone, Quaternionf by) {
        int b = pose.skeleton().indexOf(bone);
        Vector3f t = pose.translation(b, new Vector3f());
        Quaternionf r = pose.rotation(b, new Quaternionf()).mul(by);
        pose.set(b, t.x, t.y, t.z, r.x, r.y, r.z, r.w, 1f, 1f, 1f);
    }

    private static void move(CgAnimPose pose, String bone, float dx, float dy, float dz) {
        int b = pose.skeleton().indexOf(bone);
        Vector3f t = pose.translation(b, new Vector3f()).add(dx, dy, dz);
        Quaternionf r = pose.rotation(b, new Quaternionf());
        pose.set(b, t.x, t.y, t.z, r.x, r.y, r.z, r.w, 1f, 1f, 1f);
    }

    @Test
    public void bothDefaultRigsAreFoundFromTheirNames() {
        assertNotNull(FROM);
        assertNotNull(TO);
        assertEquals("upperarm_l", FROM.chain("arm_l").start());
        assertEquals("left_foot_locator", TO.chain("leg_l").end());
        assertEquals("the knee bends toward its front", -1f, TO.chain("leg_l").bend().z(), 1e-6f);
    }

    @Test
    public void chainsMapByNameAndArmsAlignToTheSource() {
        CgRetargeter r = CgRetargeter.auto(STANDARD, FROM, GECKO, TO);
        assertEquals("arm_l", r.chain("arm_l").source());
        assertEquals("spine", r.chain("spine").source());
        assertTrue("legs solve by IK", r.chain("leg_l").ik());
        assertTrue("the arms are turned out", r.targetPose().containsKey("left_arm"));

        CgAnimPose out = new CgAnimPose(GECKO);
        processor().run(new CgAnimPose(STANDARD), out);
        Vector3f shoulder = at(out, "left_arm"), hand = at(out, "left_hand_locator");
        assertEquals("the hand out at shoulder height", shoulder.y, hand.y, 0.01f);
        assertEquals("the hand out to its left", shoulder.x - 0.6f, hand.x, 0.01f);
    }

    @Test
    public void aRaisedArmIsRaisedToTheTargetsReach() {
        CgAnimPose in = new CgAnimPose(STANDARD), out = new CgAnimPose(GECKO);
        turn(in, "upperarm_l", new Quaternionf().rotateZ((float) Math.PI / 2f));   // +x onto +y
        processor().run(in, out);
        Vector3f shoulder = at(out, "left_arm"), hand = at(out, "left_hand_locator");
        assertEquals(shoulder.x, hand.x, 0.01f);
        assertEquals("the target arm's own length up", shoulder.y + 0.6f, hand.y, 0.01f);
        assertEquals(shoulder.z, hand.z, 0.01f);
    }

    @Test
    public void thePelvisMovesByTheHeightRatioInTheTargetsFrame() {
        CgRetargetProcessor p = processor();
        assertEquals(RATIO, p.heightRatio(), 1e-4f);
        CgAnimPose in = new CgAnimPose(STANDARD), out = new CgAnimPose(GECKO);
        move(in, "pelvis", 0f, -0.1f, 0.2f);   // down and toward its front
        p.run(in, out);
        Vector3f pelvis = at(out, "model");
        assertEquals(0.75f - 0.1f * RATIO, pelvis.y, 1e-4f);
        assertEquals("toward the target's front, -z", -0.2f * RATIO, pelvis.z, 1e-4f);
    }

    @Test
    public void aFootOnTheFloorStaysOnIt() {
        CgAnimPose in = new CgAnimPose(STANDARD), out = new CgAnimPose(GECKO);
        move(in, "pelvis", 0f, -0.1f, 0f);
        turn(in, "thigh_l", new Quaternionf().rotateX(-0.6f));
        turn(in, "calf_l", new Quaternionf().rotateX(1.2f));
        CgAnimPose rest = new CgAnimPose(STANDARD);
        CgRetargetProcessor p = processor();
        p.run(rest, out);
        float standing = at(out, "left_foot_locator").y;
        assertEquals("at rest, at its own height", 0.125f, standing, 0.005f);
        p.run(in, out);
        Vector3f goal = new Vector3f();
        assertTrue(p.goal("leg_l", goal));
        assertTrue("crouched, the foot reaches its goal", at(out, "left_foot_locator").distance(goal) < 0.01f);
        assertTrue("the knee bends forward, as the rig says", at(out, "left_leg_2").z < at(out, "left_leg").z);
    }

    @Test
    public void whatIsNotImplementedIsRefusedByName() {
        CgRetargeter base = CgRetargeter.auto(STANDARD, FROM, GECKO, TO);
        try {
            new CgRetargetProcessor(base.toBuilder().ops(new CgRetargeter.Op("pin_bone", 0, 0, 0, 0)).build(), STANDARD, GECKO);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("pin_bone"));
        }
        try {
            new CgRetargetProcessor(base.toBuilder().chain(CgRetargeter.ChainMap.of("spine", "spine")
                    .fk(CgRetargeter.Rotation.MATCH_CHAIN, CgRetargeter.Translation.NONE)).build(), STANDARD, GECKO);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("MATCH_CHAIN"));
        }
        try {
            new CgRetargetProcessor(base.toBuilder().chain(CgRetargeter.ChainMap.of("arm_l", "arm_l")
                    .fk(CgRetargeter.Rotation.INTERPOLATED, CgRetargeter.Translation.ABSOLUTE)).build(), STANDARD, GECKO);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("only rotates"));
        }
    }

    @Test
    public void aRigWritesAndReadsTheSame() {
        assertEquals(TO, CgRig.compile(TO.id(), TO.toTree()));
        assertEquals(FROM, CgRig.compile(FROM.id(), FROM.toTree()));
    }

    private static CgAnimSequence raise() {
        int frames = 10;
        float[] r = new float[frames * 4];
        Quaternionf q = new Quaternionf();
        for (int f = 0; f < frames; f++) {
            q.rotationZ(f * 0.15f);
            r[f * 4] = q.x;
            r[f * 4 + 1] = q.y;
            r[f * 4 + 2] = q.z;
            r[f * 4 + 3] = q.w;
        }
        return CgAnimSequence.builder("raise", 30, frames).track("upperarm_l", new float[] {0.12f, 0f, 0f}, r, new float[] {1f, 1f, 1f}).build();
    }

    @Test
    public void aBakedClipPlaysWhatTheProcessorMakes() {
        CgRetargetProcessor p = processor();
        CgAnimSequence clip = raise(), baked = CgRetargetBake.cached(p, clip);
        assertSame("cached", baked, CgRetargetBake.cached(p, clip));
        assertEquals(clip.frameCount(), baked.frameCount());
        assertTrue("a bone the clip leaves still keeps one key", baked.held(baked.track("right_leg")));
        CgAnimPose in = new CgAnimPose(STANDARD), expect = new CgAnimPose(GECKO), got = new CgAnimPose(GECKO);
        for (int k = 0; k < clip.frameCount(); k++) {
            float t = k / 30f;
            clip.sample(in.rest(), t);
            p.run(in, expect);
            baked.sample(got.rest(), t);
            float gap = at(expect, "left_hand_locator").distance(at(got, "left_hand_locator"));
            assertTrue("frame " + k + ": " + gap + " " + at(expect, "left_hand_locator") + " vs " + at(got, "left_hand_locator"), gap < 1e-3f);   // a clip key's own precision
        }
    }

    @Test
    public void aCharactersOwnClipWinsThenTheRetargetedThenNone() {
        CgAnimSequence own = CgAnimSequence.builder("raise", 30, 1).build(), theirs = raise();
        CgClipResolver clips = new CgClipResolver(Map.of("raise", own)::get, Map.of("raise", theirs, "wave", theirs)::get, processor());
        assertSame("its own", own, clips.resolve("raise"));
        CgAnimSequence wave = clips.resolve("wave");
        assertTrue("retargeted onto its bones", wave.track("left_arm") >= 0 && wave.track("upperarm_l") < 0);
        try {
            clips.resolve("kick");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("kick"));
        }
    }

    @Test
    public void aRetargeterWritesReadsAndPlaysTheSame() {
        CgRetargeter java = CgRetargeter.auto(STANDARD, FROM, GECKO, TO).toBuilder()
                .chain(CgRetargeter.ChainMap.of("head", "head").fk(CgRetargeter.Rotation.ONE_TO_ONE, CgRetargeter.Translation.NONE))
                .ops(CgRetargeter.Op.scaleSource(1f), CgRetargeter.Op.pelvisMotion(0.8f, 1f), CgRetargeter.Op.FK_CHAINS,
                        CgRetargeter.Op.IK_CHAINS, CgRetargeter.Op.floor(0.5f))
                .build();
        String yaml = CgAssetYaml.write(java.toTree(), CgBodyKinds.RETARGETER);
        Map<String, CgRig> rigs = Map.of(FROM.id(), FROM, TO.id(), TO);
        CgRetargeter data = CgRetargeter.compile(java.id(), (Map<?, ?>) CgAssetYaml.read(yaml), CgUnits.HOST, rigs::get);
        assertEquals(java.chains(), data.chains());
        assertEquals(java.ops(), data.ops());
        assertEquals(java.targetPose().keySet(), data.targetPose().keySet());
        java.targetPose().forEach((bone, q) -> assertTrue(bone, Math.abs(q.dot(data.targetPose().get(bone))) > 1f - 1e-5f));
        CgAnimPose in = new CgAnimPose(STANDARD), a = new CgAnimPose(GECKO), b = new CgAnimPose(GECKO);
        turn(in, "upperarm_l", new Quaternionf().rotateZ(0.7f));
        new CgRetargetProcessor(java, STANDARD, GECKO).run(in, a);
        new CgRetargetProcessor(data, STANDARD, GECKO).run(in, b);
        assertTrue(at(a, "left_hand_locator").distance(at(b, "left_hand_locator")) < 1e-4f);
    }

    @Test
    public void aSettingNothingImplementsFailsAtLoadByName() {
        Map<String, Object> tree = CgRetargeter.auto(STANDARD, FROM, GECKO, TO).toTree();
        tree.put("ops", List.of(Map.of("id", "o1", "op", "curve_remap")));
        Map<String, CgRig> rigs = Map.of(FROM.id(), FROM, TO.id(), TO);
        try {
            CgRetargeter.compile("test:r", tree, CgUnits.HOST, rigs::get);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("curve_remap"));
        }
    }

    @Test
    public void runningAllocatesNothing() {
        CgRetargetProcessor p = processor();
        CgAnimPose in = new CgAnimPose(STANDARD), out = new CgAnimPose(GECKO);
        turn(in, "calf_l", new Quaternionf().rotateX(0.5f));
        for (int i = 0; i < 20000; i++) p.run(in, out);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int runs = 10000;
        for (int i = 0; i < runs; i++) p.run(in, out);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + runs + " runs", bytes < runs);
    }
}
