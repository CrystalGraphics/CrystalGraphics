package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * Two poses mixed by an alpha: Unreal's {@code FAnimNode_TwoWayBlend}. At 0 only {@code a} plays (and only it is
 * updated), at 1 only {@code b}, between them both, each at its share.
 *
 * <pre>{@code
 * CgTwoWayBlendNode.of(idle, jog, RUN);                    // RUN, a float variable, 0 to 1
 * CgTwoWayBlendNode.of(standing, crouched, 0.5f);          // a constant half and half
 * }</pre>
 *
 * <ul>
 *   <li>The alpha is clamped to 0..1.</li>
 *   <li>A pose blended out keeps its place: a clip under it does not tick while its share is irrelevant.</li>
 * </ul>
 */
public final class CgTwoWayBlendNode extends CgAnimNode {

    private final CgAnimNode a, b;
    private final float alpha;
    @Nullable
    private final CgAnimVariable<Float> alphaVariable;

    private CgTwoWayBlendNode(CgAnimNode a, CgAnimNode b, float alpha, @Nullable CgAnimVariable<Float> alphaVariable) {
        this.a = Objects.requireNonNull(a, "a");
        this.b = Objects.requireNonNull(b, "b");
        this.alpha = alpha;
        this.alphaVariable = alphaVariable;
    }

    /** {@code a} and {@code b} by the variable {@code alpha}. */
    public static CgTwoWayBlendNode of(CgAnimNode a, CgAnimNode b, CgAnimVariable<Float> alpha) {
        return new CgTwoWayBlendNode(a, b, 0f, Objects.requireNonNull(alpha, "alpha"));
    }

    /** {@code a} and {@code b} by a constant {@code alpha}. */
    public static CgTwoWayBlendNode of(CgAnimNode a, CgAnimNode b, float alpha) {
        return new CgTwoWayBlendNode(a, b, alpha, null);
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime ra = g.runtime(a), rb = g.runtime(b);
        return new Runtime() {
            private final CgAnimPose[] poses = new CgAnimPose[2];
            private final float[] weights = new float[2];
            private float now;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                ra.initialize(g);
                rb.initialize(g);
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                now = Math.max(0f, Math.min(1f, alphaVariable != null ? g.get(alphaVariable) : alpha));
                if (1f - now > CgAnimPose.ZERO_WEIGHT) ra.update(g, weight * (1f - now));
                if (now > CgAnimPose.ZERO_WEIGHT) rb.update(g, weight * now);
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                if (now <= CgAnimPose.ZERO_WEIGHT) {
                    ra.evaluate(g, out);
                } else if (now >= 1f - CgAnimPose.ZERO_WEIGHT) {
                    rb.evaluate(g, out);
                } else {
                    CgAnimPose pa = g.borrow(), pb = g.borrow();
                    ra.evaluate(g, pa);
                    rb.evaluate(g, pb);
                    poses[0] = pa;
                    poses[1] = pb;
                    weights[0] = 1f - now;
                    weights[1] = now;
                    out.blend(poses, weights, 2);
                    poses[0] = poses[1] = null;
                    g.giveBack(pb);
                    g.giveBack(pa);
                }
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(a);
        each.accept(b);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        if (alphaVariable != null) each.accept(alphaVariable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("two_way_blend").node("a", a).node("b", b).alpha("alpha", alpha, alphaVariable, Float.NaN);
    }

    @Override
    public String toString() {
        return "blend(" + a + ", " + b + ")";
    }
}
