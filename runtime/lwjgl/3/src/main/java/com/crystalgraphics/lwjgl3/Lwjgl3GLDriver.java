package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import org.lwjgl.opengl.ARBBindlessTexture;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * {@link CgGLDriver} on LWJGL 3: each call the GL function of its name. Written against the plain {@code GLxx} classes,
 * which every LWJGL 3 a client ships has.
 */
public final class Lwjgl3GLDriver implements CgGLDriver {

    private static final int GL_EXTENSIONS = 0x1F03, GL_NUM_EXTENSIONS = 0x821D, GL_DEBUG_SOURCE_APPLICATION = 0x824A;

    private Set<String> extensions;

    /**
     * Which of the functions whose extension spells them apart the current context has as core. LWJGL loads a core
     * function only on a context of its version: Minecraft's 3.2 core request comes back named 3.3 with every
     * extension after it, so there {@code glBlendFuncSeparatei} is {@code ARB_draw_buffers_blend}'s
     * {@code glBlendFuncSeparateiARB}. The extensions that kept the core names (direct state access and the rest)
     * load under them either way.
     */
    private static final class Core {
        final GLCapabilities caps;
        final boolean blendIndexed, indirectCount;

        Core(GLCapabilities caps) {
            this.caps = caps;
            blendIndexed = caps.glBlendFuncSeparatei != NULL;
            indirectCount = caps.glMultiDrawArraysIndirectCount != NULL;
        }
    }

    /** Per thread, as a context is: the render thread's and a link worker's differ. */
    private final ThreadLocal<Core> core = new ThreadLocal<>();

    private Core core() {
        GLCapabilities caps = GL.getCapabilities();
        Core known = core.get();
        if (known == null || known.caps != caps) core.set(known = new Core(caps));
        return known;
    }

    // ── queries ───────────────────────────────────────────────────────────────
    @Override public String glGetString(int name) { return GL46.glGetString(name); }

    @Override public int glGetInteger(int name) { return GL46.glGetInteger(name); }

    @Override public float glGetFloat(int name) { return GL46.glGetFloat(name); }

    @Override
    public boolean hasExtension(String name) {
        if (extensions == null) {
            extensions = new HashSet<>();
            int n = GL46.glGetInteger(GL_NUM_EXTENSIONS);
            for (int i = 0; i < n; i++) extensions.add(GL46.glGetStringi(GL_EXTENSIONS, i));
        }
        return extensions.contains(name);
    }

    @Override public CgGLWorkerContext createIsolatedContext() { return Lwjgl3WorkerContext.create(false); }

    @Override public boolean glIsEnabled(int cap) { return GL46.glIsEnabled(cap); }

    @Override public boolean glIsEnabledi(int cap, int index) { return GL46.glIsEnabledi(cap, index); }

    @Override public void glGetIntegerv(int name, int[] out) { GL46.glGetIntegerv(name, out); }

    @Override public void glGetFloatv(int name, float[] out) { GL46.glGetFloatv(name, out); }

    @Override public void glGetIntegeri_v(int name, int index, int[] out) { GL46.glGetIntegeri_v(name, index, out); }

    @Override public long glGetInteger64i(int name, int index) { return GL46.glGetInteger64i(name, index); }

    @Override public void glObjectLabel(int identifier, int name, String label) { GL46.glObjectLabel(identifier, name, label); }

