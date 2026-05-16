package com.crystalgraphics.mc.platform.gl;

import com.crystalgraphics.platform.gl.CgCapabilityProbe;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/**
 * MC 1.7.10 / LWJGL 2.9 implementation of {@link CgCapabilityProbe}.
 *
 * <p>Reads capability flags from LWJGL 2's {@link ContextCapabilities}. {@link #probe()}
 * must be called once on the GL thread after context creation before any query method is
 * invoked.</p>
 */
public final class Lwjgl2CapabilityProbe implements CgCapabilityProbe {

    private volatile ContextCapabilities caps;

    @Override
    public void probe() {
        caps = GLContext.getCapabilities();
    }

    private ContextCapabilities caps() {
        ContextCapabilities c = caps;
        if (c == null) {
            throw new IllegalStateException(
                    "Lwjgl2CapabilityProbe.probe() has not been called yet. "
                    + "Ensure probe() is called on the GL thread after context creation.");
        }
        return c;
    }

    @Override
    public boolean isCoreFboSupported() {
        return caps().OpenGL30;
    }

    @Override
    public boolean isArbFboSupported() {
        return caps().GL_ARB_framebuffer_object;
    }

    @Override
    public boolean isExtFboSupported() {
        return caps().GL_EXT_framebuffer_object;
    }

    @Override
    public boolean isVaoSupported() {
        return caps().OpenGL30 || caps().GL_ARB_vertex_array_object;
    }

    @Override
    public boolean isSSBOSupported() {
        return caps().OpenGL43 || caps().GL_ARB_shader_storage_buffer_object;
    }

    @Override
    public boolean isTBOSupported() {
        return caps().OpenGL31 || caps().GL_ARB_texture_buffer_object;
    }

    @Override
    public boolean isOpenGL40() {
        return caps().OpenGL40;
    }

    @Override
    public boolean isOpenGL43() {
        return caps().OpenGL43;
    }

    @Override
    public boolean isARBSync() {
        return caps().OpenGL32 || caps().GL_ARB_sync;
    }
}
