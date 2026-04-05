package io.github.somehussar.crystalgraphics.gl.text;

import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgProjectedSizeEstimatorTest {

    // ── Orthographic projection (no perspective distortion) ──────────

    @Test
    public void orthographicProjectionGivesStableEstimate() {
        Matrix4f modelView = new Matrix4f();
        Matrix4f projection = new Matrix4f().ortho(0, 800, 600, 0, -1, 1);
        float result = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 800, 600, 24);
        assertTrue("Orthographic projection should give positive estimate", result > 0.0f);
    }

    // ── Perspective projection ────────────────────────────────────────

    @Test
    public void closerObjectProjectsLarger() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);

        Matrix4f nearView = new Matrix4f().translate(0, 0, -50.0f);
        Matrix4f farView = new Matrix4f().translate(0, 0, -200.0f);

        float nearPx = ProjectedSizeEstimator.estimateScreenPx(
                nearView, projection, 800, 600, 24);
        float farPx = ProjectedSizeEstimator.estimateScreenPx(
                farView, projection, 800, 600, 24);

        assertTrue("Near text should appear larger than far text",
                nearPx > farPx);
        assertTrue("Both should be positive", nearPx > 0 && farPx > 0);
    }

    @Test
    public void largerBaseSizeProjectsLarger() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        Matrix4f modelView = new Matrix4f().translate(0, 0, -100.0f);

        float small = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 800, 600, 12);
        float large = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 800, 600, 48);

        assertTrue("Larger base size should project larger", large > small);
    }

    @Test
    public void widerViewportProducesLargerProjection() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 1.0f, 0.1f, 1000.0f);
        Matrix4f modelView = new Matrix4f().translate(0, 0, -100.0f);

        float narrow = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 400, 400, 24);
        float wide = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 1600, 1600, 24);

        assertTrue("Wider viewport should produce larger pixel projection",
                wide > narrow);
    }

    // ── Behind camera ────────────────────────────────────────────────

    @Test
    public void behindCameraReturnsNegative() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        Matrix4f modelView = new Matrix4f().translate(0, 0, 50.0f);

        float result = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 800, 600, 24);
        assertTrue("Behind camera should return -1", result < 0.0f);
    }

    // ── Layout invariance contract ────────────────────────────────────

    @Test
    public void estimateDoesNotMutateInputMatrices() {
        Matrix4f modelView = new Matrix4f().translate(10, 20, -100.0f);
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);

        Matrix4f mvCopy = new Matrix4f(modelView);
        Matrix4f projCopy = new Matrix4f(projection);

        ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, 800, 600, 24);

        assertEquals("ModelView should not be mutated",
                mvCopy, modelView);
        assertEquals("Projection should not be mutated",
                projCopy, projection);
    }
}
