package com.crystalgraphics.lwjgl3;

import java.util.function.Supplier;
import com.crystalgraphics.platform.gl.GLDebug;
import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import org.lwjgl.opengl.ARBBindlessTexture;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRParallelShaderCompile;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * {@link CgGLDriver} on LWJGL 3: each call the GL function of its name. Written against the plain {@code GLxx} classes,
 * which every LWJGL 3 a client ships has.
 *
 * <pre>{@code
 * CgGLDriver driver = new Lwjgl3GLDriver();                                // a GLFW window's context
 * CgGLDriver sdl = new Lwjgl3GLDriver(SdlWorkerContext::isolated);         // an SDL3 window's (Minecraft 26.3)
 * }</pre>
 */
public final class Lwjgl3GLDriver implements CgGLDriver {

    private final Supplier<? extends CgGLWorkerContext> isolatedContexts;

    private static final int GL_EXTENSIONS = 0x1F03, GL_NUM_EXTENSIONS = 0x821D, GL_DEBUG_SOURCE_APPLICATION = 0x824A;

    private Set<String> extensions;

    /** On the context's thread, with it current: arms {@link GLDebug}'s error stacks where they are asked for. */
    /** A driver in a GLFW window's context: its link worker's context is a hidden GLFW window's. */
    public Lwjgl3GLDriver() {
        this(Lwjgl3WorkerContext::isolated);
    }

    /**
     * A driver in a context some other windowing library made: {@code isolatedContexts} makes the link worker's, a
     * context at the current one's version sharing none of its objects, or null where it cannot.
     */
    public Lwjgl3GLDriver(Supplier<? extends CgGLWorkerContext> isolatedContexts) {
        this.isolatedContexts = isolatedContexts;
        GlDebugStacks.installIfAsked();
    }

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
    @Override public String glGetString(int name) { return GL11.glGetString(name); }

    @Override public int glGetInteger(int name) { return GL11.glGetInteger(name); }

    @Override
    public void glMaxShaderCompilerThreads(int count) {
        GLCapabilities caps = GL.getCapabilities();
        if (caps.GL_KHR_parallel_shader_compile) {
            KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(count);
        } else if (caps.GL_ARB_parallel_shader_compile) {
            ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(count);
        }
    }

    @Override public float glGetFloat(int name) { return GL11.glGetFloat(name); }

    @Override
    public boolean hasExtension(String name) {
        if (extensions == null) {
            extensions = new HashSet<>();
            int n = GL11.glGetInteger(GL_NUM_EXTENSIONS);
            for (int i = 0; i < n; i++) extensions.add(GL30.glGetStringi(GL_EXTENSIONS, i));
        }
        return extensions.contains(name);
    }

    @Override public CgGLWorkerContext createIsolatedContext() { return isolatedContexts.get(); }

    @Override public boolean glIsEnabled(int cap) { return GL11.glIsEnabled(cap); }

    @Override public boolean glIsEnabledi(int cap, int index) { return GL30.glIsEnabledi(cap, index); }

    @Override public void glGetIntegerv(int name, int[] out) { GL11.glGetIntegerv(name, out); }

    @Override public void glGetFloatv(int name, float[] out) { GL11.glGetFloatv(name, out); }

    @Override public void glGetIntegeri_v(int name, int index, int[] out) { GL30.glGetIntegeri_v(name, index, out); }

    @Override public long glGetInteger64i(int name, int index) { return GL32.glGetInteger64i(name, index); }

    @Override public void glObjectLabel(int identifier, int name, String label) { GL43.glObjectLabel(identifier, name, label); }

