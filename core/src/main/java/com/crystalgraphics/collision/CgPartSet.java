package com.crystalgraphics.collision;

import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A snapshot's hittable hitboxes, frozen: each part's shape, a copy of its pose, its box, and its owner's flags, kind
 * and root vehicle as they were. Candidates are found by a scan of the boxes.
 */
final class CgPartSet {

    /** The flags of an owner in a world with no entities, which entity filters let through. */
    static final int NOT_AN_ENTITY = -1;

    private CgHitbox[] hitboxes = new CgHitbox[4];
    private CgHitPart[] names = new CgHitPart[4];
    private CgShape[] shapes = new CgShape[4];
    private CgPose[] poses = new CgPose[4];
    private CgAABB[] bounds = new CgAABB[4];
    /** Per part: the id it stands for, and that id's flags, kind and root vehicle. */
    private int[] owners = new int[4 * 4];
    private int size;

    /** {@code world}'s live hittable hitboxes touching {@code within}, copied now; null for none. */
    static @Nullable CgPartSet frozen(CgCollisionWorld world, CgAABBc within) {
        CgPartSet set = new CgPartSet();
        set.freeze(world, within);
        return set.size == 0 ? null : set;
    }

    private void freeze(CgCollisionWorld world, CgAABBc within) {
        CgEntityQuery view = world.view();
        CgAABB box = new CgAABB();
        CgHitboxes.hittables(new CgHitboxes.Hittables() {
            private int owner, flags, kind, root;

            @Override
            public boolean wants(CgHitbox hitbox, CgCollisionWorld tickedIn, int owner) {
                if (!world.sameLevel(tickedIn)) return false;
                this.owner = owner;
                flags = flagsOf(view, owner);
                kind = view.kind(owner);
                root = view.rootVehicle(owner);
                return true;
            }

            @Override
            public void part(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose) {
                if (shape.bounds(pose, box).intersects(within)) {
                    add(hitbox, name, shape, pose, owner, flags, kind, root);
                }
            }
        }, true);
    }

    /** An entity's flags as a part's: none to filter by where the world has no entities. */
    static int flagsOf(CgEntityQuery view, int id) {
        return view == CgEntityQuery.NONE ? NOT_AN_ENTITY : view.flags(id);
    }

    /** Adds a part at a copy of {@code pose}. */
    private void add(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose, int owner, int flags,
             int kind, int root) {
        if (size == shapes.length) grow();
        hitboxes[size] = hitbox;
        names[size] = name;
        shapes[size] = shape;
        if (poses[size] == null) {
            poses[size] = new CgPose();
            bounds[size] = new CgAABB();
        }
        poses[size].set(pose);
        shape.bounds(pose, bounds[size]);
        int o = size * 4;
        owners[o] = owner;
        owners[o + 1] = flags;
        owners[o + 2] = kind;
        owners[o + 3] = root;
        size++;
    }

    /** Hands {@code q} each part whose box touches {@code within}. */
    void scan(CgQuery q, CgAABBc within) {
        for (int i = 0; i < size; i++) {
            if (!bounds[i].intersects(within)) continue;
            int o = i * 4;
            q.meet(hitboxes[i], names[i], shapes[i], poses[i], owners[o], owners[o + 1], owners[o + 2], owners[o + 3]);
        }
    }

    private void grow() {
        int n = size * 2;
        hitboxes = Arrays.copyOf(hitboxes, n);
        names = Arrays.copyOf(names, n);
        shapes = Arrays.copyOf(shapes, n);
        poses = Arrays.copyOf(poses, n);
        bounds = Arrays.copyOf(bounds, n);
        owners = Arrays.copyOf(owners, n * 4);
    }
}
