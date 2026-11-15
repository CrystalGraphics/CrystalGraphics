package com.crystalgraphics.animation.blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.graph.CgTwoWayBlendNode;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAdditive;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipSettings;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSyncMarker;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.sun.management.ThreadMXBean;

/**
 * Blend spaces, sync groups and aim offsets on a dedicated server's classpath, every number worked by hand from Unreal's
 * {@code UBlendSpace} (4.21 and release), {@code FAnimSync} and the marker sync of {@code UAnimSequence}.
 */
public class CgBlendSpaceTest {

    private static final float EPS = 1e-5f, DT = 1f / 30f;
    private static final CgAnimVariable<Float> RUN = CgAnimVariable.floating("run");
    private static final CgAnimVariable<Float> ALPHA = CgAnimVariable.floating("alpha");
    private static final CgSkeleton ARM = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("elbow", "root", new Vector3f(0, 1, 0)).build();

    /** A still clip {@code seconds} long (30 keys a second), with sync markers. */
    private static CgAnimSequence clip(String name, float seconds, CgSyncMarker... markers) {
        int keys = Math.round(seconds * 30) + 1;
        CgAnimSequence c = CgAnimSequence.builder(name, 30, keys).build();
        return markers.length == 0 ? c : c.with(CgClipSettings.NONE.withMarkers(List.of(markers)));
    }

    private static final CgAnimSequence RUN01 = clip("Run01", 1.0f), RUN02 = clip("Run02", 0.8f), RUN03 = clip("Run03", 0.6f);

    /** StandMoveLP as Kakarot cooked it: Run 0..2, its three loops at 0, 1, 2, weights at 5 a second, 4.21's switches. */
    private static CgBlendSpace standMoveLp(CgAnimSequence a, CgAnimSequence b, CgAnimSequence c) {
        return CgBlendSpace.oneD("kakarot:BS_C001A_StandMoveLP", "Run", 0, 2).sample(a, 0).sample(b, 1).sample(c, 2)
                .weightSpeed(5).weightEase(false).gridInterpolation(true).legacyLength(true).build();
    }

    private static CgAnimGraphInstance run(CgAnimNode output) {
        return new CgAnimGraphInstance(CgAnimGraph.builder("test:blend", ARM).variables(RUN, ALPHA).output(output).build(), ARM);
    }

    private static void frame(CgAnimGraphInstance g, float run, float dt) {
        g.set(RUN, run).preUpdate();
        g.update(dt);
    }

    private static float weightOf(CgBlendSpacePlayerNode.PlayerRuntime p, int sample) {
        for (int i = 0; i < p.samples(); i++) if (p.sample(i) == sample) return p.sampleWeight(i);
        return 0f;
    }

    @Test
    public void standMoveLpsWeightsMoveFiveASecondAndNormalise() {
        CgBlendSpacePlayerNode node = CgBlendSpacePlayerNode.of(standMoveLp(RUN01, RUN02, RUN03), RUN);
        CgAnimGraphInstance g = run(node);
        CgBlendSpacePlayerNode.PlayerRuntime p = (CgBlendSpacePlayerNode.PlayerRuntime) g.runtime(node);
        frame(g, 0f, DT);
        // Nothing before: Run01 enters at 5/30 and normalises alone to 1
        assertEquals(1f, weightOf(p, 0), EPS);
        frame(g, 1.5f, DT);
        // Grid point 1.5 is Run02 and Run03 half each; Run01 falls 1/6, they rise 1/6 each: 5/6, 1/6, 1/6 over 7/6
        assertEquals(5f / 7f, weightOf(p, 0), EPS);
        assertEquals(1f / 7f, weightOf(p, 1), EPS);
        assertEquals(1f / 7f, weightOf(p, 2), EPS);
        frame(g, 1.5f, DT);
        // 5/7 − 1/6 = 23/42, 1/7 + 1/6 = 13/42 each, over 49/42
        assertEquals(23f / 49f, weightOf(p, 0), EPS);
        assertEquals(13f / 49f, weightOf(p, 1), EPS);
        assertEquals(13f / 49f, weightOf(p, 2), EPS);
        for (int f = 0; f < 30; f++) frame(g, 1.5f, DT);
        assertEquals("settled on its targets", 0.5f, weightOf(p, 1), EPS);
        assertEquals(0f, weightOf(p, 0), EPS);
    }

