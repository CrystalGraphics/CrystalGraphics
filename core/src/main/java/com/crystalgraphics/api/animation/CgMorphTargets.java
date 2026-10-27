package com.crystalgraphics.api.animation;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A mesh's morph targets (blend shapes): named sets of per-vertex position and normal offsets, mixed in by weight
 * before skinning. Unreal's {@code UMorphTarget}s, kept per vertex: each vertex lists only the targets that move it, so
 * a shader loops over its own entries alone.
 *
 * <pre>{@code
 * CgMorphTargets faces = kurama.morphTargets();
 * int smile = faces.indexOf("smile");                       // -1 if it has none by that name
 * for (int e = faces.firstEntry(v); e < faces.firstEntry(v + 1); e++) {
 *     float w = weights[faces.entryTarget(e)];
 *     position.fma(w, faces.positionDelta(e, delta));
 * }
 * }</pre>
 *
 * <p>Built in Java:</p>
 *
 * <pre>{@code
 * CgMorphTargets faces = CgMorphTargets.builder(vertexCount)
 *         .target("smile", 0f)
 *         .delta(0, 120, 0, 0.01f, 0, 0, 0, 0)                  // target 0 moves vertex 120 up a hundredth
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Vertices are the mesh's, counted across its submeshes from 0.</li>
 *   <li>A delta of zero in both position and normal is not kept.</li>
 *   <li>Immutable; any thread. {@link #NONE} for a mesh without targets.</li>
 * </ul>
 */
public final class CgMorphTargets {

    /** No targets, for any number of vertices. */
    public static final CgMorphTargets NONE = new CgMorphTargets(new String[0], new float[0], new int[]{0}, new int[0],
            new float[0]);

    private final String[] names;
    private final float[] defaultWeights;
    /** Vertex {@code v}'s entries are {@code [starts[v], starts[v + 1])}; past the last vertex with any, none. */
    final int[] starts;
    final int[] targets;
    /** Six floats an entry: the position delta, then the normal delta. */
    final float[] deltas;
    private final Map<String, Integer> index = new HashMap<>();

    CgMorphTargets(String[] names, float[] defaultWeights, int[] starts, int[] targets, float[] deltas) {
        this.names = names;
        this.defaultWeights = defaultWeights;
        this.starts = starts;
        this.targets = targets;
        this.deltas = deltas;
        for (int i = 0; i < names.length; i++) index.put(names[i], i);
    }

    /** Targets over a mesh of {@code vertexCount} vertices. */
    public static Builder builder(int vertexCount) {
        return new Builder(vertexCount);
    }

    public int targetCount() {
        return names.length;
    }

    public String name(int target) {
        return names[target];
    }

    /** The target's weight when nothing keys it: glTF's mesh weights, Unreal's default of 0 otherwise. */
    public float defaultWeight(int target) {
        return defaultWeights[target];
    }

    /** The target named {@code name}, or -1. */
    public int indexOf(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    /** Vertices the entries cover: the mesh's count, or 1 for {@link #NONE}. */
    public int vertexCount() {
        return starts.length - 1;
    }

    /** Vertex {@code vertex}'s first entry; its last is {@code firstEntry(vertex + 1) - 1}. */
    public int firstEntry(int vertex) {
        return vertex >= starts.length ? targets.length : starts[vertex];
    }

    public int entryCount() {
        return targets.length;
    }

    /** The target entry {@code entry} belongs to. */
    public int entryTarget(int entry) {
        return targets[entry];
    }

    public Vector3f positionDelta(int entry, Vector3f out) {
        return out.set(deltas[entry * 6], deltas[entry * 6 + 1], deltas[entry * 6 + 2]);
    }

    public Vector3f normalDelta(int entry, Vector3f out) {
        return out.set(deltas[entry * 6 + 3], deltas[entry * 6 + 4], deltas[entry * 6 + 5]);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CgMorphTargets m && Arrays.equals(names, m.names)
                && Arrays.equals(defaultWeights, m.defaultWeights) && Arrays.equals(starts, m.starts)
                && Arrays.equals(targets, m.targets) && Arrays.equals(deltas, m.deltas);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(names) + Arrays.hashCode(starts);
    }

    @Override
    public String toString() {
        return "CgMorphTargets{" + names.length + " targets, " + targets.length + " entries}";
    }

    /** Builds {@link CgMorphTargets}: the targets, then their deltas in any order. */
    public static final class Builder {

        private final int vertexCount;
        private final List<String> names = new ArrayList<>();
        private final List<Float> weights = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();
        /** Per entry: vertex, target, then six floats; sorted by vertex, then target, at build. */
        private int count;
        private int[] vertexTarget = new int[64];
        private float[] values = new float[192];

        private Builder(int vertexCount) {
            if (vertexCount < 0) throw new IllegalArgumentException(vertexCount + " vertices");
            this.vertexCount = vertexCount;
        }

        /** A target and its weight while unkeyed; the index it gets is the count before it. */
        public Builder target(String name, float defaultWeight) {
            if (index.putIfAbsent(name, names.size()) != null) throw new IllegalArgumentException("morph target " + name + " twice");
            names.add(name);
            weights.add(defaultWeight);
            return this;
        }

        /** How target {@code target} moves vertex {@code vertex}: a position offset and a normal offset. */
        public Builder delta(int target, int vertex, float dx, float dy, float dz, float nx, float ny, float nz) {
            if (target < 0 || target >= names.size()) throw new IllegalArgumentException("morph target " + target + " is not declared");
            if (vertex < 0 || vertex >= vertexCount) throw new IllegalArgumentException("vertex " + vertex + " of " + vertexCount);
            if (dx == 0 && dy == 0 && dz == 0 && nx == 0 && ny == 0 && nz == 0) return this;
            if (count * 2 == vertexTarget.length) {
                vertexTarget = Arrays.copyOf(vertexTarget, vertexTarget.length * 2);
                values = Arrays.copyOf(values, values.length * 2);
            }
            vertexTarget[count * 2] = vertex;
            vertexTarget[count * 2 + 1] = target;
            float[] d = {dx, dy, dz, nx, ny, nz};
            System.arraycopy(d, 0, values, count * 6, 6);
            count++;
            return this;
        }

        public CgMorphTargets build() {
            if (names.isEmpty()) return NONE;
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> vertexTarget[a * 2] != vertexTarget[b * 2] ? vertexTarget[a * 2] - vertexTarget[b * 2]
                    : vertexTarget[a * 2 + 1] - vertexTarget[b * 2 + 1]);
            int[] starts = new int[vertexCount + 1], targets = new int[count];
            float[] deltas = new float[count * 6];
            for (int e = 0; e < count; e++) {
                int i = order[e];
                if (e > 0 && vertexTarget[i * 2] == vertexTarget[order[e - 1] * 2]
                        && vertexTarget[i * 2 + 1] == vertexTarget[order[e - 1] * 2 + 1]) {
                    throw new IllegalArgumentException("morph target " + names.get(vertexTarget[i * 2 + 1]) + " moves vertex "
                            + vertexTarget[i * 2] + " twice");
                }
                starts[vertexTarget[i * 2] + 1]++;
                targets[e] = vertexTarget[i * 2 + 1];
                System.arraycopy(values, i * 6, deltas, e * 6, 6);
            }
            for (int v = 0; v < vertexCount; v++) starts[v + 1] += starts[v];
            float[] w = new float[weights.size()];
            for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
            return new CgMorphTargets(names.toArray(new String[0]), w, starts, targets, deltas);
        }
    }
}
