package com.crystalgraphics.platform.gl.driver;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;

import java.nio.ByteBuffer;

/**
 * The GL 4.6 entry points {@code CgGLDevice} calls, one implementation per LWJGL era (Unreal's {@code FOpenGL}): direct
 * state access throughout, so nothing is bound to be edited. The constants they take are {@link GLC}'s.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), translator, width, height);
 * }</pre>
 *
 * <ul>
 *   <li>Every call is made on the thread whose context is current.</li>
 *   <li>A {@code ByteBuffer} is read or written from its position to its limit, and must be direct.</li>
 *   <li>A {@code long offset} in place of a pointer is a byte offset into the buffer bound at that call's target
 *   ({@code GL_PIXEL_PACK_BUFFER}, {@code GL_DRAW_INDIRECT_BUFFER}, ...), as GL reads it.</li>
 * </ul>
 */
public interface CgGLDriver {

    // ── queries ───────────────────────────────────────────────────────────────
    String glGetString(int name);

    int glGetInteger(int name);

    float glGetFloat(int name);

    boolean hasExtension(String name);

    /** A context at this one's version sharing none of its objects, for a link worker; null where none can be made. */
    CgGLWorkerContext createIsolatedContext();

    boolean glIsEnabled(int cap);

    boolean glIsEnabledi(int cap, int index);

    /** {@code out.length} values of {@code name}. */
    void glGetIntegerv(int name, int[] out);

    void glGetFloatv(int name, float[] out);

    /** {@code out.length} values of {@code name} at {@code index}. */
    void glGetIntegeri_v(int name, int index, int[] out);

    long glGetInteger64i(int name, int index);

    void glObjectLabel(int identifier, int name, String label);

    void glPushDebugGroup(String message);

    void glPopDebugGroup();

    // ── buffers ───────────────────────────────────────────────────────────────
    int glCreateBuffers();

