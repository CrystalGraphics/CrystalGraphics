package com.crystalgraphics.shadergraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The preview render targets, owned by the <b>context</b> rather than by whoever is drawing.
 *
 * <h3>Why this exists</h3>
 *
 * <p>{@link CgPreviewRenderer} used to own its own {@link CgPreviewSlots}, so the pool was per open
 * shader graph. Two consequences, and the second is the expensive one:</p>
 *
 * <ul>
 *   <li><b>Memory scaled with what was open, not with what was visible.</b> Five open graphs held five
 *       capacities' worth of framebuffers — ~2.25 MB each at the default size — to draw the one graph in
 *       front. The others are behind tabs and are not drawing at all.</li>
 *   <li><b>Closing a graph deleted a pool.</b> Creating and destroying GPU objects is driver-serialised,
 *       so a close/reopen cycle — which is an ordinary gesture, repeated constantly — paid a full
 *       allocate for targets that had just been thrown away.</li>
 * </ul>
 *
 * <p>Both are the same mistake: a resource whose lifetime was tied to a <em>document</em> instead of to
 * the <em>context</em>. With the pool here, closing a graph releases keys and deletes nothing, reopening
 * allocates nothing, and the framebuffer count is bounded by capacity rather than by how many graphs
 * somebody has opened this session.</p>
 *
 * <h3>One pool per geometry</h3>
 *
 * <p>Targets of different sizes or sample counts cannot substitute for one another, so they cannot share
 * a free list — a pool is keyed by {@code (size, samples)}. In practice there is one: everything uses the
 * defaults, and the parameterised constructor exists for harness scenes.</p>
 *
 * <h3>Teardown</h3>
 *
 * <p>{@link #deleteAll()} is called from {@code CgGraphicsLifecycle.destroyContext()}. That is a real
 * improvement on its own: the targets are {@code createOwned}, so <b>no registry sweeps them</b> and
 * release previously depended on every renderer's owner remembering to call {@code delete()}. Now the
 * context frees them because the context owns them.</p>
 */
public final class CgPreviewPool {

    private CgPreviewPool() {
    }

    /** {@code size + "x" + samples} → its pool. Insertion-ordered so teardown is deterministic. */
    private static final Map<String, CgPreviewSlots<CgPreviewTarget>> POOLS = new LinkedHashMap<>();

    /** Hands every renderer a distinct key namespace — see {@link CgPreviewSlots#retainOnlyWithin}. */
    private static final AtomicInteger SCOPES = new AtomicInteger();

    /** A namespace no other renderer will use, for keys in the shared pool. */
    public static String newScope() {
        return "s" + SCOPES.incrementAndGet() + "/";
    }

    /**
     * The shared pool for this geometry, created on first ask.
     *
     * <p>{@code capacity} is honoured from the first caller and ignored afterwards: a pool's capacity is
     * a property of the pool, and letting a later renderer shrink one that others are already using would
     * evict their targets for a reason none of them can see.</p>
     */
    public static CgPreviewSlots<CgPreviewTarget> forGeometry(int size, int samples, int capacity) {
        return POOLS.computeIfAbsent(size + "x" + samples,
                key -> new CgPreviewSlots<>(capacity, () -> new CgPreviewTarget("cg_preview", size, samples)));
    }

    /**
     * Deletes every target in every pool. <b>GL thread, at context destruction only.</b>
     *
     * <p>Not to be called when a graph closes — that is a release, and the distinction is the entire
     * point of this class.</p>
     */
    public static void deleteAll() {
        for (CgPreviewSlots<CgPreviewTarget> pool : POOLS.values()) {
            for (CgPreviewTarget target : pool.all()) target.delete();
            pool.clear();
        }
        POOLS.clear();
    }

    /** Total live slots across every pool. For tests and diagnostics. */
    public static int liveCount() {
        int total = 0;
        for (CgPreviewSlots<CgPreviewTarget> pool : POOLS.values()) total += pool.liveCount();
        return total;
    }

    /** Total free (allocated, unassigned) slots across every pool. */
    public static int pooledCount() {
        int total = 0;
        for (CgPreviewSlots<CgPreviewTarget> pool : POOLS.values()) total += pool.pooledCount();
        return total;
    }

    /** Drops every pool <b>without</b> deleting anything. For tests that never made a GL object. */
    public static void resetForTesting() {
        POOLS.clear();
    }
}
