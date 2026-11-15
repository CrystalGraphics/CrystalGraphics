package com.crystalgraphics.animation.ik;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * A node that moves bones over its input pose: Unreal's {@code FAnimNode_SkeletalControlBase}
 * (AnimNode_SkeletalControlBase.cpp). Its control solves in the skeleton's space against the input as posed, its bones'
 * new transforms then landing over the input at the node's {@link CgControlAlpha}, each moved bone's children riding
 * it ({@link CgAnimPose#blendComponent}).
 *
 * <pre>{@code
 * // a control of your own: the head turned to face +z in the skeleton's space
 * final class FaceForward extends CgSkeletalControlNode {
 *     FaceForward(CgAnimNode source) { super(source, CgControlAlpha.FULL); }
 *     protected Control control(CgAnimGraphInstance g) {
 *         int head = g.skeleton().indexOf("head");
 *         return new Control(1) {
 *             protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
 *                 bones[0] = head;
 *                 out[0].set(pose.component(head)).setRotationXYZ(0f, 0f, 0f);
 *                 return 1;
 *             }
 *         };
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A control writes its bones in ascending order, each in the skeleton's space, and returns how many.</li>
 *   <li>Its {@code update} and {@code evaluate} run only while its alpha is relevant; its input runs always.</li>
 *   <li>A control's per-frame state is made in {@link #control}, once: it allocates nothing after.</li>
 * </ul>
 */
public abstract class CgSkeletalControlNode extends CgAnimNode {

    protected final CgAnimNode source;
    protected final CgControlAlpha alpha;

    protected CgSkeletalControlNode(CgAnimNode source, CgControlAlpha alpha) {
        this.source = Objects.requireNonNull(source, "source");
        this.alpha = Objects.requireNonNull(alpha, "alpha");
    }

    /** One instance's control: the solver, with room for {@code capacity} bones. */
    protected abstract static class Control {
        final int[] bones;
        final Matrix4f[] transforms;

        protected Control(int capacity) {
            bones = new int[capacity];
            transforms = new Matrix4f[capacity];
            for (int i = 0; i < capacity; i++) transforms[i] = new Matrix4f();
        }

        /** Unreal's {@code UpdateInternal}: while the alpha is relevant. */
        protected void update(CgAnimGraphInstance g, float dt) {
        }

        /**
         * Unreal's {@code EvaluateSkeletalControl_AnyThread}: its bones' new transforms in the skeleton's space, ascending,
         * into {@code bones} and {@code out}; returns how many. {@code pose} is the input, resolved.
         */
        protected abstract int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out);

        /** Back to its first frame. */
        protected void initialize(CgAnimGraphInstance g) {
        }

        /** Whether it reads the game's state on the game thread first: {@link #preUpdate}. */
        protected boolean hasPreUpdate() {
            return false;
        }

        /** The game thread's turn before the worker's: Unreal's {@code PreUpdate}. */
        protected void preUpdate(CgAnimGraphInstance g) {
        }
    }

    /** This node's control in {@code g}, made once. */
    protected abstract Control control(CgAnimGraphInstance g);

    @Override
    protected final Runtime instantiate(CgAnimGraphInstance g) {
        Runtime input = g.runtime(source);
        Control control = control(g);
        CgControlAlpha.State state = alpha.state();
        return new Runtime() {
            private float actual;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                input.initialize(g);
                state.reset();
                control.initialize(g);
            }

            @Override
            public boolean hasPreUpdate() {
                return control.hasPreUpdate();
            }

            @Override
            public void preUpdate(CgAnimGraphInstance g) {
                control.preUpdate(g);
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                input.update(g, weight);
                actual = state.apply(g, g.delta());
                if (actual > CgAnimPose.ZERO_WEIGHT) control.update(g, g.delta());
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                input.evaluate(g, out);
                if (actual <= CgAnimPose.ZERO_WEIGHT) return;
                out.resolve();
                int n = control.evaluate(g, out, control.bones, control.transforms);
                out.blendComponent(n, control.bones, control.transforms, actual);
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(source);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        alpha.reads(each);
    }

    /** Bone {@code name} of {@code skeleton}, failing by name where it has none. */
    protected static int bone(CgSkeleton skeleton, String name, Object node) {
        int b = skeleton.indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException(node + ": the skeleton has no bone " + name);
        return b;
    }

    /** Writes the base's fields, the input and the alpha, after a kind's {@code out.kind(...)}. */
    protected final void writeBase(CgAnimNodeWriter out) {
        out.node("source", source);
        if (alpha.enabled() != null) {
            out.put("alpha", Map.of("bool", alpha.enabled().name(), "blend_in", alpha.blendIn(), "blend_out", alpha.blendOut()));
        } else {
            out.alpha("alpha", alpha.constant(), alpha.variable(), 1f);
        }
    }
}
