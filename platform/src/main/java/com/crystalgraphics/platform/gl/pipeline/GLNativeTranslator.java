package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.rhi.shader.CgGlslSources;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A GL device's translator from its start: a module {@link GLNativeGlslCompiler} made passes through with each of its
 * bindings' points defined (what {@code SpvcGlslTranslator} writes as decorations), and a SPIR-V module goes to the
 * toolchain's translator once {@link #spirv} hands it over.
 *
 * <pre>{@code
 * GLNativeTranslator translator = new GLNativeTranslator();
 * CgGLDevice device = new CgGLDevice(driver, translator, w, h, false, GLHostState.byQuery(driver));
 * translator.spirv(toolchain.translator());   // once the toolchain lands, any thread
 * }</pre>
 *
 * <p>A SPIR-V module before {@link #spirv} is refused: no compiler made one, since the native compiler was the one
 * installed.</p>
 */
public final class GLNativeTranslator implements GLShaderTranslator {

    private static final byte[] MARK = GLNativeGlslCompiler.MARK.getBytes(StandardCharsets.UTF_8);

    /** Null until the toolchain lands. */
    private volatile GLShaderTranslator spirv;

    /** SPIR-V's translator from now on: the shader toolchain's. */
    public void spirv(GLShaderTranslator translator) {
        spirv = translator;
    }

    @Override
    public String glsl(ByteBuffer module, CgShaderModule.Stage stage, GLBindingPoints points, String label) {
        if (!isNative(module)) {
            GLShaderTranslator t = spirv;
            if (t == null) throw new CgShaderModule.CompileException(label + ": a SPIR-V module before the shader toolchain");
            return t.glsl(module, stage, points, label);
        }
        byte[] bytes = new byte[module.remaining()];
        module.duplicate().get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        int slotsAt = text.indexOf('\n') + 1, sourceAt = text.indexOf('\n', slotsAt) + 1;
        StringBuilder defines = new StringBuilder(256);
        for (String slot : text.substring(slotsAt + 2, sourceAt - 1).trim().split(" ")) {
            if (slot.isEmpty()) continue;
            String[] f = slot.split(",");
            if (f.length == 1) {   // declared, never read: bound at any point, since nothing binds it
                defines.append("#define ").append(f[0]).append(" 0\n");
                continue;
            }
            int point = points.find(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
            if (point < 0) throw new CgShaderModule.CompileException(label + ": " + f[0] + " is in no slot of the layout");
            defines.append("#define ").append(f[0]).append(' ').append(point).append('\n');
        }
        defines.append("#define CG_GL_PUSH ").append(points.pushConstants()).append('\n');
        return CgGlslSources.withDefines(text.substring(sourceAt), label, defines.toString());
    }

    /** Whether {@code module} is GLSL from {@link GLNativeGlslCompiler}, not SPIR-V. */
    public static boolean isNative(ByteBuffer module) {
        if (module.remaining() < MARK.length) return false;
        for (int i = 0; i < MARK.length; i++) if (module.get(module.position() + i) != MARK[i]) return false;
        return true;
    }

    /** Native modules are their own text, so they key binaries by it; the toolchain's version keys SPIR-V's. */
    @Override
    public String version() {
        GLShaderTranslator t = spirv;
        return "native 1/" + (t == null ? "-" : t.version());
    }
}
