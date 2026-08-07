package com.crystalgraphics.shadergraph;

import org.junit.Test;

import java.util.Set;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>One pool, many renderers</b> — the scoping that lets preview targets belong to the context.
 *
 * <p>A pool per renderer meant memory scaled with how many graphs were <em>open</em> rather than with how
 * many were <em>visible</em>, and closing a graph deleted framebuffers that reopening it immediately
 * re-created — driver-serialised work on a gesture people repeat constantly.</p>
 *
 * <p>Asserted against {@link CgPreviewSlots} directly with {@code String} slots, because that is what the
 * class is generic for: the policy is the part that can be wrong, and it can be wrong without a GL
 * context.</p>
 */
public class CgPreviewPoolScopeTest {

    /** Allocates a distinguishable slot each time, so "was this reused" is observable. */
    private static Supplier<String> counting(int[] made) {
        return () -> "slot" + (++made[0]);
    }

    @Test
    public void twoScopesShareOnePoolWithoutCollidingOnKeys() {
        int[] made = {0};
        CgPreviewSlots<String> pool = new CgPreviewSlots<>(8, counting(made));

        String a = pool.acquire("s1/node");
        String b = pool.acquire("s2/node");

        assertNotSame("two graphs' node 'node' must not share one target", a, b);
        assertEquals(2, pool.liveCount());
        assertEquals(1, pool.liveCountWithin("s1/"));
        assertEquals(1, pool.liveCountWithin("s2/"));
    }

    /**
     * <b>A cull confined to its own namespace.</b>
     *
     * <p>The pool is shared, so an unscoped {@code retainOnly} would release every other open graph's
     * targets — which would show up as thumbnails going blank in a tab nobody had touched.</p>
     */
    @Test
    public void cullingOneScopeLeavesAnotherAlone() {
        int[] made = {0};
        CgPreviewSlots<String> pool = new CgPreviewSlots<>(8, counting(made));
        pool.acquire("s1/a");
        pool.acquire("s1/b");
        String other = pool.acquire("s2/a");

        pool.retainOnlyWithin("s1/", Set.of("s1/a"));

        assertEquals("s1 should have culled exactly one", 1, pool.liveCountWithin("s1/"));
        assertEquals("s2 was culled by a cull it had nothing to do with", 1, pool.liveCountWithin("s2/"));
        assertSame(other, pool.peek("s2/a"));
    }

    /**
     * <b>Closing releases; it does not delete.</b>
     *
     * <p>The whole reason the pool moved to context scope. The freed slots stay allocated, so the next
     * acquire — a reopen, or another graph — takes one back instead of asking the driver for a new
     * framebuffer.</p>
     */
    @Test
    public void releasingAScopeKeepsTheSlotsForReuse() {
        int[] made = {0};
        CgPreviewSlots<String> pool = new CgPreviewSlots<>(8, counting(made));
        pool.acquire("s1/a");
        pool.acquire("s1/b");
        assertEquals(2, made[0]);

        pool.releaseAllWithin("s1/");

        assertEquals("nothing should still be live", 0, pool.liveCount());
        assertEquals("and nothing should have been thrown away", 2, pool.pooledCount());

        // A reopen, or a different graph entirely: both take a slot back rather than allocating.
        pool.acquire("s3/x");
        pool.acquire("s3/y");
        assertEquals("reopening allocated instead of reusing", 2, made[0]);
    }

    /** Releasing one scope leaves another's live slots untouched. */
    @Test
    public void releasingOneScopeDoesNotTouchAnother() {
        int[] made = {0};
        CgPreviewSlots<String> pool = new CgPreviewSlots<>(8, counting(made));
        pool.acquire("s1/a");
        String kept = pool.acquire("s2/a");

        pool.releaseAllWithin("s1/");

        assertEquals(1, pool.liveCount());
        assertSame(kept, pool.peek("s2/a"));
    }

    /**
     * <b>Capacity is now shared, which is the memory half of the win.</b>
     *
     * <p>Five open graphs used to hold five capacities' worth of framebuffers to draw the one in front.
     * One pool means the bound is what is on screen, not what has been opened.</p>
     */
    @Test
    public void capacityBoundsEveryScopeTogether() {
        int[] made = {0};
        CgPreviewSlots<String> pool = new CgPreviewSlots<>(3, counting(made));
        pool.acquire("s1/a");
        pool.acquire("s1/b");
        pool.acquire("s2/a");
        pool.acquire("s2/b");

        assertTrue("the shared cap was exceeded", made[0] <= 3);
        assertEquals(3, pool.liveCount());
    }

    /** A distinct namespace per renderer, with no coordination required. */
    @Test
    public void scopesAreUnique() {
        assertNotSame(CgPreviewPool.newScope(), CgPreviewPool.newScope());
        assertTrue(CgPreviewPool.newScope().endsWith("/"));
    }

    /** One pool per geometry: different sizes cannot substitute for each other in a free list. */
    @Test
    public void geometriesGetTheirOwnPool() {
        CgPreviewPool.resetForTesting();
        assertSame(CgPreviewPool.forGeometry(256, 4, 8), CgPreviewPool.forGeometry(256, 4, 8));
        assertNotSame(CgPreviewPool.forGeometry(256, 4, 8), CgPreviewPool.forGeometry(128, 4, 8));
        CgPreviewPool.resetForTesting();
    }
}
