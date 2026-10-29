package com.crystalgraphics.vfx.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.object.value.CgUnits;

import static org.junit.Assert.assertEquals;

/**
 * The mirror against Chaos's formulas ({@code FieldSystemNodes.cpp}'s {@code Evaluate}), each expected value worked by
 * hand, and the flatten's choice of instances.
 */
public class CgFieldEvalTest {

    static {
        CgAssetKinds.register();
    }

    private static CgFieldDefinition.Graph graph(String target, String nodes) {
        String yaml = "target: " + target + "\nroot: " + last(nodes) + "\nnodes:\n" + nodes;
        return CgFieldDefinition.Graph.read("mymod:f", (Map<?, ?>) CgAssetYaml.read(yaml), CgUnits.HOST);
    }

    private static String last(String nodes) {
        String[] lines = nodes.trim().split("\n");
        String l = lines[lines.length - 1];
        return l.substring(l.indexOf("id: ") + 4, l.indexOf(','));
    }

    private static float[] at(CgFieldDefinition.Graph g, float x, float y, float z, float age) {
        float[] v = new float[4];
        CgFieldEval.graph(g, x, y, z, age, 7, 0, v);
        return v;
    }

    private static float scalar(String nodes, float x, float y, float z) {
        return at(graph("speed_scale", nodes), x, y, z, 0f)[0];
    }

    @Test
    public void aRadialFalloffIsScaleFunctionResultOfItsFalloff() {
        // magnitude × (min + (max − min) × g(1 − d / r)): 2 × (0.5 + 1 × g(0.75)) at a quarter of the radius
        String n = "  - { id: r, kind: radial_falloff, magnitude: 2, min_range: 0.5, max_range: 1.5, default: 9, radius: 4, falloff: %s }\n";
        assertEquals(2f * (0.5f + 1f), scalar(n.formatted("none"), 1, 0, 0), 1e-5f);
        assertEquals(2f * (0.5f + 0.75f), scalar(n.formatted("linear"), 1, 0, 0), 1e-5f);
        assertEquals(2f * (0.5f + 0.5625f), scalar(n.formatted("squared"), 1, 0, 0), 1e-5f);
        assertEquals(2f * (0.5f + 2f * (1f - 1f / 1.75f)), scalar(n.formatted("inverse"), 1, 0, 0), 1e-5f);
        assertEquals(2f * (0.5f + (float) (Math.log(1.75) / Math.log(2))), scalar(n.formatted("logarithmic"), 1, 0, 0), 1e-5f);
        assertEquals(9f, scalar(n.formatted("linear"), 5, 0, 0), 0f);                   // Default past the radius
        assertEquals(0f, scalar("  - { id: r, kind: radial_falloff, radius: 0, default: 9 }\n", 1, 0, 0), 0f);   // no radius: unwritten
    }

    @Test
    public void aPlaneAndABoxFalloffAsChaosEvaluatesThem() {
        // Plane through (0, 2, 0) facing +y, distance 4: g(1 + s / 4) for −4 < s < 0, Default elsewhere.
        String plane = "  - { id: p, kind: plane_falloff, magnitude: 3, default: -1, distance: 4, position: [0, 2, 0], normal: [0, 1, 0] }\n";
        assertEquals(3f * (1f - 1f / 4f), scalar(plane, 0, 1, 0), 1e-5f);
        assertEquals(-1f, scalar(plane, 0, 3, 0), 0f);
        assertEquals(-1f, scalar(plane, 0, -3, 0), 0f);
        // Box half a block each way under scale 4 at (10, 0, 0): f = −min(max(|x| − 0.5), 0) / 0.5 in its own space.
        String box = "  - { id: b, kind: box_falloff, magnitude: 1, transform: { location: [10, 0, 0], scale: [4, 4, 4] }, default: 0 }\n";
        assertEquals(1f, scalar(box, 10, 0, 0), 1e-5f);                          // centre: f = 0.5 / 0.5
        assertEquals(0.5f, scalar(box, 11, 0, 0), 1e-5f);                        // local 0.25: f = 0.25 / 0.5
        assertEquals(0f, scalar(box, 13, 0, 0), 0f);                             // outside: Default
    }

    @Test
    public void aWaveFollowsItsFrontByAge() {
        // radius = wavelength × t / period = 4 at t 2; phase = −(2π / 8) × 4 × (1 − d / 4)
        String w = "  - { id: w, kind: wave_scalar, magnitude: 2, wavelength: 8, period: 4, function: %s, falloff: linear }\n";
        CgFieldDefinition.Graph cos = graph("speed_scale", w.formatted("cosine"));
        float phase = (float) (-(2 * Math.PI / 8) * 4 * (1 - 1 / 4.0));
        assertEquals(2f * (float) Math.cos(phase), at(cos, 1, 0, 0, 2f)[0], 1e-4f);
        assertEquals(2f * (float) Math.exp(-phase * phase), at(graph("speed_scale", w.formatted("gaussian")), 1, 0, 0, 2f)[0], 1e-4f);
        assertEquals(2f * 0.75f, at(graph("speed_scale", w.formatted("falloff")), 1, 0, 0, 2f)[0], 1e-5f);
        assertEquals(0f, at(graph("speed_scale", w.formatted("falloff")), 5, 0, 0, 2f)[0], 0f);
        assertEquals(2f * (float) Math.exp(-0.25), at(graph("speed_scale", w.formatted("decay")), 1, 0, 0, 2f)[0], 1e-5f);
    }

