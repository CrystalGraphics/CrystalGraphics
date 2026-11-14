package com.crystalgraphics.animation.graph;

import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * One node of an animation graph: a source of a pose (a clip playing, a blend, a slot), as Unreal's
 * {@code FAnimNode_Base}. A node is a definition, immutable and shared by every instance of its graph; each instance
 * gets a {@link Runtime} of its own, made once when the instance binds, which holds what the node remembers between
 * frames (a clip's time, a blend's weights).
 *
 * <pre>{@code
 * CgAnimNode idle = CgSequencePlayerNode.of(IDLE).loop();
 * CgAnimNode run = CgSequencePlayerNode.of(RUN).loop();
 * CgAnimNode body = CgTwoWayBlendNode.of(idle, run, SPEED_ALPHA);         // its alpha read from a variable
 * CgAnimGraph graph = CgAnimGraph.builder("mymod:hero", skeleton).variables(SPEED_ALPHA)
 *         .output(CgSlotNode.of("DefaultSlot", body)).build();
 * }</pre>
 *
 * <p>A node kind of your own extends this and its {@link Runtime}, with Unreal's two phases:</p>
 *
 * <pre>{@code
 * final class Hold extends CgAnimNode {                                    // the source's pose, frozen while a bool holds
 *     final CgAnimNode source; final CgAnimVariable<Boolean> frozen;
 *     protected Runtime instantiate(CgAnimGraphInstance g) {
 *         Runtime src = g.runtime(source);
 *         return new Runtime() {
 *             public void update(CgAnimGraphInstance g, float weight) { if (!g.is(frozen)) src.update(g, weight); }
 *             public void evaluate(CgAnimGraphInstance g, CgAnimPose out) { src.evaluate(g, out); }
 *         };
 *     }
 *     protected void children(Consumer<CgAnimNode> each) { each.accept(source); }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link Runtime#update} runs first, root to leaves, with the weight the node reaches the output at; a node passes
 *       its children their share and never updates a child whose share is irrelevant (Unreal's relevance). Players
 *       record their tick there; their time moves after the walk.</li>
 *   <li>{@link Runtime#evaluate} then writes the node's local pose into {@code out}. Borrow scratch poses from the
 *       graph instance and give them back before returning.</li>
 *   <li>Both run on a worker, one instance per worker at a time: read variables through the graph instance, never
 *       the game's state.</li>
 *   <li>Neither allocates: what a node needs per frame is made in {@link #instantiate}.</li>
 * </ul>
 */
public abstract class CgAnimNode {

    /** The node's state in one instance: what it remembers between frames. */
    public abstract static class Runtime {

        /**
         * This frame's update, at {@code weight}: its children updated at their shares, a player's tick recorded.
         * Unreal's {@code Update_AnyThread}.
         */
        public abstract void update(CgAnimGraphInstance g, float weight);

        /** Its local pose into {@code out}: Unreal's {@code Evaluate_AnyThread}. */
        public abstract void evaluate(CgAnimGraphInstance g, CgAnimPose out);

        /** Back to its first frame: a clip to its start, a blend to its first choice. Unreal's {@code Initialize_AnyThread}. */
        public void initialize(CgAnimGraphInstance g) {
        }
    }

    /** Its runtime in {@code g}; children's runtimes come from {@link CgAnimGraphInstance#runtime}. */
    protected abstract Runtime instantiate(CgAnimGraphInstance g);

    /** Each child this node reads, for the graph's walk at build: cycles and pose depth. */
    protected abstract void children(Consumer<CgAnimNode> each);

    /** Each node it reads, in order: for a tool walking a graph. */
    public final void forEachChild(Consumer<CgAnimNode> each) {
        children(each);
    }

    /** Each variable this node reads, so the graph can check it declares them. */
    protected void reads(Consumer<CgAnimVariable<?>> each) {
    }

    /**
     * Its data form, an {@code anim_graph} node: what {@link CgAnimGraphCodec#toTree} writes.
     *
     * @throws UnsupportedOperationException for a node with no data form, naming it
     */
    protected void write(CgAnimNodeWriter out) {
        throw new UnsupportedOperationException(this + " has no data form");
    }
}
