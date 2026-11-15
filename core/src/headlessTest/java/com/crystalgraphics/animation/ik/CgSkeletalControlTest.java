package com.crystalgraphics.animation.ik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
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
 * The bone controls on a dedicated server's classpath: each solver reaching what Unreal's reaches, world targets through
 * the component's placement, every kind written, read and played the same, and a warm frame allocating nothing.
 */
public class CgSkeletalControlTest {

    private static final float EPS = 1e-4f;
    /** root, then a chain a-b-c-d a block apart going up, and a side bone off the root. */
    private static final CgSkeleton CHAIN = CgSkeleton.builder()
            .bone("root", null, new Vector3f())
            .bone("a", "root", new Vector3f(0, 1, 0))
            .bone("b", "a", new Vector3f(0, 1, 0))
            .bone("c", "b", new Vector3f(0, 1, 0))
            .bone("d", "c", new Vector3f(0, 1, 0))
            .bone("side", "root", new Vector3f(1, 0, 0))
            .build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
        int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
        int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(2, 1f).end();
        int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(4, 1f).end();
        m.triangle(p, q, r);
    }), CHAIN).build();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        CgBuiltInCompilers.register();
    }

    private static CgAnimInstance play(CgAnimNode output, CgAnimVariable<?>... variables) {
        return new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:ik", CHAIN).variables(variables).output(output).build());
    }

    private static Vector3f at(CgAnimInstance anim, String bone) {
        return anim.pose().component(CHAIN.indexOf(bone), new Matrix4f()).getTranslation(new Vector3f());
    }

    private static void near(String what, Vector3f expected, Vector3f actual, float eps) {
        assertTrue(what + ": " + actual + " is not " + expected, actual.distance(expected) <= eps);
    }

    private static CgIkTarget point(float x, float y, float z) {
        return CgIkTarget.of(CgBoneSpace.COMPONENT, null, x, y, z);
    }

    @Test
    public void twoBoneIkPutsTheEndOnTheTargetWithTheLimbsKept() {
        CgAnimInstance anim = play(CgTwoBoneIkNode.of(CgRefPoseNode.create(), "c", point(1, 2, 0), point(-1, 2, 0))).tick(0f);
        near("the end", new Vector3f(1, 2, 0), at(anim, "c"), EPS);
        assertEquals("the upper limb's length", 1f, at(anim, "b").distance(at(anim, "a")), EPS);
        // a right angle at the knee, bent toward (-1, 2, 0): the knee straight up from the root
        near("the knee", new Vector3f(0, 2, 0), at(anim, "b"), EPS);
        anim = play(CgTwoBoneIkNode.of(CgRefPoseNode.create(), "c", point(1, 2, 0), point(2, 0, 0))).tick(0f);
        near("bent the other way", new Vector3f(1, 1, 0), at(anim, "b"), EPS);
        // out of reach: straight toward it, at full length
        anim = play(CgTwoBoneIkNode.of(CgRefPoseNode.create(), "c", point(10, 1, 0), point(0, 2, 0))).tick(0f);
        near("straightened", new Vector3f(2, 1, 0), at(anim, "c"), EPS);
    }

    @Test
    public void aWorldTargetIsReadThroughThePlacement() {
        CgAnimInstance anim = play(CgTwoBoneIkNode.of(CgRefPoseNode.create(), "c", CgIkTarget.of(CgBoneSpace.WORLD, null, 11f, 2f, 0f),
                point(-1, 2, 0))).placedBy(out -> out.translation(10, 0, 0)).tick(0f);
        near("the world's (11, 2, 0) is the component's (1, 2, 0)", new Vector3f(1, 2, 0), at(anim, "c"), EPS);
    }

    @Test
    public void fabrikAndCcdBringTheTipWithinPrecision() {
        Vector3f target = new Vector3f(1.5f, 2f, 0f);
        CgAnimInstance fabrik = play(CgFabrikNode.of(CgRefPoseNode.create(), "a", "d", point(1.5f, 2f, 0f))).tick(0f);
        assertTrue("FABRIK's tip " + at(fabrik, "d"), at(fabrik, "d").distance(target) <= 0.01f + EPS);
        for (String[] link : new String[][] {{"a", "b"}, {"b", "c"}, {"c", "d"}}) {
            assertEquals("FABRIK keeps " + link[1] + "'s length", 1f, at(fabrik, link[0]).distance(at(fabrik, link[1])), 1e-3f);
        }
        CgAnimInstance ccd = play(CgCcdIkNode.of(CgRefPoseNode.create(), "a", "d", point(1.5f, 2f, 0f)).precision(0.01f, 50)).tick(0f);
        assertTrue("CCD's tip " + at(ccd, "d"), at(ccd, "d").distance(target) <= 0.01f + EPS);
        near("CCD never moves its root", new Vector3f(0, 1, 0), at(ccd, "a"), EPS);
    }

    @Test
    public void lookAtAimsTheAxisAndClampsIt() {
        // a stands at (0, 1, 0), its +y up the chain; the target is level with it, along +x
        CgAnimInstance anim = play(CgLookAtNode.of(CgRefPoseNode.create(), "a", point(5, 1, 0))).tick(0f);
        near("b, a block along a's aimed axis", new Vector3f(1, 1, 0), at(anim, "b"), EPS);
        anim = play(CgLookAtNode.of(CgRefPoseNode.create(), "a", point(5, 1, 0)).clamp(45f)).tick(0f);
        float s = (float) Math.sqrt(0.5);
        near("clamped at 45 degrees", new Vector3f(s, 1 + s, 0), at(anim, "b"), 1e-3f);
    }

    @Test
    public void copyConstraintLimitsAndDrivenBones() {
        CgModifyBoneNode turnA = CgModifyBoneNode.of(CgRefPoseNode.create(), "a")
                .rotation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.PARENT_BONE, 0f, 0f, 90f);

        CgAnimInstance copy = play(CgCopyBoneNode.of(CgRefPoseNode.create(), "b", "side").copy(true, false, false)).tick(0f);
        near("side copies b's place", new Vector3f(0, 2, 0), at(copy, "side"), EPS);

        // side keeps its rest offset from b ((1, -2, 0) in b's space) while a turns b about z
        CgAnimInstance follow = play(CgConstraintNode.of(turnA, "side").constraint("b", CgConstraintNode.Type.PARENT, true, 1f)).tick(0f);
        near("b turned to (-1, 1, 0)", new Vector3f(-1, 1, 0), at(follow, "b"), EPS);
        near("side follows it", new Vector3f(1, 2, 0), at(follow, "side"), EPS);

        CgAnimInstance limited = play(CgApplyLimitsNode.of(turnA).limit("a", -180f, -180f, -30f, 180f, 180f, 30f)).tick(0f);
        Quaternionf q = limited.pose().rotation(CHAIN.indexOf("a"), new Quaternionf());
        assertEquals("a's 90 degrees held at 30", 30f, (float) Math.toDegrees(CgIkMath.eulerZYX(q, new Vector3f()).z), 1e-2f);

        CgAnimInstance driven = play(CgBoneDrivenControllerNode.of(CgModifyBoneNode.of(CgRefPoseNode.create(), "a")
                .rotation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.PARENT_BONE, 0f, 0f, 40f), "a",
                CgBoneDrivenControllerNode.Source.ROTATION_Z, "side")
                .affects(CgBoneDrivenControllerNode.Part.TRANSLATION_X).multiplier(0.01f)).tick(0f);
        near("40 degrees moves side 0.4 along x", new Vector3f(1.4f, 0, 0), at(driven, "side"), 1e-3f);
    }

    /** The IK foot ("side") placed in the skeleton's space, over a pose whose leg a-b-c is bent at b. */
    private static CgAnimNode bentLegWithIkFootAt(float x, float y, float z) {
        CgAnimNode n = CgModifyBoneNode.of(CgRefPoseNode.create(), "side")
                .translation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.COMPONENT, x, y, z);
        n = CgModifyBoneNode.of(n, "a").rotation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.PARENT_BONE, 0f, 0f, 30f);
        return CgModifyBoneNode.of(n, "b").rotation(CgModifyBoneNode.Mode.REPLACE, CgBoneSpace.PARENT_BONE, 0f, 0f, -60f);
    }

    @Test
    public void legIkLandsTheFootAndKeepsTheLimbs() {
        // a bent leg of three joints: the exact two-bone solve, its bend kept from the input
        CgAnimInstance leg = play(CgLegIkNode.of(bentLegWithIkFootAt(0.5f, 2.2f, 0f)).leg("side", "c")).tick(0f);
        near("the foot on the IK foot", new Vector3f(0.5f, 2.2f, 0f), at(leg, "c"), 1e-3f);
        assertEquals("the thigh", 1f, at(leg, "a").distance(at(leg, "b")), 1e-3f);
        assertEquals("the shin", 1f, at(leg, "b").distance(at(leg, "c")), 1e-3f);
        near("the hip stays", new Vector3f(0, 1, 0), at(leg, "a"), EPS);
        assertTrue("the knee still bends the way it did", at(leg, "b").x < at(leg, "a").x + 0.5f * (at(leg, "c").x - at(leg, "a").x));

        // four joints: FABRIK, its pulls averaged
        CgAnimInstance long_ = play(CgLegIkNode.of(bentLegWithIkFootAt(1f, 2.5f, 0f))
                .leg(CgLegIkNode.Leg.of("side", "d").bones(3))).tick(0f);
        assertTrue("the long leg's foot " + at(long_, "d"), at(long_, "d").distance(new Vector3f(1f, 2.5f, 0f)) <= 0.02f);
        for (String[] link : new String[][] {{"a", "b"}, {"b", "c"}, {"c", "d"}}) {
            assertEquals("keeps " + link[1] + "'s length", 1f, at(long_, link[0]).distance(at(long_, link[1])), 1e-3f);
        }
    }

    @Test
    public void aSpringBoneLagsAMoveThenSettles() {
        double[] x = {0};
        CgAnimInstance anim = play(CgSpringBoneNode.of(CgRefPoseNode.create(), "d")).placedBy(out -> out.translation(x[0], 0, 0));
        anim.tick(1 / 60f);
        near("at rest on its target", new Vector3f(0, 4, 0), at(anim, "d"), EPS);
        x[0] = 1;                                                 // the character jumps a block along +x
        anim.tick(1 / 60f);
        assertTrue("left behind: in the component, toward -x " + at(anim, "d"), at(anim, "d").x < -0.5f);
        for (int f = 0; f < 600; f++) anim.tick(1 / 60f);
        near("settled back", new Vector3f(0, 4, 0), at(anim, "d"), 1e-3f);
    }

    /** A box the length of its bone, centred along it, so each joint sits on a bone: how a chain is set up. */
    private static final CgAnimDynamicsNode.BodyDefinition LINK = CgAnimDynamicsNode.BodyDefinition.DEFAULT
            .withBox(0.1f, 1f, 0.1f).withJointOffset(0f, 0.5f, 0f);

    @Test
    public void animDynamicsHangsAChainUnderGravityWithinItsLimits() {
        // b-c-d stands straight up; free to swing 60 degrees a joint, it sags and settles
        CgAnimInstance anim = play(CgAnimDynamicsNode.of(CgRefPoseNode.create(), "b").chain("d")
                .body(LINK.withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-60f, 60f))));
        anim.tick(1 / 60f);
        near("at rest where the pose puts it", new Vector3f(0, 4, 0), at(anim, "d"), 1e-3f);
        anim.placedBy(out -> out.rotationZ(0.3f));               // tipped over, so gravity has a side to pull it to
        for (int f = 0; f < 600; f++) anim.tick(1 / 60f);
        Vector3f settled = at(anim, "d");
        assertTrue("sagged under gravity " + settled, settled.y < 3.5f);
        near("b stays on its bone", new Vector3f(0, 2, 0), at(anim, "b"), 1e-2f);
        assertEquals("the joints hold c", 1f, at(anim, "b").distance(at(anim, "c")), 0.02f);
        assertEquals("and d", 1f, at(anim, "c").distance(at(anim, "d")), 0.02f);
        for (int f = 0; f < 60; f++) anim.tick(1 / 60f);
        near("settled", settled, at(anim, "d"), 0.01f);

        // Unreal's default joint is rigid: the chain keeps the pose's shape, to the solver's give
        CgAnimInstance rigid = play(CgAnimDynamicsNode.of(CgRefPoseNode.create(), "b").chain("d").body(LINK))
                .placedBy(out -> out.rotationZ(0.3f));
        for (int f = 0; f < 300; f++) rigid.tick(1 / 60f);
        near("rigid", new Vector3f(0, 4, 0), at(rigid, "d"), 0.05f);
    }

    @Test
    public void aBoolAlphaBlendsInOverItsTime() {
        CgAnimVariable<Boolean> on = CgAnimVariable.bool("on", false);
        CgAnimInstance anim = play(CgModifyBoneNode.of(CgRefPoseNode.create(), "a")
                .translation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.COMPONENT, 1f, 0f, 0f)
                .alpha(CgControlAlpha.bool(on, 0.2f, 0.2f)), on);
        anim.tick(0.1f);
        near("off: untouched", new Vector3f(0, 1, 0), at(anim, "a"), EPS);
        anim.set(on, true);
        anim.tick(0.1f);
        near("half way in", new Vector3f(0.5f, 1, 0), at(anim, "a"), 1e-3f);
        anim.tick(0.1f);
        near("in", new Vector3f(1, 1, 0), at(anim, "a"), EPS);
    }

    /** Every control kind in one graph, its values from variables where a kind takes them. */
    private static CgAnimGraph everyKind() {
        CgAnimVariable<Float> x = CgAnimVariable.floating("x", 1.2f), weight = CgAnimVariable.floating("weight", 0.5f);
        CgAnimVariable<Boolean> on = CgAnimVariable.bool("on", true);
        CgAnimNode n = CgModifyBoneNode.of(CgRefPoseNode.create(), "a")
                .rotation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.PARENT_BONE, null, null, x).alpha(CgControlAlpha.bool(on, 0.1f, 0.3f));
        n = CgTwoBoneIkNode.of(n, "c", CgIkTarget.of(CgBoneSpace.COMPONENT, null, x, null, null), point(-1, 2, 0)).stretch(1f, 1.2f);
        n = CgLookAtNode.of(n, "d", CgIkTarget.of(CgBoneSpace.BONE, "side", 0f, 1f, 0f)).clamp(60f)
                .interpolation(0.2f, CgLookAtNode.Blend.SINUSOIDAL, 0.1f);
        n = CgCopyBoneNode.of(n, "b", "side").copy(false, true, false).space(CgBoneSpace.PARENT_BONE);
        n = CgFabrikNode.of(n, "a", "d", point(1.5f, 2f, 0f)).tipRotation(CgFabrikNode.TipRotation.KEEP_COMPONENT)
                .alpha(CgControlAlpha.of(weight).scaleBias(2f, 0f).clamp(0f, 1f).interp(4f, 2f));
        n = CgCcdIkNode.of(n, "a", "d", point(1f, 3f, 0f)).rotationLimits(true, 20f, 20f, 20f).startFromTail(false);
        n = CgConstraintNode.of(n, "side").constraint("b", CgConstraintNode.Type.ROTATION, true, false, false, true, weight);
        n = CgApplyLimitsNode.of(n).limit("a", -30f, -30f, -30f, 30f, 30f, 30f);
        n = CgBoneDrivenControllerNode.of(n, "a", CgBoneDrivenControllerNode.Source.ROTATION_Z, "side")
                .affects(CgBoneDrivenControllerNode.Part.TRANSLATION_X, CgBoneDrivenControllerNode.Part.ROTATION_Y)
                .range(-30f, 30f, 0f, 1f).mode(CgBoneDrivenControllerNode.Mode.ADD_TO_REF_POSE);
        n = CgSpringBoneNode.of(n, "d").spring(60f, 5f).translate(true, false, true).rotate(false, false, true).limit(0.3f);
        n = CgAnimDynamicsNode.of(n, "b").chain("d")
                .body(CgAnimDynamicsNode.BodyDefinition.DEFAULT.withConstraint(CgAnimDynamicsNode.ConstraintSetup.DEFAULT.withAngularLimits(-40f, 40f)))
                .physics(CgAnimDynamicsNode.Physics.DEFAULT.withGravityScale(0.5f).withSprings(4f, null))
                .spherical(new CgAnimDynamicsNode.SphericalLimit("a", new Vector3f(1f, 0f, 0f), 0.3f, CgAnimDynamicsNode.SphereLimitType.OUTER))
                .planar(new CgAnimDynamicsNode.PlanarLimit(null, new Vector3f(0f, -5f, 0f), new Vector3f(0f, 1f, 0f)));
        n = CgLegIkNode.of(n).leg(CgLegIkNode.Leg.of("side", "d").bones(3).rotationLimit(20f).hinge(CgLegIkNode.Axis.Z))
                .soft(0.95f, 0.5f);
        return CgAnimGraph.builder("test:controls", CHAIN).variables(x, weight, on).output(n).build();
    }

    @Test
    public void everyKindWritesReadsAndPlaysTheSame() {
        CgAnimGraph java = everyKind();
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(java), CgBodyKinds.ANIM_GRAPH);
        CgAnimGraph data = CgAnimGraphCodec.compile(CgAssetData.of("test:controls", CgBodyKinds.ANIM_GRAPH,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), null).graph(CHAIN, id -> null);
        assertEquals("written again, the same file", yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(data), CgBodyKinds.ANIM_GRAPH));
        CgAnimInstance a = new CgAnimInstance(MESH).graph(java), b = new CgAnimInstance(MESH).graph(data);
        for (int f = 0; f < 60; f++) {
            a.tick(1 / 60f);
            b.tick(1 / 60f);
            for (int bone = 0; bone < CHAIN.boneCount(); bone++) {
                assertTrue("frame " + f + ", " + CHAIN.name(bone),
                        a.pose().component(bone, new Matrix4f()).equals(b.pose().component(bone, new Matrix4f()), 1e-5f));
            }
        }
    }

    @Test
    public void aWarmFrameAllocatesNothing() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(everyKind()).placedBy(out -> out.translation(3, 0, 0));
        for (int f = 0; f < 20000; f++) anim.tick(1 / 60f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) anim.tick(1 / 60f);
        // Under a byte a frame: an allocation every frame is 16 bytes at least
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }
}
