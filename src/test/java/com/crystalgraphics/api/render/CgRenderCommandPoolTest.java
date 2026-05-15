package com.crystalgraphics.api.render;

import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.render.CgRenderCommandPool;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgRenderCommandPool}.
 *
 * Tests the acquire/release lifecycle, pool growth, and AABB NaN initialisation.
 */
public class CgRenderCommandPoolTest {

    @Test
    public void acquire_returnsNonNull() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        CgRenderCommand cmd = pool.acquire();
        assertNotNull(cmd);
    }

    @Test
    public void reset_initialisesAabbToNaN() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        CgRenderCommand cmd = pool.acquire();

        // All 6 worldAabb floats must be NaN after acquire (reset() initialises to NaN)
        for (int i = 0; i < 6; i++) {
            assertTrue("worldAabb[" + i + "] must be NaN after reset",
                       Float.isNaN(cmd.worldAabb[i]));
        }
    }

    @Test
    public void released_commandReused() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        CgRenderCommand cmd1 = pool.acquire();
        pool.release(cmd1);
        CgRenderCommand cmd2 = pool.acquire();
        // Pool must reuse the same object, not allocate a new one
        assertSame(cmd1, cmd2);
    }

    @Test
    public void acquireMany_noException() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        // Acquire more than initial capacity to trigger growth
        for (int i = 0; i < 512; i++) {
            CgRenderCommand cmd = pool.acquire();
            assertNotNull("slot " + i + " should not be null", cmd);
        }
    }

    @Test
    public void releaseAll_allowsReacquire() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        CgRenderCommand[] cmds = new CgRenderCommand[10];
        for (int i = 0; i < 10; i++) {
            cmds[i] = pool.acquire();
        }
        pool.releaseAll();
        // After releaseAll(), the first acquire should return one of the released objects
        CgRenderCommand reacquired = pool.acquire();
        assertNotNull(reacquired);
    }

    @Test
    public void grow_newSlotsHaveNonNullJomlMatrices() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        // Acquire 257 to force grow (initial capacity is 256)
        CgRenderCommand last = null;
        for (int i = 0; i < 257; i++) {
            last = pool.acquire();
        }
        assertNotNull("257th slot must not be null after grow()", last);
        assertNotNull("257th slot must have non-null modelMatrix", last.modelMatrix);
        assertNotNull("257th slot must have non-null normalMatrix", last.normalMatrix);
    }

    @Test
    public void release_idempotent() {
        CgRenderCommandPool pool = new CgRenderCommandPool();
        CgRenderCommand cmd = pool.acquire();
        // Double-release must not throw
        pool.release(cmd);
        pool.release(cmd); // second release — must be a no-op
    }
}
