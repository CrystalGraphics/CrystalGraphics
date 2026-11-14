package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * An additive pose added over a base at an alpha: Unreal's {@code FAnimNode_ApplyAdditive}, in each bone's parent space
 * ({@code AccumulateLocalSpaceAdditivePose}). The additive side is a player of an additive clip
 * ({@code CgClipSettings.additive}), or anything making differences.
 *
 * <pre>{@code
 * CgApplyAdditiveNode.of(locomotion, CgSequencePlayerNode.of(BREATHE).loop(), 1f);   // a breath over whatever plays
 * CgApplyAdditiveNode.of(body, CgSequencePlayerNode.of(FLINCH), FLINCH_ALPHA);      // its alpha a variable
 * }</pre>
 *
 * <p>The additive side is updated only while its alpha is relevant, so its clip waits where it was.</p>
 */
public final class CgApplyAdditiveNode extends CgAnimNode {

    private final CgAnimNode base, additive;
    private final float alpha;
    @Nullable
    private final CgAnimVariable<Float> alphaVariable;

    private CgApplyAdditiveNode(CgAnimNode base, CgAnimNode additive, float alpha, @Nullable CgAnimVariable<Float> alphaVariable) {
        this.base = Objects.requireNonNull(base, "base");
        this.additive = Objects.requireNonNull(additive, "additive");
        this.alpha = alpha;
        this.alphaVariable = alphaVariable;
    }

    public static CgApplyAdditiveNode of(CgAnimNode base, CgAnimNode additive, float alpha) {
        return new CgApplyAdditiveNode(base, additive, alpha, null);
    }

    public static CgApplyAdditiveNode of(CgAnimNode base, CgAnimNode additive, CgAnimVariable<Float> alpha) {
        return new CgApplyAdditiveNode(base, additive, 0f, Objects.requireNonNull(alpha, "alpha"));
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime rb = g.runtime(base), ra = g.runtime(additive);
        return new Runtime() {
            private float now;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                rb.initialize(g);
                ra.initialize(g);
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                rb.update(g, weight);
                now = Math.max(0f, Math.min(1f, alphaVariable != null ? g.get(alphaVariable) : alpha));
                if (now > CgAnimPose.ZERO_WEIGHT) ra.update(g, weight * now);
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                rb.evaluate(g, out);
                if (now <= CgAnimPose.ZERO_WEIGHT) return;
                CgAnimPose delta = g.borrow();
                ra.evaluate(g, delta);
                out.accumulateAdditive(delta, now);
                g.giveBack(delta);
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(base);
        each.accept(additive);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        if (alphaVariable != null) each.accept(alphaVariable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("apply_additive").node("base", base).node("additive", additive).alpha("alpha", alpha, alphaVariable, 1f);
    }

    @Override
    public String toString() {
        return "additive(" + base + ", " + additive + ")";
    }
}
