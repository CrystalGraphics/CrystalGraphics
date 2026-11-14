package com.crystalgraphics.animation.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.blend.CgAlphaBlend;
import com.crystalgraphics.animation.blend.CgInertializationNode;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.graph.CgSlotNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.easing.CgEasings;
import com.sun.management.ThreadMXBean;

/**
 * Unreal's state machine and inertialization on a dedicated server's classpath, every number worked by hand from
 * AnimNode_StateMachine.cpp, AlphaBlend.cpp and AnimNode_Inertialization.cpp.
 */
public class CgAnimStateMachineTest {

    private static final float EPS = 1e-5f;
    private static final CgAnimVariable<Boolean> GO = CgAnimVariable.bool("go");
    private static final CgAnimVariable<Boolean> OPEN = CgAnimVariable.bool("open");

    private static final CgSkeleton ARM = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("elbow", "root", new Vector3f(0, 1, 0)).build();
    private static final CgSkeletalMesh MESH = mesh();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        CgAnimGraphCodec.registerKind("state_machine", CgAnimStateMachineNode.KIND);
        CgAnimGraphCodec.registerKind("inertialization", CgInertializationNode.KIND);
    }

    private static CgSkeletalMesh mesh() {
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, r);
        });
        return CgSkeletalMesh.builder(tri, ARM).build();
    }

    /** The elbow held at height {@code y}. */
    private static CgAnimSequence at(String name, float y) {
        return CgAnimSequence.builder(name, 30, 1).track("elbow", new float[] {0, y, 0}, new float[] {0, 0, 0, 1},
                new float[] {1, 1, 1}).build();
    }

    /** The elbow rising from 1 to 5 over a second, 8 keys a second. */
    private static CgAnimSequence rise() {
        float[] t = new float[9 * 3], r = new float[9 * 4];
        for (int k = 0; k < 9; k++) {
            t[k * 3 + 1] = 1f + k * 0.5f;
            r[k * 4 + 3] = 1f;
        }
        return CgAnimSequence.builder("rise", 8, 9).track("elbow", t, r, new float[] {1, 1, 1}).build();
    }

    private static CgAnimNode hold(float y) {
        return CgSequencePlayerNode.of(at("y" + y, y)).loop();
    }

    private static float elbow(CgAnimInstance anim) {
        return anim.pose().translation(1, new Vector3f()).y;
    }

    private static float smooth(float t) {
        return (float) CgEasings.SMOOTH_STEP.ease(t);
    }

    private static CgAnimInstance run(CgAnimNode output, CgAnimVariable<?>... variables) {
        return new CgAnimInstance(MESH).graph(CgAnimGraph.builder("test:machine", ARM).variables(variables).output(output).build());
    }

    private static CgAnimStateMachine.Builder ab() {
        return CgAnimStateMachine.builder("ab").state(CgAnimState.of("a", hold(1))).state(CgAnimState.of("b", hold(5)));
    }

    @Test
    public void theFirstUpdateEntersAtOnce() {
        CgAnimInstance anim = run(CgAnimStateMachineNode.of(ab().transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO))).build()), GO);
        anim.set(GO, true).tick(0.05f);
        assertEquals("bSkipFirstUpdateTransition: b at once", 5f, elbow(anim), EPS);
    }

    @Test
    public void aCrossfadeFollowsItsCurveAndEndsWithItsBlend() {
        CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab().transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO))
                .crossfade(0.2f)).build());
        CgAnimInstance anim = run(node, GO);
        assertEquals(1f, elbow(anim.tick(0.05f)), EPS);
        anim.set(GO, true).tick(0.05f);                    // taken and updated this frame: 0.05 of 0.2
        assertEquals(1 + 4 * smooth(0.25f), elbow(anim), EPS);
        anim.tick(0.1f);
        assertEquals(1 + 4 * smooth(0.75f), elbow(anim), EPS);
        assertEquals(1, node.in(anim.graph()).activeTransitions());
        anim.tick(0.05f);                                  // FAlphaBlend's time runs out: finished and removed
        assertEquals(5f, elbow(anim), EPS);
        assertEquals(0, node.in(anim.graph()).activeTransitions());
        assertEquals("b", node.in(anim.graph()).currentState());
    }

    @Test
    public void aTransitionBackMidCrossfadeStacksAndIsShortenedByTheInverseAlpha() {
        CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab()
                .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)).crossfade(0.2f))
                .transition(CgAnimTransition.of("b", "a", CgAnimRule.not(CgAnimRule.isTrue(GO))).crossfade(0.2f)).build());
        CgAnimInstance anim = run(node, GO);
        anim.tick(0.05f);
        anim.set(GO, true).tick(0.05f);
        float a0 = smooth(0.25f);
        // a still holds 1 − a0: the way back is 0.2 · inverse(a0), about 0.043 s, so a short frame to see the stack
        anim.set(GO, false).tick(0.02f);
        float first = smooth(0.07f / 0.2f);
        float existingA = 1f - a0;
        float hermite = 1f - existingA;
        float crossfade = 0.2f * (hermite * hermite * hermite * (4f / 3f) - 2f * hermite * hermite + (5f / 3f) * hermite);
        float second = smooth(0.02f / crossfade);
        float expected = (1 + 4 * first) * (1 - second) + 1 * second;
        assertEquals(2, node.in(anim.graph()).activeTransitions());
        assertEquals(expected, elbow(anim), 1e-4f);
        assertEquals("a", node.in(anim.graph()).currentState());
    }

    @Test
    public void theAutomaticRuleFiresAtTheCrossfadeLeftAndShortensByTheOvershoot() {
        // A 1 s clip played once, 0.125 s frames: 1 − 0.125k left after k frames
        for (int mode = 0; mode < 3; mode++) {
            CgAnimTransition t = CgAnimTransition.automatic("a", "b").crossfade(0.25f);
            if (mode == 1) t = t.triggerTime(0.3f);                 // trips at 0.25 left: 0.05 over
            if (mode == 2) t = t.overshootCorrection(false);        // Unreal 4.21's: no shortening
            CgAnimStateMachineNode node = CgAnimStateMachineNode.of(CgAnimStateMachine.builder("auto")
                    .state(CgAnimState.of("a", CgSequencePlayerNode.of(rise()))).state(CgAnimState.of("b", hold(0)))
                    .transition(t).build());
            CgAnimInstance anim = run(node);
            int frames = 0;
            while (node.in(anim.graph()).currentState().equals("a")) {
                anim.tick(0.125f);
                frames++;
            }
            assertEquals("mode " + mode + ": taken with 0.25 s left, on the 7th frame", 7, frames);
            float crossfade = mode == 1 ? 0.2f : 0.25f;
            // a went on playing through the crossfade: 0.875 s in, its elbow at 4.5; b is 0
            assertEquals("mode " + mode, 4.5f * (1 - smooth(0.125f / crossfade)), elbow(anim), 1e-4f);
        }
    }

    @Test
    public void aConduitGatesItsWayAndIsPassedInTheSameFrame() {
        CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab().state(CgAnimState.conduit("gate", CgAnimRule.isTrue(OPEN)))
                .transition(CgAnimTransition.of("a", "gate", CgAnimRule.isTrue(GO)))
                .transition(CgAnimTransition.of("gate", "b", CgAnimRule.always()).crossfade(0f)).build());
        CgAnimInstance anim = run(node, GO, OPEN);
        anim.tick(0.05f).set(GO, true).tick(0.05f);
        assertEquals("closed: stays", "a", node.in(anim.graph()).currentState());
        anim.set(OPEN, true).tick(0.05f);
        assertEquals("open: through the conduit to b, in one frame", "b", node.in(anim.graph()).currentState());
        assertEquals("the conduit's 0 s transition: at once", 5f, elbow(anim), EPS);
    }

    @Test
    public void aFrameTakesAtMostItsMaxTransitions() {
        for (int max : new int[] {1, 3}) {
            CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab().state(CgAnimState.of("c", hold(7)))
                    .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)))
                    .transition(CgAnimTransition.of("b", "c", CgAnimRule.isTrue(GO)))
                    .maxTransitionsPerFrame(max).build());
            CgAnimInstance anim = run(node, GO);
            anim.tick(0.05f).set(GO, true).tick(0.05f);
            assertEquals("max " + max, max == 1 ? "b" : "c", node.in(anim.graph()).currentState());
        }
    }

    @Test
    public void anEventIsTakenOnceAndExpires() {
        CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab().state(CgAnimState.of("c", hold(7)))
                .transition(CgAnimTransition.of("a", "b", CgAnimRule.event("jump")).crossfade(0f))
                .transition(CgAnimTransition.of("b", "c", CgAnimRule.event("jump")).crossfade(0f)).build());
        CgAnimInstance anim = run(node);
        anim.tick(0.05f).requestTransitionEvent("jump", 1.0).tick(0.05f);
        assertEquals("one event, one transition: shared, consumed by the first", "b", node.in(anim.graph()).currentState());
        anim.tick(0.05f);
        assertEquals("b", node.in(anim.graph()).currentState());

        CgAnimInstance late = run(CgAnimStateMachineNode.of(ab().transition(CgAnimTransition.of("a", "b", CgAnimRule.all(
                CgAnimRule.event("jump"), CgAnimRule.isTrue(GO))).crossfade(0f)).build()), GO);
        late.tick(0.05f).requestTransitionEvent("jump", 0.1).tick(0.05f).tick(0.1f);
        late.set(GO, true).tick(0.05f);
        assertEquals("expired after 0.1 s of the instance's clock", 1f, elbow(late), EPS);
    }

    @Test
    public void aStateIsNotReenteredWithinItsMinimum() {
        CgAnimStateMachineNode node = CgAnimStateMachineNode.of(ab()
                .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)).crossfade(0f).minReentry(0.5f))
                .transition(CgAnimTransition.of("b", "a", CgAnimRule.not(CgAnimRule.isTrue(GO))).crossfade(0f)).build());
        CgAnimInstance anim = run(node, GO);
        anim.tick(0.1f).set(GO, true).tick(0.1f);
        assertEquals("b", node.in(anim.graph()).currentState());
        anim.set(GO, false).tick(0.1f).set(GO, true).tick(0.1f);
        assertEquals("b entered 0.2 s ago: not again yet", "a", node.in(anim.graph()).currentState());
        anim.tick(0.1f).tick(0.1f).tick(0.1f);
        assertEquals("0.5 s on: entered", "b", node.in(anim.graph()).currentState());
    }

    @Test
    public void aStateRestartsFromNoWeightOrAlways() {
        for (boolean always : new boolean[] {false, true}) {
            CgAnimState rising = CgAnimState.of("a", CgSequencePlayerNode.of(rise()));
            CgAnimStateMachineNode node = CgAnimStateMachineNode.of(CgAnimStateMachine.builder("r")
                    .state(always ? rising.alwaysResetOnEntry() : rising).state(CgAnimState.of("b", hold(0)))
                    .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)).crossfade(0.25f))
                    .transition(CgAnimTransition.of("b", "a", CgAnimRule.not(CgAnimRule.isTrue(GO))).crossfade(0.25f)).build());
            CgAnimInstance anim = run(node, GO);
            anim.tick(0.125f).tick(0.125f);                              // a at 0.25 s
            anim.set(GO, true).tick(0.125f).set(GO, false).tick(0.125f);  // back to a while it still shows
            // a kept playing in the crossfade (0.5 s), unless reset on entry (0 then, + this frame's 0.125)
            float time = always ? 0.125f : 0.5f;
            assertEquals("always " + always, time, playerTime(anim, "rise"), EPS);
        }
    }

    /** The time of this frame's player of {@code clip}. */
    private static float playerTime(CgAnimInstance anim, String clip) {
        for (int i = 0; i < anim.graph().samples(); i++) {
            CgSequencePlayerNode.PlayerRuntime p = anim.graph().samplePlayer(i);
            if (p != null && p.clip() != null && p.clip().name().equals(clip)) return p.time();
        }
        return Float.NaN;
    }

    @Test
    public void inertializationDecaysTheOffsetAlongUnrealsQuintic() {
        CgAnimStateMachineNode machine = CgAnimStateMachineNode.of(ab()
                .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)).crossfade(0.25f).inertialize()).build());
        CgAnimInstance anim = run(CgInertializationNode.of(machine), GO);
        anim.tick(0.125f).tick(0.125f);
        anim.set(GO, true).tick(0.125f);
        // Offset 4 down, no speed; at half its 0.25 s, x0·(1 − 10u³ + 15u⁴ − 6u⁵) = 4 · 0.5
        assertEquals(5f - 2f, elbow(anim), 1e-4f);
        anim.tick(0.125f);
        assertEquals("done: b alone", 5f, elbow(anim), EPS);
    }

    @Test
    public void inertializingWithNoNodeAboveFailsWhenTheGraphRuns() {
        CgAnimStateMachineNode machine = CgAnimStateMachineNode.of(ab()
                .transition(CgAnimTransition.of("a", "b", CgAnimRule.isTrue(GO)).inertialize()).build());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(machine, GO));
        assertTrue(e.getMessage(), e.getMessage().contains("no inertialization node is above the machine"));
    }

    @Test
    public void aMachineWritesReadsAndPlaysTheSame() {
        CgAnimVariable<Float> speed = CgAnimVariable.floating("speed");
        CgAnimStateMachine m = CgAnimStateMachine.builder("loco")
                .state(CgAnimState.of("idle", hold(1)))
                .state(CgAnimState.of("run", CgSequencePlayerNode.of(rise()).loop()).alwaysResetOnEntry())
                .state(CgAnimState.conduit("branch", CgAnimRule.any(CgAnimRule.isTrue(GO), CgAnimRule.event("dash"))))
                .state(CgAnimState.of("stop", CgSequencePlayerNode.of(rise())))
                .transition(CgAnimTransition.of("idle", "branch", CgAnimRule.variable(speed, CgAnimRule.Op.GREATER, 0.5f)))
                .transition(CgAnimTransition.of("branch", "run", CgAnimRule.always()).crossfade(0.1f).blend(CgAlphaBlend.CUBIC_IN_OUT).priority(0))
                .transition(CgAnimTransition.of("run", "stop", CgAnimRule.all(CgAnimRule.not(CgAnimRule.isTrue(GO)),
                        CgAnimRule.stateTime(CgAnimRule.Op.GREATER_EQUAL, 0.25f))).inertialize().minReentry(0.5f))
                .transition(CgAnimTransition.automatic("stop", "idle").triggerTime(0.2f).overshootCorrection(false))
                .transition(CgAnimTransition.of("stop", "run", CgAnimRule.timeRemainingFraction(CgAnimRule.Op.LESS, 0.5f)))
                .maxTransitionsPerFrame(2).build();
        CgAnimGraph java = CgAnimGraph.builder("test:loco", ARM).variables(speed, GO)
                .output(CgInertializationNode.of(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(m)))).build();
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(java), CgBodyKinds.ANIM_GRAPH);
        Map<String, CgAnimSequence> clips = Map.of("y1.0", at("y1.0", 1), "rise", rise());
        CgAnimGraph data = CgAnimGraphCodec.compile(CgAssetData.of("test:loco", CgBodyKinds.ANIM_GRAPH,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), null).graph(ARM, clips::get);
        assertEquals("written again, the same file", yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(data), CgBodyKinds.ANIM_GRAPH));
        CgAnimInstance a = new CgAnimInstance(MESH).graph(java), d = new CgAnimInstance(MESH).graph(data);
        for (int f = 0; f < 240; f++) {
            float s = (f / 40) % 2 == 0 ? 1f : 0f;
            boolean go = (f / 25) % 2 == 0;
            a.set(speed, s).set(GO, go).tick(1f / 60);
            d.set(speed, s).set(GO, go).tick(1f / 60);
            assertEquals("frame " + f, elbow(a), elbow(d), 0f);
        }
    }

    /** The graphs guide's machine, as printed, with its variables: loads, runs, lands on its event, returns by itself. */
    @Test
    public void theGuidesMachineLoadsAndPlays() {
        String yaml = """
                _v: 1
                variables:
                  speed: { type: float }
                  grounded: { type: bool, default: true }
                output: out
                nodes:
                  out: { kind: inertialization, source: body }   # what the inertialized transition needs above its machine
                  body: { kind: slot, source: loco }
                  loco: { kind: state_machine, machine: locomotion }
                  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
                  run: { kind: sequence_player, clip: mymod:hero/run, loop: true }
                  land: { kind: sequence_player, clip: mymod:hero/land }
                machines:
                  locomotion:
                    states:
                      idle: { pose: idle }
                      run: { pose: run }
                      land: { pose: land, always_reset_on_entry: true }
                      moving: { entry_rule: { variable: grounded, op: '==', value: true } }   # a conduit
                    transitions:
                      - { from: idle, to: moving, rule: { variable: speed, op: '>', value: 0.1 } }
                      - { from: moving, to: run, rule: { all: [] }, crossfade: 0.25, blend: cubic_in_out }
                      - { from: run, to: idle, rule: { variable: speed, op: '<=', value: 0.1 }, crossfade: 0.3 }
                      - { from: land, to: idle, automatic: true, crossfade: 0.15 }
                      - { from: run, to: land, rule: { event: land }, logic: inertialization, priority: 0 }
                """;
        Map<String, CgAnimSequence> clips = Map.of("mymod:hero/idle", at("idle", 1), "mymod:hero/run", at("run", 5),
                "mymod:hero/land", rise());
        CgAnimGraph g = CgAnimGraphCodec.compile(CgAssetData.of("mymod:hero", CgBodyKinds.ANIM_GRAPH,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), null).graph(ARM, clips::get);
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(g);
        CgAnimStateMachineNode loco = null;
        for (CgAnimNode n = g.output(); loco == null; ) {
            CgAnimNode[] next = new CgAnimNode[1];
            n.forEachChild(c -> next[0] = c);
            if (next[0] instanceof CgAnimStateMachineNode m) loco = m;
            n = next[0];
        }
        CgAnimStateMachineNode.Machine m = loco.in(anim.graph());
        anim.tick(0.1f);
        assertEquals("idle", m.currentState());
        anim.set(CgAnimVariable.floating("speed"), 1f).tick(0.1f);
        assertEquals("through the conduit while grounded", "run", m.currentState());
        anim.requestTransitionEvent("land", 0.5).tick(0.1f);
        assertEquals("land", m.currentState());
        anim.set(CgAnimVariable.floating("speed"), 0f);   // still moving, idle would go on through the conduit to run
        for (int i = 0; i < 12; i++) anim.tick(0.1f);
        assertEquals("the landing clip ran out: back by itself", "idle", m.currentState());
    }

    @Test
    public void aWarmMachineAllocatesNothing() {
        CgAnimStateMachineNode machine = CgAnimStateMachineNode.of(ab().state(CgAnimState.conduit("gate", CgAnimRule.isTrue(OPEN)))
                .transition(CgAnimTransition.of("a", "gate", CgAnimRule.isTrue(GO)))
                .transition(CgAnimTransition.of("gate", "b", CgAnimRule.always()))
                .transition(CgAnimTransition.of("b", "a", CgAnimRule.not(CgAnimRule.isTrue(GO))).inertialize()).build());
        CgAnimInstance anim = run(CgInertializationNode.of(machine), GO, OPEN);
        for (int f = 0; f < 20000; f++) step(anim, f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) step(anim, f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    private static void step(CgAnimInstance anim, int f) {
        anim.set(GO, (f / 7) % 2 == 0).set(OPEN, (f / 11) % 3 != 0).tick(1f / 60);
    }
}
