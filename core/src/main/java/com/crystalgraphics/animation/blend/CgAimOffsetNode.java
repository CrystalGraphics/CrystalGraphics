package com.crystalgraphics.animation.blend;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAdditive;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * A look or an aim turned onto whatever plays: Unreal's {@code FAnimNode_RotationOffsetBlendSpace} (aim offset player,
 * AnimNode_RotationOffsetBlendSpace.cpp:39-106). Its blend space's samples are mesh-space rotation additives (each a
 * pose aimed up, down, left, right); the one at its inputs is added over the base in the mesh's space, so the spine,
 * arms and head turn together whatever the legs do.
 *
 * <pre>{@code
 * CgAimOffsetNode.of(locomotion, AIM, YAW, PITCH);             // the aim at the character's look
 * CgAimOffsetNode.of(locomotion, AIM, YAW, PITCH).alpha(AIMING); // only while aiming
 * }</pre>
 *
 * <ul>
 *   <li>The blend space's clips are {@code additive: mesh_space}, or the graph fails at bind naming it.</li>
 *   <li>Its blend space updates only while the alpha is relevant; the base always.</li>
 * </ul>
 */
public final class CgAimOffsetNode extends CgAnimNode {

    /** The {@code aim_offset} kind's reader. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgAnimNode base = c.pose(c.string(at, m, "base"));
        CgBlendSpace space = CgBlendSpacePlayerNode.read(c, at, m);
        CgBlendSpacePlayerNode.Inputs in = CgBlendSpacePlayerNode.Inputs.read(c, at, m);
        Object alpha = m.get("alpha");
        CgAnimVariable<Float> alphaVar = alpha instanceof String v ? c.variable(at + ".alpha", v, CgAnimVariable.Type.FLOAT) : null;
        return new CgAimOffsetNode(base, space, in, alpha == null || alphaVar != null ? 1f : c.number(at + ".alpha", alpha), alphaVar);
    };

    private final CgAnimNode base;
    private final CgBlendSpace space;
    private final CgBlendSpacePlayerNode.Inputs inputs;
    private final float alpha;
    @Nullable
    private final CgAnimVariable<Float> alphaVariable;

    private CgAimOffsetNode(CgAnimNode base, CgBlendSpace space, CgBlendSpacePlayerNode.Inputs inputs, float alpha,
                            @Nullable CgAnimVariable<Float> alphaVariable) {
        this.base = Objects.requireNonNull(base, "base");
        this.space = Objects.requireNonNull(space, "space");
        if (space.additive() != CgAdditive.Type.MESH_SPACE) {
            throw new IllegalArgumentException(space.id() + " is no aim offset: its samples are not mesh-space additives");
        }
        this.inputs = inputs;
        this.alpha = alpha;
        this.alphaVariable = alphaVariable;
    }

    /** {@code space} over {@code base} by {@code x} (yaw) and {@code y} (pitch). */
    public static CgAimOffsetNode of(CgAnimNode base, CgBlendSpace space, CgAnimVariable<Float> x, CgAnimVariable<Float> y) {
        return new CgAimOffsetNode(base, space, new CgBlendSpacePlayerNode.Inputs(0f, Objects.requireNonNull(x, "x"), 0f,
                Objects.requireNonNull(y, "y"), 1f, null), 1f, null);
    }

    /** Added at a constant {@code alpha}. */
    public CgAimOffsetNode alpha(float alpha) {
        return new CgAimOffsetNode(base, space, inputs, alpha, null);
    }

    /** Added at {@code alpha}, a variable 0..1. */
    public CgAimOffsetNode alpha(CgAnimVariable<Float> alpha) {
        return new CgAimOffsetNode(base, space, inputs, 1f, Objects.requireNonNull(alpha, "alpha"));
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime rb = g.runtime(base);
        CgBlendSpacePlayerNode.PlayerRuntime player = new CgBlendSpacePlayerNode.PlayerRuntime(space, inputs, true, 0f, null);
        return new Runtime() {
            private float now;

            @Override
            public void initialize(CgAnimGraphInstance g) {
                rb.initialize(g);
                player.initialize(g);
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                now = Math.max(0f, Math.min(1f, alphaVariable != null ? g.get(alphaVariable) : alpha));
                if (now > CgAnimPose.ZERO_WEIGHT) player.update(g, weight);
                rb.update(g, weight);
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                rb.evaluate(g, out);
                if (now <= CgAnimPose.ZERO_WEIGHT) return;
                CgAnimPose aim = g.borrow();
                player.evaluate(g, aim);
                out.accumulateMeshRotationAdditive(aim, now);
                g.giveBack(aim);
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(base);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        inputs.reads(each);
        if (alphaVariable != null) each.accept(alphaVariable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("aim_offset").node("base", base).put("blend_space", space.id());
        inputs.write(out);
        out.alpha("alpha", alpha, alphaVariable, 1f);
    }

    @Override
    public String toString() {
        return "aimOffset(" + space.id() + ", " + base + ")";
    }
}
