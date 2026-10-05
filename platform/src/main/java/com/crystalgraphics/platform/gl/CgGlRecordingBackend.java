package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;

import java.nio.*;

/**
 * The backend {@link CgGlRecording} installs: every call it can defer is encoded onto a tape, and {@link #replay}
 * decodes the tape back through {@link CgGL}. Encoding and decoding sit side by side so an operation cannot be
 * written one way and read another.
 *
 * <p>Allocation-free once warm: the tape, the reference table and the replay scratch grow and are reused.</p>
 */
final class CgGlRecordingBackend extends CgGLBackend {

    private static final int
            BIND_FRAMEBUFFER = 1, BLIT_FRAMEBUFFER = 2, COPY_IMAGE_SUB_DATA = 3, FRAMEBUFFER_TEXTURE_LAYER = 4,
            DELETE_FRAMEBUFFERS = 5, FRAMEBUFFER_TEXTURE_2D = 6, DRAW_BUFFERS = 7, DELETE_SHADER = 8,
            USE_PROGRAM = 9, DELETE_PROGRAM = 10, UNIFORM1I = 11, UNIFORM1F = 12, UNIFORM2F = 13, UNIFORM3F = 14,
            UNIFORM4F = 15, UNIFORM_MATRIX4FV = 16, SHADER_STORAGE_BLOCK_BINDING = 17, UNIFORM_BLOCK_BINDING = 18,
            BIND_BUFFER = 19, BUFFER_DATA_BYTES = 20, BUFFER_DATA_SHORTS = 21, BUFFER_DATA_SIZE = 22,
            BUFFER_SUB_DATA = 23, DELETE_BUFFERS = 24, BIND_BUFFER_BASE = 25, BIND_BUFFER_RANGE = 26,
            TEX_BUFFER = 27, BEGIN_TIME_QUERY = 28, END_TIME_QUERY = 29, DELETE_QUERY = 30, BIND_VAO = 31,
            DELETE_VAO = 32, ENABLE_ATTRIB = 33, ATTRIB_POINTER = 34, ATTRIB_DIVISOR = 35, BIND_TEXTURE = 36,
            DELETE_TEXTURES = 37, TEX_IMAGE_2D_B = 38, TEX_IMAGE_2D_F = 39, TEX_SUB_IMAGE_2D_B = 40,
            TEX_SUB_IMAGE_2D_F = 41, TEX_IMAGE_3D_B = 42, TEX_IMAGE_3D_F = 43, TEX_SUB_IMAGE_3D_B = 44,
            TEX_SUB_IMAGE_3D_F = 45, TEX_SUB_IMAGE_3D_S = 46, GENERATE_MIPMAP = 47, ACTIVE_TEXTURE = 48,
            TEX_PARAMETERI = 49, DRAW_ARRAYS = 50, DRAW_ELEMENTS = 51, DRAW_ARRAYS_INSTANCED = 52,
            DRAW_ELEMENTS_INSTANCED = 53, ENABLE = 54, DISABLE = 55, BLEND_FUNC = 56, BLEND_FUNC_SEPARATE = 57,
            DEPTH_MASK = 58, CULL_FACE = 59, VIEWPORT = 60, SCISSOR = 61, LINE_WIDTH = 62, POLYGON_MODE = 63,
            COLOR_MASK = 64, STENCIL_FUNC = 65, STENCIL_OP = 66, ALPHA_FUNC = 67, CLEAR = 68, CLEAR_DEPTH = 69,
            CLEAR_COLOR = 70, CLEAR_STENCIL = 71, DEPTH_FUNC = 72, STENCIL_MASK = 73, BLEND_EQUATION_SEPARATE = 74,
            COLOR_MASKI = 75, FRONT_FACE = 76, POLYGON_OFFSET = 77, POINT_SIZE = 78, DRAW_BUFFER = 79,
            READ_BUFFER = 80, PIXEL_STOREI = 81, BIND_SAMPLER = 82, MAP_WRITE = 83, UNMAP = 84, FLUSH_MAPPED = 85,
            BUFFER_STORAGE = 86, DELETE_SYNC = 87, DELETE_RBO = 88, BIND_RBO = 89, RBO_STORAGE = 90,
            RBO_STORAGE_MS = 91, FRAMEBUFFER_RBO = 92, TEX_IMAGE_2D_MS = 93, UNIFORM1_FV = 94, UNIFORM1_IV = 95,
            UNIFORM_MATRIX3 = 96, UNIFORM_MATRIX4 = 97, SCOPE_BEGIN = 98, SCOPE_END = 99, INVALIDATE = 100,
            INVALIDATE_ALL = 101, FOREIGN = 102, COPY_BUFFER_SUB_DATA = 103, ATTRIB_IPOINTER = 104,
            DRAW_ELEMENTS_INSTANCED_BASE_VERTEX = 105, DRAW_ARRAYS_INDIRECT = 106, DRAW_ELEMENTS_INDIRECT = 107,
            MULTI_DRAW_ARRAYS_INDIRECT = 108, MULTI_DRAW_ELEMENTS_INDIRECT = 109,
            MULTI_DRAW_ARRAYS_INDIRECT_COUNT = 110, MULTI_DRAW_ELEMENTS_INDIRECT_COUNT = 111, DISPATCH_COMPUTE = 112,
            DISPATCH_COMPUTE_INDIRECT = 113, MEMORY_BARRIER = 114, BIND_IMAGE_TEXTURE = 115, CG_BUFFER_BARRIER = 116,
            CG_IMAGE_BARRIER = 117, CG_FILL_BUFFER = 118, BEGIN_TRANSFORM_FEEDBACK = 119,
            END_TRANSFORM_FEEDBACK = 120, QUERY_TIMESTAMP = 121, TEX_SUB_IMAGE_2D_O = 122, TEX_SUB_IMAGE_3D_O = 123;

    private static final int MAX_SCOPES = 32;
    /** {@code -Dcrystalgraphics.recording.debugScopes=true}: an open scope at {@code end()} names where it opened. */
    private static final boolean DEBUG_SCOPES = Boolean.getBoolean("crystalgraphics.recording.debugScopes");

    private final CgGLBackend live;
    private final CgGlStateManager manager;

    private ByteBuffer tape = ByteBuffer.allocate(64 << 10).order(ByteOrder.nativeOrder());
    private int written;
    private Object[] refs = new Object[64];
    private int refCount;

    private int pendingMapTarget = -1, pendingMapAccess;
    private long pendingMapOffset, pendingMapLength;
    private ByteBuffer mapScratch = ByteBuffer.allocateDirect(64 << 10).order(ByteOrder.nativeOrder());

    private final int[] pixelStoreNames = new int[16], pixelStoreValues = new int[16];
    private int pixelStoreCount;

