package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A program compiled as written: its sets, heap, push range and outputs read from its SPIR-V. */
public class ExplicitCompileTest {

    private static final String VERTEX = "#version 450\n"
            + "layout(set = 0, binding = 0) uniform View { mat4 m; } view;\n"
            + "layout(set = 2, binding = 1) readonly buffer Records { vec4 r[]; } records;\n"
            + "void main() { gl_Position = view.m * records.r[gl_VertexIndex]; }\n";
    private static final String FRAGMENT = "#version 450\n#extension GL_EXT_nonuniform_qualifier : require\n"
            + "layout(set = 0, binding = 0) uniform View { mat4 m; } view;\n"
            + "layout(set = 2, binding = 0) uniform sampler2D albedo;\n"
            + "layout(set = 3, binding = 0) uniform sampler2D textures[];\n"
            + "layout(push_constant) uniform P { uint tex; float k; } p;\n"
            + "layout(location = 0) out vec4 colour;\nlayout(location = 1) out vec4 normal;\n"
            + "void main() { colour = texture(albedo, vec2(0.5)) * p.k + texture(textures[p.tex], vec2(0.5));"
            + " normal = view.m[0]; }\n";

    @Test
    public void setsHeapPushAndOutputsAsWritten() {
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(null, true)) {
            CgGlslCompiler.Explicit p = compiler.compileExplicit(VERTEX, FRAGMENT, Map.of(), "explicit");
            assertEquals("sets 0 to 3, the heap's last", 4, p.sets().size());
            assertEquals(List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER)), p.sets().get(0));
            assertEquals("set 1 skipped", List.of(), p.sets().get(1));
            assertEquals(List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE),
                    new CgBindingLayout.Slot(1, CgBindingLayout.Type.STORAGE_BUFFER)), p.sets().get(2));
            assertTrue(p.heap());
            assertEquals(8, p.pushConstantBytes());
            assertEquals(2, p.colorOutputs());
        }
    }

    @Test
    public void aResourceLeftUnboundIsRefused() {
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(null, true)) {
            compiler.compileExplicit("#version 450\nuniform U { vec4 c; } u;\nvoid main() { gl_Position = u.c; }\n",
                    "#version 450\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n", Map.of(), "unbound");
            fail("an unbound block compiled");
        } catch (CgShaderModule.CompileException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
