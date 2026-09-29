package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLBackend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * {@code CgGLBackend} over a {@link CgDevice}: the GL subset CrystalGraphics calls (spec §5.0), with GL's own
 * semantics, turned into passes, pipelines and per-draw bindings by a {@link CgTracker}. Nothing above
 * {@code CgGL} changes; a process installs one backend.
 *
 * <pre>{@code
 * CgTrackedGLBackend gl = new CgTrackedGLBackend(device, debug);
 * CgGL.init(gl);
 * CgCapabilities.init(new CgTrackedGLContext());
 * ... draw through the engine as on GL ...
 * gl.endFrame();          // once a frame, before the host presents
 * }</pre>
 *
 * <p>The census ({@code CENSUS.md} beside {@code CgGLBackend}) orders what is built: a domain not reached yet
 * throws naming the call.</p>
 */
public final class CgTrackedGLBackend extends CgGLBackend {

    private static final int GL_MAX_RENDERBUFFER_SIZE = 0x84E8, GL_MAX_COLOR_ATTACHMENTS = 0x8CDF;
    private static final int GL_MAX_UNIFORM_BLOCK_SIZE = 0x8A30, GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT = 0x8A34;
    private static final int GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT = 0x90DF, GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT = 0x919F;
    private static final int GL_MAX_TEXTURE_BUFFER_SIZE = 0x8C2B, GL_MAX_VIEWPORT_DIMS = 0x0D3A;
    private static final int GL_CONTEXT_PROFILE_MASK = 0x9126, GL_MAJOR_VERSION = 0x821B, GL_MINOR_VERSION = 0x821C;
    private static final int GL_NUM_EXTENSIONS = 0x821D, GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;

    private final CgDevice device;
    private final CgTracker tracker;
    private final TrackedGlErrors errors = new TrackedGlErrors();
    private final TrackedRenderState state;
    private final TrackedVertexArrays vaos;
    private final TrackedBuffers buffers;
    private final double[] q = new double[16];

    /** @param debug refuse what GL leaves undefined and a device cannot survive: feedback loops (decision 21) */
    public CgTrackedGLBackend(CgDevice device, boolean debug) {
        this.device = device;
        this.tracker = new CgTracker(device, debug);
        CgTarget surface = CgTarget.surface(device);
        this.state = new TrackedRenderState(tracker, errors, surface.width(), surface.height());
        this.vaos = new TrackedVertexArrays(errors);
        this.buffers = new TrackedBuffers(tracker, errors, vaos);
        vaos.buffers(buffers);
        tracker.bindTarget(surface);
    }

    public CgTracker tracker() { return tracker; }

    public CgTrackerStats stats() { return tracker.stats(); }

    /** Ends the frame: every pass ends and the device submits. The host presents after it. */
    public void endFrame() { tracker.endFrame(); }

    TrackedRenderState renderState() { return state; }

    TrackedVertexArrays vertexArrays() { return vaos; }

    TrackedBuffers bufferObjects() { return buffers; }

    private static UnsupportedOperationException notYet(String call) {
        return new UnsupportedOperationException(call + " is not on the tracked backend yet (D3.4)");
    }

    // ── lifecycle and context ──────────────────────────────────────────────────

    @Override public void initContext() {}

    @Override public boolean isAvailable() { return true; }

    @Override public int getPriority() { return 0; }

    @Override public boolean isContextCurrent() { return device.ownedByCurrentThread(); }

    @Override public boolean ownedByCurrentThread() { return device.ownedByCurrentThread(); }

    @Override public int glGetError() { return errors.take(); }

    // ── queries ────────────────────────────────────────────────────────────────

    private int query(int pname) {
        int n = state.query(pname, q);
        if (n < 0) n = buffers.query(pname, q);
        if (n < 0) n = vaos.query(pname, q);
        if (n < 0) n = limits(pname);
        if (n < 0) {
            errors.invalidEnum("glGet", pname);
            q[0] = 0;
            n = 1;
        }
        return n;
    }

