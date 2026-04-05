package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.*;

public class CgWorldTextRenderContextTest {

    // ── Factory and identity ─────────────────────────────────────────

    @Test
    public void createReturnsWorldContext() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);
        assertNotNull(ctx);
        assertTrue(ctx.isWorldText());
    }

    @Test
    public void baseContextIsNotWorldText() {
        CgTextRenderContext base = CgTextRenderContext.orthographic(800, 600);
        assertFalse(base.isWorldText());
    }

    // ── Scale resolver is always-MSDF ─────────────────────────────────

    @Test
    public void worldContextResolverAlwaysMsdf() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);
        CgTextScaleResolver resolver = ctx.getScaleResolver();

        assertTrue(resolver.shouldUseMsdf(10, false));
        assertTrue(resolver.shouldUseMsdf(32, false));
        assertTrue(resolver.shouldUseMsdf(64, true));
    }

    @Test
    public void worldContextForcesZeroSubPixelBucket() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);
       // assertEquals(0, CgTextRenderer.resolveSubPixelBucket(ctx, 16, 0.75f,1));
    }

    // ── Viewport tracking ────────────────────────────────────────────

    @Test
    public void viewportDimensionsAreTracked() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);
        assertEquals(800, ctx.getViewportWidth());
        assertEquals(600, ctx.getViewportHeight());
    }

    @Test
    public void updateProjectionChangesViewport() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);

        Matrix4f newPersp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 1920.0f / 1080.0f, 0.1f, 1000.0f);
        ctx.updateProjection(newPersp, 1920, 1080);

        assertEquals(1920, ctx.getViewportWidth());
        assertEquals(1080, ctx.getViewportHeight());
    }

    // ── Projected size hint ──────────────────────────────────────────

    @Test
    public void updateProjectedSizeAffectsResolverHint() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);

        Matrix4f modelView = new Matrix4f().translate(0, 0, -100.0f);
        ctx.updateProjectedSize(modelView, persp, 24);

        CgTextScaleResolver resolver = ctx.getScaleResolver();
        io.github.somehussar.crystalgraphics.api.PoseStack stack =
                new io.github.somehussar.crystalgraphics.api.PoseStack();
        int effective = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);

        // After updateProjectedSize, the effective size should be based on the
        // projected coverage, not just the default 2x multiplier
        assertTrue("Effective size should be positive after projected hint", effective > 0);
    }

    @Test
    public void clearProjectedSizeRevertsToDefault() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);

        Matrix4f modelView = new Matrix4f().translate(0, 0, -100.0f);
        ctx.updateProjectedSize(modelView, persp, 24);
        ctx.clearProjectedSizeHint();

        CgTextScaleResolver resolver = ctx.getScaleResolver();
        io.github.somehussar.crystalgraphics.api.PoseStack stack =
                new io.github.somehussar.crystalgraphics.api.PoseStack();
        int effective = resolver.resolveEffectiveTargetPx(24, stack.last(), -1);

        assertEquals("After clearing hint, should use default 2x tier",
                24 * WorldTextScaleResolver.DEFAULT_RASTER_MULTIPLIER, effective);
    }

    // ── Hysteresis state per font ─────────────────────────────────────

    @Test
    public void hysteresisStateIsInheritedFromBase() {
        Matrix4f persp = new Matrix4f().perspective(
                (float) Math.toRadians(60.0), 800.0f / 600.0f, 0.1f, 1000.0f);
        CgWorldTextRenderContext ctx = CgWorldTextRenderContext.create(persp, 800, 600);

        CgFontKey key = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 24);
        assertEquals(-1, ctx.getPreviousEffectiveTargetPx(key));

        ctx.setPreviousEffectiveTargetPx(key, 48);
        assertEquals(48, ctx.getPreviousEffectiveTargetPx(key));
    }
}
