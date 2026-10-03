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
 * <p>Every backend meets a GL 3.3 floor, so there is one spelling per call and no ARB or EXT fallback.</p>
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
    // Framebuffers
    // -------------------------------------------------------------------------

    public abstract void bindFramebuffer(int target, int fbo);
    public abstract void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                          int dstX0, int dstY0, int dstX1, int dstY1,
                                          int mask, int filter);

    // ── GPU-side texture copy ────────────────────────────────────────────────
    //
    // Deliberately NOT abstract, unlike everything above: these are optional fast paths, so a
    // backend that has not implemented one leaves the default in place and CgTextureCopy falls
    // through to its next strategy. Making them abstract would force every present and future
    // loader backend to implement an optional path immediately.
    //
    // Whether the CONTEXT supports these is CgCapabilities' question
    // (isCopyImageSubDataSupported), not the backend's —
    // the backend only answers "have I wired this call up". CgTextureCopy checks the former and
    // treats UnsupportedOperationException from the latter as "fall through", so the two can
    // disagree safely.

    /**
     * Direct GPU-to-GPU texel copy between two texture images, no CPU round trip
     * ({@code glCopyImageSubData}, core GL 4.3).
     *
     * @throws UnsupportedOperationException if this backend has not implemented it
     */
    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                  int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                  int srcWidth, int srcHeight, int srcDepth) {
        throw new UnsupportedOperationException("glCopyImageSubData unsupported by this backend");
    }

    /**
     * Attaches a single layer of an array/3D texture to the bound framebuffer
     * ({@code glFramebufferTextureLayer}, core GL 3.0).
     *
     * @throws UnsupportedOperationException if this backend has not implemented it
     */
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        throw new UnsupportedOperationException("glFramebufferTextureLayer unsupported by this backend");
    }
    public abstract int genFramebuffers();
    public abstract void deleteFramebuffers(int fbo);
    public abstract void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level);
    public abstract int checkFramebufferStatus(int target);
    public abstract void drawBuffers(IntBuffer bufs);
    public abstract int getFramebufferAttachmentParameteriv(int target, int attachment, int pname);

    // There is deliberately no host-delegating bind beside `bindFramebuffer`: a host that keeps its own
    // framebuffer tracking overrides this one (Blaze3dGLBackend, GlStateManagerGLBackend).

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
    /** Between the buffers bound at two targets, in GL order with the draws around it (core GL 3.1). */
    public abstract void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size);
    public abstract void glDeleteBuffers(int buffer);
    public abstract void glBindBufferBase(int target, int index, int buffer);
    public abstract void glBindBufferRange(int target, int index, int buffer, long offset, long size);
    public abstract void glTexBuffer(int target, int internalFormat, int buffer);

    // -------------------------------------------------------------------------
    // Timer queries (GPU timing)
    //
    // GL_TIME_ELAPSED queries CANNOT be nested — only one may be active at a time.
    // Reading a result in the same frame it was issued stalls the pipeline and
    // destroys the thing being measured, so callers must poll with
    // glIsQueryResultAvailable() on a later frame.
    // -------------------------------------------------------------------------
    
    public abstract int glGenQuery() ;
    /** Begins a {@code GL_TIME_ELAPSED} query. Must not be nested inside another. */
    public abstract void glBeginTimeElapsedQuery(int query) ;
    public abstract void glEndTimeElapsedQuery() ;
    /** Non-blocking. Polling this instead of reading directly is what avoids a pipeline stall. */
    public abstract boolean glIsQueryResultAvailable(int query) ;
    /** @return elapsed GPU time in nanoseconds; only valid once {@link #glIsQueryResultAvailable} is true */
    public abstract long glGetQueryResultNanos(int query) ;
    public abstract void glDeleteQuery(int query);

    // -------------------------------------------------------------------------
    // Vertex Array Objects
    // -------------------------------------------------------------------------

    public abstract int glGenVertexArrays();
    public abstract void glBindVertexArray(int array);
    public abstract void glDeleteVertexArrays(int array);
    public abstract void glEnableVertexAttribArray(int index);
    public abstract void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
    /** An integer attribute: the shader reads {@code int}/{@code ivec}/{@code uvec}, never a float. */
    public abstract void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer);
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
    public abstract void glTexImage3D(int target, int level, int internalFormat,
                                       int width, int height, int depth, int border,
                                       int format, int type, FloatBuffer pixels);
    public abstract void glTexSubImage3D(int target, int level,
                                          int xOffset, int yOffset, int zOffset,
                                          int width, int height, int depth,
                                          int format, int type, ByteBuffer pixels);
    public abstract void glTexSubImage3D(int target, int level,
                                          int xOffset, int yOffset, int zOffset,
                                          int width, int height, int depth,
                                          int format, int type, FloatBuffer pixels);
    /** {@code short}-data variant — the natural fit for {@code GL_HALF_FLOAT} uploads. */
    public abstract void glTexSubImage3D(int target, int level,
                                          int xOffset, int yOffset, int zOffset,
                                          int width, int height, int depth,
                                          int format, int type, ShortBuffer pixels);
    public abstract void glGenerateMipmap(int target);
    public abstract void glActiveTexture(int texture);
    public abstract void glTexParameteri(int target, int pname, int param);
    /** Reads back the currently bound texture's mip level 0 (all layers/faces, for array/cubemap targets). */
    public abstract void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels);

    // -------------------------------------------------------------------------
    // Draw calls
    // -------------------------------------------------------------------------

    public abstract void glDrawArrays(int mode, int first, int count);
    public abstract void glDrawElements(int mode, int count, int type, long indices);
    public abstract void glDrawArraysInstanced(int mode, int first, int count, int instanceCount);
    public abstract void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount);
    /** Each index plus {@code baseVertex}, as is {@code gl_VertexID} (core GL 3.2). */
    public abstract void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices,
                                                           int instanceCount, int baseVertex);

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
    public abstract void glReadPixels(int x, int y, int width, int height,
                                      int format, int type, ByteBuffer pixels);
    /** Into the bound {@code GL_PIXEL_PACK_BUFFER} at {@code packOffset}; returns without waiting. */
    public abstract void glReadPixels(int x, int y, int width, int height,
                                      int format, int type, long packOffset);
    public abstract void glGetInteger(int pname, IntBuffer params);
    public abstract boolean glGetBoolean(int pname);
    public abstract void glGetBoolean(int pname, ByteBuffer params);
    public abstract void glGetFloat(int pname, FloatBuffer params);
    /** Returns a single float state value (e.g. {@code GL_LINE_WIDTH}, {@code GL_POINT_SIZE}). */
    public abstract float glGetFloat(int pname);
    /** {@code glGetString}: the context's version, vendor, renderer or shading-language version. */
    public abstract String glGetString(int name);
    /** {@code glGetStringi}: the string at {@code index}, an extension's name for {@code GL_EXTENSIONS}. */
    public abstract String glGetStringi(int name, int index);
    /** {@code glGetIntegeri_v}: one element of an indexed value, such as a compute work group's dimension. */
    public abstract int glGetIntegeri(int target, int index);

    // -------------------------------------------------------------------------
    // Samplers
    // -------------------------------------------------------------------------

    /** Binds a sampler object to a texture unit (GL 3.3). */
    public abstract void glBindSampler(int unit, int sampler);

    // -------------------------------------------------------------------------
    // Buffer mapping
    // -------------------------------------------------------------------------

    /** @return the mapped buffer, or {@code null} if mapping fails */
    public abstract ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer);
    public abstract boolean glUnmapBuffer(int target);
    public abstract void glFlushMappedBufferRange(int target, long offset, long length);
    /** Immutable buffer storage (GL 4.4 / {@code ARB_buffer_storage}). */
    public abstract void glBufferStorage(int target, long size, int flags);

    // -------------------------------------------------------------------------
    // Sync objects (GL 3.2)
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
    // Host coexistence
    // -------------------------------------------------------------------------

    /**
     * A host's texture as a GL name the engine can adopt with {@code CgTexture2D.wrap}.
     *
     * <pre>{@code
     * int name = CgGL.importHostTexture(minecraftTextureId);    // an Integer on every GL host
     * CgTexture2D host = CgTexture2D.wrap(name, width, height);
     * }</pre>
     *
     * <p>On a GL backend the handle already is a GL name and comes back unchanged; a backend over another
     * API registers the host's image under a name of its own.</p>
     */
    public abstract int importHostTexture(Object hostHandle);

    /**
     * The host hands us its frame. Reached through {@link CgGL#fromHost()}, once per outermost bracket, which
     * explains why the pair exists.
     *
     * <ul>
     *   <li>A GL backend does nothing: the host's own brackets manage the shared context.</li>
     *   <li>A device-backed backend takes what the host owns and we draw into, such as the command buffer it is
     *       recording and its current target.</li>
     * </ul>
     */
    public abstract void fromHost();

    /**
     * We hand the frame back. Reached through {@link CgGL#toHost()}, once, when the outermost bracket closes.
     *
     * <ul>
     *   <li>A GL backend does nothing.</li>
     *   <li>A device-backed backend ends any render pass it has open, so the host never records into ours, and
     *       leaves the host's command buffer and images in the state the host's own tracking expects.</li>
     * </ul>
     */
    public abstract void toHost();

    /**
     * Whether this thread may issue calls. On GL, the state manager's owner; on a device-backed backend, the
     * device's. For a caller that must decline rather than throw, such as a callback forwarded from another thread.
     */
    public abstract boolean ownedByCurrentThread();

    // -------------------------------------------------------------------------
    // Framebuffers — renderbuffer operations
    // -------------------------------------------------------------------------

    public abstract int glGenRenderbuffers();
    public abstract void glDeleteRenderbuffers(int rbo);
    public abstract void glBindRenderbuffer(int target, int renderbuffer);
    public abstract void glRenderbufferStorage(int target, int internalFormat, int width, int height);

    /**
     * Multisampled renderbuffer storage — the backing call for MSAA framebuffers.
     *
     * <p>{@code samples} is a request, not a guarantee: GL silently clamps to the implementation's
     * maximum, so a caller asking for 16 on hardware offering 4 gets 4 rather than an error.</p>
     */
    public abstract void glRenderbufferStorageMultisample(int target, int samples, int internalFormat,
                                                          int width, int height);

    public abstract void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer);

    /**
     * Multisampled <b>texture</b> storage, for {@code GL_TEXTURE_2D_MULTISAMPLE}.
     *
     * <p>The difference from {@link #glRenderbufferStorageMultisample} is that this attachment can be
     * <b>sampled</b> — a shader reads it through {@code sampler2DMS} and picks individual samples, which
     * a renderbuffer cannot offer at all. Use a renderbuffer when the target will only ever be resolved
     * by a blit; use this when a pass needs to read the samples itself.</p>
     *
     * <p>{@code fixedSampleLocations} must be true for an attachment mixed with renderbuffers in the
     * same framebuffer, or completeness fails.</p>
     */
    public abstract void glTexImage2DMultisample(int target, int samples, int internalFormat,
                                                 int width, int height, boolean fixedSampleLocations);
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
