package com.crystalgraphics.render.ui.svg;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Which filters resolve to a drop shadow, what it measures in absolute space, and which nodes share it. */
public class CgSvgDropShadowTest {

    /** The chain authoring tools export, as the CrystalGUI logo carries it. */
    private static final String CHAIN = "<filter id=\"drop\" filterUnits=\"userSpaceOnUse\" x=\"-10\" y=\"-10\" width=\"84\" height=\"84\">"
            + "<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\"1.5\"/><feOffset dy=\"2\" result=\"blur\"/>"
            + "<feFlood flood-color=\"#0A0F3A\" flood-opacity=\"0.55\"/><feComposite in2=\"blur\" operator=\"in\"/>"
            + "<feMerge><feMergeNode/><feMergeNode in=\"SourceGraphic\"/></feMerge></filter>";

    private static List<CgSvgScene.Node> nodes(String body) {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><defs>" + body;
        return CgSvgResolver.resolve(CgSvgScanner.scan(svg), 8).nodes();
    }

    @Test
    public void theExportedChainIsADropShadow() {
        List<CgSvgScene.Node> nodes = nodes(CHAIN + "</defs><rect width=\"10\" height=\"10\" filter=\"url(#drop)\"/></svg>");
        CgSvgScene.DropShadow shadow = nodes.get(0).shadow();
        assertNotNull(shadow);
        assertEquals(0f, shadow.dx(), 1e-5f);
        assertEquals(2f, shadow.dy(), 1e-5f);
        assertEquals(1.5f, shadow.sigmaX(), 1e-5f);
        assertEquals(1.5f, shadow.sigmaY(), 1e-5f);
        assertEquals("flood-opacity folds into the alpha", (Math.round(255 * 0.55f) << 24) | 0x0A0F3A, shadow.argb());
    }

    @Test
    public void feDropShadowTakesTheSpecDefaults() {
        List<CgSvgScene.Node> nodes = nodes("<filter id=\"s\"><feDropShadow/></filter></defs>"
                + "<rect width=\"10\" height=\"10\" filter=\"url(#s)\"/></svg>");
        CgSvgScene.DropShadow shadow = nodes.get(0).shadow();
        assertNotNull(shadow);
        assertEquals(2f, shadow.dx(), 1e-5f);
        assertEquals(2f, shadow.dy(), 1e-5f);
        assertEquals(2f, shadow.sigmaX(), 1e-5f);
        assertEquals(0xFF000000, shadow.argb());
    }

    @Test
    public void aGroupsChildrenShareOneShadowAndASiblingGetsItsOwn() {
        List<CgSvgScene.Node> nodes = nodes(CHAIN + "</defs>"
                + "<rect width=\"10\" height=\"10\" filter=\"url(#drop)\"/>"
                + "<g filter=\"url(#drop)\"><rect x=\"20\" width=\"4\" height=\"4\"/><rect x=\"30\" width=\"4\" height=\"4\"/></g>"
                + "<rect x=\"40\" width=\"4\" height=\"4\"/></svg>");
        assertEquals(4, nodes.size());
        assertSame("one group, one shadow", nodes.get(1).shadow(), nodes.get(2).shadow());
        assertNotSame("an equal filter on another element is another group", nodes.get(0).shadow(), nodes.get(1).shadow());
        assertNull("outside the filtered group", nodes.get(3).shadow());
    }

    @Test
    public void theElementsTransformScalesTheOffsetAndTheDeviation() {
        List<CgSvgScene.Node> nodes = nodes(CHAIN + "</defs>"
                + "<g transform=\"translate(5 5) scale(2)\" filter=\"url(#drop)\"><rect width=\"4\" height=\"4\"/></g></svg>");
        CgSvgScene.DropShadow shadow = nodes.get(0).shadow();
        assertNotNull(shadow);
        assertEquals("the translation does not move an offset", 4f, shadow.dy(), 1e-5f);
        assertEquals(3f, shadow.sigmaX(), 1e-5f);
    }

    @Test
    public void anyOtherFilterIsIgnored() {
        List<CgSvgScene.Node> nodes = nodes("<filter id=\"m\"><feColorMatrix type=\"saturate\" values=\"0\"/></filter></defs>"
                + "<rect width=\"10\" height=\"10\" filter=\"url(#m)\"/></svg>");
        assertEquals(1, nodes.size());
        assertNull(nodes.get(0).shadow());
    }
}
