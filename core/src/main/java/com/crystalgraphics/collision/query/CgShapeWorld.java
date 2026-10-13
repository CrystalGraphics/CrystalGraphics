package com.crystalgraphics.collision.query;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A world of plain shapes placed by id, with no blocks and no host: what a test, an arena or a mod's own hurtboxes
 * query. Every test is exact against the shapes themselves, not their boxes.
 *
 * <pre>{@code
 * CgShapeWorld arena = CgCollisionWorld.shapes();
 * arena.add(1, CgCapsule.of(0.9, 0.3), pose.at(4, 0, 0));      // the pose is copied
 * arena.add(2, CgSphere.of(1), pose.at(-3, 1, 2));
 *
 * arena.overlap(BLAST).at(0, 0, 0).forEach(h -> hurt(h.entity()));
 * arena.move(1, pose.at(5, 0, 0));
 * arena.remove(2);
 * }</pre>
 *
 * <ul>
 *   <li>Only {@link CgQuery#except}, {@link CgQuery#source}, {@link CgQuery#where} and {@link CgQuery#max} filter here:
 *       there are no entity flags, kinds or boxes to inflate, and every shape is in sight.</li>
 *   <li>A cast meets convex shapes and compounds of them; an overlap and a ray meet any (see {@code CgShapes}).</li>
 *   <li>Candidates are found by a scan of every shape's box: tens to hundreds of shapes, not thousands.</li>
 * </ul>
 */
public final class CgShapeWorld extends CgCollisionWorld {

    private int[] ids = new int[8];
    private CgShape[] shapes = new CgShape[8];
    private CgPose[] poses = new CgPose[8];
    private CgAABB[] bounds = new CgAABB[8];
    private int size;

    CgShapeWorld() {
        super(CgWorldQuery.NONE, CgEntityQuery.NONE, 1f);
    }

    /**
     * Places {@code shape} as {@code id}, at a copy of {@code pose}.
     *
     * @throws IllegalArgumentException for an id already placed
     */
    public CgShapeWorld add(int id, CgShape shape, CgPose pose) {
        Objects.requireNonNull(shape);
        if (indexOf(id) >= 0) throw new IllegalArgumentException("shape " + id + " is already placed");
        if (size == ids.length) grow();
        ids[size] = id;
        shapes[size] = shape;
        if (poses[size] == null) {
            poses[size] = new CgPose();
            bounds[size] = new CgAABB();
        }
        poses[size].set(pose);
        shape.bounds(pose, bounds[size]);
        size++;
        return this;
    }

    /**
     * Moves {@code id} to a copy of {@code pose}.
     *
     * @throws IllegalArgumentException for an id not placed
     */
    public CgShapeWorld move(int id, CgPose pose) {
        int i = indexOf(id);
        if (i < 0) throw new IllegalArgumentException("shape " + id + " is not placed");
        poses[i].set(pose);
        shapes[i].bounds(pose, bounds[i]);
        return this;
    }

    /** Takes {@code id} away; false when it was not placed. */
    public boolean remove(int id) {
        int i = indexOf(id);
        if (i < 0) return false;
        int last = --size;
        CgPose pose = poses[i];
        CgAABB box = bounds[i];
        ids[i] = ids[last];
        shapes[i] = shapes[last];
        poses[i] = poses[last];
        bounds[i] = bounds[last];
        poses[last] = pose;
        bounds[last] = box;
        shapes[last] = null;
        return true;
    }

    public CgShapeWorld clear() {
        for (int i = 0; i < size; i++) shapes[i] = null;
        size = 0;
        return this;
    }

    public int size() {
        return size;
    }

    /** Whether {@code target} is placed: nothing here blocks sight. */
    @Override
    public boolean lineOfSight(double x, double y, double z, int target) {
        return indexOf(target) >= 0;
    }

    /** 1 for a placed shape, 0 for none: nothing here blocks sight. */
    @Override
    public float exposure(double x, double y, double z, int target) {
        return indexOf(target) >= 0 ? 1f : 0f;
    }

    @Override
    void candidates(CgQuery q, CgEntityQuery view, CgAABBc within, IntConsumer visitor) {
        for (int i = 0; i < size; i++) {
            if (bounds[i].intersects(within)) q.placed(ids[i], shapes[i], poses[i]);
        }
    }

    @Override
    boolean hasBlocks() {
        return false;
    }

    @Override
    int rootOf(CgEntityQuery view, int id) {
        return id;
    }

    @Override
    boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        return true;
    }

    private int indexOf(int id) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == id) return i;
        }
        return -1;
    }

    private void grow() {
        int n = ids.length * 2;
        int[] i = new int[n];
        CgShape[] s = new CgShape[n];
        CgPose[] p = new CgPose[n];
        CgAABB[] b = new CgAABB[n];
        System.arraycopy(ids, 0, i, 0, size);
        System.arraycopy(shapes, 0, s, 0, size);
        System.arraycopy(poses, 0, p, 0, size);
        System.arraycopy(bounds, 0, b, 0, size);
        ids = i;
        shapes = s;
        poses = p;
        bounds = b;
    }
}