    @Test
    public void vectorsAndOperatorsAsChaosSumsThem() {
        float[] u = at(graph("linear_force", "  - { id: u, kind: uniform_vector, magnitude: 3, direction: [0, 0, 1] }\n"), 5, 5, 5, 0f);
        assertEquals(3f, u[2], 0f);
        float[] r = at(graph("linear_force", "  - { id: r, kind: radial_vector, magnitude: 2, position: [1, 0, 0] }\n"), 1, 4, 0, 0f);
        assertEquals(2f, r[1], 1e-6f);                                            // m × normalize(p − pos)
        assertEquals(0f, at(graph("linear_force", "  - { id: r, kind: radial_vector, magnitude: 2 }\n"), 0, 0, 0, 0f)[0], 0f);   // safe at the centre
        // Turned 90 degrees about z: +x becomes +y.
        float[] t = at(graph("linear_force", "  - { id: t, kind: rotated_radial_vector, magnitude: 1, angle: " + (Math.PI / 2) + ", axis: [0, 0, 1] }\n"), 3, 0, 0, 0f);
        assertEquals(0f, t[0], 1e-6f);
        assertEquals(1f, t[1], 1e-6f);
        float[] rnd = at(graph("linear_force", "  - { id: n, kind: random_vector, magnitude: 5 }\n"), 0, 0, 0, 0f);
        assertEquals(5f, (float) Math.sqrt(rnd[0] * rnd[0] + rnd[1] * rnd[1] + rnd[2] * rnd[2]), 1e-5f);
        String two = "  - { id: a, kind: uniform_scalar, magnitude: 6 }\n  - { id: b, kind: uniform_scalar, magnitude: 2 }\n";
        assertEquals(2f * 12f, scalar(two + "  - { id: o, kind: operator, magnitude: 2, op: multiply, left: a, right: b }\n", 0, 0, 0), 0f);
        assertEquals(3f, scalar(two + "  - { id: o, kind: operator, op: divide, left: a, right: b }\n", 0, 0, 0), 0f);
        assertEquals(4f, scalar(two + "  - { id: o, kind: operator, op: subtract, left: a, right: b }\n", 0, 0, 0), 0f);
        assertEquals(18f, scalar(two + "  - { id: o, kind: operator, magnitude: 3, op: add, left: a }\n", 0, 0, 0), 0f);   // one side passes
        // A vector times a scalar broadcast.
        float[] vs = at(graph("linear_force", "  - { id: v, kind: uniform_vector, magnitude: 1, direction: [0, 1, 0] }\n"
                + "  - { id: s, kind: uniform_scalar, magnitude: 4 }\n  - { id: o, kind: operator, left: v, right: s }\n"), 0, 0, 0, 0f);
        assertEquals(4f, vs[1], 0f);
    }

    @Test
    public void cullingEvaluatesWhereItsCullingAllows() {
        String in = "  - { id: c, kind: radial_falloff, radius: 2 }\n  - { id: s, kind: uniform_scalar, magnitude: 5 }\n";
        // Outside: where the culling is not 0, inside its radius.
        String outside = in + "  - { id: k, kind: culling, culling: c, field: s, op: outside }\n";
        assertEquals(5f, scalar(outside, 1, 0, 0), 0f);
        assertEquals(0f, scalar(outside, 3, 0, 0), 0f);
        // Inside: where the culling is 0, past its radius.
        String inside = in + "  - { id: k, kind: culling, culling: c, field: s, op: inside }\n";
        assertEquals(0f, scalar(inside, 1, 0, 0), 0f);
        assertEquals(5f, scalar(inside, 3, 0, 0), 0f);
    }

    @Test
    public void aGroupKeepsItsSixteenNearestThatReachTheCamera() {
        List<CgFieldWorld.Instance> made = new ArrayList<>();
        CgFieldPlacement bounded = placement("{ id: f, group: flatten.near, field: { kind: graph, target: linear_force, root: o, nodes: ["
                + "{ id: v, kind: uniform_vector, magnitude: 1, direction: [0, 1, 0] }, { id: r, kind: radial_falloff, radius: 2 },"
                + "{ id: o, kind: operator, left: v, right: r }] } }");
        CgFieldPlacement everywhere = placement("{ id: g, group: flatten.near, field: { kind: graph, target: kill, root: s, nodes: ["
                + "{ id: s, kind: uniform_scalar }] } }");
        CgFieldWorld.around(0.5, 0.5, 0.5);
        for (int i = 0; i < 17; i++) made.add(CgFieldWorld.add(bounded, null).place(i + 1, 0, 0, new Quaternionf(), 0f));
        made.add(CgFieldWorld.add(bounded, null).place(1000, 0, 0, new Quaternionf(), 0f));      // past the reach
        made.add(CgFieldWorld.add(everywhere, null).place(5000, 0, 0, new Quaternionf(), 0f));    // unbounded: always
        CgFieldWorld.flatten();
        int g = CgFieldWorld.group("flatten.near");
        int[] w = CgFieldWorld.words();
        assertEquals(CgFieldWorld.PER_GROUP, w[g * 4 + 1]);
        int head = w[g * 4] * 4;
        // Nearest first: the unbounded one far out is beaten by the 16 nearest bounded.
        assertEquals(1f, Float.intBitsToFloat(w[head + 4]), 0f);
        assertEquals(3, w[head + 1]);
        for (CgFieldWorld.Instance i : made) i.remove();
        CgFieldWorld.add(everywhere, null).place(5000, 0, 0, new Quaternionf(), 0f);
        CgFieldWorld.flatten();
        assertEquals(1, CgFieldWorld.words()[g * 4 + 1]);
        assertEquals(CgFieldDefinition.Target.KILL.index(), CgFieldWorld.words()[CgFieldWorld.words()[g * 4] * 4]);
    }

    private static CgFieldPlacement placement(String yaml) {
        return CgFieldPlacement.read("mymod:s: fields.f", (Map<?, ?>) CgAssetYaml.read(yaml), null);
    }
}
