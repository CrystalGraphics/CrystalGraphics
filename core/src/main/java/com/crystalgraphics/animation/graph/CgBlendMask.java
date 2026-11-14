package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * Which bones a layer reaches, and how much: Unreal's branch filters ({@code FBranchFilter}) and blend mask entries,
 * as {@code FAnimationRuntime::CreateMaskWeights} turns them into a weight per bone (AnimationRuntime.cpp:2421-2504).
 *
 * <pre>{@code
 * CgBlendMask face = CgBlendMask.branch("facialbase", 1);                       // the face, all at once below its root
 * CgBlendMask arms = CgBlendMask.branch("upperarm_l", 0).andBranch("upperarm_r", 0);
 * CgBlendMask spine = CgBlendMask.branch("spine_01", 3);                        // ramping in over three levels
 * CgBlendMask lean = CgBlendMask.bone("spine_02", 0.5f).andBone("head", 0.25f); // a weight per bone
 * }</pre>
 *
 * <ul>
 *   <li>A branch weighs a bone {@code d} levels below its root {@code (d + 1) / depth}, at most 1, depth 0 taking the
 *       whole branch at once; branches add, at most 1.</li>
 *   <li>A bone weight replaces what the branches gave that bone.</li>
 *   <li>A bone the skeleton lacks fails when the graph binds, naming it.</li>
 * </ul>
 */
public final class CgBlendMask {

    private record Entry(String bone, int depth, float weight, boolean branch) {
    }

    private final List<Entry> entries;

    private CgBlendMask(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** {@code bone}'s branch, ramping in over {@code depth} levels (0: the whole branch at once). */
    public static CgBlendMask branch(String bone, int depth) {
        return new CgBlendMask(List.of()).andBranch(bone, depth);
    }

    /** {@code bone} alone at {@code weight}, 0 to 1. */
    public static CgBlendMask bone(String bone, float weight) {
        return new CgBlendMask(List.of()).andBone(bone, weight);
    }

    public CgBlendMask andBranch(String bone, int depth) {
        if (depth < 0) throw new IllegalArgumentException("branch " + bone + " depth " + depth);
        List<Entry> e = new ArrayList<>(entries);
        e.add(new Entry(Objects.requireNonNull(bone, "bone"), depth, 0f, true));
        return new CgBlendMask(e);
    }

    public CgBlendMask andBone(String bone, float weight) {
        List<Entry> e = new ArrayList<>(entries);
        e.add(new Entry(Objects.requireNonNull(bone, "bone"), 0, Math.max(0f, Math.min(1f, weight)), false));
        return new CgBlendMask(e);
    }

    /**
     * A weight per bone of {@code skeleton}.
     *
     * @throws IllegalArgumentException where it names a bone the skeleton lacks
     */
    public float[] weights(CgSkeleton skeleton) {
        float[] out = new float[skeleton.boneCount()];
        for (Entry e : entries) {
            int root = skeleton.indexOfIgnoringCase(e.bone);
            if (root < 0) throw new IllegalArgumentException("a blend mask names " + e.bone + ", which " + skeleton + " lacks");
            if (!e.branch) continue;
            float perDepth = e.depth != 0 ? 1f / e.depth : 1f;
            for (int b = root; b < out.length; b++) {
                int depth = 0, at = b;
                while (at >= 0 && at != root) {
                    at = skeleton.parent(at);
                    depth++;
                }
                if (at == root) out[b] = Math.min(1f, Math.max(0f, out[b] + perDepth * (depth + 1)));
            }
        }
        for (Entry e : entries) if (!e.branch) out[skeleton.indexOfIgnoringCase(e.bone)] = e.weight;
        return out;
    }

    /** Its data form: {@code branches} then {@code bones}, each in the order made. */
    Map<String, Object> toTree() {
        List<Object> branches = new ArrayList<>(), bones = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bone", e.bone);
            if (e.branch) {
                if (e.depth != 0) m.put("depth", e.depth);
                branches.add(m);
            } else {
                m.put("weight", (double) e.weight);
                bones.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!branches.isEmpty()) out.put("branches", branches);
        if (!bones.isEmpty()) out.put("bones", bones);
        return out;
    }
}
