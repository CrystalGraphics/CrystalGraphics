package com.crystalgraphics.render.draw;

/**
 * How a pass may reorder its draws into batches.
 *
 * <pre>{@code
 * recording.raster(target, CgLoad.load(), constants, uiState, CgOrder.LOOKBACK);   // the UI, anything 2D
 * recording.raster(target, CgLoad.load(), constants, worldState, CgOrder.SORTED);  // the world, keys written per draw
 * }</pre>
 */
public enum CgOrder {

    /**
     * Painter's order. A draw joins the most recent batch with its key unless a batch after that one overlaps it,
     * so overlapping draws are never reordered. WebRender's alpha batching.
     */
    LOOKBACK,

    /**
     * By {@link CgDrawChunk#sortKey}, ascending and stable, with adjacent equal batches merged: an opaque pass writes
     * keys front to back, a transparent one back to front.
     */
    SORTED
}
