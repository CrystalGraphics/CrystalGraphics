package com.crystalgraphics.shadergraph;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * P6.3.6 — {@link CgShaderPort#implicitDefault}: an unconnected port whose default is another NODE.
 *
 * <h3>The bug this exists to kill</h3>
 * <p>Every UV-consuming node ({@code Polar Coordinates}, {@code Checkerboard}, {@code Rounded Rectangle}
 * and nine more) declared its {@code UV} input with the literal default {@code vec2(0.5, 0.5)}. With
 * nothing wired in — the normal state, and the exact state a node preview renders — every pixel therefore
 * evaluated <b>the same input</b>, so every one of those nodes previewed as one flat colour: solid green
 * for Polar Coordinates, solid white for the shapes. Unity does not use a literal there; an untouched
 * UV-typed slot behaves as if the {@code UV} node were wired into it, which is why its previews show real
 * gradients and patterns.</p>
 *
 * <h3>Why a literal could never have been fixed with a different literal</h3>
 * <p>"The current UV" is spelled three different ways depending on where it is compiled: {@code
 * cg_TexCoord0} in a vertex body, {@code i.uv} in a preview's fragment body, and a per-graph {@code v2f}
 * field in a real material's fragment stage. A {@link CgShaderPort#defaultExpression} is one fixed string
 * and can only ever be right in one of the three. Wiring in the real {@link CgBuiltinShaderNodes#UV} node
 * — which already emits the correct form for each context via its own {@code body}/{@code previewBody} —
 * is the only answer correct in all three, which is what {@link #theSameImplicitNodeEmitsPerContext}
 * pins.</p>
 */
public class CgImplicitPortDefaultTest {

    /** The variable {@link CgGraphCompiler}'s synthesized {@code UV} instance declares. */
    private static final String UV_OUT = "node_implicit_cg_Input_Geometry_uv_Out";

    private static CgShaderGraph oneNode(CgShaderNode type) {
        return new CgShaderGraph().add(CgShaderGraph.Instance.of("n", type)).output("n");
    }

    // ── The declaration ─────────────────────────────────────────────────────

    /**
     * A port cannot offer both a literal and an implicit source: the compiler would have to pick one
     * silently, and whichever it picked would make the other look like dead configuration.
     */
    @Test(expected = IllegalArgumentException.class)
    public void aPortCannotDeclareBothALiteralAndAnImplicitSource() {
        new CgShaderPort("UV", CgShaderType.VEC2, CgShaderPort.Direction.INPUT,
                "vec2(0.5, 0.5)", true, () -> CgBuiltinShaderNodes.UV, "Out");
    }

    /** An output is produced, never supplied — an implicit source on one is meaningless. */
    @Test(expected = IllegalArgumentException.class)
    public void anOutputCannotDeclareAnImplicitSource() {
        new CgShaderPort("Out", CgShaderType.VEC2, CgShaderPort.Direction.OUTPUT,
                null, true, () -> CgBuiltinShaderNodes.UV, "Out");
    }

    /**
     * The port carries NO literal default, which is what makes the editor stop offering an inline field
     * for it — {@code ShaderGraphBridge.portFieldFor} returns null on a null {@code defaultExpression}.
     * That is the intended editor-side consequence: Unity shows a UV <em>channel dropdown</em> on such a
     * slot, never a typed-in vec2, so an inline number pair would be inventing an affordance.
     */
    @Test
    public void anImplicitPortHasNoLiteralDefaultForTheEditorToOffer() {
        CgShaderPort uv = CgBuiltinShaderNodes.POLAR_COORDINATES.port("UV");
        assertNotNull(uv);
        assertTrue(uv.hasImplicitSource());
        assertNull("an implicit port must expose no literal, or the editor draws a field for it",
                uv.defaultExpression());
        assertFalse(uv.defaultIsLiteral());
    }

    // ── Compilation ─────────────────────────────────────────────────────────

    /** The whole point: an untouched UV port reads a real, emitted UV value rather than a constant. */
    @Test
    public void anUntouchedImplicitPortIsWiredToItsSourceNode() {
        CgGraphCompiler.Result result = CgGraphCompiler.compile(
                oneNode(CgBuiltinShaderNodes.POLAR_COORDINATES));

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("the UV node must be emitted alongside the consumer",
                result.code().contains("vec4 " + UV_OUT + ";"));
        assertTrue("and the consumer must read it, not a literal",
                result.code().contains("polar_coordinates_uv(" + UV_OUT + ".xy, "));
        assertFalse("the old flat literal must be gone from the UV argument",
                result.code().contains("polar_coordinates_uv(vec2(0.5, 0.5),"));
    }

    /**
     * <b>The same implicit node emits a different expression per compilation context</b> — the reason
     * this is a node reference and not a string. Nothing about the port changes between these two; the
     * {@code UV} node's own {@code body} vs {@code previewBody} does all of it.
     */
    @Test
    public void theSameImplicitNodeEmitsPerContext() {
        String real = CgGraphCompiler.compile(oneNode(CgBuiltinShaderNodes.POLAR_COORDINATES)).code();
        assertTrue("a real material reads the vertex attribute",
                real.contains(UV_OUT + " = vec4(cg_TexCoord0, 0.0, 0.0);"));

        CgPreviewEmitter.Result preview =
                CgPreviewEmitter.emit(oneNode(CgBuiltinShaderNodes.POLAR_COORDINATES), "n");
        assertTrue(String.join("\n", preview.errors()), preview.ok());
        assertTrue("a preview reads its own fragment varying instead",
                preview.source().contains(UV_OUT + " = vec4(i.uv, 0.0, 0.0);"));
    }

    /**
     * A value the user actually typed WINS, exactly as a real connection would.
     *
     * <p>Read from {@link CgShaderGraph.Instance#inputValues}, which holds only what was genuinely
     * stored — {@code ShaderGraphBridge.inputValuesOf} never pre-populates a default — so "untouched" is
     * a question with a real answer rather than a comparison against the port's own default.</p>
     */
    @Test
    public void aTypedLiteralWinsOverTheImplicitSource() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(new CgShaderGraph.Instance("n", CgBuiltinShaderNodes.POLAR_COORDINATES,
                        Map.of("UV", "vec2(0.25, 0.75)")))
                .output("n");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue(result.code().contains("polar_coordinates_uv(vec2(0.25, 0.75), "));
        assertFalse("no UV node should be synthesized when the port was pinned",
                result.code().contains(UV_OUT));
    }

    /** A real wire wins too — the implicit source is a default, not an override. */
    @Test
    public void aRealConnectionWinsOverTheImplicitSource() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", CgBuiltinShaderNodes.POLAR_COORDINATES))
                .add(CgShaderGraph.Instance.of("b", CgBuiltinShaderNodes.TWIRL))
                .link("a", "Out", "b", "UV")
                .output("b");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertTrue("b reads a's output directly", result.code().contains("twirl_uv(node_a_Out, "));
    }

    /**
     * Two consumers share ONE implicit instance — matching what is being ported: Unity's implicit UV is
     * not N separate reads, and the shared instance is what keeps the Nth consumer free.
     */
    @Test
    public void everyConsumerSharesOneImplicitInstance() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", CgBuiltinShaderNodes.CHECKERBOARD))
                .add(CgShaderGraph.Instance.of("b", CgBuiltinShaderNodes.SIMPLE_NOISE))
                .add(new CgShaderGraph.Instance("m", CgBuiltinShaderNodes.MULTIPLY, Map.of()))
                .link("a", "Out", "m", "A")
                .link("b", "Out", "m", "B")
                .output("m");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertEquals("the UV node must be declared exactly once for both consumers",
                1, countOf(result.code(), "vec4 " + UV_OUT + ";"));
    }

    /**
     * <b>Compiling the same graph twice must not accumulate anything.</b> Not hypothetical: {@code
     * CgPreviewRenderer} compiles from a different root every frame against one long-lived {@code
     * CgShaderGraph}, so a non-idempotent wiring pass would add a duplicate instance (which
     * {@link CgShaderGraph#add} throws on) or a duplicate link every single frame.
     */
    @Test
    public void wiringIsIdempotentAcrossRepeatedCompiles() {
        CgShaderGraph graph = oneNode(CgBuiltinShaderNodes.POLAR_COORDINATES);

        CgGraphCompiler.compile(graph);
        int afterFirst = graph.instances().size();
        int linksAfterFirst = graph.links().size();
        CgGraphCompiler.compile(graph);
        CgGraphCompiler.Result third = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", third.errors()), third.ok());
        assertEquals("a second compile must not add another instance", afterFirst, graph.instances().size());
        assertEquals("nor another link", linksAfterFirst, graph.links().size());
        assertEquals(1, countOf(third.code(), "vec4 " + UV_OUT + ";"));
    }

    /**
     * <b>The wiring pass must not walk the live instance collection while adding to it.</b>
     *
     * <p>{@link CgShaderGraph#instances()} is an unmodifiable <em>view</em> over the same map the pass
     * writes into, so iterating it directly threw {@link java.util.ConcurrentModificationException} the
     * moment the first implicit node landed — uncaught, since {@code CgPreviewEmitter.emit} has no
     * try/catch around the compile, so it took down the whole render pipeline through
     * {@code ShaderGraphPreviews.tickFrame} rather than failing one thumbnail. Reproduced here with two
     * implicit consumers, since a single-instance graph can finish its only iteration step before the
     * modification is observed.</p>
     */
    @Test
    public void wiringManyConsumersDoesNotConcurrentlyModifyTheGraph() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", CgBuiltinShaderNodes.CHECKERBOARD))
                .add(CgShaderGraph.Instance.of("b", CgBuiltinShaderNodes.ROUNDED_RECTANGLE))
                .add(CgShaderGraph.Instance.of("c", CgBuiltinShaderNodes.SIMPLE_NOISE))
                .add(CgShaderGraph.Instance.of("d", CgBuiltinShaderNodes.TWIRL))
                .output("a");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertTrue(String.join("\n", result.errors()), result.ok());
    }

    /**
     * Narrowing is allowed ONLY on a link this compiler synthesized. A user-drawn vec4 → vec2 edge is
     * still refused, because picking components silently is how a graph starts lying about what it
     * computes — that belongs in an explicit Split node the user can see.
     */
    @Test
    public void narrowingStaysRefusedForAUserDrawnEdge() {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("uv", CgBuiltinShaderNodes.UV))
                .add(CgShaderGraph.Instance.of("n", CgBuiltinShaderNodes.POLAR_COORDINATES))
                .link("uv", "Out", "n", "Center")
                .output("n");

        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);

        assertFalse("a hand-drawn vec4 -> vec2 edge must still be reported", result.ok());
        assertTrue(String.join("\n", result.errors()),
                result.errors().stream().anyMatch(e -> e.contains("Center")));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) count++;
        return count;
    }
}
