package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.CgPlatform;

import java.nio.IntBuffer;

/**
 * Platform abstraction for all raw OpenGL calls made by the CrystalGraphics core engine.
 * Each platform provides a concrete implementation (e.g. {@code Lwjgl2GlDispatch} for
 * MC 1.7.10 with LWJGL2) that delegates to the appropriate native GL bindings.
 *
 * <h3>Singleton access</h3>
 * <pre>{@code
 * CgGlDispatch.get().glUseProgram(programId);
 * }</pre>
 *
 * <h3>FBO waterfall</h3>
 * The {@link #bindFramebufferCompat(int)} method is the platform-neutral substitute for
 * {@code OpenGlHelper.func_153171_g} — mc1710 routes through the Minecraft compat helper,
 * standalone impls call {@code glBindFramebuffer} directly. Used by
 * {@code CallFamily.OPENGLHELPER_WRAPPER} routing in core/.
 */
public abstract class CgGlDispatch {

    // -------------------------------------------------------------------------
    // Singleton management
    // -------------------------------------------------------------------------

    private static CgGlDispatch instance;

    /** Returns the registered dispatch instance. Throws if not yet registered. */
    public static CgGlDispatch get() {
        if (instance == null) {
            throw new IllegalStateException(
                "CgGlDispatch: no dispatch registered. Call CgPlatform.register() during init.");
        }
        return instance;
    }

