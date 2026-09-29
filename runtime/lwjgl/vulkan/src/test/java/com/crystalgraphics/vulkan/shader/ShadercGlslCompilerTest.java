package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** GL GLSL through shaderc and SPIRV-Cross: the stages agree by name, and every GL name has its place. */
public class ShadercGlslCompilerTest {

    private static final ShadercGlslCompiler COMPILER = new ShadercGlslCompiler();

    @AfterClass
    public static void close() { COMPILER.close(); }

    /** The shape the material compiler emits: blocks, a sampler in both stages, a varying block, a flat varying. */
    private static final String MATERIAL_VERTEX = String.join("\n",
            "#version 430 core",
            "#define CG_USE_SSBO 1",
            "layout(std140) uniform CgFrameBlock { mat4 cg_ViewMatrix; mat4 cg_ProjMatrix; };",
            "struct CgObjectData { mat4 modelMatrix; vec4 custom0; };",
            "#ifdef CG_USE_SSBO",
            "layout(std430) readonly buffer CgObjectDataBuffer { CgObjectData cg_Objects[]; };",
            "#else",
            "uniform samplerBuffer CgObjectDataBuffer;",
            "#endif",
            "in vec3 cg_Position;",
            "in vec2 cg_TexCoord0;",
            "uniform sampler2D _MainTex;",
            "flat out int cg_InstanceId;",
            "out _CgV2fBlock { vec2 uv; vec4 color; } _cg_v2f;",
            "void main() {",
            "    cg_InstanceId = gl_InstanceID;",
            "    _cg_v2f.uv = cg_TexCoord0;",
            "    _cg_v2f.color = cg_Objects[gl_InstanceID].custom0;",
            "    if (cg_TexCoord0.x < 0.0) { gl_Position = vec4(0.0); return; }",
            "    gl_Position = cg_ProjMatrix * cg_ViewMatrix * cg_Objects[gl_InstanceID].modelMatrix * vec4(cg_Position, 1.0);",
            "}");

    private static final String MATERIAL_FRAGMENT = String.join("\n",
            "#version 430 core",
            "layout(std140) uniform CgFrameBlock { mat4 cg_ViewMatrix; mat4 cg_ProjMatrix; };",
            "uniform sampler2D _MainTex;",
            "flat in int cg_InstanceId;",
            "in _CgV2fBlock { vec2 uv; vec4 color; } _cg_v2f;",
            "out vec4 _cg_fragColor;",
            "void main() { _cg_fragColor = texture(_MainTex, _cg_v2f.uv) * _cg_v2f.color * float(cg_InstanceId); }");

