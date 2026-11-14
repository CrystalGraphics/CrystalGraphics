package com.crystalgraphics.animation.graph;

import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * The skeleton's rest pose: Unreal's {@code FAnimNode_RefPose}. A slot's source where nothing plays under it.
 *
 * <pre>{@code
 * CgSlotNode.of("DefaultSlot", CgRefPoseNode.create());   // montages over a standing rest pose
 * }</pre>
 *
 * <p>Read by one parent, as every node is: a second rest pose is a second {@link #create()}.</p>
 */
public final class CgRefPoseNode extends CgAnimNode {

    private CgRefPoseNode() {
    }

    /** A rest pose node. */
    public static CgRefPoseNode create() {
        return new CgRefPoseNode();
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new Runtime() {
            @Override
            public void update(CgAnimGraphInstance g, float weight) {
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                out.rest();
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("ref_pose");
    }

    @Override
    public String toString() {
        return "rest";
    }
}
