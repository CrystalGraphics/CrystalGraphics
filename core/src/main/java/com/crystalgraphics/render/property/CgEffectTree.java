package com.crystalgraphics.render.property;

import java.util.Arrays;

/**
 * A recording's effect nodes: opacity groups, each multiplying into its parent's, so a fade can change at execution
 * with nothing recorded again. Node 0 is opacity 1.
 *
 * <pre>{@code
 * int fade = recording.effects().add(0, 1f);   // the compositor may change it with CgPropertyValues.opacity
 * chunks.begin(spatial, clip, fade);
 * }</pre>
 *
 * <p>A material opts in: {@code CG_QUAD_OPACITY} or {@code CG_CURVE_OPACITY} is the record's group opacity, and one
 * that does not read it draws at full opacity. At most {@link CgSpatialTree#MAX_NODES}; one thread at a time.</p>
 */
public final class CgEffectTree {

    private int count = 1;
    private int[] parents = new int[16];
    private float[] opacities = new float[16];

    public CgEffectTree() {
        opacities[0] = 1f;
    }

    /** A group under {@code parent} drawn at {@code opacity}. */
    public int add(int parent, float opacity) {
        if (parent < 0 || parent >= count) throw new IllegalArgumentException("no effect node " + parent);
        if (count == CgSpatialTree.MAX_NODES) {
            throw new IllegalStateException("a recording has at most " + CgSpatialTree.MAX_NODES + " effect nodes");
        }
        if (count == parents.length) {
            parents = Arrays.copyOf(parents, count * 2);
            opacities = Arrays.copyOf(opacities, count * 2);
        }
        int n = count++;
        parents[n] = parent;
        opacities[n] = opacity;
        return n;
    }

    public int count() {
        return count;
    }

    public int parent(int node) {
        return parents[node];
    }

    public float opacity(int node) {
        return opacities[node];
    }

    /** Makes this a copy of {@code other}. */
    public void copyFrom(CgEffectTree other) {
        if (parents.length < other.count) {
            parents = new int[other.parents.length];
            opacities = new float[other.opacities.length];
        }
        System.arraycopy(other.parents, 0, parents, 0, other.count);
        System.arraycopy(other.opacities, 0, opacities, 0, other.count);
        count = other.count;
    }

    /** Node 0 alone. */
    public void reset() {
        count = 1;
    }
}
