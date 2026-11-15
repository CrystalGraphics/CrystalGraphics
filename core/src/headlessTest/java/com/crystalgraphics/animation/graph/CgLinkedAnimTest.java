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
import com.crystalgraphics.animation.blend.CgInertializationNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
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
 * Template graphs and linked layers, against Unreal's: one graph on two characters with their own clips, a child's
 * asset override, a layer played by its graph or a linked one, grouped layers sharing a run, a linked graph swapped by
 * tag, variables and montage slots reaching a linked graph, a swap's inertialized blend, the data form, 0 B a frame.
 */
public class CgLinkedAnimTest {

    private static final CgSkeleton ARM = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("elbow", "root", new Vector3f(0, 1, 0)).build();
    private static final CgSkeletalMesh MESH = mesh();
    private static final CgAnimVariable<Float> ALPHA = CgAnimVariable.floating("alpha");

    private static CgSkeletalMesh mesh() {
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, r);
        });
        return CgSkeletalMesh.builder(tri, ARM).build();
    }

    /** The elbow held at {@code y}. */
    private static CgAnimSequence at(String name, float y) {
        return CgAnimSequence.builder(name, 30, 1).track("elbow", new float[] {0, y, 0}, new float[] {0, 0, 0, 1},
                new float[] {1, 1, 1}).build();
    }

    private static CgSequencePlayerNode hold(float y) {
        return CgSequencePlayerNode.of(at("y" + y, y)).loop();
    }

    private static float elbow(CgAnimInstance anim) {
        return anim.pose().translation(1, new Vector3f()).y;
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    /** A hero whose "arms" layer passes its body (2) through, under an inertialization. */
    private static CgAnimGraph hero(float blend) {
        return CgAnimGraph.builder("test:hero", ARM).variables(ALPHA)
                .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body").blend(blend, blend))
                .output(CgInertializationNode.of(CgLinkedAnimLayerNode.of("arms").input("body", hold(2))))
                .build();
    }

    /** A graph whose "arms" mixes the body it is passed with a sword pose (7) by {@code alpha}. */
    private static CgAnimGraph sword(float blend) {
        return CgAnimGraph.builder("test:sword", ARM).variables(CgAnimVariable.floating("alpha", 1f))
                .layer(CgAnimGraph.Layer.of("arms", CgTwoWayBlendNode.of(CgLinkedInputPoseNode.of("body"), hold(7), ALPHA), "body")
                        .blend(blend, blend))
                .output(CgRefPoseNode.create())
                .build();
    }

    @Test
    public void aLayerPlaysItsGraphsOwnUntilAGraphIsLinkedThenThatOneWithTheBodyItIsPassed() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero(0f));
        assertEquals("its own layer: the body through", 2f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.set(ALPHA, 0.5f).linkLayers(sword(0f));
        assertEquals("the linked layer, its alpha the owner's, the body passed in", 0.5f * 2 + 0.5f * 7, elbow(anim.tick(0.1f)), 1e-5f);
        anim.set(ALPHA, 1f);
        assertEquals(7f, elbow(anim.tick(0.1f)), 1e-5f);
        assertEquals(1, anim.graph().linkedInstances());
        anim.unlinkLayers(sword(0f));
        assertEquals("unlinking another graph leaves it", 7f, elbow(anim.tick(0.1f)), 1e-5f);
    }

    @Test
    public void unlinkingReturnsTheGraphsOwnLayerAndTheLinkedRunLeaves() {
        CgAnimGraph sword = sword(0f);
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero(0f)).linkLayers(sword);
        anim.set(ALPHA, 1f);
        assertEquals(7f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.unlinkLayers(sword);
        assertEquals(2f, elbow(anim.tick(0.1f)), 1e-5f);
        assertEquals(0, anim.graph().linkedInstances());
    }

    @Test
    public void aSwapIsInertializedOverTheLayersBlendTimes() {
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero(0.2f)).set(ALPHA, 1f);
        for (int i = 0; i < 5; i++) anim.tick(0.05f);
        anim.linkLayers(sword(0.2f));
        float first = elbow(anim.tick(0.05f));
        assertTrue("the swap eases from the old pose: " + first, first > 2f && first < 7f);
        for (int i = 0; i < 6; i++) anim.tick(0.05f);
        assertEquals("settled past its blend", 7f, elbow(anim), 1e-4f);
    }

    @Test
    public void aBlendWithNoInertializationAboveFailsAtBuildAndAtLink() {
        CgAnimGraph bare = CgAnimGraph.builder("test:bare", ARM)
                .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body").blend(0.2f, 0.2f))
                .output(CgLinkedAnimLayerNode.of("arms").input("body", hold(2)))
                .build();
        assertThrows(IllegalArgumentException.class, () -> new CgAnimInstance(MESH).graph(bare));
        CgAnimGraph still = CgAnimGraph.builder("test:still", ARM)
                .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body"))
                .output(CgLinkedAnimLayerNode.of("arms").input("body", hold(2)))
                .build();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(still);
        assertThrows(IllegalArgumentException.class, () -> anim.linkLayers(sword(0.2f)));
    }

    @Test
    public void layersOfOneGroupShareARunAndUngroupedOnesEachHaveTheirOwn() {
        CgAnimGraph two = CgAnimGraph.builder("test:two", ARM)
                .layer(CgAnimGraph.Layer.of("a", CgLinkedInputPoseNode.of("in"), "in"))
                .layer(CgAnimGraph.Layer.of("b", CgLinkedInputPoseNode.of("in"), "in"))
                .output(CgTwoWayBlendNode.of(CgLinkedAnimLayerNode.of("a").input("in", hold(2)),
                        CgLinkedAnimLayerNode.of("b").input("in", hold(4)), 0.5f))
                .build();
        CgAnimGraph grouped = CgAnimGraph.builder("test:grouped", ARM)
                .layer(CgAnimGraph.Layer.of("a", hold(6)).group("g"))
                .layer(CgAnimGraph.Layer.of("b", hold(8)).group("g"))
                .output(CgRefPoseNode.create()).build();
        CgAnimGraph ungrouped = CgAnimGraph.builder("test:ungrouped", ARM)
                .layer(CgAnimGraph.Layer.of("a", hold(6)))
                .layer(CgAnimGraph.Layer.of("b", hold(8)))
                .output(CgRefPoseNode.create()).build();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(two);
        assertEquals(3f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.linkLayers(grouped);
        assertEquals(7f, elbow(anim.tick(0.1f)), 1e-5f);
        assertEquals("one group, one run", 1, anim.graph().linkedInstances());
        anim.unlinkLayers(grouped).linkLayers(ungrouped);
        assertEquals(7f, elbow(anim.tick(0.1f)), 1e-5f);
        assertEquals("a run each", 2, anim.graph().linkedInstances());
    }

    @Test
    public void aLinkedGraphRunsAsANodeIsSwappedByItsTagAndPlaysTheOwnersMontages() {
        CgAnimGraph face = CgAnimGraph.builder("test:face", ARM).inputs("body")
                .output(CgSlotNode.of("FacialSlot", CgTwoWayBlendNode.of(CgLinkedInputPoseNode.of("body"), hold(8), 0.5f)))
                .build();
        CgAnimGraph angry = CgAnimGraph.builder("test:angry", ARM).inputs("body").output(hold(9)).build();
        CgAnimGraph hero = CgAnimGraph.builder("test:linking", ARM)
                .output(CgLinkedAnimGraphNode.of(face).input("body", hold(2)).tag("face"))
                .build();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero);
        assertEquals("the face over the body it is passed", 5f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.slot("FacialSlot").play(at("blink", 3), 0f, 1f);
        assertEquals("the owner's slot, played in the linked graph", 3f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.linkGraph("face", angry);
        assertEquals(9f, elbow(anim.tick(0.1f)), 1e-5f);
        anim.linkGraph("face", null);
        assertEquals("none: the body through", 2f, elbow(anim.tick(0.1f)), 1e-5f);
        assertEquals(0, anim.graph().linkedInstances());
        assertThrows(IllegalArgumentException.class, () -> anim.linkGraph("hands", angry));
    }

    @Test
    public void undeclaredLayersAndInputsFailTheBuild() {
        assertThrows(IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", ARM)
                .output(CgLinkedAnimLayerNode.of("arms")).build());
        assertThrows(IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", ARM)
                .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body"))
                .output(CgLinkedAnimLayerNode.of("arms").input("legs", hold(1))).build());
        assertThrows(IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", ARM)
                .output(CgLinkedInputPoseNode.of("body")).build());
    }

    private static CgAnimGraphDefinition compile(String id, String yaml, Map<String, CgAnimGraphDefinition> deps) {
        return CgAnimGraphCodec.compile(CgAssetData.of(id, CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null),
                (kind, did) -> deps.get(did));
    }

    @Test
    public void theDataFormLinksLayersAndGraphsAndFailsByName() {
        CgAnimGraphDefinition face = compile("test:face",
                "_v: 1\ninputs: [body]\noutput: mix\nnodes:\n"
                        + "  in: { kind: linked_input_pose, name: body }\n"
                        + "  eight: { kind: sequence_player, clip: test:eight, loop: true }\n"
                        + "  mix: { kind: two_way_blend, a: in, b: eight, alpha: 0.5 }\n", Map.of());
        String hero = "_v: 1\noutput: out\nnodes:\n"
                + "  body: { kind: sequence_player, clip: test:two, loop: true }\n"
                + "  arms: { kind: linked_anim_layer, layer: arms, inputs: { body: body } }\n"
                + "  out: { kind: linked_anim_graph, graph: test:face, tag: face, inputs: { body: arms } }\n"
                + "  through: { kind: linked_input_pose, name: body }\n"
                + "layers:\n  arms: { inputs: [body], output: through }\n";
        CgAnimGraphDefinition d = compile("test:hero", hero, Map.of("test:face", face));
        Map<String, CgAnimSequence> clips = Map.of("test:two", at("two", 2), "test:eight", at("eight", 8));
        CgAnimGraph graph = d.graph(ARM, clips::get);
        assertEquals(5f, elbow(new CgAnimInstance(MESH).graph(graph).tick(0.1f)), 1e-5f);
        Map<String, Object> back = CgAnimGraphCodec.toTree(graph);
        assertEquals("written back, it reads the same", 5f, elbow(new CgAnimInstance(MESH).graph(CgAnimGraphCodec.build("test:back", back, ARM,
                clips::get, (at, kind, did) -> face)).tick(0.1f)), 1e-5f);

        assertThrows("an input no layer declares", CgAssetException.class,
                () -> compile("test:bad", hero.replace("inputs: { body: body }", "inputs: { legs: body }"), Map.of("test:face", face)));
        assertThrows("a layer the graph does not declare", CgAssetException.class,
                () -> compile("test:bad", hero.replace("layer: arms,", "layer: legs,"), Map.of("test:face", face)));
        assertThrows("a graph that is no anim_graph", CgAssetException.class,
                () -> CgAnimGraphCodec.compile(CgAssetData.of("test:bad", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(hero), null),
                        (kind, did) -> "not a graph"));
    }

    @Test
    public void oneGraphOnTwoCharactersPlaysEachOnesClipsAndAChildOverridesAnAssetAlone() {
        String yaml = "_v: 1\noutput: idle\nnodes:\n  idle: { kind: sequence_player, clip: kit:idle, loop: true, start_position: 0.5 }\n";
        CgAnimGraphDefinition template = compile("test:template", yaml, Map.of());
        CgAnimInstance goku = new CgAnimInstance(MESH).graph(template.graph(ARM, id -> at("goku_idle", 3)));
        CgAnimInstance vegeta = new CgAnimInstance(MESH).graph(template.graph(ARM, id -> at("vegeta_idle", 6)));
        assertEquals("the same graph, Goku's clip", 3f, elbow(goku.tick(0.1f)), 1e-5f);
        assertEquals("the same graph, Vegeta's clip", 6f, elbow(vegeta.tick(0.1f)), 1e-5f);

        CgAssetData parent = CgAssetData.of("test:template", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        CgAssetData child = CgAssetData.of("test:child", CgBodyKinds.ANIM_GRAPH,
                (Map<?, ?>) CgAssetYaml.read("_v: 1\nparent: test:template\nnodes:\n  idle: { clip: kit:goku_idle }\n"), parent);
        CgAnimGraphDefinition c = CgAnimGraphCodec.compile(child, null);
        Map<?, ?> idle = (Map<?, ?>) ((Map<?, ?>) c.tree().get("nodes")).get("idle");
        assertEquals("the asset replaced", "kit:goku_idle", idle.get("clip"));
        assertEquals("the rest of the node kept", true, idle.get("loop"));
        assertEquals(0.5, ((Number) idle.get("start_position")).doubleValue(), 0);
    }

    @Test
    public void aWarmFrameWithLinkedLayersAndGraphsAllocatesNothing() {
        CgAnimGraph face = CgAnimGraph.builder("test:face", ARM).inputs("body")
                .output(CgTwoWayBlendNode.of(CgLinkedInputPoseNode.of("body"), hold(8), 0.5f)).build();
        CgAnimGraph hero = CgAnimGraph.builder("test:hero", ARM).variables(ALPHA)
                .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body"))
                .output(CgLinkedAnimGraphNode.of(face).input("body", CgLinkedAnimLayerNode.of("arms").input("body", hold(2))))
                .build();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero).linkLayers(sword(0f)).set(ALPHA, 0.5f);
        for (int f = 0; f < 20000; f++) anim.tick(0.016f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) anim.tick(0.016f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }
}
