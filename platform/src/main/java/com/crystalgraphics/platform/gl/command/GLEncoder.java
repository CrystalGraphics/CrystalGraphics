package com.crystalgraphics.platform.gl.command;

import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.binding.GLBindingLayout;
import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.binding.GLBindingSet;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.gl.pipeline.GLComputePipeline;
import com.crystalgraphics.platform.gl.pipeline.GLGraphicsPipeline;
import com.crystalgraphics.platform.gl.resource.GLBuffer;
import com.crystalgraphics.platform.gl.resource.GLSampler;
import com.crystalgraphics.platform.gl.resource.GLTexture;
import com.crystalgraphics.platform.gl.sync.GLTimerQuery;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * The GL device's command list. GL runs a command as it is called, so recording is executing, as Unreal's OpenGL RHI
 * runs its command context: a pipeline and its sets are pending until a draw or dispatch commits them against what
 * the context last had (`OpenGLCommands.cpp` {@code SetupDraw}), and transitions are memory barriers only where a
 * shader's storage write needs one.
 */
public final class GLEncoder implements CgCommandList {

    /** Bytes of push constants each frame's slice of the ring holds before draws fall back to a buffer update. */
    private static final int RING_SLICE = 1 << 20;

    private final CgGLDevice device;
    private final CgGLDriver gl;
    private final GLFramebuffers framebuffers;
    private final Pass pass = new Pass();
    private final Compute compute = new Compute();
    private boolean inPass;

    // the bindings a draw or dispatch commits
    private final GLBindingSet[] sets = new GLBindingSet[4];
    private final CgBindings pushed = new CgBindings();
    private boolean pushedSet;
    private boolean bindingsDirty;
    private final ByteBuffer push = ByteBuffer.allocateDirect(CgPipelineLayout.MAX_PUSH_CONSTANT_BYTES)
            .order(ByteOrder.nativeOrder());
    private boolean pushDirty;
    private final GLBuffer ring, spill;
    private final int alignment;
    private int ringStart, ringAt;

    private final ByteBuffer word = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
    private final CgTextureView[] attachments = new CgTextureView[8];
    private final int[] discard = new int[9];

    public GLEncoder(CgGLDevice device, CgGLDriver gl, GLFramebuffers framebuffers, int uniformAlignment) {
        this.device = device;
        this.gl = gl;
        this.framebuffers = framebuffers;
        this.alignment = Math.max(4, uniformAlignment);
        EnumSet<CgGpuBuffer.Usage> uniform = EnumSet.of(CgGpuBuffer.Usage.UNIFORM);
        this.ring = new GLBuffer(gl, new CgGpuBuffer.Desc("push constants", (long) RING_SLICE * CgGLDevice.FRAMES_IN_FLIGHT,
                uniform, true, false, true));
        this.spill = new GLBuffer(gl, new CgGpuBuffer.Desc("push constants (spill)",
                CgPipelineLayout.MAX_PUSH_CONSTANT_BYTES, uniform, false));
    }

    /** The frame ended: push constants go to the next frame's slice of the ring, which its fence has freed. */
    public void endFrame(long nextFrame) {
        if (inPass) throw new IllegalStateException("a pass is open at the frame's end");
        ringStart = ringAt = (int) (nextFrame % CgGLDevice.FRAMES_IN_FLIGHT) * RING_SLICE;
    }

    public boolean inPass() { return inPass; }

    private void outsidePass(String what) {
        if (inPass) throw new IllegalStateException(what + " inside a pass");
    }

    // ── bindings ──────────────────────────────────────────────────────────────

    private void resetBindings() {
        for (int i = 0; i < sets.length; i++) sets[i] = null;
        pushed.clear();
        pushedSet = false;
        bindingsDirty = true;
        pushDirty = true;
    }

    private void setBindingSet(int set, CgBindingSet bindings) {
        sets[set] = (GLBindingSet) bindings;
        bindingsDirty = true;
    }

    private void pushBindings(CgBindings bindings) {
        GLBindingSet.copy(bindings, pushed);
        pushedSet = true;
        bindingsDirty = true;
    }

    private void pushConstant(int offset, int value) {
        push.putInt(offset, value);
        pushDirty = true;
    }

    private void pushConstants(int offset, ByteBuffer data) {
        int at = data.position();
        for (int i = 0; i < data.remaining(); i++) push.put(offset + i, data.get(at + i));
        pushDirty = true;
    }

