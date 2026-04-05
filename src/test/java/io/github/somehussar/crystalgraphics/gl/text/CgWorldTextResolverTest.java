package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.PoseStack;
import org.joml.Matrix4f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgWorldTextResolverTest {

    private WorldTextScaleResolver resolver;

    @Before
    public void setUp() {
        resolver = new WorldTextScaleResolver();
    }

    // ── Always MSDF ──────────────────────────────────────────────────

    @Test
    public void shouldUseMsdfAlwaysReturnsTrue() {
        assertTrue(resolver.shouldUseMsdf(16, false));
        assertTrue(resolver.shouldUseMsdf(32, false));
        assertTrue(resolver.shouldUseMsdf(64, true));
        assertTrue(resolver.shouldUseMsdf(1, false));
        assertTrue(resolver.shouldUseMsdf(256, true));
    }

    @Test
    public void shouldUseMsdfNeverReturnsFalse() {
        assertFalse("World resolver must never return false",
                !resolver.shouldUseMsdf(10, false));
    }

    // ── Default raster tier (no projected-size hint) ─────────────────

    @Test
    public void defaultRasterTierIsDoubleBaseSize() {
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(24 * WorldTextScaleResolver.DEFAULT_RASTER_MULTIPLIER, result);
    }

    @Test
    public void defaultRasterTierClampsToMax() {
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(200, stack.last(), -1);
        assertEquals(CgTextScaleResolver.MAX_EFFECTIVE_PX, result);
    }

    @Test
    public void defaultRasterTierSmallFont() {
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(8, stack.last(), -1);
        assertEquals(16, result);
    }

    // ── PoseStack scale does NOT affect world resolver ────────────────

    @Test
    public void poseScaleDoesNotAffectEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(3.0f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        // Without projected-size hint, result is baseTargetPx * 2, not baseTargetPx * poseScale
        assertEquals(48, result);
    }

    @Test
    public void poseTranslateDoesNotAffectEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().translate(100.0f, 200.0f, -500.0f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void poseRotateDoesNotAffectEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().rotateY((float) Math.toRadians(45.0));
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    // ── Projected-size hint ───────────────────────────────────────────

    @Test
    public void projectedSizeHintOverridesDefaultTier() {
        resolver.setProjectedSizeHint(96.0f);
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(96, result);
    }

    @Test
    public void projectedSizeHintClampsToMax() {
        resolver.setProjectedSizeHint(500.0f);
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(CgTextScaleResolver.MAX_EFFECTIVE_PX, result);
    }

    @Test
    public void projectedSizeHintClampsToMin() {
        resolver.setProjectedSizeHint(0.1f);
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(CgTextScaleResolver.MIN_EFFECTIVE_PX, result);
    }

    @Test
    public void projectedSizeHintRoundsCorrectly() {
        resolver.setProjectedSizeHint(47.6f);
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void clearProjectedSizeHintRevertsToDefault() {
        resolver.setProjectedSizeHint(96.0f);
        resolver.clearProjectedSizeHint();
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    // ── Hysteresis ────────────────────────────────────────────────────

    @Test
    public void hysteresisPreventsBoundaryOscillation() {
        PoseStack stack = new PoseStack();
        int first = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, first);

        // Small projected-size hint just above 49 but within deadband of 48
        resolver.setProjectedSizeHint(48.5f);
        int second = resolver.resolveEffectiveTargetPx(24, stack.last(), first);
        assertEquals(48, second);
    }

    @Test
    public void hysteresisAllowsChangeOutsideDeadband() {
        PoseStack stack = new PoseStack();
        int first = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, first);

        resolver.setProjectedSizeHint(49.0f);
        int second = resolver.resolveEffectiveTargetPx(24, stack.last(), first);
        assertEquals(49, second);
    }

}
