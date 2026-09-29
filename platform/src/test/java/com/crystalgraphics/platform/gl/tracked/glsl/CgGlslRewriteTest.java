package com.crystalgraphics.platform.gl.tracked.glsl;

import com.crystalgraphics.platform.device.CgBindingLayout;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The link-time rewrite: what the program reads keeps its meaning, and Vulkan's compiler takes the result. */
public class CgGlslRewriteTest {

    /** The shape the material compiler emits: blocks, a sampler, a varying block, a flat varying, a keyword branch. */
    private static final String MATERIAL_VERTEX = String.join("\n",
            "#version 430 core",
            "#define CG_VERTEX_STAGE 1",
            "#define CG_USE_SSBO 1",
            "layout(std140) uniform CgFrameBlock {",
            "    mat4 cg_ViewMatrix;",
            "    mat4 cg_ProjMatrix;",
            "};",
            "struct CgObjectData { mat4 modelMatrix; vec4 custom0; };",
            "#ifdef CG_USE_SSBO",
            "layout(std430) readonly buffer CgObjectDataBuffer { CgObjectData cg_Objects[]; };",
            "#define CG_OBJECT_DATA cg_Objects[CG_INSTANCE_ID]",
            "#else",
            "uniform samplerBuffer CgObjectDataBuffer;",
            "#endif",
            "#define CG_INSTANCE_ID gl_InstanceID",
            "in vec3 cg_Position;",
            "in vec2 cg_TexCoord0;",
            "uniform sampler2D _MainTex;   // declared in both stages",
            "flat out int cg_InstanceId;",
            "out _CgV2fBlock {",
            "    vec2 uv;",
            "    vec4 color;",
            "} _cg_v2f;",
            "void main() {",
            "    cg_InstanceId = gl_InstanceID;",
            "    _cg_v2f.uv = cg_TexCoord0;",
            "    _cg_v2f.color = CG_OBJECT_DATA.custom0;",
            "    if (cg_TexCoord0.x < 0.0) { gl_Position = vec4(0.0); return; }",
            "    gl_Position = cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_DATA.modelMatrix * vec4(cg_Position, 1.0);",
            "}");

    private static final String MATERIAL_FRAGMENT = String.join("\n",
            "#version 430 core",
            "#define CG_FRAGMENT_STAGE 1",
            "layout(std140) uniform CgFrameBlock {",
            "    mat4 cg_ViewMatrix;",
            "    mat4 cg_ProjMatrix;",
            "};",
            "uniform sampler2D _MainTex;",
            "flat in int cg_InstanceId;",
            "in _CgV2fBlock {",
            "    vec2 uv;",
            "    vec4 color;",
            "} _cg_v2f;",
            "out vec4 _cg_fragColor;",
            "void main() {",
            "    _cg_fragColor = texture(_MainTex, _cg_v2f.uv) * _cg_v2f.color;",
            "}");

