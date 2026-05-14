package io.github.somehussar.crystalgraphics.api.render;

import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;

/**
 * 64-bit sort key for {@link CgRenderCommand}.
 *
 * <p>Bit layout derived from Filament {@code RenderPass.h §4.3} (raw findings).
 * bgfx DepthDescending trick (UINT32_MAX-depth) adapted as inverted normalised depth.</p>
 *
 * <pre>
 * Bit 63    60 59    56 55    48 47    32 31          0
 * +---------+---------+---------+---------+-----------+
 * | slot(4b)| prio(4b)| mat(8b) | dep(16b)| reserved  |
 * +---------+---------+---------+---------+-----------+
 *
 * Bits 63-60: Queue slot (from slotIndex() threshold logic)
 *   queue &lt; ALPHA_TEST_THRESHOLD (2450)                → 0 (OPAQUE bucket)
 *   queue &gt;= ALPHA_TEST_THRESHOLD and &lt; TRANSPARENT_THRESHOLD (2500) → 1 (ALPHA_TEST bucket)
 *   queue &gt;= TRANSPARENT_THRESHOLD and &lt; OVERLAY_THRESHOLD (4000) → 2 (TRANSPARENT bucket)
 *   queue &gt;= OVERLAY_THRESHOLD                         → 3 (OVERLAY bucket)
 *
 * Bits 59-56: Render priority (0-15, user-supplied via renderPriority)
 *   Higher value = renders LATER within the same queue slot.
 *
 * Bits 55-48: Material ID (System.identityHashCode(material) &amp; 0xFF)
 *   For OPAQUE slots: groups consecutive commands with same material after sort.
 *
 * Bits 47-32: Depth bucket (16-bit quantised camera-space distance)
 *   OPAQUE/ALPHA_TEST: bucket = (normalizedDepth * 0xFFFF)
 *     → smaller = closer to camera → ascending sort = front-to-back (early-Z) ✓
 *   TRANSPARENT/OVERLAY: bucket = ((1 - normalizedDepth) * 0xFFFF)
 *     → smaller = FARTHER from camera → ascending sort = back-to-front (correct blend) ✓
 *
 * Bits 31-0: Reserved (0)
 * </pre>
 */
public final class CgSortKey {

    private CgSortKey() {} // utility class — no instances

    /**
     * Encodes the sort key for opaque/alpha-test commands.
     *
     * <p>Result: slot(4b) | priority(4b) | materialId(8b) | depthBucket(16b,front-to-back) | 0(32b)</p>
     * <p>Sort: ascending → front-to-back within each slot, state-minimising by material.</p>
     *
     * @param queueValue     integer queue value (e.g. {@link CgRenderQueue#GEOMETRY})
     * @param renderPriority user priority (0-15)
     * @param materialId     {@code System.identityHashCode(material) & 0xFF}
     * @param cameraDepth    positive z-distance from camera to AABB center
     * @param farPlane       camera far plane distance (world units)
     * @return 64-bit opaque sort key
     */
    public static long buildOpaqueKey(int queueValue, int renderPriority,
                                      int materialId, float cameraDepth, float farPlane) {
        long key = 0L;
        key |= ((long)(slotIndex(queueValue) & 0xF))  << 60;
        key |= ((long)(renderPriority        & 0xF))  << 56;
        key |= ((long)(materialId            & 0xFF)) << 48;
        // Front-to-back: smaller depth → smaller bucket value → sorted first
        float nd     = clamp01(cameraDepth / farPlane);
        int   bucket = (int)(nd * 0xFFFFL);
        key |= ((long)(bucket & 0xFFFF)) << 32;
        return key;
    }

    /**
     * Encodes the sort key for transparent commands.
     *
     * <p>Result: slot(4b) | priority(4b) | 0(8b) | depthBucket(16b,INVERTED,back-to-front) | 0(32b)</p>
     * <p>Sort: ascending → back-to-front (inverted depth bucket = far objects get small key).</p>
     * <p>Material ID not used for transparent: depth ordering is mandatory for correct blending.</p>
     *
     * @param queueValue     integer queue value (e.g. {@link CgRenderQueue#TRANSPARENT})
     * @param renderPriority user priority (0-15)
     * @param cameraDepth    positive z-distance from camera
     * @param farPlane       camera far plane
     * @return 64-bit transparent sort key
     */
    public static long buildTransparentKey(int queueValue,
                                           int renderPriority, float cameraDepth, float farPlane) {
        long key = 0L;
        key |= ((long)(slotIndex(queueValue) & 0xF)) << 60;
        key |= ((long)(renderPriority        & 0xF)) << 56;
        // Material ID = 0 for transparent: depth ordering takes priority
        // Back-to-front: far=small bucket → sorted first; invert depth
        float nd     = clamp01(cameraDepth / farPlane);
        int   bucket = (int)((1.0f - nd) * 0xFFFFL);
        key |= ((long)(bucket & 0xFFFF)) << 32;
        return key;
    }

    /**
     * Maps an integer queue value to the 4-bit slot index embedded in bits 63-60,
     * using threshold comparisons. This enables any integer queue value to be used,
     * not just the named constants.
     *
     * <p>Routing table (matches Unity renderQueue conventions):</p>
     * <ul>
     *   <li>{@code >= OVERLAY_THRESHOLD} (4000) → slot 3 (overlay)</li>
     *   <li>{@code >= TRANSPARENT_THRESHOLD} (2500) → slot 2 (transparent)</li>
     *   <li>{@code >= ALPHA_TEST_THRESHOLD} (2450) → slot 1 (alpha-test)</li>
     *   <li>anything else → slot 0 (opaque)</li>
     * </ul>
     */
    static int slotIndex(int queue) {
        if (queue >= CgRenderQueue.OVERLAY_THRESHOLD)     return 3;
        if (queue >= CgRenderQueue.TRANSPARENT_THRESHOLD) return 2;
        if (queue >= CgRenderQueue.ALPHA_TEST_THRESHOLD)  return 1;
        return 0;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
