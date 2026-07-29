package com.crystalgraphics.api.render;

import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers {@link CgViewFrustum} against both of its intended coordinate spaces — an orthographic
 * box (the UI/text case) and a perspective frustum (the world-geometry case).
 *
 * <p>The properties that matter for a visibility test are asymmetric: wrongly reporting something
 * visible costs a wasted draw, wrongly reporting it invisible makes geometry vanish. So the
 * conservative direction is exercised explicitly, as is the unconfigured case.</p>
 */
public class CgViewFrustumTest {

    /** Screen-space ortho matching the UI convention: origin top-left, y down. */
    private static Matrix4f ortho(float w, float h) {
        return new Matrix4f().ortho(0f, w, h, 0f, -1000f, 1000f);
    }

    private static CgViewFrustum orthoFrustum(float w, float h) {
        return new CgViewFrustum().set(ortho(w, h));
    }

    // ── Unconfigured ────────────────────────────────────────────────────

    @Test
    public void unconfiguredFrustumHidesNothing() {
        CgViewFrustum f = new CgViewFrustum();
        assertFalse(f.isInitialised());
        // A culler nobody configured must never remove geometry -- failing open is the only safe
        // default, since the alternative is a blank screen with no error.
        assertTrue(f.testRect(-9e9f, -9e9f, -8e9f, -8e9f));
        assertTrue(f.testAabb(new float[] {1e9f, 1e9f, 1e9f, 2e9f, 2e9f, 2e9f}));
        assertTrue(f.testSphere(1e9f, 1e9f, 1e9f, 1f));
    }

    @Test
    public void clearRevertsToHidingNothing() {
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertFalse(f.testRect(5000f, 5000f, 5100f, 5100f));
        f.clear();
        assertTrue(f.testRect(5000f, 5000f, 5100f, 5100f));
    }

    // ── Orthographic / UI case ──────────────────────────────────────────

    @Test
    public void rectInsideViewportIsVisible() {
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertTrue(f.testRect(10f, 10f, 100f, 40f));
        assertTrue("full-viewport rect", f.testRect(0f, 0f, 800f, 600f));
    }

    @Test
    public void rectFarBelowViewportIsCulled() {
        // The real case this was built for: text-3d parks two paragraphs at y=2112 in a 600px-tall
        // viewport and every one of their quads was still being built and submitted.
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertFalse(f.testRect(0f, 2112f, 500f, 2130f));
    }

    @Test
    public void rectOutsideOnEachSideIsCulled() {
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertFalse("left", f.testRect(-200f, 10f, -10f, 40f));
        assertFalse("right", f.testRect(900f, 10f, 1000f, 40f));
        assertFalse("above", f.testRect(10f, -200f, 100f, -10f));
        assertFalse("below", f.testRect(10f, 700f, 100f, 800f));
    }

    @Test
    public void rectStraddlingAnEdgeIsVisible() {
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertTrue("straddles left edge", f.testRect(-50f, 10f, 50f, 40f));
        assertTrue("straddles bottom edge", f.testRect(10f, 580f, 100f, 650f));
    }

    @Test
    public void rectLargerThanViewportIsVisible() {
        // Conservative direction: a box enclosing the whole frustum intersects every plane and
        // must be reported visible.
        CgViewFrustum f = orthoFrustum(800f, 600f);
        assertTrue(f.testRect(-5000f, -5000f, 5000f, 5000f));
    }

    @Test
    public void rectArgumentOrderIsTwoCornersNotPositionAndSize() {
        // Guards the documented convention -- passing (x, y, w, h) by mistake would make a
        // far-away rect look near the origin and defeat culling silently.
        CgViewFrustum f = orthoFrustum(800f, 600f);
        float x = 2000f, y = 2000f, w = 100f, h = 20f;
        assertFalse("as corners", f.testRect(x, y, x + w, y + h));
        assertTrue("as position+size would wrongly read as a rect near the origin",
                f.testRect(0f, 0f, w, h));
    }

    // ── Perspective / world case ────────────────────────────────────────

    private static CgViewFrustum perspectiveLookingDownNegZ() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 100f);
        Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
        return new CgViewFrustum().set(proj.mul(view));
    }

    @Test
    public void boxInFrontOfCameraIsVisible() {
        assertTrue(perspectiveLookingDownNegZ().testAabb(-1f, -1f, -10f, 1f, 1f, -8f));
    }

    @Test
    public void boxBehindCameraIsCulled() {
        assertFalse(perspectiveLookingDownNegZ().testAabb(-1f, -1f, 8f, 1f, 1f, 10f));
    }

    @Test
    public void boxBeyondFarPlaneIsCulled() {
        assertFalse(perspectiveLookingDownNegZ().testAabb(-1f, -1f, -500f, 1f, 1f, -400f));
    }

    @Test
    public void boxFarOffAxisIsCulled() {
        assertFalse(perspectiveLookingDownNegZ().testAabb(400f, -1f, -10f, 500f, 1f, -8f));
    }

    @Test
    public void sphereAgreesWithBoxOnClearCases() {
        CgViewFrustum f = perspectiveLookingDownNegZ();
        assertTrue(f.testSphere(0f, 0f, -9f, 1f));
        assertFalse(f.testSphere(0f, 0f, 9f, 1f));
        assertFalse(f.testSphere(450f, 0f, -9f, 1f));
    }

    // ── worldAabb contract ──────────────────────────────────────────────

    @Test
    public void aabbArrayMatchesCgRenderCommandLayout() {
        // [minX, minY, minZ, maxX, maxY, maxZ] -- same order CgRenderCommand.worldAabb uses and
        // CgRenderCommandQueue.submit validates, so a command's array can be passed straight in.
        CgViewFrustum f = perspectiveLookingDownNegZ();
        assertTrue(f.testAabb(new float[] {-1f, -1f, -10f, 1f, 1f, -8f}));
        assertFalse(f.testAabb(new float[] {-1f, -1f, 8f, 1f, 1f, 10f}));
    }

    @Test
    public void wrongLengthAabbThrows() {
        CgViewFrustum f = perspectiveLookingDownNegZ();
        for (float[] bad : new float[][] {new float[0], new float[3], new float[7]}) {
            try {
                f.testAabb(bad);
                fail("expected IllegalArgumentException for length " + bad.length);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void nullMatrixThrows() {
        try {
            new CgViewFrustum().set(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
