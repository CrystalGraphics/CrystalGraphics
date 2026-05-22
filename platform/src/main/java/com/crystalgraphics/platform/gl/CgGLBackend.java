package com.crystalgraphics.platform.gl;

import java.nio.*;


/**
 * Platform abstraction for all raw OpenGL calls made by the CrystalGraphics core engine.
 * Each platform provides a concrete implementation (e.g. {@code Lwjgl2GLBackend} for
 * MC 1.7.10 with LWJGL2) that delegates to the appropriate native GL bindings.
 *
 * <h3>Singleton access</h3>
 * <pre>{@code
 * CgGLBackend.get().glUseProgram(programId);
 * }</pre>
 *
 * <h3>FBO naming convention</h3>
 * FBO methods use the <strong>no-{@code gl}-prefix</strong> naming convention
 * ({@code bindFramebuffer}, {@code genFramebuffers}, etc.) while all other methods
 * use the standard {@code glXxx} prefix.  {@code CgGL}, the static facade in
 * {@code core/}, normalises all methods to the {@code gl}-prefix and delegates
 * internally (e.g. {@code CgGL.glBindFramebuffer} delegates to
 * {@code CgGLBackend.get().bindFramebuffer}).
 *
 * <h3>FBO waterfall</h3>
 * The {@link #bindFramebufferCompat(int)} method is the platform-neutral substitute for
 * {@code OpenGlHelper.func_153171_g} — mc1710 routes through the Minecraft compat helper,
 * standalone impls call {@code glBindFramebuffer} directly. Used by
 * {@code CallFamily.OPENGLHELPER_WRAPPER} routing in core/.
 */
