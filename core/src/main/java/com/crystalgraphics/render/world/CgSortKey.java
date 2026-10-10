package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgRenderQueue;

/**
 * The 64-bit key a world draw sorts by: Filament's {@code RenderPass} layout, with the depth bucket a log-quantised
 * distance so no far plane is needed. Its {@link CgSortLayer} outranks everything but the slot; a transparent draw then
 * sorts in Niagara's levels: its group (an effect) back to front, then within it by its own order and distance.
 *
 * <pre>
 * opaque       63    60 59    53 52 51  48 47          32 31          16 15          0
 *              | slot  | layer  | 0 | ord  | material id  | depth bucket | mesh id     |
 * transparent  63    60 59    53 52          37 36 35  32 31          16 15          0
 *              | slot  | layer  | group bucket | s | ord  | batch key    | depth bucket |
 * </pre>
 *
 * <ul>
 *   <li>Slot: opaque 0, alpha test 1, transparent 2, from {@link CgRenderQueue}'s thresholds. Layer: a
 *       {@link CgSortLayer}'s rank. Order: 0 to 15, higher later, within the layer or the group.</li>
 *   <li>s: 0 for a transparent draw writing depth, which draws before the rest of its group whatever its order: what
 *       blends over it then sees its depth, and what is behind it is hidden by it.</li>
 *   <li>Opaque groups by material, then front to back, then by mesh, so neighbours that can instance are adjacent.
 *       Transparent is back to front alone: blending needs the order more than the batching. A group draws whole,
 *       back to front among the other groups and draws of its layer, so a haze nearer than an effect bends all of it,
 *       and a group's own orders decide what of it its haze bends.</li>
 *   <li>Ids are 16-bit hashes. A collision costs a batch, never a wrong picture: batches merge on the real
 *       pipeline, snapshot and mesh.</li>
 * </ul>
 */
final class CgSortKey {

    /** Bucket units per natural log of a block distance: 0 to 65,535 covers 0 to about 60,000 blocks. */
    private static final double BUCKETS_PER_LOG = 5950.0;

    private CgSortKey() {
    }

    static long opaque(int queue, int layer, int order, int materialId, int meshId, float distance) {
        return head(queue, layer)
                | ((long) (order & 0xF)) << 48
                | ((long) (materialId & 0xFFFF)) << 32
                | ((long) bucket(distance)) << 16
                | (meshId & 0xFFFF);
    }

    /**
     * A draw in a group at {@code groupDistance}, at {@code order} within it, then by {@code batchKey} (0 for most).
     * A draw in no group is its own: its distance in both places. One that writes depth goes first in its group.
     */
    static long transparent(int queue, int layer, float groupDistance, boolean writesDepth, int order, int batchKey,
                            float distance) {
        return head(queue, layer)
                | ((long) (0xFFFF - bucket(groupDistance))) << 37
                | (writesDepth ? 0L : 1L << 36)
                | ((long) (order & 0xF)) << 32
                | ((long) (batchKey & 0xFFFF)) << 16
                | (0xFFFF - bucket(distance));
    }

    private static long head(int queue, int layer) {
        return ((long) (slot(queue) & 0xF)) << 60 | ((long) (layer & 0x7F)) << 53;
    }

    /** At most 3: a slot of 8 or more would set the sign bit, and the keys compare as signed longs. */
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
