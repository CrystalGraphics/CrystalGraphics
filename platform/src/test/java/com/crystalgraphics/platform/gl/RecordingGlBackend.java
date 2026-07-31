package com.crystalgraphics.platform.gl;

import java.nio.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CgGLBackend} that records call names instead of talking to a driver.
 *
 * <p><strong>Generated</strong> from {@code CgGLBackend}'s abstract methods. Regenerate rather than
 * hand-edit if the backend gains methods — the compiler will tell you, since leaving one unimplemented
 * fails the build.</p>
 *
 * <p>Exists because {@code CgGlStateManager}'s restore path re-issues <em>through</em> {@link CgGL}, which
 * is the design's best property (no second write path to drift) and also what made it untestable: any test
 * that closed a scope hit a null backend. With this installed, restore behaviour — the half of the manager
 * where the real bugs have been — can be asserted without a GL context.</p>
 *
 * <p>Only call <em>names</em> are recorded. That is enough for the questions worth asking here: was the
 * call issued at all, or deduplicated away?</p>
 */
public final class RecordingGlBackend extends CgGLBackend {

    private final List<String> calls = new ArrayList<>();

    private void record(String name) { calls.add(name); }

    public List<String> calls() { return calls; }

    public void clear() { calls.clear(); }

    public int countOf(String name) {
        int n = 0;
        for (String c : calls) if (c.equals(name)) n++;
        return n;
    }

    public boolean sawCall(String name) { return countOf(name) > 0; }

    /** Installs a fresh instance as {@link CgGL}'s backend and returns it. */
    public static RecordingGlBackend install() {
        RecordingGlBackend b = new RecordingGlBackend();
        CgGL.init(b);
        return b;
    }