    private int limits(int pname) {
        CgDeviceInfo.Limits l = device.info().limits();
        switch (pname) {
            case CgGL.GL_MAX_TEXTURE_SIZE: case GL_MAX_RENDERBUFFER_SIZE: return one(l.maxTextureSize());
            case CgGL.GL_MAX_3D_TEXTURE_SIZE:          return one(l.max3DTextureSize());
            case CgGL.GL_MAX_ARRAY_TEXTURE_LAYERS:     return one(l.maxArrayLayers());
            case CgGL.GL_MAX_TEXTURE_IMAGE_UNITS:
            case CgGL.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: return one(l.maxTextureUnits());
            case CgGL.GL_MAX_DRAW_BUFFERS: case GL_MAX_COLOR_ATTACHMENTS: return one(l.maxColorAttachments());
            case CgGL.GL_MAX_VERTEX_ATTRIBS:           return one(l.maxVertexAttributes());
            case CgGL.GL_MAX_SAMPLES: case CgGL.GL_MAX_COLOR_TEXTURE_SAMPLES:
            case CgGL.GL_MAX_DEPTH_TEXTURE_SAMPLES:    return one(l.maxSamples());
            case CgGL.GL_MAX_UNIFORM_BUFFER_BINDINGS:  return one(l.maxUniformBufferBindings());
            case CgGL.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS: return one(l.maxStorageBufferBindings());
            case GL_MAX_UNIFORM_BLOCK_SIZE:            return one(l.maxUniformBlockSize());
            case GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT:   return one(l.uniformOffsetAlignment());
            case GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT: return one(l.storageOffsetAlignment());
            case GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT:   return one(l.texelOffsetAlignment());
            case GL_MAX_TEXTURE_BUFFER_SIZE:           return one(l.maxTexelBufferElements());
            case GL_MAX_VIEWPORT_DIMS:                 q[0] = q[1] = l.maxViewportSize(); return 2;
            case GL_MAX_TEXTURE_MAX_ANISOTROPY:        return one(l.maxAnisotropy());
            case GL_CONTEXT_PROFILE_MASK:              return one(1);
            case GL_MAJOR_VERSION:                     return one(4);
            case GL_MINOR_VERSION:                     return one(4);
            case GL_NUM_EXTENSIONS:                    return one(0);
            default: return -1;
        }
    }

    private int one(double v) {
        q[0] = v;
        return 1;
    }

    @Override public int glGetInteger(int pname) { query(pname); return (int) q[0]; }

