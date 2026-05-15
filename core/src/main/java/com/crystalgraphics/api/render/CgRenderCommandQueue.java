package com.crystalgraphics.api.render;

import com.crystalgraphics.api.material.CgRenderQueue;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;

/**
 * Frame command submission queue.
 *
 * <p>Game code calls {@link #submit(CgRenderCommand)} between frames; {@code CgRenderPipeline.execute()}
 * calls {@link #sort()} then uses {@link #getSortedOpaque()} / {@link #getSortedTransparent()}.</p>
 *
 * <p>All arrays are pre-allocated and grown with {@link Arrays#copyOf} only on exhaustion —
 * no {@code ArrayList}, no autoboxing, no {@code Iterator} in the hot path.</p>
 *
 * <p>Analogues:</p>
 * <ul>
 *   <li>Filament: {@code RenderPassBuilder} accumulated Command list + {@code RenderPass::sort()}</li>
 *   <li>bgfx: per-View draw call list flushed on {@code bgfx::frame()}</li>
 *   <li>Godot: RenderList with {@code sort_by_key()} / {@code sort_by_reverse_depth_and_priority()}</li>
 *   <li>Unity: {@code CullingResults} → {@code DrawRenderers} per pass</li>
 * </ul>
 *
 * <p>Source: Section 2 (CgRenderCommand), Section 3 (CgSortKey),
 * deep-dive §Gap 2 (key construction), §Gap 3 (state minimisation via sort).</p>
 */
public final class CgRenderCommandQueue {

    private final CgRenderCommandPool pool;

    /**
     * Per-frame camera and scene data used by submit() to compute camera depth and sort keys.
     * Must be set before the first submit() call each frame.
     */
    @Setter
    private CgFrameData frameData;

    // Pre-allocated parallel arrays for submission
    private CgRenderCommand[] commands = new CgRenderCommand[256];
    private long[]             sortKeys = new long[256];
    private int[]              sortIdx  = new int[256];
    private int                cmdCount = 0;

    // Post-sort filtered views (built in sort())
    @Getter
    private CgRenderCommand[] sortedOpaque      = new CgRenderCommand[256];
    @Getter
    private CgRenderCommand[] sortedTransparent = new CgRenderCommand[256];
    @Getter
    private int opaqueCount      = 0;
    @Getter
    private int transparentCount = 0;

    /**
     * Creates a new queue backed by the given pool.
     *
     * @param pool the pre-allocated command pool; must not be null
     */
    public CgRenderCommandQueue(CgRenderCommandPool pool) {
        this.pool = pool;
    }

    /**
     * Acquires a blank command slot from the pool. Caller fills all fields, then calls submit().
     *
     * @return a reset command slot ready for population
     */
    public CgRenderCommand acquireCommand() {
        return pool.acquire();
    }

    /**
     * Submits a filled command to the queue.
     *
     * <p>Actions performed at submit time:</p>
     * <ol>
     *   <li>Throws {@link IllegalArgumentException} for OVERLAY slot (unsupported in MVP).</li>
     *   <li>Validates worldAabb — all 6 floats must be finite (not NaN/Inf) AND min ≤ max per axis.
     *       Throws if invalid; releases cmd back to pool before throwing.</li>
     *   <li>Derives normalMatrix = inverse-transpose of modelMatrix upper 3x3
     *       via JOML {@code Matrix4f.normal(dest)}. Always derived — caller value overwritten.</li>
     *   <li>Computes cameraDepth = dot(aabbCenter - cameraPos, cameraForward), clamped ≥ 0.</li>
     *   <li>Sets passFlags bitmask via integer threshold comparisons against
     *       {@link CgRenderQueue} threshold constants.</li>
     *   <li>Computes 64-bit sortKey via {@link CgSortKey#buildOpaqueKey} or
     *       {@link CgSortKey#buildTransparentKey}.</li>
     *   <li>Appends to pre-allocated arrays; grows with Arrays.copyOf if needed.</li>
     * </ol>
     *
     * @param cmd a filled command acquired via acquireCommand(); must not be null
     * @throws IllegalArgumentException if queue {@code >= OVERLAY_THRESHOLD}, or worldAabb is invalid
     */
    public void submit(CgRenderCommand cmd) {
        // 1. Reject OVERLAY — unsupported in Phase 1 MVP
        if (cmd.queueSlot >= CgRenderQueue.OVERLAY_THRESHOLD) {
            pool.release(cmd);
            throw new IllegalArgumentException(
                "OVERLAY queue slot not supported in Phase 1 MVP. " +
                "Use GEOMETRY, ALPHA_TEST, or TRANSPARENT.");
        }

        // 2. Validate AABB — must be explicitly set each frame (reset() initialises to NaN)
        float minX = cmd.worldAabb[0], minY = cmd.worldAabb[1], minZ = cmd.worldAabb[2];
        float maxX = cmd.worldAabb[3], maxY = cmd.worldAabb[4], maxZ = cmd.worldAabb[5];
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            pool.release(cmd);
            throw new IllegalArgumentException(
                "worldAabb not set or invalid on submitted command — set cmd.worldAabb[0..5] before submit(). " +
                "CgRenderCommand.reset() initialises to NaN to catch this error early.");
        }

        // 3. Derive normalMatrix (JOML: inverse-transpose of upper-left 3x3, stored in dest mat4)
        cmd.modelMatrix.normal(cmd.normalMatrix);

