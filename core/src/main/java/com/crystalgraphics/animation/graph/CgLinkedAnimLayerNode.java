package com.crystalgraphics.animation.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * Plays a layer: its graph's own until another graph's is linked in ({@code anim.linkLayers(graph)}), then that one's.
 * Unreal's {@code FAnimNode_LinkedAnimLayer} (AnimNode_LinkedAnimLayer.cpp): a self layer, or a linked instance of the
 * class implementing it, which ungrouped layers have one each of and a group shares.
 *
 * <pre>{@code
 * CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
 *         .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body").blend(0.2f, 0.2f))   // passes through
 *         .output(CgInertializationNode.of(CgLinkedAnimLayerNode.of("arms").input("body", locomotion)))
 *         .build();
 * anim.graph(hero);
 * anim.linkLayers(SWORD_ARMS);       // a graph whose "arms" layer holds the sword: blended in over 0.2 s
 * anim.unlinkLayers(SWORD_ARMS);     // the hero's own arms again
 * }</pre>
 *
 * <ul>
 *   <li>Its graph declares the layer; each input it passes is one of the layer's.</li>
 *   <li>A layer with a blend time needs an inertialization node above it; a swap with none fails as it is linked.</li>
 *   <li>A linked layer's variables are its owner's of the same name and type; its slots are its owner's.</li>
 * </ul>
 */
public final class CgLinkedAnimLayerNode extends CgAnimNode {

    private final String layer;
    private final Map<String, CgAnimNode> inputs;

    private CgLinkedAnimLayerNode(String layer, Map<String, CgAnimNode> inputs) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.inputs = Collections.unmodifiableMap(inputs);
    }

    /** Plays layer {@code layer}, passing it no pose yet. */
    public static CgLinkedAnimLayerNode of(String layer) {
        return new CgLinkedAnimLayerNode(layer, new LinkedHashMap<>());
    }

    /** As this, passing {@code pose} as the layer's input {@code name}. */
    public CgLinkedAnimLayerNode input(String name, CgAnimNode pose) {
        Map<String, CgAnimNode> m = new LinkedHashMap<>(inputs);
        if (m.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(pose, "pose")) != null) {
            throw new IllegalArgumentException(this + " passes " + name + " twice");
        }
        return new CgLinkedAnimLayerNode(layer, m);
    }

    public String layer() {
        return layer;
    }

    /** The poses it passes, by input name. */
    public Map<String, CgAnimNode> inputs() {
        return inputs;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new LayerRuntime(g, this);
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        inputs.values().forEach(each);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("linked_anim_layer").put("layer", layer);
        if (!inputs.isEmpty()) out.nodeMap("inputs", inputs);
    }

    @Override
    public String toString() {
        return "linked_anim_layer(" + layer + ")";
    }

    /** A layer node's run: its target (itself or a linked instance), the inputs it passes, the blends a swap asks. */
    static final class LayerRuntime extends Runtime {

        private final CgLinkedAnimLayerNode node;
        private final String[] names;
        private final Runtime[] inputs;
        private final boolean inertialized;
        private final CgAnimGraph.Layer self;
        private Runtime root;
        private CgAnimGraphInstance target;
        private CgAnimGraph.Layer playing;
        private float pendingIn = -1f, pendingOut = -1f;

        LayerRuntime(CgAnimGraphInstance g, CgLinkedAnimLayerNode node) {
            this.node = node;
            names = node.inputs.keySet().toArray(new String[0]);
            inputs = new Runtime[names.length];
            for (int i = 0; i < names.length; i++) inputs[i] = g.runtime(node.inputs.get(names[i]));
            inertialized = g.inertializerAbove();
            self = g.graph().layers().get(node.layer);
            if (self == null) throw new IllegalArgumentException(g.graph() + " declares no layer " + node.layer);
            if ((self.blendIn() > 0f || self.blendOut() > 0f) && !inertialized) {
                throw new IllegalArgumentException(node + " blends its swaps, and no inertialization node is above it");
            }
            target = g;
            playing = self;
            bind(g, g);
            root = g.layerRuntime(node.layer);
        }

        String layer() {
            return node.layer;
        }

        /** The graph it plays a linked layer of, or null playing its own. */
        @Nullable
        CgAnimGraph linkedGraph() {
            return playing == self ? null : target.graph();
        }

        /** Whether an inertialization node is above it: what a linked instance made for it is built under. */
        boolean inertialized() {
            return inertialized;
        }

        /** Its inputs handed to {@code into}'s layer, read in {@code owner}. */
        private void bind(CgAnimGraphInstance into, CgAnimGraphInstance owner) {
            for (int i = 0; i < names.length; i++) into.input(node.layer, names[i]).set(inputs[i], owner);
        }

        /**
         * Plays {@code layer} of {@code into}, or its own for null: Unreal's {@code SetLinkedLayerInstance}, the swap
         * inertialized over the old layer's blend out and the new one's blend in ({@code RequestBlend}).
         */
        void link(CgAnimGraphInstance owner, @Nullable CgAnimGraphInstance into, @Nullable CgAnimGraph.Layer layer) {
            CgAnimGraph.Layer next = into == null ? self : layer;
            CgAnimGraphInstance was = target;
            if ((playing.blendOut() > 0f || next.blendIn() > 0f) && !inertialized) {
                throw new IllegalArgumentException(node + ": " + next.name() + " blends its swap, and no inertialization node is above it");
            }
            pendingOut = playing.blendOut();
            pendingIn = next.blendIn();
            target = into == null ? owner : into;
            playing = next;
            bind(target, owner);
            root = target.layerRuntime(node.layer);
            if (target != owner) {
                target.users++;
                root.initialize(target);
            }
            if (was != owner && --was.users == 0) was.unlinkFromMain();
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            for (Runtime r : inputs) r.initialize(g);
            root.initialize(target);
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            if (pendingOut >= 0f || pendingIn >= 0f) {
                CgAnimGraphInstance.Inertializer in = g.inertializer();
                if (in != null) {
                    if (pendingOut > 0f) in.requestInertialization(pendingOut);
                    if (pendingIn > 0f) in.requestInertialization(pendingIn);
                }
                pendingOut = pendingIn = -1f;
            }
            if (target == g) root.update(g, weight);
            else target.linkedUpdate(root, weight);
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (target == g) root.evaluate(g, out);
            else target.linkedEvaluate(root, out);
        }
    }
}