    @Override
    public void glGetInteger(int pname, IntBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (int) q[i]);
    }

    @Override public boolean glGetBoolean(int pname) { query(pname); return q[0] != 0; }

    @Override
    public void glGetBoolean(int pname, ByteBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (byte) (q[i] != 0 ? 1 : 0));
    }

    @Override public float glGetFloat(int pname) { query(pname); return (float) q[0]; }

    @Override
    public void glGetFloat(int pname, FloatBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (float) q[i]);
    }

    // ── state ──────────────────────────────────────────────────────────────────

    @Override public void glEnable(int cap) { state.enable(cap, true); }

    @Override public void glDisable(int cap) { state.enable(cap, false); }

    @Override public void glBlendFunc(int s, int d) { state.blendFunc(s, d, s, d); }

    @Override public void glBlendFuncSeparate(int sRgb, int dRgb, int sA, int dA) { state.blendFunc(sRgb, dRgb, sA, dA); }

    @Override public void glBlendEquationSeparate(int rgb, int alpha) { state.blendEquation(rgb, alpha); }

    @Override public void glDepthMask(boolean flag) { state.depthMask(flag); }

    @Override public void glDepthFunc(int func) { state.depthFunc(func); }

    @Override public void glCullFace(int mode) { state.cullFace(mode); }

    @Override public void glFrontFace(int mode) { state.frontFace(mode); }

    @Override public void glPolygonMode(int face, int mode) { state.polygonMode(face, mode); }

    @Override public void glPolygonOffset(float factor, float units) { state.polygonOffset(factor, units); }

    @Override public void glColorMask(boolean r, boolean g, boolean b, boolean a) { state.colorMask(-1, r, g, b, a); }

    @Override public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) { state.colorMask(buf, r, g, b, a); }

    @Override public void glStencilFunc(int func, int ref, int mask) { state.stencilFunc(func, ref, mask); }

    @Override public void glStencilOp(int sfail, int dpfail, int dppass) { state.stencilOp(sfail, dpfail, dppass); }

    @Override public void glStencilMask(int mask) { state.stencilMask(mask); }

    @Override public void glViewport(int x, int y, int w, int h) { state.viewport(x, y, w, h); }

    @Override public void glScissor(int x, int y, int w, int h) { state.scissor(x, y, w, h); }

    @Override public void glLineWidth(float width) { state.lineWidth(width); }

    @Override public void glPointSize(float size) { state.pointSize(size); }

    @Override public void glAlphaFunc(int func, float ref) { state.alphaFunc(func, ref); }

    @Override public void glClearColor(float r, float g, float b, float a) {
        state.clearR = r; state.clearG = g; state.clearB = b; state.clearA = a;
    }

    @Override public void glClearDepth(double depth) { state.clearDepth = depth; }

    @Override public void glClearStencil(int s) { state.clearStencil = s; }

    @Override public void glClear(int mask) { state.clear(mask); }

    // ── framebuffers ───────────────────────────────────────────────────────────

    @Override public void bindFramebuffer(int target, int fbo) { throw notYet("glBindFramebuffer"); }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
                                int mask, int filter) {
        throw notYet("glBlitFramebuffer");
    }

    @Override
    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        throw notYet("glCopyImageSubData");
    }

    @Override
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        throw notYet("glFramebufferTextureLayer");
    }

    @Override public int genFramebuffers() { throw notYet("glGenFramebuffers"); }

    @Override public void deleteFramebuffers(int fbo) { throw notYet("glDeleteFramebuffers"); }

    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        throw notYet("glFramebufferTexture2D");
    }

    @Override public int checkFramebufferStatus(int target) { throw notYet("glCheckFramebufferStatus"); }

    @Override public void drawBuffers(IntBuffer bufs) { throw notYet("glDrawBuffers"); }

    @Override
    public int getFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        throw notYet("glGetFramebufferAttachmentParameteriv");
    }

    @Override public void glDrawBuffer(int mode) { throw notYet("glDrawBuffer"); }

    @Override public void glReadBuffer(int mode) { throw notYet("glReadBuffer"); }

    @Override public int glGenRenderbuffers() { throw notYet("glGenRenderbuffers"); }

    @Override public void glDeleteRenderbuffers(int rbo) { throw notYet("glDeleteRenderbuffers"); }

    @Override public void glBindRenderbuffer(int target, int renderbuffer) { throw notYet("glBindRenderbuffer"); }

    @Override
    public void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        throw notYet("glRenderbufferStorage");
    }

    @Override
    public void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) {
        throw notYet("glRenderbufferStorageMultisample");
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) {
        throw notYet("glFramebufferRenderbuffer");
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        throw notYet("glReadPixels");
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, long packOffset) {
        throw notYet("glReadPixels");
    }

    // ── programs ───────────────────────────────────────────────────────────────

    @Override public int glCreateShader(int type) { throw notYet("glCreateShader"); }

    @Override public void glShaderSource(int shader, CharSequence source) { throw notYet("glShaderSource"); }

    @Override public void glCompileShader(int shader) { throw notYet("glCompileShader"); }

    @Override public int glGetShaderi(int shader, int pname) { throw notYet("glGetShaderi"); }

    @Override public String glGetShaderInfoLog(int shader, int maxLength) { throw notYet("glGetShaderInfoLog"); }

    @Override public void glDeleteShader(int shader) { throw notYet("glDeleteShader"); }

    @Override public int glCreateProgram() { throw notYet("glCreateProgram"); }

    @Override public void glAttachShader(int program, int shader) { throw notYet("glAttachShader"); }

    @Override public void glDetachShader(int program, int shader) { throw notYet("glDetachShader"); }

    @Override
    public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        throw notYet("glGetAttachedShaders");
    }

    @Override public void glLinkProgram(int program) { throw notYet("glLinkProgram"); }

    @Override public int glGetProgrami(int program, int pname) { throw notYet("glGetProgrami"); }

    @Override public String glGetProgramInfoLog(int program, int maxLength) { throw notYet("glGetProgramInfoLog"); }

    @Override public void glUseProgram(int program) { throw notYet("glUseProgram"); }

    @Override public void glDeleteProgram(int program) { throw notYet("glDeleteProgram"); }

    @Override public int glGetUniformLocation(int program, CharSequence name) { throw notYet("glGetUniformLocation"); }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        throw notYet("glGetActiveUniform");
    }

    @Override public void glBindAttribLocation(int program, int index, CharSequence name) { throw notYet("glBindAttribLocation"); }

    @Override
    public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        throw notYet("glGetProgramResourceIndex");
    }

    @Override
    public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        throw notYet("glShaderStorageBlockBinding");
    }

    @Override public int glGetUniformBlockIndex(int program, CharSequence name) { throw notYet("glGetUniformBlockIndex"); }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        throw notYet("glUniformBlockBinding");
    }

    @Override public void glUniform1i(int location, int v0) { throw notYet("glUniform1i"); }

    @Override public void glUniform1f(int location, float v0) { throw notYet("glUniform1f"); }

    @Override public void glUniform2f(int location, float v0, float v1) { throw notYet("glUniform2f"); }

    @Override public void glUniform3f(int location, float v0, float v1, float v2) { throw notYet("glUniform3f"); }

    @Override public void glUniform4f(int location, float v0, float v1, float v2, float v3) { throw notYet("glUniform4f"); }

    @Override public void glUniform1(int location, FloatBuffer values) { throw notYet("glUniform1fv"); }

    @Override public void glUniform1(int location, IntBuffer values) { throw notYet("glUniform1iv"); }

    @Override public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) { throw notYet("glUniformMatrix3fv"); }

    @Override public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) { throw notYet("glUniformMatrix4fv"); }

    @Override public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) { throw notYet("glUniformMatrix4fv"); }

    // ── buffers and vertex arrays ──────────────────────────────────────────────

    @Override public int glGenBuffers() { return buffers.gen(); }

    @Override public void glBindBuffer(int target, int buffer) { buffers.bind(target, buffer); }

    @Override public void glBufferData(int target, ByteBuffer data, int usage) {
        buffers.data(target, data.remaining(), data, usage);
    }

    @Override public void glBufferData(int target, ShortBuffer data, int usage) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(data.remaining() * 2).order(ByteOrder.nativeOrder());
        bytes.asShortBuffer().put(data.duplicate());
        buffers.data(target, bytes.remaining(), bytes, usage);
    }

    @Override public void glBufferData(int target, long size, int usage) { buffers.data(target, size, null, usage); }

    @Override public void glBufferSubData(int target, long offset, ByteBuffer data) { buffers.subData(target, offset, data); }

    @Override public void glDeleteBuffers(int buffer) { buffers.delete(buffer); }

    @Override public void glBindBufferBase(int target, int index, int buffer) { buffers.bindIndexed(target, index, buffer, 0, -1); }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        buffers.bindIndexed(target, index, buffer, offset, size);
    }

    @Override public void glTexBuffer(int target, int internalFormat, int buffer) { throw notYet("glTexBuffer"); }

    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        return buffers.map(target, offset, length, access);
    }

    @Override public boolean glUnmapBuffer(int target) { return buffers.unmap(target); }

    @Override public void glFlushMappedBufferRange(int target, long offset, long length) {}

    @Override public void glBufferStorage(int target, long size, int flags) { buffers.storage(target, size, flags); }

    @Override public int glGenVertexArrays() { return vaos.gen(); }

    @Override public void glBindVertexArray(int array) { vaos.bind(array); }

    @Override public void glDeleteVertexArrays(int array) { vaos.delete(array); }

    @Override public void glEnableVertexAttribArray(int index) { vaos.enable(index); }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        vaos.pointer(index, size, type, normalized, stride, pointer);
    }

    @Override public void glVertexAttribDivisor(int index, int divisor) { vaos.divisor(index, divisor); }

    // ── textures ───────────────────────────────────────────────────────────────

    @Override public int glGenTextures() { throw notYet("glGenTextures"); }

    @Override public void glBindTexture(int target, int texture) { throw notYet("glBindTexture"); }

    @Override public void glDeleteTextures(int texture) { throw notYet("glDeleteTextures"); }

    @Override public void glActiveTexture(int texture) { throw notYet("glActiveTexture"); }

    @Override public void glBindSampler(int unit, int sampler) { throw notYet("glBindSampler"); }

    @Override public void glTexParameteri(int target, int pname, int param) { throw notYet("glTexParameteri"); }

    @Override public void glGenerateMipmap(int target) { throw notYet("glGenerateMipmap"); }

    @Override public void glPixelStorei(int pname, int param) { throw notYet("glPixelStorei"); }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, ByteBuffer pixels) {
        throw notYet("glTexImage2D");
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, FloatBuffer pixels) {
        throw notYet("glTexImage2D");
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, ByteBuffer pixels) {
        throw notYet("glTexSubImage2D");
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, FloatBuffer pixels) {
        throw notYet("glTexSubImage2D");
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, ByteBuffer pixels) {
        throw notYet("glTexImage3D");
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, FloatBuffer pixels) {
        throw notYet("glTexImage3D");
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, ByteBuffer pixels) {
        throw notYet("glTexSubImage3D");
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, FloatBuffer pixels) {
        throw notYet("glTexSubImage3D");
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, ShortBuffer pixels) {
        throw notYet("glTexSubImage3D");
    }

    @Override
    public void glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height,
                                        boolean fixedSampleLocations) {
        throw notYet("glTexImage2DMultisample");
    }

    @Override public void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) { throw notYet("glGetTexImage"); }

    @Override public int importHostTexture(Object hostHandle) { throw notYet("importHostTexture"); }

    // ── draws ──────────────────────────────────────────────────────────────────

    @Override public void glDrawArrays(int mode, int first, int count) { throw notYet("glDrawArrays"); }

    @Override public void glDrawElements(int mode, int count, int type, long indices) { throw notYet("glDrawElements"); }

    @Override
    public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        throw notYet("glDrawArraysInstanced");
    }

    @Override
    public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        throw notYet("glDrawElementsInstanced");
    }

    // ── sync, timers, host sections ────────────────────────────────────────────

    @Override public long glFenceSync(int condition, int flags) { throw notYet("glFenceSync"); }

    @Override public int glClientWaitSync(long sync, int flags, long timeout) { throw notYet("glClientWaitSync"); }

    @Override public void glDeleteSync(long sync) { throw notYet("glDeleteSync"); }

    @Override public int glGenQuery() { throw notYet("glGenQuery"); }

    @Override public void glBeginTimeElapsedQuery(int query) { throw notYet("glBeginTimeElapsedQuery"); }

    @Override public void glEndTimeElapsedQuery() { throw notYet("glEndTimeElapsedQuery"); }

    @Override public boolean glIsQueryResultAvailable(int query) { throw notYet("glIsQueryResultAvailable"); }

    @Override public long glGetQueryResultNanos(int query) { throw notYet("glGetQueryResultNanos"); }

    @Override public void glDeleteQuery(int query) { throw notYet("glDeleteQuery"); }

    @Override public void hostSectionBegin() { tracker.hostSectionBegin(); }

    @Override public void hostSectionEnd() { tracker.hostSectionEnd(); }
}
