package com.crystalgraphics.asset.curve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgPatchTable;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgPropertyPath;

/** A curve evaluates as {@code FRichCurve::Eval}: vectors worked from its segment and extrapolation rules. */
public class CgRichCurveTest {

    private static final float EPS = 1e-5f;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static List<Object> key(Object... parts) {
        return Arrays.asList(parts);
    }

    private static CgRichCurve keys(String pre, String post, List<?>... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pre != null) m.put("pre", pre);
        if (post != null) m.put("post", post);
        m.put("keys", Arrays.asList(keys));
        return CgRichCurve.decode(m);
    }

    @Test
    public void segmentsTakeTheirFirstKeysInterpolation() {
        assertEquals(0.5f, keys(null, null, key(0, 0, "linear"), key(1, 2)).eval(0.25f), EPS);
        CgRichCurve constant = keys(null, null, key(0, 1, "constant"), key(1, 5));
        assertEquals(1f, constant.eval(0.99f), 0f);
        assertEquals(5f, constant.eval(1f), 0f);
        CgRichCurve flat = keys(null, null, key(0, 0), key(1, 1));     // cubic, auto: flat at the ends
        assertEquals(0.15625f, flat.eval(0.25f), EPS);                 // 3t^2 - 2t^3
        assertEquals(0.5f, flat.eval(0.5f), EPS);
    }

    @Test
    public void autoTangentsAreUnrealsAtTensionZero() {
        // the middle key's tangent is (4 - 0) / (2 - 0) = 2: the first segment is Bezier(0, 0, 1 - 2/3, 1)
        assertEquals(0.25f, keys(null, null, key(0, 0), key(1, 1), key(2, 4)).eval(0.5f), EPS);
    }

    @Test
    public void storedAndWeightedTangents() {
        CgRichCurve line = keys(null, null, key(0, 0, "cubic", "user", 0, 1), key(1, 1, "cubic", "user", 1, 0));
        assertEquals(0.3f, line.eval(0.3f), EPS);
        // a side weighted at its unweighted length, sqrt(dt^2 + (tangent dt)^2) / 3, changes nothing
        CgRichCurve bent = keys(null, null, key(0, 0, "cubic", "user", 0, 2), key(1, 1, "cubic", "user", 0, 0));
        CgRichCurve weighted = keys(null, null, key(0, 0, "cubic", "user", 0, 2, mapOf("leave_weight", Math.sqrt(5) / 3, "weight_mode", "leave")),
                key(1, 1, "cubic", "user", 0, 0));
        for (float t = 0f; t <= 1f; t += 0.125f) assertEquals("t " + t, bent.eval(t), weighted.eval(t), 1e-4f);
        // a longer flat leave handle holds the curve low for longer
        CgRichCurve heavier = keys(null, null, key(0, 0, "cubic", "user", 0, 0, mapOf("leave_weight", 0.9, "weight_mode", "leave")),
                key(1, 1, "cubic", "user", 0, 0));
        assertTrue(heavier.eval(0.5f) < 0.45f);
        assertEquals(1f, heavier.eval(1f), EPS);
        assertEquals(0f, heavier.eval(0f), EPS);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void extrapolation() {
        List<Object> a = key(0, 0, "linear"), b = key(1, 1, "linear");
        assertEquals(0.25f, keys(null, "cycle", a, b).eval(1.25f), EPS);
        assertEquals(1.25f, keys(null, "cycle_with_offset", a, b).eval(1.25f), EPS);
        assertEquals(2.25f, keys(null, "cycle_with_offset", a, b).eval(2.25f), EPS);
        assertEquals(-0.75f, keys("cycle_with_offset", null, a, b).eval(-0.75f), EPS);
        assertEquals(0.75f, keys(null, "oscillate", a, b).eval(1.25f), EPS);
        assertEquals(0.25f, keys(null, "oscillate", a, b).eval(2.25f), EPS);
        assertEquals(2f, keys(null, "linear", a, b).eval(2f), EPS);
        assertEquals(-1f, keys("linear", null, a, b).eval(-1f), EPS);
        assertEquals(1f, keys(null, "constant", a, b).eval(2f), 0f);
        assertEquals(0f, keys(null, null, a, b).eval(-3f), 0f);
    }

    @Test
    public void noKeysGiveTheDefaultAndOneKeyHolds() {
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("default", 3);
        assertEquals(3f, CgRichCurve.decode(none).eval(5f), 0f);
        assertEquals(7f, keys(null, null, key(0.5, 7)).eval(-9f), 0f);
        assertEquals(7f, keys(null, null, key(0.5, 7)).eval(9f), 0f);
    }

    @Test
    public void samplesInterpolateLinearlyAndClamp() {
        CgRichCurve s = CgRichCurve.decode(mapOf("samples", mapOf("range", Arrays.asList(0, 1), "values", Arrays.asList(0, 2, 1))));
        assertEquals(1f, s.eval(0.25f), EPS);
        assertEquals(1.5f, s.eval(0.75f), EPS);
        assertEquals(0f, s.eval(-1f), 0f);
        assertEquals(1f, s.eval(2f), 0f);
    }

    @Test
    public void aCurveAssetCompilesWithItsPatchTable() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("type", "vector");
        tree.put("curves", Arrays.asList(
                mapOf("keys", Arrays.asList(key(0, 0, "linear"), key(1, 1))),
                mapOf("keys", Arrays.asList(key(0, 2))),
                mapOf("keys", Arrays.asList(key(0, 0), key(1, 1)))));
        CgAssetData asset = CgAssetData.of("test:wobble", CgLookKinds.CURVE_KIND, tree, null);
        CgCurveDefinition d = (CgCurveDefinition) CgCurveDefinition.COMPILER.compile(asset, (kind, id) -> null);
        assertEquals(3, d.channels());
        float[] out = new float[3];
        d.eval(0.5f, out);
        assertEquals(0.5f, out[0], EPS);
        assertEquals(2f, out[1], 0f);
        assertEquals(0.5f, out[2], EPS);
        assertEquals(5, d.patches().size());
        CgPatchTable.Entry e = d.patches().entry(CgPropertyPath.of("test:wobble").key("curves").index(0).key("keys").index(1).index(1));
        d.patch(e, 3.0);
        assertEquals(1.5f, d.eval(0, 0.5f), EPS);
    }
}
