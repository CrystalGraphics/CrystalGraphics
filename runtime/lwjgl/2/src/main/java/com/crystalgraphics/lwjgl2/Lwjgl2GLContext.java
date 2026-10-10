package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.gl.CgGLContext;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * MC 1.7.10 / LWJGL 2.9 implementation of {@link CgGLContext}.
 *
 * <p>Reads capability flags from LWJGL 2's {@link ContextCapabilities}. {@link #probe()}
 * must be called once on the GL thread after context creation before any query method is
 * invoked.</p>
 */
public final class Lwjgl2GLContext implements CgGLContext {

    private volatile ContextCapabilities caps;
    /** What LWJGL 2's capabilities predate: GL 4.6, the subgroup and the parallel compile extensions. */
    private volatile boolean openGL46, subgroups, parallelCompile;
    
    private ContextCapabilities caps() {
        if(caps == null) probe();
        return caps;
    }
    
    @Override
    public void probe() {
        caps = GLContext.getCapabilities();
        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION), minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        openGL46 = major > 4 || major == 4 && minor >= 6;
        subgroups = false;
        parallelCompile = false;
        for (int i = 0, n = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS); i < n; i++) {
            String name = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
            subgroups |= "GL_KHR_shader_subgroup".equals(name);
            parallelCompile |= "GL_KHR_parallel_shader_compile".equals(name) || "GL_ARB_parallel_shader_compile".equals(name);
        }
    }

    // ── GL version tiers ──────────────────────────────────────────────────────

    @Override public boolean OpenGL30() { return caps().OpenGL30; }
    @Override public boolean OpenGL32() { return caps().OpenGL32; }
    @Override public boolean OpenGL33() { return caps().OpenGL33; }
    @Override public boolean OpenGL40() { return caps().OpenGL40; }
    @Override public boolean OpenGL42() { return caps().OpenGL42; }
    @Override public boolean OpenGL43() { return caps().OpenGL43; }
    @Override public boolean OpenGL44() { return caps().OpenGL44; }
    @Override public boolean OpenGL46() { caps(); return openGL46; }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Override public boolean GL_ARB_map_buffer_range() { return caps().GL_ARB_map_buffer_range; }
    @Override public boolean GL_ARB_sync()             { return caps().GL_ARB_sync; }
    @Override public boolean GL_ARB_buffer_storage()   { return caps().GL_ARB_buffer_storage; }

    // ── Shader buffer extensions ──────────────────────────────────────────────

    @Override public boolean GL_ARB_shader_storage_buffer_object() { return caps().GL_ARB_shader_storage_buffer_object; }

    @Override public boolean GL_ARB_instanced_arrays() { return caps().GL_ARB_instanced_arrays; }

    @Override public boolean GL_ARB_sampler_objects() { return caps().GL_ARB_sampler_objects; }

    @Override public boolean GL_ARB_explicit_attrib_location() { return caps().GL_ARB_explicit_attrib_location; }

    @Override public boolean GL_ARB_timer_query() { return caps().GL_ARB_timer_query; }

    @Override public boolean mappingIsFree() { return false; }

    // Polling needs only glGetProgrami; the driver's default thread count stands, as on LWJGL 3.
    @Override public boolean parallelShaderCompile() { caps(); return parallelCompile; }

    // ── Compute and GPU-driven draws ──────────────────────────────────────────

    @Override public boolean GL_ARB_compute_shader() { return caps().GL_ARB_compute_shader; }
    @Override public boolean GL_ARB_shader_image_load_store() { return caps().GL_ARB_shader_image_load_store; }
    @Override public boolean GL_ARB_draw_indirect() { return caps().GL_ARB_draw_indirect; }
    @Override public boolean GL_ARB_multi_draw_indirect() { return caps().GL_ARB_multi_draw_indirect; }
    @Override public boolean GL_ARB_indirect_parameters() { return caps().GL_ARB_indirect_parameters; }
    @Override public boolean GL_ARB_shader_draw_parameters() { return caps().GL_ARB_shader_draw_parameters; }
    @Override public boolean GL_ARB_base_instance() { return caps().GL_ARB_base_instance; }
    @Override public boolean GL_ARB_transform_feedback2() { return caps().GL_ARB_transform_feedback2; }
    @Override public boolean GL_KHR_shader_subgroup() { caps(); return subgroups; }
    @Override public boolean GL_NV_shader_atomic_float() { return caps().GL_NV_shader_atomic_float; }
    @Override public boolean GL_ARB_bindless_texture() { return caps().GL_ARB_bindless_texture; }
}
