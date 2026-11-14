package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * Where montages and sequences play over the graph: Unreal's {@code FAnimNode_Slot}. Its {@link CgAnimSlot}'s entries
 * blend over its source by their weights; with none, the source passes through.
 *
 * <pre>{@code
 * CgSlotNode body = CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, locomotion);   // a move's animation section plays here
 * CgSlotNode face = CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body"));
 * }</pre>
 *
 * <ul>
 *   <li>The source is updated only while it shows: at its share, at least Unreal's smallest relevant weight
 *       (AnimNode_Slot.cpp, {@code Update_AnyThread}).</li>
 *   <li>An entry's clip is sampled from the rest pose, as a montage's track is: a bone it does not key takes the
 *       rest, not the source. Limit a slot to the bones its clips key with a layered blend.</li>
 *   <li>A slot name appears once in a graph.</li>
 * </ul>
 */
public final class CgSlotNode extends CgAnimNode {

    private final String name;
    private final CgAnimNode source;

    private CgSlotNode(String name, CgAnimNode source) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Slot {@code name} over {@code source}. */
    public static CgSlotNode of(String name, CgAnimNode source) {
        return new CgSlotNode(name, source);
    }

    public String name() {
        return name;
    }

    public CgAnimNode source() {
        return source;
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        return new SlotRuntime(g.slot(name), g.runtime(source));
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(source);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("slot");
        if (!name.equals(CgAnimGraph.DEFAULT_SLOT)) out.put("slot_name", name);
        out.node("source", source);
    }

    @Override
    public String toString() {
        return "slot(" + name + ")";
    }

    private static final class SlotRuntime extends Runtime {

        /** Unreal's {@code FAnimWeight::GetSmallestRelevantWeight}. */
        private static final float SMALLEST = 2f * CgAnimPose.ZERO_WEIGHT;

        private final CgAnimSlot slot;
        private final Runtime source;
        private CgAnimPose[] poses = new CgAnimPose[0];
        private float[] shares = new float[0];
        /** This frame's: the source's share; each entry's scale, the stop's and the normalising past 1. */
        private float sourceWeight, scale;

        SlotRuntime(CgAnimSlot slot, Runtime source) {
            this.slot = slot;
            this.source = source;
        }

        @Override
        public void initialize(CgAnimGraphInstance g) {
            source.initialize(g);
        }

        @Override
        public void update(CgAnimGraphInstance g, float weight) {
            float total = slot.total();
            scale = (total > 1f ? 1f / total : 1f) * slot.kept();
            sourceWeight = Math.min(1f, Math.max(0f, 1f - total));
            if (sourceWeight > CgAnimPose.ZERO_WEIGHT) source.update(g, weight * Math.max(SMALLEST, sourceWeight));
            for (int i = 0; i < slot.count; i++) {
                float w = Math.max(0f, slot.weights[i]) * scale;
                if (w > CgAnimPose.ZERO_WEIGHT) g.sampled(slot.clips[i], slot.positions[i], weight * w);
            }
        }

        @Override
        public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (slot.count == 0) {
                source.evaluate(g, out);
                return;
            }
            int n = slot.count + 1;
            if (poses.length < n) {
                poses = new CgAnimPose[n];
                shares = new float[n];
            }
            int used = 0;
            for (int i = 0; i < slot.count; i++) {
                float w = Math.max(0f, slot.weights[i]) * scale;
                if (w <= CgAnimPose.ZERO_WEIGHT) continue;
                CgAnimPose p = g.borrow();
                slot.clips[i].sample(p.rest(), slot.positions[i]);
                if (slot.mirrors[i] != null) p.mirror(slot.mirrors[i]);
                poses[used] = p;
                shares[used++] = w;
            }
            if (sourceWeight > CgAnimPose.ZERO_WEIGHT) {
                CgAnimPose p = g.borrow();
                source.evaluate(g, p);
                poses[used] = p;
                shares[used++] = sourceWeight;
            }
            if (used == 0) {
                out.rest();
            } else {
                out.blend(poses, shares, used);
            }
            for (int i = used - 1; i >= 0; i--) {
                g.giveBack(poses[i]);
                poses[i] = null;
            }
        }
    }
}
