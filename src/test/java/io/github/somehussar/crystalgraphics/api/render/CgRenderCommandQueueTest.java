package io.github.somehussar.crystalgraphics.api.render;

import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.render.CgRenderCommandPool;
import com.crystalgraphics.api.render.CgRenderCommandQueue;
import org.joml.Matrix4f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgRenderCommandQueue}.
 *
 * <p>Tests cover: OVERLAY rejection, AABB validation, normalMatrix derivation,
 * cameraDepth computation, sort ordering, pass-flag assignment, and releaseAll.</p>
 *
 * <p>No GL calls are made — mesh and material fields remain null (submit() does not
 * invoke any method on them during the validation/sort logic).</p>
 */
public class CgRenderCommandQueueTest {

    private CgRenderCommandPool pool;
    private CgRenderCommandQueue queue;
    private CgFrameData fd;

    @Before
    public void setUp() {
        pool = new CgRenderCommandPool();
        fd   = new CgFrameData();
        // Identity view: camera at origin, looking down -Z
        fd.viewMatrix.identity();
        fd.deriveFromViewMatrix();
        fd.farPlane = 1000f;

        queue = new CgRenderCommandQueue(pool);
        queue.setFrameData(fd);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Acquires a command and sets a valid symmetric AABB centred at (cx, cy, cz)
     * with half-extent {@code r}, using the given queue slot.
     */
    private CgRenderCommand makeCmd(int slot, float cx, float cy, float cz, float r) {
        CgRenderCommand cmd = pool.acquire();
        cmd.queueSlot     = slot;
        cmd.renderPriority = 0;
        cmd.worldAabb[0]  = cx - r;
        cmd.worldAabb[1]  = cy - r;
        cmd.worldAabb[2]  = cz - r;
        cmd.worldAabb[3]  = cx + r;
        cmd.worldAabb[4]  = cy + r;
        cmd.worldAabb[5]  = cz + r;
        // mesh and material left null — submit() guards both with null-checks
        return cmd;
    }

    // ── OVERLAY rejection ─────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void submit_overlaySlot_throwsIllegalArgument() {
        CgRenderCommand cmd = makeCmd(CgRenderQueue.OVERLAY, 0, 0, -10, 1);
        queue.submit(cmd);
    }

    @Test
    public void submit_overlaySlot_releasesCommandToPool() {
        // Acquire a command slot; submit with OVERLAY should release it back
        CgRenderCommand cmd = makeCmd(CgRenderQueue.OVERLAY, 0, 0, -10, 1);
        try {
            queue.submit(cmd);
        } catch (IllegalArgumentException e) {
            // expected
        }
        // The command should be available again — re-acquire it from pool
        assertFalse("Command should have been released back to pool after OVERLAY rejection",
                    cmd.acquired);
    }

