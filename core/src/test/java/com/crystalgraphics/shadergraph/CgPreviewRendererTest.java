package com.crystalgraphics.shadergraph;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * P6.3.7 — what the renderer decides to draw, with the drawing left out.
 *
 * <h3>No GL here, on purpose</h3>
 * <p>Constructing a renderer allocates nothing on the GPU — the pool builds its format eagerly but its
 * targets lazily — so the whole scheduling side is reachable without a context. That is the half worth
 * testing: an actual draw either appears or does not, whereas a scheduling mistake produces a blank
 * thumbnail and <b>no error of any kind</b>.</p>
 */
public class CgPreviewRendererTest {

    /**
     * <b>A node that has never been drawn must get queued.</b>
     *
     * <p>The cold-start bug, and it shipped: {@code invalidateAll} marks everything already rendered,
     * which on the first frame is nothing — so the dirty set stayed empty, {@code renderPending}
     * early-returned every frame, and every preview in the gallery was blank. Nothing threw, because
     * nothing had failed; nothing had been requested.</p>
     */
    @Test
    public void aNeverRenderedVisibleNodeIsQueued() {
        CgPreviewRenderer renderer = new CgPreviewRenderer();
        assertFalse("nothing is visible yet", renderer.hasPending());

        renderer.setVisible(Set.of("a", "b"));

        assertTrue("a cold start must queue every visible node", renderer.hasPending());
    }

    /** Invalidating by name queues that node whether or not it has ever been drawn. */
    @Test
    public void explicitInvalidationQueues() {
        CgPreviewRenderer renderer = new CgPreviewRenderer();

        renderer.invalidate("a");

        assertTrue(renderer.hasPending());
    }

    /**
     * Going off screen drops the node entirely — it must not stay queued.
     *
     * <p>Otherwise the budget is spent every frame on nodes nobody is looking at, which is the exact
     * cost the visibility filter exists to remove.</p>
     */
    @Test
    public void anInvisibleNodeIsNotQueued() {
        CgPreviewRenderer renderer = new CgPreviewRenderer();
        renderer.setVisible(Set.of("a", "b"));

        renderer.setVisible(Set.of());

        assertFalse(renderer.hasPending());
    }

    /** A node still visible from the previous pass stays queued until it is actually drawn. */
    @Test
    public void aStillVisibleNodeStaysQueuedUntilDrawn() {
        CgPreviewRenderer renderer = new CgPreviewRenderer();
        renderer.setVisible(Set.of("a"));

        renderer.setVisible(Set.of("a"));

        assertTrue(renderer.hasPending());
    }
}
