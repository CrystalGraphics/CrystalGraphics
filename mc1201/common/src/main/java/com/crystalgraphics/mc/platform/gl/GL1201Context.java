package com.crystalgraphics.mc.platform.gl;

import com.crystalgraphics.platform.gl.CgGLContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * MC 1.20.x / LWJGL 3 implementation of {@link CgGLContext}.
 *
 * <p>Reads capability flags from LWJGL 3's {@link GLCapabilities}. {@link #probe()} must be
 * called once on the GL thread after context creation before any query method is invoked.
 * The structure mirrors {@code Lwjgl2GLContext} exactly — the only change is the capability
 * acquisition path: LWJGL 3 uses {@code GL.getCapabilities()} instead of LWJGL 2's
 * {@code GLContext.getCapabilities()}. Field names on {@link GLCapabilities} are identical
 * to those on LWJGL 2's {@code ContextCapabilities}.</p>
 */
public final class GL1201Context implements CgGLContext {

    private volatile GLCapabilities caps;

    private GLCapabilities caps() {
        if (caps == null) probe();
        return caps;
    }

    @Override
    public void probe() {
        // GL.getCapabilities() returns the GLCapabilities bound to the current LWJGL 3 context.
        // Must be called on the GL thread after the context has been made current.
        caps = GL.getCapabilities();
    }

    // ── GL version tiers ──────────────────────────────────────────────────────

    @Override public boolean OpenGL20() { return caps().OpenGL20; }
    @Override public boolean OpenGL30() { return caps().OpenGL30; }
    @Override public boolean OpenGL31() { return caps().OpenGL31; }
    @Override public boolean OpenGL32() { return caps().OpenGL32; }
    @Override public boolean OpenGL33() { return caps().OpenGL33; }
    @Override public boolean OpenGL40() { return caps().OpenGL40; }
    @Override public boolean OpenGL43() { return caps().OpenGL43; }

    // ── Framebuffer extensions ────────────────────────────────────────────────

    @Override public boolean GL_ARB_framebuffer_object()  { return caps().GL_ARB_framebuffer_object; }
    @Override public boolean GL_EXT_framebuffer_object()  { return caps().GL_EXT_framebuffer_object; }

    // ── Shader extensions ─────────────────────────────────────────────────────

    @Override public boolean GL_ARB_shader_objects()      { return caps().GL_ARB_shader_objects; }

    // ── Depth / stencil extensions ────────────────────────────────────────────

    @Override public boolean GL_EXT_packed_depth_stencil() { return caps().GL_EXT_packed_depth_stencil; }
    @Override public boolean GL_NV_packed_depth_stencil()  { return caps().GL_NV_packed_depth_stencil; }
    @Override public boolean GL_ARB_depth_texture()       { return caps().GL_ARB_depth_texture; }

    // ── Buffer / VAO extensions ───────────────────────────────────────────────

    @Override public boolean GL_ARB_vertex_array_object()  { return caps().GL_ARB_vertex_array_object; }
    @Override public boolean GL_ARB_map_buffer_range()     { return caps().GL_ARB_map_buffer_range; }
    @Override public boolean GL_ARB_sync()                 { return caps().GL_ARB_sync; }

    // ── Instancing extensions ─────────────────────────────────────────────────

    @Override public boolean GL_ARB_draw_instanced()      { return caps().GL_ARB_draw_instanced; }
    @Override public boolean GL_ARB_instanced_arrays()    { return caps().GL_ARB_instanced_arrays; }

    // ── Shader buffer extensions ──────────────────────────────────────────────

    // GL_ARB_program_interface_query is also required: glGetProgramResourceIndex and
    // glShaderStorageBlockBinding (used on the ARB path) are promoted from that extension.
    @Override public boolean GL_ARB_shader_storage_buffer_object() { return caps().GL_ARB_shader_storage_buffer_object && caps().GL_ARB_program_interface_query; }
    @Override public boolean GL_ARB_sampler_objects()              { return caps().GL_ARB_sampler_objects; }
}
