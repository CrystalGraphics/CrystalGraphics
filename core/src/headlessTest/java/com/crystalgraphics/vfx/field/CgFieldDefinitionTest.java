package com.crystalgraphics.vfx.field;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Field graphs compile as Chaos computes them, or fail naming the path; over NSUNS4's placements with -Pkits. */
public class CgFieldDefinitionTest {

    static {
        CgAssetKinds.register();
    }

    private static CgFieldDefinition.Graph graph(String yaml, CgUnits units) {
        return CgFieldDefinition.Graph.read("mymod:f", (Map<?, ?>) CgAssetYaml.read(yaml), units);
    }

    private static void fails(String yaml, String message) {
        try {
            graph(yaml, CgUnits.HOST);
            fail("compiled");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:f") && e.getMessage().contains(message));
        }
    }

    @Test
    public void aGraphBuiltInJavaWritesWhatLoadsBackToIt() {
        CgFieldDefinition.Graph pull = CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("v", "radial_vector", "magnitude", -5, "position", List.of(0, 1, 0))
                .node("r", "radial_falloff", "radius", 3, "position", List.of(0, 1, 0), "default", 0)
                .node("out", "operator", "op", "multiply", "left", "v", "right", "r")
                .magnitudeVar(0.5f)
                .build();
        assertEquals(new CgFieldBounds(-3, -2, -3, 3, 4, 3), pull.bounds());
        assertEquals("out", pull.root().id());
        assertEquals(pull, CgFieldDefinition.Graph.read("again", pull.write(), CgUnits.HOST));
    }

    @Test
    public void lengthsConvertByTheirDeclarationAndTheRootByItsTarget() {
        CgUnits frames = CgUnits.of(Map.of("length", "cm", "time", Map.of("seconds", false, "frames", 30)), CgUnits.HOST);
        CgFieldDefinition.Graph g = graph("target: linear_force\nroot: out\nnodes:\n"
                + "  - { id: v, kind: uniform_vector, magnitude: 2, direction: [0, 1, 0] }\n"
                + "  - { id: r, kind: radial_falloff, radius: 300, default: 0 }\n"
                + "  - { id: out, kind: operator, op: multiply, left: v, right: r }\n", frames);
        assertEquals(3f, g.nodes().get(1).param("radius"), 1e-5f);
        // cm a frame squared, 0.01 × 30², folded into the root's own magnitude alone: its value is 9 × 2 × the falloff
        assertEquals(9f, g.root().param("magnitude"), 1e-4f);
        assertEquals(2f, g.nodes().get(0).param("magnitude"), 1e-6f);
        // A culling root passes its unit through to its field.
        CgFieldDefinition.Graph c = graph("target: speed_scale\nroot: c\nnodes:\n"
                + "  - { id: s, kind: uniform_scalar, magnitude: 2 }\n"
                + "  - { id: r, kind: radial_falloff, radius: 100 }\n"
                + "  - { id: c, kind: culling, culling: r, field: s, op: outside }\n", frames);
        assertEquals(2f, c.nodes().get(0).param("magnitude"), 1e-6f);   // speed_scale has no unit
    }

    @Test
    public void boundsAreChaosComputeFieldBounds() {
        String v = "  - { id: v, kind: uniform_vector, magnitude: 1, direction: [1, 0, 0] }\n";
        String r = "  - { id: r, kind: radial_falloff, radius: 2, default: 0 }\n";
        assertEquals(CgFieldBounds.sphere(0, 0, 0, 2),
                graph("target: linear_velocity\nroot: o\nnodes:\n" + v + r + "  - { id: o, kind: operator, op: multiply, left: v, right: r }\n", CgUnits.HOST).bounds());
        assertFalse(graph("target: linear_velocity\nroot: o\nnodes:\n" + v + r + "  - { id: o, kind: operator, op: add, left: v, right: r }\n", CgUnits.HOST).bounds().bounded());
        // A falloff with a default acts outside its radius: unbounded.
        assertFalse(graph("target: speed_scale\nroot: r\nnodes:\n  - { id: r, kind: radial_falloff, radius: 2, default: 0.5 }\n", CgUnits.HOST).bounds().bounded());
        // Culling outside: the intersection of its culling and its field.
        assertEquals(CgFieldBounds.sphere(0, 0, 0, 2), graph("target: linear_velocity\nroot: c\nnodes:\n" + v + r
                + "  - { id: c, kind: culling, culling: r, field: v, op: outside }\n", CgUnits.HOST).bounds());
        // Chaos's unit box, half a block each way, under its transform.
        assertEquals(new CgFieldBounds(8f, -2f, -1f, 12f, 2f, 1f), graph("target: speed_scale\nroot: b\nnodes:\n"
                + "  - { id: b, kind: box_falloff, transform: { location: [10, 0, 0], scale: [4, 4, 2] }, default: 0 }\n", CgUnits.HOST).bounds());
    }

