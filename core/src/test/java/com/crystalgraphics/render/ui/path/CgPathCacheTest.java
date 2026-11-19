package com.crystalgraphics.render.ui.path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.api.vector.CgVectorPath;

/** The publication rule: a cell is its claimer's until its build executes, and the atlas its maker's until cleared. */
public class CgPathCacheTest {

    private final Object a = new Object(), b = new Object();
    private final CgPathCache cache = CgPathCache.SHARED;

    @After
    public void forget() {
        cache.abandon(a);
        cache.abandon(b);
    }

    private static CgPathCache.Key key(float x) {
        return new CgPathCache.Key(CgVectorPath.builder().addCircle(x, 0, 4).build(), null, 1f, 1f);
    }

    @Test
    public void theFirstClaimerBuildsAndOthersWaitForTheAtlasClear() {
        CgPathCache.Entry cell = cache.claim(key(1), 10, 10, a);
        assertNotNull(cell);
        assertTrue(cell.takeBuild());
        assertFalse("built once", cell.takeBuild());
        if (cell.takeClear()) {
            // A new generation: nobody else claims in it before its clear executes
            assertNull(cache.claim(key(2), 10, 10, b));
            cache.ready(cell.generation());
        }
        assertNotNull(cache.claim(key(2), 10, 10, b));
    }

    @Test
    public void aPendingCellIsItsClaimersAlone() {
        CgPathCache.Entry cell = cache.claim(key(3), 10, 10, a);
        assertNotNull(cell);
        cell.takeBuild();
        if (cell.takeClear()) cache.ready(cell.generation());
        CgPathCache.Entry seen = cache.claim(key(3), 10, 10, b);
        assertSame(cell, seen);
        assertFalse("not ready to b", seen.ready());
        assertSame(a, seen.owner());
        cache.ready(cell);
        assertTrue(cache.find(key(3)).ready());
    }

    @Test
    public void abandonedClaimsGoSoNobodyWaits() {
        CgPathCache.Entry cell = cache.claim(key(4), 10, 10, a);
        assertNotNull(cell);
        cell.takeBuild();
        if (cell.takeClear()) cache.ready(cell.generation());
        cache.abandon(a);
        assertNull(cache.find(key(4)));
        CgPathCache.Entry again = cache.claim(key(4), 10, 10, b);
        assertNotNull(again);
        assertSame(b, again.owner());
    }
}
