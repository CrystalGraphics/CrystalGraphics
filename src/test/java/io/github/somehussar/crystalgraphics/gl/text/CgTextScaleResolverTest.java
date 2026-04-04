package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.PoseStack;
import org.joml.Matrix4f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgTextScaleResolverTest {

    private OrthographicScaleResolver resolver;

    @Before
    public void setUp() {
        resolver = new OrthographicScaleResolver();
    }

    // ── Identity scale ────────────────────────────────────────────────

    @Test
    public void identityPoseReturnsBaseTargetPx() {
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(24, result);
    }

    @Test
    public void identityPoseReturnsBaseTargetPxLarge() {
        PoseStack stack = new PoseStack();
        int result = resolver.resolveEffectiveTargetPx(48, stack.last(), -1);
        assertEquals(48, result);
    }

    // ── Uniform scale ─────────────────────────────────────────────────

    @Test
    public void uniformScale2xDoublesEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(2.0f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void uniformScale3xTriplesEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(3.0f);
        int result = resolver.resolveEffectiveTargetPx(16, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void uniformScaleHalfHalvesEffectiveSize() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(0.5f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(12, result);
    }

    // ── Non-uniform scale (max rule) ──────────────────────────────────

    @Test
    public void nonUniformScaleUsesMaxAxis() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(3.0f, 1.0f, 1.0f);
        int result = resolver.resolveEffectiveTargetPx(16, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void nonUniformScaleYDominant() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(1.0f, 4.0f, 1.0f);
        int result = resolver.resolveEffectiveTargetPx(10, stack.last(), -1);
        assertEquals(40, result);
    }

    // ── Negative scale (absolute value) ───────────────────────────────

    @Test
    public void negativeScaleUsesAbsoluteValue() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(-2.0f, -2.0f, 1.0f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(48, result);
    }

    @Test
    public void mixedNegativeScaleUsesAbsMax() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(-3.0f, 1.0f, 1.0f);
        int result = resolver.resolveEffectiveTargetPx(10, stack.last(), -1);
        assertEquals(30, result);
    }

    // ── Clamping ──────────────────────────────────────────────────────

    @Test
    public void effectiveSizeClampedToMin() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(0.01f);
        int result = resolver.resolveEffectiveTargetPx(1, stack.last(), -1);
        assertEquals(CgTextScaleResolver.MIN_EFFECTIVE_PX, result);
    }

    @Test
    public void effectiveSizeClampedToMax() {
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(100.0f);
        int result = resolver.resolveEffectiveTargetPx(100, stack.last(), -1);
        assertEquals(CgTextScaleResolver.MAX_EFFECTIVE_PX, result);
    }

    // ── Fractional scale rounding ─────────────────────────────────────

    @Test
    public void fractionalScaleRoundsToNearest() {
        PoseStack stack = new PoseStack();
        // 24 * 1.3 = 31.2 → rounds to 31
        stack.last().pose().scale(1.3f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(31, result);
    }

    @Test
    public void fractionalScaleRoundsUp() {
        PoseStack stack = new PoseStack();
        // 24 * 1.4 = 33.6 → rounds to 34
        stack.last().pose().scale(1.4f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(34, result);
    }

    // ── Hysteresis ────────────────────────────────────────────────────

    @Test
    public void hysteresisPreventsBoundaryOscillation() {
        // First call establishes baseline at 24
        PoseStack stack = new PoseStack();
        int first = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(24, first);

        // Scale slightly above 25 boundary but within deadband (24 + 0.75 = 24.75)
        // 24 * 1.03 = 24.72 → rounds to 25, but raw < 24.75 → hysteresis retains 24
        stack.last().pose().identity().scale(1.03f);
        int second = resolver.resolveEffectiveTargetPx(24, stack.last(), first);
        assertEquals(24, second);
    }

    @Test
    public void hysteresisAllowsChangeOutsideDeadband() {
        // First call establishes baseline at 24
        PoseStack stack = new PoseStack();
        int first = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);

        // Scale clearly above deadband: 24 * 1.04 = 24.96 > 24.75
        stack.last().pose().identity().scale(1.04f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), first);
        assertEquals(25, result);
    }

    @Test
    public void hysteresisDownwardRetainsPreviousInDeadband() {
        // Establish at 30
        PoseStack stack = new PoseStack();
        stack.last().pose().scale(1.25f);
        int first = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);

        // Scale to just below: 24 * 1.21 = 29.04 → rounds to 29
        // But raw 29.04 > 30 - 0.75 = 29.25? No, 29.04 < 29.25, so change allowed
        // Actually 29.04 < 29.25 so it exits the deadband → should go to 29
        stack.last().pose().identity().scale(1.21f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), first);
        assertEquals(29, result);
    }

    @Test
    public void noPreviousValueMeansNoHysteresis() {
        PoseStack stack = new PoseStack();
        resolver.resolveEffectiveTargetPx(24, stack.last(), -1);

        stack.last().pose().identity().scale(1.03f);
        int result = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);
        assertEquals(25, result);
    }

    // ── Backend (MSDF) hysteresis ─────────────────────────────────────

    @Test
    public void msdfEntersAt33() {
        assertTrue(resolver.shouldUseMsdf(33, false));
    }

    @Test
    public void msdfExitsAt31() {
        assertFalse(resolver.shouldUseMsdf(31, true));
    }

    @Test
    public void msdfRetainsPreviousAt32() {
        assertTrue(resolver.shouldUseMsdf(32, true));
        assertFalse(resolver.shouldUseMsdf(32, false));
    }

    @Test
    public void msdfEntersAbove33() {
        assertTrue(resolver.shouldUseMsdf(48, false));
    }

    @Test
    public void msdfExitsBelow31() {
        assertFalse(resolver.shouldUseMsdf(16, true));
    }

    // ── extractMaxScale unit tests ────────────────────────────────────

    @Test
    public void extractMaxScaleIdentity() {
        Matrix4f identity = new Matrix4f();
        float maxScale = OrthographicScaleResolver.extractMaxScale(identity);
        assertEquals(1.0f, maxScale, 0.001f);
    }

    @Test
    public void extractMaxScaleUniform2x() {
        Matrix4f m = new Matrix4f().scale(2.0f);
        float maxScale = OrthographicScaleResolver.extractMaxScale(m);
        assertEquals(2.0f, maxScale, 0.001f);
    }

    @Test
    public void extractMaxScaleNonUniform() {
        Matrix4f m = new Matrix4f().scale(3.0f, 1.0f, 1.0f);
        float maxScale = OrthographicScaleResolver.extractMaxScale(m);
        assertEquals(3.0f, maxScale, 0.001f);
    }

    @Test
    public void extractMaxScaleNegative() {
        Matrix4f m = new Matrix4f().scale(-2.0f, 1.0f, 1.0f);
        float maxScale = OrthographicScaleResolver.extractMaxScale(m);
        assertEquals(2.0f, maxScale, 0.001f);
    }

    @Test
    public void extractMaxScaleWithTranslation() {
        Matrix4f m = new Matrix4f().translate(100.0f, 200.0f, 0.0f).scale(1.5f);
        float maxScale = OrthographicScaleResolver.extractMaxScale(m);
        assertEquals(1.5f, maxScale, 0.001f);
    }

    // ── Resolver strategy interface contract ──────────────────────────

    @Test
    public void orthographicResolverIsDefaultInstance() {
        assertNotNull(CgTextScaleResolver.ORTHOGRAPHIC);
        assertTrue(CgTextScaleResolver.ORTHOGRAPHIC instanceof OrthographicScaleResolver);
    }

    @Test
    public void resolverInterfaceIsStrategy() {
        // Verify the interface defines the extension seam
        CgTextScaleResolver resolver = CgTextScaleResolver.ORTHOGRAPHIC;
        assertNotNull(resolver);
        // A future PerspectiveScaleResolver would implement this same interface
    }
}
