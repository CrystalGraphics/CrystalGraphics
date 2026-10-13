package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;

import javax.annotation.Nullable;

/**
 * Each thread's started hitboxes and hittable ones: what the engine ticks after the level's tick (a server's on the server
 * tick, a client's on the client tick), what that thread's queries meet, and what the collision show watches. Each
 * thread has its own, so a hitbox is only ever touched by the thread running it.
 *
 * <p>The engine's plumbing between hitboxes, queries and the show: a mod starts, ticks and reads a {@link CgHitbox}.
 */
public final class CgHitboxes {

    /** What {@link #hittables} hands over. */
    public interface Hittables {
        /** Whether to hand over {@code hitbox}'s parts: it ticks in {@code world}, standing for {@code owner}. */
        boolean wants(CgHitbox hitbox, CgCollisionWorld world, int owner);

        /** One part as last placed; {@code shape} and {@code pose} are valid during the call unless frozen. */
        void part(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose);
    }

    /** What {@link #watch} hands each tick. */
    @FunctionalInterface
    public interface Watcher {
        /** {@code hitbox} has ticked in {@code world}, after its hits; on the thread ticking it. */
        void ticked(CgHitbox hitbox, CgCollisionWorld world);
    }

    private static final ThreadLocal<CgHitboxes> ACTIVE = ThreadLocal.withInitial(CgHitboxes::new);
    private static volatile @Nullable Watcher watcher;
    private static boolean hooked;

    private CgHitbox[] hitboxes = new CgHitbox[16];
    private int size;
    private CgHitbox[] hittables = new CgHitbox[4];
    private int hittableCount;
    /** The hitbox running its own queries on this thread: they never meet it, and the show leaves them out. */
    private CgHitbox gathering;
    private final CgPose scratch = new CgPose();

    private CgHitboxes() {
    }

    /**
     * Hands {@code out} each part of this thread's hittable hitboxes that queries meet now: active, placed and not stopped.
     * Frozen, each shape and pose is a copy of its own, for a snapshot to keep.
     */
    public static void hittables(Hittables out, boolean frozen) {
        CgHitboxes set = ACTIVE.get();
        for (int i = 0; i < set.hittableCount; i++) {
            CgHitbox t = set.hittables[i];
            if (!t.posed() || t.stopped() || !out.wants(t, t.world(), t.owner())) continue;
            for (int p = 0; p < t.partCount(); p++) {
                if (frozen) out.part(t, t.partName(p), t.frozenPartShape(p), t.partPose(p, new CgPose()));
                else out.part(t, t.partName(p), t.partShape(p), t.partPose(p, set.scratch));
            }
        }
    }

    /** The hitbox whose own queries are running on this thread; null for none. */
    public static @Nullable CgHitbox gathering() {
        return ACTIVE.get().gathering;
    }

    /** Hands {@code watcher} every hitbox each time it ticks; null stops. One watcher for the process: the collision show. */
    public static void watch(@Nullable Watcher watcher) {
        CgHitboxes.watcher = watcher;
    }

    static void add(CgHitbox hitbox) {
        hook();
        CgHitboxes set = ACTIVE.get();
        if (set.size == set.hitboxes.length) set.hitboxes = grow(set.hitboxes);
        set.hitboxes[set.size++] = hitbox;
    }

    /** Makes {@code hitbox} met by this thread's queries until it stops. */
    static void addHittable(CgHitbox hitbox) {
        hook();
        CgHitboxes set = ACTIVE.get();
        if (set.hittableCount == set.hittables.length) set.hittables = grow(set.hittables);
        set.hittables[set.hittableCount++] = hitbox;
    }

    /** Marks {@code hitbox}'s own queries running on this thread, until null. */
    static void gathering(@Nullable CgHitbox hitbox) {
        ACTIVE.get().gathering = hitbox;
    }

    static void ticked(CgHitbox hitbox, CgCollisionWorld world) {
        Watcher w = watcher;
        if (w != null) w.ticked(hitbox, world);
    }

    /** Ticks this thread's hitboxes once. Those started during the pass first tick on the next. */
    static void tickThread() {
        ACTIVE.get().tick();
    }

    /** How many are running on this thread. */
    static int running() {
        return ACTIVE.get().size;
    }

    private static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        CgNetwork.onServerTick(CgHitboxes::tickThread);
        CgNetwork.onClientTick(CgHitboxes::tickThread);
    }

    private void tick() {
        int n = size;
        try (CgCollisionWorld.Batch batch = CgCollisionWorld.batch()) {
            for (int i = 0; i < n; i++) {
                if (!hitboxes[i].stopped()) hitboxes[i].engineTick();
            }
        }
        size = keepRunning(hitboxes, size);
        // between queries: a scan never sees them move
        hittableCount = keepRunning(hittables, hittableCount);
    }

    private static CgHitbox[] grow(CgHitbox[] list) {
        CgHitbox[] grown = new CgHitbox[list.length * 2];
        System.arraycopy(list, 0, grown, 0, list.length);
        return grown;
    }

    private static int keepRunning(CgHitbox[] list, int count) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (!list[i].stopped()) list[kept++] = list[i];
        }
        for (int i = kept; i < count; i++) list[i] = null;
        return kept;
    }
}