    /** Binds the sets and push constants {@code layout} reads, at {@code points}: what changed since the last commit. */
    private void commitBindings(CgPipelineLayout layout, GLBindingPoints points) {
        if (bindingsDirty) {
            int pushSet = layout.pushSet();
            for (int s = 0; s < layout.sets().size(); s++) {
                if (s == pushSet) {
                    if (pushedSet) bind(s, pushed, points);
                } else if (sets[s] != null) {
                    GLBindingSet set = sets[s];
                    if (set.layout() instanceof GLBindingLayout l && l.heap) {
                        GLBuffer heap = device.heapBuffer();
                        gl.glBindBufferRange(GL_SHADER_STORAGE_BUFFER, points.point(s, 0, GLBindingPoints.STORAGE), heap.name,
                                0, heap.size());
                    } else {
                        bind(s, set.contents, points);
                    }
                }
            }
            bindingsDirty = false;
        }
        int bytes = layout.pushConstantBytes();
        if (bytes > 0 && pushDirty) {
            int at = (ringAt + alignment - 1) / alignment * alignment;
            if (at + bytes <= ringStart + RING_SLICE) {
                ByteBuffer m = ring.mapped();
                for (int i = 0; i < bytes; i += 4) m.putInt(at + i, push.getInt(i));
                gl.glBindBufferRange(GL_UNIFORM_BUFFER, points.pushConstants(), ring.name, at, bytes);
                ringAt = at + bytes;
            } else {
                gl.glNamedBufferSubData(spill.name, 0, push.duplicate().position(0).limit(bytes));
                gl.glBindBufferRange(GL_UNIFORM_BUFFER, points.pushConstants(), spill.name, 0, bytes);
            }
            pushDirty = false;
        }
    }

    private void bind(int set, CgBindings b, GLBindingPoints points) {
        for (int i = 0; i < b.count(); i++) {
            CgBindingLayout.Type type = b.type(i);
            int point = points.find(set, b.binding(i), GLBindingPoints.classOf(type));
            if (point < 0) continue;
            switch (type) {
                case UNIFORM_BUFFER -> gl.glBindBufferRange(GL_UNIFORM_BUFFER, point, ((GLBuffer) b.buffer(i)).name,
                        b.offset(i), b.size(i));
                case STORAGE_BUFFER -> gl.glBindBufferRange(GL_SHADER_STORAGE_BUFFER, point, ((GLBuffer) b.buffer(i)).name,
                        b.offset(i), b.size(i));
                case TEXEL_BUFFER -> {
                    gl.glBindTextureUnit(point, ((GLBuffer) b.buffer(i)).texel(gl, b.offset(i), b.size(i), b.texelFormat(i)));
                    gl.glBindSampler(point, 0);
                }
                case SAMPLED_TEXTURE -> {
                    CgTextureView v = b.view(i);
                    gl.glBindTextureUnit(point, ((GLTexture) v.texture()).sampled(gl, v));
                    gl.glBindSampler(point, b.sampler(i) == null ? 0 : ((GLSampler) b.sampler(i)).name);
                }
                case STORAGE_IMAGE -> {
                    CgTextureView v = b.view(i);
                    GLTexture t = (GLTexture) v.texture();
                    boolean layered = t.desc().kind() == CgGpuTexture.Kind.D3 || v.layers() > 1;
                    gl.glBindImageTexture(point, t.name, v.baseMip(), layered, v.baseLayer(), GL_READ_WRITE,
                            GLFormats.internal(t.desc().format()));
                }
            }
        }
    }

    // ── barriers ──────────────────────────────────────────────────────────────

    /** The {@code glMemoryBarrier} bits a reader in {@code to} needs after {@code from}; 0 unless a shader stored. */
    static int barrierBits(int from, int to) {
        if ((from & CgAccess.COMPUTE_WRITE) == 0) return 0;
        int bits = 0;
        if ((to & CgAccess.STORAGE) != 0) bits |= GL_SHADER_STORAGE_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
        if ((to & CgAccess.UNIFORM_READ) != 0) bits |= GL_UNIFORM_BARRIER_BIT;
        if ((to & CgAccess.SAMPLED_READ) != 0) bits |= GL_TEXTURE_FETCH_BARRIER_BIT;
        if ((to & CgAccess.VERTEX_INPUT) != 0) bits |= GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
        if ((to & CgAccess.INDEX_INPUT) != 0) bits |= GL_ELEMENT_ARRAY_BARRIER_BIT;
        if ((to & CgAccess.INDIRECT) != 0) bits |= GL_COMMAND_BARRIER_BIT;
        if ((to & (CgAccess.COPY_READ | CgAccess.COPY_WRITE)) != 0)
            bits |= GL_BUFFER_UPDATE_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT;
        if ((to & CgAccess.HOST_READ) != 0) bits |= GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
        if ((to & (CgAccess.COLOR_WRITE | CgAccess.DEPTH_READ | CgAccess.DEPTH_WRITE)) != 0) bits |= GL_FRAMEBUFFER_BARRIER_BIT;
        return bits;
    }

