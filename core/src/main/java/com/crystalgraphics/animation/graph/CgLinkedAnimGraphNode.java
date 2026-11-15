package com.crystalgraphics.animation.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * Runs another graph as a node, its output this node's pose: Unreal's {@code FAnimNode_LinkedAnimGraph}
 * (AnimNode_LinkedAnimGraph.cpp). The poses it passes are the linked graph's {@code inputs}; a tag lets the game swap
 * which graph runs ({@code anim.linkGraph(tag, graph)}).
 *
 * <pre>{@code
 * CgAnimGraph face = CgAnimGraph.builder("mymod:face", skeleton).inputs("body")
 *         .output(CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body")).layer(blink, CgBlendMask.branch("head", 0)))
 *         .build();
 * CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
 *         .output(CgLinkedAnimGraphNode.of(face).input("body", locomotion).tag("face"))
 *         .build();
 * anim.linkGraph("face", ANGRY_FACE);    // another face, the same body under it
 * anim.linkGraph("face", null);          // none: the body passes through
 * }</pre>
 *
 * <ul>
 *   <li>Each input it passes is one of the linked graph's {@code inputs}.</li>
 *   <li>With no graph, its first input passes through; with no input either, the rest pose.</li>
 *   <li>The linked graph's variables are its owner's of the same name and type; its slots are its owner's.</li>
 * </ul>
 */
public final class CgLinkedAnimGraphNode extends CgAnimNode {

    @Nullable
    private final CgAnimGraph graph;
    @Nullable
    private final String tag;
    private final Map<String, CgAnimNode> inputs;

    private CgLinkedAnimGraphNode(@Nullable CgAnimGraph graph, @Nullable String tag, Map<String, CgAnimNode> inputs) {
        this.graph = graph;
        this.tag = tag;
        this.inputs = Collections.unmodifiableMap(inputs);
    }

    /** Runs {@code graph}; null runs none until one is linked by its tag. */
    public static CgLinkedAnimGraphNode of(@Nullable CgAnimGraph graph) {
        return new CgLinkedAnimGraphNode(graph, null, new LinkedHashMap<>());
    }

    /** As this, passing {@code pose} as the linked graph's input {@code name}. */
    public CgLinkedAnimGraphNode input(String name, CgAnimNode pose) {
        Map<String, CgAnimNode> m = new LinkedHashMap<>(inputs);
        if (m.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(pose, "pose")) != null) {
            throw new IllegalArgumentException(this + " passes " + name + " twice");
        }
        return new CgLinkedAnimGraphNode(graph, tag, m);
    }

    /** As this, found by {@code tag} to swap its graph. */
    public CgLinkedAnimGraphNode tag(String tag) {
        return new CgLinkedAnimGraphNode(graph, Objects.requireNonNull(tag, "tag"), inputs);
    }

    @Nullable
    public CgAnimGraph graph() {
        return graph;
    }

    @Nullable
    public String tag() {
        return tag;
    }

    public Map<String, CgAnimNode> inputs() {
        return inputs;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new GraphRuntime(g, this);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        inputs.values().forEach(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("linked_anim_graph");
        if (graph == null) throw new UnsupportedOperationException(this + " links no graph, which a file cannot say");
        out.put("graph", graph.id());
        if (tag != null) out.put("tag", tag);
        if (!inputs.isEmpty()) out.nodeMap("inputs", inputs);
    }

    @Override
    public String toString() {
        return "linked_anim_graph(" + (graph == null ? "none" : graph.id()) + ")";
    }

    /** A linked graph node's run: the linked instance and its output, or none. */
    static final class GraphRuntime extends Runtime {

        private final CgLinkedAnimGraphNode node;
        private final String[] names;
        private final Runtime[] inputs;
        private final boolean inertialized;
        @Nullable
        private CgAnimGraphInstance target;
        @Nullable
        private Runtime root;

        GraphRuntime(CgAnimGraphInstance g, CgLinkedAnimGraphNode node) {
            this.node = node;
            names = node.inputs.keySet().toArray(new String[0]);
            inputs = new Runtime[names.length];
            for (int i = 0; i < names.length; i++) inputs[i] = g.runtime(node.inputs.get(names[i]));
            inertialized = g.inertializerAbove();
            if (node.graph != null) start(g, node.graph);
        }

        @Nullable
        String tag() {
            return node.tag;
        }

        private void start(CgAnimGraphInstance owner, CgAnimGraph graph) {
            CgAnimGraphInstance into = owner.linkedInstance(graph, inertialized);
            for (String in : names) {
                if (!graph.inputs().contains(in)) {
                    into.unlinkFromMain();
                    throw new IllegalArgumentException(node + ": " + graph + " has no input " + in);
                }
            }
            for (int i = 0; i < names.length; i++) into.input("", names[i]).set(inputs[i], owner);
            into.users++;
            target = into;
            root = into.runtime(graph.output());
        }

        /** {@code SetAnimClass}: {@code graph} from its first frame, or none. */
        void link(CgAnimGraphInstance owner, @Nullable CgAnimGraph graph) {
            if (target != null) {
                if (graph == target.graph()) return;
                if (--target.users == 0) target.unlinkFromMain();
                target = null;
                root = null;
            }
            if (graph != null) start(owner, graph);
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            for (Runtime r : inputs) r.initialize(g);
            if (target != null) target.initialize();
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            if (target != null) target.linkedUpdate(root, weight);
            else if (inputs.length > 0) inputs[0].update(g, weight);
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (target != null) target.linkedEvaluate(root, out);
            else if (inputs.length > 0) inputs[0].evaluate(g, out);
            else out.rest();
        }
    }
}
