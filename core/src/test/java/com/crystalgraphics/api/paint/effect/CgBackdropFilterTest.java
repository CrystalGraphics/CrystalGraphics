package com.crystalgraphics.api.paint.effect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** {@link CgBackdropFilter}'s chain as the glass pass draws it, and what it refuses. */
public class CgBackdropFilterTest {

    private static float[] matrix(CgFilter f) {
        return f.ops().get(0).values();
    }

    @Test
    public void aCssChainFoldsAroundItsBlur() {
        // backdrop-filter: grayscale(1) contrast(1.2) blur(8px)
        CgBackdropFilter.Plan p = CgBackdropFilter.of(CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f))
                .then(CgFilter.blur(8f))).plan();
        assertEquals(8f, p.blur(), 0f);
        assertArrayEquals(matrix(CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f))), p.before(), 1e-6f);
        assertEquals(0f, p.luminosity()[3], 0f);
        assertArrayEquals(CgBackdropFilter.Plan.IDENTITY, p.after(), 0f);
    }

    @Test
    public void acrylicIsAMatrixALuminosityAndAMatrix() {
        CgBackdropFilter.Plan p = CgBackdropFilter.ACRYLIC.plan();
        assertEquals(10f, p.blur(), 0f);
        assertArrayEquals(matrix(CgFilter.saturate(1.5f)), p.before(), 1e-6f);
        assertArrayEquals(new float[]{0x1F / 255f, 0x1F / 255f, 0x23 / 255f, 0.1f}, p.luminosity(), 1e-6f);
        assertArrayEquals(matrix(CgFilter.tint(0x401F1F23)), p.after(), 1e-6f);
    }

    @Test
    public void withoutALuminosityTheColourStepsAreOneMatrix() {
        CgBackdropFilter.Plan p = CgBackdropFilter.DEFAULT.plan();
        assertArrayEquals(matrix(CgFilter.saturate(1.35f).then(CgFilter.tint(0x662B2D30))), p.before(), 1e-6f);
        assertArrayEquals(CgBackdropFilter.Plan.IDENTITY, p.after(), 0f);
    }

    @Test
    public void blursAddAsGaussiansDo() {
        assertEquals(5f, CgBackdropFilter.of(CgFilter.blur(3f).then(CgFilter.blur(4f))).blur(), 1e-6f);
    }

    @Test
    public void aTintIsItsColourLaidOverByItsAlpha() {
        float[] m = matrix(CgFilter.tint(0x80FF0000));
        float a = 0x80 / 255f, k = 1f - a;
        // red channel: k * r + a * 1; green: k * g; alpha untouched
        assertArrayEquals(new float[]{k, 0, 0, 0, a, 0, k, 0, 0, 0, 0, 0, k, 0, 0, 0, 0, 0, 1, 0}, m, 1e-6f);
    }

    @Test
    public void whatABackdropRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> CgBackdropFilter.of(CgFilter.dropShadow(2, 2, 4, 0x80000000)));
        assertThrows(IllegalArgumentException.class, () -> CgBackdropFilter.of(CgFilter.blur(4f, 1f)));
        assertThrows(IllegalArgumentException.class,
                () -> CgBackdropFilter.of(CgFilter.luminosity(0xFF000000, 0.5f).then(CgFilter.blur(4f))));
        assertThrows(IllegalArgumentException.class, () -> CgBackdropFilter.of(CgFilter.luminosity(0xFF000000, 0.5f)
                .then(CgFilter.luminosity(0xFFFFFFFF, 0.5f))));
    }

    @Test
    public void scaledScalesTheLensAlone() {
        CgBackdropFilter world = CgBackdropFilter.LIQUID.scaled(1 / 32f);
        assertEquals(CgBackdropFilter.LIQUID.blur(), world.blur(), 0f);
        assertEquals(CgBackdropFilter.Lens.LIQUID.bezel() / 32f, world.lens().bezel(), 1e-6f);
        assertEquals(CgBackdropFilter.Lens.LIQUID.rimWidth() / 32f, world.lens().rimWidth(), 1e-6f);
    }

    @Test
    public void acrylicLeavesOutWhatChangesNothing() {
        assertEquals(CgFilter.NONE, CgBackdropFilter.acrylic(0f, 1f, 0f, 0x00FFFFFF).filter());
        assertEquals(CgFilter.blur(1f / 3f).then(CgFilter.saturate(1.2f)), CgBackdropFilter.PRISM.filter());
    }

    @Test
    public void editsChangeTheirOwnStepAndKeepTheRest() {
        CgBackdropFilter f = CgBackdropFilter.ACRYLIC.blur(6f).saturation(2f).tint(0x40FF0000);
        assertEquals(6f, f.blur(), 0f);
        assertEquals(2f, f.saturation(), 0f);
        assertEquals(0x40FF0000, f.tint());
        assertEquals(0.1f, f.luminosity(), 0f);
        // the luminosity moves to the new tint's colour
        assertArrayEquals(new float[]{1f, 0f, 0f, 0.1f}, f.plan().luminosity(), 1e-6f);
        assertEquals(CgBackdropFilter.ACRYLIC.lens(), f.lens());
        assertEquals(4, f.filter().steps().size());
    }

    @Test
    public void aMissingStepGoesWhereAcrylicPutsIt() {
        CgBackdropFilter f = CgBackdropFilter.of(CgFilter.NONE).tint(0x20FFFFFF).saturation(1.5f).blur(3f);
        assertEquals(CgFilter.blur(3f).then(CgFilter.saturate(1.5f)).then(CgFilter.tint(0x20FFFFFF)), f.filter());
        assertEquals(1f, CgBackdropFilter.of(CgFilter.NONE).saturation(), 0f);
        assertEquals(0, CgBackdropFilter.of(CgFilter.NONE).tint());
    }

    @Test
    public void aFilterKeepsItsStepsAsWritten() {
        CgFilter f = CgFilter.grayscale(0.5f).then(CgFilter.hueRotate(30f));
        assertEquals(2, f.steps().size());
        assertEquals(CgFilter.Fn.GRAYSCALE, f.steps().get(0).function());
        assertEquals(30f, f.steps().get(1).value(), 0f);
        // two colour functions run as one matrix
        assertEquals(1, f.ops().size());
        assertEquals(f, CgFilter.of(f.steps()));
    }
}
