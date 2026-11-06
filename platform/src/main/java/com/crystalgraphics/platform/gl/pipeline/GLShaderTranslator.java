package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import java.nio.ByteBuffer;

/**
 * Vulkan SPIR-V as GLSL 4.60 a GL driver compiles: every resource of set {@code s}, binding {@code b} declared at
 * {@code points.point(s, b, class)}, push constants as a uniform block at {@code points.pushConstants()}, and the
 * bindless heap's sampler array (set 3) as a storage block of {@code ARB_bindless_texture} handles. Nothing is then
 * looked up by name after link.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, new SpvcGlslTranslator(), width, height);
 * }</pre>
 */
public interface GLShaderTranslator {

    /** @throws CgShaderModule.CompileException when the module cannot be expressed for GL */
    String glsl(ByteBuffer spirv, CgShaderModule.Stage stage, GLBindingPoints points, String label);

    /**
     * What its output depends on besides its input: kept program binaries are keyed by it, so a translator whose
     * output changes for the same SPIR-V must answer something new.
     */
    default String version() {
        return getClass().getName();
    }
}