    // ── AABB validation ───────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void submit_nanAabb_throwsIllegalArgument() {
        CgRenderCommand cmd = pool.acquire();
        cmd.queueSlot = CgRenderQueue.GEOMETRY;
        // worldAabb is NaN from reset() — do NOT set it
        queue.submit(cmd);
    }

    @Test
    public void submit_nanAabb_releasesCommand() {
        CgRenderCommand cmd = pool.acquire();
        cmd.queueSlot = CgRenderQueue.GEOMETRY;
        try {
            queue.submit(cmd);
        } catch (IllegalArgumentException e) {
            // expected
        }
        assertFalse("Command should have been released back to pool after NaN AABB rejection",
                    cmd.acquired);
    }

    @Test(expected = IllegalArgumentException.class)
    public void submit_invertedAabb_throwsIllegalArgument() {
        CgRenderCommand cmd = pool.acquire();
        cmd.queueSlot    = CgRenderQueue.GEOMETRY;
        cmd.worldAabb[0] =  5f;  // minX
        cmd.worldAabb[3] = -5f;  // maxX < minX — inverted!
        cmd.worldAabb[1] = -1f; cmd.worldAabb[4] = 1f;
        cmd.worldAabb[2] = -1f; cmd.worldAabb[5] = 1f;
        queue.submit(cmd);
    }

    // ── normalMatrix derivation ───────────────────────────────────────────────

    @Test
    public void submit_normalMatrix_derivedFromModelMatrix() {
        // non-uniform scale 2,1,1 → normalMatrix != identity
        CgRenderCommand cmd = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -50, 1);
        cmd.modelMatrix.scaling(2f, 1f, 1f);

        queue.submit(cmd);

        // Expected normal matrix for scale(2,1,1) is scale(0.5,1,1) in upper-left 3x3
        Matrix4f expected = new Matrix4f();
        cmd.modelMatrix.normal(expected);  // same JOML call as submit() uses

        assertEquals("normalMatrix m00 should match JOML normal()",
                     expected.m00(), cmd.normalMatrix.m00(), 1e-5f);
        assertEquals("normalMatrix m11 should match JOML normal()",
                     expected.m11(), cmd.normalMatrix.m11(), 1e-5f);
        // If it were identity, m00 would be 1.0; for scale(2,1,1) it should not be 1.0
        assertNotEquals("normalMatrix should differ from identity for non-uniform scale",
                        1.0f, cmd.normalMatrix.m00(), 1e-5f);
    }

    // ── cameraDepth computation ───────────────────────────────────────────────

    @Test
    public void submit_cameraDepth_computedFromAabbCenter() {
        // Camera at origin looking down -Z (fc already set to identity view).
        // AABB centred at z=-30 → cameraDepth = dot((0,0,-30)-(0,0,0), (0,0,-1)) = 30.
        CgRenderCommand cmd = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -30, 1);
        queue.submit(cmd);
        assertEquals("cameraDepth for AABB at z=-30 should be ~30", 30f, cmd.cameraDepth, 0.1f);
    }

    @Test
    public void submit_cameraDepth_clampedToZero_behindCamera() {
        // AABB centred behind camera (z=+50 when looking down -Z → negative dot → clamp to 0)
        CgRenderCommand cmd = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, 50, 1);
        queue.submit(cmd);
        assertEquals("cameraDepth should be clamped to 0 for objects behind camera",
                     0f, cmd.cameraDepth, 1e-5f);
    }

    // ── Sort ordering ─────────────────────────────────────────────────────────

    @Test
    public void sort_opaqueBeforeTransparent_inFilteredViews() {
        // Submit transparent first, then opaque — after sort they go into separate views
        CgRenderCommand tCmd = makeCmd(CgRenderQueue.TRANSPARENT, 0, 0, -50, 1);
        CgRenderCommand oCmd = makeCmd(CgRenderQueue.GEOMETRY,    0, 0, -100, 1);
        queue.submit(tCmd);
        queue.submit(oCmd);
        queue.sort();

        assertEquals("Should have exactly 1 opaque command",       1, queue.getOpaqueCount());
        assertEquals("Should have exactly 1 transparent command",  1, queue.getTransparentCount());
        assertEquals("Opaque command should be GEOMETRY",
                     CgRenderQueue.GEOMETRY, queue.getSortedOpaque()[0].queueSlot);
        assertEquals("Transparent command should be TRANSPARENT",
                     CgRenderQueue.TRANSPARENT, queue.getSortedTransparent()[0].queueSlot);
    }

    @Test
    public void sort_opaque_frontToBack() {
        // Near object at z=-10, far object at z=-200 → near should sort FIRST (front-to-back)
        CgRenderCommand near = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -10,  1);
        CgRenderCommand far  = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -200, 1);
        queue.submit(near);
        queue.submit(far);
        queue.sort();

        assertEquals(2, queue.getOpaqueCount());
        CgRenderCommand[] sorted = queue.getSortedOpaque();
        assertTrue("Near object should have smaller cameraDepth",
                   sorted[0].cameraDepth < sorted[1].cameraDepth);
    }

    @Test
    public void sort_transparent_backToFront() {
        // Near object at z=-10, far at z=-200 → far should sort FIRST (back-to-front)
        CgRenderCommand near = makeCmd(CgRenderQueue.TRANSPARENT, 0, 0, -10,  1);
        CgRenderCommand far  = makeCmd(CgRenderQueue.TRANSPARENT, 0, 0, -200, 1);
        queue.submit(near);
        queue.submit(far);
        queue.sort();

        assertEquals(2, queue.getTransparentCount());
        CgRenderCommand[] sorted = queue.getSortedTransparent();
        assertTrue("Far transparent object should sort first (back-to-front: larger depth first)",
                   sorted[0].cameraDepth > sorted[1].cameraDepth);
    }

    // ── releaseAll ────────────────────────────────────────────────────────────

    @Test
    public void releaseAll_resetsCounts() {
        queue.submit(makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -10, 1));
        queue.submit(makeCmd(CgRenderQueue.TRANSPARENT, 0, 0, -20, 1));
        queue.sort();
        assertEquals(1, queue.getOpaqueCount());

        queue.releaseAll();
        assertEquals("cmdCount should be 0 after releaseAll",       0, queue.getCommandCount());
        assertEquals("opaqueCount should be 0 after releaseAll",    0, queue.getOpaqueCount());
        assertEquals("transparentCount should be 0 after releaseAll", 0, queue.getTransparentCount());
    }

    // ── passFlags ─────────────────────────────────────────────────────────────

    @Test
    public void passFlags_geometryIsOpaque() {
        CgRenderCommand cmd = makeCmd(CgRenderQueue.GEOMETRY, 0, 0, -10, 1);
        queue.submit(cmd);
        assertTrue("GEOMETRY should have FLAG_OPAQUE set",
                   (cmd.passFlags & CgRenderCommand.FLAG_OPAQUE) != 0);
        assertEquals("GEOMETRY should not have FLAG_TRANSPARENT",
                     0, cmd.passFlags & CgRenderCommand.FLAG_TRANSPARENT);
    }

    @Test
    public void passFlags_alphaTestFlag() {
        CgRenderCommand cmd = makeCmd(CgRenderQueue.ALPHA_TEST, 0, 0, -10, 1);
        queue.submit(cmd);
        assertTrue("ALPHA_TEST should have FLAG_ALPHA_TEST set",
                   (cmd.passFlags & CgRenderCommand.FLAG_ALPHA_TEST) != 0);
    }

    @Test
    public void passFlags_transparentFlag() {
        CgRenderCommand cmd = makeCmd(CgRenderQueue.TRANSPARENT, 0, 0, -10, 1);
        queue.submit(cmd);
        assertTrue("TRANSPARENT should have FLAG_TRANSPARENT set",
                   (cmd.passFlags & CgRenderCommand.FLAG_TRANSPARENT) != 0);
    }

    // ── Large batch — no exception ────────────────────────────────────────────

    @Test
    public void largeBatch_noException() {
        for (int i = 0; i < 400; i++) {
            float z = -(i + 1) * 2f;
            CgRenderCommand cmd = makeCmd(
                (i % 2 == 0) ? CgRenderQueue.GEOMETRY : CgRenderQueue.TRANSPARENT,
                0, 0, z, 1);
            queue.submit(cmd);
        }
        queue.sort();
        assertEquals("Total commands should be 400", 400, queue.getCommandCount());
    }
}
