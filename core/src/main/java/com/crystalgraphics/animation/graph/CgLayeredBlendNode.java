package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * A base pose with layers over parts of the skeleton: Unreal's {@code FAnimNode_LayeredBoneBlend}. Each layer replaces
 * the base on the bones its mask reaches, by the mask's weight times the layer's (AnimationRuntime.cpp:1821-1840,
 * {@code UpdateDesiredBoneWeight}); layers apply in order, a later one over an earlier.
 *
 * <pre>{@code
 * CgLayeredBlendNode.of(locomotion)
 *         .layer(CgSlotNode.of("UpperBody", CgCachedPoseNode.use("body")), CgBlendMask.branch("spine_01", 2))
 *         .layer(CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body")), CgBlendMask.branch("facialbase", 1));
 * CgLayeredBlendNode.of(run).layer(aim, CgBlendMask.branch("spine_02", 0), AIM_WEIGHT);    // a layer's weight a variable
 * }</pre>
 *
 * <ul>
 *   <li>A layer's clips set the material curves they key, over the base's ({@code CurveBlendOption MaxWeight});
 *       {@link #curvesFromBase()} keeps the base's instead ({@code UseBaseOnly}: Kakarot's lip layer).</li>
 *   <li>Blending is in each bone's parent space, Unreal's default ({@code bMeshSpaceRotationBlend} off).</li>
 *   <li>Immutable: each call answers a new node.</li>
 * </ul>
 */
public final class CgLayeredBlendNode extends CgAnimNode {

    private record Layer(CgAnimNode pose, CgBlendMask mask, float weight, @Nullable CgAnimVariable<Float> weightVariable,
                         boolean curves) {
    }

    private final CgAnimNode base;
    private final List<Layer> layers;

    private CgLayeredBlendNode(CgAnimNode base, List<Layer> layers) {
        this.base = Objects.requireNonNull(base, "base");
        this.layers = List.copyOf(layers);
    }

    /** {@code base}, with no layers yet. */
    public static CgLayeredBlendNode of(CgAnimNode base) {
        return new CgLayeredBlendNode(base, List.of());
    }

    /** {@code pose} over the bones {@code mask} reaches, at full weight. */
    public CgLayeredBlendNode layer(CgAnimNode pose, CgBlendMask mask) {
        return add(new Layer(Objects.requireNonNull(pose, "pose"), Objects.requireNonNull(mask, "mask"), 1f, null, true));
    }

    /** {@code pose} over {@code mask} at a constant {@code weight}. */
    public CgLayeredBlendNode layer(CgAnimNode pose, CgBlendMask mask, float weight) {
        return add(new Layer(Objects.requireNonNull(pose, "pose"), Objects.requireNonNull(mask, "mask"), weight, null, true));
    }

    /** {@code pose} over {@code mask} at the variable {@code weight}. */
    public CgLayeredBlendNode layer(CgAnimNode pose, CgBlendMask mask, CgAnimVariable<Float> weight) {
        return add(new Layer(Objects.requireNonNull(pose, "pose"), Objects.requireNonNull(mask, "mask"), 0f,
                Objects.requireNonNull(weight, "weight"), true));
    }

    /** The last layer's bones only: its curves are the base's ({@code UseBaseOnly}). */
    public CgLayeredBlendNode curvesFromBase() {
        if (layers.isEmpty()) throw new IllegalStateException("no layer to keep the base's curves for");
        List<Layer> l = new ArrayList<>(layers);
        Layer last = l.remove(l.size() - 1);
        l.add(new Layer(last.pose, last.mask, last.weight, last.weightVariable, false));
        return new CgLayeredBlendNode(base, l);
    }

    private CgLayeredBlendNode add(Layer layer) {
        List<Layer> l = new ArrayList<>(layers);
        l.add(layer);
        return new CgLayeredBlendNode(base, l);
    }

    @Override
    protected Runtime instantiate(CgAnimGraphInstance g) {
        Runtime baseRuntime = g.runtime(base);
        int n = layers.size();
        Runtime[] runtimes = new Runtime[n];
        float[][] masks = new float[n][];
        int[] curveLayers = new int[n];
        for (int i = 0; i < n; i++) {
            runtimes[i] = g.runtime(layers.get(i).pose);
            masks[i] = layers.get(i).mask.weights(g.skeleton());
            curveLayers[i] = layers.get(i).curves ? g.newCurveLayer() : -1;
        }
        float[] effective = new float[g.skeleton().boneCount()];
        return new Runtime() {
            private final float[] now = new float[n];

            @Override
            public void initialize(CgAnimGraphInstance g) {
                baseRuntime.initialize(g);
                for (Runtime r : runtimes) r.initialize(g);
            }

            @Override
            public void update(CgAnimGraphInstance g, float weight) {
                baseRuntime.update(g, weight);
                for (int i = 0; i < n; i++) {
                    Layer l = layers.get(i);
                    now[i] = Math.max(0f, Math.min(1f, l.weightVariable != null ? g.get(l.weightVariable) : l.weight));
                    if (now[i] <= CgAnimPose.ZERO_WEIGHT) continue;
                    int was = g.curveLayer(curveLayers[i]);
                    runtimes[i].update(g, weight * now[i]);
                    g.curveLayer(was);
                }
            }

            @Override
            public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                baseRuntime.evaluate(g, out);
                for (int i = 0; i < n; i++) {
                    if (now[i] <= CgAnimPose.ZERO_WEIGHT) continue;
                    CgAnimPose p = g.borrow();
                    runtimes[i].evaluate(g, p);
                    float[] mask = masks[i];
                    if (now[i] < 1f) {
                        for (int b = 0; b < mask.length; b++) effective[b] = mask[b] * now[i];
                        mask = effective;
                    }
                    out.blendBones(p, mask);
                    g.giveBack(p);
                }
            }
        };
    }

    @Override
    protected void children(Consumer<CgAnimNode> each) {
        each.accept(base);
        for (Layer l : layers) each.accept(l.pose);
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        for (Layer l : layers) if (l.weightVariable != null) each.accept(l.weightVariable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        List<Map<String, Object>> written = new ArrayList<>(layers.size());
        for (Layer l : layers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pose", out.name(l.pose));
            m.put("mask", l.mask.toTree());
            if (l.weightVariable != null) m.put("weight", l.weightVariable.name());
            else if (l.weight != 1f) m.put("weight", (double) l.weight);
            if (!l.curves) m.put("curves", "use_base_only");
            written.add(m);
        }
        out.kind("layered_blend").node("base", base).put("layers", written);
    }

    @Override
    public String toString() {
        return "layered(" + base + ", " + layers.size() + " layers)";
    }
}
