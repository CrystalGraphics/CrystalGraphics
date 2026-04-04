package io.github.somehussar.crystalgraphics.gl.text;

import org.lwjgl.BufferUtils;
import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.*;

public class CgTextRenderContextTest {

    @Test
    public void orthographicFactoryCreatesValidContext() {
        CgTextRenderContext ctx = CgTextRenderContext.orthographic(800, 600);
        assertNotNull(ctx.getProjectionBuffer());
        assertNotNull(ctx.getScaleResolver());
        assertEquals(CgTextScaleResolver.ORTHOGRAPHIC, ctx.getScaleResolver());
    }

    @Test
    public void projectionBufferContains16Floats() {
        CgTextRenderContext ctx = CgTextRenderContext.orthographic(800, 600);
        FloatBuffer buf = ctx.getProjectionBuffer();
        assertEquals(0, buf.position());
        assertTrue(buf.remaining() >= 16);
    }

    @Test
    public void updateOrthoModifiesProjection() {
        CgTextRenderContext ctx = CgTextRenderContext.orthographic(800, 600);
        FloatBuffer initial = ctx.getProjectionBuffer();
        float sx800 = initial.get(0);

        ctx.updateOrtho(1600, 900);
        FloatBuffer updated = ctx.getProjectionBuffer();
        float sx1600 = updated.get(0);

        // Wider viewport → smaller sx scale factor
        assertNotEquals(sx800, sx1600, 0.0001f);
    }

    @Test
    public void constructorWithExplicitResolver() {
        FloatBuffer proj = BufferUtils.createFloatBuffer(16);
        CgTextRenderContext.populateOrthoMatrix(proj, 640, 480);
        CgTextScaleResolver resolver = CgTextScaleResolver.ORTHOGRAPHIC;
        CgTextRenderContext ctx = new CgTextRenderContext(proj, resolver);

        assertSame(resolver, ctx.getScaleResolver());
        assertSame(proj, ctx.getProjectionBuffer());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullProjectionThrows() {
        new CgTextRenderContext(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullResolverThrows() {
        FloatBuffer proj = BufferUtils.createFloatBuffer(16);
        new CgTextRenderContext(proj, null);
    }

    @Test
    public void defaultResolverIsOrthographic() {
        FloatBuffer proj = BufferUtils.createFloatBuffer(16);
        CgTextRenderContext.populateOrthoMatrix(proj, 800, 600);
        CgTextRenderContext ctx = new CgTextRenderContext(proj);
        assertEquals(CgTextScaleResolver.ORTHOGRAPHIC, ctx.getScaleResolver());
    }

    @Test
    public void orthoMatrixHasCorrectTopLeftOrigin() {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        CgTextRenderContext.populateOrthoMatrix(buf, 800, 600);

        // For top-left origin ortho (left=0, right=800, top=0, bottom=600):
        // sx = 2/800 = 0.0025
        // sy = 2/(0-600) = -0.003333...
        // tx = -1.0
        // ty = 1.0
        assertEquals(2.0f / 800.0f, buf.get(0), 0.0001f);  // sx
        assertEquals(2.0f / -600.0f, buf.get(5), 0.0001f);  // sy
        assertEquals(-1.0f, buf.get(12), 0.0001f);           // tx
        assertEquals(1.0f, buf.get(13), 0.0001f);            // ty
    }

    @Test
    public void hysteresisStateIsTrackedPerFontKey() {
        FloatBuffer proj = BufferUtils.createFloatBuffer(16);
        CgTextRenderContext.populateOrthoMatrix(proj, 800, 600);
        CgTextRenderContext ctx = new CgTextRenderContext(proj);

        io.github.somehussar.crystalgraphics.api.font.CgFontKey a =
                new io.github.somehussar.crystalgraphics.api.font.CgFontKey("a.ttf",
                        io.github.somehussar.crystalgraphics.api.font.CgFontStyle.REGULAR, 24);
        io.github.somehussar.crystalgraphics.api.font.CgFontKey b =
                new io.github.somehussar.crystalgraphics.api.font.CgFontKey("b.ttf",
                        io.github.somehussar.crystalgraphics.api.font.CgFontStyle.REGULAR, 24);

        assertEquals(-1, ctx.getPreviousEffectiveTargetPx(a));
        assertFalse(ctx.wasMsdf(a));

        ctx.setPreviousEffectiveTargetPx(a, 48);
        ctx.setWasMsdf(a, true);

        assertEquals(48, ctx.getPreviousEffectiveTargetPx(a));
        assertTrue(ctx.wasMsdf(a));
        assertEquals(-1, ctx.getPreviousEffectiveTargetPx(b));
        assertFalse(ctx.wasMsdf(b));
    }
}
