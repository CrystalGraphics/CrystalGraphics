package com.crystalgraphics.shadergraph;

import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.7 — one node becoming a thumbnail.
 *
 * <h3>What is actually being asserted</h3>
 * <p>That a preview is a <b>real, parseable {@code .shader}</b> and that the value reaching
 * {@code fragColor} is the right one, converted the way a user expects to read it. No GL: the driver's
 * opinion is a separate question, and everything decided here is decided before a context exists.</p>
 *
 * <p>These matter disproportionately because a preview is <em>only</em> ever observed as a picture. A
 * wrong conversion does not fail — it draws something plausible, and a float previewing as pure black
 * looks exactly like a float that is genuinely zero.</p>
 */
public class CgPreviewEmitterTest {

    private static CgShaderNode colour() {
        return CgTemplateShaderNode.of("cg:input/colour")
                .out("Out", CgShaderType.VEC4)
                .body("{Out} = vec4(0.2, 0.4, 0.8, 1.0);")
                .build();
    }

    private static CgShaderNode scalar() {
        return CgTemplateShaderNode.of("cg:input/float")
                .in("Value", CgShaderType.FLOAT, "0.5")
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = {Value};")
                .build();
    }

    /** Spatial by declaration — the reason the sphere mode exists at all. */
    private static CgShaderNode position() {
        return CgTemplateShaderNode.of("cg:input/position")
                .out("Out", CgShaderType.VEC3)
                .previewGeometry(CgPreviewGeometry.SPHERE)
                .body("{Out} = i.objectPos;")
                .build();
    }

    private static CgShaderNode multiply() {
        return CgTemplateShaderNode.of("cg:math/multiply")
                .in("A", CgShaderType.DYNAMIC, "1.0")
                .in("B", CgShaderType.DYNAMIC, "1.0")
                .out("Out", CgShaderType.DYNAMIC)
                .body("{Out} = {A} * {B};")
                .build();
    }

    /**
     * The line a preview ends with, for a given colour expression.
     *
     * <p>Built here rather than spelled out four times: the wrapping (sRGB encode, coverage as alpha) is
     * one decision, and a test suite that restates it per case turns any change to it into four
     * unrelated-looking failures.</p>
     */
    private static String fragColorLine(String rgbExpression) {
        return "fragColor = vec4(linear_to_srgb(" + rgbExpression + "), cg_coverage);";
    }

    private static CgShaderGraph graphOf(CgShaderGraph.Instance... instances) {
        CgShaderGraph graph = new CgShaderGraph();
        for (CgShaderGraph.Instance instance : instances) graph.add(instance);
        return graph;
    }

    // ── The file ────────────────────────────────────────────────────────────