public abstract class CgGLBackend {
    
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
    public abstract int getFramebufferAttachmentParameteriv(int target, int attachment, int pname);

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
    public abstract void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value);
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
    public abstract void glBufferData(int target, ByteBuffer data, int usage);
    public abstract void glBufferData(int target, ShortBuffer data, int usage);
    public abstract void glBufferData(int target, long size, int usage);
    public abstract void glBufferSubData(int target, long offset, ByteBuffer data);
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
                                       int format, int type, ByteBuffer pixels);
    public abstract void glTexImage2D(int target, int level, int internalFormat,
                                       int width, int height, int border,
                                       int format, int type, FloatBuffer pixels);
    public abstract void glTexSubImage2D(int target, int level,
                                          int xOffset, int yOffset, int width, int height,
                                          int format, int type, ByteBuffer pixels);
    public abstract void glTexSubImage2D(int target, int level,
                                          int xOffset, int yOffset, int width, int height,
                                          int format, int type, FloatBuffer pixels);
    public abstract void glTexImage3D(int target, int level, int internalFormat,
                                       int width, int height, int depth, int border,
                                       int format, int type, ByteBuffer pixels);
    public abstract void glTexSubImage3D(int target, int level,
                                          int xOffset, int yOffset, int zOffset,
                                          int width, int height, int depth,
                                          int format, int type, ByteBuffer pixels);
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

    // -------------------------------------------------------------------------
    // GL clear
    // -------------------------------------------------------------------------

    /** Clears the specified buffer bits on the currently bound framebuffer. */
    public abstract void glClear(int mask);

    /** Sets the depth value used when the depth buffer is cleared. */
    public abstract void glClearDepth(double depth);

    /** Sets the RGBA colour value written when the colour buffer is cleared. */
    public abstract void glClearColor(float r, float g, float b, float a);

    /** Sets the integer value written to the stencil buffer when it is cleared. */
    public abstract void glClearStencil(int s);

    // -------------------------------------------------------------------------
    // GL state — additional setters
    // -------------------------------------------------------------------------

    public abstract void glDepthFunc(int func);
    public abstract void glStencilMask(int mask);
    public abstract void glBlendEquationSeparate(int modeRGB, int modeAlpha);
    /** GL 3.0 per-draw-buffer color mask. */
    public abstract void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a);
    public abstract void glFrontFace(int mode);
    public abstract void glPolygonOffset(float factor, float units);
    public abstract void glPointSize(float size);
    public abstract void glDrawBuffer(int mode);
    public abstract void glReadBuffer(int mode);
    public abstract void glPixelStorei(int pname, int param);

    // -------------------------------------------------------------------------
    // GL state — queries
    // -------------------------------------------------------------------------

    public abstract int glGetInteger(int pname);
    public abstract void glGetInteger(int pname, IntBuffer params);
    public abstract boolean glGetBoolean(int pname);
    public abstract void glGetBoolean(int pname, ByteBuffer params);
    public abstract void glGetFloat(int pname, FloatBuffer params);
    /** Returns a single float state value (e.g. {@code GL_LINE_WIDTH}, {@code GL_POINT_SIZE}). */
    public abstract float glGetFloat(int pname);

    // -------------------------------------------------------------------------
    // Samplers
    // -------------------------------------------------------------------------

    /** Binds a sampler object to a texture unit (ARB_sampler_objects / GL 3.3). */
    public abstract void glBindSampler(int unit, int sampler);

    // -------------------------------------------------------------------------
    // Buffer mapping
    // -------------------------------------------------------------------------

    /** @return the mapped buffer, or {@code null} if mapping fails */
    public abstract ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer);
    public abstract boolean glUnmapBuffer(int target);
    public abstract void glFlushMappedBufferRange(int target, long offset, long length);

    // -------------------------------------------------------------------------
    // Sync objects (ARBSync / GL 3.2)
    // -------------------------------------------------------------------------

    public abstract long glFenceSync(int condition, int flags);
    public abstract int glClientWaitSync(long sync, int flags, long timeout);
    public abstract void glDeleteSync(long sync);


    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------
    
        public abstract int glGetError();

    // -------------------------------------------------------------------------
    // Context
    // -------------------------------------------------------------------------

    /** @return {@code true} if an OpenGL context is current on this thread. */
    public abstract boolean isContextCurrent();

    // -------------------------------------------------------------------------
    // Fixed-function matrix stack (legacy / compat — used by PoseStack)
    // -------------------------------------------------------------------------

    public abstract void glPushMatrix();
    public abstract void glPopMatrix();
    public abstract void glLoadMatrix(FloatBuffer m);

    // -------------------------------------------------------------------------
    // Framebuffers — renderbuffer operations (Core / ARB / EXT waterfall)
    // -------------------------------------------------------------------------

    public abstract int glGenRenderbuffers();
    public abstract void glDeleteRenderbuffers(int rbo);
    public abstract void glBindRenderbuffer(int target, int renderbuffer);
    public abstract void glRenderbufferStorage(int target, int internalFormat, int width, int height);
    public abstract void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer);
    // -------------------------------------------------------------------------
    // Shaders — ARBShaderObjects unified-handle methods
    //
    // ARBShaderObjects used a single "object" concept that could be either a shader
    // or a program handle.  The GL core split these into glDeleteShader/glDeleteProgram,
    // glGetShaderi/glGetProgrami, etc.  These unified wrappers preserve the ARB
    // handle-agnostic semantics so that CgArbShaderProgram and friends can migrate
    // without requiring per-call type analysis.
    // -------------------------------------------------------------------------

    /** Delete a shader OR program object handle (ARBShaderObjects unified semantics). */
    public abstract void glDeleteObject(int handle);

    /**
     * Query a parameter on a shader or program object handle.
     * Equivalent to {@code ARBShaderObjects.glGetObjectParameteriARB}.
     */
    public abstract int glGetObjectParameteri(int obj, int pname);

    /**
     * Retrieve the info log for a shader or program object handle.
     * Equivalent to {@code ARBShaderObjects.glGetInfoLogARB}.
     */
    public abstract String glGetObjectInfoLog(int obj, int maxLength);

    /**
     * Returns the handle of the currently active object for the given target.
     * Typically called as {@code glGetHandle(GL_PROGRAM_OBJECT_ARB)} to obtain
     * the currently bound program handle.
     * Equivalent to {@code ARBShaderObjects.glGetHandleARB}.
     */
    public abstract int glGetHandle(int pname);

    // -------------------------------------------------------------------------
    // Shaders — additional methods
    // -------------------------------------------------------------------------

    public abstract void glDetachShader(int program, int shader);
    public abstract void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders);
    /** LWJGL2 convenience form: returns the uniform name; fills {@code sizeTypeBuf[0]=size, [1]=type}. */
    public abstract String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf);
    /** Sets a float-array uniform ({@code glUniform1fv} semantics). */
    public abstract void glUniform1(int location, FloatBuffer values);
    /** Sets an int-array uniform ({@code glUniform1iv} semantics). */
    public abstract void glUniform1(int location, IntBuffer values);
    public abstract void glUniformMatrix3(int location, boolean transpose, FloatBuffer value);
    /** Equivalent to {@link #glUniformMatrix4fv}; present for LWJGL2 naming parity. */
    public abstract void glUniformMatrix4(int location, boolean transpose, FloatBuffer value);
}
