package com.crystalgraphics.mc.platform.gl;

import com.crystalgraphics.platform.gl.CgCapabilityProbe;
import com.crystalgraphics.platform.gl.CgGlDispatch;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.*;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC 1.7.10 / LWJGL 2.9 implementation of {@link CgGlDispatch}.
 *
 * <p>All raw OpenGL calls delegate to the appropriate LWJGL 2 static methods.
 * The FBO waterfall follows Core GL30 &gt; ARB &gt; EXT, determined at call time by
 * reading from {@link CgPlatform#capabilities()} ()}.</p>
 *
 * <p>{@link #bindFramebufferCompat(int)} routes through
 * {@code OpenGlHelper.func_153171_g} so that Minecraft's own FBO tracking
 * remains consistent with CrystalGraphics-issued binds.</p>
 */
public final class Lwjgl2GlDispatch extends CgGlDispatch {

    /** Maps GL sync object handles (long) to LWJGL2 GLSync wrappers. */
    private static final ConcurrentHashMap<Long, GLSync> SYNC_CACHE = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void initContext() {
        // No additional setup required; capabilities are probed via CgPlatform.
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available — LWJGL 2 is on the classpath in mc1710.
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
        CgCapabilityProbe p = CgPlatform.capabilities();
        return p != null && p.isCoreFboSupported();
    }

    /** @return {@code true} if ARB_framebuffer_object is supported */
    private boolean arbFbo() {
        CgCapabilityProbe p = CgPlatform.capabilities();
        return p != null && p.isArbFboSupported();
    }

    // -------------------------------------------------------------------------
    // Framebuffers — Core / ARB / EXT waterfall
    // -------------------------------------------------------------------------

    @Override
    public void bindFramebuffer(int target, int fbo) {
        if (coreGl30()) {
            GL30.glBindFramebuffer(target, fbo);
        } else if (arbFbo()) {
            ARBFramebufferObject.glBindFramebuffer(target, fbo);
        } else {
            EXTFramebufferObject.glBindFramebufferEXT(target, fbo);
        }
    }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                 int mask, int filter) {
        if (coreGl30()) {
            GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } else if (arbFbo()) {
            ARBFramebufferObject.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } else {
            EXTFramebufferBlit.glBlitFramebufferEXT(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
    }

    @Override
    public int genFramebuffers() {
        if (coreGl30()) {
            return GL30.glGenFramebuffers();
        } else if (arbFbo()) {
            return ARBFramebufferObject.glGenFramebuffers();
        } else {
            return EXTFramebufferObject.glGenFramebuffersEXT();
        }
    }

    @Override
    public void deleteFramebuffers(int fbo) {
        if (coreGl30()) {
            GL30.glDeleteFramebuffers(fbo);
        } else if (arbFbo()) {
            ARBFramebufferObject.glDeleteFramebuffers(fbo);
        } else {
            EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
        }
    }

    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        if (coreGl30()) {
            GL30.glFramebufferTexture2D(target, attachment, texTarget, texture, level);
        } else if (arbFbo()) {
            ARBFramebufferObject.glFramebufferTexture2D(target, attachment, texTarget, texture, level);
        } else {
            EXTFramebufferObject.glFramebufferTexture2DEXT(target, attachment, texTarget, texture, level);
        }
    }

    @Override
    public int checkFramebufferStatus(int target) {
        if (coreGl30()) {
            return GL30.glCheckFramebufferStatus(target);
        } else if (arbFbo()) {
            return ARBFramebufferObject.glCheckFramebufferStatus(target);
        } else {
            return EXTFramebufferObject.glCheckFramebufferStatusEXT(target);
        }
    }

    @Override
    public void drawBuffers(IntBuffer bufs) {
        GL20.glDrawBuffers(bufs);
    }

    @Override
    public void bindFramebufferCompat(int fbo) {
        OpenGlHelper.func_153171_g(GL30.GL_FRAMEBUFFER, fbo);
    }

    // -------------------------------------------------------------------------
    // Shaders
    // -------------------------------------------------------------------------

    @Override
    public int glCreateShader(int type) {
        return GL20.glCreateShader(type);
    }

    @Override
    public void glShaderSource(int shader, CharSequence source) {
        GL20.glShaderSource(shader, source);
    }

    @Override
    public void glCompileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GL20.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        return GL20.glGetShaderInfoLog(shader, maxLength);
    }

    @Override
    public void glDeleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    @Override
    public int glCreateProgram() {
        return GL20.glCreateProgram();
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GL20.glGetProgrami(program, pname);
    }

    @Override
    public String glGetProgramInfoLog(int program, int maxLength) {
        return GL20.glGetProgramInfoLog(program, maxLength);
    }

    @Override
    public void glUseProgram(int program) {
        GL20.glUseProgram(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GL20.glGetUniformLocation(program, name);
    }

    @Override
    public void glUniform1i(int location, int v0) {
        GL20.glUniform1i(location, v0);
    }

    @Override
    public void glUniform1f(int location, float v0) {
        GL20.glUniform1f(location, v0);
    }

    @Override
    public void glUniform2f(int location, float v0, float v1) {
        GL20.glUniform2f(location, v0, v1);
    }

    @Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        GL20.glUniform3f(location, v0, v1, v2);
    }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        GL20.glUniform4f(location, v0, v1, v2, v3);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        GL20.glUniformMatrix4(location, transpose, value);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        GL20.glBindAttribLocation(program, index, name);
    }

    @Override
    public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return GL43.glGetProgramResourceIndex(program, programInterface, name);
    }

    @Override
    public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        GL43.glShaderStorageBlockBinding(program, storageBlockIndex, storageBlockBinding);
    }

    @Override
    public int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return GL31.glGetUniformBlockIndex(program, uniformBlockName);
    }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        GL31.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    // -------------------------------------------------------------------------
    // Buffers
    // -------------------------------------------------------------------------

    @Override
    public int glGenBuffers() {
        return GL15.glGenBuffers();
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GL15.glBindBuffer(target, buffer);
    }

    @Override
    public void glBufferData(int target, ByteBuffer data, int usage) {
        GL15.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, long size, int usage) {
        GL15.glBufferData(target, size, usage);
    }

    @Override
    public void glBufferSubData(int target, long offset, ByteBuffer data) {
        GL15.glBufferSubData(target, offset, data);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        GL30.glBindBufferBase(target, index, buffer);
    }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        GL30.glBindBufferRange(target, index, buffer, offset, size);
    }

    @Override
    public void glTexBuffer(int target, int internalFormat, int buffer) {
        GL31.glTexBuffer(target, internalFormat, buffer);
    }

    // -------------------------------------------------------------------------
    // Vertex Array Objects
    // -------------------------------------------------------------------------

    @Override
    public int glGenVertexArrays() {
        return GL30.glGenVertexArrays();
    }

    @Override
    public void glBindVertexArray(int array) {
        GL30.glBindVertexArray(array);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GL30.glDeleteVertexArrays(array);
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void glVertexAttribDivisor(int index, int divisor) {
        if (GLContext.getCapabilities().OpenGL33) {
            GL33.glVertexAttribDivisor(index, divisor);
        } else {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        }
    }

    // -------------------------------------------------------------------------
    // Textures
    // -------------------------------------------------------------------------

    @Override
    public int glGenTextures() {
        return GL11.glGenTextures();
    }

    @Override
    public void glBindTexture(int target, int texture) {
        GL11.glBindTexture(target, texture);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat,
                              int width, int height, int border,
                              int format, int type, ByteBuffer pixels) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat,
                              int width, int height, int depth, int border,
                              int format, int type, ByteBuffer pixels) {
        GL12.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level,
                                 int xOffset, int yOffset, int width, int height,
                                 int format, int type, ByteBuffer pixels) {
        GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    @Override
    public void glGenerateMipmap(int target) {
        GL30.glGenerateMipmap(target);
    }

    @Override
    public void glActiveTexture(int texture) {
        GL13.glActiveTexture(texture);
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
    }

    // -------------------------------------------------------------------------
    // Draw calls
    // -------------------------------------------------------------------------

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        GL11.glDrawArrays(mode, first, count);
    }

    @Override
    public void glDrawElements(int mode, int count, int type, long indices) {
        GL11.glDrawElements(mode, count, type, indices);
    }

    @Override
    public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        GL31.glDrawArraysInstanced(mode, first, count, instanceCount);
    }

    @Override
    public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        GL31.glDrawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    // -------------------------------------------------------------------------
    // GL state
    // -------------------------------------------------------------------------

    @Override
    public void glEnable(int cap) {
        GL11.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        GL11.glDisable(cap);
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    @Override
    public void glCullFace(int mode) {
        GL11.glCullFace(mode);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        GL11.glScissor(x, y, width, height);
    }

    @Override
    public void glLineWidth(float width) {
        GL11.glLineWidth(width);
    }

    @Override
    public void glPolygonMode(int face, int mode) {
        GL11.glPolygonMode(face, mode);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GL11.glColorMask(red, green, blue, alpha);
    }

    @Override
    public void glStencilFunc(int func, int ref, int mask) {
        GL11.glStencilFunc(func, ref, mask);
    }

    @Override
    public void glStencilOp(int sfail, int dpfail, int dppass) {
        GL11.glStencilOp(sfail, dpfail, dppass);
    }

    @Override
    public void glAlphaFunc(int func, float ref) {
        GL11.glAlphaFunc(func, ref);
    }

    // -------------------------------------------------------------------------
    // GL state — additional setters
    // -------------------------------------------------------------------------

    @Override
    public void glDepthFunc(int func) {
        GL11.glDepthFunc(func);
    }

    @Override
    public void glStencilMask(int mask) {
        GL11.glStencilMask(mask);
    }

    @Override
    public void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        GL20.glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    @Override
    public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) {
        GL30.glColorMaski(buf, r, g, b, a);
    }

    @Override
    public void glFrontFace(int mode) {
        GL11.glFrontFace(mode);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GL11.glPolygonOffset(factor, units);
    }

    @Override
    public void glPointSize(float size) {
        GL11.glPointSize(size);
    }

    @Override
    public void glDrawBuffer(int mode) {
        GL11.glDrawBuffer(mode);
    }

    @Override
    public void glReadBuffer(int mode) {
        GL11.glReadBuffer(mode);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GL11.glPixelStorei(pname, param);
    }

    // -------------------------------------------------------------------------
    // GL state — queries
    // -------------------------------------------------------------------------

    @Override
    public int glGetInteger(int pname) {
        return GL11.glGetInteger(pname);
    }

    @Override
    public boolean glGetBoolean(int pname) {
        return GL11.glGetBoolean(pname);
    }

    @Override
    public void glGetFloat(int pname, FloatBuffer params) {
        GL11.glGetFloat(pname, params);
    }

    // -------------------------------------------------------------------------
    // Samplers
    // -------------------------------------------------------------------------

    @Override
    public void glBindSampler(int unit, int sampler) {
        ARBSamplerObjects.glBindSampler(unit, sampler);
    }

    // -------------------------------------------------------------------------
    // Buffer mapping
    // -------------------------------------------------------------------------

    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        return GL30.glMapBufferRange(target, offset, length, access, oldBuffer);
    }

    @Override
    public boolean glUnmapBuffer(int target) {
        return GL15.glUnmapBuffer(target);
    }

    @Override
    public void glFlushMappedBufferRange(int target, long offset, long length) {
        GL30.glFlushMappedBufferRange(target, offset, length);
    }

    // -------------------------------------------------------------------------
    // Sync objects (ARBSync / GL 3.2)
    // -------------------------------------------------------------------------

    @Override
    public long glFenceSync(int condition, int flags) {
        GLSync sync = ARBSync.glFenceSync(condition, flags);
        if (sync == null) return 0L;
        long handle = sync.getPointer();
        SYNC_CACHE.put(handle, sync);
        return handle;
    }

    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        GLSync glSync = SYNC_CACHE.get(sync);
        if (glSync == null) return ARBSync.GL_WAIT_FAILED;
        return ARBSync.glClientWaitSync(glSync, flags, timeout);
    }

    @Override
    public void glDeleteSync(long sync) {
        GLSync glSync = SYNC_CACHE.remove(sync);
        if (glSync != null) ARBSync.glDeleteSync(glSync);
    }

    // -------------------------------------------------------------------------
    // Texture 3D sub-image
    // -------------------------------------------------------------------------

    @Override
    public void glTexSubImage3D(int target, int level,
                                 int xOffset, int yOffset, int zOffset,
                                 int width, int height, int depth,
                                 int format, int type, ByteBuffer pixels) {
        GL12.glTexSubImage3D(target, level, xOffset, yOffset, zOffset,
                width, height, depth, format, type, pixels);
    }

    // -------------------------------------------------------------------------
    // Context
    // -------------------------------------------------------------------------

    @Override
    public boolean isContextCurrent() {
        try {
            return Display.isCurrent();
        } catch (org.lwjgl.LWJGLException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Framebuffers — renderbuffer operations (Core / ARB / EXT waterfall)
    // -------------------------------------------------------------------------

    @Override
    public int genRenderbuffers() {
        if (coreGl30()) {
            return GL30.glGenRenderbuffers();
        } else if (arbFbo()) {
            return ARBFramebufferObject.glGenRenderbuffers();
        } else {
            return EXTFramebufferObject.glGenRenderbuffersEXT();
        }
    }

    @Override
    public void deleteRenderbuffers(int rbo) {
        if (coreGl30()) {
            GL30.glDeleteRenderbuffers(rbo);
        } else if (arbFbo()) {
            ARBFramebufferObject.glDeleteRenderbuffers(rbo);
        } else {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(rbo);
        }
    }

    @Override
    public void bindRenderbuffer(int target, int renderbuffer) {
        if (coreGl30()) {
            GL30.glBindRenderbuffer(target, renderbuffer);
        } else if (arbFbo()) {
            ARBFramebufferObject.glBindRenderbuffer(target, renderbuffer);
        } else {
            EXTFramebufferObject.glBindRenderbufferEXT(target, renderbuffer);
        }
    }

    @Override
    public void renderbufferStorage(int target, int internalFormat, int width, int height) {
        if (coreGl30()) {
            GL30.glRenderbufferStorage(target, internalFormat, width, height);
        } else if (arbFbo()) {
            ARBFramebufferObject.glRenderbufferStorage(target, internalFormat, width, height);
        } else {
            EXTFramebufferObject.glRenderbufferStorageEXT(target, internalFormat, width, height);
        }
    }

    @Override
    public void framebufferRenderbuffer(int target, int attachment,
                                         int renderbufferTarget, int renderbuffer) {
        if (coreGl30()) {
            GL30.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
        } else if (arbFbo()) {
            ARBFramebufferObject.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
        } else {
            EXTFramebufferObject.glFramebufferRenderbufferEXT(target, attachment, renderbufferTarget, renderbuffer);
        }
    }

    // -------------------------------------------------------------------------
    // Shaders — ARBShaderObjects unified-handle methods
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
        GL20.glDetachShader(program, shader);
    }

    @Override
    public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        GL20.glGetAttachedShaders(program, count, shaders);
    }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        return GL20.glGetActiveUniform(program, index, maxLength, sizeTypeBuf);
    }

    @Override
    public void glUniform1(int location, FloatBuffer values) {
        GL20.glUniform1(location, values);
    }

    @Override
    public void glUniform1(int location, IntBuffer values) {
        GL20.glUniform1(location, values);
    }

    @Override
    public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        GL20.glUniformMatrix3(location, transpose, value);
    }

    @Override
    public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        GL20.glUniformMatrix4(location, transpose, value);
    }
}