    /** Registers the active dispatch. Called internally by {@link CgPlatform#register}. */
    public static void setInstance(CgGlDispatch dispatch) {
        instance = dispatch;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Called once after GL context creation. Implementation may store capabilities or perform setup. */
    public abstract void initContext();

    /** @return {@code true} if this dispatch is available in the current environment (classpath check). */
    public abstract boolean isAvailable();

    /** @return selection priority; higher wins when multiple dispatches are available. */
    public abstract int getPriority();

    // -------------------------------------------------------------------------
    // Framebuffers — Core / ARB / EXT dispatch
    // -------------------------------------------------------------------------

    public abstract void bindFramebuffer(int target, int fbo);
    public abstract void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                          int dstX0, int dstY0, int dstX1, int dstY1,
                                          int mask, int filter);
    public abstract int genFramebuffers();
    public abstract void deleteFramebuffers(int fbo);
    public abstract void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level);
    public abstract int checkFramebufferStatus(int target);
    public abstract void drawBuffers(IntBuffer bufs);

    /**
     * Bind the given FBO using the platform's compatibility path.
     *
     * <p>MC 1.7.10 implementation calls {@code OpenGlHelper.func_153171_g(GL_FRAMEBUFFER, fbo)}
     * so that Minecraft's own FBO tracking remains consistent. Standalone / harness implementations
     * call {@code glBindFramebuffer(GL_FRAMEBUFFER, fbo)} directly.</p>
     *
     * <p>This is the replacement for {@code OpenGlHelper.func_153171_g} in core/.
     * Used by {@code CallFamily.OPENGLHELPER_WRAPPER} routing.</p>
     *
     * @param fbo the framebuffer object name to bind, or 0 to unbind
     */
    public abstract void bindFramebufferCompat(int fbo);

    // -------------------------------------------------------------------------
    // Shaders
    // -------------------------------------------------------------------------

    public abstract int glCreateShader(int type);
    public abstract void glShaderSource(int shader, CharSequence source);
    public abstract void glCompileShader(int shader);
    public abstract int glGetShaderi(int shader, int pname);
    public abstract String glGetShaderInfoLog(int shader, int maxLength);
    public abstract void glDeleteShader(int shader);
    public abstract int glCreateProgram();
    public abstract void glAttachShader(int program, int shader);
    public abstract void glLinkProgram(int program);
    public abstract int glGetProgrami(int program, int pname);
    public abstract String glGetProgramInfoLog(int program, int maxLength);
    public abstract void glUseProgram(int program);
    public abstract void glDeleteProgram(int program);
    public abstract int glGetUniformLocation(int program, CharSequence name);
    public abstract void glUniform1i(int location, int v0);
    public abstract void glUniform1f(int location, float v0);
    public abstract void glUniform2f(int location, float v0, float v1);
    public abstract void glUniform3f(int location, float v0, float v1, float v2);
    public abstract void glUniform4f(int location, float v0, float v1, float v2, float v3);
    public abstract void glUniformMatrix4fv(int location, boolean transpose, java.nio.FloatBuffer value);
    public abstract void glBindAttribLocation(int program, int index, CharSequence name);
    public abstract int glGetProgramResourceIndex(int program, int programInterface, CharSequence name);
    public abstract void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding);
    public abstract int glGetUniformBlockIndex(int program, CharSequence uniformBlockName);
    public abstract void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding);

    // -------------------------------------------------------------------------
    // Buffers
    // -------------------------------------------------------------------------

    public abstract int glGenBuffers();
    public abstract void glBindBuffer(int target, int buffer);
    public abstract void glBufferData(int target, java.nio.ByteBuffer data, int usage);
    public abstract void glBufferData(int target, long size, int usage);
    public abstract void glBufferSubData(int target, long offset, java.nio.ByteBuffer data);
    public abstract void glDeleteBuffers(int buffer);
    public abstract void glBindBufferBase(int target, int index, int buffer);
    public abstract void glBindBufferRange(int target, int index, int buffer, long offset, long size);
    public abstract void glTexBuffer(int target, int internalFormat, int buffer);

    // -------------------------------------------------------------------------
    // Vertex Array Objects
    // -------------------------------------------------------------------------

    public abstract int glGenVertexArrays();
    public abstract void glBindVertexArray(int array);
    public abstract void glDeleteVertexArrays(int array);
    public abstract void glEnableVertexAttribArray(int index);
    public abstract void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
    public abstract void glVertexAttribDivisor(int index, int divisor);

    // -------------------------------------------------------------------------
    // Textures
    // -------------------------------------------------------------------------

    public abstract int glGenTextures();
    public abstract void glBindTexture(int target, int texture);
    public abstract void glDeleteTextures(int texture);
    public abstract void glTexImage2D(int target, int level, int internalFormat,
                                       int width, int height, int border,
                                       int format, int type, java.nio.ByteBuffer pixels);
    public abstract void glTexImage3D(int target, int level, int internalFormat,
                                       int width, int height, int depth, int border,
                                       int format, int type, java.nio.ByteBuffer pixels);
    public abstract void glTexSubImage2D(int target, int level,
                                          int xOffset, int yOffset, int width, int height,
                                          int format, int type, java.nio.ByteBuffer pixels);
    public abstract void glGenerateMipmap(int target);
    public abstract void glActiveTexture(int texture);
    public abstract void glTexParameteri(int target, int pname, int param);

    // -------------------------------------------------------------------------
    // Draw calls
    // -------------------------------------------------------------------------

    public abstract void glDrawArrays(int mode, int first, int count);
    public abstract void glDrawElements(int mode, int count, int type, long indices);
    public abstract void glDrawArraysInstanced(int mode, int first, int count, int instanceCount);
    public abstract void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount);

    // -------------------------------------------------------------------------
    // GL state
    // -------------------------------------------------------------------------

    public abstract void glEnable(int cap);
    public abstract void glDisable(int cap);
    public abstract void glBlendFunc(int sfactor, int dfactor);
    public abstract void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
    public abstract void glDepthMask(boolean flag);
    public abstract void glCullFace(int mode);
    public abstract void glViewport(int x, int y, int width, int height);
    public abstract void glScissor(int x, int y, int width, int height);
    public abstract void glLineWidth(float width);
    public abstract void glPolygonMode(int face, int mode);
    public abstract void glColorMask(boolean red, boolean green, boolean blue, boolean alpha);
    public abstract void glStencilFunc(int func, int ref, int mask);
    public abstract void glStencilOp(int sfail, int dpfail, int dppass);
    public abstract void glAlphaFunc(int func, float ref);
}
