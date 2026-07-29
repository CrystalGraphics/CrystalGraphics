package com.crystalgraphics.text.render;

import com.crystalgraphics.text.render.context.OrthographicScaleResolver;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link OrthographicScaleResolver#isAxisAligned} — the predicate that decides whether a
 * draw may use pre-rasterized bitmap glyphs or must be forced onto the distance-field tier.
 *
 * <p>The rule under test: a transform qualifies for bitmap only when it leaves the text quad's
 * XY plane mapped onto the screen axes. Translation, scale (including non-uniform and negative),
 * and quarter-turns qualify; any other rotation, any shear, and any out-of-plane tilt do not.</p>
 */
public class CgPoseAxisAlignmentTest {

    private static Matrix4f rotationZ(float degrees) {
        return new Matrix4f().rotateZ((float) Math.toRadians(degrees));
    }

    /** Shear along X: X basis stays on its axis, Y basis tilts toward it. */
    private static Matrix4f shearX(float k) {
        Matrix4f m = new Matrix4f();
        m.m10(k);
        return m;
    }

    // ── bitmap tier allowed ─────────────────────────────────────────────

    @Test
    public void identityIsAligned() {
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f()));
    }

    @Test
    public void translationIsAligned() {
        assertTrue(OrthographicScaleResolver.isAxisAligned(
                new Matrix4f().translate(137.5f, -42.25f, 8f)));
    }

    @Test
    public void scaleIsAligned() {
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(3f)));
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(0.25f)));
        // Extreme anisotropy: the per-axis relative tolerance must not let the short axis hide a
        // tilt under the long axis's slack.
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(1000f, 0.001f, 1f)));
    }

    @Test
    public void axisFlipsAreAligned() {
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(-1f, 1f, 1f)));
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(1f, -1f, 1f)));
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(-1f, -1f, 1f)));
    }

    @Test
    public void quarterTurnsAreTexelExact() {
        for (float deg : new float[] {0f, 90f, 180f, 270f, 360f, -90f, -180f, 720f}) {
            assertTrue("rotation of " + deg + " deg should keep the bitmap tier",
                    OrthographicScaleResolver.isAxisAligned(rotationZ(deg)));
        }
    }

    @Test
    public void quarterTurnComposedWithTranslateAndScaleIsAligned() {
        Matrix4f m = new Matrix4f()
                .translate(10f, 20f, 0f)
                .rotateZ((float) Math.toRadians(90))
                .scale(2f, 5f, 1f);
        assertTrue(OrthographicScaleResolver.isAxisAligned(m));
    }

    @Test
    public void degenerateBasisReportsAligned() {
        // Zero-area quads draw nothing; don't force a tier change on them.
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(0f, 1f, 1f)));
        assertTrue(OrthographicScaleResolver.isAxisAligned(new Matrix4f().scale(1f, 0f, 1f)));
    }

    @Test
    public void accumulatedFloatResidueStaysUnderTolerance() {
        Matrix4f m = new Matrix4f();
        for (int i = 0; i < 64; i++) {
            m.translate(1.7f, -0.3f, 0.11f).scale(1.01f, 0.99f, 1f);
        }
        assertTrue("accumulated translate/scale must not be mistaken for a rotation",
                OrthographicScaleResolver.isAxisAligned(m));
    }

    // ── distance-field tier forced ──────────────────────────────────────

    @Test
    public void offAxisRotationsAreNotAligned() {
        for (float deg : new float[] {1f, 15f, 30f, 45f, 89f, 91f, 135f, 179f, -45f, 12.5f}) {
            assertFalse("rotation of " + deg + " deg must force the distance-field tier",
                    OrthographicScaleResolver.isAxisAligned(rotationZ(deg)));
        }
    }

    @Test
    public void rotationNearTheThresholdIsStillCaught() {
        // sin(0.1 deg) ~ 1.7e-3, comfortably above AXIS_ALIGNMENT_EPSILON (1e-4).
        assertFalse(OrthographicScaleResolver.isAxisAligned(rotationZ(0.1f)));
    }

    @Test
    public void shearIsNotAligned() {
        assertFalse(OrthographicScaleResolver.isAxisAligned(shearX(0.5f)));
        assertFalse(OrthographicScaleResolver.isAxisAligned(shearX(-0.25f)));
        // Faux-italic-strength shear.
        assertFalse(OrthographicScaleResolver.isAxisAligned(shearX(0.21f)));
    }

    @Test
    public void shearSurvivesScalingAndIsNotMistakenForAnisotropy() {
        Matrix4f m = new Matrix4f().scale(4f, 4f, 1f);
        m.m10(m.m10() + 2f);
        assertFalse(OrthographicScaleResolver.isAxisAligned(m));
    }

    @Test
    public void quarterTurnPlusRotationIsNotAligned() {
        assertFalse(OrthographicScaleResolver.isAxisAligned(rotationZ(90f + 30f)));
    }

    @Test
    public void outOfPlaneTiltIsNotAligned() {
        assertFalse(OrthographicScaleResolver.isAxisAligned(
                new Matrix4f().rotateX((float) Math.toRadians(30))));
        assertFalse(OrthographicScaleResolver.isAxisAligned(
                new Matrix4f().rotateY((float) Math.toRadians(30))));
    }
}
