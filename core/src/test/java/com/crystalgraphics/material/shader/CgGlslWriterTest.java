package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static com.crystalgraphics.material.shader.CgShaderSpecTest.global;
import static com.crystalgraphics.material.shader.CgShaderSpecTest.surface;
import static org.junit.Assert.*;

/** The GLSL a spec's passes are written into. Whether it links: the Vulkan module's {@code MaterialFormatTest}. */
public class CgGlslWriterTest {

    private static final String DISCARDS = "#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; }";
    private static final String RETURNS = "#define cg_Clip(v) { if ((v) < CG_CLIP_THRESHOLD) discard; return; }";
    private static final String NOTHING = "#define cg_Clip(v)\n";

    private static CgGlslProgram write(CgShaderSpec spec, Set<String> keywords) {
        return CgGlslWriter.write(spec, spec.pass(CgRenderPassVariant.FORWARD), Collections.emptyList(), null, keywords);
    }

    private static CgGlslProgram write(CgShaderSpec spec) {
        return write(spec, Collections.emptySet());
    }

    @Test
    public void aMaskedMaterialDiscardsBelowItsThreshold() {
        String fragment = write(surface("BlendMode Masked  MaskClip 0.4", "s.opacityMask = i.uv0.x;")).fragment();
        assertTrue(fragment, fragment.contains("#define CG_CLIP_THRESHOLD 0.4"));
        assertTrue(fragment, fragment.contains(DISCARDS));
    }

    @Test
    public void aPrepassClipsOnlyInItsDepthVariant() {
        CgShaderSpec smoke = surface("BlendMode Translucent  DepthPrepass On", "s.opacity = 0.5; s.opacityMask = i.uv0.x;");
        CgGlslProgram blend = write(smoke);
        assertTrue(blend.fragment(), blend.fragment().contains(NOTHING));
        assertTrue(blend.vertex(), blend.vertex().contains("invariant gl_Position;"));

        CgGlslProgram depth = write(smoke, Set.of(CgEngineKeywords.DEPTH_PREPASS));
        assertTrue(depth.fragment(), depth.fragment().contains(RETURNS));
        assertTrue(depth.vertex(), depth.vertex().contains("invariant gl_Position;"));
    }

    @Test
    public void onlyTheKeywordsOnAreDefinedInBothStages() {
        CgShaderSpec spec = CgShaderSpec.read("#domain surface\nKeywords {\n    MOSS (\"Mossy\")\n    WET\n}\n"
                + CgShaderSpecTest.V2F + "void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0); }\n",
                "test:k.shader");
        CgGlslProgram off = write(spec);
        assertFalse(off.vertex().contains("#define MOSS"));
        assertFalse(off.fragment().contains("#define WET"));

        CgGlslProgram moss = write(spec, Set.of("MOSS"));
        assertTrue(moss.vertex().contains("#define MOSS 1"));
        assertTrue(moss.fragment().contains("#define MOSS 1"));
        assertFalse(moss.fragment().contains("#define WET"));
        assertFalse(moss.fragment().contains("#pragma"));
    }

    @Test
    public void whatAProgramNamesIsIncluded() {
        String lights = write(global("", "fragColor = vec4(CG_DRAW_POINT_LIGHT_COLOR(0), 1.0);")).fragment();
        assertTrue(lights, lights.contains("#define CG_HANDED_LIGHTS 1"));
        assertTrue(lights, lights.contains("env/handed/lights.glsl"));

        String sun = write(global("", "fragColor = vec4(vec3(CG_DIRECTIONAL_SHADOW(i.n)), 1.0);")).fragment();
        assertTrue(sun, sun.contains("#define CG_HANDED_SUN 1"));
        assertTrue(sun, sun.contains("env/handed/sun.glsl"));

        String plain = write(global("", "fragColor = vec4(1.0);")).fragment();
        assertFalse(plain.contains("CG_HANDED_LIGHTS"));
    }

    @Test
    public void aBufferIsAStorageBlockInBothStagesAfterItsStruct() {
        CgShaderSpec spec = global("struct Spark { vec4 positionLife; vec4 velocitySeed; };\n"
                + "Buffers {\n    SPARKS (\"Sparks\", Spark, readonly)\n}\n", "fragColor = SPARKS(0u).positionLife;");
        CgGlslProgram p = write(spec);
        for (String stage : new String[]{p.vertex(), p.fragment()}) {
            assertTrue(stage, stage.contains("readonly buffer CgMaterialBuffer_SPARKS { Spark _cg_SPARKS[]; };"));
            assertTrue(stage, stage.contains("Spark SPARKS(uint i)"));
            assertTrue(stage.indexOf("struct Spark") < stage.indexOf("CgMaterialBuffer_SPARKS"));
        }
    }

    @Test
    public void theGeneratedDepthPassKeepsAVertexThatIsNotSimple() {
        CgShaderSpec simple = surface("", "s.baseColor = vec3(1.0);");
        CgShaderPass forward = simple.pass(CgRenderPassVariant.FORWARD);
        assertTrue(forward.simpleVertex());
        CgGlslProgram minimal = CgGlslWriter.writeDepth(simple, forward, Collections.emptyList(), null, Collections.emptySet());
        assertFalse(minimal.fragment(), minimal.fragment().contains("discard"));

        CgShaderSpec waving = CgShaderSpec.read("#domain surface\n" + CgShaderSpecTest.V2F
                + "void vertex(inout CgVertex v, out v2f o) { v.position.y += sin(cg_Time); o.uv0 = v.uv0; }\n"
                + "void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0); }\n", "test:w.shader");
        CgShaderPass waves = waving.pass(CgRenderPassVariant.FORWARD);
        assertFalse(waves.simpleVertex());
        String vertex = CgGlslWriter.writeDepth(waving, waves, Collections.emptyList(), null, Collections.emptySet()).vertex();
        assertTrue(vertex, vertex.contains("sin(cg_Time)"));
    }

    @Test
    public void vertexInputsAreDeclaredAfterTheEnvironment() {
        String vertex = write(surface("", "s.baseColor = vec3(1.0);")).vertex();
        int env = vertex.indexOf("cg_env.glsl"), position = vertex.indexOf("cg_Position;");
        assertTrue(vertex, env >= 0 && position > env);
    }
}
