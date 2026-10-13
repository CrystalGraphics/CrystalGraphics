package com.crystalgraphics.collision;

import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Arrays;

/** A snapshot's hittable hitboxes: each part's shape and pose, and its owner's flags, as they were when it was taken. */
final class CgFrozenHittables implements CgHitboxes.Hittables {

    private CgHitbox[] hitboxes = new CgHitbox[4];
    private CgHitPart[] names = new CgHitPart[4];
    private CgShape[] shapes = new CgShape[4];
    private CgPose[] poses = new CgPose[4];
    private CgAABB[] bounds = new CgAABB[4];
    /** Per part: its owner, and the owner's flags, kind and root vehicle. */
    private int[] owners = new int[4 * 4];
    private int size;

    // while freezing, then let go: a snapshot outlives its world's tick
    private CgCollisionWorld world;
    private CgEntityQuery view;
    private @Nullable CgAABBc within;
    private int owner, flags, kind, root;

    private CgFrozenHittables(CgCollisionWorld world, @Nullable CgAABBc within) {
        this.world = world;
        this.view = world.view();
        this.within = within;
    }

    /** The parts of {@code world}'s hittable hitboxes touching {@code within}, every part for null; null for none. */
    static @Nullable CgFrozenHittables of(CgCollisionWorld world, @Nullable CgAABBc within) {
        CgFrozenHittables frozen = new CgFrozenHittables(world, within);
        CgHitboxes.hittables(frozen, true);
        frozen.world = null;
        frozen.view = null;
        frozen.within = null;
        return frozen.size == 0 ? null : frozen;
    }

    @Override
    public boolean wants(CgHitbox hitbox, CgCollisionWorld tickedIn, int owner) {
        if (!world.sameLevel(tickedIn)) return false;
        this.owner = owner;
        flags = view.flags(owner);
        kind = view.kind(owner);
        root = world.rootOf(view, owner);
        return true;
    }

    @Override
    public void part(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose) {
        CgAABB box = shape.bounds(pose, new CgAABB());
        if (within != null && !box.intersects(within)) return;
        if (size == shapes.length) {
            int n = size * 2;
            hitboxes = Arrays.copyOf(hitboxes, n);
            names = Arrays.copyOf(names, n);
            shapes = Arrays.copyOf(shapes, n);
            poses = Arrays.copyOf(poses, n);
            bounds = Arrays.copyOf(bounds, n);
            owners = Arrays.copyOf(owners, n * 4);
        }
        hitboxes[size] = hitbox;
        names[size] = name;
        shapes[size] = shape;
        poses[size] = pose;
        bounds[size] = box;
        owners[size * 4] = owner;
        owners[size * 4 + 1] = flags;
        owners[size * 4 + 2] = kind;
        owners[size * 4 + 3] = root;
        size++;
    }

    /** Hands {@code q} each part whose box touches {@code within}. */
    void scan(CgQuery q, CgAABBc within) {
        for (int i = 0; i < size; i++) {
            if (!bounds[i].intersects(within)) continue;
            int o = i * 4;
            q.hittable(hitboxes[i], names[i], shapes[i], poses[i], owners[o], owners[o + 1], owners[o + 2],
                    owners[o + 3]);
        }
    }
}
