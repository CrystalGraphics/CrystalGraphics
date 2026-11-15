package com.crystalgraphics.sequence.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.sun.management.ThreadMXBean;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.graph.CgSlotNode;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.animation.montage.CgMontageInstance;
import com.crystalgraphics.animation.montage.CgMontages;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipSettings;
import com.crystalgraphics.api.animation.CgRootMotion;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * A sequence's animation sections on a character as one montage, against Unreal's {@code FAnimMontageInstance}: its
 * blend in and auto blend-out, an interrupt playing on alone, slot groups, a looping section, and root motion. Frames
 * are 1/16 s so every blend lands on whole frames.
 */
public class CgMontageTest {

    static final float DT = 1f / 16f;

    static final CgSkeleton ARM = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("elbow", "root", new Vector3f(0, 1, 0)).build();

    /** The elbow rising from 1 by 4 a second over a second; a root walking 4 a second along x, its motion extracted. */
    static CgSkeletalMesh mesh() {
        float[] t = new float[5 * 3], root = new float[5 * 3], r = new float[5 * 4];
        for (int k = 0; k < 5; k++) {
            t[k * 3 + 1] = 1f + k;
            root[k * 3] = k;
            r[k * 4 + 3] = 1f;
        }
        CgAnimSequence rise = CgAnimSequence.builder("rise", 4, 5).track("elbow", t, r, new float[] {1, 1, 1}).build();
        CgAnimSequence walk = CgAnimSequence.builder("walk", 4, 5).track("root", root, r, new float[] {1, 1, 1})
                .track("elbow", t, r, new float[] {1, 1, 1}).build()
                .with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int s = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, s);
        });
        CgSkeletalMesh.Builder b = CgSkeletalMesh.builder(tri, ARM).sequence(rise).sequence(walk);
        for (int i = 1; i <= 4; i++) b.sequence(held("c" + i, 1f + i));
        return b.build();
    }

    /** The elbow held at {@code y} for a second. */
    static CgAnimSequence held(String name, float y) {
        float[] t = new float[2 * 3], r = new float[2 * 4];
        for (int k = 0; k < 2; k++) {
            t[k * 3 + 1] = y;
            r[k * 4 + 3] = 1f;
        }
        return CgAnimSequence.builder(name, 1, 2).track("elbow", t, r, new float[] {1, 1, 1}).build();
    }

    /** A character whose clips are its mesh's, by the last part of their ids. */
    static final class Fighter implements CgSequenceSkeletal {
        final CgAnimInstance body;

        Fighter(CgAnimInstance body) {
            this.body = body;
        }

        @Override public CgAnimInstance instance() {
            return body;
        }

        @Override public CgAnimSequence clip(String id) {
            return body.mesh().sequence(id.substring(Math.max(id.lastIndexOf('/'), id.indexOf(':')) + 1));
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    static CgSequenceDefinition sequence(String id, String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    /** One second, one clip section on the caster, its own keys appended to the section. */
    static CgSequenceDefinition oneClip(String id, String clip, String keys) {
        return sequence(id, "_v: 1\ntick_resolution: 1000\ndisplay_rate: 16\nrange: [0, 1000]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [ { id: s1, range: [0, 1000], clip: test:"
                + clip + keys + " } ] }\n");
    }

    static float elbow(CgAnimInstance anim) {
        return anim.pose().translation(1, new Vector3f()).y;
    }

    private final CgSequencer sequencer = new CgWorld().add(new CgSequencer());
    private final CgAnimInstance body = new CgAnimInstance(mesh());
    private final Fighter fighter = new Fighter(body);
    private final List<String> heard = new ArrayList<>();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    /** A game frame: the sequencer, then the character's pose. */
    private void frame() {
        sequencer.evaluate(DT);
        body.tick(DT);
    }

    private void listen() {
        body.montages().listen(new CgMontages.Listener() {
            @Override public void blendedIn(CgMontageInstance m) {
                heard.add("in");
            }

            @Override public void blendingOut(CgMontageInstance m, boolean interrupted) {
                heard.add(interrupted ? "out!" : "out");
            }

            @Override public void ended(CgMontageInstance m, boolean interrupted) {
                heard.add(interrupted ? "ended!" : "ended");
            }
        });
    }

    @Test
    public void itBlendsInOverItsFirstEaseAndOutByItselfBeforeItsEnd() {
        listen();
        sequencer.play(oneClip("test:rise", "rise", ", ease: { in: 250, out: 250 }"), Map.of("caster", fighter));
        frame();
        CgMontageInstance m = body.montages().active().get(0);
        assertEquals("a quarter of its blend in", 0.25f, m.weight(), 1e-6f);
        frame();
        // Linear: its ease names no function. Half the clip (1.5 at 0.125 s) over the rest pose (1)
        assertEquals(0.5f * 1.5f + 0.5f * 1f, elbow(body), 1e-5f);
        for (int k = 3; k <= 11; k++) frame();
        assertEquals(1f, m.weight(), 0f);
        assertFalse(m.stopped());
        frame();                                                        // 0.75 s: its blend out's time from the end
        assertTrue("blending out by itself", m.stopped());
        assertFalse(m.interrupted());
        assertEquals("the weight starts down next frame, as Unreal's", 1f, m.weight(), 0f);
        frame();
        assertEquals(0.75f, m.weight(), 1e-6f);
        assertEquals(0.75f * (1 + 4 * 13 / 16f) + 0.25f, elbow(body), 1e-4f);
        for (int k = 14; k <= 16; k++) frame();
        assertTrue("blended out at its end, and left", m.terminated());
        assertTrue(body.montages().active().isEmpty());
        assertEquals(1f, elbow(body), 1e-6f);
        assertEquals(List.of("in", "out", "ended"), heard);
    }

    @Test
    public void aStopInterruptsItAndItsClipPlaysOnAloneWhileItBlendsOut() {
        listen();
        CgSequencePlayer p = sequencer.play(oneClip("test:rise", "rise", ", ease: { out: 250 }"), Map.of("caster", fighter));
        for (int k = 1; k <= 6; k++) frame();
        CgMontageInstance m = body.montages().active().get(0);
        assertEquals("no ease in: at once", 1f, m.weight(), 0f);
        p.stop();
        frame();                                                        // its play leaves; the montage stays
        assertTrue(m.interrupted());
        assertEquals("a stop drops it the same frame", 0.75f, m.weight(), 1e-6f);
        assertEquals("its clip on at 7/16 s", 0.75f * (1 + 4 * 7 / 16f) + 0.25f, elbow(body), 1e-4f);
        frame();
        assertEquals(0.5f * (1 + 4 * 8 / 16f) + 0.5f, elbow(body), 1e-4f);
        frame();
        frame();
        assertTrue(m.terminated());
        assertEquals("a blend in of 0 is blended in at its first update, as Unreal's", List.of("in", "out!", "ended!"), heard);
    }

    @Test
    public void aMontageOfTheSameGroupInterruptsTheOtherAndItsPlayAnotherGroupDoesNot() {
        CgSequencePlayer a = sequencer.play(oneClip("test:a", "c1", ""), Map.of("caster", fighter));
        frame();
        CgSequencePlayer upper = sequencer.play(oneClip("test:u", "c3", ", slot_group: UpperBody"), Map.of("caster", fighter));
        frame();
        assertFalse("another group leaves it be", a.stopped());
        CgSequencePlayer b = sequencer.play(oneClip("test:b", "c2", ", ease: { in: 250 }"), Map.of("caster", fighter));
        frame();
        assertTrue("its play stopped by the newcomer", a.stopped());
        assertFalse(upper.stopped());
        List<CgMontageInstance> on = body.montages().active();
        assertEquals(3, on.size());
        assertEquals("out over the newcomer's blend in", 0.75f, on.get(0).weight(), 1e-6f);
        assertEquals(0.25f, on.get(2).weight(), 1e-6f);
        assertSame(b, on.get(2).source());
        for (int k = 0; k < 3; k++) frame();
        assertEquals(2, body.montages().active().size());
    }

    @Test
    public void aLoopingSectionHoldsItsWeightUntilRelinkedThenItBlendsOutAtItsEnd() {
        CgSequenceDefinition d = sequence("test:loop", "_v: 1\ntick_resolution: 1000\ndisplay_rate: 16\nrange: [0, 1000]\n"
                + "composite_sections:\n"
                + "  - { id: q1, name: start, range: [0, 250], next: q2 }\n"
                + "  - { id: q2, name: loop, range: [250, 500], next: q2 }\n"
                + "  - { id: q3, name: end, range: [500, 1000] }\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [\n"
                + "      { id: s1, range: [0, 250], clip: test:c1 },\n"
                + "      { id: s2, range: [250, 500], clip: test:c2 },\n"
                + "      { id: s3, range: [500, 1000], clip: test:c3, ease: { out: 125 } } ] }\n");
        CgSequencePlayer p = sequencer.play(d, Map.of("caster", fighter));
        for (int k = 0; k < 40; k++) frame();                           // 2.5 s: ten laps of the loop
        assertEquals("loop", p.section());
        CgMontageInstance m = body.montages().active().get(0);
        assertEquals("a looping section has no end to blend out at", 1f, m.weight(), 0f);
        assertEquals(3f, elbow(body), 1e-6f);
        p.nextSection("loop", "end");
        for (int k = 0; k < 4; k++) frame();
        assertEquals("end", p.section());
        assertEquals(4f, elbow(body), 1e-6f);
        while (!m.stopped()) frame();
        assertEquals("out within its blend out of its end", 0.125, p.timeToEnd(), 1e-6);
        frame();
        frame();
        frame();
        assertTrue(m.terminated());
    }

    @Test
    public void blendingOutItLeadsItsSyncGroupAndTheGraphFollowsItsPlace() {
        // Two seconds of rising under the slot, in group feet: at its own pace it would reach half the montage's place
        float[] t = new float[9 * 3], r = new float[9 * 4];
        for (int k = 0; k < 9; k++) {
            t[k * 3 + 1] = 1f + k;
            r[k * 4 + 3] = 1f;
        }
        CgAnimSequence slow = CgAnimSequence.builder("slow", 4, 9).track("elbow", t, r, new float[] {1, 1, 1}).build();
        body.graph(CgAnimGraph.builder("test:feet", ARM)
                .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgSequencePlayerNode.of(slow).loop().sync(CgSyncGroup.of("feet"))))
                .build());
        sequencer.play(oneClip("test:rise", "rise", ", ease: { out: 250 }, sync_group: feet"), Map.of("caster", fighter));
        for (int k = 1; k <= 12; k++) frame();
        assertEquals("under a full slot its source is not relevant, so not ticked", -1f, slowTime(), 0f);
        frame();                                                        // blending out: the montage leads, scored above all
        assertEquals("at the montage's place through its length", 13 / 16f * 2f, slowTime(), 1e-5f);
        frame();
        assertEquals(14 / 16f * 2f, slowTime(), 1e-5f);
    }

    /** The graph's player's time after the last frame, -1 where it was not ticked. */
    private float slowTime() {
        CgAnimGraphInstance g = body.graph();
        for (int i = 0; i < g.records(); i++) {
            if (!(g.recordPlayer(i) instanceof CgMontageInstance)) return g.recordPlayer(i).accumulatedTime();
        }
        return -1f;
    }

    @Test
    public void aWarmMontageFrameAllocatesNothing() {
        CgSequenceDefinition d = sequence("test:loop", "_v: 1\ntick_resolution: 1000\ndisplay_rate: 16\nrange: [0, 1000]\n"
                + "composite_sections:\n  - { id: q1, name: loop, range: [0, 1000], next: q1 }\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [ { id: s1, range: [0, 1000], clip: test:walk } ] }\n");
        sequencer.play(d, Map.of("caster", fighter));
        Matrix4f step = new Matrix4f();
        for (int f = 0; f < 20000; f++) {
            frame();
            body.montages().consumeRootMotion(1f, step);
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) {
            frame();
            body.montages().consumeRootMotion(1f, step);
        }
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    @Test
    public void rootMotionMovesTheCharacterAndHoldsItsRoot() {
        CgSequencePlayer p = sequencer.play(oneClip("test:walk", "walk", ""), Map.of("caster", fighter));
        Matrix4f step = new Matrix4f();
        Vector3f moved = new Vector3f();
        for (int k = 1; k <= 4; k++) {
            frame();
            assertTrue(body.montages().consumeRootMotion(1f, step));
            assertEquals("a frame's walk: 4 a second", 4f * DT, step.getTranslation(moved).x, 1e-5f);
            assertEquals("the root held at its rest", 0f, body.pose().translation(0, moved).x, 1e-6f);
            assertFalse("consumed", body.montages().consumeRootMotion(1f, step));
        }
        // A jump moves nothing
        p.jumpTo(0.75);
        frame();
        assertFalse(body.montages().consumeRootMotion(1f, step));
        frame();
        assertTrue(body.montages().consumeRootMotion(0.5f, step));
        assertEquals("half of it", 2f * DT, step.getTranslation(moved).x, 1e-5f);
        assertTrue("the rest kept", body.montages().consumeRootMotion(1f, step));
        assertEquals(2f * DT, step.getTranslation(moved).x, 1e-5f);
    }
}
