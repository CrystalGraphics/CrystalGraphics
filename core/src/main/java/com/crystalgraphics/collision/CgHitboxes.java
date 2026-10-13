package com.crystalgraphics.collision;

import com.crystalgraphics.net.CgNetwork;

/**
 * The started hitboxes of one thread, ticked after its level's tick: a server's on the server tick, a client's on the
 * client tick. Each thread has its own, so a hitbox is only ever touched by the thread that started it. Beside them,
 * the thread's targets, started or ticked by an owner, which its queries meet.
 */
final class CgHitboxes {

    private static final ThreadLocal<CgHitboxes> ACTIVE = ThreadLocal.withInitial(CgHitboxes::new);
    private static boolean hooked;

    private CgHitbox[] hitboxes = new CgHitbox[16];
    private int size;
    CgHitbox[] targets = new CgHitbox[4];
    int targetCount;

    static void add(CgHitbox hitbox) {
        hook();
        CgHitboxes set = ACTIVE.get();
        if (set.size == set.hitboxes.length) {
            CgHitbox[] grown = new CgHitbox[set.size * 2];
            System.arraycopy(set.hitboxes, 0, grown, 0, set.size);
            set.hitboxes = grown;
        }
        set.hitboxes[set.size++] = hitbox;
    }

    /** Makes {@code target} met by this thread's queries until it stops. */
    static void addTarget(CgHitbox target) {
        hook();
        CgHitboxes set = ACTIVE.get();
        if (set.targetCount == set.targets.length) {
            CgHitbox[] grown = new CgHitbox[set.targetCount * 2];
            System.arraycopy(set.targets, 0, grown, 0, set.targetCount);
            set.targets = grown;
        }
        set.targets[set.targetCount++] = target;
    }

    /** This thread's set, which its queries scan for targets. */
    static CgHitboxes thread() {
        return ACTIVE.get();
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
        // between queries: a scan never sees the targets move
        targetCount = keepRunning(targets, targetCount);
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
