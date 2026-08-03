package com.crystalgraphics.shadergraph;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P6.3.6 — the fourth volume batch: Channel's Combine/Flip, three UV nodes with a direct {@code
 * uv.glsl} call, three UV nodes whose formulas are new to {@code uv.glsl} this batch, Utility's Logic
 * set, and six of Procedural's nine (Voronoi and the two Polygon shapes deferred — see {@link
 * CgBuiltinShaderNodes}'s own class doc). See {@link CgBuiltinMathNodesTest} and its siblings for the
 * earlier batches and the shared testing approach.
 */
public class CgBuiltinMathNodesBatch4Test {

    /**
     * What an untouched {@code UV} port compiles to now that it carries a
     * {@link CgShaderPort#implicitDefault} rather than a literal — see
     * {@link CgImplicitPortDefaultTest} for the mechanism itself.
     *
     * <p>Every UV-consuming node below reads this instead of the {@code vec2(0.5, 0.5)} it used to. That
     * old literal is exactly the bug: it made a node's own preview one flat colour, because every pixel
     * evaluated the same input. {@code .xy} because {@code UV.Out} is a vec4 (matching Unity's
     * {@code UV Out(4)}) and these ports want a vec2.</p>
     */
    private static final String UV = "node_implicit_cg_Input_Geometry_uv_Out.xy";

