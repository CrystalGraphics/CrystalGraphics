package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CgMaterialShaderCompilerDepthTest {

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

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

    private static final String SIMPLE_SHADER =
            "#type spatial\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) { o.uv = cg_TexCoord0; }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    private static final String ANIM_SHADER =
            "#type spatial\n"
            + "Properties { _AnimOffset (\"AnimOffset\", float) = 0.0 }\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) {\n"
            + "        vec3 pos = cg_Position;\n"
            + "        pos.y += _AnimOffset;\n"
            + "        gl_Position = CG_MATRIX_MVP * vec4(pos, 1.0);\n"
            + "        o.uv = cg_TexCoord0;\n"
            + "    }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    private static final String ALPHA_CLIP_SHADER =
            "#type spatial\n"
            + "Properties { _Cutoff (\"Cutoff\", float) = 0.5 }\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) {\n"
            + "        vec3 pos = cg_Position;\n"
            + "        pos.y += _Cutoff;\n"
            + "        gl_Position = CG_MATRIX_MVP * vec4(pos, 1.0);\n"
            + "        o.uv = cg_TexCoord0;\n"
            + "    }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) {\n"
            + "        vec4 col = vec4(1.0);\n"
            + "        if (col.a < _Cutoff) discard;\n"
            + "        fragColor = col;\n"
            + "    }\n"
            + "}\n";

    private static final String CG_TIME_SHADER =
            "#type spatial\n"
            + "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f {\n    vec2 uv;\n};\n"
            + "    void vertex(out v2f o) {\n"
            + "        vec3 pos = cg_Position;\n"
            + "        pos.y += sin(cg_Time.x) * 0.5;\n"
            + "        gl_Position = CG_MATRIX_MVP * vec4(pos, 1.0);\n"
            + "        o.uv = cg_TexCoord0;\n"
            + "    }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }\n"
            + "}\n";

    @Test
    public void depthAutoGen_simpleVertex_minimalPositionTransform() {
        CgParsedShader parsed = CgShaderParser.parse(SIMPLE_SHADER, "test");
        CgParsedPass forward = parsed.passes().get(0);
        CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compileDepthAutoGen(
                parsed, forward, NO_BUFFERS, null, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [depthAutoGen_simpleVertex] VERTEX ===");
        System.out.println(cs.vertexSource());
        System.out.println("=== [depthAutoGen_simpleVertex] FRAGMENT ===");
        System.out.println(cs.fragmentSource());

        assertTrue("Vertex must contain CG_MATRIX_MVP", cs.vertexSource().contains("CG_MATRIX_MVP"));
        assertFalse("Vertex must NOT contain _AnimOffset (not from forward body)",
                cs.vertexSource().contains("_AnimOffset"));
        assertFalse("Fragment must NOT contain discard", cs.fragmentSource().contains("discard"));
    }

    @Test
    public void depthAutoGen_complexVertex_forwardBodyPreserved() {
        CgParsedShader parsed = CgShaderParser.parse(ANIM_SHADER, "test");
        CgParsedPass forward = parsed.passes().get(0);
        CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compileDepthAutoGen(
                parsed, forward, NO_BUFFERS, null, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [depthAutoGen_complexVertex] VERTEX ===");
        System.out.println(cs.vertexSource());
        System.out.println("=== [depthAutoGen_complexVertex] FRAGMENT ===");
        System.out.println(cs.fragmentSource());

        assertTrue("Vertex must contain _AnimOffset (forward body preserved)",
                cs.vertexSource().contains("_AnimOffset"));
        assertFalse("Fragment must NOT contain discard", cs.fragmentSource().contains("discard"));
    }

    @Test
    public void depthAutoGen_alphaClip_fragmentBodyIncluded() {
        CgParsedShader parsed = CgShaderParser.parse(ALPHA_CLIP_SHADER, "test");
        CgParsedPass forward = parsed.passes().get(0);
        CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compileDepthAutoGen(
                parsed, forward, NO_BUFFERS, null, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [depthAutoGen_alphaClip] VERTEX ===");
        System.out.println(cs.vertexSource());
        System.out.println("=== [depthAutoGen_alphaClip] FRAGMENT ===");
        System.out.println(cs.fragmentSource());

        assertTrue("Fragment must contain discard", cs.fragmentSource().contains("discard"));
        assertTrue("Vertex must contain _Cutoff (forward body preserved)",
                cs.vertexSource().contains("_Cutoff"));
    }

    @Test
    public void depthAutoGen_cgTimeVertex_isNotSimple() {
        CgParsedShader parsed = CgShaderParser.parse(CG_TIME_SHADER, "test");
        CgParsedPass forward = parsed.passes().get(0);
        CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compileDepthAutoGen(
                parsed, forward, NO_BUFFERS, null, CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        System.out.println("=== [depthAutoGen_cgTimeVertex] VERTEX ===");
        System.out.println(cs.vertexSource());

        assertTrue("Vertex must contain cg_Time (forward body preserved — not minimal transform)",
                cs.vertexSource().contains("cg_Time"));
        assertFalse("Vertex must NOT use the minimal-only path (no sin() in minimal transform)",
                cs.vertexSource().contains("CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n}"));
    }
}
