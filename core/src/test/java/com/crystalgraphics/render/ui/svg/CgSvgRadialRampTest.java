package com.crystalgraphics.render.ui.svg;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A radial gradient is carried as a per-triangle <b>ramp</b>, and that ramp is exact at every vertex.
 *
 * <h3>Why vertex-exactness is the assertion worth making</h3>
 *
 * <p>The fix for radial banding is an affine fit of the ramp parameter through each triangle's three
 * corners. Its whole value rests on one property: because two triangles sharing an edge interpolate the
 * <em>same two vertex values</em> along it, they agree on that edge exactly, and the mesh has no colour
 * discontinuity left however coarsely it is cut. <b>That continuity follows from exactness at the
 * vertices</b>, so checking the vertices checks the thing that matters — and it does so without needing a
 * GL context or a screenshot.</p>
 *
 * <p>The failure this guards against is not subtle in appearance and is very subtle in code: an axis that
 * is off by a scale or a sign still produces a smooth-looking gradient, just the wrong one, and on artwork
 * with a soft centre nobody notices until it is compared side by side.</p>
 */
public class CgSvgRadialRampTest {

    /** Two stops, so the ramp is linear between them and a two-colour lerp is exact rather than close. */
    private static final String WHEEL = """
            <svg viewBox="0 0 100 100">
              <defs>
                <radialGradient id="g" cx="50" cy="50" r="50" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#ffffff"/>
                  <stop offset="1" stop-color="#ff0000"/>
                </radialGradient>
              </defs>
              <circle cx="50" cy="50" r="50" fill="url(#g)"/>
            </svg>""";

    @Test
    public void aRadialFillCarriesAPerCellRampRatherThanFlatColours() {
        CgSvgMesh mesh = radialMesh();
        assertNotNull("radial gradients must carry a start colour", mesh.colour0());
        assertNotNull("radial gradients must carry an end colour -- null means the flat-cell path",
                mesh.colour1());
        assertNotNull("radial gradients must carry a per-cell axis", mesh.axes());
        assertTrue("expected a real mesh", mesh.cellCount() > 16);
    }

    /**
     * The ramp evaluated at each vertex must equal the gradient's own colour there.
     *
     * <p>This is the fragment stage's arithmetic done on the CPU: {@code t = clamp(dot(p - origin, dir))},
     * then {@code mix(colour0, colour1, t)}.</p>
     */
    @Test
    public void theRampIsExactAtEveryVertex() {
        CgSvgScene.Node node = radialNode();
        CgSvgScene.Gradient paint = (CgSvgScene.Gradient) node.fill().paint();
        CgSvgGradient gradient = paint.gradient();
        float[] box = CgSvgGeometry.boundsOf(node.contours());
        CgSvgMesh mesh = radialMesh();

        float[] cells = mesh.cells();
        int worst = 0;
        for (int i = 0; i < mesh.cellCount(); i++) {
            for (int v = 0; v < 4; v++) {
                float px = cells[i * 8 + v * 2];
                float py = cells[i * 8 + v * 2 + 1];

                float t = (px - mesh.axes()[i * 4]) * mesh.axes()[i * 4 + 2]
                        + (py - mesh.axes()[i * 4 + 1]) * mesh.axes()[i * 4 + 3];
                t = Math.max(0f, Math.min(1f, t));
                int shaded = mix(mesh.colour0()[i], mesh.colour1()[i], t);

                // The gradient is stated in user space and the geometry is absolute; for this document the
                // two coincide, so the point can be sampled directly.
                int expected = gradient.colourAt(gradient.parameterAt(px, py, box));
                worst = Math.max(worst, maxChannelDelta(shaded, expected));
            }
        }
        // A few levels of slack for 8-bit endpoints being interpolated rather than the ramp being sampled;
        // anything structurally wrong with the axis lands far outside this.
        assertTrue("worst channel error across every vertex was " + worst, worst <= 4);
    }

    /** {@code repeat}/{@code reflect} make the parameter a sawtooth, so those keep the flat-cell path. */
    @Test
    public void aRepeatingRadialGradientKeepsTheFlatCellPath() {
        CgSvgMesh mesh = meshOf(WHEEL.replace("gradientUnits=\"userSpaceOnUse\"",
                "gradientUnits=\"userSpaceOnUse\" spreadMethod=\"repeat\""));
        assertNotNull(mesh.colour0());
        assertNull("a sawtooth parameter must not be fitted with a plane", mesh.colour1());
        assertNull(mesh.axes());
    }

    /** The centre is where an affine fit is worst, so it is where the subdivision has to hold up. */
    @Test
    public void theGradientCentreIsStillTheStartOfTheRamp() {
        CgSvgScene.Node node = radialNode();
        CgSvgScene.Gradient paint = (CgSvgScene.Gradient) node.fill().paint();
        float[] box = CgSvgGeometry.boundsOf(node.contours());
        int centre = paint.gradient().colourAt(paint.gradient().parameterAt(50f, 50f, box));
        assertEquals("the centre stop is white", 0xFFFFFF, centre & 0xFFFFFF);
    }

    private static CgSvgMesh radialMesh() {
        return meshOf(WHEEL);
    }

    private static CgSvgMesh meshOf(String svg) {
        CgSvgScene.Node node = nodeOf(svg);
        return CgSvgTessellator.tessellate(node.contours(), node.fill().evenOdd(), node.fill().paint());
    }

    private static CgSvgScene.Node radialNode() {
        return nodeOf(WHEEL);
    }

    private static CgSvgScene.Node nodeOf(String svg) {
        CgSvgScene scene = CgSvgResolver.resolve(CgSvgScanner.scan(svg), 16);
        for (CgSvgScene.Node node : scene.nodes()) {
            if (node.fill() != null && node.fill().paint() instanceof CgSvgScene.Gradient g
                    && g.gradient().radial()) {
                return node;
            }
        }
        throw new AssertionError("the document resolved to no radial gradient fill");
    }

    private static int mix(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            out |= Math.round(a + (b - a) * t) << shift;
        }
        return out;
    }

    private static int maxChannelDelta(int a, int b) {
        int worst = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            worst = Math.max(worst, Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)));
        }
        return worst;
    }

}
