package com.crystalgraphics.shadergraph;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P6.3.2 / 6.3.3 — node types and the emitter.
 *
 * <h3>What is actually being asserted</h3>
 * <p>The emitted string. That is the whole point of making text the seam: the compiler needs no GL
 * context, no driver and no window, so every rule it enforces is checkable here rather than in a
 * harness. A compile that <em>links</em> is a separate question and belongs to the driver.</p>
 */
public class CgGraphCompilerTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A dynamic-width multiply — the node that exists because declarative-only would need four. */
    private static CgShaderNode multiply() {
        return CgTemplateShaderNode.of("cg:math/multiply").label("Multiply")
                .in("A", CgShaderType.DYNAMIC, "1.0")
                .in("B", CgShaderType.DYNAMIC, "1.0")
                .out("Out", CgShaderType.DYNAMIC)
                .body("{Out} = {A} * {B};")
                .build();
    }

    private static CgShaderNode constantVec3() {
        return CgTemplateShaderNode.of("cg:input/vec3")
                .in("Value", CgShaderType.VEC3, "vec3(0.0)")
                .out("Out", CgShaderType.VEC3)
                .body("{Out} = {Value};")
                .build();
    }

    private static CgShaderNode constantFloat() {
        return CgTemplateShaderNode.of("cg:input/float")
                .in("Value", CgShaderType.FLOAT, "0.0")
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = {Value};")
                .build();
    }

    private static CgShaderNode constantVec4() {
        return CgTemplateShaderNode.of("cg:input/vec4")
                .in("Value", CgShaderType.VEC4, "vec4(0.0)")
                .out("Out", CgShaderType.VEC4)
                .body("{Out} = {Value};")
                .build();
    }

    private static CgShaderNode constantVec2() {
        return CgTemplateShaderNode.of("cg:input/vec2")
                .in("Value", CgShaderType.VEC2, "vec2(0.0)")
                .out("Out", CgShaderType.VEC2)
                .body("{Out} = {Value};")
                .build();
    }

    // ── The basics ──────────────────────────────────────────────────────────

    /**
     * <b>Namespacing cannot collide, because the node never chooses it.</b>
     *
     * <p>The node is handed the finished variable names and returns a snippet — Godot's inversion. Two
     * instances of one type therefore cannot clash however the template is written.</p>
     */
    @Test
    public void twoInstancesOfOneTypeDoNotCollide() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", constantFloat()))
                .add(CgShaderGraph.Instance.of("b", constantFloat()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("a", "Out", "m", "A")
                .link("b", "Out", "m", "B")
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue(result.code().contains("node_a_Out"));
        assertTrue(result.code().contains("node_b_Out"));
        assertTrue("the multiply reads both", result.code().contains("node_m_Out = node_a_Out * node_b_Out;"));
    }

    /** Dependencies are emitted before the nodes that read them, or the GLSL does not compile. */
    @Test
    public void nodesAreEmittedInDependencyOrder() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .add(CgShaderGraph.Instance.of("a", constantFloat()))
                .link("a", "Out", "m", "A")
                .output("m");

        String code = CgGraphCompiler.compile(graph).code();

        assertTrue("upstream must be declared first",
                code.indexOf("node_a_Out") < code.indexOf("node_m_Out = "));
    }

    // ── Type resolution ─────────────────────────────────────────────────────

    /**
     * <b>A dynamic node takes the widest type reaching it, and the narrow side is cast.</b>
     *
     * <p>{@code Multiply(float, vec3)} is a vec3 throughout. Resolving each port independently would
     * make the output a float whenever the first input happened to be one — a bug that depends on wiring
     * order and is therefore unreproducible.</p>
     */
    @Test
    public void aDynamicNodeWidensAndTheCompilerEmitsTheCast() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("f", constantFloat()))
                .add(CgShaderGraph.Instance.of("v", constantVec3()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("f", "Out", "m", "A")
                .link("v", "Out", "m", "B")
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("the dynamic node resolved to vec3", result.code().contains("vec3 node_m_Out;"));
        assertTrue("and the float side was promoted, which is the compiler's job not the user's",
                result.code().contains("vec3(node_f_Out)"));
    }

    /**
     * <b>{@code Add(vec4, vec2)} compiles, resolves to vec2, and truncates the wide side.</b>
     *
     * <p>The case that made this rule wrong the first time. Resolving to the WIDEST asked a vec2 to feed
     * a vec4 — which {@link CgShaderType#canFeed} forbids, correctly, since there is no honest value for
     * the missing channels — so the node reported an error and its preview rendered black. Unity resolves
     * to the narrowest non-scalar and swizzles the wide side down, which is what this pins.</p>
     */
    @Test
    public void aDynamicNodeResolvesToTheNarrowerInputAndTruncatesTheWider() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("wide", constantVec4()))
                .add(CgShaderGraph.Instance.of("narrow", constantVec2()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("wide", "Out", "m", "A")
                .link("narrow", "Out", "m", "B")
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("the node resolved to the NARROWER side",
                result.code().contains("vec2 node_m_Out;"));
        assertTrue("and the wide side was truncated rather than refused",
                result.code().contains("node_wide_Out.xy"));
    }

    /** The same graph wired the other way round must produce the same types. */
    @Test
    public void wideningDoesNotDependOnWiringOrder() {
        CgShaderGraph first = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("f", constantFloat()))
                .add(CgShaderGraph.Instance.of("v", constantVec3()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("f", "Out", "m", "A").link("v", "Out", "m", "B").output("m");
        CgShaderGraph swapped = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("f", constantFloat()))
                .add(CgShaderGraph.Instance.of("v", constantVec3()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("v", "Out", "m", "A").link("f", "Out", "m", "B").output("m");

        assertTrue(CgGraphCompiler.compile(first).code().contains("vec3 node_m_Out;"));
        assertTrue(CgGraphCompiler.compile(swapped).code().contains("vec3 node_m_Out;"));
    }

    // ── Unconnected inputs ──────────────────────────────────────────────────

    /**
     * <b>An unconnected input emits its value, and the node cannot tell the difference.</b>
     *
     * <p>This is what the editor's inline field on {@code nodeport:blank} has been collecting all along.
     * A node never branches on connectedness, because it is handed an expression either way.</p>
     */
    @Test
    public void anUnconnectedInputBecomesItsLiteral() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(new CgShaderGraph.Instance("m", multiply(), Map.of("A", "2.0")))
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("the explicit value", result.code().contains("2.0"));
        assertTrue("and the port's declared default for the other side",
                result.code().contains("node_m_Out = 2.0 * 1.0;"));
    }

    /** An input with neither a connection nor a default is an error, not a silent zero. */
    @Test
    public void anInputWithNoConnectionAndNoDefaultIsReported() {
        CgShaderNode needsInput = CgTemplateShaderNode.of("cg:test/needs")
                .in("In", CgShaderType.FLOAT, null)
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = {In};")
                .build();

        CgGraphCompiler.Result result = CgGraphCompiler.compile(
                new CgShaderGraph().add(CgShaderGraph.Instance.of("n", needsInput)).output("n"));

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("no default"));
    }

    // ── Rooting, which is what makes previews free ──────────────────────────

    /**
     * <b>A preview is the same compile with a different root.</b>
     *
     * <p>Not a second emitter and not a second traversal — {@code compileFrom} takes the root as a
     * parameter, so a preview of node X is the graph up to X. Godot's {@code p_for_preview} lands in the
     * same place. Everything downstream of the root is simply not reachable and not emitted.</p>
     */
    @Test
    public void compilingFromAnIntermediateNodeEmitsOnlyItsSubgraph() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", constantFloat()))
                .add(CgShaderGraph.Instance.of("b", constantFloat()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("a", "Out", "m", "A")
                .link("b", "Out", "m", "B")
                .output("m");

        String preview = CgGraphCompiler.compileFrom(graph, "a", true).code();

        assertTrue(preview.contains("node_a_Out"));
        assertFalse("nothing downstream of the previewed node is emitted", preview.contains("node_m_Out"));
        assertFalse(preview.contains("node_b_Out"));
    }

    /** Nodes reaching nothing are not emitted, so a half-built subgraph is not dead GLSL. */
    @Test
    public void unreachableNodesAreNotEmitted() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("used", constantFloat()))
                .add(CgShaderGraph.Instance.of("orphan", constantVec3()))
                .output("used");

        String code = CgGraphCompiler.compile(graph).code();

        assertTrue(code.contains("node_used_Out"));
        assertFalse(code.contains("node_orphan_Out"));
    }

    // ── Includes ────────────────────────────────────────────────────────────

    /**
     * <b>Includes are the union of what the present nodes declare, once each.</b>
     *
     * <p>Declared rather than inferred, because inferring means parsing GLSL. Ten noise nodes include
     * {@code noise.glsl} once; a graph with none does not include it at all.</p>
     */
    @Test
    public void includesAreDeclaredUnionedAndDeduplicated() {
        CgShaderNode noisy = CgTemplateShaderNode.of("cg:procedural/noise")
                .in("UV", CgShaderType.VEC2, "vec2(0.0)")
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = value_noise({UV});")
                .include("crystalgraphics:shaders/lib/noise.glsl")
                .build();

        CgGraphCompiler.Result result = CgGraphCompiler.compile(new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("n1", noisy))
                .add(CgShaderGraph.Instance.of("n2", noisy))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("n1", "Out", "m", "A")
                .link("n2", "Out", "m", "B")
                .output("m"));

        assertEquals(List.of("crystalgraphics:shaders/lib/noise.glsl"), result.includes());
    }

    // ── Errors that must point at a node ────────────────────────────────────

    /**
     * <b>Every emitted line knows which node emitted it.</b>
     *
     * <p>The property that decides whether the editor is usable. A driver reports a failure at a line of
     * generated source the user never wrote; without this map the editor can only repeat the message.
     * Built while emitting, which is nearly free — and impossible to reconstruct afterwards.</p>
     */
    @Test
    public void everyLineMapsBackToTheNodeThatEmittedIt() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", constantFloat()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("a", "Out", "m", "A")
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertEquals("a", result.ownerOfLine(1));
        int lastLine = result.code().split("\n", -1).length - 1;
        assertEquals("m", result.ownerOfLine(lastLine));
    }

    /** A cycle is reported against a named node rather than hanging or emitting non-terminating GLSL. */
    @Test
    public void aCycleIsReportedRatherThanEmitted() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("x", multiply()))
                .add(CgShaderGraph.Instance.of("y", multiply()))
                .link("x", "Out", "y", "A")
                .link("y", "Out", "x", "A")
                .output("y");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("Cycle"));
    }

    /** A graph with no output compiles to nothing and says so. */
    @Test
    public void aGraphWithNoOutputIsReported() {
        CgGraphCompiler.Result result = CgGraphCompiler.compile(new CgShaderGraph());

        assertFalse(result.ok());
        assertTrue(result.errors().get(0).contains("no output node"));
    }

    // ── Determinism, which content-hash keying depends on ───────────────────

    /**
     * <b>The same graph emits byte-identical source.</b>
     *
     * <p>Not cosmetic: {@code CgMaterialShaderRegistry.getOrCreateGenerated} keys on the content hash of
     * this string. Non-deterministic output would mean a fresh compile on every reopen and a new GL
     * program each time.</p>
     */
    @Test
    public void theSameGraphEmitsIdenticalSource() {
        java.util.function.Supplier<CgShaderGraph> build = () -> new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", constantFloat()))
                .add(CgShaderGraph.Instance.of("v", constantVec3()))
                .add(CgShaderGraph.Instance.of("m", multiply()))
                .link("a", "Out", "m", "A")
                .link("v", "Out", "m", "B")
                .output("m");

        assertEquals(CgGraphCompiler.compile(build.get()).code(),
                CgGraphCompiler.compile(build.get()).code());
    }

    // ── The template language ───────────────────────────────────────────────

    /** {@code {type:Port}} is what lets one template serve every width of a dynamic node. */
    @Test
    public void aTemplateCanNameItsOwnResolvedType() {
        CgShaderNode splat = CgTemplateShaderNode.of("cg:test/splat")
                .in("In", CgShaderType.DYNAMIC, "0.0")
                .out("Out", CgShaderType.DYNAMIC)
                .body("{Out} = {type:Out}({In});")
                .build();

        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("v", constantVec3()))
                .add(CgShaderGraph.Instance.of("s", splat))
                .link("v", "Out", "s", "In")
                .output("s");

        assertTrue(CgGraphCompiler.compile(graph).code().contains("node_s_Out = vec3(node_v_Out);"));
    }

    /** A template naming a port that does not exist fails against the node, not the driver. */
    @Test
    public void aTemplateReferencingAnUnknownPortNamesTheNode() {
        CgShaderNode broken = CgTemplateShaderNode.of("cg:test/broken")
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = {Nope};")
                .build();

        CgGraphCompiler.Result result = CgGraphCompiler.compile(
                new CgShaderGraph().add(CgShaderGraph.Instance.of("b", broken)).output("b"));

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("cg:test/broken"));
    }

    /** A node with no output could never be reached from the master node. */
    @Test
    public void aNodeWithNoOutputIsRefusedAtDefinitionTime() {
        try {
            CgTemplateShaderNode.of("cg:test/sink").in("In", CgShaderType.FLOAT, "0.0").build();
            fail("expected a node with no output to be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no output"));
        }
    }

    // ── Multiple outputs (6.3's real remaining blocker, per the node-library research) ──────────

    /**
     * <b>Four outputs, four independent downstream consumers.</b>
     *
     * <p>Nothing before {@link CgBuiltinShaderNodes#SPLIT} ever exercised
     * {@link CgShaderNode#outputs()} returning more than one port — every prior fixture in this file
     * has exactly one. This is the proof: each channel gets its own declared variable and its own
     * consumer, and none of them collide or starve each other of a line.</p>
     */
    @Test
    public void aNodeWithFourOutputsFeedsFourIndependentConsumers() {
        // R and G each reach their OWN multiply, and both multiplies feed a final combiner — so both
        // are genuine ancestors of the root and both survive the walk `compileFrom` actually performs
        // (only what the root depends on is emitted; a sibling with no path to the root never would be,
        // which is what made the first version of this test compile everything away silently).
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("v", constantVec4()))
                .add(CgShaderGraph.Instance.of("s", CgBuiltinShaderNodes.SPLIT))
                .add(CgShaderGraph.Instance.of("addR", multiply()))
                .add(CgShaderGraph.Instance.of("addG", multiply()))
                .add(CgShaderGraph.Instance.of("combine", multiply()))
                .link("v", "Out", "s", "In")
                .link("s", "R", "addR", "A")
                .link("s", "G", "addG", "A")
                .link("addR", "Out", "combine", "A")
                .link("addG", "Out", "combine", "B")
                .output("combine");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("R is declared", result.code().contains("float node_s_R;"));
        assertTrue("G is declared", result.code().contains("float node_s_G;"));
        assertTrue("B is declared even though nothing downstream reads it — a node emits every "
                + "output it declares, not only the ones currently wired", result.code().contains("float node_s_B;"));
        assertTrue("A is declared for the same reason", result.code().contains("float node_s_A;"));
        assertTrue("each channel is assigned from its own swizzle",
                result.code().contains("node_s_R = node_v_Out.r;"));
        assertTrue(result.code().contains("node_s_G = node_v_Out.g;"));
        assertTrue(result.code().contains("node_s_B = node_v_Out.b;"));
        // B is left unconnected on both multiplies, so it stays its literal default ("1.0") — the
        // point being tested is which VARIABLE feeds A, not B.
        assertTrue("R reached its own, independent consumer",
                result.code().contains("node_addR_Out = node_s_R * 1.0;"));
        assertTrue("G reached a DIFFERENT consumer, not R's",
                result.code().contains("node_addG_Out = node_s_G * 1.0;"));
        assertTrue("and both consumers converge on the final combiner",
                result.code().contains("node_combine_Out = node_addR_Out * node_addG_Out;"));
    }

    /**
     * A fixed-type input (not {@code DYNAMIC}) still promotes a narrower value fed into it — the same
     * compiler-owned cast every dynamic node relies on, just triggered by a plain type mismatch instead
     * of resolution. {@code SPLIT.In} is a fixed {@code vec4}, so a bare float wired into it must widen
     * exactly as {@code Multiply}'s dynamic ports do.
     */
    @Test
    public void splitPromotesAFloatFedIntoItsFixedVec4Input() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("f", constantFloat()))
                .add(CgShaderGraph.Instance.of("s", CgBuiltinShaderNodes.SPLIT))
                .link("f", "Out", "s", "In")
                .output("s");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("a float feeding a vec4 port must be promoted, the same rule dynamic nodes use",
                result.code().contains("node_s_R = vec4(node_f_Out).r;"));
    }

    /** Each output's TYPE is independently recorded, not just its name — {@link CgGraphCompiler.Result}
     * is what a preview thumbnail reads to know what a specific port resolved to. */
    @Test
    public void eachOutputsTypeIsIndividuallyRecorded() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("v", constantVec4()))
                .add(CgShaderGraph.Instance.of("s", CgBuiltinShaderNodes.SPLIT))
                .link("v", "Out", "s", "In")
                .output("s");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertEquals(CgShaderType.FLOAT, result.typeOf("node_s_R"));
        assertEquals(CgShaderType.FLOAT, result.typeOf("node_s_G"));
        assertEquals(CgShaderType.FLOAT, result.typeOf("node_s_B"));
        assertEquals(CgShaderType.FLOAT, result.typeOf("node_s_A"));
    }
}