    @Test
    public void anUnboundedGraphIsLegalAndAPlacementsRadiusBoundsIt() {
        Map<?, ?> placed = (Map<?, ?>) CgAssetYaml.read("{ id: f1, group: g, radius: 4, field: { kind: graph, target: linear_force, root: v, "
                + "nodes: [{ id: v, kind: uniform_vector, magnitude: 1, direction: [0, 1, 0] }] } }");
        CgFieldPlacement p = CgFieldPlacement.read("mymod:s: fields.f1", placed, null);
        assertFalse(p.field().bounds().bounded());
        assertEquals(CgFieldBounds.sphere(0, 0, 0, 4), p.bounds());
        assertFalse(p.world());
        Map<?, ?> free = (Map<?, ?>) CgAssetYaml.read("{ id: f2, group: g, orient: world, field: { kind: graph, target: linear_force, root: v, "
                + "nodes: [{ id: v, kind: uniform_vector }] } }");
        CgFieldPlacement q = CgFieldPlacement.read("mymod:s: fields.f2", free, null);
        assertFalse(q.bounds().bounded());
        assertTrue(q.world());
    }

    @Test
    public void aGraphPastSixteenNodesOrReadingALaterNodeFailsByPath() {
        StringBuilder many = new StringBuilder("target: speed_scale\nroot: n16\nnodes:\n");
        for (int i = 0; i < 17; i++) many.append("  - { id: n").append(i).append(", kind: uniform_scalar }\n");
        fails(many.toString(), ".nodes: 17 nodes, past the 16");
        fails("target: linear_force\nroot: o\nnodes:\n  - { id: o, kind: operator, left: v, right: v }\n"
                + "  - { id: v, kind: uniform_vector }\n", ".nodes.o.left: v is not a node before it");
        fails("target: linear_force\nroot: s\nnodes:\n  - { id: s, kind: uniform_scalar }\n", ".root: s gives a scalar");
        fails("target: linear_force\nroot: s\nnodes:\n  - { id: s, kind: swirl }\n", ".nodes.s: no field node kind swirl");
        fails("target: push\nroot: s\nnodes:\n  - { id: s, kind: uniform_scalar }\n", ".target: push is no field target");
    }

    /** -Pkits=<ns>=<dir>;...: every placed field of every kit system compiles, in its kit's units. */
    @Test
    public void everyKitFieldShapeCompiles() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits", kits != null && !kits.isEmpty());
        int placed = 0, unbounded = 0;
        for (String kit : kits.split(";")) {
            Path dir = Paths.get(kit.split("=", 2)[1]);
            Path unitsFile = dir.resolve("units.yaml");
            CgUnits units = Files.isRegularFile(unitsFile)
                    ? CgUnits.of(((Map<?, ?>) CgAssetYaml.read(Files.readString(unitsFile))).get("units"), CgUnits.HOST) : CgUnits.HOST;
            Path systems = dir.resolve("vfx/systems");
            if (!Files.isDirectory(systems)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(systems)) {
                files = walk.filter(f -> f.toString().endsWith(".yaml")).toList();
            }
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (!text.contains("\nfields:")) continue;
                Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(text);
                CgUnits own = CgUnits.of(tree.get("units"), units);
                for (Object o : (List<?>) tree.get("fields")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    CgFieldPlacement p = CgFieldPlacement.read(f.getFileName() + ": fields." + m.get("id"), m, deps(own));
                    placed++;
                    if (!p.bounds().bounded()) unbounded++;
                }
            }
        }
        System.out.println("[fields] " + placed + " placed fields compiled, " + unbounded + " unbounded");
        assertTrue(placed > 0);
    }

    private static CgAssetCompiler.Dependencies deps(CgUnits units) {
        return new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String ref) {
                return null;
            }

            @Override
            public CgUnits units() {
                return units;
            }
        };
    }
}