    @Override public void initContext() { record("initContext"); }
    @Override public boolean isAvailable() { record("isAvailable"); return false; }
    @Override public int getPriority() { record("getPriority"); return 0; }
    @Override public void bindFramebuffer(int target, int fbo) { record("bindFramebuffer"); }
    @Override public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { record("blitFramebuffer"); }
    @Override public int genFramebuffers() { record("genFramebuffers"); return 0; }
    @Override public void deleteFramebuffers(int fbo) { record("deleteFramebuffers"); }
    @Override public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) { record("framebufferTexture2D"); }
    @Override public int checkFramebufferStatus(int target) { record("checkFramebufferStatus"); return 0; }
    @Override public void drawBuffers(IntBuffer bufs) { record("drawBuffers"); }
    @Override public int getFramebufferAttachmentParameteriv(int target, int attachment, int pname) { record("getFramebufferAttachmentParameteriv"); return 0; }
    @Override public void bindFramebufferCompat(int fbo) { record("bindFramebufferCompat"); }
    @Override public int glCreateShader(int type) { record("glCreateShader"); return 0; }
    @Override public void glShaderSource(int shader, CharSequence source) { record("glShaderSource"); }
    @Override public void glCompileShader(int shader) { record("glCompileShader"); }
    @Override public int glGetShaderi(int shader, int pname) { record("glGetShaderi"); return 0; }
    @Override public String glGetShaderInfoLog(int shader, int maxLength) { record("glGetShaderInfoLog"); return null; }
    @Override public void glDeleteShader(int shader) { record("glDeleteShader"); }
    @Override public int glCreateProgram() { record("glCreateProgram"); return 0; }
    @Override public void glAttachShader(int program, int shader) { record("glAttachShader"); }
    @Override public void glLinkProgram(int program) { record("glLinkProgram"); }
    @Override public int glGetProgrami(int program, int pname) { record("glGetProgrami"); return 0; }
    @Override public String glGetProgramInfoLog(int program, int maxLength) { record("glGetProgramInfoLog"); return null; }
    @Override public void glUseProgram(int program) { record("glUseProgram"); }
    @Override public void glDeleteProgram(int program) { record("glDeleteProgram"); }
    @Override public int glGetUniformLocation(int program, CharSequence name) { record("glGetUniformLocation"); return 0; }
    @Override public void glUniform1i(int location, int v0) { record("glUniform1i"); }
    @Override public void glUniform1f(int location, float v0) { record("glUniform1f"); }
    @Override public void glUniform2f(int location, float v0, float v1) { record("glUniform2f"); }
    @Override public void glUniform3f(int location, float v0, float v1, float v2) { record("glUniform3f"); }
    @Override public void glUniform4f(int location, float v0, float v1, float v2, float v3) { record("glUniform4f"); }
    @Override public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) { record("glUniformMatrix4fv"); }
    @Override public void glBindAttribLocation(int program, int index, CharSequence name) { record("glBindAttribLocation"); }
    @Override public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) { record("glGetProgramResourceIndex"); return 0; }
    @Override public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) { record("glShaderStorageBlockBinding"); }
    @Override public int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) { record("glGetUniformBlockIndex"); return 0; }
    @Override public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) { record("glUniformBlockBinding"); }
    @Override public int glGenBuffers() { record("glGenBuffers"); return 0; }
    @Override public void glBindBuffer(int target, int buffer) { record("glBindBuffer"); }
    @Override public void glBufferData(int target, ByteBuffer data, int usage) { record("glBufferData"); }
    @Override public void glBufferData(int target, ShortBuffer data, int usage) { record("glBufferData"); }
    @Override public void glBufferData(int target, long size, int usage) { record("glBufferData"); }
    @Override public void glBufferSubData(int target, long offset, ByteBuffer data) { record("glBufferSubData"); }
    @Override public void glDeleteBuffers(int buffer) { record("glDeleteBuffers"); }
    @Override public void glBindBufferBase(int target, int index, int buffer) { record("glBindBufferBase"); }
    @Override public void glBindBufferRange(int target, int index, int buffer, long offset, long size) { record("glBindBufferRange"); }
    @Override public void glTexBuffer(int target, int internalFormat, int buffer) { record("glTexBuffer"); }
    @Override public int glGenQuery() { record("glGenQuery"); return 0; }
    @Override public void glBeginTimeElapsedQuery(int query) { record("glBeginTimeElapsedQuery"); }
    @Override public void glEndTimeElapsedQuery() { record("glEndTimeElapsedQuery"); }
    @Override public boolean glIsQueryResultAvailable(int query) { record("glIsQueryResultAvailable"); return false; }
    @Override public long glGetQueryResultNanos(int query) { record("glGetQueryResultNanos"); return 0L; }
    @Override public void glDeleteQuery(int query) { record("glDeleteQuery"); }
    @Override public int glGenVertexArrays() { record("glGenVertexArrays"); return 0; }
    @Override public void glBindVertexArray(int array) { record("glBindVertexArray"); }
    @Override public void glDeleteVertexArrays(int array) { record("glDeleteVertexArrays"); }
    @Override public void glEnableVertexAttribArray(int index) { record("glEnableVertexAttribArray"); }
    @Override public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) { record("glVertexAttribPointer"); }
    @Override public void glVertexAttribDivisor(int index, int divisor) { record("glVertexAttribDivisor"); }
    @Override public int glGenTextures() { record("glGenTextures"); return 0; }
    @Override public void glBindTexture(int target, int texture) { record("glBindTexture"); }
    @Override public void glDeleteTextures(int texture) { record("glDeleteTextures"); }
    @Override public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) { record("glTexImage2D"); }
    @Override public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) { record("glTexImage2D"); }
    @Override public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, ByteBuffer pixels) { record("glTexSubImage2D"); }
    @Override public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, FloatBuffer pixels) { record("glTexSubImage2D"); }
    @Override public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) { record("glTexImage3D"); }
    @Override public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, FloatBuffer pixels) { record("glTexImage3D"); }
    @Override public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) { record("glTexSubImage3D"); }
    @Override public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth, int format, int type, FloatBuffer pixels) { record("glTexSubImage3D"); }
    @Override public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth, int format, int type, ShortBuffer pixels) { record("glTexSubImage3D"); }
    @Override public void glGenerateMipmap(int target) { record("glGenerateMipmap"); }
    @Override public void glActiveTexture(int texture) { record("glActiveTexture"); }
    @Override public void glTexParameteri(int target, int pname, int param) { record("glTexParameteri"); }
    @Override public void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) { record("glGetTexImage"); }
    @Override public void glDrawArrays(int mode, int first, int count) { record("glDrawArrays"); }
    @Override public void glDrawElements(int mode, int count, int type, long indices) { record("glDrawElements"); }
    @Override public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) { record("glDrawArraysInstanced"); }
    @Override public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) { record("glDrawElementsInstanced"); }
    @Override public void glEnable(int cap) { record("glEnable"); }
    @Override public void glDisable(int cap) { record("glDisable"); }
    @Override public void glBlendFunc(int sfactor, int dfactor) { record("glBlendFunc"); }
    @Override public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { record("glBlendFuncSeparate"); }
    @Override public void glDepthMask(boolean flag) { record("glDepthMask"); }
    @Override public void glCullFace(int mode) { record("glCullFace"); }
    @Override public void glViewport(int x, int y, int width, int height) { record("glViewport"); }
    @Override public void glScissor(int x, int y, int width, int height) { record("glScissor"); }
    @Override public void glLineWidth(float width) { record("glLineWidth"); }
    @Override public void glPolygonMode(int face, int mode) { record("glPolygonMode"); }
    @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) { record("glColorMask"); }
    @Override public void glStencilFunc(int func, int ref, int mask) { record("glStencilFunc"); }
    @Override public void glStencilOp(int sfail, int dpfail, int dppass) { record("glStencilOp"); }
    @Override public void glAlphaFunc(int func, float ref) { record("glAlphaFunc"); }
    @Override public void glClear(int mask) { record("glClear"); }
    @Override public void glClearDepth(double depth) { record("glClearDepth"); }
    @Override public void glClearColor(float r, float g, float b, float a) { record("glClearColor"); }
    @Override public void glClearStencil(int s) { record("glClearStencil"); }
    @Override public void glDepthFunc(int func) { record("glDepthFunc"); }
    @Override public void glStencilMask(int mask) { record("glStencilMask"); }
    @Override public void glBlendEquationSeparate(int modeRGB, int modeAlpha) { record("glBlendEquationSeparate"); }
    @Override public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) { record("glColorMaski"); }
    @Override public void glFrontFace(int mode) { record("glFrontFace"); }
    @Override public void glPolygonOffset(float factor, float units) { record("glPolygonOffset"); }
    @Override public void glPointSize(float size) { record("glPointSize"); }
    @Override public void glDrawBuffer(int mode) { record("glDrawBuffer"); }
    @Override public void glReadBuffer(int mode) { record("glReadBuffer"); }
    @Override public void glPixelStorei(int pname, int param) { record("glPixelStorei"); }
    @Override public int glGetInteger(int pname) { record("glGetInteger"); return 0; }
    @Override public void glGetInteger(int pname, IntBuffer params) { record("glGetInteger"); }
    @Override public boolean glGetBoolean(int pname) { record("glGetBoolean"); return false; }
    @Override public void glGetBoolean(int pname, ByteBuffer params) { record("glGetBoolean"); }
    @Override public void glGetFloat(int pname, FloatBuffer params) { record("glGetFloat"); }
    @Override public float glGetFloat(int pname) { record("glGetFloat"); return 0f; }
    @Override public void glBindSampler(int unit, int sampler) { record("glBindSampler"); }
    @Override public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) { record("glMapBufferRange"); return null; }
    @Override public boolean glUnmapBuffer(int target) { record("glUnmapBuffer"); return false; }
    @Override public void glFlushMappedBufferRange(int target, long offset, long length) { record("glFlushMappedBufferRange"); }
    @Override public long glFenceSync(int condition, int flags) { record("glFenceSync"); return 0L; }
    @Override public int glClientWaitSync(long sync, int flags, long timeout) { record("glClientWaitSync"); return 0; }
    @Override public void glDeleteSync(long sync) { record("glDeleteSync"); }
    @Override public int glGetError() { record("glGetError"); return 0; }
    @Override public boolean isContextCurrent() { record("isContextCurrent"); return false; }
    @Override public void glPushMatrix() { record("glPushMatrix"); }
    @Override public void glPopMatrix() { record("glPopMatrix"); }
    @Override public void glLoadMatrix(FloatBuffer m) { record("glLoadMatrix"); }
    @Override public int glGenRenderbuffers() { record("glGenRenderbuffers"); return 0; }
    @Override public void glDeleteRenderbuffers(int rbo) { record("glDeleteRenderbuffers"); }
    @Override public void glBindRenderbuffer(int target, int renderbuffer) { record("glBindRenderbuffer"); }
    @Override public void glRenderbufferStorage(int target, int internalFormat, int width, int height) { record("glRenderbufferStorage"); }
    @Override public void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) { record("glRenderbufferStorageMultisample"); }
    @Override public void glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height, boolean fixedSampleLocations) { record("glTexImage2DMultisample"); }
    @Override public void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) { record("glFramebufferRenderbuffer"); }
    @Override public void glDeleteObject(int handle) { record("glDeleteObject"); }
    @Override public int glGetObjectParameteri(int obj, int pname) { record("glGetObjectParameteri"); return 0; }
    @Override public String glGetObjectInfoLog(int obj, int maxLength) { record("glGetObjectInfoLog"); return null; }
    @Override public int glGetHandle(int pname) { record("glGetHandle"); return 0; }
    @Override public void glDetachShader(int program, int shader) { record("glDetachShader"); }
    @Override public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) { record("glGetAttachedShaders"); }
    @Override public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) { record("glGetActiveUniform"); return null; }
    @Override public void glUniform1(int location, FloatBuffer values) { record("glUniform1"); }
    @Override public void glUniform1(int location, IntBuffer values) { record("glUniform1"); }
    @Override public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) { record("glUniformMatrix3"); }
    @Override public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) { record("glUniformMatrix4"); }
}