    void glNamedBufferStorage(int buffer, long size, int flags);

    ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access);

    void glNamedBufferSubData(int buffer, long offset, ByteBuffer data);

    void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer out);

    void glCopyNamedBufferSubData(int read, int write, long readOffset, long writeOffset, long size);

    void glClearNamedBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type,
                                   ByteBuffer value);

    void glDeleteBuffers(int buffer);

    void glBindBuffer(int target, int buffer);

    void glBindBufferRange(int target, int index, int buffer, long offset, long size);

    void glBindBufferBase(int target, int index, int buffer);

    // ── textures and samplers ─────────────────────────────────────────────────
    int glCreateTextures(int target);

    /** A name never bound, for {@link #glTextureView}. */
    int glGenTextures();

    void glTextureStorage2D(int texture, int levels, int internalFormat, int width, int height);

    void glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth);

    void glTextureStorage2DMultisample(int texture, int samples, int internalFormat, int width, int height);

    void glTextureBufferRange(int texture, int internalFormat, int buffer, long offset, long size);

    void glTextureView(int texture, int target, int orig, int internalFormat, int minLevel, int levels, int minLayer,
                       int layers);

    void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                             ByteBuffer pixels);

    void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                             long offset);

    void glCompressedTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format,
                                       ByteBuffer data);

    void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth, int format,
                             int type, ByteBuffer pixels);

    void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth, int format,
                             int type, long offset);

    void glCompressedTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                       int format, ByteBuffer data);

    void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format,
                              int type, ByteBuffer out);

    void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format,
                              int type, int size, long offset);

    void glCopyImageSubData(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dst, int dstTarget,
                            int dstLevel, int dstX, int dstY, int dstZ, int width, int height, int depth);

    void glPixelStorei(int name, int value);

    void glGenerateTextureMipmap(int texture);

    void glDeleteTextures(int texture);

    void glBindTextureUnit(int unit, int texture);

    /** Only to read a unit's bindings: the device binds by unit and never relies on the active one. */
    void glActiveTexture(int texture);

    void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format);

    int glCreateSamplers();

    void glSamplerParameteri(int sampler, int name, int value);

    void glSamplerParameterf(int sampler, int name, float value);

    void glDeleteSamplers(int sampler);

    void glBindSampler(int unit, int sampler);

    // ── ARB_bindless_texture ──────────────────────────────────────────────────
    long glGetTextureSamplerHandleARB(int texture, int sampler);

    void glMakeTextureHandleResidentARB(long handle);

    void glMakeTextureHandleNonResidentARB(long handle);

    // ── framebuffers ──────────────────────────────────────────────────────────
    int glCreateFramebuffers();

    void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level);

    void glNamedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer);

    void glNamedFramebufferDrawBuffers(int framebuffer, int[] buffers);

    int glCheckNamedFramebufferStatus(int framebuffer, int target);

    void glDeleteFramebuffers(int framebuffer);

    void glBindFramebuffer(int target, int framebuffer);

    void glClearNamedFramebufferfv(int framebuffer, int buffer, int drawBuffer, float r, float g, float b, float a);

    void glClearNamedFramebufferiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a);

    void glClearNamedFramebufferuiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a);

    void glClearNamedFramebufferfi(int framebuffer, int buffer, int drawBuffer, float depth, int stencil);

    /** The first {@code count} of {@code attachments}. */
    void glInvalidateNamedFramebufferData(int framebuffer, int[] attachments, int count);

    void glBlitNamedFramebuffer(int read, int draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1,
                                int dy1, int mask, int filter);

    // ── programs ──────────────────────────────────────────────────────────────
    int glCreateShader(int type);

    void glShaderSource(int shader, String source);

    void glCompileShader(int shader);

    int glGetShaderi(int shader, int name);

    String glGetShaderInfoLog(int shader);

    void glDeleteShader(int shader);

    int glCreateProgram();

    void glAttachShader(int program, int shader);

    void glDetachShader(int program, int shader);

    void glLinkProgram(int program);

    int glGetProgrami(int program, int name);

    String glGetProgramInfoLog(int program);

    void glDeleteProgram(int program);

    void glUseProgram(int program);

    void glProgramParameteri(int program, int name, int value);

    /** Fills {@code out} with {@code program}'s binary and answers its format. */
    int glGetProgramBinary(int program, ByteBuffer out);

    void glProgramBinary(int program, int format, ByteBuffer binary);

    // ── vertex arrays ─────────────────────────────────────────────────────────
    int glCreateVertexArrays();

    void glEnableVertexArrayAttrib(int vao, int index);

    void glVertexArrayAttribFormat(int vao, int index, int size, int type, boolean normalized, int offset);

    void glVertexArrayAttribIFormat(int vao, int index, int size, int type, int offset);

    void glVertexArrayAttribBinding(int vao, int index, int binding);

    void glVertexArrayBindingDivisor(int vao, int binding, int divisor);

    void glVertexArrayVertexBuffer(int vao, int binding, int buffer, long offset, int stride);

    void glVertexArrayElementBuffer(int vao, int buffer);

    void glDeleteVertexArrays(int vao);

    void glBindVertexArray(int vao);

    // ── fixed-function state ──────────────────────────────────────────────────
    void glEnable(int cap);

    void glDisable(int cap);

    void glEnablei(int cap, int index);

    void glDisablei(int cap, int index);

    void glCullFace(int mode);

    void glFrontFace(int mode);

    void glPolygonMode(int face, int mode);

    void glPolygonOffset(float factor, float units);

    void glDepthFunc(int func);

    void glDepthMask(boolean write);

    void glStencilFuncSeparate(int face, int func, int ref, int mask);

    void glStencilOpSeparate(int face, int fail, int depthFail, int pass);

    void glStencilMaskSeparate(int face, int mask);

    void glBlendFuncSeparatei(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha);

    void glBlendEquationSeparatei(int buffer, int modeRgb, int modeAlpha);

    void glColorMaski(int buffer, boolean r, boolean g, boolean b, boolean a);

    void glViewportIndexedf(int index, float x, float y, float width, float height);

    void glDepthRangef(float near, float far);

    void glScissor(int x, int y, int width, int height);

    void glClipControl(int origin, int depth);

    // ── draws and dispatches ──────────────────────────────────────────────────
    void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int baseInstance);

    void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long offset, int instances,
                                                       int baseVertex, int baseInstance);

    void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride);

    void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride);

    void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount, int stride);

    void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maxDrawCount,
                                          int stride);

    void glDispatchCompute(int x, int y, int z);

    void glDispatchComputeIndirect(long offset);

    void glMemoryBarrier(int barriers);

    // ── sync and queries ──────────────────────────────────────────────────────
    /** {@code glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)}. */
    long glFenceSync();

    /** {@code GL_ALREADY_SIGNALED}, {@code GL_CONDITION_SATISFIED}, {@code GL_TIMEOUT_EXPIRED} or {@code GL_WAIT_FAILED}. */
    int glClientWaitSync(long sync, int flags, long timeoutNanos);

    void glDeleteSync(long sync);

    void glFlush();

    void glFinish();

    int glCreateQueries(int target);

    void glQueryCounter(int query, int target);

    long glGetQueryObjecti64(int query, int name);

    void glDeleteQueries(int query);
}
