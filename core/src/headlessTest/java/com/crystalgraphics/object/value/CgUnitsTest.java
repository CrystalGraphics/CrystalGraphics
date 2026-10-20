package com.crystalgraphics.object.value;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class CgUnitsTest {

    @Test
    public void unrealGravityReachesTheHostDownAlongY() {
        List<?> g = (List<?>) CgUnits.UNREAL.toHost(List.of(0, 0, -980), CgQuantity.ACCELERATION);
        assertEquals(0, ((Number) g.get(0)).doubleValue(), 1e-9);
        assertEquals(-9.8, ((Number) g.get(1)).doubleValue(), 1e-9);
        assertEquals(0, ((Number) g.get(2)).doubleValue(), 1e-9);
    }

    @Test
    public void lengthsScaleAndATwoNumberSizeIsNotPermuted() {
        assertEquals(0.5, ((Number) CgUnits.UNREAL.toHost(50, CgQuantity.LENGTH)).doubleValue(), 1e-9);
        List<?> size = (List<?>) CgUnits.UNREAL.toHost(List.of(50, 20), CgQuantity.LENGTH);
        assertEquals(0.5, ((Number) size.get(0)).doubleValue(), 1e-9);
        assertEquals(0.2, ((Number) size.get(1)).doubleValue(), 1e-9);
    }

    @Test
    public void aRangeConvertsBothEnds() {
        Map<?, ?> r = (Map<?, ?>) CgUnits.UNREAL.toHost(Map.of("range", List.of(100, 200)), CgQuantity.LENGTH);
        List<?> ends = (List<?>) r.get("range");
        assertEquals(1.0, ((Number) ends.get(0)).doubleValue(), 1e-9);
        assertEquals(2.0, ((Number) ends.get(1)).doubleValue(), 1e-9);
    }

    @Test
    public void whatHasNoUnitComesBackAsWritten() {
        Integer count = 3;
        assertSame(count, CgUnits.UNREAL.toHost(count, CgQuantity.NONE));
        assertEquals("sphere", CgUnits.UNREAL.toHost("sphere", CgQuantity.NONE));
    }

    @Test
    public void unrealFlipsHandednessAndTheHostDoesNot() {
        assertTrue(CgUnits.UNREAL.flipsHandedness());
        assertFalse(CgUnits.HOST.flipsHandedness());
        assertEquals(Math.PI, ((Number) CgUnits.UNREAL.toHost(180, CgQuantity.ANGLE)).doubleValue(), 1e-9);
    }
}