    private static CgGraphCompiler.Result compileResult(CgShaderNode node) {
        CgShaderGraph graph = new CgShaderGraph()
                .add(CgShaderGraph.Instance.of("n", node))
                .output("n");
        CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);
        assertTrue(String.join("\n", result.errors()), result.ok());
        return result;
    }

    private static String compile(CgShaderNode node) {
        return compileResult(node).code();
    }

    // ── Channel ─────────────────────────────────────────────────────────────

    @Test
    public void combine() {
        assertTrue(compile(CgBuiltinShaderNodes.COMBINE)
                .contains("node_n_Out = vec4(0.0, 0.0, 0.0, 1.0);"));
    }

    @Test
    public void flip() {
        String code = compile(CgBuiltinShaderNodes.FLIP);
        assertTrue(code.contains("node_n_Out = vec4(false ? 1.0 - vec4(0.0, 0.0, 0.0, 0.0).r "
                + ": vec4(0.0, 0.0, 0.0, 0.0).r, false ? 1.0 - vec4(0.0, 0.0, 0.0, 0.0).g "
                + ": vec4(0.0, 0.0, 0.0, 0.0).g, false ? 1.0 - vec4(0.0, 0.0, 0.0, 0.0).b "
                + ": vec4(0.0, 0.0, 0.0, 0.0).b, false ? 1.0 - vec4(0.0, 0.0, 0.0, 0.0).a "
                + ": vec4(0.0, 0.0, 0.0, 0.0).a);"));
    }

    // ── UV — direct stdlib ──────────────────────────────────────────────────

    @Test
    public void uvRotate() {
        CgGraphCompiler.Result result = compileResult(CgBuiltinShaderNodes.UV_ROTATE);
        assertTrue(result.code().contains(
                "node_n_Out = rotate_uv(" + UV + ", 0.0, vec2(0.5, 0.5));"));
        assertTrue(result.includes().contains("crystalgraphics:shaders/lib/uv.glsl"));
    }

    @Test
    public void tilingAndOffset() {
        assertTrue(compile(CgBuiltinShaderNodes.TILING_AND_OFFSET).contains(
                "node_n_Out = tile_uv(" + UV + ", vec2(1.0, 1.0), vec2(0.0, 0.0));"));
    }

    /**
     * Unity's own node formula, via {@code polar_coordinates_uv} — <b>not</b> the generic
     * {@code cartesian_to_polar_uv} helper, which wraps theta into {@code [0,1]} and made the whole
     * preview a flat green/yellow field with its seam on the wrong axis. Unity's angle is signed, which
     * is what gives the thumbnail its red/green split.
     */
    @Test
    public void polarCoordinates() {
        String code = compile(CgBuiltinShaderNodes.POLAR_COORDINATES);
        assertTrue(code.contains(
                "node_n_Out = polar_coordinates_uv(" + UV + ", vec2(0.5, 0.5), 1.0, 1.0);"));
        assertFalse("must not fall back to the [0,1]-wrapping helper — different convention",
                code.contains("cartesian_to_polar_uv"));
    }

    // ── UV — new formulas ───────────────────────────────────────────────────

    @Test
    public void twirl() {
        assertTrue(compile(CgBuiltinShaderNodes.TWIRL).contains(
                "node_n_Out = twirl_uv(" + UV + ", vec2(0.5, 0.5), 1.0, vec2(0.0, 0.0));"));
    }

    @Test
    public void radialShear() {
        assertTrue(compile(CgBuiltinShaderNodes.RADIAL_SHEAR).contains(
                "node_n_Out = radial_shear_uv(" + UV + ", vec2(0.5, 0.5), vec2(1.0, 1.0), "
                        + "vec2(0.0, 0.0));"));
    }

    @Test
    public void spherize() {
        assertTrue(compile(CgBuiltinShaderNodes.SPHERIZE).contains(
                "node_n_Out = spherize_uv(" + UV + ", vec2(0.5, 0.5), 1.0, vec2(0.0, 0.0));"));
    }

    // ── Utility ▸ Logic ─────────────────────────────────────────────────────

    @Test
    public void and() {
        assertTrue(compile(CgBuiltinShaderNodes.AND).contains("node_n_Out = true && true;"));
        assertTrue(compile(CgBuiltinShaderNodes.AND).contains("bool node_n_Out;"));
    }

    @Test
    public void or() {
        assertTrue(compile(CgBuiltinShaderNodes.OR).contains("node_n_Out = false || false;"));
    }

    @Test
    public void not() {
        assertTrue(compile(CgBuiltinShaderNodes.NOT).contains("node_n_Out = !false;"));
    }

    @Test
    public void nand() {
        assertTrue(compile(CgBuiltinShaderNodes.NAND).contains("node_n_Out = !(true && true);"));
    }

    @Test
    public void comparisonDefaultsToEqual() {
        assertTrue(compile(CgBuiltinShaderNodes.COMPARISON).contains("node_n_Out = 0.0 == 0.0;"));
    }

    @Test
    public void comparisonEveryVariant() {
        assertEquals("Equal", CgBuiltinShaderNodes.COMPARISON.property("Condition").options().get(0));
        String[][] cases = {
                {"NotEqual", "!="}, {"Less", "<"}, {"LessOrEqual", "<="},
                {"Greater", ">"}, {"GreaterOrEqual", ">="},
        };
        for (String[] c : cases) {
            CgShaderGraph graph = new CgShaderGraph()
                    .add(new CgShaderGraph.Instance("n", CgBuiltinShaderNodes.COMPARISON,
                            java.util.Map.of(), java.util.Map.of("Condition", c[0])))
                    .output("n");
            CgGraphCompiler.Result result = CgGraphCompiler.compile(graph);
            assertTrue(String.join("\n", result.errors()), result.ok());
            assertTrue(c[0], result.code().contains("node_n_Out = 0.0 " + c[1] + " 0.0;"));
        }
    }

    @Test
    public void branch() {
        assertTrue(compile(CgBuiltinShaderNodes.BRANCH).contains("node_n_Out = true ? 1.0 : 0.0;"));
    }

    // ── Procedural ──────────────────────────────────────────────────────────

    @Test
    public void checkerboard() {
        assertTrue(compile(CgBuiltinShaderNodes.CHECKERBOARD).contains(
                "node_n_Out = mix(vec4(1.0, 1.0, 1.0, 1.0), vec4(0.0, 0.0, 0.0, 1.0), "
                        + "mod(floor(" + UV + ".x * vec2(2.0, 2.0).x) "
                        + "+ floor(" + UV + ".y * vec2(2.0, 2.0).y), 2.0));"));
    }

    @Test
    public void simpleNoise() {
        CgGraphCompiler.Result result = compileResult(CgBuiltinShaderNodes.SIMPLE_NOISE);
        assertTrue(result.code().contains("node_n_Out = value_noise(" + UV + " * 10.0);"));
        assertTrue(result.includes().contains("crystalgraphics:shaders/lib/noise.glsl"));
    }

    @Test
    public void gradientNoise() {
        assertTrue(compile(CgBuiltinShaderNodes.GRADIENT_NOISE).contains(
                "node_n_Out = fbm4(" + UV + " * 10.0);"));
    }

    @Test
    public void ellipse() {
        CgGraphCompiler.Result result = compileResult(CgBuiltinShaderNodes.ELLIPSE);
        assertTrue(result.code().contains(
                "node_n_Out = sdf_coverage(length((" + UV + " - vec2(0.5, 0.5)) "
                        + "/ vec2(0.25, 0.25)) - 1.0);"));
        assertEquals(CgShaderDomain.FRAGMENT, CgBuiltinShaderNodes.ELLIPSE.domain());
    }

    @Test
    public void rectangle() {
        assertTrue(compile(CgBuiltinShaderNodes.RECTANGLE).contains(
                "node_n_Out = sdf_coverage(sdf_rounded_box(" + UV + " - vec2(0.5, 0.5), "
                        + "vec2(0.25, 0.25), 0.0));"));
    }

    @Test
    public void roundedRectangle() {
        assertTrue(compile(CgBuiltinShaderNodes.ROUNDED_RECTANGLE).contains(
                "node_n_Out = sdf_coverage(sdf_rounded_box(" + UV + " - vec2(0.5, 0.5), "
                        + "vec2(0.25, 0.25), 0.1));"));
    }

    /** All three shapes must declare FRAGMENT — {@code sdf_coverage} is {@code fwidth}-based and does
     * not compile in a vertex shader, same rule the Derivative batch already pins. */
    @Test
    public void shapesAreFragmentOnly() {
        assertEquals(CgShaderDomain.FRAGMENT, CgBuiltinShaderNodes.RECTANGLE.domain());
        assertEquals(CgShaderDomain.FRAGMENT, CgBuiltinShaderNodes.ROUNDED_RECTANGLE.domain());
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Test
    public void everyNodeInThisBatchIsRegistered() {
        CgShaderNodeRegistry registry = new CgShaderNodeRegistry();
        CgBuiltinShaderNodes.registerAll(registry);
        for (CgShaderNode node : new CgShaderNode[] {
                CgBuiltinShaderNodes.COMBINE, CgBuiltinShaderNodes.FLIP,
                CgBuiltinShaderNodes.UV_ROTATE, CgBuiltinShaderNodes.TILING_AND_OFFSET,
                CgBuiltinShaderNodes.POLAR_COORDINATES, CgBuiltinShaderNodes.TWIRL,
                CgBuiltinShaderNodes.RADIAL_SHEAR, CgBuiltinShaderNodes.SPHERIZE,
                CgBuiltinShaderNodes.AND, CgBuiltinShaderNodes.OR, CgBuiltinShaderNodes.NOT,
                CgBuiltinShaderNodes.NAND, CgBuiltinShaderNodes.COMPARISON, CgBuiltinShaderNodes.BRANCH,
                CgBuiltinShaderNodes.CHECKERBOARD, CgBuiltinShaderNodes.SIMPLE_NOISE,
                CgBuiltinShaderNodes.GRADIENT_NOISE, CgBuiltinShaderNodes.ELLIPSE,
                CgBuiltinShaderNodes.RECTANGLE, CgBuiltinShaderNodes.ROUNDED_RECTANGLE,
        }) {
            assertEquals(node, registry.get(node.id()));
        }
    }
}
