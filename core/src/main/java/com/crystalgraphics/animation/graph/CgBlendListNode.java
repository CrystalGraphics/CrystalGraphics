package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * One of several poses, chosen by a variable, blended in over a time when the choice changes: Unreal's blend list
 * nodes ({@code FAnimNode_BlendListByBool}, {@code ...ByInt}, from {@code FAnimNode_BlendListBase}).
 *
 * <pre>{@code
 * CgBlendListNode.byBool(GROUNDED, onGround, inAir).blendTimes(0.2f, 0.1f);   // the true pose first, as Unreal's
 * CgBlendListNode.byInt(GEAR, walk, jog, sprint).blendTimes(0.25f, 0.25f, 0.25f);
 * }</pre>
 *
 * <ul>
 *   <li>On a change every pose eases from its weight now toward 1 (the chosen) or 0 over the chosen pose's blend time,
 *       times how far it has to go; the weights are then normalised (AnimNode_BlendListBase.cpp, {@code
 *       Update_AnyThread}). The first choice is instant.</li>
 *   <li>An integer out of range chooses the first pose.</li>
 *   <li>{@link #resetOnActivate} restarts a pose's clips when it is chosen from no weight.</li>
 * </ul>
 */
public final class CgBlendListNode extends CgAnimNode {

    private final CgAnimVariable<?> selector;
    private final List<CgAnimNode> poses;
    private final float[] blendTimes;
    private final boolean reset;

    private CgBlendListNode(CgAnimVariable<?> selector, List<CgAnimNode> poses, float[] blendTimes, boolean reset) {
        this.selector = Objects.requireNonNull(selector, "selector");
        if (poses.isEmpty()) throw new IllegalArgumentException("a blend list has poses");
        this.poses = List.copyOf(poses);
        if (blendTimes.length != poses.size()) {
            throw new IllegalArgumentException("a blend list has a blend time per pose: " + blendTimes.length + " for " + poses.size());
        }
        this.blendTimes = blendTimes.clone();
        this.reset = reset;
    }

    /** {@code whenTrue} while {@code selector} holds, else {@code whenFalse}; instant until {@link #blendTimes}. */
    public static CgBlendListNode byBool(CgAnimVariable<Boolean> selector, CgAnimNode whenTrue, CgAnimNode whenFalse) {
        return new CgBlendListNode(selector, List.of(whenTrue, whenFalse), new float[2], false);
    }

    /** Pose {@code selector}, of {@code poses}; instant until {@link #blendTimes}. */
    public static CgBlendListNode byInt(CgAnimVariable<Integer> selector, CgAnimNode... poses) {
        return new CgBlendListNode(selector, List.of(poses), new float[poses.length], false);
    }

    /** Each pose's blend-in time, seconds, in pose order. */
    public CgBlendListNode blendTimes(float... seconds) {
        return new CgBlendListNode(selector, poses, seconds, reset);
    }

    /** Restart a pose when it is chosen from no weight: Unreal's {@code ResetChildOnActivate}. */
    public CgBlendListNode resetOnActivate() {
        return new CgBlendListNode(selector, poses, blendTimes, true);
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime[] children = new Runtime[poses.size()];
        for (int i = 0; i < children.length; i++) children[i] = g.runtime(poses.get(i));
        int n = children.length;
        return new Runtime() {
            private final float[] weight = new float[n], from = new float[n], to = new float[n];
            private final CgAnimPose[] scratch = new CgAnimPose[n];
            private final float[] shares = new float[n];
            private int active = -1;
            private float remaining, elapsed;

            @Override
            protected void initialize(CgAnimGraphInstance g) {
                for (Runtime c : children) c.initialize(g);
                active = -1;
                Arrays.fill(weight, 0f);
            }

            private int choice(CgAnimGraphInstance g) {
                int i = selector.type() == CgAnimVariable.Type.BOOL ? (g.get(selector) != 0f ? 0 : 1) : (int) g.get(selector);
                return i >= 0 && i < n ? i : 0;
            }

            @Override
            protected void update(CgAnimGraphInstance g, float w) {
                int chosen = choice(g);
                if (chosen != active) {
                    boolean first = active < 0;
                    float current = weight[chosen];
                    remaining = first ? 0f : blendTimes[chosen] * Math.min(1f, Math.abs(1f - current));
                    elapsed = 0f;
                    for (int i = 0; i < n; i++) {
                        from[i] = weight[i];
                        to[i] = i == chosen ? 1f : 0f;
                    }
                    if (reset && current <= CgAnimPose.ZERO_WEIGHT) children[chosen].initialize(g);
                    active = chosen;
                }
                elapsed += g.delta();
                float alpha = remaining > 0f ? Math.min(1f, elapsed / remaining) : 1f;
                float sum = 0f;
                for (int i = 0; i < n; i++) {
                    weight[i] = from[i] + (to[i] - from[i]) * alpha;
                    sum += weight[i];
                }
                for (int i = 0; i < n; i++) {
                    weight[i] = sum > 0f ? weight[i] / sum : (i == active ? 1f : 0f);
                    if (weight[i] > CgAnimPose.ZERO_WEIGHT) children[i].update(g, w * weight[i]);
                }
            }

            @Override
            protected void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                int used = 0;
                for (int i = 0; i < n; i++) {
                    if (weight[i] <= CgAnimPose.ZERO_WEIGHT) continue;
                    if (weight[i] >= 1f - CgAnimPose.ZERO_WEIGHT) {
                        children[i].evaluate(g, out);
                        return;
                    }
                    CgAnimPose p = g.borrow();
                    children[i].evaluate(g, p);
                    scratch[used] = p;
                    shares[used++] = weight[i];
                }
                if (used == 0) {
                    out.rest();
                    return;
                }
                out.blend(scratch, shares, used);
                for (int i = used - 1; i >= 0; i--) {
                    g.giveBack(scratch[i]);
                    scratch[i] = null;
                }
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        poses.forEach(each);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        each.accept(selector);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        if (selector.type() == CgAnimVariable.Type.BOOL) {
            out.kind("blend_list_by_bool").put("selector", selector.name()).node("when_true", poses.get(0)).node("when_false", poses.get(1));
        } else {
            out.kind("blend_list_by_int").put("selector", selector.name()).nodes("poses", poses);
        }
        boolean timed = false;
        for (float t : blendTimes) timed |= t != 0f;
        if (timed) {
            List<Double> times = new ArrayList<>(blendTimes.length);
            for (float t : blendTimes) times.add((double) t);
            out.put("blend_times", times);
        }
        if (reset) out.put("reset_child_on_activate", true);
    }

    @Override
    public String toString() {
        return "blendList(" + selector.name() + ")";
    }
}
