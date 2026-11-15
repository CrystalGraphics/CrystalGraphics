package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * A pose handed in by whoever plays this part of the graph: inside a layer, the layer node's input of that name; in a
 * graph another graph plays, its linked graph node's. Unreal's {@code FAnimNode_LinkedInputPose}.
 *
 * <pre>{@code
 * CgAnimGraph.Layer legs = CgAnimGraph.Layer.of("legs",
 *         CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body")).layer(run, CgBlendMask.branch("pelvis", 0)), "body");
 * }</pre>
 *
 * <ul>
 *   <li>The name is one of its layer's inputs, or of its graph's {@code inputs}: anything else fails the build.</li>
 *   <li>With nothing handed in, it is the rest pose.</li>
 * </ul>
 */
public final class CgLinkedInputPoseNode extends CgAnimNode {

    private final String name;

    private CgLinkedInputPoseNode(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The input pose named {@code name}. */
    public static CgLinkedInputPoseNode of(String name) {
        return new CgLinkedInputPoseNode(name);
    }

    public String name() {
        return name;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        CgAnimGraphInstance.Input input = g.input(name);
        return new Runtime() {
            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                if (input.source != null) input.source.update(input.instance, weight);
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                if (input.source != null) input.source.evaluate(input.instance, out);
                else out.rest();
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("linked_input_pose").put("name", name);
    }

    @Override
    public String toString() {
        return "linked_input_pose(" + name + ")";
    }
}
