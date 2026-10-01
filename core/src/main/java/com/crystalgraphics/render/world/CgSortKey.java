package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgRenderQueue;

/**
 * The 64-bit key a world draw sorts by: Filament's {@code RenderPass} layout, with the depth bucket a log-quantised
 * distance so no far plane is needed.
 *
 * <pre>
 * 63    60 59    56 55          40 39          24 23          8 7     0
 * | slot  | prio  | material id  | depth bucket | mesh id     | 0     |
 * </pre>
 *
 * <ul>
 *   <li>Slot: opaque 0, alpha test 1, transparent 2, from {@link CgRenderQueue}'s thresholds. Priority: 0 to 15,
 *       higher later.</li>
 *   <li>Opaque groups by material, then front to back, then by mesh, so neighbours that can instance are adjacent.
 *       Transparent is back to front alone: blending needs the order more than the batching.</li>
 *   <li>Ids are 16-bit hashes. A collision costs a batch, never a wrong picture: batches merge on the real
 *       pipeline, snapshot and mesh.</li>
 * </ul>
 */
final class CgSortKey {

    /** Bucket units per natural log of a block distance: 0 to 65,535 covers 0 to about 60,000 blocks. */
    private static final double BUCKETS_PER_LOG = 5950.0;

    private CgSortKey() {
    }

    static long opaque(int queue, int priority, int materialId, int meshId, float distance) {
        return head(queue, priority)
                | ((long) (materialId & 0xFFFF)) << 40
                | ((long) bucket(distance)) << 24
                | ((long) (meshId & 0xFFFF)) << 8;
    }

    static long transparent(int queue, int priority, float distance) {
        return head(queue, priority) | ((long) (0xFFFF - bucket(distance))) << 24;
    }

    private static long head(int queue, int priority) {
        return ((long) (slot(queue) & 0xF)) << 60 | ((long) (priority & 0xF)) << 56;
    }

    static int slot(int queue) {
        if (queue >= CgRenderQueue.OVERLAY_THRESHOLD) return 3;
        if (queue >= CgRenderQueue.TRANSPARENT_THRESHOLD) return 2;
        if (queue >= CgRenderQueue.ALPHA_TEST_THRESHOLD) return 1;
        return 0;
    }

    /** Nearer is smaller, and a block's difference near the eye outweighs one far away. */
    static int bucket(float distance) {
        return (int) Math.min(0xFFFF, Math.log1p(Math.max(0f, distance)) * BUCKETS_PER_LOG);
    }
}
