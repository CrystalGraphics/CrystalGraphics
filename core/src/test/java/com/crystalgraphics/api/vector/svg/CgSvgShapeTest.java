package com.crystalgraphics.api.vector.svg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.api.vector.CgFillRule;
import com.crystalgraphics.api.vector.CgStrokeCap;

/** A document's parts as their own paths: found by id, in paint order, with their paint and stroke. */
public class CgSvgShapeTest {

    private static final CgSvg DOC = CgSvg.parse("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <rect id="plate" x="2" y="2" width="20" height="20" fill="#203048" fill-rule="evenodd"/>
              <g transform="translate(4, 0)">
                <path id="tick" d="M2 12 L8 18 L16 6" fill="none" stroke="currentColor" stroke-width="2"
                      stroke-linecap="round"/>
              </g>
              <circle cx="20" cy="4" r="3" fill="#FF0000"/>
            </svg>
            """);

    @Test
    public void partsInPaintOrderWithTheirIds() {
        List<CgSvgShape> shapes = DOC.shapes();
        assertEquals(3, shapes.size());
        assertEquals("plate", shapes.get(0).id());
        assertEquals("tick", shapes.get(1).id());
        assertNull("no id", shapes.get(2).id());
    }

    @Test
    public void aFillKeepsItsColourAndRule() {
        CgSvgShape plate = DOC.shape("plate");
        assertNotNull(plate.fill());
        assertEquals(0xFF203048, plate.fill().argb());
        assertFalse(plate.fill().gradient());
        assertEquals(CgFillRule.EVEN_ODD, plate.path().fillRule());
        assertNull("not stroked", plate.stroke());
    }

    @Test
    public void aStrokeKeepsItsWidthCapAndCurrentColour() {
        CgSvgShape tick = DOC.shape("tick");
        assertNull("fill none", tick.fill());
        assertNotNull(tick.stroke());
        assertEquals(2f, tick.stroke().width(), 1e-5f);
        assertEquals(CgStrokeCap.ROUND, tick.stroke().cap());
        assertTrue(tick.strokePaint().currentColor());
    }

    @Test
    public void itsTransformIsApplied() {
        assertEquals("2 + the group's 4", 6f, DOC.shape("tick").path().minX(), 1e-4f);
        assertEquals(20f, DOC.shape("tick").path().maxX(), 1e-4f);
    }

    @Test
    public void anIdTheDocumentLacksFails() {
        assertThrows(IllegalArgumentException.class, () -> DOC.shape("nope"));
    }
}
