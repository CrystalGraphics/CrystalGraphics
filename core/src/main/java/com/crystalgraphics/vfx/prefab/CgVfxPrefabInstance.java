package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;

/**
 * One playing copy of a {@link CgVfxPrefab}: each node's transform and opacity as they stand, evaluated parents first
 * into one matrix a node, and each node with parts drawn through {@link CgVfxFrame#mesh} at its presence. Made by an
 * effect, whose look holds the prefab's {@link CgVfxPrefab#kit()}.
 *
 * <pre>{@code
 * private final CgVfxPrefabInstance core = new CgVfxPrefabInstance(CORE);
 * private final int ball01 = CORE.node("ball01");
 *
 * @Override protected void tick(float dt) {
 *     core.opacity(ball01, 1f - age);                  // fades ball01's parts; its children keep their own
 * }
 *
 * @Override protected void submit(CgVfxFrame frame) {
 *     core.submit(this, frame);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Transforms are in the parent's space, a root's in the instance's {@link #place}, which is from the effect's
 *       origin. Matrices multiply as glTF's do: a rotated child under a non-uniformly scaled parent shears.</li>
 *   <li>Render thread, or the effect's tick: nothing here allocates once made.</li>
 * </ul>
 */
public final class CgVfxPrefabInstance {

    private final CgVfxPrefab prefab;
    private final float[] local;
    private final float[] opacity;
    private final float[] presence;
    private final Matrix4f[] world;
    private final Matrix4f place = new Matrix4f(), node = new Matrix4f(), turn = new Matrix4f();

    public CgVfxPrefabInstance(CgVfxPrefab prefab) {
        this.prefab = prefab;
        int n = prefab.nodes();
        this.local = Arrays.copyOf(prefab.rest(), n * CgVfxPrefab.REST);
        this.opacity = Arrays.copyOf(prefab.restOpacity(), n);
        this.presence = new float[n];
        this.world = new Matrix4f[n];
        for (int i = 0; i < n; i++) world[i] = new Matrix4f();
    }

    public CgVfxPrefab prefab() {
        return prefab;
    }

    /** Where its roots stand, from the effect's origin. */
    public CgVfxPrefabInstance place(Matrix4fc transform) {
        place.set(transform);
        return this;
    }

    public CgVfxPrefabInstance location(int node, float x, float y, float z) {
        int o = node * CgVfxPrefab.REST;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        return this;
    }

    /** {@code node}'s rotation in its parent's space, a unit quaternion. */
    public CgVfxPrefabInstance rotation(int node, float x, float y, float z, float w) {
        int o = node * CgVfxPrefab.REST + 3;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        local[o + 3] = w;
        return this;
    }

    public CgVfxPrefabInstance scale(int node, float x, float y, float z) {
        int o = node * CgVfxPrefab.REST + 7;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        return this;
    }

    /**
     * {@code node}'s own opacity, 0 to 1, which its parts draw at: never its children's, as an Unreal component's
     * opacity is its own material's (aaa-design C5). A game whose opacity passes down bakes it at import (blueprint 06
     * §3.7, G0.12).
     */
    public CgVfxPrefabInstance opacity(int node, float a) {
        opacity[node] = a;
        return this;
    }

    /** {@code node}'s own opacity as it stands. */
    public float opacity(int node) {
        return opacity[node];
    }

    /**
     * {@code node}'s transform as it stands into {@code out} (10 floats, as {@link CgVfxPrefab#rest}): what a track
     * keying it starts from. Answers {@code out}.
     */
    public float[] local(int node, float[] out) {
        System.arraycopy(local, node * CgVfxPrefab.REST, out, 0, CgVfxPrefab.REST);
        return out;
    }

    /** Every node back at its rest. */
    public void reset() {
        System.arraycopy(prefab.rest(), 0, local, 0, local.length);
        System.arraycopy(prefab.restOpacity(), 0, opacity, 0, opacity.length);
    }

    /** Each node's matrix from its parent's, parents first, and its presence: its own opacity. */
    public void evaluate() {
        for (int i = 0, o = 0; i < world.length; i++, o += CgVfxPrefab.REST) {
            node.translationRotateScale(local[o], local[o + 1], local[o + 2], local[o + 3], local[o + 4], local[o + 5],
                    local[o + 6], local[o + 7], local[o + 8], local[o + 9]);
            int p = prefab.parent(i);
            (p < 0 ? place : world[p]).mul(node, world[i]);
            presence[i] = opacity[i];
        }
    }

    /** {@code node}'s matrix from the effect's origin, as of the last {@link #evaluate}. */
    public Matrix4fc world(int node) {
        return world[node];
    }

    /** {@code node}'s opacity as of the last {@link #evaluate}: what its parts draw at. */
    public float presence(int node) {
        return presence[node];
    }

    /** Evaluates it, then draws each node's parts at its presence through {@code effect}'s look. */
    public void submit(CgVfxEffect<?> effect, CgVfxFrame frame) {
        evaluate();
        for (int i = 0; i < world.length; i++) {
            int first = prefab.firstPart(i), end = prefab.firstPart(i + 1);
            if (first == end || presence[i] < CgVfxPrefab.MIN_OPACITY) continue;
            Matrix4f m = world[i];
            turn.set(m).setTranslation(0f, 0f, 0f);
            for (int k = first; k < end; k++) {
                frame.mesh(effect, prefab.partSlot(k), prefab.partMesh(k), m.m30(), m.m31(), m.m32(), turn, presence[i],
                        0f, 0f, 0f, 0f);
            }
        }
    }
}
