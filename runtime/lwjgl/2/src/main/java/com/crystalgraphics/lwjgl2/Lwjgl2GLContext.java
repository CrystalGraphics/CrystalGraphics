package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.gl.CgGLContext;
import org.lwjgl.opengl.ContextCapabilities;
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
    
    private ContextCapabilities caps() {
        if(caps == null) probe();
        return caps;
    }
    
    @Override
    public void probe() {
        caps = GLContext.getCapabilities();
    }

    // ── GL version tiers ──────────────────────────────────────────────────────

    @Override public boolean OpenGL30() { return caps().OpenGL30; }
    @Override public boolean OpenGL32() { return caps().OpenGL32; }
    @Override public boolean OpenGL33() { return caps().OpenGL33; }
    @Override public boolean OpenGL40() { return caps().OpenGL40; }
    @Override public boolean OpenGL43() { return caps().OpenGL43; }
    @Override public boolean OpenGL44() { return caps().OpenGL44; }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Override public boolean GL_ARB_map_buffer_range() { return caps().GL_ARB_map_buffer_range; }
    @Override public boolean GL_ARB_sync()             { return caps().GL_ARB_sync; }
    @Override public boolean GL_ARB_buffer_storage()   { return caps().GL_ARB_buffer_storage; }

    // ── Shader buffer extensions ──────────────────────────────────────────────

    @Override public boolean GL_ARB_shader_storage_buffer_object() { return caps().GL_ARB_shader_storage_buffer_object; }
}
