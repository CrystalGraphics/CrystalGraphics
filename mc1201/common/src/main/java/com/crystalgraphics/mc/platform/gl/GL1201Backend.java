package com.crystalgraphics.mc.platform.gl;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * MC 1.20.x / LWJGL 3 implementation of {@link CgGLBackend}.
 *
 * <h3>3-Tier Routing</h3>
 * <p>Each GL call is routed to the highest available MC abstraction to keep MC's internal
 * state mirror ({@code GlStateManager}) consistent with CrystalGraphics-issued GL calls:</p>
 * <ul>
 *   <li><b>Tier 1 — {@code RenderSystem}</b>: blend enable/disable/func, depth test, depth
 *       mask, depth func, color mask, viewport, stencil, polygon offset, clear</li>
 *   <li><b>Tier 2 — {@code GlStateManager}</b>: framebuffer bind/blit/gen/delete, renderbuffer
 *       ops, polygon mode, pixel store; state operations that RenderSystem doesn't fully expose</li>
 *   <li><b>Tier 3 — Raw {@code GL*C}</b>: VAOs, VBOs, shaders, texture uploads, draw calls,
 *       sync objects, SSBO/TBO/UBO; non-state operations MC doesn't track at all</li>
 * </ul>
 *
 * <p>Note: {@code GL_ALPHA_TEST} and the fixed-function matrix stack are unavailable in the
 * OpenGL core profile used by MC 1.20+. Methods covering those paths throw
 * {@link UnsupportedOperationException}. This is pre-existing {@code core/} debt — see the
 * "Known Runtime Risk" section in the mc1201 platform plan.</p>
 */
public final class GL1201Backend extends CgGLBackend {

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void initContext() {
        // No additional setup required; capabilities are probed via CgPlatform.
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available — LWJGL 3 is on the classpath in mc1201.
    }

    @Override
    public int getPriority() {
        return 50;
    }

    // -------------------------------------------------------------------------
    // FBO helpers
    // -------------------------------------------------------------------------

    /** @return {@code true} if Core GL 3.0 FBO is supported */
    private boolean coreGl30() {
        CgGLContext p = CgPlatform.capabilities();
        return p != null && p.OpenGL30();
    }

    /** @return {@code true} if ARB_framebuffer_object is supported */
    private boolean arbFbo() {
        CgGLContext p = CgPlatform.capabilities();
        return p != null && p.GL_ARB_framebuffer_object();
    }

    // -------------------------------------------------------------------------
    // Framebuffers
    //
    // GlStateManager tracks FBO state; route all FBO operations through it
    // so MC's internal FBO accounting stays consistent (Tier 2).
    // -------------------------------------------------------------------------