    @Test
    public void aMaterialProgramCompilesForVulkanWithEveryNameWhereGlPutIt() {
        CgGlslRewrite.Program p = CgGlslRewrite.rewrite(MATERIAL_VERTEX, MATERIAL_FRAGMENT,
                Map.of("cg_Position", 0, "cg_TexCoord0", 1));
        Shaderc.assertCompiles(p);

        assertEquals(List.of(new CgGlslRewrite.Attribute("cg_Position", 0, 0x8B51),
                new CgGlslRewrite.Attribute("cg_TexCoord0", 1, 0x8B50)), p.attributes());
        assertEquals(1, p.uniformBlocks().size());
        assertEquals(1, p.storageBlocks().size());
        assertEquals("one binding for a sampler both stages declare", 1, p.samplers().size());
        assertEquals(List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                new CgBindingLayout.Slot(1, CgBindingLayout.Type.STORAGE_BUFFER),
                new CgBindingLayout.Slot(2, CgBindingLayout.Type.SAMPLED_TEXTURE)), p.slots());
        assertTrue(p.vertexGlDepth().contains("gl_InstanceIndex"));
        assertFalse(p.vertexGlDepth().contains("gl_InstanceID"));
    }

    @Test
    public void onlyTheGlDepthVariantRemapsClipZ() {
        CgGlslRewrite.Program p = CgGlslRewrite.rewrite(MATERIAL_VERTEX, MATERIAL_FRAGMENT, Map.of());
        String remap = "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;";
        assertTrue("an early return in main still gets the remap: main is wrapped", p.vertexGlDepth().contains(remap));
        assertFalse(p.vertexZeroToOne().contains(remap));
    }

    @Test
    public void looseUniformsMoveIntoAStd140BlockPerStage() {
        String vertex = String.join("\n",
                "#version 330 core",
                "in vec2 a_pos;",
                "uniform vec3 u_a;",
                "uniform float u_b = 0.5;",
                "uniform mat3 u_m;",
                "uniform vec4 u_list[3];",
                "void main() { gl_Position = vec4(u_m * u_a + u_list[2].xyz * u_b, 1.0) + vec4(a_pos, 0.0, 0.0); }");
        String fragment = String.join("\n",
                "#version 330 core",
                "uniform float u_b;",
                "uniform int u_mode;",
                "out vec4 color;",
                "void main() { color = vec4(u_b, float(u_mode), 0.0, 1.0); }");
        CgGlslRewrite.Program p = CgGlslRewrite.rewrite(vertex, fragment, Map.of("a_pos", 0));
        Shaderc.assertCompiles(p);

        CgGlslRewrite.Uniform a = uniform(p, "u_a"), b = uniform(p, "u_b"), m = uniform(p, "u_m"), list = uniform(p, "u_list");
        assertEquals(0, a.vertexOffset());
        assertEquals("a float packs into a vec3's fourth slot", 12, b.vertexOffset());
        assertEquals(16, m.vertexOffset());
        assertEquals("a mat3 is three 16-byte columns", 64, list.vertexOffset());
        assertEquals(16, list.stride());
        assertEquals(3, list.count());
        assertEquals(112, p.vertexUniformSize());
        assertEquals("the same uniform in both stages is one, in each stage's block", 0, b.fragmentOffset());
        assertEquals(-1, uniform(p, "u_mode").vertexOffset());
        assertArrayEquals(new float[] {0.5f}, b.initial(), 0f);
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
        CgGlslRewrite.Program p = CgGlslRewrite.rewrite(vertex, fragment, Map.of());
        Shaderc.assertCompiles(p);
        assertTrue(p.samplers().isEmpty());
        assertTrue(p.slots().isEmpty());
    }

    @Test
    public void glsl130RawShadersCarryOver() {
        String vertex = String.join("\n",
                "#version 130",
                "in vec2 a_pos;",
                "in vec2 a_uv;",
                "out vec2 v_uv;",
                "uniform mat4 u_projection;",
                "void main() { gl_Position = u_projection * vec4(a_pos, 0.0, 1.0); v_uv = a_uv; }");
        String fragment = String.join("\n",
                "#version 130",
                "in vec2 v_uv;",
                "out vec4 fragColor;",
                "uniform sampler2D u_atlas;",
                "uniform int u_atlasType;",
                "void main() { fragColor = u_atlasType == 0 ? texture2D(u_atlas, v_uv) : vec4(1.0); }");
        CgGlslRewrite.Program p = CgGlslRewrite.rewrite(vertex, fragment, Map.of("a_pos", 0, "a_uv", 1));
        Shaderc.assertCompiles(p);
    }

    private static CgGlslRewrite.Uniform uniform(CgGlslRewrite.Program p, String name) {
        for (CgGlslRewrite.Uniform u : p.uniforms()) if (u.name().equals(name)) return u;
        throw new AssertionError("no uniform " + name + " in " + p.uniforms());
    }
}