        // 4. Compute camera-space depth from AABB center
        float ax = (minX + maxX) * 0.5f;
        float ay = (minY + maxY) * 0.5f;
        float az = (minZ + maxZ) * 0.5f;
        float d = (ax - frameData.cameraPos.x) * frameData.cameraForward.x
                + (ay - frameData.cameraPos.y) * frameData.cameraForward.y
                + (az - frameData.cameraPos.z) * frameData.cameraForward.z;
        cmd.cameraDepth = Math.max(0f, d);

        // 5. Set pass flags via threshold comparisons (Unity-style integer queue routing)
        int q = cmd.queueSlot;
        cmd.passFlags = 0;
        if (q >= CgRenderQueue.OVERLAY_THRESHOLD) {
            cmd.passFlags |= CgRenderCommand.FLAG_OVERLAY;
        } else if (q >= CgRenderQueue.TRANSPARENT_THRESHOLD) {
            cmd.passFlags |= CgRenderCommand.FLAG_TRANSPARENT;
        } else if (q >= CgRenderQueue.ALPHA_TEST_THRESHOLD) {
            cmd.passFlags |= CgRenderCommand.FLAG_ALPHA_TEST;
        } else {
            cmd.passFlags |= CgRenderCommand.FLAG_OPAQUE;
        }

        // Shadow flag — set for v2 readiness even though no shadow pass runs in MVP
        if ((cmd.passFlags & (CgRenderCommand.FLAG_OPAQUE | CgRenderCommand.FLAG_ALPHA_TEST)) != 0
                && cmd.material != null && cmd.material.hasShadowCasterPass()) {
            cmd.passFlags |= CgRenderCommand.FLAG_SHADOW;
        }

        // 6. Compute sort key
        int materialId = cmd.material != null ? cmd.material.getMaterialId() : 0; // stable per-instance counter — no hash collisions
        boolean isTransparent = q >= CgRenderQueue.TRANSPARENT_THRESHOLD
                && q < CgRenderQueue.OVERLAY_THRESHOLD;
        if (isTransparent) {
            cmd.sortKey = CgSortKey.buildTransparentKey(
                q, cmd.renderPriority, cmd.cameraDepth, frameData.farPlane);
        } else {
            int meshId = System.identityHashCode(cmd.mesh); // identity hash sufficient for grouping
            cmd.sortKey = CgSortKey.buildOpaqueKey(
                q, cmd.renderPriority, materialId, meshId, cmd.cameraDepth, frameData.farPlane);
        }

        // 7. Append to arrays; grow if needed
        if (cmdCount == commands.length) {
            int n = commands.length * 2;
            commands = Arrays.copyOf(commands, n);
            sortKeys  = Arrays.copyOf(sortKeys,  n);
            sortIdx   = Arrays.copyOf(sortIdx,   n);
        }
        commands[cmdCount] = cmd;
        sortKeys[cmdCount]  = cmd.sortKey;
        cmdCount++;
    }

    /**
     * Sorts submitted commands and builds filtered post-sort views.
     * Called once per frame in {@code CgRenderPipeline.execute()}, after all submits.
     *
     * <p>Indirect ascending quicksort on sortKeys[] — preserves command objects in place.
     * Post-sort: builds {@link #sortedOpaque} (FLAG_OPAQUE | FLAG_ALPHA_TEST)
     *        and {@link #sortedTransparent} (FLAG_TRANSPARENT).</p>
     */
    public void sort() {
        for (int i = 0; i < cmdCount; i++) sortIdx[i] = i;
        if (cmdCount > 1) quicksortIndirect(sortKeys, sortIdx, 0, cmdCount - 1);

        // Build filtered post-sort views
        opaqueCount = 0;
        transparentCount = 0;
        for (int i = 0; i < cmdCount; i++) {
            CgRenderCommand cmd = commands[sortIdx[i]];
            int f = cmd.passFlags;
            if ((f & (CgRenderCommand.FLAG_OPAQUE | CgRenderCommand.FLAG_ALPHA_TEST)) != 0) {
                if (opaqueCount == sortedOpaque.length) {
                    sortedOpaque = Arrays.copyOf(sortedOpaque, sortedOpaque.length * 2);
                }
                sortedOpaque[opaqueCount++] = cmd;
            } else if ((f & CgRenderCommand.FLAG_TRANSPARENT) != 0) {
                if (transparentCount == sortedTransparent.length) {
                    sortedTransparent = Arrays.copyOf(sortedTransparent, sortedTransparent.length * 2);
                }
                sortedTransparent[transparentCount++] = cmd;
            }
        }
    }

    /**
     * Indirect ascending quicksort: sorts {@code idx[lo..hi]} such that
     * {@code keys[idx[i]]} is ascending. No boxing, no allocation.
     */
    private static void quicksortIndirect(long[] keys, int[] idx, int lo, int hi) {
        if (lo >= hi) return;
        long pivot = keys[idx[(lo + hi) >>> 1]];
        int i = lo, j = hi;
        while (i <= j) {
            while (keys[idx[i]] < pivot) i++;
            while (keys[idx[j]] > pivot) j--;
            if (i <= j) {
                int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
                i++; j--;
            }
        }
        if (lo < j) quicksortIndirect(keys, idx, lo, j);
        if (i < hi) quicksortIndirect(keys, idx, i, hi);
    }

    /** Returns the total number of submitted commands this frame. */
    public int getCommandCount() {
        return cmdCount;
    }

    /**
     * Releases all acquired commands back to the pool and resets all counters.
     * Called by {@code CgRenderPipeline} AFTER the try-with-resources scope closes —
     * never inside the scope — to guarantee release even if a pass throws.
     */
    public void releaseAll() {
        pool.releaseAll();
        cmdCount = 0;
        opaqueCount = 0;
        transparentCount = 0;
    }
}
