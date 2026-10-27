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
    public void aValuesOwnUnitOverridesTheAssetsAndIsDropped() {
        Map<?, ?> r = (Map<?, ?>) CgUnits.UNREAL.toHost(Map.of("range", List.of(0, 0.5), "unit", "turns"), CgQuantity.ANGLE);
        assertEquals(Math.PI, ((Number) ((List<?>) r.get("range")).get(1)).doubleValue(), 1e-9);
        assertFalse(r.containsKey("unit"));
        assertEquals(Math.PI / 2, ((Number) CgUnits.HOST.toHost(Map.of("value", 0.25, "unit", "turns"), CgQuantity.ANGLE))
                .doubleValue(), 1e-9);
    }

    @Test
    public void aKitsUnitsConvertFormsThroughItsAxes() {
        CgUnits kit = CgUnits.of(Map.of("length", "cm", "axes", "unreal"), CgUnits.HOST);
        Map<String, Object> curve = Map.of("samples", Map.of("range", List.of(0, 1), "values", List.of(100, 300)));
        Map<?, ?> curves = (Map<?, ?>) kit.toHost(Map.of("curves", List.of(Map.of("keys", List.of()), Map.of("keys", List.of()), curve),
                "by", "age"), CgQuantity.OFFSET);
        Map<?, ?> y = (Map<?, ?>) ((Map<?, ?>) ((List<?>) curves.get("curves")).get(1)).get("samples");
        assertEquals("Unreal's z curve is the host's y", 3.0, ((Number) ((List<?>) y.get("values")).get(1)).doubleValue(), 1e-9);
        Map<?, ?> locked = (Map<?, ?>) kit.toHost(Map.of("range", List.of(List.of(1, 1, 1), List.of(2, 2, 2)), "per_channel", true,
                "locked", "xy"), CgQuantity.SCALE);
        assertEquals("Unreal's y is the host's z", "xz", locked.get("locked"));
    }

    @Test
    public void anUnrealYawTurnsItsXToItsYWhichIsTheHostsZ() {
        float[] q = CgUnits.rotatorToHost(0, 0, Math.PI / 2);
        // v' = v + 2w (q x v) + 2 q x (q x v), for v = +X
        double tx = 0, ty = 2 * q[2], tz = -2 * q[1];
        double x = 1 + q[3] * tx + (q[1] * tz - q[2] * ty), y = q[3] * ty + (q[2] * tx - q[0] * tz), z = q[3] * tz + (q[0] * ty - q[1] * tx);
        assertEquals(0, x, 1e-6);
        assertEquals(0, y, 1e-6);
        assertEquals(1, z, 1e-6);
    }

    @Test
    public void unrealFlipsHandednessAndTheHostDoesNot() {
        assertTrue(CgUnits.UNREAL.flipsHandedness());
        assertFalse(CgUnits.HOST.flipsHandedness());
        assertEquals(Math.PI, ((Number) CgUnits.UNREAL.toHost(180, CgQuantity.ANGLE)).doubleValue(), 1e-9);
    }

    @Test
    public void aUserBindingConvertsItsResult() {
        // Cascade's LocationDirect bound to the beam's CylinderLength: 600 to 50000 cm along Unreal's x.
        Map<?, ?> written = Map.of("bind", "user.CylinderLength", "default", List.of(20000, 0, 0),
                "remap", Map.of("in", List.of(List.of(0, 0, 0), List.of(100000, 0, 5)), "out", List.of(List.of(600, 0, 0), List.of(50000, 0, 7))));
        Map<?, ?> host = (Map<?, ?>) CgUnits.UNREAL.toHost(written, CgQuantity.POSITION);
        Map<?, ?> remap = (Map<?, ?>) host.get("remap");
        assertEquals("in stays the parameter's numbers, in host channel order", List.of(100000, 5, 0), ((List<?>) remap.get("in")).get(1));
        assertEquals(List.of(500.0, 0.07, 0.0), ((List<?>) remap.get("out")).get(1));
        assertEquals("host y reads Unreal's z", List.of(0, 2, 1), host.get("channels"));
        assertEquals("default stays the parameter's", List.of(20000, 0, 0), host.get("default"));
        // Unmapped, the scale takes the factor; an engine binding is in host units already.
        Map<?, ?> radius = (Map<?, ?>) CgUnits.UNREAL.toHost(Map.of("bind", "user.Radius", "scale", 2), CgQuantity.LENGTH);
        assertEquals(0.02, ((Number) radius.get("scale")).doubleValue(), 1e-12);
        Map<?, ?> age = Map.of("bind", "particles.age");
        assertSame(age, CgUnits.UNREAL.toHost(age, CgQuantity.TIME));
    }
}
