package com.crystalgraphics.shadergraph;

import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.11 — the Vertex and Fragment blocks of the master node.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Same load-bearing assertion as {@code CgShaderEmitterTest}: that the emitted file <b>parses</b>
 * through the real {@code CgShaderParser}. A substring check passes happily while emitting GLSL the
 * parser rejects, and every test here adds a new statement to the fragment body — exactly where that
 * would go wrong.</p>
 *
 * <h3>Why the block is so much shorter than Unity's</h3>
 * <p>No lighting model exists: {@code CgFrameBlock} carries no light term and
 * {@code CgFrameData.hasDirectionalLight()} returns false. Metallic, Smoothness, Ambient Occlusion,
 * Emission and a tangent-space Normal would each be a port that accepts a wire and changes no pixel.
 * {@link #theBlocksOfferOnlyWhatSomethingConsumes()} pins that decision so it is a stated one rather
 * than a gap someone fills in by reflex.</p>
 */
public class CgMasterBlocksTest {

    private static CgShaderNode colour4() {
        return CgTemplateShaderNode.of("t:colour4")
                .out("Out", CgShaderType.VEC4)
                .body("{Out} = vec4(0.2, 0.4, 0.8, 0.5);")
                .build();
    }

    private static CgShaderNode colour3() {
        return CgTemplateShaderNode.of("t:colour3")
                .out("Out", CgShaderType.VEC3)
                .body("{Out} = vec3(0.2, 0.4, 0.8);")
                .build();
    }

    private static CgShaderNode scalar() {
        return CgTemplateShaderNode.of("t:scalar")
                .out("Out", CgShaderType.FLOAT)
                .body("{Out} = 0.25;")
                .build();
    }

    private static CgShaderEmitter.Result emit(CgShaderGraph graph, CgMasterNode master) {
        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);
        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the emitted source must parse", CgShaderParser.parse(result.source()));
        return result;
    }

    // ── The blocks ──────────────────────────────────────────────────────────

    /**
     * The scope decision, stated as an assertion.
     *
     * <p>Written as an exact list rather than "contains Alpha" so that <b>adding</b> a port is also a
     * deliberate act. The whole point of the reasoning is that a port must have a consumer; a test that
     * only checked for presence would wave through the next one added by analogy with Unity.</p>
     */
    @Test
    public void theBlocksOfferOnlyWhatSomethingConsumes() {
        assertEquals(java.util.List.of(CgMasterNode.POSITION),
                CgMasterNode.VERTEX_PORTS.stream().map(CgShaderPort::id).toList());
        assertEquals(java.util.List.of(CgMasterNode.BASE_COLOR, CgMasterNode.ALPHA,
                        CgMasterNode.ALPHA_CLIP_THRESHOLD),
                CgMasterNode.FRAGMENT_PORTS.stream().map(CgShaderPort::id).toList());
    }

    @Test
    public void everyBlockPortResolvesToItsOwnStage() {
        assertEquals(CgShaderDomain.VERTEX, CgMasterNode.blockOf(CgMasterNode.POSITION));
        assertEquals(CgShaderDomain.FRAGMENT, CgMasterNode.blockOf(CgMasterNode.BASE_COLOR));
        assertEquals(CgShaderDomain.FRAGMENT, CgMasterNode.blockOf(CgMasterNode.ALPHA));
        assertEquals(CgShaderDomain.FRAGMENT, CgMasterNode.blockOf(CgMasterNode.ALPHA_CLIP_THRESHOLD));
        // Not a throw: an unknown port is reported by the compiler through other means, and blowing up
        // inside a stage walk turns a clear message into a stack trace.
        assertEquals(CgShaderDomain.ANY, CgMasterNode.blockOf("Metallic"));
    }

    // ── The generated file has to be legal GLSL, not merely a legal .shader ─

    /**
     * <b>{@code struct v2f} is never empty.</b>
     *
     * <p>GLSL has no empty struct — {@code struct v2f { };} is a compile error, not an empty type. This
     * shipped with 6.3.5 and hid for as long as nothing fed the emitter's output to a driver:
     * {@code CgShaderParser} accepts it (structurally it is a fine {@code .shader}), so every test passed
     * and the editor's source pane displayed something that looked entirely correct. The first thing to
     * actually compile one was the main preview, which drew a plain white sphere — the material fallback
     * — for a graph whose GLSL was right in every other respect. The driver's own words were
     * {@code error C0000: syntax error, unexpected '}' at token "}"}, followed by a cascade about
     * {@code cg_InstanceId} that had nothing to do with anything.</p>
     *
     * <p>Asserted on the <b>zero-varying</b> case specifically, because that is the only one that can
     * produce it and it is also the most common graph there is: anything purely fragment-side.</p>
     */
    @Test
    public void aGraphWithNoVaryingsStillEmitsALegalStruct() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colour3()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = emit(graph, master);
        assertTrue("this fixture is meant to have no varyings", result.varyings().isEmpty());
        assertFalse("an empty struct will not compile in any GLSL version",
                result.source().replace(" ", "").replace("\n", "").contains("structv2f{}"));
    }

    // ── Preview shading ─────────────────────────────────────────────────────

    /**
     * <b>The shipped shader is never lit.</b>
     *
     * <p>The whole safety of preview shading rests on this: the mode is a preview convenience, and the
     * moment it leaked into what {@code CgMaterial} loads in game, a graph would depend on lighting the
     * pipeline does not have. Asserted on the default overload, since that is what every non-preview
     * caller reaches for.</p>
     */
    @Test
    public void theDefaultEmitIsUnlit() {
        String source = emit(litFixture(), new CgMasterNode()).source();
        assertFalse(source, source.contains("cg_lit"));
        assertFalse(source, source.contains("v_cg_preview_normal"));
    }

    /** The lit variant still has to be a legal {@code .shader} — the same bar every other test here sets. */
    @Test
    public void thePreviewLitVariantParsesAndShades() {
        CgMasterNode master = new CgMasterNode();
        CgShaderEmitter.Result result = CgShaderEmitter.emit(litFixture(), master,
                CgShaderEmitter.Shading.PREVIEW_LIT);

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the lit variant must parse", CgShaderParser.parse(result.source()));

        String source = result.source();
        assertTrue("it needs a normal to shade with", source.contains("v_cg_preview_normal"));
        assertTrue("the vertex stage must write it",
                source.contains("CG_NORMAL_MATRIX * cg_Normal"));
        assertTrue("and the output goes through the lit colour", source.contains("fragColor = vec4(cg_lit"));
    }

    /**
     * The key light is <b>world-fixed</b>, which is the entire reason rotation reads.
     *
     * <p>A light baked in view space is a headlight: it turns with the camera, the shading barely changes
     * as the mesh orbits, and the preview goes back to being as uninformative as the unlit one it
     * replaced. The tell is the view matrix appearing on the light direction.</p>
     */
    @Test
    public void theKeyLightIsWorldFixedRatherThanAHeadlight() {
        String source = CgShaderEmitter.emit(litFixture(), new CgMasterNode(),
                CgShaderEmitter.Shading.PREVIEW_LIT).source();
        assertTrue("the world light must be rotated into view space, not written in it",
                source.contains("cg_l = normalize(mat3(cg_ViewMatrix) * normalize(vec3("));
    }

    /**
     * A specular term is present, and it is <b>added</b> rather than folded into the base colour.
     *
     * <p>That is what lets a black surface still read as curved — the case Unity's own default preview
     * demonstrates, with its albedo set to black and the sphere still perfectly legible. Diffuse alone on
     * a dark base is barely better than unlit.</p>
     */
    @Test
    public void specularIsAddedSoADarkSurfaceStillReadsAsCurved() {
        String source = CgShaderEmitter.emit(litFixture(), new CgMasterNode(),
                CgShaderEmitter.Shading.PREVIEW_LIT).source();
        assertTrue(source, source.contains("pow(cg_ndh"));
        assertTrue("specular must be added on top of the diffuse product, not multiplied into it",
                source.contains("cg_ndl)\n                + vec3("));
    }

    /** Lit mode always contributes a varying, so the empty-struct guard cannot also fire. */
    @Test
    public void theLitStructIsNeverPaddedAsWellAsFilled() {
        String source = CgShaderEmitter.emit(litFixture(), new CgMasterNode(),
                CgShaderEmitter.Shading.PREVIEW_LIT).source();
        assertFalse("a struct with a real field must not also get the empty-struct padding",
                source.contains("float unused;"));
    }

    private static CgShaderGraph litFixture() {
        CgMasterNode master = new CgMasterNode();
        return new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colour3()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");
    }

    // ── Alpha ───────────────────────────────────────────────────────────────

    /** Base Color is a vec3 and Alpha is its own port; the two are composed at the very end. */
    @Test
    public void baseColorAndAlphaComposeIntoTheOutput() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colour3()))
                .add(CgShaderGraph.Instance.of("a", scalar()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .link("a", "Out", "out", CgMasterNode.ALPHA)
                .output("out");

        String source = emit(graph, master).source();
        assertTrue(source, source.contains("float cg_alpha = node_a_Out;"));
        assertTrue(source, source.contains("fragColor = vec4(node_c_Out, cg_alpha);"));
    }

    /**
     * Alpha is resolved into a local first.
     *
     * <p>Not tidiness: the expression may be an arbitrary node chain, and the clip test reads it as well
     * as the output does. Written twice, whatever produced it would run twice.</p>
     */
    @Test
    public void alphaIsEvaluatedOnce() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("a", scalar()))
                .add(CgShaderGraph.Instance.of("t", scalar()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("a", "Out", "out", CgMasterNode.ALPHA)
                .link("t", "Out", "out", CgMasterNode.ALPHA_CLIP_THRESHOLD)
                .output("out");

        String source = emit(graph, master).source();
        // The alpha expression is bound once, and both readers go through the local rather than
        // re-evaluating it. Asserting on `cg_alpha` rather than on `node_a_Out`, because the latter also
        // appears in its own declaration and assignment — counting those measured nothing.
        assertEquals("bound exactly once", 1, occurrences(source, "float cg_alpha = "));
        assertTrue("the clip test reads the local", source.contains("if (cg_alpha < node_t_Out) discard;"));
        assertTrue("and so does the output", source.contains(", cg_alpha);"));
    }

    // ── Alpha clipping ──────────────────────────────────────────────────────

    /**
     * <b>No {@code discard} unless clipping was asked for.</b>
     *
     * <p>A constant {@code < 0.0} is dead code any driver strips, so this is not about speed. It is that
     * {@code discard} is the most misread instruction in a fragment body, and leaving one in every shader
     * in the project guarantees someone eventually concludes their opaque material is alpha-testing.</p>
     */
    @Test
    public void anUnclippedGraphEmitsNoDiscard() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("out", master))
                .output("out");

        assertFalse(emit(graph, master).source().contains("discard"));
    }

    @Test
    public void aNonZeroThresholdLiteralEmitsADiscard() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(new CgShaderGraph.Instance("out", master,
                        java.util.Map.of(CgMasterNode.ALPHA_CLIP_THRESHOLD, "0.5")))
                .output("out");

        String source = emit(graph, master).source();
        assertTrue(source, source.contains("if (cg_alpha < 0.5) discard;"));
    }

    // ── Narrowing at the master boundary ────────────────────────────────────

    /**
     * A {@code vec4} wired into {@code BaseColor} keeps its RGB rather than failing in the driver.
     *
     * <p>The master is the one place a truncating swizzle is applied to a <em>user-drawn</em> edge.
     * Ordinary ports refuse it, deliberately — but the master never reaches that check at all, because it
     * emits no code and so has no inputs for the compiler to resolve. Without the carve-out this produces
     * {@code vec4(someVec4, cg_alpha)}, which the parser accepts and the driver does not.</p>
     */
    @Test
    public void aVec4IntoBaseColorKeepsItsRgb() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colour4()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        String source = emit(graph, master).source();
        assertTrue(source, source.contains("fragColor = vec4(node_c_Out.xyz, cg_alpha);"));
    }

    /**
     * <b>A vertex-domain node wired straight into Base Color still narrows.</b>
     *
     * <p>{@code UV} into {@code Base Color} is the everyday case and it produced a white surface. The
     * node is {@code VERTEX}-domain, so it is hoisted into the vertex stage and crosses as a varying —
     * meaning its variable is declared over <em>there</em>, and the fragment stage's own type map knows
     * nothing about it. {@link CgShaderEmitter} read that missing entry as "nothing to convert" and wrote
     * the vec4 into {@code vec4(..., cg_alpha)} whole, which is a GLSL error, so the material fell back
     * to white.</p>
     *
     * <p>The symptom pointed the wrong way entirely: putting <em>any</em> fragment-stage node in between —
     * a Fraction, say — fixed it, because then the variable really was declared in the stage being
     * asked. That reads as "the direct connection is unsupported" rather than as a lookup in one map too
     * few.</p>
     */
    @Test
    public void aHoistedVertexNodeIntoBaseColorIsStillNarrowed() {
        CgShaderNode uv = CgTemplateShaderNode.of("t:uv")
                .out("Out", CgShaderType.VEC4)
                .domain(CgShaderDomain.VERTEX)
                .body("{Out} = vec4(cg_TexCoord0, 0.0, 0.0);")
                .build();

        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("uv", uv))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("uv", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = emit(graph, master);
        assertFalse("this fixture is meant to cross a varying", result.varyings().isEmpty());

        String source = result.source();
        assertTrue("the varying must be swizzled down to a vec3, not written whole: " + source,
                source.contains(".xyz, cg_alpha)"));
        assertFalse("a bare vec4 into vec4(...) will not compile",
                source.contains("fragColor = vec4(i.v_uv_Out, cg_alpha);"));
    }

    /** The mirror: a scalar widens, exactly as it does into any other port. */
    @Test
    public void aScalarIntoBaseColorIsPromoted() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("s", scalar()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("s", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        String source = emit(graph, master).source();
        assertTrue("a float must become a vec3 rather than being written bare",
                source.contains("fragColor = vec4(vec3(node_s_Out)")
                        || source.contains("fragColor = vec4(vec3(node_s_Out, node_s_Out, node_s_Out)"));
    }

    // ── Multiple roots in one stage ─────────────────────────────────────────

    /**
     * <b>One node feeding two master ports is compiled once.</b>
     *
     * <p>The fragment stage now has three roots rather than one, and each is compiled by the same
     * subgraph walk. Without carrying the emitted set forward between them, a node reachable from two
     * roots is declared twice — a redeclaration error the parser cannot see, because it is legal
     * {@code .shader} structure containing illegal GLSL.</p>
     */
    @Test
    public void aNodeFeedingTwoPortsIsDeclaredOnce() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("s", scalar()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("s", "Out", "out", CgMasterNode.BASE_COLOR)
                .link("s", "Out", "out", CgMasterNode.ALPHA)
                .output("out");

        String source = emit(graph, master).source();
        assertEquals("declared exactly once", 1, occurrences(source, "float node_s_Out;"));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) count++;
        return count;
    }

    // ── The master's own type checking ──────────────────────────────────────

    private static CgShaderNode texture() {
        return CgTemplateShaderNode.of("t:texture")
                .out("Out", CgShaderType.SAMPLER2D)
                .body("{Out} = _MainTex;")
                .build();
    }

    /**
     * <b>The master is the one place edges were never type-checked, and it emitted garbage instead.</b>
     *
     * <p>Structural rather than an oversight: {@code CgGraphCompiler} validates a link while resolving
     * the consuming node's inputs, and the master emits no code — so it has no inputs to resolve and
     * never reaches that path. Every ordinary edge has been checked since 6.3.3; this was the hole
     * beside them.</p>
     *
     * <p>What made it worth fixing is <em>where</em> it failed. A texture wired into Base Color emitted
     * {@code vec4(node_t_Out, cg_alpha)} — a {@code .shader} that <b>parses</b> and then fails in the
     * driver, surfacing as a white material with the real complaint in a GL log the editor never shows.
     * The assertion is therefore on the error, not on the source: emitting nothing useful is fine, and
     * saying nothing is not.</p>
     */
    @Test
    public void aTextureIntoBaseColorIsReportedRatherThanEmitted() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("t", texture()))
                .add(CgShaderGraph.Instance.of("out", master))
                .link("t", "Out", "out", CgMasterNode.BASE_COLOR)
                .output("out");

        CgShaderEmitter.Result result = CgShaderEmitter.emit(graph, master);
        assertFalse("a sampler is not a colour", result.ok());
        assertTrue("and the message must name both sides: " + result.errors(),
                result.errors().stream().anyMatch(e ->
                        e.contains("sampler2D") && e.contains(CgMasterNode.BASE_COLOR)
                                && e.contains("vec3")));
    }

    /** Vectors still adapt in both directions — the check must not have narrowed what already worked. */
    @Test
    public void vectorsStillReachEveryPort() {
        CgMasterNode master = new CgMasterNode();
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("c", colour4()))
                .add(CgShaderGraph.Instance.of("s", scalar()))
                .add(CgShaderGraph.Instance.of("out", master))
                // vec4 NARROWS into a vec3 port, and a float SPLATS into one.
                .link("c", "Out", "out", CgMasterNode.BASE_COLOR)
                .link("s", "Out", "out", CgMasterNode.ALPHA)
                .output("out");

        emit(graph, master);   // asserts ok() and that the source parses
    }
}