    @Override public void glPushDebugGroup(String message) { GL46.glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, message); }

    @Override public void glPopDebugGroup() { GL46.glPopDebugGroup(); }

    // ── buffers ───────────────────────────────────────────────────────────────
    @Override public int glCreateBuffers() { return GL46.glCreateBuffers(); }

    @Override public void glNamedBufferStorage(int buffer, long size, int flags) { GL46.glNamedBufferStorage(buffer, size, flags); }

    @Override
    public ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access) {
        return GL46.glMapNamedBufferRange(buffer, offset, length, access);
    }

    @Override public void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { GL46.glNamedBufferSubData(buffer, offset, data); }

    @Override public void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer out) { GL46.glGetNamedBufferSubData(buffer, offset, out); }

    @Override
    public void glCopyNamedBufferSubData(int read, int write, long readOffset, long writeOffset, long size) {
        GL46.glCopyNamedBufferSubData(read, write, readOffset, writeOffset, size);
    }

    @Override
    public void glClearNamedBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type,
                                          ByteBuffer value) {
        GL46.glClearNamedBufferSubData(buffer, internalFormat, offset, size, format, type, value);
    }

    @Override public void glBindBufferBase(int target, int index, int buffer) { GL46.glBindBufferBase(target, index, buffer); }

    @Override public long glGetNamedBufferParameteri64(int buffer, int name) { return GL46.glGetNamedBufferParameteri64(buffer, name); }

    @Override public void glDeleteBuffers(int buffer) { GL46.glDeleteBuffers(buffer); }

    @Override public void glBindBuffer(int target, int buffer) { GL46.glBindBuffer(target, buffer); }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        GL46.glBindBufferRange(target, index, buffer, offset, size);
    }

    // ── textures and samplers ─────────────────────────────────────────────────
    @Override public int glCreateTextures(int target) { return GL46.glCreateTextures(target); }

    @Override public int glGenTextures() { return GL46.glGenTextures(); }

    @Override
    public void glTextureStorage2D(int texture, int levels, int internalFormat, int width, int height) {
        GL46.glTextureStorage2D(texture, levels, internalFormat, width, height);
    }

    @Override
    public void glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) {
        GL46.glTextureStorage3D(texture, levels, internalFormat, width, height, depth);
    }

    @Override
    public void glTextureStorage2DMultisample(int texture, int samples, int internalFormat, int width, int height) {
        GL46.glTextureStorage2DMultisample(texture, samples, internalFormat, width, height, true);
    }

    @Override
    public void glTextureBufferRange(int texture, int internalFormat, int buffer, long offset, long size) {
        GL46.glTextureBufferRange(texture, internalFormat, buffer, offset, size);
    }

    @Override
    public void glTextureView(int texture, int target, int orig, int internalFormat, int minLevel, int levels, int minLayer,
                              int layers) {
        GL46.glTextureView(texture, target, orig, internalFormat, minLevel, levels, minLayer, layers);
    }

    @Override
    public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                                    ByteBuffer pixels) {
        GL46.glTextureSubImage2D(texture, level, x, y, width, height, format, type, pixels);
    }

    @Override
    public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                                    long offset) {
        GL46.glTextureSubImage2D(texture, level, x, y, width, height, format, type, offset);
    }

    @Override
    public void glCompressedTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format,
                                              ByteBuffer data) {
        GL46.glCompressedTextureSubImage2D(texture, level, x, y, width, height, format, data);
    }

    @Override
    public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                    int format, int type, ByteBuffer pixels) {
        GL46.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, pixels);
    }

    @Override
    public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                    int format, int type, long offset) {
        GL46.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, offset);
    }

    @Override
    public void glCompressedTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height,
                                              int depth, int format, ByteBuffer data) {
        GL46.glCompressedTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, data);
    }

    @Override
    public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                     int format, int type, ByteBuffer out) {
        GL46.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, out);
    }

    @Override
    public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                     int format, int type, int size, long offset) {
        GL46.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, size, offset);
    }

    @Override
    public void glCopyImageSubData(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dst,
                                   int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int width, int height,
                                   int depth) {
        GL46.glCopyImageSubData(src, srcTarget, srcLevel, srcX, srcY, srcZ, dst, dstTarget, dstLevel, dstX, dstY, dstZ,
                width, height, depth);
    }

    @Override public void glPixelStorei(int name, int value) { GL46.glPixelStorei(name, value); }

    @Override public void glGenerateTextureMipmap(int texture) { GL46.glGenerateTextureMipmap(texture); }

    @Override public void glDeleteTextures(int texture) { GL46.glDeleteTextures(texture); }

    @Override public void glBindTextureUnit(int unit, int texture) { GL46.glBindTextureUnit(unit, texture); }

    @Override public void glActiveTexture(int texture) { GL46.glActiveTexture(texture); }

    @Override
    public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        GL46.glBindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    @Override public int glCreateSamplers() { return GL46.glCreateSamplers(); }

    @Override public void glSamplerParameteri(int sampler, int name, int value) { GL46.glSamplerParameteri(sampler, name, value); }
    @Override public void glTextureParameteri(int texture, int name, int value) { GL46.glTextureParameteri(texture, name, value); }

    @Override public void glSamplerParameterf(int sampler, int name, float value) { GL46.glSamplerParameterf(sampler, name, value); }

    @Override public void glDeleteSamplers(int sampler) { GL46.glDeleteSamplers(sampler); }

    @Override public void glBindSampler(int unit, int sampler) { GL46.glBindSampler(unit, sampler); }

    // ── ARB_bindless_texture ──────────────────────────────────────────────────
    @Override
    public long glGetTextureSamplerHandleARB(int texture, int sampler) {
        return ARBBindlessTexture.glGetTextureSamplerHandleARB(texture, sampler);
    }

    @Override public void glMakeTextureHandleResidentARB(long handle) { ARBBindlessTexture.glMakeTextureHandleResidentARB(handle); }

    @Override
    public void glMakeTextureHandleNonResidentARB(long handle) {
        ARBBindlessTexture.glMakeTextureHandleNonResidentARB(handle);
    }

    // ── framebuffers ──────────────────────────────────────────────────────────
    @Override public int glCreateFramebuffers() { return GL46.glCreateFramebuffers(); }

    @Override
    public void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        GL46.glNamedFramebufferTexture(framebuffer, attachment, texture, level);
    }

    @Override
    public void glNamedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffer) {
        GL46.glNamedFramebufferRenderbuffer(framebuffer, attachment, 0x8D41 /* GL_RENDERBUFFER */, renderbuffer);
    }

    @Override
    public void glNamedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        GL46.glNamedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer);
    }

    @Override public void glNamedFramebufferDrawBuffers(int framebuffer, int[] buffers) { GL46.glNamedFramebufferDrawBuffers(framebuffer, buffers); }

    @Override public int glCheckNamedFramebufferStatus(int framebuffer, int target) { return GL46.glCheckNamedFramebufferStatus(framebuffer, target); }

    @Override public void glDeleteFramebuffers(int framebuffer) { GL46.glDeleteFramebuffers(framebuffer); }

    @Override public void glBindFramebuffer(int target, int framebuffer) { GL46.glBindFramebuffer(target, framebuffer); }

    @Override
    public void glClearNamedFramebufferfv(int framebuffer, int buffer, int drawBuffer, float r, float g, float b, float a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46.glClearNamedFramebufferfv(framebuffer, buffer, drawBuffer, stack.floats(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46.glClearNamedFramebufferiv(framebuffer, buffer, drawBuffer, stack.ints(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferuiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46.glClearNamedFramebufferuiv(framebuffer, buffer, drawBuffer, stack.ints(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferfi(int framebuffer, int buffer, int drawBuffer, float depth, int stencil) {
        GL46.glClearNamedFramebufferfi(framebuffer, buffer, drawBuffer, depth, stencil);
    }

    @Override
    public void glInvalidateNamedFramebufferData(int framebuffer, int[] attachments, int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46.glInvalidateNamedFramebufferData(framebuffer, stack.ints(attachments).limit(count));
        }
    }

    @Override
    public void glBlitNamedFramebuffer(int read, int draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1,
                                       int dy1, int mask, int filter) {
        GL46.glBlitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    // ── programs ──────────────────────────────────────────────────────────────
    @Override public int glCreateShader(int type) { return GL46.glCreateShader(type); }

    @Override public void glShaderSource(int shader, String source) { GL46.glShaderSource(shader, source); }

    @Override public void glCompileShader(int shader) { GL46.glCompileShader(shader); }

    @Override public int glGetShaderi(int shader, int name) { return GL46.glGetShaderi(shader, name); }

    @Override public String glGetShaderInfoLog(int shader) { return GL46.glGetShaderInfoLog(shader); }

    @Override public void glDeleteShader(int shader) { GL46.glDeleteShader(shader); }

    @Override public int glCreateProgram() { return GL46.glCreateProgram(); }

    @Override public void glAttachShader(int program, int shader) { GL46.glAttachShader(program, shader); }

    @Override public void glDetachShader(int program, int shader) { GL46.glDetachShader(program, shader); }

    @Override public void glLinkProgram(int program) { GL46.glLinkProgram(program); }

    @Override public int glGetProgrami(int program, int name) { return GL46.glGetProgrami(program, name); }
    @Override public int glGetTextureLevelParameteri(int texture, int level, int name) {
        return GL46.glGetTextureLevelParameteri(texture, level, name);
    }

    @Override public int glGetNamedRenderbufferParameteri(int renderbuffer, int name) {
        return GL46.glGetNamedRenderbufferParameteri(renderbuffer, name);
    }

    @Override public int glGetNamedFramebufferAttachmentParameteri(int framebuffer, int attachment, int name) {
        return GL46.glGetNamedFramebufferAttachmentParameteri(framebuffer, attachment, name);
    }

    @Override public String glGetProgramInfoLog(int program) { return GL46.glGetProgramInfoLog(program); }

    @Override public void glDeleteProgram(int program) { GL46.glDeleteProgram(program); }

    @Override
    public int glGetProgramInterfacei(int program, int programInterface, int name) {
        return GL46.glGetProgramInterfacei(program, programInterface, name);
    }

    @Override
    public String glGetProgramResourceName(int program, int programInterface, int index) {
        return GL46.glGetProgramResourceName(program, programInterface, index);
    }

    @Override
    public int glGetProgramResourcei(int program, int programInterface, int index, int property) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            GL46.glGetProgramResourceiv(program, programInterface, index, stack.ints(property), null, value);
            return value.get(0);
        }
    }

    @Override public int glGetUniformi(int program, int location) { return GL46.glGetUniformi(program, location); }

    @Override public void glUseProgram(int program) { GL46.glUseProgram(program); }

    @Override public void glProgramParameteri(int program, int name, int value) { GL46.glProgramParameteri(program, name, value); }

    @Override
    public int glGetProgramBinary(int program, ByteBuffer out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer format = stack.mallocInt(1);
            GL46.glGetProgramBinary(program, null, format, out);
            return format.get(0);
        }
    }

    @Override public void glProgramBinary(int program, int format, ByteBuffer binary) { GL46.glProgramBinary(program, format, binary); }

    // ── vertex arrays ─────────────────────────────────────────────────────────
    @Override public int glCreateVertexArrays() { return GL46.glCreateVertexArrays(); }

    @Override public void glEnableVertexArrayAttrib(int vao, int index) { GL46.glEnableVertexArrayAttrib(vao, index); }

    @Override
    public void glVertexArrayAttribFormat(int vao, int index, int size, int type, boolean normalized, int offset) {
        GL46.glVertexArrayAttribFormat(vao, index, size, type, normalized, offset);
    }

    @Override
    public void glVertexArrayAttribIFormat(int vao, int index, int size, int type, int offset) {
        GL46.glVertexArrayAttribIFormat(vao, index, size, type, offset);
    }

    @Override public void glVertexArrayAttribBinding(int vao, int index, int binding) { GL46.glVertexArrayAttribBinding(vao, index, binding); }

    @Override public void glVertexArrayBindingDivisor(int vao, int binding, int divisor) { GL46.glVertexArrayBindingDivisor(vao, binding, divisor); }

    @Override
    public void glVertexArrayVertexBuffer(int vao, int binding, int buffer, long offset, int stride) {
        GL46.glVertexArrayVertexBuffer(vao, binding, buffer, offset, stride);
    }

    @Override public void glVertexArrayElementBuffer(int vao, int buffer) { GL46.glVertexArrayElementBuffer(vao, buffer); }

    @Override public void glDeleteVertexArrays(int vao) { GL46.glDeleteVertexArrays(vao); }

    @Override public void glBindVertexArray(int vao) { GL46.glBindVertexArray(vao); }

    // ── fixed-function state ──────────────────────────────────────────────────
    @Override public void glEnable(int cap) { GL46.glEnable(cap); }

    @Override public void glDisable(int cap) { GL46.glDisable(cap); }

    @Override public void glEnablei(int cap, int index) { GL46.glEnablei(cap, index); }

    @Override public void glDisablei(int cap, int index) { GL46.glDisablei(cap, index); }

    @Override public void glCullFace(int mode) { GL46.glCullFace(mode); }

    @Override public void glFrontFace(int mode) { GL46.glFrontFace(mode); }

    @Override public void glPolygonMode(int face, int mode) { GL46.glPolygonMode(face, mode); }

    @Override public void glPolygonOffset(float factor, float units) { GL46.glPolygonOffset(factor, units); }

    @Override public void glDepthFunc(int func) { GL46.glDepthFunc(func); }

    @Override public void glDepthMask(boolean write) { GL46.glDepthMask(write); }

    @Override public void glStencilFuncSeparate(int face, int func, int ref, int mask) { GL46.glStencilFuncSeparate(face, func, ref, mask); }

    @Override public void glStencilOpSeparate(int face, int fail, int depthFail, int pass) { GL46.glStencilOpSeparate(face, fail, depthFail, pass); }

    @Override public void glStencilMaskSeparate(int face, int mask) { GL46.glStencilMaskSeparate(face, mask); }

    @Override
    public void glBlendFuncSeparatei(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (core().blendIndexed) GL46.glBlendFuncSeparatei(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
        else ARBDrawBuffersBlend.glBlendFuncSeparateiARB(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    @Override
    public void glBlendEquationSeparatei(int buffer, int modeRgb, int modeAlpha) {
        if (core().blendIndexed) GL46.glBlendEquationSeparatei(buffer, modeRgb, modeAlpha);
        else ARBDrawBuffersBlend.glBlendEquationSeparateiARB(buffer, modeRgb, modeAlpha);
    }

    @Override public void glColorMaski(int buffer, boolean r, boolean g, boolean b, boolean a) { GL46.glColorMaski(buffer, r, g, b, a); }

    @Override public void glViewportIndexedf(int index, float x, float y, float width, float height) { GL46.glViewportIndexedf(index, x, y, width, height); }

    @Override public void glDepthRangef(float near, float far) { GL46.glDepthRangef(near, far); }

    @Override public void glScissor(int x, int y, int width, int height) { GL46.glScissor(x, y, width, height); }

    @Override public void glClipControl(int origin, int depth) { GL46.glClipControl(origin, depth); }

    // ── draws and dispatches ──────────────────────────────────────────────────
    @Override
    public void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int baseInstance) {
        GL46.glDrawArraysInstancedBaseInstance(mode, first, count, instances, baseInstance);
    }

    @Override
    public void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long offset, int instances,
                                                              int baseVertex, int baseInstance) {
        GL46.glDrawElementsInstancedBaseVertexBaseInstance(mode, count, type, offset, instances, baseVertex, baseInstance);
    }

    @Override
    public void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) {
        GL46.glMultiDrawArraysIndirect(mode, offset, drawCount, stride);
    }

    @Override
    public void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) {
        GL46.glMultiDrawElementsIndirect(mode, type, offset, drawCount, stride);
    }

    @Override
    public void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount, int stride) {
        if (core().indirectCount) GL46.glMultiDrawArraysIndirectCount(mode, offset, countOffset, maxDrawCount, stride);
        else ARBIndirectParameters.glMultiDrawArraysIndirectCountARB(mode, offset, countOffset, maxDrawCount, stride);
    }

    @Override
    public void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maxDrawCount,
                                                 int stride) {
        if (core().indirectCount) GL46.glMultiDrawElementsIndirectCount(mode, type, offset, countOffset, maxDrawCount, stride);
        else ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(mode, type, offset, countOffset, maxDrawCount, stride);
    }

    @Override public void glDispatchCompute(int x, int y, int z) { GL46.glDispatchCompute(x, y, z); }

    @Override public void glDispatchComputeIndirect(long offset) { GL46.glDispatchComputeIndirect(offset); }

    @Override public void glMemoryBarrier(int barriers) { GL46.glMemoryBarrier(barriers); }

    // ── sync and queries ──────────────────────────────────────────────────────
    @Override public long glFenceSync() { return GL46.glFenceSync(GL46.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }

    @Override public int glClientWaitSync(long sync, int flags, long timeoutNanos) { return GL46.glClientWaitSync(sync, flags, timeoutNanos); }

    @Override public void glDeleteSync(long sync) { GL46.glDeleteSync(sync); }

    @Override public void glFlush() { GL46.glFlush(); }

    @Override public void glFinish() { GL46.glFinish(); }

    @Override public int glCreateQueries(int target) { return GL46.glCreateQueries(target); }

    @Override public void glQueryCounter(int query, int target) { GL46.glQueryCounter(query, target); }

    @Override public long glGetQueryObjecti64(int query, int name) { return GL46.glGetQueryObjecti64(query, name); }

    @Override public void glDeleteQueries(int query) { GL46.glDeleteQueries(query); }
}