    private final RecordedScope[] scopes = new RecordedScope[MAX_SCOPES];
    private int scopeDepth;

    private int read;
    private ByteBuffer scratch;
    private FloatBuffer scratchFloats;
    private IntBuffer scratchInts;
    private ShortBuffer scratchShorts;
    private final CgGlScope[] replayScopes = new CgGlScope[MAX_SCOPES];

    CgGlRecordingBackend(CgGLBackend live, CgGlStateManager manager) {
        this.live = live;
        this.manager = manager;
        for (int i = 0; i < MAX_SCOPES; i++) scopes[i] = new RecordedScope();
        growScratch(64 << 10);
    }

    // ── Recording lifecycle ───────────────────────────────────────────────────

    void reset() {
        written = 0;
        for (int i = 0; i < refCount; i++) refs[i] = null;
        refCount = 0;
        pendingMapTarget = -1;
        pixelStoreCount = 0;
        scopeDepth = 0;
        firstRefusal = null;
    }

    void finish() {
        if (scopeDepth != 0) {
            Throwable cause = firstRefusal != null ? firstRefusal : scopes[scopeDepth - 1].openedAt;
            throw new IllegalStateException(scopeDepth + " recorded GL scope(s) still open at end()"
                    + (cause == null ? "; -Dcrystalgraphics.recording.debugScopes=true names where it opened" : ""), cause);
        }
        if (pendingMapTarget != -1) throw new IllegalStateException("A buffer mapped while recording was never unmapped");
    }

    int size() { return written; }

    CgGlScope recordScope(boolean foreign, CgGlSlot[] slots) {
        if (scopeDepth == MAX_SCOPES) throw new IllegalStateException("Recorded GL scope nesting exceeded " + MAX_SCOPES);
        op(SCOPE_BEGIN); ref(slots); z(foreign);
        RecordedScope s = scopes[scopeDepth++];
        s.open(foreign, slots);
        return s;
    }

    void recordInvalidate(CgGlSlot[] slots) { op(INVALIDATE); ref(slots); }

    void recordInvalidateAll() { op(INVALIDATE_ALL); }

    void recordForeign(Runnable body, CgGlSlot[] slots) { op(FOREIGN); ref(slots); ref(body); }

    private final class RecordedScope implements CgGlScope {
        private boolean foreign, closed;
        private CgGlSlot[] slots;
        private Throwable openedAt;

        void open(boolean foreign, CgGlSlot[] slots) {
            this.foreign = foreign;
            this.slots = slots;
            this.closed = false;
            this.openedAt = DEBUG_SCOPES ? new Throwable("recorded scope opened here") : null;
        }

        @Override public void restore() {
            if (closed) return;
            if (scopeDepth == 0 || scopes[scopeDepth - 1] != this) {
                throw new IllegalStateException("Recorded GL scopes closed out of order; use try-with-resources");
            }
            closed = true;
            op(SCOPE_END);
            manager.forgetRecorded(foreign, slots);
            slots = null;
            scopeDepth--;
        }

        @Override public void close() { restore(); }
    }

    // ── Replay ────────────────────────────────────────────────────────────────

    /** Decodes the tape through {@link CgGL}, so the live state manager sees every call. */
    void replay() {
        CgGL.replaying = true;
        try {
            replayTape();
        } finally {
            CgGL.replaying = false;
        }
    }

