package com.crystalgraphics.render.property;

import org.joml.Matrix4fc;

import java.util.Arrays;

/**
 * A recording's spatial nodes: each a 2D affine from its own space into its parent's, so a draw recorded in a node's
 * space moves with it when the node's value changes at execution — a scroll, a window move — with nothing recorded
 * again. Node 0 is the recording's root: the space its raster passes are expressed in.
 *
 * <pre>{@code
 * CgSpatialTree tree = recording.spatial();
 * int window = tree.add(0, placement, true);                  // movable: the compositor may move it
 * int scroller = tree.add(window, 0f, 32f, true);             // a translation under it
 * int rotated = tree.add(scroller, rotation, false);          // a static transform: batches with its domain
 * chunks.begin(rotated, clip, effect);                        // draws in rotated's own space
 * }</pre>
 *
 * <ul>
 *   <li>A node is <b>movable</b> when its value may change after recording. Draws are reordered only among
 *       draws whose relative position cannot change, so a movable node starts a batching domain
 *       ({@link #domain}); a static one shares its parent's.</li>
 *   <li>An affine is {@code a b c d tx ty}: {@code x' = a x + c y + tx}, {@code y' = b x + d y + ty}.</li>
 *   <li>At most {@link #MAX_NODES}; one thread at a time, like the recording.</li>
 * </ul>
 */
public final class CgSpatialTree {

    /** Nodes a recording may have: a record packs its spatial and effect nodes into one float. */
    public static final int MAX_NODES = 4096;

    private int count = 1;
    private int[] parents = new int[16];
    private float[] locals = new float[6 * 16];
    private boolean[] movable = new boolean[16];

    public CgSpatialTree() {
        locals[0] = 1f;
        locals[3] = 1f;
    }

    /** A node under {@code parent} with the 2D affine part of {@code local}. */
    public int add(int parent, Matrix4fc local, boolean movable) {
        return add(parent, local.m00(), local.m01(), local.m10(), local.m11(), local.m30(), local.m31(), movable);
    }

    /** A node under {@code parent}, translated by {@code (tx, ty)}. */
    public int add(int parent, float tx, float ty, boolean movable) {
        return add(parent, 1f, 0f, 0f, 1f, tx, ty, movable);
    }

    /** A node under {@code parent} with the affine {@code a b c d tx ty}. */
    public int add(int parent, float a, float b, float c, float d, float tx, float ty, boolean movable) {
        if (parent < 0 || parent >= count) throw new IllegalArgumentException("no spatial node " + parent);
        if (count == MAX_NODES) throw new IllegalStateException("a recording has at most " + MAX_NODES + " spatial nodes");
        if (count == parents.length) grow();
        int n = count++;
        parents[n] = parent;
        this.movable[n] = movable;
        int o = n * 6;
        locals[o] = a;
        locals[o + 1] = b;
        locals[o + 2] = c;
        locals[o + 3] = d;
        locals[o + 4] = tx;
        locals[o + 5] = ty;
        return n;
    }

    public int count() {
        return count;
    }

    /** The node {@code node} is in; 0's is 0. */
    public int parent(int node) {
        return parents[node];
    }

    public boolean movable(int node) {
        return movable[node];
    }

    /** Component {@code i} of the node's local affine, {@code a b c d tx ty}. */
    public float local(int node, int i) {
        return locals[node * 6 + i];
    }

    /** The nearest movable node at or above {@code node}, 0 for none: the draws that may batch together. */
    public int domain(int node) {
        while (node != 0 && !movable[node]) node = parents[node];
        return node;
    }

    /**
     * {@code x0 y0 x1 y1}, in {@code node}'s space, as the box around them in its {@link #domain}'s space through the
     * recorded affines between: what batching compares draws of one domain by. Written to {@code out[0..3]}.
     */
    public void boundsInDomain(int node, float x0, float y0, float x1, float y1, float[] out) {
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y1;
        while (node != 0 && !movable[node]) {
            int o = node * 6;
            float a = locals[o], b = locals[o + 1], c = locals[o + 2], d = locals[o + 3], tx = locals[o + 4], ty = locals[o + 5];
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                float x = (corner & 1) == 0 ? out[0] : out[2];
                float y = (corner & 2) == 0 ? out[1] : out[3];
                float px = a * x + c * y + tx, py = b * x + d * y + ty;
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
            out[0] = minX;
            out[1] = minY;
            out[2] = maxX;
            out[3] = maxY;
            node = parents[node];
        }
    }

    /** Makes this a copy of {@code other}: what a built frame keeps, so its recording can be reset at once. */
    public void copyFrom(CgSpatialTree other) {
        if (parents.length < other.count) {
            parents = new int[other.parents.length];
            locals = new float[other.locals.length];
            movable = new boolean[other.movable.length];
        }
        System.arraycopy(other.parents, 0, parents, 0, other.count);
        System.arraycopy(other.locals, 0, locals, 0, other.count * 6);
        System.arraycopy(other.movable, 0, movable, 0, other.count);
        count = other.count;
    }

    /** Node 0 alone. */
    public void reset() {
        count = 1;
    }

    private void grow() {
        int n = parents.length * 2;
        parents = Arrays.copyOf(parents, n);
        locals = Arrays.copyOf(locals, n * 6);
        movable = Arrays.copyOf(movable, n);
    }
}
