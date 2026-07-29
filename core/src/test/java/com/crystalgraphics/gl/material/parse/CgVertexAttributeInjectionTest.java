package com.crystalgraphics.gl.material.parse;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
/**
 * End-to-end tests verifying that vertex attribute declarations are correctly injected
 * into the generated GLSL vertex source for multiple vertex formats.
 *
 * <p>Each test parses a real {@code .shader} source string through the full compiler
 * pipeline ({@link CgShaderParser} → {@link CgMaterialShaderCompiler}) and inspects
 * the raw GLSL strings in the returned {@link CgMaterialShaderCompiler.CompiledSource}.
 * No GL context is required — {@link CgCapabilities} is stubbed via reflection.</p>
 *
 * <p>These tests exercise the full pipeline path. The isolated unit tests for
 * {@link CgGlslEmitter#emitVertexInputs} live in {@link CgGlslEmitterTest}.</p>
 */
public class CgVertexAttributeInjectionTest {
    // ── Capabilities stub — no GL context needed ──────────────────────────────
    @BeforeClass
    public static void injectTboCapabilities() throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, CgCapabilities.ShaderBufferPath.TBO);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }
    @AfterClass
    public static void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }
    // ── Shared helpers ────────────────────────────────────────────────────────
    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();
    /**
     * Custom 3-attribute format registered at class-load time so the parser can resolve
     * {@code #type test_vtx_inj_custom}. Uses three different GLSL types:
     * vec3 (position), vec2 (uv), float (single-component scalar).
     */
    private static final CgVertexFormat CUSTOM_3ATTR = CgVertexFormat.builder("test_vtx_inj_custom")
            .add(CgVertexSemantic.POSITION, "vtx_pos",    3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV,       "vtx_uv",     2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.GENERIC,  "vtx_weight", 1, CgAttribType.FLOAT)
            .build();
    /** Minimal pass body that compiles for any format (no format-specific attribute references). */
    private static String minimalPassBlock() {
        return "Pass {\n"
                + "    Tags { \"LightMode\" = \"Forward\" }\n"
                + "    struct v2f { vec2 uv; };\n"
                + "    void vertex(out v2f o) { o.uv = vec2(0.0); }\n"
                + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
                + "}\n";
    }
    /** Builds a minimal shader source string for the given {@code #type} name. */
    private static String shaderOf(String type) {
        return "#type " + type + "\n" + minimalPassBlock();
    }
    private static CgParsedShader parse(String src) {
        return CgShaderParser.parse(src, "test");
    }
    private static CgMaterialShaderCompiler.CompiledSource compile(String src) {
        return CgMaterialShaderCompiler.compile(parse(src), NO_BUFFERS);
    }
    // ── Test 1 — SPATIAL format ───────────────────────────────────────────────
    /**
     * Verifies that the full compiler pipeline injects the three SPATIAL vertex attribute
     * declarations (cg_Position, cg_TexCoord0, cg_Normal) into the vertex source.
     * Exercises the main happy path: #type spatial → CgVertexFormat.SPATIAL → emitVertexInputs.
     */
    @Test
    public void spatial_vertexSource_containsAllThreeAttributes() {
        String vert = compile(shaderOf("spatial")).vertexSource();
        assertTrue("Must contain cg_Position as vec3",  vert.contains("in vec3 cg_Position;"));
        assertTrue("Must contain cg_TexCoord0 as vec2", vert.contains("in vec2 cg_TexCoord0;"));
        assertTrue("Must contain cg_Normal as vec3",    vert.contains("in vec3 cg_Normal;"));
    }
    /**
     * Verifies that the vertex attribute header comment embeds the format debug name.
     * The comment is the first line emitted by emitVertexInputs and serves as a diagnostic marker.
     */
    @Test
    public void spatial_vertexSource_containsFormatKeyNameComment() {
        String vert = compile(shaderOf("spatial")).vertexSource();
        assertTrue("Comment must name the spatial format",
                vert.contains("// Vertex attributes (format: spatial)"));
    }
    /**
     * Verifies that vertex attribute declarations appear AFTER the cg_env.glsl include line.
     * Compiler generation order: #version → defines → #include cg_env.glsl → vertex attributes.
     * Attributes injected before env would produce undefined identifier errors in the GLSL compiler.
     */
    @Test
    public void spatial_vertexSource_attributesAppearAfterEnvInclude() {
        String vert = compile(shaderOf("spatial")).vertexSource();
        int envIdx = vert.indexOf("cg_env.glsl");
        int posIdx = vert.indexOf("in vec3 cg_Position;");
        assertTrue("cg_env.glsl include must be present", envIdx >= 0);
        assertTrue("cg_Position must be present",         posIdx >= 0);
        assertTrue("Vertex attributes must appear AFTER cg_env.glsl include",
                posIdx > envIdx);
    }
    // ── Test 2 — POS3_UV2_COL4UB format ──────────────────────────────────────
    /**
     * Verifies that the 3D-textured-quad format produces correct declarations:
     * 3D position (vec3 FLOAT), UV (vec2 FLOAT), and normalized ubyte4 color (vec4).
     * The color attribute tests the normalized=true → float-family mapping.
     */
    @Test
    public void pos3Uv2Col4ub_vertexSource_containsCorrectDeclarations() {
        String vert = compile(shaderOf("pos3_uv2_col4ub")).vertexSource();
        assertTrue("3D position must be vec3",             vert.contains("in vec3 cg_Position;"));
        assertTrue("UV must be vec2",                      vert.contains("in vec2 cg_TexCoord0;"));
        // normalized UNSIGNED_BYTE×4 → float family → vec4 (not uvec4)
        assertTrue("Normalized ubyte4 color must be vec4", vert.contains("in vec4 cg_Color;"));
        assertFalse("Must not emit non-normalized uvec4",  vert.contains("in uvec4 cg_Color;"));
        assertTrue("Comment must name the format",
                vert.contains("// Vertex attributes (format: pos3_uv2_col4ub)"));
    }
    /**
     * Verifies that CompiledSource.vertexFormat() returns the correct registered instance.
     * The compiler must resolve the format from the registry and propagate it to the caller.
     */
    @Test
    public void pos3Uv2Col4ub_compiledSource_vertexFormatIsCorrect() {
        CgMaterialShaderCompiler.CompiledSource cs = compile(shaderOf("pos3_uv2_col4ub"));
        assertSame("CompiledSource must carry CgVertexFormat.POS3_UV2_COL4UB",
                CgVertexFormat.POS3_UV2_COL4UB, cs.vertexFormat());
    }
    // ── Test 3 — POS2_UV2_COL4UB format ──────────────────────────────────────
    /**
     * Verifies that the 2D-textured-quad format generates a vec2 position declaration —
     * NOT vec3. This confirms the compiler picks the correct format from the registry
     * rather than hard-coding one.
     *
     * <p>Note that {@code pos2}, {@code pos3} and {@code spatial} now all name their position
     * attribute {@code cg_Position} (renamed from {@code a_pos} in commit 328a617, aligning these
     * formats with the {@code cg_*} aliases {@code cg_env.glsl} declares). The component count is
     * therefore what distinguishes them, which is exactly what this asserts.</p>
     */
    @Test
    public void pos2Uv2Col4ub_vertexSource_positionIsVec2NotVec3() {
        String vert = compile(shaderOf("pos2_uv2_col4ub")).vertexSource();
        assertTrue("2D position must be vec2", vert.contains("in vec2 cg_Position;"));
        assertFalse("2D format must NOT produce vec3 position", vert.contains("in vec3 cg_Position;"));
        assertTrue("UV must be vec2",          vert.contains("in vec2 cg_TexCoord0;"));
        assertTrue("Normalized ubyte4 color must be vec4", vert.contains("in vec4 cg_Color;"));
    }
    /**
     * Verifies correct format propagation for the POS2 variant.
     */
    @Test
    public void pos2Uv2Col4ub_compiledSource_vertexFormatIsCorrect() {
        CgMaterialShaderCompiler.CompiledSource cs = compile(shaderOf("pos2_uv2_col4ub"));
        assertSame("CompiledSource must carry CgVertexFormat.POS2_UV2_COL4UB",
                CgVertexFormat.POS2_UV2_COL4UB, cs.vertexFormat());
    }
    // ── Test 4 — Custom format built in-test ─────────────────────────────────
    /**
     * Verifies that a custom format registered at class-load time (CUSTOM_3ATTR) correctly
     * injects all three varied-type attributes. Specifically exercises the single-component
     * float path (vtx_weight: FLOAT×1 → GLSL type "float", not "vec1").
     */
    @Test
    public void customFormat_vertexSource_allThreeAttributesPresent() {
        String vert = compile(shaderOf("test_vtx_inj_custom")).vertexSource();
        assertTrue("Custom vec3 position",   vert.contains("in vec3 vtx_pos;"));
        assertTrue("Custom vec2 UV",         vert.contains("in vec2 vtx_uv;"));
        // single-component FLOAT → "float" (not "vec1")
        assertTrue("Custom float scalar",    vert.contains("in float vtx_weight;"));
        assertTrue("Comment must name custom format",
                vert.contains("// Vertex attributes (format: test_vtx_inj_custom)"));
    }
    /**
     * Verifies that CompiledSource.vertexFormat() returns the exact CUSTOM_3ATTR instance.
     */
    @Test
    public void customFormat_compiledSource_vertexFormatMatchesRegistered() {
        CgMaterialShaderCompiler.CompiledSource cs = compile(shaderOf("test_vtx_inj_custom"));
        assertSame("CompiledSource.vertexFormat() must be CUSTOM_3ATTR",
                CUSTOM_3ATTR, cs.vertexFormat());
    }
    // ── Test 5 — Registry isolation / collision ───────────────────────────────
    /**
     * Verifies that registering two CgVertexFormat instances under the same key name
     * but with different attribute layouts throws IllegalStateException on the second build().
     * Each key name must map to exactly one layout — the registry enforces this invariant.
     */
    @Test
    public void registry_sameNameDifferentLayout_throwsIllegalStateException() {
        // Unique name to avoid interference with any other test or static initializer
        final String name = "test_vtx_collision_7f3a";
        // First build registers successfully (or matches an already-registered equivalent)
        CgVertexFormat.builder(name)
                .add(CgVertexSemantic.POSITION, "a_p", 3, CgAttribType.FLOAT)
                .add(CgVertexSemantic.UV,       "a_u", 2, CgAttribType.FLOAT)
                .build();
        // Second build with a different layout (extra NORMAL attr) must be rejected
        try {
            CgVertexFormat.builder(name)
                    .add(CgVertexSemantic.POSITION, "a_p", 3, CgAttribType.FLOAT)
                    .add(CgVertexSemantic.UV,       "a_u", 2, CgAttribType.FLOAT)
                    .add(CgVertexSemantic.NORMAL,   "a_n", 3, CgAttribType.FLOAT)
                    .build();
            fail("Expected IllegalStateException for duplicate name with different layout");
        } catch (IllegalStateException e) {
            assertTrue("Exception message must mention the conflicting name",
                    e.getMessage().contains(name));
        }
    }
    /**
     * Verifies that two build() calls with the same name AND same layout succeed
     * (value-equal formats are accepted — no collision). Tests the non-throwing side of the
     * registry contract.
     */
    @Test
    public void registry_sameNameSameLayout_succeeds() {
        CgVertexFormat first = CgVertexFormat.builder("test_vtx_same_layout_4b2c")
                .add(CgVertexSemantic.POSITION, "q_pos", 3, CgAttribType.FLOAT)
                .build();
        CgVertexFormat second = CgVertexFormat.builder("test_vtx_same_layout_4b2c")
                .add(CgVertexSemantic.POSITION, "q_pos", 3, CgAttribType.FLOAT)
                .build();
        assertEquals("Value-equal formats must be equals()", first, second);
    }
    // ── Test 6 — Fragment source cleanliness ──────────────────────────────────
    /**
     * Verifies that vertex attribute "in" declarations are NOT present in the fragment
     * source for the SPATIAL format. The emitter injects these only into buildVertexSource()
     * (step 5a) — never into buildFragmentSource(). The v2f "in" interface block is different
     * from attribute "in" declarations — we check the specific attribute names.
     */
    @Test
    public void spatial_fragmentSource_mustNotContainAnyVertexAttributeDeclaration() {
        String frag = compile(shaderOf("spatial")).fragmentSource();
        assertFalse("Fragment must NOT contain in vec3 cg_Position",
                frag.contains("in vec3 cg_Position;"));
        assertFalse("Fragment must NOT contain in vec2 cg_TexCoord0",
                frag.contains("in vec2 cg_TexCoord0;"));
        assertFalse("Fragment must NOT contain in vec3 cg_Normal",
                frag.contains("in vec3 cg_Normal;"));
        assertFalse("Fragment must NOT contain the vertex-attributes comment header",
                frag.contains("// Vertex attributes (format:"));
    }
    /**
     * Same fragment-cleanliness verification for POS3_UV2_COL4UB — exercises a format with
     * different attribute names to confirm the exclusion is not SPATIAL-specific.
     */
    @Test
    public void pos3Uv2Col4ub_fragmentSource_mustNotContainAnyVertexAttributeDeclaration() {
        String frag = compile(shaderOf("pos3_uv2_col4ub")).fragmentSource();
        assertFalse("Fragment must NOT contain in vec3 a_pos",   frag.contains("in vec3 a_pos;"));
        assertFalse("Fragment must NOT contain in vec2 a_uv",    frag.contains("in vec2 a_uv;"));
        assertFalse("Fragment must NOT contain in vec4 a_color", frag.contains("in vec4 a_color;"));
        assertFalse("Fragment must NOT contain the vertex-attributes comment header",
                frag.contains("// Vertex attributes (format:"));
    }
    // ── Test 7 — Unknown format rejection ────────────────────────────────────
    /**
     * Verifies that parsing a shader with an unregistered #type name fails fast at parse
     * time (CgStructureParser.parseShaderType) with a CgShaderParseException that names
     * the unknown type and lists the registered types. This prevents confusing "null format"
     * errors from surfacing later in the compile pipeline.
     */
    @Test
    public void unknownShaderType_atParseTime_throwsCgShaderParseException() {
        String src = "#type unknown_format_xyz\n" + minimalPassBlock();
        try {
            CgShaderParser.parse(src, "test");
            fail("Expected CgShaderParseException for unknown #type");
        } catch (CgShaderParseException e) {
            String msg = e.getMessage();
            assertTrue("Exception message must contain the unknown type name",
                    msg.contains("unknown_format_xyz"));
            assertTrue("Exception message must list registered types",
                    msg.toLowerCase().contains("registered types"));
        }
    }
    // ── Test 8 — Attribute ordering ───────────────────────────────────────────
    /**
     * Verifies that SPATIAL vertex attribute declarations appear in definition order:
     * cg_Position (location 0) → cg_TexCoord0 (location 1) → cg_Normal (location 2).
     * Ordering matters because glBindAttribLocation assigns sequential locations by
     * declaration order; swapped declarations would mis-wire the VAO.
     */
    @Test
    public void spatial_vertexSource_attributesAppearInDefinitionOrder() {
        String vert = compile(shaderOf("spatial")).vertexSource();
        int posIdx  = vert.indexOf("in vec3 cg_Position;");
        int uvIdx   = vert.indexOf("in vec2 cg_TexCoord0;");
        int normIdx = vert.indexOf("in vec3 cg_Normal;");
        assertTrue("cg_Position must appear",    posIdx >= 0);
        assertTrue("cg_TexCoord0 must appear",   uvIdx >= 0);
        assertTrue("cg_Normal must appear",      normIdx >= 0);
        assertTrue("cg_Position (loc 0) must precede cg_TexCoord0 (loc 1)", posIdx < uvIdx);
        assertTrue("cg_TexCoord0 (loc 1) must precede cg_Normal (loc 2)",   uvIdx < normIdx);
    }
    /**
     * Verifies attribute ordering for the custom 3-attribute format:
     * vtx_pos (loc 0) → vtx_uv (loc 1) → vtx_weight (loc 2).
     */
    @Test
    public void customFormat_vertexSource_attributesAppearInDefinitionOrder() {
        String vert = compile(shaderOf("test_vtx_inj_custom")).vertexSource();
        int posIdx    = vert.indexOf("in vec3 vtx_pos;");
        int uvIdx     = vert.indexOf("in vec2 vtx_uv;");
        int weightIdx = vert.indexOf("in float vtx_weight;");
        assertTrue("vtx_pos must appear",    posIdx >= 0);
        assertTrue("vtx_uv must appear",     uvIdx >= 0);
        assertTrue("vtx_weight must appear", weightIdx >= 0);
        assertTrue("vtx_pos must precede vtx_uv",    posIdx < uvIdx);
        assertTrue("vtx_uv must precede vtx_weight", uvIdx < weightIdx);
    }
}