    @Test
    public void aMaterialProgramCompilesWithEveryNameWhereGlPutIt() {
        CgGlslCompiler.Program p = COMPILER.compile(MATERIAL_VERTEX, MATERIAL_FRAGMENT,
                Map.of("cg_Position", 0, "cg_TexCoord0", 1), "material");

        assertEquals(List.of(new CgGlslCompiler.Attribute("cg_Position", 0, 0x8B51),
                new CgGlslCompiler.Attribute("cg_TexCoord0", 1, 0x8B50)), p.attributes());
        assertEquals(List.of(new CgGlslCompiler.Block("CgFrameBlock", 0)), p.uniformBlocks());
        assertEquals(List.of(new CgGlslCompiler.Block("CgObjectDataBuffer", 1)), p.storageBlocks());
        assertEquals("one binding for a sampler both stages declare",
                List.of(new CgGlslCompiler.Sampler("_MainTex", 2, 0x8B5E, false)), p.samplers());
        assertEquals(List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                new CgBindingLayout.Slot(1, CgBindingLayout.Type.STORAGE_BUFFER),
                new CgBindingLayout.Slot(2, CgBindingLayout.Type.SAMPLED_TEXTURE)), p.slots());
        assertEquals(-1, p.vertexUniformBinding());
        assertNotEquals("the GL-depth stage has the wrapper; the other does not",
                p.vertexGlDepth().remaining(), p.vertexZeroToOne().remaining());
    }

    @Test
    public void varyingsMeetByNameWhateverOrderEachStageDeclaresThem() {
        String vertex = "#version 330 core\nin vec2 p;\nout vec4 a;\nout vec2 b;\n"
                + "void main() { a = vec4(1.0); b = p; gl_Position = vec4(p, 0.0, 1.0); }";
        String fragment = "#version 330 core\nin vec2 b;\nin vec4 a;\nout vec4 color;\n"
                + "void main() { color = a + vec4(b, 0.0, 0.0); }";
        SpirvModule v = new SpirvModule(COMPILER.compile(vertex, fragment, Map.of("p", 0), "order").vertexGlDepth(), "v");
        SpirvModule f = new SpirvModule(COMPILER.compile(vertex, fragment, Map.of("p", 0), "order").fragment(), "f");
        for (SpirvModule.Resource in : f.inputs) {
            SpirvModule.Resource out = v.outputs.stream().filter(o -> o.name().equals(in.name())).findFirst().orElseThrow();
            assertEquals(in.name(), out.value(), in.value());
        }
    }

    @Test
    public void looseUniformsLiveInAStd140BlockPerStage() {
        String vertex = String.join("\n",
                "#version 330 core",
                "in vec2 a_pos;",
                "uniform vec3 u_a;",
                "uniform float u_b;",
                "uniform mat3 u_m;",
                "uniform vec4 u_list[3];",
                "void main() { gl_Position = vec4(u_m * u_a + u_list[2].xyz * u_b, 1.0) + vec4(a_pos, 0.0, 0.0); }");
        String fragment = String.join("\n",
                "#version 330 core",
                "uniform float u_b;",
                "uniform int u_mode;",
                "out vec4 color;",
                "void main() { color = vec4(u_b, float(u_mode), 0.0, 1.0); }");
        CgGlslCompiler.Program p = COMPILER.compile(vertex, fragment, Map.of("a_pos", 0), "loose");

        assertEquals(0, p.vertexUniformBinding());
        assertEquals(1, p.fragmentUniformBinding());
        assertEquals(0, uniform(p, "u_a").vertexOffset());
        assertEquals("a float packs into a vec3's fourth slot", 12, uniform(p, "u_b").vertexOffset());
        assertEquals(16, uniform(p, "u_m").vertexOffset());
        assertEquals(16, uniform(p, "u_m").matrixStride());
        assertEquals("a mat3 is three 16-byte columns", 64, uniform(p, "u_list").vertexOffset());
        assertEquals(16, uniform(p, "u_list").stride());
        assertEquals(3, uniform(p, "u_list").count());
        assertEquals(112, p.vertexUniformSize());
        assertEquals("the same uniform in both stages, in each stage's block", 0, uniform(p, "u_b").fragmentOffset());
        assertEquals(-1, uniform(p, "u_mode").vertexOffset());
        assertTrue(uniform(p, "u_mode").integer());
    }

    @Test
    public void anInactiveBranchDeclaresNothing() {
        String vertex = "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }";
        String fragment = String.join("\n",
                "#version 330 core",
                "#if defined(WITH_MASK) && WITH_MASK > 0",
                "uniform sampler2D _Mask;",
                "#endif",
                "out vec4 color;",
                "void main() { color = vec4(1.0); }");
        CgGlslCompiler.Program p = COMPILER.compile(vertex, fragment, Map.of(), "branch");
        assertTrue(p.samplers().isEmpty());
        assertTrue(p.slots().isEmpty());
    }

    private static CgGlslCompiler.Uniform uniform(CgGlslCompiler.Program p, String name) {
        for (CgGlslCompiler.Uniform u : p.uniforms()) if (u.name().equals(name)) return u;
        throw new AssertionError("no uniform " + name + " in " + p.uniforms());
    }
}
