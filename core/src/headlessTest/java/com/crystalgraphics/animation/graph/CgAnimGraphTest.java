package com.crystalgraphics.animation.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.joml.Vector3f;
import com.sun.management.ThreadMXBean;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAdditive;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipSettings;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * The graph's runtime on a dedicated server's classpath: variables handed to the worker, Unreal's blends worked by hand
 * from their sources, a cached pose updated once however often it is read, and a warm frame allocating nothing.
 */
public class CgAnimGraphTest {

    private static final float EPS = 1e-5f;
    private static final CgAnimVariable<Float> ALPHA = CgAnimVariable.floating("alpha", 0.25f);
    private static final CgAnimVariable<Integer> CHOICE = CgAnimVariable.integer("choice");

    private static final CgSkeleton ARM = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("elbow", "root", new Vector3f(0, 1, 0)).build();
    private static final CgSkeletalMesh MESH = mesh();

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

    /** The elbow rising from 1 by 1 a key, 4 keys a second over a second. */
    private static CgAnimSequence rise() {
        float[] t = new float[5 * 3];
        float[] r = new float[5 * 4];
        for (int k = 0; k < 5; k++) {
            t[k * 3 + 1] = 1f + k;
            r[k * 4 + 3] = 1f;
        }
        return CgAnimSequence.builder("rise", 4, 5).track("elbow", t, r, new float[] {1, 1, 1}).build();
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static float elbow(CgAnimInstance anim) {
        return anim.pose().translation(1, new Vector3f()).y;
    }

    private static CgAnimInstance run(CgAnimGraph graph) {
        return new CgAnimInstance(MESH).graph(graph);
    }

    @Test
    public void theWorkerReadsTheVariablesHandedOverAtPreUpdate() {
        CgAnimInstance anim = run(CgAnimGraph.builder("test:two", ARM).variables(ALPHA)
                .output(CgTwoWayBlendNode.of(CgSequencePlayerNode.of(at("a", 1)), CgSequencePlayerNode.of(at("b", 5)), ALPHA))
                .build());
        assertEquals("its default, a quarter of b", 2f, elbow(anim.tick(0f)), EPS);
        anim.set(ALPHA, 1f).evaluate();
        assertEquals("set after the hand-over: the worker still reads the old copy", 2f, elbow(anim), EPS);
        anim.tick(0f);
        assertEquals(5f, elbow(anim), EPS);
        assertThrows(IllegalArgumentException.class, () -> anim.set(CHOICE, 1));
    }

    @Test
    public void aBlendListEasesLinearlyByItsBlendTimeAndItsFirstChoiceIsInstant() {
        CgAnimInstance anim = run(CgAnimGraph.builder("test:list", ARM).variables(CHOICE)
                .output(CgBlendListNode.byInt(CHOICE, CgSequencePlayerNode.of(at("a", 1)), CgSequencePlayerNode.of(at("b", 5)))
                        .blendTimes(0.2f, 0.2f))
                .build());
        assertEquals("the first choice, at once", 1f, elbow(anim.tick(0.1f)), EPS);
        anim.set(CHOICE, 1).tick(0.1f);
        assertEquals("half way through 0.2 s", 3f, elbow(anim), EPS);
        anim.tick(0.05f);
        assertEquals("three quarters", 4f, elbow(anim), EPS);
        // Back to a from b's three quarters: a quarter of the way to go, so 0.2 * 0.75 s for the rest
        anim.set(CHOICE, 0).tick(0.075f);
        assertEquals("half of 0.15 s back: a at 0.625", 0.625f * 1 + 0.375f * 5, elbow(anim), EPS);
    }

    @Test
    public void aCachedPoseUpdatesOnceHoweverOftenItIsRead() {
        CgAnimInstance anim = run(CgAnimGraph.builder("test:cached", ARM)
                .save("rise", CgSequencePlayerNode.of(rise()).loop())
                .output(CgTwoWayBlendNode.of(CgCachedPoseNode.use("rise"), CgCachedPoseNode.use("rise"), 0.5f))
                .build());
        anim.tick(0.25f);
        assertEquals("read twice, ticked once: 0.25 s in", 2f, elbow(anim), EPS);
        anim.tick(0.25f);
        assertEquals(3f, elbow(anim), EPS);
    }

    @Test
    public void anAdditiveAddsItsDifferenceFromTheRefPoseByItsAlpha() {
        CgAnimSequence lift = at("lift", 3).with(CgClipSettings.NONE.withAdditive(CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0)));
        CgAnimInstance anim = run(CgAnimGraph.builder("test:additive", ARM)
                .output(CgApplyAdditiveNode.of(CgSequencePlayerNode.of(at("base", 5)), CgSequencePlayerNode.of(lift), 0.5f))
                .build());
        assertEquals("5, plus half of lift's 2 over the rest pose's 1", 6f, elbow(anim.tick(0f)), EPS);
    }