    @Test
    public void releaseEasesTheWeightsByACriticallyDampedSpring() {
        CgBlendSpace eased = CgBlendSpace.oneD("test:eased", "run", 0, 2).sample(RUN01, 0).sample(RUN02, 1).weightSpeed(5).build();
        CgBlendSpacePlayerNode node = CgBlendSpacePlayerNode.of(eased, RUN);
        CgAnimGraphInstance g = run(node);
        CgBlendSpacePlayerNode.PlayerRuntime p = (CgBlendSpacePlayerNode.PlayerRuntime) g.runtime(node);
        frame(g, 0f, DT);
        frame(g, 1f, DT);
        // CriticallyDampedSmoothing at time 1/(e·5): W = 2/time, exp = InvExpApprox(W·dt). Frame 1 brought Run01 in from
        // rest (x0 −1), leaving it the rate W²·dt·exp; normalising touched its weight, not its rate. Frame 2 takes it from 1
        // toward 0 with that rate, and Run02 in from rest toward 1.
        float s = 1f / ((float) Math.E * 5f), w = 2f / s, a = w * DT;
        float exp = 1f / (1f + 1.00746054f * a + 0.45053901f * a * a + 0.25724632f * a * a * a);
        float rate = w * w * DT * exp;
        float down = (1f + (rate + w) * DT) * exp, up = 1f - (1f + w * DT) * exp;
        assertEquals(down / (down + up), weightOf(p, 0), EPS);
        assertEquals(up / (down + up), weightOf(p, 1), EPS);
    }

    @Test
    public void lengthsAre421sSummedOrReleasesInverted() {
        CgBlendSpace legacy = CgBlendSpace.oneD("test:legacy", "run", 0, 2).sample(RUN01, 0).sample(RUN02, 1).legacyLength(true).build();
        CgBlendSpacePlayerNode n1 = CgBlendSpacePlayerNode.of(legacy, RUN);
        CgAnimGraphInstance g1 = run(n1);
        frame(g1, 0.5f, 0.09f);
        // Half each: 4.21's length 0.5·1.0 + 0.5·0.8 = 0.9, so 0.09 s is a tenth; Run01 at 0.1 s, Run02 at 0.08 s
        CgBlendSpacePlayerNode.PlayerRuntime p1 = (CgBlendSpacePlayerNode.PlayerRuntime) g1.runtime(n1);
        assertEquals(0.1f, p1.accumulatedTime(), EPS);
        assertEquals(0.1f, p1.sampleTime(0), EPS);
        assertEquals(0.08f, p1.sampleTime(1), EPS);

        CgBlendSpace release = CgBlendSpace.oneD("test:release", "run", 0, 2).sample(RUN01, 0).sample(RUN02, 1).build();
        CgBlendSpacePlayerNode n2 = CgBlendSpacePlayerNode.of(release, RUN);
        CgAnimGraphInstance g2 = run(n2);
        frame(g2, 0.5f, 0.09f);
        // Release: 1 / (0.5/1.0 + 0.5/0.8) = 1/1.125, so 0.09 s is 0.10125
        assertEquals(0.10125f, ((CgBlendSpacePlayerNode.PlayerRuntime) g2.runtime(n2)).accumulatedTime(), EPS);
    }

    /** Where {@code time} sits in a two-marker step cycle: 0..1 from foot_l to foot_r, 1..2 from foot_r round to foot_l. */
    private static float phase(CgAnimSequence clip, float time) {
        float l = clip.settings().markers().get(0).time(), r = clip.settings().markers().get(1).time(), len = clip.duration();
        if (time >= l && time < r) return (time - l) / (r - l);
        float since = time >= r ? time - r : time + len - r;
        return 1f + since / (len - r + l);
    }