    /** <b>A preview is an ordinary {@code .shader}</b>, so nothing downstream needs a special case. */
    @Test
    public void aPreviewIsAParseableShaderFile() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("c1", colour(), java.util.Map.of()));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "c1");

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the preview must parse like any other material",
                CgShaderParser.parse(result.source()));
        assertTrue(result.source(), result.source().contains(fragColorLine("node_c1_Out.rgb")));
    }

    // ── Turning a value into pixels ─────────────────────────────────────────

    /**
     * <b>A float previews as greyscale, not as red.</b>
     *
     * <p>Unity's convention, and the one users read fluently: black at 0, white at 1. Emitting
     * {@code vec4(v, 0, 0, 1)} would be just as "correct" and completely unreadable.</p>
     */
    @Test
    public void aFloatIsShownAsGreyscale() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("f1", scalar(), java.util.Map.of()));

        String source = CgPreviewEmitter.emit(graph, "f1").source();

        assertTrue(source, source.contains(fragColorLine("vec3(node_f1_Out)")));
    }

    /**
     * <b>Alpha is forced to 1 for EVERY type, vec4 included.</b>
     *
     * <p>The vec4 case looks like the one that should pass straight through, and it shipped that way.
     * {@code UV} is {@code vec4(uv, 0, 0)} — alpha 0 — so its thumbnail drew fully transparent, and a
     * blank slot is indistinguishable from one that has not rendered yet. The node that previews best in
     * every reference screenshot was the one node showing nothing.</p>
     */
    @Test
    public void alphaIsForcedForEveryType() {
        // displayRgb returns only the COLOUR — alpha is supplied structurally by the emitter as the
        // geometry's coverage, so no type can contribute a transparent thumbnail.
        assertEquals("v", CgPreviewEmitter.displayRgb("v", CgShaderType.VEC3));
        assertEquals("vec3(v, 0.0)", CgPreviewEmitter.displayRgb("v", CgShaderType.VEC2));
        assertEquals("v.rgb", CgPreviewEmitter.displayRgb("v", CgShaderType.VEC4));
        assertEquals("vec3(v)", CgPreviewEmitter.displayRgb("v", CgShaderType.FLOAT));
    }

    /** The concrete case that caught it: a UV preview must not be transparent. */
    @Test
    public void aUvPreviewIsOpaqueDespiteItsZeroAlpha() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("u1",
                CgBuiltinShaderNodes.UV, java.util.Map.of()));

        String source = CgPreviewEmitter.emit(graph, "u1").source();

        assertTrue(source, source.contains(fragColorLine("node_u1_Out.rgb")));
    }

    /** A matrix or a sampler has no picture, and emitting one anyway produces GLSL the driver refuses. */
    @Test
    public void aTypeWithNoPictureIsRefusedRatherThanEmitted() {
        assertFalse(CgPreviewEmitter.isPreviewable(CgShaderType.MAT4));
        assertFalse(CgPreviewEmitter.isPreviewable(CgShaderType.SAMPLER2D));
        assertTrue(CgPreviewEmitter.isPreviewable(CgShaderType.FLOAT));

        CgShaderNode matrixNode = CgTemplateShaderNode.of("cg:test/matrix")
                .out("Out", CgShaderType.MAT4).body("{Out} = mat4(1.0);").build();
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("m1", matrixNode, java.util.Map.of()));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "m1");

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("no meaningful thumbnail"));
    }

    // ── The dynamic case, which is the whole reason the compiler reports types ──

    /**
     * <b>A dynamic output is displayed by its RESOLVED type, which only the compiler knows.</b>
     *
     * <p>{@code Multiply}'s output type is written nowhere — it is whatever its inputs made it. Reading
     * the declared port type would give {@code DYNAMIC} and there would be nothing to convert; that is
     * why {@code CgGraphCompiler.Result} carries the types it settled on.</p>
     */
    @Test
    public void aDynamicOutputIsDisplayedByItsResolvedType() {
        CgShaderGraph graph = graphOf(
                new CgShaderGraph.Instance("c1", colour(), java.util.Map.of()),
                new CgShaderGraph.Instance("f1", scalar(), java.util.Map.of()),
                new CgShaderGraph.Instance("m1", multiply(), java.util.Map.of()));
        graph.link("c1", "Out", "m1", "A");
        graph.link("f1", "Out", "m1", "B");

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "m1");

        assertTrue(String.join("\n", result.errors()), result.ok());
        // vec4 * float resolved to vec4 — shown as rgb rather than wrapped in vec3(...), which is what
        // a float-resolved output would have produced.
        assertTrue(result.source(), result.source().contains(fragColorLine("node_m1_Out.rgb")));
        assertNotNull(CgShaderParser.parse(result.source()));
    }

    // ── Geometry propagation ────────────────────────────────────────────────

    /** Nothing spatial anywhere: a flat quad, which is right for colours and masks. */
    @Test
    public void anOrdinaryChainPreviewsOnAQuad() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("c1", colour(), java.util.Map.of()));

        assertEquals(CgPreviewGeometry.QUAD, CgPreviewEmitter.emit(graph, "c1").geometry());
    }

    /**
     * <b>The sphere travels downstream.</b>
     *
     * <p>Unity's rule, and the reason it is worth porting: a Multiply fed by a Position is still showing
     * a spatial quantity, and a flat quad would render it as one uniform colour — technically correct,
     * completely uninformative. Only Position declares anything here; Multiply inherits.</p>
     */
    @Test
    public void aSpherePropagatesToEveryNodeDownstream() {
        CgShaderGraph graph = graphOf(
                new CgShaderGraph.Instance("p1", position(), java.util.Map.of()),
                new CgShaderGraph.Instance("f1", scalar(), java.util.Map.of()),
                new CgShaderGraph.Instance("m1", multiply(), java.util.Map.of()));
        graph.link("p1", "Out", "m1", "A");
        graph.link("f1", "Out", "m1", "B");

        assertEquals("the node itself", CgPreviewGeometry.SPHERE,
                CgPreviewGeometry.resolve(graph, "p1"));
        assertEquals("and everything it feeds", CgPreviewGeometry.SPHERE,
                CgPreviewGeometry.resolve(graph, "m1"));
        assertEquals("a sibling it does not feed is unaffected", CgPreviewGeometry.QUAD,
                CgPreviewGeometry.resolve(graph, "f1"));
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    /**
     * A vertex-only node cannot be evaluated in the fragment stage, where previews run.
     *
     * <p>Reported by name rather than emitted, because the alternative is generated GLSL naming a vertex
     * attribute inside a fragment body — a driver error against source the user never wrote.</p>
     */
    @Test
    public void aVertexOnlyNodeIsReportedRatherThanEmittedIntoTheFragmentStage() {
        CgShaderNode vertexOnly = CgTemplateShaderNode.of("cg:input/vertex-thing")
                .out("Out", CgShaderType.VEC3)
                .domain(CgShaderDomain.VERTEX)
                .body("{Out} = cg_Position;")
                .build();
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("v1", vertexOnly, java.util.Map.of()));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "v1");

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("vertex-only"));
    }

    /**
     * <b>A vertex-only node WITH a preview form is previewed, not refused.</b>
     *
     * <p>This is what makes Position, UV and Normal Vector previewable — which matters because they are
     * the nodes a thumbnail exists to show in the first place. The preview compile must emit the form
     * reading the varying, and the real compile must still emit the vertex attribute.</p>
     */
    @Test
    public void aVertexNodeWithAPreviewFormIsPreviewedUsingIt() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of()));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "p1");

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertEquals("a position is spatial, so it previews on a sphere",
                CgPreviewGeometry.SPHERE, result.geometry());
        assertNotNull(CgShaderParser.parse(result.source()));

        // Asserted on the NODE's contribution, not the whole file: the preview's own fixed vertex shader
        // legitimately reads cg_Position to fill the varying, so searching the file for it would fail on
        // the scaffolding rather than on anything the node emitted.
        String nodeCode = CgGraphCompiler.compileFrom(graph, "p1", true).code();
        assertTrue(nodeCode, nodeCode.contains("i.objectPos"));
        assertFalse("the node must not name a vertex attribute in the fragment stage",
                nodeCode.contains("cg_Position"));
    }

    /** The same node still emits the REAL form when it is not a preview — the two must not be confused. */
    @Test
    public void theRealCompileStillUsesTheVertexAttribute() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of()));

        String code = CgGraphCompiler.compileFrom(graph, "p1", false).code();

        assertTrue(code, code.contains("cg_Position"));
        assertFalse(code, code.contains("i.objectPos"));
    }

    /** UV is the flat one, and must stay flat — a gradient on a sphere is not what anyone reads it for. */
    @Test
    public void uvPreviewsOnAQuad() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("u1",
                CgBuiltinShaderNodes.UV, java.util.Map.of()));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "u1");

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertEquals(CgPreviewGeometry.QUAD, result.geometry());
        assertTrue(result.source(), result.source().contains("i.uv"));
    }

    // ── Node properties ─────────────────────────────────────────────────────

    /** <b>The chosen option selects which GLSL the node emits.</b> Object is the default, as Unity has it. */
    @Test
    public void aPropertySelectsTheEmittedVariant() {
        CgShaderGraph objectSpace = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of()));
        CgShaderGraph worldSpace = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of(),
                java.util.Map.of(CgBuiltinShaderNodes.SPACE_ID, CgBuiltinShaderNodes.SPACE_WORLD)));

        String asObject = CgGraphCompiler.compileFrom(objectSpace, "p1", false).code();
        String asWorld = CgGraphCompiler.compileFrom(worldSpace, "p1", false).code();

        assertTrue(asObject, asObject.contains("= cg_Position;"));
        assertFalse("the default must not be the world form", asObject.contains("CG_OBJECT_TO_WORLD"));
        assertTrue(asWorld, asWorld.contains("CG_OBJECT_TO_WORLD"));
    }

    /**
     * <b>An option a stored document names but this build no longer has falls back to the default.</b>
     *
     * <p>A node's option list is code and the document is data, so the two drift across versions. Without
     * the fallback the compiler would look for a variant that does not exist and emit the plain body,
     * which is right by accident here and would not be for a node whose default body is a stub.</p>
     */
    @Test
    public void anUnknownStoredOptionFallsBackToTheDefault() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of(),
                java.util.Map.of(CgBuiltinShaderNodes.SPACE_ID, "Tangent")));

        assertEquals(CgBuiltinShaderNodes.SPACE_OBJECT,
                graph.instance("p1").propertyOr(CgBuiltinShaderNodes.SPACE_ID));
        assertTrue(CgGraphCompiler.compileFrom(graph, "p1", false).code().contains("= cg_Position;"));
    }

    /**
     * View space rotates by the camera, and a NORMAL takes only the rotation.
     *
     * <p>{@code mat3(cg_ViewMatrix)}, never the full {@code mat4}: a normal is a direction, so translating
     * it is meaningless — the full matrix would fold the camera's position into a unit vector and
     * denormalise it by however far the camera happens to be from the origin.</p>
     */
    @Test
    public void viewSpaceUsesOnlyTheRotationForANormal() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("n1",
                CgBuiltinShaderNodes.NORMAL, java.util.Map.of(),
                java.util.Map.of(CgBuiltinShaderNodes.SPACE_ID, CgBuiltinShaderNodes.SPACE_VIEW)));

        String code = CgGraphCompiler.compileFrom(graph, "n1", false).code();

        assertTrue(code, code.contains("mat3(cg_ViewMatrix)"));
    }

    /** A view-space POSITION is a point, so it does take the full transform. */
    @Test
    public void viewSpaceUsesTheFullTransformForAPosition() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of(),
                java.util.Map.of(CgBuiltinShaderNodes.SPACE_ID, CgBuiltinShaderNodes.SPACE_VIEW)));

        String code = CgGraphCompiler.compileFrom(graph, "p1", false).code();

        assertTrue(code, code.contains("cg_ViewMatrix * CG_OBJECT_TO_WORLD"));
        assertFalse("a point is not a direction", code.contains("mat3(cg_ViewMatrix)"));
    }

    /** A property variant still gets its preview form, or the world option would break the thumbnail. */
    @Test
    public void aPropertyVariantStillHasAPreviewForm() {
        CgShaderGraph graph = graphOf(new CgShaderGraph.Instance("p1",
                CgBuiltinShaderNodes.POSITION, java.util.Map.of(),
                java.util.Map.of(CgBuiltinShaderNodes.SPACE_ID, CgBuiltinShaderNodes.SPACE_WORLD)));

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "p1");

        assertTrue(String.join("\n", result.errors()), result.ok());
        String nodeCode = CgGraphCompiler.compileFrom(graph, "p1", true).code();
        assertTrue(nodeCode, nodeCode.contains("i.objectPos"));
        assertFalse("a preview must not name a vertex attribute", nodeCode.contains("cg_Position"));
    }

    /** An output node has nothing to show, and that is not a failure worth shouting about. */
    @Test
    public void aNodeWithNoOutputReportsRatherThanThrowing() {
        CgShaderGraph graph = new CgShaderGraph();

        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, "nope");

        assertFalse(result.ok());
        assertEquals(CgPreviewGeometry.QUAD, result.geometry());
    }

    /**
     * <b>Two nodes computing the same thing emit byte-identical source.</b>
     *
     * <p>Which is what makes preview materials free to cache: {@code CgMaterial.fromSource} is keyed on
     * the content hash, so this is the difference between one compiled program and one per node. It also
     * pins the decision NOT to write the geometry into the file — doing so would split one computation
     * into two sources for no gain.</p>
     */
    @Test
    public void identicalComputationsEmitIdenticalSource() {
        CgShaderGraph a = graphOf(new CgShaderGraph.Instance("x", colour(), java.util.Map.of()));
        CgShaderGraph b = graphOf(new CgShaderGraph.Instance("x", colour(), java.util.Map.of()));

        assertEquals(CgPreviewEmitter.emit(a, "x").source(), CgPreviewEmitter.emit(b, "x").source());
    }
}
