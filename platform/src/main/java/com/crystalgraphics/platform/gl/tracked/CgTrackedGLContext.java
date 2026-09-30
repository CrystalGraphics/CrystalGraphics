package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGLContext;

/**
 * The tracked backend's capabilities: a GL 4.4 core profile, so every feature gate in {@code CgCapabilities} takes
 * its best path — SSBOs, the persistent frame ring, {@code glCopyImageSubData}.
 */
public final class CgTrackedGLContext implements CgGLContext {

    @Override public void probe() {}

    @Override public boolean OpenGL30() { return true; }

    @Override public boolean OpenGL32() { return true; }

    @Override public boolean OpenGL33() { return true; }

    @Override public boolean OpenGL40() { return true; }

    @Override public boolean OpenGL43() { return true; }

    @Override public boolean OpenGL44() { return true; }

    @Override public boolean GL_ARB_map_buffer_range() { return true; }

    @Override public boolean GL_ARB_sync() { return true; }

    @Override public boolean GL_ARB_buffer_storage() { return true; }

    @Override public boolean GL_ARB_shader_storage_buffer_object() { return true; }

    @Override public boolean GL_ARB_instanced_arrays() { return true; }

    @Override public boolean GL_ARB_sampler_objects() { return true; }

    @Override public boolean GL_ARB_explicit_attrib_location() { return true; }

    @Override public boolean GL_ARB_timer_query() { return true; }

    @Override public boolean mappingIsFree() { return true; }

    /** A compile here is shaderc on the calling thread: finished when {@code glCompileShader} returns. */
    @Override public boolean parallelShaderCompile() { return false; }
}