    @Test
    public void feetStayInPhaseAcrossABlend() {
        CgAnimSequence walk = clip("walk", 1.0f, new CgSyncMarker("foot_l", 0.25f), new CgSyncMarker("foot_r", 0.75f));
        CgAnimSequence jog = clip("jog", 0.8f, new CgSyncMarker("foot_l", 0.1f), new CgSyncMarker("foot_r", 0.6f));
        CgBlendSpacePlayerNode node = CgBlendSpacePlayerNode.of(standMoveLp(walk, jog, RUN03), RUN);
        CgAnimGraphInstance g = run(node);
        CgBlendSpacePlayerNode.PlayerRuntime p = (CgBlendSpacePlayerNode.PlayerRuntime) g.runtime(node);
        for (int f = 0; f < 90; f++) {
            frame(g, 0.4f + 0.2f * (float) Math.sin(f * 0.1f), DT);
            assertEquals("frame " + f, phase(walk, p.sampleTime(0)), phase(jog, p.sampleTime(1)), 1e-4f);
        }
    }

    @Test
    public void aSyncGroupsFollowerKeepsItsLeadersStep() {
        CgAnimSequence walk = clip("walk", 1.0f, new CgSyncMarker("foot_l", 0.25f), new CgSyncMarker("foot_r", 0.75f));
        CgAnimSequence run = clip("run", 0.6f, new CgSyncMarker("foot_l", 0.1f), new CgSyncMarker("foot_r", 0.4f));
        CgAnimGraphInstance g = run(CgTwoWayBlendNode.of(CgSequencePlayerNode.of(walk).loop().sync(CgSyncGroup.of("feet")),
                CgSequencePlayerNode.of(run).loop().sync(CgSyncGroup.of("feet")), ALPHA));
        g.set(ALPHA, 0.3f);
        for (int f = 0; f < 60; f++) {
            frame(g, 0f, DT);
            float tw = time(g, "walk"), tr = time(g, "run");
            // Walk weighs 0.7 and leads at its own rate; run lands at its place between the same markers
            if (f == 0) assertEquals(DT, tw, EPS);
            assertEquals("frame " + f, phase(walk, tw), phase(run, tr), 1e-4f);
        }
        assertTrue("walk, the heavier, leads", g.sync().leads("feet", g.samplePlayer(index(g, "walk"))));
    }

    @Test
    public void withoutMarkersAFollowerKeepsItsLeadersFraction() {
        CgAnimSequence walk = clip("walk", 1.0f), run = clip("run", 0.6f);
        CgAnimGraphInstance g = run(CgTwoWayBlendNode.of(CgSequencePlayerNode.of(walk).loop().sync(CgSyncGroup.of("feet")),
                CgSequencePlayerNode.of(run).loop().sync(CgSyncGroup.of("feet")), ALPHA));
        g.set(ALPHA, 0.3f);
        for (int f = 0; f < 45; f++) {
            frame(g, 0f, DT);
            assertEquals("frame " + f, time(g, "walk") / 1.0f, time(g, "run") / 0.6f, EPS);
        }
    }

    private static int index(CgAnimGraphInstance g, String clip) {
        for (int i = 0; i < g.samples(); i++) if (g.sampleClip(i).name().equals(clip)) return i;
        throw new AssertionError("no sample of " + clip);
    }

    private static float time(CgAnimGraphInstance g, String clip) {
        return g.sampleTime(index(g, clip));
    }

