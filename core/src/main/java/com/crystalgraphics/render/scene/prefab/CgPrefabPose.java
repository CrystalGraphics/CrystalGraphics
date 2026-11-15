package com.crystalgraphics.render.scene.prefab;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;

/**
 * A {@link CgPrefab}'s nodes as they stand: each node's transform and opacity, evaluated parents first into one matrix a
 * node. What a VFX effect playing a prefab keys and draws from, Sequencer's transform and opacity tracks on a spawned
 * actor's components.
 *
 * <pre>{@code
 * CgPrefabPose pose = new CgPrefabPose(CORE);
 * int ball01 = CORE.node("ball01");
 * pose.opacity(ball01, 1f - age).location(ball01, 0f, lift, 0f);    // ball01 alone: its children keep their own opacity
 * pose.place(effectTurn).evaluate();
 * Matrix4fc m = pose.world(ball01);                                  // from the place's origin
 * }</pre>
 *
 * <ul>
 *   <li>Transforms are in the parent's space, a root's in {@link #place}. Matrices multiply as glTF's do: a rotated
 *       child under a non-uniformly scaled parent shears.</li>
 *   <li>Nothing here allocates once made.</li>
 * </ul>
 */
public final class CgPrefabPose {

    private final CgPrefab prefab;
    private final float[] local;
    private final float[] opacity;
    private final Matrix4f[] world;
    private final Matrix4f place = new Matrix4f(), node = new Matrix4f();

    public CgPrefabPose(CgPrefab prefab) {
        this.prefab = prefab;
        int n = prefab.nodes();
        this.local = Arrays.copyOf(prefab.rest(), n * CgPrefab.REST);
        this.opacity = Arrays.copyOf(prefab.restOpacity(), n);
        this.world = new Matrix4f[n];
        for (int i = 0; i < n; i++) world[i] = new Matrix4f();
    }

    public CgPrefab prefab() {
        return prefab;
    }

    /** Where its roots stand. */
    public CgPrefabPose place(Matrix4fc transform) {
        place.set(transform);
        return this;
    }

    public CgPrefabPose location(int node, float x, float y, float z) {
        int o = node * CgPrefab.REST;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        return this;
    }

    /** {@code node}'s rotation in its parent's space, a unit quaternion. */
    public CgPrefabPose rotation(int node, float x, float y, float z, float w) {
        int o = node * CgPrefab.REST + 3;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        local[o + 3] = w;
        return this;
    }

    public CgPrefabPose scale(int node, float x, float y, float z) {
        int o = node * CgPrefab.REST + 7;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        return this;
    }

    /**
     * {@code node}'s own opacity, 0 to 1, which its parts draw at: never its children's, as an Unreal component's
     * opacity is its own material's. A game whose opacity passes down has it baked into each node at import.
     */
    public CgPrefabPose opacity(int node, float a) {
        opacity[node] = a;
        return this;
    }

    /** {@code node}'s own opacity as it stands. */
    public float opacity(int node) {
        return opacity[node];
    }

    /**
     * {@code node}'s transform as it stands into {@code out} ({@link CgPrefab#REST} floats): what a track keying it starts
     * from. Answers {@code out}.
     */
    public float[] local(int node, float[] out) {
        System.arraycopy(local, node * CgPrefab.REST, out, 0, CgPrefab.REST);
        return out;
    }

    /** Every node back at its rest. */
    public void reset() {
        System.arraycopy(prefab.rest(), 0, local, 0, local.length);
        System.arraycopy(prefab.restOpacity(), 0, opacity, 0, opacity.length);
    }

    /** Each node's matrix from its parent's, parents first. */
    public CgPrefabPose evaluate() {
        for (int i = 0, o = 0; i < world.length; i++, o += CgPrefab.REST) {
            node.translationRotateScale(local[o], local[o + 1], local[o + 2], local[o + 3], local[o + 4], local[o + 5],
                    local[o + 6], local[o + 7], local[o + 8], local[o + 9]);
            int p = prefab.parent(i);
            (p < 0 ? place : world[p]).mul(node, world[i]);
        }
        return this;
    }

    /** {@code node}'s matrix from the place's origin, as of the last {@link #evaluate}. */
    public Matrix4fc world(int node) {
        return world[node];
    }
}