    @Test
    public void aGraphThatCannotRunFailsWhenItBuilds() {
        CgAnimNode a = CgSequencePlayerNode.of(at("a", 1));
        assertThrows("an undeclared variable", IllegalArgumentException.class,
                () -> CgAnimGraph.builder("test:x", ARM).output(CgTwoWayBlendNode.of(a, a, ALPHA)).build());
        assertThrows("a use of a pose never saved", IllegalArgumentException.class,
                () -> CgAnimGraph.builder("test:x", ARM).output(CgCachedPoseNode.use("nope")).build());
        assertThrows("a slot twice", IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", ARM)
                .output(CgTwoWayBlendNode.of(CgSlotNode.of("S", a), CgSlotNode.of("S", CgRefPoseNode.create()), 0.5f)).build());
        assertThrows("a mask naming a bone the skeleton lacks", IllegalArgumentException.class, () -> run(
                CgAnimGraph.builder("test:x", ARM).output(CgLayeredBlendNode.of(a).layer(a, CgBlendMask.branch("tail", 0))).build()));
    }

    @Test
    public void aWarmFrameAllocatesNothing() {
        CgAnimSequence rise = rise();
        CgAnimInstance anim = run(CgAnimGraph.builder("test:warm", ARM).variables(ALPHA, CHOICE)
                .save("body", CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT,
                        CgBlendListNode.byInt(CHOICE, CgSequencePlayerNode.of(rise).loop(), CgSequencePlayerNode.of(at("b", 5)))
                                .blendTimes(0.3f, 0.3f)))
                .output(CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
                        .layer(CgTwoWayBlendNode.of(CgCachedPoseNode.use("body"), CgSequencePlayerNode.of(at("c", 2)), ALPHA),
                                CgBlendMask.branch("elbow", 1)))
                .build());
        CgAnimSlot slot = anim.slot(CgAnimGraph.DEFAULT_SLOT);
        for (int f = 0; f < 20000; f++) frame(anim, slot, rise, f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) frame(anim, slot, rise, f);
        // Under a byte a frame: an allocation every frame is 16 bytes at least; a deoptimisation's once is not the graph's
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    /** A graph with every kind A2 runs: a cached body under a slot, a blend list, a layer, an additive. */
    private static CgAnimGraph everyKind(CgAnimSequence rise, CgAnimSequence b, CgAnimSequence c, CgAnimSequence lift) {
        return CgAnimGraph.builder("test:every", ARM).variables(ALPHA, CHOICE, CgAnimVariable.bool("grounded", true))
                .save("body", CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT,
                        CgBlendListNode.byInt(CHOICE, CgSequencePlayerNode.of(rise).loop().playRate(1.5f), CgSequencePlayerNode.of(b))
                                .blendTimes(0.3f, 0.2f).resetOnActivate()))
                .output(CgApplyAdditiveNode.of(
                        CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
                                .layer(CgSlotNode.of("Upper", CgTwoWayBlendNode.of(CgCachedPoseNode.use("body"),
                                        CgSequencePlayerNode.of(c).startPosition(0.25f), ALPHA)),
                                        CgBlendMask.branch("elbow", 1).andBone("root", 0.5f), 0.75f)
                                .curvesFromBase(),
                        CgBlendListNode.byBool(CgAnimVariable.bool("grounded"), CgSequencePlayerNode.of(lift), CgRefPoseNode.create()),
                        0.5f))
                .build();
    }

    private static CgAssetData asset(String yaml) {
        return CgAssetData.of("test:every", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null);
    }

    @Test
    public void aGraphBuiltInJavaWritesReadsAndPlaysTheSame() {
        CgAnimSequence rise = rise(), b = at("b", 5), c = at("c", 2);
        CgAnimSequence lift = at("lift", 3).with(CgClipSettings.NONE.withAdditive(CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0)));
        Map<String, CgAnimSequence> clips = Map.of("rise", rise, "b", b, "c", c, "lift", lift);
        CgAnimGraph java = everyKind(rise, b, c, lift);
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(java), CgBodyKinds.ANIM_GRAPH);
        CgAnimGraph data = CgAnimGraphCodec.compile(asset(yaml), null).graph(ARM, clips::get);
        assertEquals("written again, the same file", yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(data), CgBodyKinds.ANIM_GRAPH));

        CgAnimInstance a = run(java), d = run(data);
        for (int f = 0; f < 120; f++) {
            a.set(CHOICE, (f / 30) % 2).set(ALPHA, (f % 10) / 10f).tick(1f / 60);
            d.set(CHOICE, (f / 30) % 2).set(ALPHA, (f % 10) / 10f).tick(1f / 60);
            for (int bone = 0; bone < 2; bone++) assertEquals("frame " + f, a.pose().component(bone), d.pose().component(bone));
        }
    }

    @Test
    public void theCodecsExampleLoads() {
        CgAnimGraph g = CgAnimGraphCodec.compile(asset("""
                _v: 1
                variables:
                  run: { type: float }
                output: body
                nodes:
                  body: { kind: slot, source: locomotion }
                  locomotion: { kind: two_way_blend, a: idle, b: jog, alpha: run }
                  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
                  jog: { kind: sequence_player, clip: mymod:hero/jog, loop: true }
                """), null).graph(ARM, id -> at(id, id.endsWith("jog") ? 5 : 1));
        CgAnimInstance anim = run(g).set(CgAnimVariable.floating("run"), 0.5f);      // a handle by name and type
        assertEquals(3f, elbow(anim.tick(0f)), EPS);
    }

    /** The graphs guide's hero, and its villain overriding one node by name through the format's inheritance. */
    @Test
    public void theGuidesHeroAndAChildOverridingANodeByName() {
        CgAssetData hero = CgAssetData.of("mymod:hero", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                variables:
                  speed: { type: float }
                  grounded: { type: bool, default: true }
                output: body
                nodes:
                  body: { kind: slot, source: locomotion }
                  locomotion: { kind: blend_list_by_bool, selector: grounded, when_true: ground, when_false: fall, blend_times: [0.2, 0.1] }
                  ground: { kind: two_way_blend, a: idle, b: run, alpha: speed }
                  idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
                  run: { kind: sequence_player, clip: mymod:hero/run, loop: true }
                  fall: { kind: sequence_player, clip: mymod:hero/fall, loop: true }
                """), null);
        CgAssetData villain = CgAssetData.of("mymod:villain", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                parent: mymod:hero
                nodes:
                  run: { clip: mymod:villain/run }
                """), hero);
        Map<String, CgAnimSequence> clips = Map.of("mymod:hero/idle", at("idle", 1), "mymod:hero/run", at("run", 5),
                "mymod:hero/fall", at("fall", 0), "mymod:villain/run", at("villain_run", 3));
        CgAnimVariable<Float> speed = CgAnimVariable.floating("speed");
        CgAnimInstance h = run(CgAnimGraphCodec.compile(hero, null).graph(ARM, clips::get)).set(speed, 1f);
        CgAnimInstance v = run(CgAnimGraphCodec.compile(villain, null).graph(ARM, clips::get)).set(speed, 1f);
        assertEquals("the hero runs", 5f, elbow(h.tick(0f)), EPS);
        assertEquals("the villain runs his own run, the rest inherited", 3f, elbow(v.tick(0f)), EPS);
        v.set(CgAnimVariable.bool("grounded"), false).tick(0.1f);
        assertEquals("and falls into the hero's fall over 0.1 s", 0f, elbow(v), EPS);
    }

    @Test
    public void aFileThatCannotRunFailsAtLoadByName() {
        String head = """
                _v: 1
                variables:
                  grounded: { type: bool }
                output: out
                nodes:
                """;
        String[][] cases = {
                {"""
                  out: { kind: spline }
                """, "nodes.out.kind: spline is no anim graph node kind"},
                {"""
                  out: { kind: two_way_blend, a: p, b: p, alpha: 0.5 }
                  p: { kind: ref_pose }
                """, "nodes.p: two nodes read it"},
                {"""
                  out: { kind: two_way_blend, a: p, b: q, alpha: grounded }
                  p: { kind: ref_pose }
                  q: { kind: ref_pose }
                """, "nodes.out.alpha: grounded is a bool, not a float"},
                {"""
                  out: { kind: slot, source: gone }
                """, "nodes.gone: a node reads it; nodes lacks it"},
                {"""
                  out: { kind: ref_pose }
                  stray: { kind: ref_pose }
                """, "nodes.stray: no node reaches it from out"},
                {"""
                  out: { kind: use_cached_pose, save: out }
                """, "nodes.out.save: out is no save_cached_pose"},
        };
        for (String[] c : cases) {
            CgAssetException e = assertThrows(c[1], CgAssetException.class, () -> CgAnimGraphCodec.compile(asset(head + c[0]), null));
            assertTrue(e.getMessage() + " names " + c[1], e.getMessage().contains(c[1]));
        }
        CgAnimGraphDefinition clip = CgAnimGraphCodec.compile(asset(head + """
                  out: { kind: sequence_player, clip: mymod:gone }
                """), null);
        CgAssetException e = assertThrows(CgAssetException.class, () -> clip.graph(ARM, id -> null));
        assertTrue(e.getMessage(), e.getMessage().contains("nodes.out.clip: mymod:gone names no clip"));
    }

    private static void frame(CgAnimInstance anim, CgAnimSlot slot, CgAnimSequence clip, int f) {
        anim.set(CHOICE, (f / 20) % 2).set(ALPHA, (f % 10) / 10f);
        if (f % 3 == 0) slot.play(clip, (f % 60) / 60f, 0.5f);
        anim.tick(1f / 60);
    }
}
