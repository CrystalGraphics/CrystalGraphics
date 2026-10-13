package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Arrays;

/** A snapshot's target hitboxes: each part's shape and pose, and its owner's flags, as they were when it was taken. */
final class CgFrozenTargets {

    private CgHitbox[] hitboxes = new CgHitbox[4];
    private CgHitPart[] names = new CgHitPart[4];
    private CgShape[] shapes = new CgShape[4];
    private CgPose[] poses = new CgPose[4];
    private CgAABB[] bounds = new CgAABB[4];
    /** Per part: its owner, and the owner's flags, kind and root vehicle. */
    private int[] owners = new int[4 * 4];
    private int size;

    private CgFrozenTargets() {
    }

    /** The parts of {@code world}'s targets touching {@code within}, every part for null; null for none. */
    static @Nullable CgFrozenTargets of(CgCollisionWorld world, @Nullable CgAABBc within) {
        CgHitboxes set = CgHitboxes.thread();
        CgEntityQuery view = world.view();
        CgFrozenTargets frozen = null;
        for (int i = 0; i < set.targetCount; i++) {
            CgHitbox t = set.targets[i];
            if (!t.metIn(world)) continue;
            int owner = t.owner();
            for (int p = 0; p < t.partCount(); p++) {
                CgShape shape = t.frozenPartShape(p);
                CgPose pose = t.partPose(p, new CgPose());
                CgAABB box = shape.bounds(pose, new CgAABB());
                if (within != null && !box.intersects(within)) continue;
                if (frozen == null) frozen = new CgFrozenTargets();
                frozen.add(t, t.partName(p), shape, pose, box, owner, view.flags(owner), view.kind(owner),
                        world.rootOf(view, owner));
            }
        }
        return frozen;
    }

    private void add(CgHitbox t, CgHitPart name, CgShape shape, CgPose pose, CgAABB box, int owner, int flags,
                     int kind, int root) {
        if (size == shapes.length) {
            int n = size * 2;
            hitboxes = Arrays.copyOf(hitboxes, n);
            names = Arrays.copyOf(names, n);
            shapes = Arrays.copyOf(shapes, n);
            poses = Arrays.copyOf(poses, n);
            bounds = Arrays.copyOf(bounds, n);
            owners = Arrays.copyOf(owners, n * 4);
        }
        hitboxes[size] = t;
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
            q.target(hitboxes[i], names[i], shapes[i], poses[i], owners[o], owners[o + 1], owners[o + 2],
                    owners[o + 3]);
        }
    }
}
