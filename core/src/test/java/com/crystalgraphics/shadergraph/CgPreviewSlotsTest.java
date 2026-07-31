package com.crystalgraphics.shadergraph;

import org.junit.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * P6.3.7 — the preview pool's keep/reuse/evict rule.
 *
 * <h3>Why this is tested at all, given it holds framebuffers</h3>
 * <p>It does not, here — that is the point of the split. Every failure mode of a wrong LRU is a thumbnail
 * that is blank, stale, or belongs to a different node, and <b>none of them throws</b>. On screen they are
 * indistinguishable from a preview that simply has not finished rendering, so this is precisely the logic
 * that would ship broken and stay broken.</p>
 */
public class CgPreviewSlotsTest {

    /** Hands out distinct tokens and counts how many were ever made — the allocation is what we assert on. */
    private static final class Counting {
        final AtomicInteger made = new AtomicInteger();

        CgPreviewSlots<String> pool(int capacity) {
            return new CgPreviewSlots<>(capacity, () -> "slot" + made.incrementAndGet());
        }
    }

    // ── Reuse ───────────────────────────────────────────────────────────────

    /** Asking twice for the same node must not allocate twice — otherwise every frame leaks a target. */
    @Test
    public void thesameNodeKeepsTheSameTarget() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(4);

        assertEquals(slots.acquire("a"), slots.acquire("a"));
        assertEquals(1, counting.made.get());
    }

    /** A released target is reused rather than a new one being made. */
    @Test
    public void aReleasedTargetIsRecycled() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(4);

        String first = slots.acquire("a");
        slots.release("a");
        String second = slots.acquire("b");

        assertEquals("b should have been handed a's old target", first, second);
        assertEquals("and nothing new allocated", 1, counting.made.get());
    }

    // ── The bound ───────────────────────────────────────────────────────────

    /**
     * <b>Allocation never exceeds the capacity</b>, however many nodes ask.
     *
     * <p>This is the whole reason the cap exists: a graph can hold hundreds of nodes, and one texture per
     * node is how a preview system turns into an out-of-memory report.</p>
     */
    @Test
    public void allocationIsBoundedByCapacity() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(3);

        for (int i = 0; i < 50; i++) slots.acquire("node" + i);

        assertEquals(3, counting.made.get());
        assertEquals(3, slots.liveCount());
    }

    /** Eviction takes the LEAST RECENTLY USED, not the oldest-inserted. */
    @Test
    public void evictionTakesTheLeastRecentlyUsed() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(2);

        String a = slots.acquire("a");
        slots.acquire("b");
        slots.acquire("a");   // a is now the most recent, so b is the victim
        slots.acquire("c");

        assertEquals("a must have survived", a, slots.peek("a"));
        assertNull("b was the least recently used", slots.peek("b"));
        assertNotNull(slots.peek("c"));
    }

    /**
     * <b>Everything acquired in one pass survives that pass.</b>
     *
     * <p>A frame renders its whole visible set before drawing any of it. If acquiring the last node
     * evicted the first, a full visible set would evict targets it is still in the middle of filling and
     * the thumbnails would churn every frame without ever settling.</p>
     */
    @Test
    public void afullVisibleSetSurvivesItsOwnPass() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(4);

        String[] held = new String[4];
        for (int i = 0; i < 4; i++) held[i] = slots.acquire("n" + i);

        for (int i = 0; i < 4; i++) {
            assertEquals("n" + i + " was evicted during its own frame", held[i], slots.peek("n" + i));
        }
    }

    /**
     * <b>{@code peek} must not count as a use.</b>
     *
     * <p>If it reordered the LRU, merely asking whether a preview exists would change which one is
     * evicted next — so the eviction order would depend on how often the editor happened to query it,
     * which is not a property anything should be able to observe.</p>
     */
    @Test
    public void peekingDoesNotCountAsUse() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(2);

        slots.acquire("a");
        slots.acquire("b");
        slots.peek("a");      // if this promoted a, b would be evicted below instead of a
        slots.acquire("c");

        assertNull("a was still the least recently USED, peek notwithstanding", slots.peek("a"));
        assertNotNull(slots.peek("b"));
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    /** The cull set is the render set — anything off screen gives its target back. */
    @Test
    public void retainOnlyReleasesEverythingNotVisible() {
        Counting counting = new Counting();
        CgPreviewSlots<String> slots = counting.pool(8);

        slots.acquire("a");
        slots.acquire("b");
        slots.acquire("c");
        slots.retainOnly(Set.of("b"));

        assertEquals(1, slots.liveCount());
        assertNotNull(slots.peek("b"));
        assertNull(slots.peek("a"));
        assertEquals("the other two are pooled, not freed", 2, slots.pooledCount());

        // And they are genuinely reusable, rather than merely forgotten.
        slots.acquire("d");
        slots.acquire("e");
        assertEquals(3, counting.made.get());
    }

    /** Releasing a node that holds nothing is a no-op, not a crash — the editor calls it freely. */
    @Test
    public void releasingAnUnknownNodeIsHarmless() {
        CgPreviewSlots<String> slots = new Counting().pool(2);

        slots.release("never-seen");

        assertEquals(0, slots.liveCount());
        assertEquals(0, slots.pooledCount());
    }
}
