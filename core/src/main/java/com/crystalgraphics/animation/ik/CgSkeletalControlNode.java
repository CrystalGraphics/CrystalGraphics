package com.crystalgraphics.animation.ik;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.joml.Matrix4f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

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

    /** The bones from {@code root} down to {@code tip}, root first, failing by name where {@code tip} is not below it. */
    protected static int[] chain(CgSkeleton skeleton, String root, String tip, Object node) {
        int r = bone(skeleton, root, node), b = bone(skeleton, tip, node), n = 1;
        for (int at = b; at != r; at = skeleton.parent(at), n++) {
            if (at < 0) throw new IllegalArgumentException(node + ": " + tip + " is not below " + root);
        }
        if (n < 2) throw new IllegalArgumentException(node + ": a chain needs two bones, " + root + " to " + tip);
        int[] out = new int[n];
        for (int at = b, i = n - 1; i >= 0; at = skeleton.parent(at), i--) out[i] = at;
        return out;
    }

    /** Writes the base's fields, the input and the alpha (omitted at full), after a kind's {@code out.kind(...)}. */
    protected final void writeBase(CgAnimNodeWriter out) {
        out.node("source", source);
        if (!alpha.full()) out.put("alpha", alpha.tree());
    }

    // ── reading a control's data form ──

    /** Its {@code source} node. */
    static CgAnimNode source(CgAnimNodeKind.Context c, String at, Map<?, ?> m) {
        return c.pose(c.string(at, m, "source"));
    }

    /** Its {@code alpha}, {@link CgControlAlpha#FULL} where absent. */
    static CgControlAlpha alpha(CgAnimNodeKind.Context c, String at, Map<?, ?> m) {
        return CgControlAlpha.read(c, at + ".alpha", m.get("alpha"));
    }

    /** {@code m.key} as a number, {@code dflt} where absent. */
    static float number(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key, float dflt) {
        Object o = m.get(key);
        return o == null ? dflt : c.number(at + "." + key, o);
    }

    /** {@code m.key} as three numbers, {@code dflt} where absent. */
    static float[] vector(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key, float... dflt) {
        float[] v = CgControlAlpha.numbers(c, at + "." + key, m.get(key), 3);
        return v != null ? v : dflt;
    }

    /** {@code m.key} as an enum constant by its snake-case name, {@code dflt} where absent. */
    static <E extends Enum<E>> E choice(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key, Class<E> type, E dflt) {
        Object o = m.get(key);
        if (o == null) return dflt;
        for (E e : type.getEnumConstants()) if (e.name().toLowerCase(Locale.ROOT).equals(o)) return e;
        throw new CgAssetException(at + "." + key + ": " + o + " is not one of " + Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT));
    }

    /** {@code [x, y, z]} as three booleans, all {@code dflt} where absent. */
    static boolean[] flags(String at, Object o, boolean dflt) {
        if (o == null) return new boolean[]{dflt, dflt, dflt};
        if (!(o instanceof List<?> l) || l.size() != 3) throw new CgAssetException(at + ": three booleans");
        boolean[] out = new boolean[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Boolean flag)) throw new CgAssetException(at + ": three booleans");
            out[i] = flag;
        }
        return out;
    }

    /** {@code e}'s data form: its name, snake-cased. */
    static String id(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
