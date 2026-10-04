package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.gl.CgGLContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * LWJGL 3 implementation of {@link CgGLContext}.
 *
 * <p>Reads capability flags from LWJGL 3's {@link GLCapabilities}. {@link #probe()} must be
 * called once on the GL thread after context creation before any query method is invoked.
 * The structure mirrors {@code Lwjgl2GLContext} exactly — the only change is the capability
 * acquisition path: LWJGL 3 uses {@code GL.getCapabilities()} instead of LWJGL 2's
 * {@code GLContext.getCapabilities()}. Field names on {@link GLCapabilities} are identical
 * to those on LWJGL 2's {@code ContextCapabilities}.</p>
 */
public final class Lwjgl3GLContext implements CgGLContext {

    private volatile GLCapabilities caps;
    /** What LWJGL 3.2.2's capabilities predate, from the extension list. */
    private volatile boolean subgroups;

    private GLCapabilities caps() {
        if (caps == null) probe();
        return caps;
    }

    @Override
    public void probe() {
        // GL.getCapabilities() returns the GLCapabilities bound to the current LWJGL 3 context.
        // Must be called on the GL thread after the context has been made current.
        caps = GL.getCapabilities();
        subgroups = lists("GL_KHR_shader_subgroup");
    }

    private static boolean lists(String extension) {
        for (int i = 0, n = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS); i < n; i++) {
            if (extension.equals(GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i))) return true;
        }
        return false;
    }

    // ── GL version tiers ──────────────────────────────────────────────────────

    @Override public boolean OpenGL30() { return caps().OpenGL30; }
    @Override public boolean OpenGL32() { return caps().OpenGL32; }
    @Override public boolean OpenGL33() { return caps().OpenGL33; }
    @Override public boolean OpenGL40() { return caps().OpenGL40; }
    @Override public boolean OpenGL42() { return caps().OpenGL42; }
    @Override public boolean OpenGL43() { return caps().OpenGL43; }
    @Override public boolean OpenGL44() { return caps().OpenGL44; }
    @Override public boolean OpenGL46() { return caps().OpenGL46; }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Override public boolean GL_ARB_map_buffer_range() { return caps().GL_ARB_map_buffer_range; }
    @Override public boolean GL_ARB_sync()             { return caps().GL_ARB_sync; }
    @Override public boolean GL_ARB_buffer_storage()   { return caps().GL_ARB_buffer_storage; }

    // ── Shader buffer extensions ──────────────────────────────────────────────

    // GL_ARB_program_interface_query is also required: glGetProgramResourceIndex and
    // glShaderStorageBlockBinding (used on the ARB path) are promoted from that extension.
    @Override public boolean GL_ARB_shader_storage_buffer_object() { return caps().GL_ARB_shader_storage_buffer_object && caps().GL_ARB_program_interface_query; }

    @Override public boolean GL_ARB_instanced_arrays() { return caps().GL_ARB_instanced_arrays; }

    @Override public boolean GL_ARB_sampler_objects() { return caps().GL_ARB_sampler_objects; }

    @Override public boolean GL_ARB_explicit_attrib_location() { return caps().GL_ARB_explicit_attrib_location; }

    @Override public boolean GL_ARB_timer_query() { return caps().GL_ARB_timer_query; }

    @Override public boolean mappingIsFree() { return false; }

    @Override public boolean parallelShaderCompile() {
        return caps().GL_KHR_parallel_shader_compile || caps().GL_ARB_parallel_shader_compile;
    }

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
