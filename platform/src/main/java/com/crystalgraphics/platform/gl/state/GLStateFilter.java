package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * The GL device's redundancy filter (Unreal's per-field compare in {@code Update*InOpenGLContext}): a
 * {@link CgGLDriver} over the raw one that drops a call setting what the context already has, by
 * {@link GLContextState}. Every other call goes straight through.
 *
 * <pre>{@code
 * GLStateFilter gl = new GLStateFilter(new Lwjgl3GLDriver());
 * gl.glEnable(GL_DEPTH_TEST);
 * gl.glEnable(GL_DEPTH_TEST);              // dropped
 *
 * gl.invalidate();                         // code outside the device touched GL: every field unknown
 * gl.adopt(hostState);                     // or: the host says what the context has
 * gl.restore(hostState);                   // set back every field that differs from it
 * }</pre>
 *
 * <ul>
 *   <li>Only calls made through it are seen: GL set any other way leaves it wrong until {@link #invalidate}.</li>
 *   <li>Indexed points and units past {@link GLContextState#POINTS} and {@link GLContextState#UNITS} are not kept.</li>
 * </ul>
 */
public final class GLStateFilter implements CgGLDriver {

    private final CgGLDriver raw;
    private final GLContextState now = new GLContextState();

    public GLStateFilter(CgGLDriver raw) {
        this.raw = raw;
    }

    /** What the context has, as far as this filter knows. */
    public GLContextState state() { return now; }

    /** Every field unknown: the next call setting each reaches GL. */
    public void invalidate() { now.invalidate(); }

    /** The context has {@code s}, as its owner says. */
    public void adopt(GLContextState s) { now.copyFrom(s); }

    /** Sets the context back to {@code s} wherever it differs; a field unknown in {@code s} is left as it is. */
    public void restore(GLContextState s) {
        final int u = GLContextState.UNKNOWN;
        for (int i = 0; i < GLContextState.CAPS.length; i++) {
            if (s.caps[i] == u) continue;
            if (s.caps[i] == 1) glEnable(GLContextState.CAPS[i]);
            else glDisable(GLContextState.CAPS[i]);
        }
        for (int i = 0; i < GLContextState.DRAW_BUFFERS; i++) {
            if (s.blendEnabled[i] != u) {
                if (s.blendEnabled[i] == 1) glEnablei(GL_BLEND, i);
                else glDisablei(GL_BLEND, i);
            }
            if (s.blendSrcRgb[i] != u) glBlendFuncSeparatei(i, s.blendSrcRgb[i], s.blendDstRgb[i], s.blendSrcAlpha[i],
                    s.blendDstAlpha[i]);
            if (s.blendEqRgb[i] != u) glBlendEquationSeparatei(i, s.blendEqRgb[i], s.blendEqAlpha[i]);
            int m = s.colorMask[i];
            if (m != u) glColorMaski(i, (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
        }
        if (s.program != u) glUseProgram(s.program);
        if (s.vertexArray != u) glBindVertexArray(s.vertexArray);
        if (s.drawFramebuffer != u) glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s.drawFramebuffer);
        if (s.readFramebuffer != u) glBindFramebuffer(GL_READ_FRAMEBUFFER, s.readFramebuffer);
        if (s.cullFace != u) glCullFace(s.cullFace);
        if (s.frontFace != u) glFrontFace(s.frontFace);
        if (s.polygonMode != u) glPolygonMode(GL_FRONT_AND_BACK, s.polygonMode);
        if (!Float.isNaN(s.offsetFactor)) glPolygonOffset(s.offsetFactor, s.offsetUnits);
        if (s.depthFunc != u) glDepthFunc(s.depthFunc);
        if (s.depthMask != u) glDepthMask(s.depthMask == 1);
        for (int f = 0; f < 2; f++) {
            int face = f == 0 ? GL_FRONT : GL_BACK;
            if (s.stencilFunc[f] != u) glStencilFuncSeparate(face, s.stencilFunc[f], s.stencilRef[f], s.stencilValueMask[f]);
            if (s.stencilFail[f] != u) glStencilOpSeparate(face, s.stencilFail[f], s.stencilDepthFail[f], s.stencilPass[f]);
            if (s.stencilWriteMask[f] != u) glStencilMaskSeparate(face, s.stencilWriteMask[f]);
        }
        if (!Float.isNaN(s.viewportX)) glViewportIndexedf(0, s.viewportX, s.viewportY, s.viewportW, s.viewportH);
        if (!Float.isNaN(s.depthNear)) glDepthRangef(s.depthNear, s.depthFar);
        if (s.scissorX != u) glScissor(s.scissorX, s.scissorY, s.scissorW, s.scissorH);
        if (s.clipOrigin != u) glClipControl(s.clipOrigin, s.clipDepth);
        if (s.unpackAlignment != u) glPixelStorei(GL_UNPACK_ALIGNMENT, s.unpackAlignment);
        if (s.packAlignment != u) glPixelStorei(GL_PACK_ALIGNMENT, s.packAlignment);
        for (int i = 0; i < GLContextState.POINTS; i++) {
            if (s.uniformBuffer[i] != u) point(GL_UNIFORM_BUFFER, i, s.uniformBuffer[i], s.uniformOffset[i], s.uniformSize[i]);
            if (s.storageBuffer[i] != u) point(GL_SHADER_STORAGE_BUFFER, i, s.storageBuffer[i], s.storageOffset[i],
                    s.storageSize[i]);
        }
        for (int i = 0; i < GLContextState.UNITS; i++) {
            if (s.unitTexture[i] != u) glBindTextureUnit(i, s.unitTexture[i]);
            if (s.unitSampler[i] != u) glBindSampler(i, s.unitSampler[i]);
        }
        for (int i = 0; i < GLContextState.TARGETS.length; i++) {
            if (s.targetBuffer[i] != u) glBindBuffer(GLContextState.TARGETS[i], s.targetBuffer[i]);
        }
    }

    /** A point bound whole (size 0, as {@code glBindBufferBase} reports it) or as a range. */
    private void point(int target, int index, int buffer, long offset, long size) {
        if (buffer == 0 || size <= 0) glBindBufferBase(target, index, buffer);
        else glBindBufferRange(target, index, buffer, offset, size);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        if (index < GLContextState.POINTS && (target == GL_UNIFORM_BUFFER || target == GL_SHADER_STORAGE_BUFFER)) {
            boolean uniform = target == GL_UNIFORM_BUFFER;
            int[] b = uniform ? now.uniformBuffer : now.storageBuffer;
            long[] o = uniform ? now.uniformOffset : now.storageOffset, s = uniform ? now.uniformSize : now.storageSize;
            if (b[index] == buffer && o[index] == 0 && s[index] == 0) return;
            b[index] = buffer;
            o[index] = 0;
            s[index] = 0;
        }
        raw.glBindBufferBase(target, index, buffer);
    }

    // ── objects bound ─────────────────────────────────────────────────────────

    @Override
    public void glUseProgram(int program) {
        if (now.program == program) return;
        now.program = program;
        raw.glUseProgram(program);
    }

    @Override
    public void glBindVertexArray(int vao) {
        if (now.vertexArray == vao) return;
        now.vertexArray = vao;
        raw.glBindVertexArray(vao);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        boolean draw = target != GL_READ_FRAMEBUFFER, read = target != GL_DRAW_FRAMEBUFFER;
        if ((!draw || now.drawFramebuffer == framebuffer) && (!read || now.readFramebuffer == framebuffer)) return;
        if (draw && read && now.drawFramebuffer != framebuffer && now.readFramebuffer != framebuffer) {
            raw.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        } else {
            if (draw && now.drawFramebuffer != framebuffer) raw.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            if (read && now.readFramebuffer != framebuffer) raw.glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        }
        if (draw) now.drawFramebuffer = framebuffer;
        if (read) now.readFramebuffer = framebuffer;
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        int t = GLContextState.target(target);
        if (t >= 0) {
            if (now.targetBuffer[t] == buffer) return;
            now.targetBuffer[t] = buffer;
        }
        raw.glBindBuffer(target, buffer);
    }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (index < GLContextState.POINTS && (target == GL_UNIFORM_BUFFER || target == GL_SHADER_STORAGE_BUFFER)) {
            boolean uniform = target == GL_UNIFORM_BUFFER;
            int[] b = uniform ? now.uniformBuffer : now.storageBuffer;
            long[] o = uniform ? now.uniformOffset : now.storageOffset, s = uniform ? now.uniformSize : now.storageSize;
            if (b[index] == buffer && o[index] == offset && s[index] == size) return;
            b[index] = buffer;
            o[index] = offset;
            s[index] = size;
        }
        raw.glBindBufferRange(target, index, buffer, offset, size);
    }

    @Override
    public void glBindTextureUnit(int unit, int texture) {
        if (unit < GLContextState.UNITS) {
            if (now.unitTexture[unit] == texture) return;
            now.unitTexture[unit] = texture;
        }
        raw.glBindTextureUnit(unit, texture);
    }

    @Override
    public void glBindSampler(int unit, int sampler) {
        if (unit < GLContextState.UNITS) {
            if (now.unitSampler[unit] == sampler) return;
            now.unitSampler[unit] = sampler;
        }
        raw.glBindSampler(unit, sampler);
    }

    // ── deletes: GL unbinds a deleted object from every binding of this context ─

    @Override
    public void glDeleteBuffers(int buffer) {
        for (int i = 0; i < GLContextState.POINTS; i++) {
            if (now.uniformBuffer[i] == buffer) now.uniformBuffer[i] = 0;
            if (now.storageBuffer[i] == buffer) now.storageBuffer[i] = 0;
        }
        for (int i = 0; i < now.targetBuffer.length; i++) if (now.targetBuffer[i] == buffer) now.targetBuffer[i] = 0;
        raw.glDeleteBuffers(buffer);
    }

    @Override
    public void glDeleteTextures(int texture) {
        for (int i = 0; i < GLContextState.UNITS; i++) if (now.unitTexture[i] == texture) now.unitTexture[i] = 0;
        raw.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteSamplers(int sampler) {
        for (int i = 0; i < GLContextState.UNITS; i++) if (now.unitSampler[i] == sampler) now.unitSampler[i] = 0;
        raw.glDeleteSamplers(sampler);
    }

    @Override
    public void glDeleteVertexArrays(int vao) {
        if (now.vertexArray == vao) now.vertexArray = 0;
        raw.glDeleteVertexArrays(vao);
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        if (now.drawFramebuffer == framebuffer) now.drawFramebuffer = 0;
        if (now.readFramebuffer == framebuffer) now.readFramebuffer = 0;
        raw.glDeleteFramebuffers(framebuffer);
    }

    // ── fixed-function state ──────────────────────────────────────────────────

    @Override
    public void glEnable(int cap) {
        if (cap == GL_BLEND) {
            Arrays.fill(now.blendEnabled, 1);
        } else {
            int c = GLContextState.cap(cap);
            if (c >= 0) {
                if (now.caps[c] == 1) return;
                now.caps[c] = 1;
            }
        }
        raw.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        if (cap == GL_BLEND) {
            Arrays.fill(now.blendEnabled, 0);
        } else {
            int c = GLContextState.cap(cap);
            if (c >= 0) {
                if (now.caps[c] == 0) return;
                now.caps[c] = 0;
            }
        }
        raw.glDisable(cap);
    }

    @Override
    public void glEnablei(int cap, int index) {
        if (cap == GL_BLEND && index < GLContextState.DRAW_BUFFERS) {
            if (now.blendEnabled[index] == 1) return;
            now.blendEnabled[index] = 1;
        }
        raw.glEnablei(cap, index);
    }

    @Override
    public void glDisablei(int cap, int index) {
        if (cap == GL_BLEND && index < GLContextState.DRAW_BUFFERS) {
            if (now.blendEnabled[index] == 0) return;
            now.blendEnabled[index] = 0;
        }
        raw.glDisablei(cap, index);
    }

    @Override
    public void glCullFace(int mode) {
        if (now.cullFace == mode) return;
        now.cullFace = mode;
        raw.glCullFace(mode);
    }

    @Override
    public void glFrontFace(int mode) {
        if (now.frontFace == mode) return;
        now.frontFace = mode;
        raw.glFrontFace(mode);
    }

    @Override
    public void glPolygonMode(int face, int mode) {
        if (face == GL_FRONT_AND_BACK) {
            if (now.polygonMode == mode) return;
            now.polygonMode = mode;
        } else {
            now.polygonMode = GLContextState.UNKNOWN;
        }
        raw.glPolygonMode(face, mode);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        if (now.offsetFactor == factor && now.offsetUnits == units) return;
        now.offsetFactor = factor;
        now.offsetUnits = units;
        raw.glPolygonOffset(factor, units);
    }

    @Override
    public void glDepthFunc(int func) {
        if (now.depthFunc == func) return;
        now.depthFunc = func;
        raw.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean write) {
        int m = write ? 1 : 0;
        if (now.depthMask == m) return;
        now.depthMask = m;
        raw.glDepthMask(write);
    }

    private static int faceFrom(int face) {
        return face == GL_FRONT ? 0 : face == GL_BACK ? 1 : -1;
    }

    @Override
    public void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        boolean same = true;
        for (int f = 0; f < 2; f++) {
            if (faceFrom(face) >= 0 && faceFrom(face) != f) continue;
            same &= now.stencilFunc[f] == func && now.stencilRef[f] == ref && now.stencilValueMask[f] == mask;
            now.stencilFunc[f] = func;
            now.stencilRef[f] = ref;
            now.stencilValueMask[f] = mask;
        }
        if (!same) raw.glStencilFuncSeparate(face, func, ref, mask);
    }

    @Override
    public void glStencilOpSeparate(int face, int fail, int depthFail, int pass) {
        boolean same = true;
        for (int f = 0; f < 2; f++) {
            if (faceFrom(face) >= 0 && faceFrom(face) != f) continue;
            same &= now.stencilFail[f] == fail && now.stencilDepthFail[f] == depthFail && now.stencilPass[f] == pass;
            now.stencilFail[f] = fail;
            now.stencilDepthFail[f] = depthFail;
            now.stencilPass[f] = pass;
        }
        if (!same) raw.glStencilOpSeparate(face, fail, depthFail, pass);
    }

    @Override
    public void glStencilMaskSeparate(int face, int mask) {
        boolean same = true;
        for (int f = 0; f < 2; f++) {
            if (faceFrom(face) >= 0 && faceFrom(face) != f) continue;
            same &= now.stencilWriteMask[f] == mask;
            now.stencilWriteMask[f] = mask;
        }
        if (!same) raw.glStencilMaskSeparate(face, mask);
    }

    @Override
    public void glBlendFuncSeparatei(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (buffer < GLContextState.DRAW_BUFFERS) {
            if (now.blendSrcRgb[buffer] == srcRgb && now.blendDstRgb[buffer] == dstRgb
                    && now.blendSrcAlpha[buffer] == srcAlpha && now.blendDstAlpha[buffer] == dstAlpha) return;
            now.blendSrcRgb[buffer] = srcRgb;
            now.blendDstRgb[buffer] = dstRgb;
            now.blendSrcAlpha[buffer] = srcAlpha;
            now.blendDstAlpha[buffer] = dstAlpha;
        }
        raw.glBlendFuncSeparatei(buffer, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    @Override
    public void glBlendEquationSeparatei(int buffer, int modeRgb, int modeAlpha) {
        if (buffer < GLContextState.DRAW_BUFFERS) {
            if (now.blendEqRgb[buffer] == modeRgb && now.blendEqAlpha[buffer] == modeAlpha) return;
            now.blendEqRgb[buffer] = modeRgb;
            now.blendEqAlpha[buffer] = modeAlpha;
        }
        raw.glBlendEquationSeparatei(buffer, modeRgb, modeAlpha);
    }

    @Override
    public void glColorMaski(int buffer, boolean r, boolean g, boolean b, boolean a) {
        int m = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        if (buffer < GLContextState.DRAW_BUFFERS) {
            if (now.colorMask[buffer] == m) return;
            now.colorMask[buffer] = m;
        }
        raw.glColorMaski(buffer, r, g, b, a);
    }

    @Override
    public void glViewportIndexedf(int index, float x, float y, float width, float height) {
        if (index == 0) {
            if (now.viewportX == x && now.viewportY == y && now.viewportW == width && now.viewportH == height) return;
            now.viewportX = x;
            now.viewportY = y;
            now.viewportW = width;
            now.viewportH = height;
        }
        raw.glViewportIndexedf(index, x, y, width, height);
    }

    @Override
    public void glDepthRangef(float near, float far) {
        if (now.depthNear == near && now.depthFar == far) return;
        now.depthNear = near;
        now.depthFar = far;
        raw.glDepthRangef(near, far);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        if (now.scissorX == x && now.scissorY == y && now.scissorW == width && now.scissorH == height) return;
        now.scissorX = x;
        now.scissorY = y;
        now.scissorW = width;
        now.scissorH = height;
        raw.glScissor(x, y, width, height);
    }

    @Override
    public void glClipControl(int origin, int depth) {
        if (now.clipOrigin == origin && now.clipDepth == depth) return;
        now.clipOrigin = origin;
        now.clipDepth = depth;
        raw.glClipControl(origin, depth);
    }

    @Override
    public void glPixelStorei(int name, int value) {
        if (name == GL_UNPACK_ALIGNMENT) {
            if (now.unpackAlignment == value) return;
            now.unpackAlignment = value;
        } else if (name == GL_PACK_ALIGNMENT) {
            if (now.packAlignment == value) return;
            now.packAlignment = value;
        }
        raw.glPixelStorei(name, value);
    }

    // ── everything else, straight through ────────────────────────────────────

    @Override public String glGetString(int name) { return raw.glGetString(name); }
    @Override public int glGetInteger(int name) { return raw.glGetInteger(name); }
    @Override public float glGetFloat(int name) { return raw.glGetFloat(name); }
    @Override public boolean hasExtension(String name) { return raw.hasExtension(name); }
    @Override public CgGLWorkerContext createIsolatedContext() { return raw.createIsolatedContext(); }
    @Override public boolean glIsEnabled(int cap) { return raw.glIsEnabled(cap); }
    @Override public boolean glIsEnabledi(int cap, int index) { return raw.glIsEnabledi(cap, index); }
    @Override public void glGetIntegerv(int name, int[] out) { raw.glGetIntegerv(name, out); }
    @Override public void glGetFloatv(int name, float[] out) { raw.glGetFloatv(name, out); }
    @Override public void glGetIntegeri_v(int name, int index, int[] out) { raw.glGetIntegeri_v(name, index, out); }
    @Override public long glGetInteger64i(int name, int index) { return raw.glGetInteger64i(name, index); }
    /** {@code GL_MAX_LABEL_LENGTH}, asked once; 0 until then. */
    private int maxLabel;

    /** Clipped to the driver's limit: a longer label is an error, not a shorter label. */
    @Override
    public void glObjectLabel(int identifier, int name, String label) {
        if (maxLabel == 0) maxLabel = Math.max(1, raw.glGetInteger(GL_MAX_LABEL_LENGTH));
        raw.glObjectLabel(identifier, name, label.length() < maxLabel ? label : label.substring(0, maxLabel - 1));
    }
    @Override public void glPushDebugGroup(String message) { raw.glPushDebugGroup(message); }
    @Override public void glPopDebugGroup() { raw.glPopDebugGroup(); }
    @Override public int glCreateBuffers() { return raw.glCreateBuffers(); }
    @Override public void glNamedBufferStorage(int buffer, long size, int flags) { raw.glNamedBufferStorage(buffer, size, flags); }
    @Override public ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access) { return raw.glMapNamedBufferRange(buffer, offset, length, access); }
    @Override public void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { raw.glNamedBufferSubData(buffer, offset, data); }
    @Override public void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer out) { raw.glGetNamedBufferSubData(buffer, offset, out); }
    @Override public void glCopyNamedBufferSubData(int read, int write, long readOffset, long writeOffset, long size) { raw.glCopyNamedBufferSubData(read, write, readOffset, writeOffset, size); }
    @Override public void glClearNamedBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type, ByteBuffer value) { raw.glClearNamedBufferSubData(buffer, internalFormat, offset, size, format, type, value); }
    @Override public long glGetNamedBufferParameteri64(int buffer, int name) { return raw.glGetNamedBufferParameteri64(buffer, name); }
    @Override public int glCreateTextures(int target) { return raw.glCreateTextures(target); }
    @Override public int glGenTextures() { return raw.glGenTextures(); }
    @Override public void glTextureStorage2D(int texture, int levels, int internalFormat, int width, int height) { raw.glTextureStorage2D(texture, levels, internalFormat, width, height); }
    @Override public void glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) { raw.glTextureStorage3D(texture, levels, internalFormat, width, height, depth); }
    @Override public void glTextureStorage2DMultisample(int texture, int samples, int internalFormat, int width, int height) { raw.glTextureStorage2DMultisample(texture, samples, internalFormat, width, height); }
    @Override public void glTextureBufferRange(int texture, int internalFormat, int buffer, long offset, long size) { raw.glTextureBufferRange(texture, internalFormat, buffer, offset, size); }
    @Override public void glTextureView(int texture, int target, int orig, int internalFormat, int minLevel, int levels, int minLayer, int layers) { raw.glTextureView(texture, target, orig, internalFormat, minLevel, levels, minLayer, layers); }
    @Override public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type, ByteBuffer pixels) { raw.glTextureSubImage2D(texture, level, x, y, width, height, format, type, pixels); }
    @Override public void glTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, int type, long offset) { raw.glTextureSubImage2D(texture, level, x, y, width, height, format, type, offset); }
    @Override public void glCompressedTextureSubImage2D(int texture, int level, int x, int y, int width, int height, int format, ByteBuffer data) { raw.glCompressedTextureSubImage2D(texture, level, x, y, width, height, format, data); }
    @Override public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, ByteBuffer pixels) { raw.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, pixels); }
    @Override public void glTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, long offset) { raw.glTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, type, offset); }
    @Override public void glCompressedTextureSubImage3D(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, ByteBuffer data) { raw.glCompressedTextureSubImage3D(texture, level, x, y, z, width, height, depth, format, data); }
    @Override public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, ByteBuffer out) { raw.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, out); }
    @Override public void glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, int size, long offset) { raw.glGetTextureSubImage(texture, level, x, y, z, width, height, depth, format, type, size, offset); }
    @Override public void glCopyImageSubData(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dst, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int width, int height, int depth) { raw.glCopyImageSubData(src, srcTarget, srcLevel, srcX, srcY, srcZ, dst, dstTarget, dstLevel, dstX, dstY, dstZ, width, height, depth); }
    @Override public void glGenerateTextureMipmap(int texture) { raw.glGenerateTextureMipmap(texture); }
    @Override public void glActiveTexture(int texture) { raw.glActiveTexture(texture); }
    @Override public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) { raw.glBindImageTexture(unit, texture, level, layered, layer, access, format); }
    @Override public int glCreateSamplers() { return raw.glCreateSamplers(); }
    @Override public void glSamplerParameteri(int sampler, int name, int value) { raw.glSamplerParameteri(sampler, name, value); }
    @Override public void glTextureParameteri(int texture, int name, int value) { raw.glTextureParameteri(texture, name, value); }
    @Override public void glSamplerParameterf(int sampler, int name, float value) { raw.glSamplerParameterf(sampler, name, value); }
    @Override public long glGetTextureSamplerHandleARB(int texture, int sampler) { return raw.glGetTextureSamplerHandleARB(texture, sampler); }
    @Override public void glMakeTextureHandleResidentARB(long handle) { raw.glMakeTextureHandleResidentARB(handle); }
    @Override public void glMakeTextureHandleNonResidentARB(long handle) { raw.glMakeTextureHandleNonResidentARB(handle); }
    @Override public int glCreateFramebuffers() { return raw.glCreateFramebuffers(); }
    @Override public void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) { raw.glNamedFramebufferTexture(framebuffer, attachment, texture, level); }
    @Override public void glNamedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffer) { raw.glNamedFramebufferRenderbuffer(framebuffer, attachment, renderbuffer); }
    @Override public void glNamedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) { raw.glNamedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer); }
    @Override public void glNamedFramebufferDrawBuffers(int framebuffer, int[] buffers) { raw.glNamedFramebufferDrawBuffers(framebuffer, buffers); }
    @Override public int glCheckNamedFramebufferStatus(int framebuffer, int target) { return raw.glCheckNamedFramebufferStatus(framebuffer, target); }
    @Override public void glClearNamedFramebufferfv(int framebuffer, int buffer, int drawBuffer, float r, float g, float b, float a) { raw.glClearNamedFramebufferfv(framebuffer, buffer, drawBuffer, r, g, b, a); }
    @Override public void glClearNamedFramebufferiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) { raw.glClearNamedFramebufferiv(framebuffer, buffer, drawBuffer, r, g, b, a); }
    @Override public void glClearNamedFramebufferuiv(int framebuffer, int buffer, int drawBuffer, int r, int g, int b, int a) { raw.glClearNamedFramebufferuiv(framebuffer, buffer, drawBuffer, r, g, b, a); }
    @Override public void glClearNamedFramebufferfi(int framebuffer, int buffer, int drawBuffer, float depth, int stencil) { raw.glClearNamedFramebufferfi(framebuffer, buffer, drawBuffer, depth, stencil); }
    @Override public void glInvalidateNamedFramebufferData(int framebuffer, int[] attachments, int count) { raw.glInvalidateNamedFramebufferData(framebuffer, attachments, count); }
    @Override public void glBlitNamedFramebuffer(int read, int draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) { raw.glBlitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter); }
    @Override public int glCreateShader(int type) { return raw.glCreateShader(type); }
    @Override public void glShaderSource(int shader, String source) { raw.glShaderSource(shader, source); }
    @Override public void glCompileShader(int shader) { raw.glCompileShader(shader); }
    @Override public int glGetShaderi(int shader, int name) { return raw.glGetShaderi(shader, name); }
    @Override public String glGetShaderInfoLog(int shader) { return raw.glGetShaderInfoLog(shader); }
    @Override public void glDeleteShader(int shader) { raw.glDeleteShader(shader); }
    @Override public int glCreateProgram() { return raw.glCreateProgram(); }
    @Override public void glAttachShader(int program, int shader) { raw.glAttachShader(program, shader); }
    @Override public void glDetachShader(int program, int shader) { raw.glDetachShader(program, shader); }
    @Override public void glLinkProgram(int program) { raw.glLinkProgram(program); }
    @Override public int glGetProgrami(int program, int name) { return raw.glGetProgrami(program, name); }
    @Override public int glGetTextureLevelParameteri(int texture, int level, int name) { return raw.glGetTextureLevelParameteri(texture, level, name); }
    @Override public int glGetNamedRenderbufferParameteri(int renderbuffer, int name) { return raw.glGetNamedRenderbufferParameteri(renderbuffer, name); }
    @Override public int glGetNamedFramebufferAttachmentParameteri(int framebuffer, int attachment, int name) { return raw.glGetNamedFramebufferAttachmentParameteri(framebuffer, attachment, name); }
    @Override public String glGetProgramInfoLog(int program) { return raw.glGetProgramInfoLog(program); }
    @Override public void glDeleteProgram(int program) { raw.glDeleteProgram(program); }
    @Override public int glGetProgramInterfacei(int program, int programInterface, int name) {
        return raw.glGetProgramInterfacei(program, programInterface, name);
    }
    @Override public String glGetProgramResourceName(int program, int programInterface, int index) {
        return raw.glGetProgramResourceName(program, programInterface, index);
    }
    @Override public int glGetProgramResourcei(int program, int programInterface, int index, int property) {
        return raw.glGetProgramResourcei(program, programInterface, index, property);
    }
    @Override public int glGetUniformi(int program, int location) { return raw.glGetUniformi(program, location); }
    @Override public void glProgramParameteri(int program, int name, int value) { raw.glProgramParameteri(program, name, value); }
    @Override public int glGetProgramBinary(int program, ByteBuffer out) { return raw.glGetProgramBinary(program, out); }
    @Override public void glProgramBinary(int program, int format, ByteBuffer binary) { raw.glProgramBinary(program, format, binary); }
    @Override public int glCreateVertexArrays() { return raw.glCreateVertexArrays(); }
    @Override public void glEnableVertexArrayAttrib(int vao, int index) { raw.glEnableVertexArrayAttrib(vao, index); }
    @Override public void glVertexArrayAttribFormat(int vao, int index, int size, int type, boolean normalized, int offset) { raw.glVertexArrayAttribFormat(vao, index, size, type, normalized, offset); }
    @Override public void glVertexArrayAttribIFormat(int vao, int index, int size, int type, int offset) { raw.glVertexArrayAttribIFormat(vao, index, size, type, offset); }
    @Override public void glVertexArrayAttribBinding(int vao, int index, int binding) { raw.glVertexArrayAttribBinding(vao, index, binding); }
    @Override public void glVertexArrayBindingDivisor(int vao, int binding, int divisor) { raw.glVertexArrayBindingDivisor(vao, binding, divisor); }
    @Override public void glVertexArrayVertexBuffer(int vao, int binding, int buffer, long offset, int stride) { raw.glVertexArrayVertexBuffer(vao, binding, buffer, offset, stride); }
    @Override public void glVertexArrayElementBuffer(int vao, int buffer) { raw.glVertexArrayElementBuffer(vao, buffer); }
    @Override public void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int baseInstance) { raw.glDrawArraysInstancedBaseInstance(mode, first, count, instances, baseInstance); }
    @Override public void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long offset, int instances, int baseVertex, int baseInstance) { raw.glDrawElementsInstancedBaseVertexBaseInstance(mode, count, type, offset, instances, baseVertex, baseInstance); }
    @Override public void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) { raw.glMultiDrawArraysIndirect(mode, offset, drawCount, stride); }
    @Override public void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) { raw.glMultiDrawElementsIndirect(mode, type, offset, drawCount, stride); }
    @Override public void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount, int stride) { raw.glMultiDrawArraysIndirectCount(mode, offset, countOffset, maxDrawCount, stride); }
    @Override public void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maxDrawCount, int stride) { raw.glMultiDrawElementsIndirectCount(mode, type, offset, countOffset, maxDrawCount, stride); }
    @Override public void glDispatchCompute(int x, int y, int z) { raw.glDispatchCompute(x, y, z); }
    @Override public void glDispatchComputeIndirect(long offset) { raw.glDispatchComputeIndirect(offset); }
    @Override public void glMemoryBarrier(int barriers) { raw.glMemoryBarrier(barriers); }
    @Override public long glFenceSync() { return raw.glFenceSync(); }
    @Override public int glClientWaitSync(long sync, int flags, long timeoutNanos) { return raw.glClientWaitSync(sync, flags, timeoutNanos); }
    @Override public void glDeleteSync(long sync) { raw.glDeleteSync(sync); }
    @Override public void glFlush() { raw.glFlush(); }
    @Override public void glFinish() { raw.glFinish(); }
    @Override public int glCreateQueries(int target) { return raw.glCreateQueries(target); }
    @Override public void glQueryCounter(int query, int target) { raw.glQueryCounter(query, target); }
    @Override public long glGetQueryObjecti64(int query, int name) { return raw.glGetQueryObjecti64(query, name); }
    @Override public void glDeleteQueries(int query) { raw.glDeleteQueries(query); }
}