    @Test
    public void anAimOffsetTurnsTheBaseInTheMeshsSpace() {
        // The elbow aimed a quarter turn about z in the mesh's space, over a root turned a quarter about y
        Quaternionf aim = new Quaternionf().rotateZ((float) Math.PI / 2), root = new Quaternionf().rotateY((float) Math.PI / 2);
        CgAnimSequence up = CgAnimSequence.builder("aim_up", 30, 1)
                .track("elbow", new float[] {0, 1, 0}, new float[] {aim.x, aim.y, aim.z, aim.w}, new float[] {1, 1, 1})
                .settings(CgClipSettings.NONE.withAdditive(CgAdditive.meshSpace(CgAdditive.Base.REF_POSE, null, 0))).build();
        CgAnimSequence turned = CgAnimSequence.builder("turned", 30, 1)
                .track("root", new float[] {0, 0, 0}, new float[] {root.x, root.y, root.z, root.w}, new float[] {1, 1, 1}).build();
        CgBlendSpace space = CgBlendSpace.oneD("test:aim", "pitch", 0, 1).sample(up, 0).build();
        CgAnimGraphInstance g = run(CgAimOffsetNode.of(CgSequencePlayerNode.of(turned), space, RUN, RUN));
        frame(g, 0f, DT);
        CgAnimPose out = new CgAnimPose(ARM);
        g.evaluate(out);
        // Mesh rotation aim·root, back to local: root⁻¹·aim·root
        Quaternionf expected = new Quaternionf(root).invert().mul(aim).mul(root), got = out.rotation(1, new Quaternionf());
        assertEquals(1f, Math.abs(expected.dot(got)), 1e-5f);
    }

    @Test
    public void aBlendSpaceRoundTripsThroughItsFile() {
        CgBlendSpace built = CgBlendSpace.twoD("test:strafe", "dir", -180, 180, "speed", 0, 6)
                .sample(RUN01, "c:idle", 0, 0, 1).sample(RUN02, "c:fwd", 0, 6, 1.2f).sample(RUN03, "c:left", -90, 6, 1)
                .sample(RUN01, "c:right", 90, 6, 1).wrap(0).smoothing(1, 0.25f, CgBlendFilter.Type.CUBIC).weightSpeed(3).build();
        Map<String, Object> tree = CgBlendSpaceCodec.toTree(built);
        CgBlendSpace back = new CgBlendSpaceDefinition("test:strafe", tree)
                .bind(id -> id.equals("c:fwd") ? RUN02 : id.equals("c:left") ? RUN03 : RUN01);
        assertEquals(tree, CgBlendSpaceCodec.toTree(back));
        int[] i = new int[12];
        float[] w = new float[12], r = new float[12];
        int n = back.lookup(-45, 6, i, w, r);
        // Between left (-90) and forward (0) on the top edge: half each
        assertEquals(2, n);
        assertEquals(0.5f, w[0], EPS);
        assertEquals(0.5f, w[1], EPS);
    }

    @Test
    public void aSampleOutsideItsAxesFailsAtLoad() {
        assertThrows(CgAssetException.class, () -> new CgBlendSpaceDefinition("test:bad", Map.of(
                "axes", List.of(Map.of("name", "run", "min", 0, "max", 2)),
                "samples", List.of(Map.of("clip", "c:a", "at", List.of(3.0))))).bind(id -> RUN01));
    }

    @Test
    public void aWarmFrameAllocatesNothing() {
        CgAnimSequence walk = clip("walk", 1.0f, new CgSyncMarker("foot_l", 0.25f), new CgSyncMarker("foot_r", 0.75f));
        CgAnimSequence jog = clip("jog", 0.8f, new CgSyncMarker("foot_l", 0.1f), new CgSyncMarker("foot_r", 0.6f));
        CgAnimGraphInstance g = run(CgTwoWayBlendNode.of(
                CgBlendSpacePlayerNode.of(standMoveLp(walk, jog, RUN03), RUN).sync(CgSyncGroup.of("feet")),
                CgSequencePlayerNode.of(walk).loop().sync(CgSyncGroup.of("feet")), ALPHA));
        CgAnimPose out = new CgAnimPose(ARM);
        g.set(ALPHA, 0.4f);
        for (int f = 0; f < 20000; f++) warm(g, out, f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) warm(g, out, f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    private static void warm(CgAnimGraphInstance g, CgAnimPose out, int f) {
        frame(g, 1f + (float) Math.sin(f * 0.01f), DT);
        g.evaluate(out);
    }
}
