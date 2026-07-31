package com.crystalgraphics.shadergraph;

import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.4 / 6.3.5 — the stage split, and a graph becoming a whole {@code .shader}.
 *
 * <h3>What is actually being asserted</h3>
 * <p>That the emitted file <b>parses</b>. Every other test in this package checks a fragment of GLSL;
 * this one runs the real {@code CgShaderParser} over the whole output, which is the only way to know the
 * generated file is a {@code .shader} rather than something that merely looks like one. Still no GL —
 * parsing is structural, and the driver's opinion is a separate question.</p>
 */
public class CgShaderEmitterTest {

    private static CgShaderNode colourConstant() {
        return CgTemplateShaderNode.of("cg:input/colour")
                .out("Out", CgShaderType.VEC4)
                .body("{Out} = vec4(0.2, 0.4, 0.8, 1.0);")
                .build();
    }

    /** Vertex-only by declaration: it reads a vertex attribute, which the fragment stage does not have. */
    private static CgShaderNode objectPosition() {
        return CgTemplateShaderNode.of("cg:input/position")
                .out("Out", CgShaderType.VEC3)
                .domain(CgShaderDomain.VERTEX)
                .body("{Out} = cg_Position;")
                .build();
    }

    private static CgShaderNode toColour() {
        return CgTemplateShaderNode.of("cg:util/to-colour")
                .in("In", CgShaderType.VEC3, "vec3(0.0)")
                .out("Out", CgShaderType.VEC4)
                .body("{Out} = vec4({In}, 1.0);")
                .build();
    }

    // ── A whole file ────────────────────────────────────────────────────────

    /**
     * <b>The generated file parses as a real {@code .shader}.</b>
     *
     * <p>The end-to-end claim of 6.3.5. Anything less — asserting on substrings — would pass happily
     * while emitting a file the parser rejects, which is precisely the failure that would surface as an
     * exception at material load with no obvious cause.</p>
     */
    @Test
    public void aGraphEmitsAShaderThatParses() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colourConstant()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the emitted source must parse", CgShaderParser.parse(result.source()));
        assertTrue(result.source().contains("#type spatial"));
        assertTrue("the colour reaches the output", result.source().contains("fragColor = node_c_Out;"));
    }

    /** The master's settings land in the file rather than being defaults nobody can change. */
    @Test
    public void masterSettingsAndPropertiesAreEmitted() {
        CgMasterNode master = new CgMasterNode()
                .queue("Transparent")
                .renderType("Transparent")
                .property("_Tint", CgShaderType.VEC4, "(1.0, 1.0, 1.0, 1.0)")
                .property("_Toggle", CgShaderType.BOOL, "true");
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colourConstant()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        String source = CgShaderEmitter.emit(graph, master).source();

        assertTrue(source.contains("Queue = \"Transparent\""));
        assertTrue(source.contains("\"RenderType\" = \"Transparent\""));
        assertTrue(source.contains("_Tint (\"_Tint\", vec4) = (1.0, 1.0, 1.0, 1.0)"));
        // bool is spelled `boolean` in a Properties block — emitting the GLSL name here would produce
        // a file the parser rejects.
        assertTrue(source, source.contains("_Toggle (\"_Toggle\", boolean) = true"));
        assertNotNull(CgShaderParser.parse(source));
    }

    /** An empty graph still emits a valid shader rather than nothing. */
    @Test
    public void aGraphWithNothingWiredStillEmitsAValidShader() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("out", master))
                .output("out");

        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull(CgShaderParser.parse(result.source()));
        assertTrue("the master's own default is used", result.source().contains("fragColor = vec4(1.0"));
        assertTrue("and the position default keeps the geometry transforming",
                result.source().contains("vec4(cg_Position, 1.0)"));
    }

    // ── The stage split ─────────────────────────────────────────────────────

    /**
     * <b>A vertex-only node feeding the colour is hoisted into the vertex stage and crosses as a
     * varying.</b>
     *
     * <p>The core of 6.3.4. {@code cg_Position} is a vertex attribute and does not exist in the fragment
     * stage, so a node declaring {@link CgShaderDomain#VERTEX} cannot simply be emitted where it was
     * reached from — it moves, and its value is passed through {@code v2f}. This engine has already
     * shipped the equivalent bug once, when {@code sdf.glsl}'s {@code fwidth} reached the vertex stage
     * and AMD refused the whole gallery.</p>
     */
    @Test
    public void aVertexOnlyNodeCrossesToTheFragmentStageAsAVarying() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("pos", objectPosition()))
                .add(CgShaderGraph.Instance.of("col", toColour()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("pos", "Out", "col", "In")
                .link("col", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertEquals("exactly one value crosses the stage boundary", 1, result.varyings().size());

        String source = result.source();
        assertNotNull(CgShaderParser.parse(source));
        assertTrue("the varying is declared in v2f",
                source.contains("vec3 v_pos_Out;"));
        assertTrue("written in the vertex stage", source.contains("o.v_pos_Out = node_pos_Out;"));
        assertTrue("and read in the fragment stage", source.contains("i.v_pos_Out"));

        // The decisive one: the vertex attribute must not appear in the fragment body at all.
        String fragmentBody = source.substring(source.indexOf("void fragment"));
        assertFalse("a vertex attribute must never reach the fragment stage",
                fragmentBody.contains("cg_Position"));
    }

    /**
     * <b>A fragment-only node feeding the vertex stage is refused, not reordered.</b>
     *
     * <p>The asymmetry that cannot be smoothed over: vertex data reaches the fragment stage through a
     * varying, but the vertex shader has already run by the time a fragment value exists, so there is no
     * direction to pass it in.</p>
     */
    @Test
    public void aFragmentOnlyNodeFeedingTheVertexStageIsReported() {
        CgShaderNode derivative = CgTemplateShaderNode.of("cg:math/fwidth")
                .in("In", CgShaderType.VEC3, "vec3(0.0)")
                .out("Out", CgShaderType.VEC3)
                .domain(CgShaderDomain.FRAGMENT)
                .body("{Out} = fwidth({In});")
                .build();
        CgShaderNode vertexConsumer = CgTemplateShaderNode.of("cg:test/vertex-consumer")
                .in("In", CgShaderType.VEC3, "vec3(0.0)")
                .out("Out", CgShaderType.VEC4)
                .domain(CgShaderDomain.VERTEX)
                .body("{Out} = vec4({In}, 1.0);")
                .build();

        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("d", derivative))
                .add(CgShaderGraph.Instance.of("v", vertexConsumer))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("d", "Out", "v", "In")
                .link("v", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);

        assertFalse(result.ok());
        assertTrue(String.join("\n", result.errors()),
                String.join("\n", result.errors()).contains("fragment-only"));
    }

    /** Domain rules, stated directly — the asymmetry in one place. */
    @Test
    public void vertexReachesFragmentButNotTheReverse() {
        assertTrue(CgShaderDomain.FRAGMENT.canReceiveFrom(CgShaderDomain.VERTEX));
        assertFalse(CgShaderDomain.VERTEX.canReceiveFrom(CgShaderDomain.FRAGMENT));
        assertTrue(CgShaderDomain.ANY.canReceiveFrom(CgShaderDomain.FRAGMENT));
    }

    // ── The end-to-end claim ────────────────────────────────────────────────

}
