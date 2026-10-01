package com.crystalgraphics.render.draw;

import org.junit.Test;

import static org.junit.Assert.*;

/** The lookback must never change what overlapping draws look like, and must join everything else it can. */
public class CgBatcherTest {

    private final CgBatcher batcher = new CgBatcher();

    private void add(int pipeline, int domain, float x0, float y0, float x1, float y1, int ref) {
        batcher.add(pipeline, 0, CgInstanceKind.QUAD, null, domain, x0, y0, x1, y1, 0, ref);
    }

    private String batches() {
        StringBuilder out = new StringBuilder();
        for (int b = 0; b < batcher.batches(); b++) {
            out.append('[');
            for (int i = batcher.batchStart(b); i < batcher.batchEnd(b); i++) out.append(batcher.ref(i));
            out.append(']');
        }
        return out.toString();
    }

    @Test
    public void joinsAcrossADrawItDoesNotOverlap() {
        batcher.reset(CgOrder.LOOKBACK);
        add(1, 0, 0, 0, 10, 10, 0);
        add(2, 0, 20, 0, 30, 10, 1);
        add(1, 0, 40, 0, 50, 10, 2);
        batcher.finish();
        assertEquals("[02][1]", batches());
    }

    @Test
    public void neverMovesADrawPastOneItOverlaps() {
        batcher.reset(CgOrder.LOOKBACK);
        add(1, 0, 0, 0, 10, 10, 0);
        add(2, 0, 35, 0, 45, 10, 1);
        add(1, 0, 40, 0, 50, 10, 2);
        batcher.finish();
        assertEquals("[0][1][2]", batches());
    }

    @Test
    public void touchingEdgesDoNotOverlap() {
        batcher.reset(CgOrder.LOOKBACK);
        add(1, 0, 0, 0, 10, 10, 0);
        add(2, 0, 10, 0, 20, 10, 1);
        add(1, 0, 20, 0, 30, 10, 2);
        batcher.finish();
        assertEquals("[02][1]", batches());
    }

    @Test
    public void neverJoinsAcrossADomain() {
        batcher.reset(CgOrder.LOOKBACK);
        add(1, 0, 0, 0, 10, 10, 0);
        add(2, 1, 100, 100, 110, 110, 1);   // far away, but in a domain that may move
        add(1, 0, 40, 0, 50, 10, 2);
        batcher.finish();
        assertEquals("[0][1][2]", batches());
    }

    @Test
    public void aDrawWithoutBoundsOverlapsEverything() {
        batcher.reset(CgOrder.LOOKBACK);
        add(1, 0, 0, 0, 10, 10, 0);
        add(2, 0, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 1);
        add(1, 0, 40, 0, 50, 10, 2);
        batcher.finish();
        assertEquals("[0][1][2]", batches());
    }

    @Test
    public void sortedOrdersStablyAndMergesNeighbours() {
        batcher.reset(CgOrder.SORTED);
        batcher.add(1, 0, CgInstanceKind.OBJECT, null, 0, 0, 0, 1, 1, 30, 0);
        batcher.add(2, 0, CgInstanceKind.OBJECT, null, 0, 0, 0, 1, 1, 10, 1);
        batcher.add(1, 0, CgInstanceKind.OBJECT, null, 0, 0, 0, 1, 1, 30, 2);
        batcher.add(2, 0, CgInstanceKind.OBJECT, null, 0, 0, 0, 1, 1, 10, 3);
        batcher.finish();
        assertEquals("[13][02]", batches());
    }
}