    private void replayTape() {
        read = 0;
        int depth = 0;
        byte[] a = tape.array();
        int base = tape.arrayOffset();
        while (read < written) {
            int code = ri();
            switch (code) {
                case BIND_FRAMEBUFFER: CgGL.glBindFramebuffer(ri(), ri()); break;
                case BLIT_FRAMEBUFFER: CgGL.glBlitFramebuffer(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri()); break;
                case COPY_IMAGE_SUB_DATA:
                    CgGL.glCopyImageSubData(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri());
                    break;
                case FRAMEBUFFER_TEXTURE_LAYER: CgGL.glFramebufferTextureLayer(ri(), ri(), ri(), ri(), ri()); break;
                case DELETE_FRAMEBUFFERS: CgGL.glDeleteFramebuffers(ri()); break;
                case FRAMEBUFFER_TEXTURE_2D: CgGL.glFramebufferTexture2D(ri(), ri(), ri(), ri(), ri()); break;
                case DRAW_BUFFERS: CgGL.glDrawBuffers(rints()); break;
                case DELETE_SHADER: CgGL.glDeleteShader(ri()); break;
                case USE_PROGRAM: CgGL.glUseProgram(ri()); break;
                case DELETE_PROGRAM: CgGL.glDeleteProgram(ri()); break;
                case UNIFORM1I: CgGL.glUniform1i(ri(), ri()); break;
                case UNIFORM1F: CgGL.glUniform1f(ri(), rf()); break;
                case UNIFORM2F: CgGL.glUniform2f(ri(), rf(), rf()); break;
                case UNIFORM3F: CgGL.glUniform3f(ri(), rf(), rf(), rf()); break;
                case UNIFORM4F: CgGL.glUniform4f(ri(), rf(), rf(), rf(), rf()); break;
                case UNIFORM_MATRIX4FV: CgGL.glUniformMatrix4fv(ri(), rz(), rfloats()); break;
                case SHADER_STORAGE_BLOCK_BINDING: CgGL.glShaderStorageBlockBinding(ri(), ri(), ri()); break;
                case UNIFORM_BLOCK_BINDING: CgGL.glUniformBlockBinding(ri(), ri(), ri()); break;
                case BIND_BUFFER: CgGL.glBindBuffer(ri(), ri()); break;
                case BUFFER_DATA_BYTES: CgGL.glBufferData(ri(), rbytes(), ri()); break;
                case BUFFER_DATA_SHORTS: CgGL.glBufferData(ri(), rshorts(), ri()); break;
                case BUFFER_DATA_SIZE: CgGL.glBufferData(ri(), rl(), ri()); break;
                case BUFFER_SUB_DATA: CgGL.glBufferSubData(ri(), rl(), rbytes()); break;
                case COPY_BUFFER_SUB_DATA: CgGL.glCopyBufferSubData(ri(), ri(), rl(), rl(), rl()); break;
                case DELETE_BUFFERS: CgGL.glDeleteBuffers(ri()); break;
                case BIND_BUFFER_BASE: CgGL.glBindBufferBase(ri(), ri(), ri()); break;
                case BIND_BUFFER_RANGE: CgGL.glBindBufferRange(ri(), ri(), ri(), rl(), rl()); break;
                case TEX_BUFFER: CgGL.glTexBuffer(ri(), ri(), ri()); break;
                case BEGIN_TIME_QUERY: CgGL.glBeginTimeElapsedQuery(ri()); break;
                case END_TIME_QUERY: CgGL.glEndTimeElapsedQuery(); break;
                case QUERY_TIMESTAMP: CgGL.glQueryTimestamp(ri()); break;
                case DELETE_QUERY: CgGL.glDeleteQuery(ri()); break;
                case BIND_VAO: CgGL.glBindVertexArray(ri()); break;
                case DELETE_VAO: CgGL.glDeleteVertexArrays(ri()); break;
                case ENABLE_ATTRIB: CgGL.glEnableVertexAttribArray(ri()); break;
                case ATTRIB_POINTER: CgGL.glVertexAttribPointer(ri(), ri(), ri(), rz(), ri(), rl()); break;
                case ATTRIB_IPOINTER: CgGL.glVertexAttribIPointer(ri(), ri(), ri(), ri(), rl()); break;
                case ATTRIB_DIVISOR: CgGL.glVertexAttribDivisor(ri(), ri()); break;
                case BIND_TEXTURE: CgGL.glBindTexture(ri(), ri()); break;
                case DELETE_TEXTURES: CgGL.glDeleteTextures(ri()); break;
                case TEX_IMAGE_2D_B: CgGL.glTexImage2D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rbytes()); break;
                case TEX_IMAGE_2D_F: CgGL.glTexImage2D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rfloats()); break;
                case TEX_SUB_IMAGE_2D_B: CgGL.glTexSubImage2D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rbytes()); break;
                case TEX_SUB_IMAGE_2D_F: CgGL.glTexSubImage2D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rfloats()); break;
                case TEX_IMAGE_3D_B: CgGL.glTexImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rbytes()); break;
                case TEX_IMAGE_3D_F: CgGL.glTexImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rfloats()); break;
                case TEX_SUB_IMAGE_3D_B:
                    CgGL.glTexSubImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rbytes()); break;
                case TEX_SUB_IMAGE_3D_F:
                    CgGL.glTexSubImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rfloats()); break;
                case TEX_SUB_IMAGE_3D_S:
                    CgGL.glTexSubImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rshorts()); break;
                case TEX_SUB_IMAGE_2D_O: CgGL.glTexSubImage2D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rl()); break;
                case TEX_SUB_IMAGE_3D_O:
                    CgGL.glTexSubImage3D(ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), ri(), rl()); break;
                case GENERATE_MIPMAP: CgGL.glGenerateMipmap(ri()); break;
                case ACTIVE_TEXTURE: CgGL.glActiveTexture(ri()); break;
                case TEX_PARAMETERI: CgGL.glTexParameteri(ri(), ri(), ri()); break;
                case DRAW_ARRAYS: CgGL.glDrawArrays(ri(), ri(), ri()); break;
                case DRAW_ELEMENTS: CgGL.glDrawElements(ri(), ri(), ri(), rl()); break;
                case DRAW_ARRAYS_INSTANCED: CgGL.glDrawArraysInstanced(ri(), ri(), ri(), ri()); break;
                case DRAW_ELEMENTS_INSTANCED: CgGL.glDrawElementsInstanced(ri(), ri(), ri(), rl(), ri()); break;
                case DRAW_ELEMENTS_INSTANCED_BASE_VERTEX:
                    CgGL.glDrawElementsInstancedBaseVertex(ri(), ri(), ri(), rl(), ri(), ri()); break;
                case DRAW_ARRAYS_INDIRECT: CgGL.glDrawArraysIndirect(ri(), rl()); break;
                case DRAW_ELEMENTS_INDIRECT: CgGL.glDrawElementsIndirect(ri(), ri(), rl()); break;
                case MULTI_DRAW_ARRAYS_INDIRECT: CgGL.glMultiDrawArraysIndirect(ri(), rl(), ri(), ri()); break;
                case MULTI_DRAW_ELEMENTS_INDIRECT: CgGL.glMultiDrawElementsIndirect(ri(), ri(), rl(), ri(), ri()); break;
                case MULTI_DRAW_ARRAYS_INDIRECT_COUNT:
                    CgGL.glMultiDrawArraysIndirectCount(ri(), rl(), rl(), ri(), ri()); break;
                case MULTI_DRAW_ELEMENTS_INDIRECT_COUNT:
                    CgGL.glMultiDrawElementsIndirectCount(ri(), ri(), rl(), rl(), ri(), ri()); break;
                case DISPATCH_COMPUTE: CgGL.glDispatchCompute(ri(), ri(), ri()); break;
                case DISPATCH_COMPUTE_INDIRECT: CgGL.glDispatchComputeIndirect(rl()); break;
                case MEMORY_BARRIER: CgGL.glMemoryBarrier(ri()); break;
                case BIND_IMAGE_TEXTURE: CgGL.glBindImageTexture(ri(), ri(), ri(), rz(), ri(), ri(), ri()); break;
                case CG_BUFFER_BARRIER: CgGL.cgBufferBarrier(ri(), ri(), ri()); break;
                case CG_IMAGE_BARRIER: CgGL.cgImageBarrier(ri(), ri(), ri()); break;
                case CG_FILL_BUFFER: CgGL.cgFillBuffer(ri(), rl(), rl(), ri()); break;
                case BEGIN_TRANSFORM_FEEDBACK: CgGL.glBeginTransformFeedback(ri()); break;
                case END_TRANSFORM_FEEDBACK: CgGL.glEndTransformFeedback(); break;
                case ENABLE: CgGL.glEnable(ri()); break;
                case DISABLE: CgGL.glDisable(ri()); break;
                case BLEND_FUNC: CgGL.glBlendFunc(ri(), ri()); break;
                case BLEND_FUNC_SEPARATE: CgGL.glBlendFuncSeparate(ri(), ri(), ri(), ri()); break;
                case DEPTH_MASK: CgGL.glDepthMask(rz()); break;
                case CULL_FACE: CgGL.glCullFace(ri()); break;
                case VIEWPORT: CgGL.glViewport(ri(), ri(), ri(), ri()); break;
                case SCISSOR: CgGL.glScissor(ri(), ri(), ri(), ri()); break;
                case LINE_WIDTH: CgGL.glLineWidth(rf()); break;
                case POLYGON_MODE: CgGL.glPolygonMode(ri(), ri()); break;
                case COLOR_MASK: CgGL.glColorMask(rz(), rz(), rz(), rz()); break;
                case STENCIL_FUNC: CgGL.glStencilFunc(ri(), ri(), ri()); break;
                case STENCIL_OP: CgGL.glStencilOp(ri(), ri(), ri()); break;
                case ALPHA_FUNC: CgGL.glAlphaFunc(ri(), rf()); break;
                case CLEAR: CgGL.glClear(ri()); break;
                case CLEAR_DEPTH: CgGL.glClearDepth(rd()); break;
                case CLEAR_COLOR: CgGL.glClearColor(rf(), rf(), rf(), rf()); break;
                case CLEAR_STENCIL: CgGL.glClearStencil(ri()); break;
                case DEPTH_FUNC: CgGL.glDepthFunc(ri()); break;
                case STENCIL_MASK: CgGL.glStencilMask(ri()); break;
                case BLEND_EQUATION_SEPARATE: CgGL.glBlendEquationSeparate(ri(), ri()); break;
                case COLOR_MASKI: CgGL.glColorMaski(ri(), rz(), rz(), rz(), rz()); break;
                case FRONT_FACE: CgGL.glFrontFace(ri()); break;
                case POLYGON_OFFSET: CgGL.glPolygonOffset(rf(), rf()); break;
                case POINT_SIZE: CgGL.glPointSize(rf()); break;
                case DRAW_BUFFER: CgGL.glDrawBuffer(ri()); break;
                case READ_BUFFER: CgGL.glReadBuffer(ri()); break;
                case PIXEL_STOREI: CgGL.glPixelStorei(ri(), ri()); break;
                case BIND_SAMPLER: CgGL.glBindSampler(ri(), ri()); break;
                case MAP_WRITE: {
                    int target = ri();
                    long offset = rl(), length = rl();
                    int access = ri();
                    ByteBuffer mapped = CgGL.glMapBufferRange(target, offset, length, access, null);
                    if (mapped == null) throw new IllegalStateException("glMapBufferRange returned null on replay");
                    mapped.put(a, base + read, (int) length);
                    read += (int) length;
                    break;
                }
                case UNMAP: CgGL.glUnmapBuffer(ri()); break;
                case FLUSH_MAPPED: CgGL.glFlushMappedBufferRange(ri(), rl(), rl()); break;
                case BUFFER_STORAGE: CgGL.glBufferStorage(ri(), rl(), ri()); break;
                case DELETE_SYNC: CgGL.glDeleteSync(rl()); break;
                case DELETE_RBO: CgGL.glDeleteRenderbuffers(ri()); break;
                case BIND_RBO: CgGL.glBindRenderbuffer(ri(), ri()); break;
                case RBO_STORAGE: CgGL.glRenderbufferStorage(ri(), ri(), ri(), ri()); break;
                case RBO_STORAGE_MS: CgGL.glRenderbufferStorageMultisample(ri(), ri(), ri(), ri(), ri()); break;
                case FRAMEBUFFER_RBO: CgGL.glFramebufferRenderbuffer(ri(), ri(), ri(), ri()); break;
                case TEX_IMAGE_2D_MS: CgGL.glTexImage2DMultisample(ri(), ri(), ri(), ri(), ri(), rz()); break;
                case UNIFORM1_FV: CgGL.glUniform1(ri(), rfloats()); break;
                case UNIFORM1_IV: CgGL.glUniform1(ri(), rints()); break;
                case UNIFORM_MATRIX3: CgGL.glUniformMatrix3(ri(), rz(), rfloats()); break;
                case UNIFORM_MATRIX4: CgGL.glUniformMatrix4(ri(), rz(), rfloats()); break;
                case SCOPE_BEGIN: {
                    CgGlSlot[] slots = (CgGlSlot[]) refs[ri()];
                    replayScopes[depth++] = rz() ? CgGlState.hostForeign(slots) : CgGlState.save(slots);
                    break;
                }
                case SCOPE_END: {
                    CgGlScope s = replayScopes[--depth];
                    replayScopes[depth] = null;
                    s.close();
                    break;
                }
                case INVALIDATE: CgGlState.manager().invalidate((CgGlSlot[]) refs[ri()]); break;
                case INVALIDATE_ALL: CgGlState.invalidateAllIfPresent(); break;
                case FOREIGN: {
                    CgGlSlot[] slots = (CgGlSlot[]) refs[ri()];
                    CgGlState.hostForeign((Runnable) refs[ri()], slots);
                    break;
                }
                default: throw new IllegalStateException("Corrupt GL recording: opcode " + code + " at " + (read - 4));
            }
        }
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    private void ensure(int bytes) {
        if (written + bytes <= tape.capacity()) return;
        ByteBuffer grown = ByteBuffer.allocate(Math.max(tape.capacity() * 2, written + bytes)).order(ByteOrder.nativeOrder());
        System.arraycopy(tape.array(), tape.arrayOffset(), grown.array(), grown.arrayOffset(), written);
        tape = grown;
    }

    private void op(int code) { i(code); }
    private void i(int v) { ensure(4); tape.putInt(written, v); written += 4; }
    private void l(long v) { ensure(8); tape.putLong(written, v); written += 8; }
    private void f(float v) { ensure(4); tape.putFloat(written, v); written += 4; }
    private void d(double v) { ensure(8); tape.putDouble(written, v); written += 8; }
    private void z(boolean v) { i(v ? 1 : 0); }

    private void ref(Object o) {
        if (refCount == refs.length) {
            Object[] grown = new Object[refs.length * 2];
            System.arraycopy(refs, 0, grown, 0, refCount);
            refs = grown;
        }
        refs[refCount] = o;
        i(refCount++);
    }

    private void bytes(ByteBuffer b) {
        if (b == null) { i(-1); return; }
        int n = b.remaining(), p = b.position();
        i(n);
        ensure(n);
        for (int k = 0; k < n; k++) tape.put(written + k, b.get(p + k));
        written += n;
    }

    private void floats(FloatBuffer b) {
        if (b == null) { i(-1); return; }
        int n = b.remaining(), p = b.position();
        i(n);
        ensure(n * 4);
        for (int k = 0; k < n; k++) tape.putFloat(written + k * 4, b.get(p + k));
        written += n * 4;
    }

    private void ints(IntBuffer b) {
        if (b == null) { i(-1); return; }
        int n = b.remaining(), p = b.position();
        i(n);
        ensure(n * 4);
        for (int k = 0; k < n; k++) tape.putInt(written + k * 4, b.get(p + k));
        written += n * 4;
    }

    private void shorts(ShortBuffer b) {
        if (b == null) { i(-1); return; }
        int n = b.remaining(), p = b.position();
        i(n);
        ensure(n * 2);
        for (int k = 0; k < n; k++) tape.putShort(written + k * 2, b.get(p + k));
        written += n * 2;
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    private int ri() { int v = tape.getInt(read); read += 4; return v; }
    private long rl() { long v = tape.getLong(read); read += 8; return v; }
    private float rf() { float v = tape.getFloat(read); read += 4; return v; }
    private double rd() { double v = tape.getDouble(read); read += 8; return v; }
    private boolean rz() { return ri() != 0; }

    /** Copies {@code n} tape bytes into the direct scratch, which every LWJGL call needs. */
    private boolean toScratch(int n) {
        if (n < 0) return false;
        if (n > scratch.capacity()) growScratch(Math.max(n, scratch.capacity() * 2));
        scratch.clear();
        scratch.put(tape.array(), tape.arrayOffset() + read, n);
        scratch.flip();
        read += n;
        return true;
    }

    private void growScratch(int capacity) {
        scratch = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        scratchFloats = scratch.asFloatBuffer();
        scratchInts = scratch.asIntBuffer();
        scratchShorts = scratch.asShortBuffer();
    }

    private ByteBuffer rbytes() { return toScratch(ri()) ? scratch : null; }

    private FloatBuffer rfloats() {
        int n = ri();
        if (!toScratch(n < 0 ? -1 : n * 4)) return null;
        scratchFloats.clear().limit(n);
        return scratchFloats;
    }

    private IntBuffer rints() {
        int n = ri();
        if (!toScratch(n < 0 ? -1 : n * 4)) return null;
        scratchInts.clear().limit(n);
        return scratchInts;
    }

    private ShortBuffer rshorts() {
        int n = ri();
        if (!toScratch(n < 0 ? -1 : n * 2)) return null;
        scratchShorts.clear().limit(n);
        return scratchShorts;
    }

    // ── What a recording refuses ──────────────────────────────────────────────

    /** The first refusal of this recording: an exception that unwound a paint is why a scope was left open. */
    private RuntimeException firstRefusal;

    private UnsupportedOperationException refused(String call, String why) {
        UnsupportedOperationException e = new UnsupportedOperationException(call + " cannot be recorded: " + why);
        if (firstRefusal == null) firstRefusal = e;
        return e;
    }

    private static final String CREATES = "a new object's name is needed before replay; create it before recording";
    private static final String COMPILES = "compile and link programs before recording";
    private static final String READS = "the pixels do not exist until replay";

    // ── CgGLBackend: lifecycle ────────────────────────────────────────────────

    @Override public void initContext() { throw refused("initContext", "a context is initialised outside a recording"); }
    @Override public boolean isAvailable() { return live.isAvailable(); }
    @Override public int getPriority() { return live.getPriority(); }

    // ── Framebuffers ──────────────────────────────────────────────────────────

    @Override public void bindFramebuffer(int target, int fbo) { op(BIND_FRAMEBUFFER); i(target); i(fbo); }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
                                int mask, int filter) {
        op(BLIT_FRAMEBUFFER); i(srcX0); i(srcY0); i(srcX1); i(srcY1); i(dstX0); i(dstY0); i(dstX1); i(dstY1); i(mask); i(filter);
    }

    @Override
    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        op(COPY_IMAGE_SUB_DATA); i(srcName); i(srcTarget); i(srcLevel); i(srcX); i(srcY); i(srcZ);
        i(dstName); i(dstTarget); i(dstLevel); i(dstX); i(dstY); i(dstZ); i(srcWidth); i(srcHeight); i(srcDepth);
    }

    @Override
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        op(FRAMEBUFFER_TEXTURE_LAYER); i(target); i(attachment); i(texture); i(level); i(layer);
    }

    @Override public int genFramebuffers() { throw refused("glGenFramebuffers", CREATES); }
    @Override public void deleteFramebuffers(int fbo) { op(DELETE_FRAMEBUFFERS); i(fbo); }

    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        op(FRAMEBUFFER_TEXTURE_2D); i(target); i(attachment); i(texTarget); i(texture); i(level);
    }

    @Override public int checkFramebufferStatus(int target) { throw refused("glCheckFramebufferStatus", "completeness is decided on replay"); }
    @Override public void drawBuffers(IntBuffer bufs) { op(DRAW_BUFFERS); ints(bufs); }

    @Override
    public int getFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        return live.getFramebufferAttachmentParameteriv(target, attachment, pname);
    }

    // ── Shaders ───────────────────────────────────────────────────────────────

    @Override public int glCreateShader(int type) { throw refused("glCreateShader", CREATES); }
    @Override public void glShaderSource(int shader, CharSequence source) { throw refused("glShaderSource", COMPILES); }
    @Override public void glCompileShader(int shader) { throw refused("glCompileShader", COMPILES); }
    @Override public int glGetShaderi(int shader, int pname) { throw refused("glGetShaderi", COMPILES); }
    @Override public String glGetShaderInfoLog(int shader, int maxLength) { throw refused("glGetShaderInfoLog", COMPILES); }
    @Override public void glDeleteShader(int shader) { op(DELETE_SHADER); i(shader); }
    @Override public int glCreateProgram() { throw refused("glCreateProgram", CREATES); }
    @Override public void glAttachShader(int program, int shader) { throw refused("glAttachShader", COMPILES); }
    @Override public void glLinkProgram(int program) { throw refused("glLinkProgram", COMPILES); }
    @Override public int glGetProgrami(int program, int pname) { throw refused("glGetProgrami", COMPILES); }
    @Override public String glGetProgramInfoLog(int program, int maxLength) { throw refused("glGetProgramInfoLog", COMPILES); }
    @Override public void glUseProgram(int program) { op(USE_PROGRAM); i(program); }
    @Override public void glDeleteProgram(int program) { op(DELETE_PROGRAM); i(program); }

    /** A linked program's answer, which nothing recorded can have changed: linking is refused. */
    @Override public int glGetUniformLocation(int program, CharSequence name) { return live.glGetUniformLocation(program, name); }

    @Override public void glUniform1i(int location, int v0) { op(UNIFORM1I); i(location); i(v0); }
    @Override public void glUniform1f(int location, float v0) { op(UNIFORM1F); i(location); f(v0); }
    @Override public void glUniform2f(int location, float v0, float v1) { op(UNIFORM2F); i(location); f(v0); f(v1); }
    @Override public void glUniform3f(int location, float v0, float v1, float v2) { op(UNIFORM3F); i(location); f(v0); f(v1); f(v2); }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        op(UNIFORM4F); i(location); f(v0); f(v1); f(v2); f(v3);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        op(UNIFORM_MATRIX4FV); i(location); z(transpose); floats(value);
    }

    @Override public void glBindAttribLocation(int program, int index, CharSequence name) { throw refused("glBindAttribLocation", COMPILES); }

    @Override
    public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return live.glGetProgramResourceIndex(program, programInterface, name);
    }

    @Override
    public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        op(SHADER_STORAGE_BLOCK_BINDING); i(program); i(storageBlockIndex); i(storageBlockBinding);
    }

    @Override public int glGetUniformBlockIndex(int program, CharSequence name) { return live.glGetUniformBlockIndex(program, name); }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        op(UNIFORM_BLOCK_BINDING); i(program); i(uniformBlockIndex); i(uniformBlockBinding);
    }

    // ── Buffers ───────────────────────────────────────────────────────────────

    @Override public int glGenBuffers() { throw refused("glGenBuffers", CREATES); }
    @Override public void glBindBuffer(int target, int buffer) { op(BIND_BUFFER); i(target); i(buffer); }
    @Override public void glBufferData(int target, ByteBuffer data, int usage) { op(BUFFER_DATA_BYTES); i(target); bytes(data); i(usage); }
    @Override public void glBufferData(int target, ShortBuffer data, int usage) { op(BUFFER_DATA_SHORTS); i(target); shorts(data); i(usage); }
    @Override public void glBufferData(int target, long size, int usage) { op(BUFFER_DATA_SIZE); i(target); l(size); i(usage); }
    @Override public void glBufferSubData(int target, long offset, ByteBuffer data) { op(BUFFER_SUB_DATA); i(target); l(offset); bytes(data); }

    @Override
    public void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        op(COPY_BUFFER_SUB_DATA); i(readTarget); i(writeTarget); l(readOffset); l(writeOffset); l(size);
    }
    @Override public void glDeleteBuffers(int buffer) { op(DELETE_BUFFERS); i(buffer); }
    @Override public void glBindBufferBase(int target, int index, int buffer) { op(BIND_BUFFER_BASE); i(target); i(index); i(buffer); }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        op(BIND_BUFFER_RANGE); i(target); i(index); i(buffer); l(offset); l(size);
    }

    @Override public void glTexBuffer(int target, int internalFormat, int buffer) { op(TEX_BUFFER); i(target); i(internalFormat); i(buffer); }

    // ── Timer queries ─────────────────────────────────────────────────────────

    @Override public int glGenQuery() { throw refused("glGenQuery", CREATES); }
    @Override public void glBeginTimeElapsedQuery(int query) { op(BEGIN_TIME_QUERY); i(query); }
    @Override public void glEndTimeElapsedQuery() { op(END_TIME_QUERY); }
    @Override public void glQueryTimestamp(int query) { op(QUERY_TIMESTAMP); i(query); }
    @Override public boolean glIsQueryResultAvailable(int query) { return live.glIsQueryResultAvailable(query); }
    @Override public long glGetQueryResultNanos(int query) { return live.glGetQueryResultNanos(query); }
    @Override public void glDeleteQuery(int query) { op(DELETE_QUERY); i(query); }

    // ── Vertex arrays ─────────────────────────────────────────────────────────

    @Override public int glGenVertexArrays() { throw refused("glGenVertexArrays", CREATES); }
    @Override public void glBindVertexArray(int array) { op(BIND_VAO); i(array); }
    @Override public void glDeleteVertexArrays(int array) { op(DELETE_VAO); i(array); }
    @Override public void glEnableVertexAttribArray(int index) { op(ENABLE_ATTRIB); i(index); }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        op(ATTRIB_POINTER); i(index); i(size); i(type); z(normalized); i(stride); l(pointer);
    }

    @Override
    public void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        op(ATTRIB_IPOINTER); i(index); i(size); i(type); i(stride); l(pointer);
    }

    @Override public void glVertexAttribDivisor(int index, int divisor) { op(ATTRIB_DIVISOR); i(index); i(divisor); }

    // ── Textures ──────────────────────────────────────────────────────────────

    @Override public int glGenTextures() { throw refused("glGenTextures", CREATES); }
    @Override public void glBindTexture(int target, int texture) { op(BIND_TEXTURE); i(target); i(texture); }
    @Override public void glDeleteTextures(int texture) { op(DELETE_TEXTURES); i(texture); }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, ByteBuffer pixels) {
        op(TEX_IMAGE_2D_B); i(target); i(level); i(internalFormat); i(width); i(height); i(border); i(format); i(type); bytes(pixels);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, FloatBuffer pixels) {
        op(TEX_IMAGE_2D_F); i(target); i(level); i(internalFormat); i(width); i(height); i(border); i(format); i(type); floats(pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, ByteBuffer pixels) {
        op(TEX_SUB_IMAGE_2D_B); i(target); i(level); i(xOffset); i(yOffset); i(width); i(height); i(format); i(type); bytes(pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, FloatBuffer pixels) {
        op(TEX_SUB_IMAGE_2D_F); i(target); i(level); i(xOffset); i(yOffset); i(width); i(height); i(format); i(type); floats(pixels);
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, ByteBuffer pixels) {
        op(TEX_IMAGE_3D_B); i(target); i(level); i(internalFormat); i(width); i(height); i(depth); i(border); i(format); i(type);
        bytes(pixels);
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, FloatBuffer pixels) {
        op(TEX_IMAGE_3D_F); i(target); i(level); i(internalFormat); i(width); i(height); i(depth); i(border); i(format); i(type);
        floats(pixels);
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth,
                                int format, int type, ByteBuffer pixels) {
        op(TEX_SUB_IMAGE_3D_B); i(target); i(level); i(xOffset); i(yOffset); i(zOffset); i(width); i(height); i(depth);
        i(format); i(type); bytes(pixels);
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth,
                                int format, int type, FloatBuffer pixels) {
        op(TEX_SUB_IMAGE_3D_F); i(target); i(level); i(xOffset); i(yOffset); i(zOffset); i(width); i(height); i(depth);
        i(format); i(type); floats(pixels);
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth,
                                int format, int type, ShortBuffer pixels) {
        op(TEX_SUB_IMAGE_3D_S); i(target); i(level); i(xOffset); i(yOffset); i(zOffset); i(width); i(height); i(depth);
        i(format); i(type); shorts(pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type,
                                long unpackOffset) {
        op(TEX_SUB_IMAGE_2D_O); i(target); i(level); i(xOffset); i(yOffset); i(width); i(height); i(format); i(type);
        l(unpackOffset);
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height, int depth,
                                int format, int type, long unpackOffset) {
        op(TEX_SUB_IMAGE_3D_O); i(target); i(level); i(xOffset); i(yOffset); i(zOffset); i(width); i(height); i(depth);
        i(format); i(type); l(unpackOffset);
    }

    @Override public void glGenerateMipmap(int target) { op(GENERATE_MIPMAP); i(target); }
    @Override public void glActiveTexture(int texture) { op(ACTIVE_TEXTURE); i(texture); }
    @Override public void glTexParameteri(int target, int pname, int param) { op(TEX_PARAMETERI); i(target); i(pname); i(param); }

    @Override
    public void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        throw refused("glGetTexImage", READS);
    }

    // ── Draws ─────────────────────────────────────────────────────────────────

    @Override public void glDrawArrays(int mode, int first, int count) { op(DRAW_ARRAYS); i(mode); i(first); i(count); }
    @Override public void glDrawElements(int mode, int count, int type, long indices) { op(DRAW_ELEMENTS); i(mode); i(count); i(type); l(indices); }

    @Override
    public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        op(DRAW_ARRAYS_INSTANCED); i(mode); i(first); i(count); i(instanceCount);
    }

    @Override
    public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        op(DRAW_ELEMENTS_INSTANCED); i(mode); i(count); i(type); l(indices); i(instanceCount);
    }

    @Override
    public void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int instanceCount,
                                                  int baseVertex) {
        op(DRAW_ELEMENTS_INSTANCED_BASE_VERTEX); i(mode); i(count); i(type); l(indices); i(instanceCount); i(baseVertex);
    }

    @Override public void glDrawArraysIndirect(int mode, long offset) { op(DRAW_ARRAYS_INDIRECT); i(mode); l(offset); }

    @Override
    public void glDrawElementsIndirect(int mode, int type, long offset) {
        op(DRAW_ELEMENTS_INDIRECT); i(mode); i(type); l(offset);
    }

    @Override
    public void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) {
        op(MULTI_DRAW_ARRAYS_INDIRECT); i(mode); l(offset); i(drawCount); i(stride);
    }

    @Override
    public void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) {
        op(MULTI_DRAW_ELEMENTS_INDIRECT); i(mode); i(type); l(offset); i(drawCount); i(stride);
    }

    @Override
    public void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount, int stride) {
        op(MULTI_DRAW_ARRAYS_INDIRECT_COUNT); i(mode); l(offset); l(countOffset); i(maxDrawCount); i(stride);
    }

    @Override
    public void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maxDrawCount,
                                                 int stride) {
        op(MULTI_DRAW_ELEMENTS_INDIRECT_COUNT); i(mode); i(type); l(offset); l(countOffset); i(maxDrawCount); i(stride);
    }

    // ── Compute ───────────────────────────────────────────────────────────────

    @Override public void glDispatchCompute(int x, int y, int z) { op(DISPATCH_COMPUTE); i(x); i(y); i(z); }
    @Override public void glTransformFeedbackVaryings(int program, String[] varyings, int bufferMode) {
        throw refused("glTransformFeedbackVaryings", COMPILES);
    }
    @Override public void glBeginTransformFeedback(int primitiveMode) { op(BEGIN_TRANSFORM_FEEDBACK); i(primitiveMode); }
    @Override public void glEndTransformFeedback() { op(END_TRANSFORM_FEEDBACK); }
    @Override public void glDispatchComputeIndirect(long offset) { op(DISPATCH_COMPUTE_INDIRECT); l(offset); }
    @Override public void glMemoryBarrier(int barriers) { op(MEMORY_BARRIER); i(barriers); }

    @Override
    public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        op(BIND_IMAGE_TEXTURE); i(unit); i(texture); i(level); z(layered); i(layer); i(access); i(format);
    }

    /** Taped as named, so the backend it replays on decides what the barrier is. */
    @Override public void cgBufferBarrier(int buffer, int from, int to) { op(CG_BUFFER_BARRIER); i(buffer); i(from); i(to); }
    @Override public void cgImageBarrier(int texture, int from, int to) { op(CG_IMAGE_BARRIER); i(texture); i(from); i(to); }
    /** In order, taped as nothing: a replay has no point to wait for, so what they bracket replays where it stands. */
    @Override public void cgBeginAsync() {}
    @Override public long cgEndAsync() { return 0L; }
    @Override public void cgWaitAsync(long point) {}
    @Override public void cgFillBuffer(int buffer, long offset, long size, int value) {
        op(CG_FILL_BUFFER); i(buffer); l(offset); l(size); i(value);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Override public void glEnable(int cap) { op(ENABLE); i(cap); }
    @Override public void glDisable(int cap) { op(DISABLE); i(cap); }
    @Override public void glBlendFunc(int sfactor, int dfactor) { op(BLEND_FUNC); i(sfactor); i(dfactor); }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        op(BLEND_FUNC_SEPARATE); i(srcRGB); i(dstRGB); i(srcAlpha); i(dstAlpha);
    }

    @Override public void glDepthMask(boolean flag) { op(DEPTH_MASK); z(flag); }
    @Override public void glCullFace(int mode) { op(CULL_FACE); i(mode); }
    @Override public void glViewport(int x, int y, int width, int height) { op(VIEWPORT); i(x); i(y); i(width); i(height); }
    @Override public void glScissor(int x, int y, int width, int height) { op(SCISSOR); i(x); i(y); i(width); i(height); }
    @Override public void glLineWidth(float width) { op(LINE_WIDTH); f(width); }
    @Override public void glPolygonMode(int face, int mode) { op(POLYGON_MODE); i(face); i(mode); }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        op(COLOR_MASK); z(red); z(green); z(blue); z(alpha);
    }

    @Override public void glStencilFunc(int func, int ref, int mask) { op(STENCIL_FUNC); i(func); i(ref); i(mask); }
    @Override public void glStencilOp(int sfail, int dpfail, int dppass) { op(STENCIL_OP); i(sfail); i(dpfail); i(dppass); }
    @Override public void glAlphaFunc(int func, float ref) { op(ALPHA_FUNC); i(func); f(ref); }
    @Override public void glClear(int mask) { op(CLEAR); i(mask); }
    @Override public void glClearDepth(double depth) { op(CLEAR_DEPTH); d(depth); }
    @Override public void glClearColor(float r, float g, float b, float a) { op(CLEAR_COLOR); f(r); f(g); f(b); f(a); }
    @Override public void glClearStencil(int s) { op(CLEAR_STENCIL); i(s); }
    @Override public void glDepthFunc(int func) { op(DEPTH_FUNC); i(func); }
    @Override public void glStencilMask(int mask) { op(STENCIL_MASK); i(mask); }
    @Override public void glBlendEquationSeparate(int modeRGB, int modeAlpha) { op(BLEND_EQUATION_SEPARATE); i(modeRGB); i(modeAlpha); }

    @Override
    public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) {
        op(COLOR_MASKI); i(buf); z(r); z(g); z(b); z(a);
    }

    @Override public void glFrontFace(int mode) { op(FRONT_FACE); i(mode); }
    @Override public void glPolygonOffset(float factor, float units) { op(POLYGON_OFFSET); f(factor); f(units); }
    @Override public void glPointSize(float size) { op(POINT_SIZE); f(size); }
    @Override public void glDrawBuffer(int mode) { op(DRAW_BUFFER); i(mode); }
    @Override public void glReadBuffer(int mode) { op(READ_BUFFER); i(mode); }

    @Override
    public void glPixelStorei(int pname, int param) {
        op(PIXEL_STOREI); i(pname); i(param);
        for (int k = 0; k < pixelStoreCount; k++) {
            if (pixelStoreNames[k] == pname) { pixelStoreValues[k] = param; return; }
        }
        if (pixelStoreCount < pixelStoreNames.length) {
            pixelStoreNames[pixelStoreCount] = pname;
            pixelStoreValues[pixelStoreCount++] = param;
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * What the recording set comes back as set; anything else from the live context, which nothing recorded has
     * reached yet, so it still holds the state a replay starts from.
     */
    @Override
    public int glGetInteger(int pname) {
        if (isPixelStore(pname)) {
            for (int k = 0; k < pixelStoreCount; k++) if (pixelStoreNames[k] == pname) return pixelStoreValues[k];
            return live.glGetInteger(pname);
        }
        return manager.recordedSets(pname) ? manager.recordedInteger(pname) : live.glGetInteger(pname);
    }

    private static boolean isPixelStore(int pname) {
        return (pname >= 0x0CF0 && pname <= 0x0CF5)      // GL_UNPACK_SWAP_BYTES .. GL_UNPACK_ALIGNMENT
            || (pname >= 0x0D00 && pname <= 0x0D05)      // GL_PACK_*
            || (pname >= 0x806B && pname <= 0x806E);     // GL_PACK/UNPACK_SKIP_IMAGES, _IMAGE_HEIGHT
    }

    @Override public void glReadPixels(int x, int y, int w, int h, int format, int type, ByteBuffer pixels) { throw refused("glReadPixels", READS); }
    @Override public void glReadPixels(int x, int y, int w, int h, int format, int type, long packOffset) { throw refused("glReadPixels", READS); }
    @Override public void glGetInteger(int pname, IntBuffer params) { throw refused("glGetIntegerv", "no recording answers a multi-value query"); }
    @Override public boolean glGetBoolean(int pname) { return manager.recordedSets(pname) ? manager.recordedBoolean(pname) : live.glGetBoolean(pname); }
    @Override public String glGetString(int name) { return live.glGetString(name); }
    @Override public String glGetStringi(int name, int index) { return live.glGetStringi(name, index); }
    @Override public int glGetIntegeri(int target, int index) { return live.glGetIntegeri(target, index); }
    @Override public void glGetBoolean(int pname, ByteBuffer params) { throw refused("glGetBooleanv", "no recording answers a multi-value query"); }
    @Override public void glGetFloat(int pname, FloatBuffer params) { throw refused("glGetFloatv", "no recording answers a float query"); }
    @Override public float glGetFloat(int pname) { throw refused("glGetFloat", "no recording answers a float query"); }

    // ── Samplers, mapping, sync ───────────────────────────────────────────────

    @Override public void glBindSampler(int unit, int sampler) { op(BIND_SAMPLER); i(unit); i(sampler); }

    /** Hands out scratch memory; what was written is taped at the flush or unmap that follows. */
    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        if (pendingMapTarget != -1) throw refused("glMapBufferRange", "a recording maps one buffer at a time");
        if (length > mapScratch.capacity()) mapScratch = ByteBuffer.allocateDirect((int) Math.max(length, mapScratch.capacity() * 2L)).order(ByteOrder.nativeOrder());
        pendingMapTarget = target;
        pendingMapOffset = offset;
        pendingMapLength = length;
        pendingMapAccess = access;
        mapScratch.clear().limit((int) length);
        return mapScratch;
    }

    private void tapePendingMap() {
        if (pendingMapTarget == -1) return;
        int n = (int) pendingMapLength;
        op(MAP_WRITE); i(pendingMapTarget); l(pendingMapOffset); l(pendingMapLength); i(pendingMapAccess);
        ensure(n);
        for (int k = 0; k < n; k++) tape.put(written + k, mapScratch.get(k));
        written += n;
        pendingMapTarget = -1;
    }

    @Override public boolean glUnmapBuffer(int target) { tapePendingMap(); op(UNMAP); i(target); return true; }

    @Override
    public void glFlushMappedBufferRange(int target, long offset, long length) {
        tapePendingMap();
        op(FLUSH_MAPPED); i(target); l(offset); l(length);
    }

    @Override public void glBufferStorage(int target, long size, int flags) { op(BUFFER_STORAGE); i(target); l(size); i(flags); }
    @Override public long glFenceSync(int condition, int flags) { throw refused("glFenceSync", "a fence marks submitted work, and none is submitted until replay"); }

    /** A wait on an earlier frame's fence, which nothing recorded can affect. */
    @Override public int glClientWaitSync(long sync, int flags, long timeout) { return live.glClientWaitSync(sync, flags, timeout); }

    @Override public void glDeleteSync(long sync) { op(DELETE_SYNC); l(sync); }
    @Override public int glGetError() { return 0; }
    @Override public boolean isContextCurrent() { return live.isContextCurrent(); }

    // ── Renderbuffers ─────────────────────────────────────────────────────────

    @Override public int glGenRenderbuffers() { throw refused("glGenRenderbuffers", CREATES); }
    @Override public void glDeleteRenderbuffers(int rbo) { op(DELETE_RBO); i(rbo); }
    @Override public void glBindRenderbuffer(int target, int renderbuffer) { op(BIND_RBO); i(target); i(renderbuffer); }

    @Override
    public void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        op(RBO_STORAGE); i(target); i(internalFormat); i(width); i(height);
    }

    @Override
    public void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) {
        op(RBO_STORAGE_MS); i(target); i(samples); i(internalFormat); i(width); i(height);
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) {
        op(FRAMEBUFFER_RBO); i(target); i(attachment); i(renderbufferTarget); i(renderbuffer);
    }

    @Override
    public void glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height,
                                        boolean fixedSampleLocations) {
        op(TEX_IMAGE_2D_MS); i(target); i(samples); i(internalFormat); i(width); i(height); z(fixedSampleLocations);
    }

    // ── Shaders, additional ───────────────────────────────────────────────────

    @Override public void glDetachShader(int program, int shader) { throw refused("glDetachShader", COMPILES); }
    @Override public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) { throw refused("glGetAttachedShaders", COMPILES); }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        throw refused("glGetActiveUniform", COMPILES);
    }

    @Override public void glUniform1(int location, FloatBuffer values) { op(UNIFORM1_FV); i(location); floats(values); }
    @Override public void glUniform1(int location, IntBuffer values) { op(UNIFORM1_IV); i(location); ints(values); }
    @Override public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) { op(UNIFORM_MATRIX3); i(location); z(transpose); floats(value); }
    @Override public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) { op(UNIFORM_MATRIX4); i(location); z(transpose); floats(value); }

    // ── Host coexistence ──────────────────────────────────────────────────────

    @Override public int importHostTexture(Object hostHandle) { return live.importHostTexture(hostHandle); }
    @Override public void toHost() { throw refused("toHost", "a recording is replayed inside a CgGL.fromHost bracket, never across one"); }
    @Override public void fromHost() { throw refused("fromHost", "a recording is replayed inside a CgGL.fromHost bracket, never across one"); }
    @Override public boolean ownedByCurrentThread() { return live.ownedByCurrentThread(); }
}