    private void barrier(int from, int to) {
        int bits = barrierBits(from, to);
        if (bits != 0) gl.glMemoryBarrier(bits);
    }

    @Override public void bufferBarrier(CgGpuBuffer buffer, int from, int to) { barrier(from, to); }

    @Override public void imageBarrier(CgGpuTexture texture, int from, int to) { barrier(from, to); }

    @Override public void memoryBarrier(int from, int to) { barrier(from, to); }

    @Override
    public void transition(CgTransition t) {
        outsidePass("transition");
        int bits = 0;
        for (int i = 0; i < t.count(); i++) bits |= barrierBits(t.from(i), t.to(i));
        if (bits != 0) gl.glMemoryBarrier(bits);
    }

    /** GL has no split barrier: the moves are made at {@link #endTransitions}. */
    @Override public void beginTransitions(CgTransition t) { outsidePass("beginTransitions"); }

    @Override public void endTransitions(CgTransition t) { transition(t); }

    /** GL puts no barrier between dispatches unasked, so there is nothing to lift. */
    @Override public void beginStorageOverlap() { outsidePass("beginStorageOverlap"); }

    @Override public void endStorageOverlap() { outsidePass("endStorageOverlap"); }

    // ── copies ────────────────────────────────────────────────────────────────

    @Override
    public void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data) {
        gl.glNamedBufferSubData(((GLBuffer) dst).name, dstOffset, data);
    }

    @Override
    public void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size) {
        outsidePass("copyBuffer");
        gl.glCopyNamedBufferSubData(((GLBuffer) src).name, ((GLBuffer) dst).name, srcOffset, dstOffset, size);
    }

    @Override
    public void fillBuffer(CgGpuBuffer dst, long dstOffset, long size, int value) {
        outsidePass("fillBuffer");
        word.putInt(0, value);
        gl.glClearNamedBufferSubData(((GLBuffer) dst).name, GL_R32UI, dstOffset, size, GL_RED_INTEGER, GL_UNSIGNED_INT, word);
    }

    @Override
    public void writeTexture(CgGpuTexture dst, CgTextureRegion r, ByteBuffer data) {
        GLTexture t = (GLTexture) dst;
        CgFormat f = t.desc().format();
        if (t.target == GL_TEXTURE_2D) {
            if (f.compressed()) gl.glCompressedTextureSubImage2D(t.name, r.mip(), r.x(), r.y(), r.width(), r.height(),
                    GLFormats.internal(f), data);
            else gl.glTextureSubImage2D(t.name, r.mip(), r.x(), r.y(), r.width(), r.height(), GLFormats.format(f),
                    GLFormats.type(f), data);
        } else if (f.compressed()) {
            gl.glCompressedTextureSubImage3D(t.name, r.mip(), r.x(), r.y(), r.z(), r.width(), r.height(), r.depth(),
                    GLFormats.internal(f), data);
        } else {
            gl.glTextureSubImage3D(t.name, r.mip(), r.x(), r.y(), r.z(), r.width(), r.height(), r.depth(),
                    GLFormats.format(f), GLFormats.type(f), data);
        }
    }

    @Override
    public void copyBufferToTexture(CgGpuBuffer src, long srcOffset, CgGpuTexture dst, CgTextureRegion r) {
        outsidePass("copyBufferToTexture");
        GLTexture t = (GLTexture) dst;
        CgFormat f = t.desc().format();
        gl.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, ((GLBuffer) src).name);
        if (t.target == GL_TEXTURE_2D) gl.glTextureSubImage2D(t.name, r.mip(), r.x(), r.y(), r.width(), r.height(),
                GLFormats.format(f), GLFormats.type(f), srcOffset);
        else gl.glTextureSubImage3D(t.name, r.mip(), r.x(), r.y(), r.z(), r.width(), r.height(), r.depth(),
                GLFormats.format(f), GLFormats.type(f), srcOffset);
        gl.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }

    @Override
    public void copyTexture(CgGpuTexture src, CgTextureRegion s, CgGpuTexture dst, CgTextureRegion d) {
        outsidePass("copyTexture");
        GLTexture a = (GLTexture) src, b = (GLTexture) dst;
        gl.glCopyImageSubData(a.name, a.target, s.mip(), s.x(), s.y(), s.z(), b.name, b.target, d.mip(), d.x(), d.y(), d.z(),
                s.width(), s.height(), s.depth());
    }

    @Override
    public void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1, CgTextureView dst, int dx0, int dy0, int dx1,
                     int dy1, CgGpuSampler.Filter filter) {
        outsidePass("blit");
        CgFormat f = src.texture().desc().format();
        int mask = f.aspect() == CgFormat.Aspect.COLOR ? GL_COLOR_BUFFER_BIT
                : (f.hasDepth() ? GL_DEPTH_BUFFER_BIT : 0) | (f.hasStencil() ? GL_STENCIL_BUFFER_BIT : 0);
        int read = single(src), draw = single(dst);
        gl.glBlitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask,
                filter == CgGpuSampler.Filter.LINEAR && mask == GL_COLOR_BUFFER_BIT ? GL_LINEAR : GL_NEAREST);
    }

    private int single(CgTextureView v) {
        CgTextureView one = CgTextureView.attachment(v.texture(), v.baseMip(), v.baseLayer());
        if (v.texture().desc().format().aspect() == CgFormat.Aspect.COLOR) {
            attachments[0] = one;
            return framebuffers.get(attachments, 1, null);
        }
        return framebuffers.get(attachments, 0, one);
    }

    @Override
    public void resolve(CgTextureView src, CgTextureView dst) {
        blit(src, 0, 0, src.width(), src.height(), dst, 0, 0, dst.width(), dst.height(), CgGpuSampler.Filter.NEAREST);
    }

    @Override
    public void generateMipmaps(CgGpuTexture texture) {
        outsidePass("generateMipmaps");
        gl.glGenerateTextureMipmap(((GLTexture) texture).name);
    }

    @Override
    public void readTexture(CgGpuTexture src, CgTextureRegion r, ByteBuffer out) {
        outsidePass("readTexture");
        GLTexture t = (GLTexture) src;
        CgFormat f = t.desc().format();
        gl.glGetTextureSubImage(t.name, r.mip(), r.x(), r.y(), r.z(), r.width(), r.height(), r.depth(), GLFormats.format(f),
                GLFormats.type(f), out);
    }

    @Override
    public void readBuffer(CgGpuBuffer src, long srcOffset, ByteBuffer out) {
        outsidePass("readBuffer");
        gl.glGetNamedBufferSubData(((GLBuffer) src).name, srcOffset, out);
    }

    @Override
    public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion r, CgGpuBuffer dst, long dstOffset) {
        outsidePass("copyTextureToBuffer");
        GLTexture t = (GLTexture) src;
        GLBuffer b = (GLBuffer) dst;
        CgFormat f = t.desc().format();
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, b.name);
        gl.glGetTextureSubImage(t.name, r.mip(), r.x(), r.y(), r.z(), r.width(), r.height(), r.depth(), GLFormats.format(f),
                GLFormats.type(f), (int) Math.min(Integer.MAX_VALUE, b.size() - dstOffset), dstOffset);
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }

    // ── queues, waits and timers ──────────────────────────────────────────────

    /** One queue: async work runs in order. */
    @Override public void beginAsync() { outsidePass("beginAsync"); }

    @Override public long endAsync() { return 0; }

    @Override public void waitAsync(long point) { outsidePass("waitAsync"); }

    @Override public void beginTransfer() { outsidePass("beginTransfer"); }

    @Override public void endTransfer() { outsidePass("endTransfer"); }

    @Override
    public void finish() {
        outsidePass("finish");
        gl.glFinish();
    }

    @Override public void beginTimer(CgTimerQuery query) { ((GLTimerQuery) query).begin(); }

    @Override public void endTimer(CgTimerQuery query) { ((GLTimerQuery) query).end(device.frameIndex(), false); }

    @Override public void timestamp(CgTimerQuery query) { ((GLTimerQuery) query).end(device.frameIndex(), true); }

    // ── passes ────────────────────────────────────────────────────────────────

    @Override
    public CgRenderPass beginPass(CgPassDesc desc) {
        outsidePass("beginPass");
        inPass = true;
        pass.begin(desc);
        return pass;
    }

    @Override
    public CgComputePass beginCompute(String label) {
        outsidePass("beginCompute");
        inPass = true;
        gl.glPushDebugGroup(label);
        resetBindings();
        compute.pipeline = null;
        return compute;
    }

    /** What the context last had of a pipeline's state, so a draw sets only what differs. */
    private CgGraphicsPipeline committed;

    /** The context changed under the cache (a clear, a blit, a host): the next draw sets everything. */
    public void invalidate() {
        committed = null;
    }

    private final class Pass implements CgRenderPass {

        private CgPassDesc desc;
        private int framebuffer;
        private boolean readOnlyDepth;
        private GLGraphicsPipeline pipeline;
        private final GLBuffer[] vertexBuffers = new GLBuffer[16];
        private final long[] vertexOffsets = new long[16];
        private int vertexDirty;
        private GLBuffer indexBuffer;
        private long indexOffset;
        private boolean wideIndices, indexDirty;
        private int stencilReference;
        private int scissorX, scissorY, scissorW, scissorH;

        void begin(CgPassDesc d) {
            desc = d;
            gl.glPushDebugGroup(d.label());
            int count = d.colors().size();
            for (int i = 0; i < count; i++) attachments[i] = d.colors().get(i).view();
            CgTextureView depth = d.depth() == null ? null : d.depth().view();
            readOnlyDepth = d.depth() != null && d.depth().readOnly();
            committed = null;   // a pipeline's depth and stencil masks depend on the pass's read-only depth
            framebuffer = framebuffers.get(attachments, count, depth);
            gl.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            loadActions(d, count);
            resetBindings();
            pipeline = null;
            vertexDirty = 0;
            indexBuffer = null;
            stencilReference = 0;
            gl.glViewportIndexedf(0, 0, 0, d.width(), d.height());
            gl.glDepthRangef(0, 1);
            setScissor(0, 0, d.width(), d.height());
            gl.glEnable(GL_SCISSOR_TEST);
        }

        private void loadActions(CgPassDesc d, int count) {
            int invalidated = 0;
            for (int i = 0; i < count; i++) {
                CgPassDesc.Color c = d.colors().get(i);
                if (c.load() == CgPassDesc.LoadOp.CLEAR) {
                    unmasked();
                    clear(framebuffer, i, c.view().texture().desc().format(), c.r(), c.g(), c.b(), c.a());
                } else if (c.load() == CgPassDesc.LoadOp.DONT_CARE) {
                    discard[invalidated++] = GL_COLOR_ATTACHMENT0 + i;
                }
            }
            CgPassDesc.Depth z = d.depth();
            if (z != null) {
                CgFormat f = z.view().texture().desc().format();
                boolean depth = f.hasDepth() && z.depthLoad() == CgPassDesc.LoadOp.CLEAR;
                boolean stencil = f.hasStencil() && z.stencilLoad() == CgPassDesc.LoadOp.CLEAR;
                if (depth || stencil) unmasked();
                if (depth && stencil) gl.glClearNamedFramebufferfi(framebuffer, GL_DEPTH_STENCIL, 0, z.clearDepth(), z.clearStencil());
                else if (depth) gl.glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, z.clearDepth(), 0, 0, 0);
                else if (stencil) gl.glClearNamedFramebufferiv(framebuffer, GL_STENCIL, 0, z.clearStencil(), 0, 0, 0);
                if (f.hasDepth() && z.depthLoad() == CgPassDesc.LoadOp.DONT_CARE && !z.readOnly()) {
                    discard[invalidated++] = GLFormats.attachment(f, 0);
                }
            }
            if (invalidated > 0) gl.glInvalidateNamedFramebufferData(framebuffer, discard, invalidated);
        }

        /** Masks and scissor off for a clear, which both limit; the next draw restores its pipeline's. */
        private void unmasked() {
            gl.glDisable(GL_SCISSOR_TEST);
            for (int i = 0; i < 8; i++) gl.glColorMaski(i, true, true, true, true);
            gl.glDepthMask(true);
            gl.glStencilMaskSeparate(GL_FRONT_AND_BACK, 0xFF);
            committed = null;
        }

        private void clear(int fb, int attachment, CgFormat f, float r, float g, float b, float a) {
            if (f.numeric() == CgFormat.Numeric.FLOAT) gl.glClearNamedFramebufferfv(fb, GL_COLOR, attachment, r, g, b, a);
            else if (f.name().contains("UINT")) gl.glClearNamedFramebufferuiv(fb, GL_COLOR, attachment, (int) r, (int) g,
                    (int) b, (int) a);
            else gl.glClearNamedFramebufferiv(fb, GL_COLOR, attachment, (int) r, (int) g, (int) b, (int) a);
        }

        @Override
        public void setPipeline(CgGraphicsPipeline p) {
            GLGraphicsPipeline next = (GLGraphicsPipeline) p;
            if (next == pipeline) return;
            pipeline = next;
            bindingsDirty = true;
            pushDirty = true;
            vertexDirty = -1;
            indexDirty = true;
        }

        @Override public void pushBindings(CgBindings bindings) { GLEncoder.this.pushBindings(bindings); }

        @Override public void setBindingSet(int set, CgBindingSet bindings) { GLEncoder.this.setBindingSet(set, bindings); }

        @Override public void pushConstants(int offset, int value) { pushConstant(offset, value); }

        @Override public void pushConstants(int offset, ByteBuffer data) { GLEncoder.this.pushConstants(offset, data); }

        @Override
        public void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset) {
            vertexBuffers[binding] = (GLBuffer) buffer;
            vertexOffsets[binding] = offset;
            vertexDirty |= 1 << binding;
        }

        @Override
        public void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide) {
            indexBuffer = (GLBuffer) buffer;
            indexOffset = offset;
            wideIndices = wide;
            indexDirty = true;
        }

        @Override
        public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
            gl.glViewportIndexedf(0, x, y, width, height);
            gl.glDepthRangef(minDepth, maxDepth);
        }

        @Override
        public void setScissor(int x, int y, int width, int height) {
            scissorX = x;
            scissorY = y;
            scissorW = width;
            scissorH = height;
            gl.glScissor(x, y, width, height);
        }

        @Override public void setDepthBias(float constant, float slope) { gl.glPolygonOffset(slope, constant); }

        @Override
        public void setStencilReference(int reference) {
            stencilReference = reference;
            if (pipeline != null && committed == pipeline) applyStencil(pipeline.desc().depthStencil());
        }

        @Override
        public void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height) {
            unmasked();
            gl.glScissor(x, y, width, height);
            gl.glEnable(GL_SCISSOR_TEST);
            clear(framebuffer, attachment, desc.colors().get(attachment).view().texture().desc().format(), r, g, b, a);
            gl.glScissor(scissorX, scissorY, scissorW, scissorH);
        }

        @Override
        public void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil, int x, int y,
                                      int width, int height) {
            unmasked();
            gl.glScissor(x, y, width, height);
            gl.glEnable(GL_SCISSOR_TEST);
            if (depth && stencil) gl.glClearNamedFramebufferfi(framebuffer, GL_DEPTH_STENCIL, 0, clearDepth, clearStencil);
            else if (depth) gl.glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, clearDepth, 0, 0, 0);
            else if (stencil) gl.glClearNamedFramebufferiv(framebuffer, GL_STENCIL, 0, clearStencil, 0, 0, 0);
            gl.glScissor(scissorX, scissorY, scissorW, scissorH);
        }

        /** Unreal's {@code SetupDraw}: the pending pipeline's state, then its bindings, then its vertex input. */
        private void setupDraw() {
            GLGraphicsPipeline p = pipeline;
            if (p == null) throw new IllegalStateException(desc.label() + ": a draw with no pipeline");
            if (committed != p) {
                CgGraphicsPipelineDesc d = p.desc();
                gl.glUseProgram(p.program(gl));
                gl.glBindVertexArray(p.vao);
                applyRaster(d.raster());
                applyDepthStencil(d.depthStencil());
                applyBlend(d);
                gl.glEnable(GL_SCISSOR_TEST);
                committed = p;
            }
            commitBindings(p.desc().layout(), p.points);
            if (vertexDirty != 0) {
                // A buffer the pipeline declares no stream for is unread, as Vulkan leaves it (a vertex stage pulling
                // its own vertices declares none)
                int streams = p.desc().vertexBuffers().size();
                for (int b = 0; b < Math.min(streams, vertexBuffers.length); b++) {
                    if ((vertexDirty & (1 << b)) == 0 || vertexBuffers[b] == null) continue;
                    gl.glVertexArrayVertexBuffer(p.vao, b, vertexBuffers[b].name, vertexOffsets[b], p.stride(b));
                }
                vertexDirty = 0;
            }
            if (indexDirty && indexBuffer != null) {
                gl.glVertexArrayElementBuffer(p.vao, indexBuffer.name);
                indexDirty = false;
            }
        }

        private void applyRaster(CgGraphicsPipelineDesc.Raster r) {
            if (r.cull() == CgGraphicsPipelineDesc.CullMode.NONE) gl.glDisable(GL_CULL_FACE);
            else {
                gl.glEnable(GL_CULL_FACE);
                gl.glCullFace(switch (r.cull()) {
                    case FRONT -> GL_FRONT;
                    case BACK -> GL_BACK;
                    default -> GL_FRONT_AND_BACK;
                });
            }
            gl.glFrontFace(r.frontFace() == CgGraphicsPipelineDesc.FrontFace.CCW ? GL_CCW : GL_CW);
            gl.glPolygonMode(GL_FRONT_AND_BACK, switch (r.polygonMode()) {
                case FILL -> GL_FILL;
                case LINE -> GL_LINE;
                case POINT -> GL_POINT;
            });
            if (r.depthBias()) {
                gl.glEnable(GL_POLYGON_OFFSET_FILL);
                gl.glEnable(GL_POLYGON_OFFSET_LINE);
                gl.glEnable(GL_POLYGON_OFFSET_POINT);
            } else {
                gl.glDisable(GL_POLYGON_OFFSET_FILL);
                gl.glDisable(GL_POLYGON_OFFSET_LINE);
                gl.glDisable(GL_POLYGON_OFFSET_POINT);
            }
        }

        private void applyDepthStencil(CgGraphicsPipelineDesc.DepthStencil ds) {
            if (ds.depthTest()) {
                gl.glEnable(GL_DEPTH_TEST);
                gl.glDepthFunc(GLFormats.compare(ds.depthCompare()));
            } else {
                gl.glDisable(GL_DEPTH_TEST);
            }
            gl.glDepthMask(ds.depthWrite() && !readOnlyDepth);
            if (ds.stencilTest()) {
                gl.glEnable(GL_STENCIL_TEST);
                applyStencil(ds);
                gl.glStencilOpSeparate(GL_FRONT, op(ds.front().fail()), op(ds.front().depthFail()), op(ds.front().pass()));
                gl.glStencilOpSeparate(GL_BACK, op(ds.back().fail()), op(ds.back().depthFail()), op(ds.back().pass()));
                gl.glStencilMaskSeparate(GL_FRONT_AND_BACK, readOnlyDepth ? 0 : ds.writeMask());
            } else {
                gl.glDisable(GL_STENCIL_TEST);
            }
        }

        private void applyStencil(CgGraphicsPipelineDesc.DepthStencil ds) {
            if (!ds.stencilTest()) return;
            gl.glStencilFuncSeparate(GL_FRONT, GLFormats.compare(ds.front().compare()), stencilReference, ds.readMask());
            gl.glStencilFuncSeparate(GL_BACK, GLFormats.compare(ds.back().compare()), stencilReference, ds.readMask());
        }

        private int op(CgGraphicsPipelineDesc.StencilOp o) {
            return switch (o) {
                case KEEP -> GL_KEEP;
                case ZERO -> GL_ZERO;
                case REPLACE -> GL_REPLACE;
                case INCREMENT_CLAMP -> GL_INCR;
                case DECREMENT_CLAMP -> GL_DECR;
                case INVERT -> GL_INVERT;
                case INCREMENT_WRAP -> GL_INCR_WRAP;
                case DECREMENT_WRAP -> GL_DECR_WRAP;
            };
        }

        private void applyBlend(CgGraphicsPipelineDesc d) {
            for (int i = 0; i < d.colorTargets().size(); i++) {
                CgGraphicsPipelineDesc.ColorTarget t = d.colorTargets().get(i);
                CgGraphicsPipelineDesc.Blend b = t.blend();
                if (b == null) {
                    gl.glDisablei(GL_BLEND, i);
                } else {
                    gl.glEnablei(GL_BLEND, i);
                    gl.glBlendFuncSeparatei(i, factor(b.srcColor()), factor(b.dstColor()), factor(b.srcAlpha()),
                            factor(b.dstAlpha()));
                    gl.glBlendEquationSeparatei(i, blendOp(b.colorOp()), blendOp(b.alphaOp()));
                }
                int m = t.writeMask();
                gl.glColorMaski(i, (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
            }
        }

        private int factor(CgGraphicsPipelineDesc.BlendFactor f) {
            return switch (f) {
                case ZERO -> GL_ZERO;
                case ONE -> GL_ONE;
                case SRC_COLOR -> GL_SRC_COLOR;
                case ONE_MINUS_SRC_COLOR -> GL_ONE_MINUS_SRC_COLOR;
                case DST_COLOR -> GL_DST_COLOR;
                case ONE_MINUS_DST_COLOR -> GL_ONE_MINUS_DST_COLOR;
                case SRC_ALPHA -> GL_SRC_ALPHA;
                case ONE_MINUS_SRC_ALPHA -> GL_ONE_MINUS_SRC_ALPHA;
                case DST_ALPHA -> GL_DST_ALPHA;
                case ONE_MINUS_DST_ALPHA -> GL_ONE_MINUS_DST_ALPHA;
                case CONSTANT_COLOR -> GL_CONSTANT_COLOR;
                case ONE_MINUS_CONSTANT_COLOR -> GL_ONE_MINUS_CONSTANT_COLOR;
                case CONSTANT_ALPHA -> GL_CONSTANT_ALPHA;
                case ONE_MINUS_CONSTANT_ALPHA -> GL_ONE_MINUS_CONSTANT_ALPHA;
                case SRC_ALPHA_SATURATE -> GL_SRC_ALPHA_SATURATE;
            };
        }

        private int blendOp(CgGraphicsPipelineDesc.BlendOp o) {
            return switch (o) {
                case ADD -> GL_FUNC_ADD;
                case SUBTRACT -> GL_FUNC_SUBTRACT;
                case REVERSE_SUBTRACT -> GL_FUNC_REVERSE_SUBTRACT;
                case MIN -> GL_MIN;
                case MAX -> GL_MAX;
            };
        }

        private int indexType() {
            return wideIndices ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT;
        }

        private long indexStart(int firstIndex) {
            if (indexBuffer == null) throw new IllegalStateException(desc.label() + ": an indexed draw with no index buffer");
            return indexOffset + (long) firstIndex * (wideIndices ? 4 : 2);
        }

        /** GL's indirect commands count indices from the element buffer's start: it has no bound offset. */
        private void indirectIndices() {
            if (indexOffset != 0)
                throw new UnsupportedOperationException(desc.label() + ": an indexed indirect draw on GL needs its index"
                        + " buffer bound at offset 0, not " + indexOffset);
        }

        @Override
        public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
            setupDraw();
            gl.glDrawArraysInstancedBaseInstance(pipeline.mode, firstVertex, vertexCount, instanceCount, firstInstance);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
            setupDraw();
            gl.glDrawElementsInstancedBaseVertexBaseInstance(pipeline.mode, indexCount, indexType(), indexStart(firstIndex),
                    instanceCount, baseVertex, firstInstance);
        }

        @Override
        public void drawIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            setupDraw();
            gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) buffer).name);
            gl.glMultiDrawArraysIndirect(pipeline.mode, offset, drawCount, stride);
        }

        @Override
        public void drawIndexedIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            setupDraw();
            indirectIndices();
            gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) buffer).name);
            gl.glMultiDrawElementsIndirect(pipeline.mode, indexType(), offset, drawCount, stride);
        }

        @Override
        public void drawIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws,
                                      int stride) {
            setupDraw();
            gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) buffer).name);
            gl.glBindBuffer(GL_PARAMETER_BUFFER, ((GLBuffer) count).name);
            gl.glMultiDrawArraysIndirectCount(pipeline.mode, offset, countOffset, maxDraws, stride);
        }

        @Override
        public void drawIndexedIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset,
                                             int maxDraws, int stride) {
            setupDraw();
            indirectIndices();
            gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) buffer).name);
            gl.glBindBuffer(GL_PARAMETER_BUFFER, ((GLBuffer) count).name);
            gl.glMultiDrawElementsIndirectCount(pipeline.mode, indexType(), offset, countOffset, maxDraws, stride);
        }

        @Override
        public void end() {
            if (!inPass) throw new IllegalStateException("end of a pass not open");
            int invalidated = 0;
            for (int i = 0; i < desc.colors().size(); i++) {
                if (desc.colors().get(i).store() == CgPassDesc.StoreOp.DONT_CARE) discard[invalidated++] = GL_COLOR_ATTACHMENT0 + i;
            }
            CgPassDesc.Depth z = desc.depth();
            if (z != null && !z.readOnly() && z.store() == CgPassDesc.StoreOp.DONT_CARE)
                discard[invalidated++] = GLFormats.attachment(z.view().texture().desc().format(), 0);
            if (invalidated > 0) gl.glInvalidateNamedFramebufferData(framebuffer, discard, invalidated);
            gl.glDisable(GL_SCISSOR_TEST);
            gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            for (int i = 0; i < vertexBuffers.length; i++) vertexBuffers[i] = null;
            indexBuffer = null;
            pipeline = null;
            desc = null;
            gl.glPopDebugGroup();
            inPass = false;
        }
    }

    private final class Compute implements CgComputePass {

        private GLComputePipeline pipeline;

        @Override
        public void setPipeline(CgComputePipeline p) {
            GLComputePipeline next = (GLComputePipeline) p;
            if (next == pipeline) return;
            pipeline = next;
            gl.glUseProgram(next.program(gl));
            committed = null;
            bindingsDirty = true;
            pushDirty = true;
        }

        @Override public void pushBindings(CgBindings bindings) { GLEncoder.this.pushBindings(bindings); }

        @Override public void setBindingSet(int set, CgBindingSet bindings) { GLEncoder.this.setBindingSet(set, bindings); }

        @Override public void pushConstants(int offset, int value) { pushConstant(offset, value); }

        @Override public void pushConstants(int offset, ByteBuffer data) { GLEncoder.this.pushConstants(offset, data); }

        private void setup() {
            if (pipeline == null) throw new IllegalStateException("a dispatch with no pipeline");
            commitBindings(pipeline.layout(), pipeline.points);
        }

        @Override
        public void dispatch(int groupsX, int groupsY, int groupsZ) {
            setup();
            gl.glDispatchCompute(groupsX, groupsY, groupsZ);
        }

        @Override
        public void dispatchIndirect(CgGpuBuffer buffer, long offset) {
            setup();
            gl.glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, ((GLBuffer) buffer).name);
            gl.glDispatchComputeIndirect(offset);
        }

        @Override
        public void end() {
            if (!inPass) throw new IllegalStateException("end of a pass not open");
            pipeline = null;
            gl.glPopDebugGroup();
            inPass = false;
        }
    }

    /** Releases the ring and its spill buffer. */
    public void delete() {
        ring.delete(gl);
        spill.delete(gl);
    }
}