    @Override
    public void bindFramebuffer(int target, int fbo) {
        GlStateManager._glBindFramebuffer(target, fbo);
    }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                 int mask, int filter) {
        GlStateManager._glBlitFrameBuffer(srcX0, srcY0, srcX1, srcY1,
                dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    @Override
    public int genFramebuffers() {
        return GlStateManager.glGenFramebuffers();
    }

    @Override
    public void deleteFramebuffers(int fbo) {
        GlStateManager._glDeleteFramebuffers(fbo);
    }

    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        GlStateManager._glFramebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return GlStateManager.glCheckFramebufferStatus(target);
    }

    @Override
    public void drawBuffers(IntBuffer bufs) {
        GL20C.glDrawBuffers(bufs);
    }

    @Override
    public void bindFramebufferCompat(int fbo) {
        // This is the MC 1.20 equivalent of OpenGlHelper.func_153171_g used in mc1710.
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
    }

    // -------------------------------------------------------------------------
    // Shaders — Tier 3 (raw GL20C / GL31C / GL43C)
    // -------------------------------------------------------------------------

    @Override
    public int glCreateShader(int type) {
        return GL20C.glCreateShader(type);
    }

    @Override
    public void glShaderSource(int shader, CharSequence source) {
        GL20C.glShaderSource(shader, source);
    }

    @Override
    public void glCompileShader(int shader) {
        GL20C.glCompileShader(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GL20C.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        return GL20C.glGetShaderInfoLog(shader, maxLength);
    }

    @Override
    public void glDeleteShader(int shader) {
        GL20C.glDeleteShader(shader);
    }

    @Override
    public int glCreateProgram() {
        return GL20C.glCreateProgram();
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GL20C.glAttachShader(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        GL20C.glLinkProgram(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GL20C.glGetProgrami(program, pname);
    }

    @Override
    public String glGetProgramInfoLog(int program, int maxLength) {
        return GL20C.glGetProgramInfoLog(program, maxLength);
    }

    @Override
    public void glUseProgram(int program) {
        GL20C.glUseProgram(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        GL20C.glDeleteProgram(program);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GL20C.glGetUniformLocation(program, name);
    }

    @Override
    public void glUniform1i(int location, int v0) {
        GL20C.glUniform1i(location, v0);
    }

    @Override
    public void glUniform1f(int location, float v0) {
        GL20C.glUniform1f(location, v0);
    }

    @Override
    public void glUniform2f(int location, float v0, float v1) {
        GL20C.glUniform2f(location, v0, v1);
    }

    @Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        GL20C.glUniform3f(location, v0, v1, v2);
    }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        GL20C.glUniform4f(location, v0, v1, v2, v3);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        GL20C.glUniformMatrix4fv(location, transpose, value);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        GL20C.glBindAttribLocation(program, index, name);
    }

    @Override
    public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return GL43C.glGetProgramResourceIndex(program, programInterface, name);
    }

    @Override
    public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        GL43C.glShaderStorageBlockBinding(program, storageBlockIndex, storageBlockBinding);
    }

    @Override
    public int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return GL31C.glGetUniformBlockIndex(program, uniformBlockName);
    }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        GL31C.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    // -------------------------------------------------------------------------
    // Buffers — Tier 3 (raw GL15C / GL30C / GL31C)
    // -------------------------------------------------------------------------

    @Override
    public int glGenBuffers() {
        return GL15C.glGenBuffers();
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GL15C.glBindBuffer(target, buffer);
    }

    @Override
    public void glBufferData(int target, ByteBuffer data, int usage) {
        GL15C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, ShortBuffer data, int usage) {
        GL15C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, long size, int usage) {
        GL15C.glBufferData(target, size, usage);
    }

    @Override
    public void glBufferSubData(int target, long offset, ByteBuffer data) {
        GL15C.glBufferSubData(target, offset, data);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GL15C.glDeleteBuffers(buffer);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        GL30C.glBindBufferBase(target, index, buffer);
    }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        GL30C.glBindBufferRange(target, index, buffer, offset, size);
    }

    @Override
    public void glTexBuffer(int target, int internalFormat, int buffer) {
        GL31C.glTexBuffer(target, internalFormat, buffer);
    }

    // -------------------------------------------------------------------------
    // Vertex Array Objects — Tier 3 (raw GL30C)
    // -------------------------------------------------------------------------

    @Override
    public int glGenVertexArrays() {
        return GL30C.glGenVertexArrays();
    }

    @Override
    public void glBindVertexArray(int array) {
        GL30C.glBindVertexArray(array);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GL30C.glDeleteVertexArrays(array);
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        GL20C.glEnableVertexAttribArray(index);
    }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        GL20C.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void glVertexAttribDivisor(int index, int divisor) {
        // GL 3.3 core path; fall back to ARB_instanced_arrays on older hardware.
        if (CgPlatform.capabilities().OpenGL33()) {
            GL33C.glVertexAttribDivisor(index, divisor);
        } else {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        }
    }

    // -------------------------------------------------------------------------
    // Textures — Tier 3 for object management and image upload (raw GL*C)
    // -------------------------------------------------------------------------

    @Override
    public int glGenTextures() {
        return GL11C.glGenTextures();
    }

    @Override
    public void glBindTexture(int target, int texture) {
        if (target == GL11C.GL_TEXTURE_2D) {
            GlStateManager._bindTexture(texture);
            return;
        }
        // RenderSystem.bindTexture() only handles GL_TEXTURE_2D; CG binds multiple targets.
        GL11C.glBindTexture(target, texture);
    }

    @Override
    public void glDeleteTextures(int texture) {
        // Route through RenderSystem to notify MC's texture state tracker.
        RenderSystem.deleteTexture(texture);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat,
                              int width, int height, int border,
                              int format, int type, ByteBuffer pixels) {
        GL11C.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat,
                              int width, int height, int border,
                              int format, int type, FloatBuffer pixels) {
        GL11C.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat,
                              int width, int height, int depth, int border,
                              int format, int type, ByteBuffer pixels) {
        GL12C.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level,
                                 int xOffset, int yOffset, int width, int height,
                                 int format, int type, ByteBuffer pixels) {
        GL11C.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level,
                                 int xOffset, int yOffset, int width, int height,
                                 int format, int type, FloatBuffer pixels) {
        GL11C.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    @Override
    public void glTexSubImage3D(int target, int level,
                                 int xOffset, int yOffset, int zOffset,
                                 int width, int height, int depth,
                                 int format, int type, ByteBuffer pixels) {
        GL12C.glTexSubImage3D(target, level, xOffset, yOffset, zOffset,
                width, height, depth, format, type, pixels);
    }

    @Override
    public void glGenerateMipmap(int target) {
        GL30C.glGenerateMipmap(target);
    }

    @Override
    public void glActiveTexture(int texture) {
        RenderSystem.activeTexture(texture);
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        RenderSystem.texParameter(target, pname, param);
    }

    // -------------------------------------------------------------------------
    // Draw calls — Tier 3 (raw GL11C / GL31C)
    // -------------------------------------------------------------------------

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        GL11C.glDrawArrays(mode, first, count);
    }

    @Override
    public void glDrawElements(int mode, int count, int type, long indices) {
        GL11C.glDrawElements(mode, count, type, indices);
    }

    @Override
    public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        GL31C.glDrawArraysInstanced(mode, first, count, instanceCount);
    }

    @Override
    public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        GL31C.glDrawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    // -------------------------------------------------------------------------
    // GL state — Tier 1 (RenderSystem) and Tier 3 (raw GL) where no Tier 1/2 exists
    // -------------------------------------------------------------------------

    /** {@code GL_ALPHA_TEST} (0x0BC0) — a legacy OpenGL 1.x fixed-function constant not present
     *  in LWJGL 3's {@code GL11C}. Stored as a raw int so the guard compiles in core-profile builds. */
    private static final int GL_ALPHA_TEST_LEGACY = 0x0BC0;

    @Override
    public void glEnable(int cap) {
        if (cap == GL_ALPHA_TEST_LEGACY)         throw new UnsupportedOperationException("GL_ALPHA_TEST is unavailable in OpenGL core profile (MC 1.20+)");
        if (cap == GL11C.GL_BLEND)               { RenderSystem.enableBlend();                return; }
        if (cap == GL11C.GL_DEPTH_TEST)          { RenderSystem.enableDepthTest();            return; }
        if (cap == GL11C.GL_CULL_FACE)           { RenderSystem.enableCull();                 return; }
        if (cap == GL11C.GL_SCISSOR_TEST)        { GlStateManager._enableScissorTest();       return; }
        if (cap == GL11C.GL_POLYGON_OFFSET_FILL) { GlStateManager._enablePolygonOffset();     return; }
        GL11C.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        if (cap == GL_ALPHA_TEST_LEGACY)         throw new UnsupportedOperationException("GL_ALPHA_TEST is unavailable in OpenGL core profile (MC 1.20+)");
        if (cap == GL11C.GL_BLEND)               { RenderSystem.disableBlend();               return; }
        if (cap == GL11C.GL_DEPTH_TEST)          { RenderSystem.disableDepthTest();           return; }
        if (cap == GL11C.GL_CULL_FACE)           { RenderSystem.disableCull();                return; }
        if (cap == GL11C.GL_SCISSOR_TEST)        { RenderSystem.disableScissor();             return; }
        if (cap == GL11C.GL_POLYGON_OFFSET_FILL) { GlStateManager._disablePolygonOffset();    return; }
        GL11C.glDisable(cap);
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        RenderSystem.blendFunc(sfactor, dfactor);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        RenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void glDepthMask(boolean flag) {
        RenderSystem.depthMask(flag);
    }

    @Override
    public void glCullFace(int mode) {
        // GlStateManager only has enableCull/disableCull, not a mode setter.
        GL11C.glCullFace(mode);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        RenderSystem.viewport(x, y, width, height);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        GlStateManager._scissorBox(x, y, width, height);
    }

    @Override
    public void glLineWidth(float width) {
        GL11C.glLineWidth(width);
    }

    @Override
    public void glPolygonMode(int face, int mode) {
        GlStateManager._polygonMode(face, mode);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        RenderSystem.colorMask(red, green, blue, alpha);
    }

    @Override
    public void glStencilFunc(int func, int ref, int mask) {
        RenderSystem.stencilFunc(func, ref, mask);
    }

    @Override
    public void glStencilOp(int sfail, int dpfail, int dppass) {
        RenderSystem.stencilOp(sfail, dpfail, dppass);
    }

    @Override
    public void glAlphaFunc(int func, float ref) {
        // GL_ALPHA_TEST is a fixed-function feature removed in the OpenGL 3.x core profile.
        throw new UnsupportedOperationException(
                "Fixed-function alpha test unavailable in OpenGL core profile (MC 1.20+)");
    }

    // -------------------------------------------------------------------------
    // GL state — additional setters
    // -------------------------------------------------------------------------

    @Override
    public void glDepthFunc(int func) {
        RenderSystem.depthFunc(func);
    }

    @Override
    public void glStencilMask(int mask) {
        RenderSystem.stencilMask(mask);
    }

    @Override
    public void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        GL20C.glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    @Override
    public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) {
        // GL 3.0 per-draw-buffer color mask; no GlStateManager wrapper.
        GL30C.glColorMaski(buf, r, g, b, a);
    }

    @Override
    public void glFrontFace(int mode) {
        GL11C.glFrontFace(mode);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GlStateManager._polygonOffset(factor, units);
    }

    @Override
    public void glPointSize(float size) {
        GL11C.glPointSize(size);
    }

    @Override
    public void glDrawBuffer(int mode) {
        GL11C.glDrawBuffer(mode);
    }

    @Override
    public void glReadBuffer(int mode) {
        GL11C.glReadBuffer(mode);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GlStateManager._pixelStore(pname, param);
    }

    // -------------------------------------------------------------------------
    // GL state — queries (Tier 3 — no GlStateManager wrappers)
    // -------------------------------------------------------------------------

    @Override
    public int glGetInteger(int pname) {
        return GL11C.glGetInteger(pname);
    }

    @Override
    public void glGetInteger(int pname, IntBuffer params) {
        GL11C.glGetIntegerv(pname, params);
    }

    @Override
    public boolean glGetBoolean(int pname) {
        return GL11C.glGetBoolean(pname);
    }

    @Override
    public void glGetBoolean(int pname, ByteBuffer params) {
        GL11C.glGetBooleanv(pname, params);
    }

    @Override
    public void glGetFloat(int pname, FloatBuffer params) {
        GL11C.glGetFloatv(pname, params);
    }

    @Override
    public float glGetFloat(int pname) {
        return GL11C.glGetFloat(pname);
    }

    // -------------------------------------------------------------------------
    // Samplers (GL 3.3 / ARB_sampler_objects waterfall)
    // -------------------------------------------------------------------------

    @Override
    public void glBindSampler(int unit, int sampler) {
        // Use GL 3.3 core path if available; fall back to ARB_sampler_objects extension.
        if (CgPlatform.capabilities().OpenGL33()) {
            GL33C.glBindSampler(unit, sampler);
        } else {
            ARBSamplerObjects.glBindSampler(unit, sampler);
        }
    }

    // -------------------------------------------------------------------------
    // Buffer mapping — Tier 3 (raw GL30C / GL15C)
    // -------------------------------------------------------------------------

    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        return GL30C.glMapBufferRange(target, offset, length, access, oldBuffer);
    }

    @Override
    public boolean glUnmapBuffer(int target) {
        return GL15C.glUnmapBuffer(target);
    }

    @Override
    public void glFlushMappedBufferRange(int target, long offset, long length) {
        GL30C.glFlushMappedBufferRange(target, offset, length);
    }

    // -------------------------------------------------------------------------
    // Sync objects — Tier 3 (GL32C)
    //
    // LWJGL 3 sync: GL32C.glFenceSync() returns a long handle directly.
    // No SYNC_CACHE / GLSync wrapper needed (that was LWJGL 2 only).
    // -------------------------------------------------------------------------

    @Override
    public long glFenceSync(int condition, int flags) {
        return GL32C.glFenceSync(condition, flags);
    }

    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        return GL32C.glClientWaitSync(sync, flags, timeout);
    }

    @Override
    public void glDeleteSync(long sync) {
        GL32C.glDeleteSync(sync);
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    @Override
    public int glGetError() {
        return GL11C.glGetError();
    }

    // -------------------------------------------------------------------------
    // Context
    // -------------------------------------------------------------------------

    @Override
    public boolean isContextCurrent() {
        return GLFW.glfwGetCurrentContext() != MemoryUtil.NULL;
    }

    // -------------------------------------------------------------------------
    // Fixed-function matrix stack — unavailable in core profile
    // -------------------------------------------------------------------------

    @Override
    public void glPushMatrix() {
        throw new UnsupportedOperationException(
                "Fixed-function matrix stack unavailable in OpenGL core profile (MC 1.20+)");
    }

    @Override
    public void glPopMatrix() {
        throw new UnsupportedOperationException(
                "Fixed-function matrix stack unavailable in OpenGL core profile (MC 1.20+)");
    }

    @Override
    public void glLoadMatrix(FloatBuffer m) {
        throw new UnsupportedOperationException(
                "Fixed-function matrix stack unavailable in OpenGL core profile (MC 1.20+)");
    }

    // -------------------------------------------------------------------------
    // Framebuffers — renderbuffer operations (Tier 2 via GlStateManager)
    // -------------------------------------------------------------------------

    @Override
    public int glGenRenderbuffers() {
        return GlStateManager.glGenRenderbuffers();
    }

    @Override
    public void glDeleteRenderbuffers(int rbo) {
        GlStateManager._glDeleteRenderbuffers(rbo);
    }

    @Override
    public void glBindRenderbuffer(int target, int renderbuffer) {
        GlStateManager._glBindRenderbuffer(target, renderbuffer);
    }

    @Override
    public void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        GlStateManager._glRenderbufferStorage(target, internalFormat, width, height);
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment,
                                          int renderbufferTarget, int renderbuffer) {
        GlStateManager._glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
    }

    // -------------------------------------------------------------------------
    // Shaders — ARBShaderObjects unified-handle methods
    //
    // ARBShaderObjects used a single "object" concept for both shaders and programs.
    // GL core split these into separate shader/program APIs, but the ARB extension classes
    // still exist in LWJGL 3's org.lwjgl.opengl.ARBShaderObjects for backward-compat code paths.
    // -------------------------------------------------------------------------

    @Override
    public void glDeleteObject(int handle) {
        ARBShaderObjects.glDeleteObjectARB(handle);
    }

    @Override
    public int glGetObjectParameteri(int obj, int pname) {
        return ARBShaderObjects.glGetObjectParameteriARB(obj, pname);
    }

    @Override
    public String glGetObjectInfoLog(int obj, int maxLength) {
        return ARBShaderObjects.glGetInfoLogARB(obj, maxLength);
    }

    @Override
    public int glGetHandle(int pname) {
        return ARBShaderObjects.glGetHandleARB(pname);
    }

    // -------------------------------------------------------------------------
    // Shaders — additional methods
    // -------------------------------------------------------------------------

    @Override
    public void glDetachShader(int program, int shader) {
        GL20C.glDetachShader(program, shader);
    }

    @Override
    public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        GL20C.glGetAttachedShaders(program, count, shaders);
    }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        // LWJGL 3 separates size and type into distinct IntBuffer arguments.
        // Bridge to the LWJGL 2-style single sizeTypeBuf (sizeTypeBuf[0]=size, [1]=type).
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);
            String name = GL20C.glGetActiveUniform(program, index, maxLength, size, type);
            sizeTypeBuf.put(0, size.get(0));
            sizeTypeBuf.put(1, type.get(0));
            return name;
        }
    }

    @Override
    public void glUniform1(int location, FloatBuffer values) {
        GL20C.glUniform1fv(location, values);
    }

    @Override
    public void glUniform1(int location, IntBuffer values) {
        GL20C.glUniform1iv(location, values);
    }

    @Override
    public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        GL20C.glUniformMatrix3fv(location, transpose, value);
    }

    @Override
    public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        GL20C.glUniformMatrix4fv(location, transpose, value);
    }
}
