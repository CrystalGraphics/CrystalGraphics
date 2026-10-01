package com.crystalgraphics.render.property;

import java.util.Arrays;

/**
 * Values that replace or adjust a recording's recorded ones at execution: what a compositor writes to move a scroll,
 * a window or a transform, or fade a group, with nothing recorded or batched again.
 *
 * <pre>{@code
 * CgPropertyValues values = new CgPropertyValues();
 * graph.add(recording.seal(), values);
 *
 * // render thread, any later frame:
 * values.translate(scrollContent, 0f, -120f);   // a scroll: moved after its recorded value
 * values.transform(window, 1f, 0f, 0f, 1f, x, y);  // a placement: replaces its recorded value
 * values.opacity(fade, 0.4f);                    // replaces the group's recorded opacity
 * CgExecutor.execute(frame);                     // the same frame, drawn with the new values
 * }</pre>
 *
 * <ul>
 *   <li>Keyed by the recording's node ids: values belong to one recording.</li>
 *   <li>A translation applies after the node's recorded affine, in its parent's space.</li>
 *   <li>Read when a frame executes, on the render thread; written there too.</li>
 * </ul>
 */
public final class CgPropertyValues {

    private static final byte NONE = 0, TRANSLATE = 1, TRANSFORM = 2;

    private byte[] spatialModes = new byte[16];
    private float[] spatial = new float[6 * 16];
    private boolean[] hasOpacity = new boolean[16];
    private float[] opacities = new float[16];
    private int revision;

    /** Moves {@code node} by {@code (dx, dy)} in its parent's space, after its recorded affine. */
    public CgPropertyValues translate(int node, float dx, float dy) {
        return set(node, TRANSLATE, 1f, 0f, 0f, 1f, dx, dy);
    }

    /** Replaces {@code node}'s recorded affine with {@code a b c d tx ty}. */
    public CgPropertyValues transform(int node, float a, float b, float c, float d, float tx, float ty) {
        return set(node, TRANSFORM, a, b, c, d, tx, ty);
    }

    /** Replaces effect node {@code node}'s recorded opacity. */
    public CgPropertyValues opacity(int node, float opacity) {
        ensure(node);
        hasOpacity[node] = true;
        opacities[node] = opacity;
        revision++;
        return this;
    }

    /** Drops every value: the recorded ones apply again. */
    public CgPropertyValues clear() {
        Arrays.fill(spatialModes, NONE);
        Arrays.fill(hasOpacity, false);
        revision++;
        return this;
    }

    /** Bumped by every write: what lets a palette skip recomputing values that did not change. */
    public int revision() {
        return revision;
    }

    /**
     * {@code node}'s affine in its parent's space: {@code recorded} (a b c d tx ty at {@code at}) as these values
     * change it, written to {@code out} at {@code o}.
     */
    void apply(int node, float[] recorded, int at, float[] out, int o) {
        byte mode = node < spatialModes.length ? spatialModes[node] : NONE;
        if (mode == TRANSFORM) {
            System.arraycopy(spatial, node * 6, out, o, 6);
            return;
        }
        System.arraycopy(recorded, at, out, o, 6);
        if (mode == TRANSLATE) {
            out[o + 4] += spatial[node * 6 + 4];
            out[o + 5] += spatial[node * 6 + 5];
        }
    }

    /** Effect node {@code node}'s opacity: {@code recorded} unless replaced. */
    float opacityOf(int node, float recorded) {
        return node < hasOpacity.length && hasOpacity[node] ? opacities[node] : recorded;
    }

    private CgPropertyValues set(int node, byte mode, float a, float b, float c, float d, float tx, float ty) {
        ensure(node);
        spatialModes[node] = mode;
        int o = node * 6;
        spatial[o] = a;
        spatial[o + 1] = b;
        spatial[o + 2] = c;
        spatial[o + 3] = d;
        spatial[o + 4] = tx;
        spatial[o + 5] = ty;
        revision++;
        return this;
    }

    private void ensure(int node) {
        if (node <= 0 || node >= CgSpatialTree.MAX_NODES) {
            throw new IllegalArgumentException("node " + node + ": the root never moves, and a recording has at most "
                    + CgSpatialTree.MAX_NODES);
        }
        if (node < spatialModes.length) return;
        int n = Math.max(node + 1, spatialModes.length * 2);
        spatialModes = Arrays.copyOf(spatialModes, n);
        spatial = Arrays.copyOf(spatial, n * 6);
        hasOpacity = Arrays.copyOf(hasOpacity, n);
        opacities = Arrays.copyOf(opacities, n);
    }
}