    @Override public void glPushDebugGroup(String message) { GL43.glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, message); }

    @Override public void glPopDebugGroup() { GL43.glPopDebugGroup(); }

    // ── buffers ───────────────────────────────────────────────────────────────
    @Override public int glCreateBuffers() { return GL45.glCreateBuffers(); }

    @Override public void glNamedBufferStorage(int buffer, long size, int flags) { GL45.glNamedBufferStorage(buffer, size, flags); }

    @Override
    public ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access) {
        return GL45.glMapNamedBufferRange(buffer, offset, length, access);
    }

    @Override public void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { GL45.glNamedBufferSubData(buffer, offset, data); }

    @Override public void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer out) { GL45.glGetNamedBufferSubData(buffer, offset, out); }

    @Override
    public void glCopyNamedBufferSubData(int read, int write, long readOffset, long writeOffset, long size) {
        GL45.glCopyNamedBufferSubData(read, write, readOffset, writeOffset, size);
    }

    @Override
    public void glClearNamedBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type,
                                          ByteBuffer value) {
        GL45.glClearNamedBufferSubData(buffer, internalFormat, offset, size, format, type, value);
    }

    @Override public void glBindBufferBase(int target, int index, int buffer) { GL30.glBindBufferBase(target, index, buffer); }

    @Override public long glGetNamedBufferParameteri64(int buffer, int name) { return GL45.glGetNamedBufferParameteri64(buffer, name); }

    @Override public void glDeleteBuffers(int buffer) { GL15.glDeleteBuffers(buffer); }

    @Override public void glBindBuffer(int target, int buffer) { GL15.glBindBuffer(target, buffer); }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        GL30.glBindBufferRange(target, index, buffer, offset, size);
    }

    // ── textures and samplers ─────────────────────────────────────────────────
    @Override public int glCreateTextures(int target) { return GL45.glCreateTextures(target); }

    @Override public int glGenTextures() { return GL11.glGenTextures(); }

    @Override
    public void glTextureStorage2D(int texture, int levels, int internalFormat, int width, int height) {
        GL45.glTextureStorage2D(texture, levels, internalFormat, width, height);
    }

    @Override
    public void glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) {
        GL45.glTextureStorage3D(texture, levels, internalFormat, width, height, depth);
    }

    @Override
    public void glTextureStorage2DMultisample(int texture, int samples, int internalFormat, int width, int height) {
        GL45.glTextureStorage2DMultisample(texture, samples, internalFormat, width, height, true);
    }

    @Override
    public void glTextureBufferRange(int texture, int internalFormat, int buffer, long offset, long size) {
        GL45.glTextureBufferRange(texture, internalFormat, buffer, offset, size);
    }

    @Override
    public void glTextureView(int texture, int target, int orig, int internalFormat, int minLevel, int levels, int minLayer,
                              int layers) {
        GL43.glTextureView(texture, target, orig, internalFormat, minLevel, levels, minLayer, layers);
    }

    @Override
    public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                                    ByteBuffer pixels) {
        GL45.glTextureSubImage2D(texture, level, x, y, width, height, format, type, pixels);
    }

    @Override
    public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type,
                                    long offset) {
        GL45.glTextureSubImage2D(texture, level, x, y, width, height, format, type, offset);
    }

    @Override
    public void glCompressedTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format,
                                              ByteBuffer data) {
        GL45.glCompressedTextureSubImage2D(texture, level, x, y, width, height, format, data);
    }

    @Override
    public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                    int format, int type, ByteBuffer pixels) {
        GL45.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, pixels);
    }

    @Override
    public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                    int format, int type, long offset) {
        GL45.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, offset);
    }

    @Override
    public void glCompressedTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height,
                                              int depth, int format, ByteBuffer data) {
        GL45.glCompressedTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, data);
    }

    @Override
    public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                     int format, int type, ByteBuffer out) {
        GL45.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, out);
    }

    @Override
    public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth,
                                     int format, int type, int size, long offset) {
        GL45.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, size, offset);
    }

    @Override
    public void glCopyImageSubData(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dst,
                                   int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int width, int height,
                                   int depth) {
        GL43.glCopyImageSubData(src, srcTarget, srcLevel, srcX, srcY, srcZ, dst, dstTarget, dstLevel, dstX, dstY, dstZ,
                width, height, depth);
    }

    @Override public void glPixelStorei(int name, int value) { GL11.glPixelStorei(name, value); }

    @Override public void glGenerateTextureMipmap(int texture) { GL45.glGenerateTextureMipmap(texture); }

    @Override public void glDeleteTextures(int texture) { GL11.glDeleteTextures(texture); }

    @Override public void glBindTextureUnit(int unit, int texture) { GL45.glBindTextureUnit(unit, texture); }

    @Override public void glActiveTexture(int texture) { GL13.glActiveTexture(texture); }

    @Override
    public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        GL42.glBindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    @Override public int glCreateSamplers() { return GL45.glCreateSamplers(); }

    @Override public void glSamplerParameteri(int sampler, int name, int value) { GL33.glSamplerParameteri(sampler, name, value); }
    @Override public void glTextureParameteri(int texture, int name, int value) { GL45.glTextureParameteri(texture, name, value); }

    @Override public void glSamplerParameterf(int sampler, int name, float value) { GL33.glSamplerParameterf(sampler, name, value); }

    @Override public void glDeleteSamplers(int sampler) { GL33.glDeleteSamplers(sampler); }

    @Override public void glBindSampler(int unit, int sampler) { GL33.glBindSampler(unit, sampler); }

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
    @Override public int glCreateFramebuffers() { return GL45.glCreateFramebuffers(); }

    @Override
    public void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        GL45.glNamedFramebufferTexture(framebuffer, attachment, texture, level);
    }

    @Override
    public void glNamedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffer) {
        GL45.glNamedFramebufferRenderbuffer(framebuffer, attachment, 0x8D41 /* GL_RENDERBUFFER */, renderbuffer);
    }

    @Override
    public void glNamedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        GL45.glNamedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer);
    }

    @Override public void glNamedFramebufferDrawBuffers(int framebuffer, int[] buffers) { GL45.glNamedFramebufferDrawBuffers(framebuffer, buffers); }

    @Override public int glCheckNamedFramebufferStatus(int framebuffer, int target) { return GL45.glCheckNamedFramebufferStatus(framebuffer, target); }

    @Override public void glDeleteFramebuffers(int framebuffer) { GL30.glDeleteFramebuffers(framebuffer); }

    @Override public void glBindFramebuffer(int target, int framebuffer) { GL30.glBindFramebuffer(target, framebuffer); }

    @Override
    public void glClearNamedFramebufferfv(int framebuffer, int buffer, int drawBuffer, float r, float g, float b, float a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45.glClearNamedFramebufferfv(framebuffer, buffer, drawBuffer, stack.floats(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45.glClearNamedFramebufferiv(framebuffer, buffer, drawBuffer, stack.ints(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferuiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45.glClearNamedFramebufferuiv(framebuffer, buffer, drawBuffer, stack.ints(r, g, b, a));
        }
    }

    @Override
    public void glClearNamedFramebufferfi(int framebuffer, int buffer, int drawBuffer, float depth, int stencil) {
        GL45.glClearNamedFramebufferfi(framebuffer, buffer, drawBuffer, depth, stencil);
    }

    @Override
    public void glInvalidateNamedFramebufferData(int framebuffer, int[] attachments, int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45.glInvalidateNamedFramebufferData(framebuffer, stack.ints(attachments).limit(count));
        }
    }

    @Override
    public void glBlitNamedFramebuffer(int read, int draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1,
                                       int dy1, int mask, int filter) {
        GL45.glBlitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    // ── programs ──────────────────────────────────────────────────────────────
    @Override public int glCreateShader(int type) { return GL20.glCreateShader(type); }

    @Override public void glShaderSource(int shader, String source) { GL20.glShaderSource(shader, source); }

    @Override public void glCompileShader(int shader) { GL20.glCompileShader(shader); }

    @Override public int glGetShaderi(int shader, int name) { return GL20.glGetShaderi(shader, name); }

    @Override public String glGetShaderInfoLog(int shader) { return GL20.glGetShaderInfoLog(shader); }

    @Override public void glDeleteShader(int shader) { GL20.glDeleteShader(shader); }

    @Override public int glCreateProgram() { return GL20.glCreateProgram(); }

    @Override public void glAttachShader(int program, int shader) { GL20.glAttachShader(program, shader); }

    @Override public void glDetachShader(int program, int shader) { GL20.glDetachShader(program, shader); }

    @Override public void glLinkProgram(int program) { GL20.glLinkProgram(program); }

    @Override public int glGetProgrami(int program, int name) { return GL20.glGetProgrami(program, name); }
    @Override public int glGetTextureLevelParameteri(int texture, int level, int name) {
        return GL45.glGetTextureLevelParameteri(texture, level, name);
    }

    @Override public int glGetNamedRenderbufferParameteri(int renderbuffer, int name) {
        return GL45.glGetNamedRenderbufferParameteri(renderbuffer, name);
    }

    @Override public int glGetNamedFramebufferAttachmentParameteri(int framebuffer, int attachment, int name) {
        return GL45.glGetNamedFramebufferAttachmentParameteri(framebuffer, attachment, name);
    }

    @Override public String glGetProgramInfoLog(int program) { return GL20.glGetProgramInfoLog(program); }

    @Override public void glDeleteProgram(int program) { GL20.glDeleteProgram(program); }

    @Override
    public int glGetProgramInterfacei(int program, int programInterface, int name) {
        return GL43.glGetProgramInterfacei(program, programInterface, name);
    }

    @Override
    public String glGetProgramResourceName(int program, int programInterface, int index) {
        return GL43.glGetProgramResourceName(program, programInterface, index);
    }

    @Override
    public int glGetProgramResourcei(int program, int programInterface, int index, int property) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            GL43.glGetProgramResourceiv(program, programInterface, index, stack.ints(property), null, value);
            return value.get(0);
        }
    }

    @Override public int glGetUniformi(int program, int location) { return GL20.glGetUniformi(program, location); }

    @Override public void glUseProgram(int program) { GL20.glUseProgram(program); }

    @Override public void glProgramParameteri(int program, int name, int value) { GL41.glProgramParameteri(program, name, value); }

    @Override
    public int glGetProgramBinary(int program, ByteBuffer out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer format = stack.mallocInt(1);
            GL41.glGetProgramBinary(program, null, format, out);
            return format.get(0);
        }
    }

    @Override public void glProgramBinary(int program, int format, ByteBuffer binary) { GL41.glProgramBinary(program, format, binary); }

    // ── vertex arrays ─────────────────────────────────────────────────────────
    @Override public int glCreateVertexArrays() { return GL45.glCreateVertexArrays(); }

    @Override public void glEnableVertexArrayAttrib(int vao, int index) { GL45.glEnableVertexArrayAttrib(vao, index); }

    @Override
    public void glVertexArrayAttribFormat(int vao, int index, int size, int type, boolean normalized, int offset) {
        GL45.glVertexArrayAttribFormat(vao, index, size, type, normalized, offset);
    }

    @Override
    public void glVertexArrayAttribIFormat(int vao, int index, int size, int type, int offset) {
        GL45.glVertexArrayAttribIFormat(vao, index, size, type, offset);
    }

    @Override public void glVertexArrayAttribBinding(int vao, int index, int binding) { GL45.glVertexArrayAttribBinding(vao, index, binding); }

    @Override public void glVertexArrayBindingDivisor(int vao, int binding, int divisor) { GL45.glVertexArrayBindingDivisor(vao, binding, divisor); }

    @Override
    public void glVertexArrayVertexBuffer(int vao, int binding, int buffer, long offset, int stride) {
        GL45.glVertexArrayVertexBuffer(vao, binding, buffer, offset, stride);
    }

    @Override public void glVertexArrayElementBuffer(int vao, int buffer) { GL45.glVertexArrayElementBuffer(vao, buffer); }

    @Override public void glDeleteVertexArrays(int vao) { GL30.glDeleteVertexArrays(vao); }

    @Override public void glBindVertexArray(int vao) { GL30.glBindVertexArray(vao); }

    // ── fixed-function state ──────────────────────────────────────────────────
    @Override public void glEnable(int cap) { GL11.glEnable(cap); }

    @Override public void glDisable(int cap) { GL11.glDisable(cap); }

    @Override public void glEnablei(int cap, int index) { GL30.glEnablei(cap, index); }

    @Override public void glDisablei(int cap, int index) { GL30.glDisablei(cap, index); }

    @Override public void glCullFace(int mode) { GL11.glCullFace(mode); }

    @Override public void glFrontFace(int mode) { GL11.glFrontFace(mode); }

    @Override public void glPolygonMode(int face, int mode) { GL11.glPolygonMode(face, mode); }

    @Override public void glPolygonOffset(float factor, float units) { GL11.glPolygonOffset(factor, units); }

    @Override public void glDepthFunc(int func) { GL11.glDepthFunc(func); }

    @Override public void glDepthMask(boolean write) { GL11.glDepthMask(write); }

    @Override public void glStencilFuncSeparate(int face, int func, int ref, int mask) { GL20.glStencilFuncSeparate(face, func, ref, mask); }

    @Override public void glStencilOpSeparate(int face, int fail, int depthFail, int pass) { GL20.glStencilOpSeparate(face, fail, depthFail, pass); }

    @Override public void glStencilMaskSeparate(int face, int mask) { GL20.glStencilMaskSeparate(face, mask); }

    @Override
    public void glBlendFuncSeparatei(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (core().blendIndexed) GL40.glBlendFuncSeparatei(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
        else ARBDrawBuffersBlend.glBlendFuncSeparateiARB(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    @Override
    public void glBlendEquationSeparatei(int buffer, int modeRgb, int modeAlpha) {
        if (core().blendIndexed) GL40.glBlendEquationSeparatei(buffer, modeRgb, modeAlpha);
        else ARBDrawBuffersBlend.glBlendEquationSeparateiARB(buffer, modeRgb, modeAlpha);
    }

    @Override public void glColorMaski(int buffer, boolean r, boolean g, boolean b, boolean a) { GL30.glColorMaski(buffer, r, g, b, a); }

    @Override public void glViewportIndexedf(int index, float x, float y, float width, float height) { GL41.glViewportIndexedf(index, x, y, width, height); }

    @Override public void glDepthRangef(float near, float far) { GL41.glDepthRangef(near, far); }

    @Override public void glScissor(int x, int y, int width, int height) { GL11.glScissor(x, y, width, height); }

    @Override public void glClipControl(int origin, int depth) { GL45.glClipControl(origin, depth); }

    // ── draws and dispatches ──────────────────────────────────────────────────
    @Override
    public void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int baseInstance) {
        GL42.glDrawArraysInstancedBaseInstance(mode, first, count, instances, baseInstance);
    }

    @Override
    public void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long offset, int instances,
                                                              int baseVertex, int baseInstance) {
        GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, count, type, offset, instances, baseVertex, baseInstance);
    }

    @Override
    public void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) {
        GL43.glMultiDrawArraysIndirect(mode, offset, drawCount, stride);
    }

    @Override
    public void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) {
        GL43.glMultiDrawElementsIndirect(mode, type, offset, drawCount, stride);
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

    @Override public void glDispatchCompute(int x, int y, int z) { GL43.glDispatchCompute(x, y, z); }

    @Override public void glDispatchComputeIndirect(long offset) { GL43.glDispatchComputeIndirect(offset); }

    @Override public void glMemoryBarrier(int barriers) { GL42.glMemoryBarrier(barriers); }

    // ── sync and queries ──────────────────────────────────────────────────────
    @Override public long glFenceSync() { return GL32.glFenceSync(GL46.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }

    @Override public int glClientWaitSync(long sync, int flags, long timeoutNanos) { return GL32.glClientWaitSync(sync, flags, timeoutNanos); }

    @Override public void glDeleteSync(long sync) { GL32.glDeleteSync(sync); }

    @Override public void glFlush() { GL11.glFlush(); }

    @Override public void glFinish() { GL11.glFinish(); }

    @Override public int glCreateQueries(int target) { return GL45.glCreateQueries(target); }

    @Override public void glQueryCounter(int query, int target) { GL33.glQueryCounter(query, target); }

    @Override public long glGetQueryObjecti64(int query, int name) { return GL33.glGetQueryObjecti64(query, name); }

    @Override public void glDeleteQueries(int query) { GL15.glDeleteQueries(query); }
}
