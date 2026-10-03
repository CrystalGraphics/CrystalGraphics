package com.crystalgraphics.platform.device.recording;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.device.command.CgCommandEncoder;
import com.crystalgraphics.platform.device.command.CgComputePass;
import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.resource.CgTimerQuery;
import com.crystalgraphics.platform.device.shader.CgShaderModule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * A {@link CgDevice} with no GPU: it keeps buffer contents, logs every command, and refuses what a Vulkan
 * device's validation would. What the tracked backend's tests and headless replays run on.
 *
 * <pre>{@code
 * CgRecordingDevice device = new CgRecordingDevice(64, 64);
 * // ... drive the tracked backend ...
 * assertEquals(List.of("beginPass fbo colors=[#1:CLEAR]", "setPipeline #4", "draw 6 1 0 0", "endPass"),
 *         device.logSince(mark));
 * }</pre>
 *
 * <p>Frames retire {@code framesInFlight} frames after they end, as a GPU running that far behind would;
 * {@link #retireAll()} catches up. A released object is destroyed at its frame's retirement and refused from
 * then on. Readbacks return zeros.</p>
 */
public final class CgRecordingDevice implements CgDevice {

    private static final int SPIRV_MAGIC = 0x07230203;

    /** The smallest module a test can hand {@link #createShaderModule}: a SPIR-V header and nothing else. */
    public static ByteBuffer emptySpirv() {
        ByteBuffer b = ByteBuffer.allocateDirect(20).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(SPIRV_MAGIC).putInt(0x00010500).putInt(0).putInt(1).putInt(0).flip();
        return b;
    }

    private static final CgDeviceInfo INFO = new CgDeviceInfo("recording", "CrystalGraphics", "0",
            new CgDeviceInfo.Limits(16384, 2048, 2048, 8, 8, 16, 32, 65536, 24, 16, 1 << 26,
                    256, 256, 16, 16384, 16f, CgDeviceInfo.Compute.MINIMUM), true, true, true, true, true, true);

    private final List<String> log = new ArrayList<>();
    private final List<CgPassDesc> passes = new ArrayList<>();
    private final TreeMap<Long, List<Runnable>> retirements = new TreeMap<>();
    private final Thread owner = Thread.currentThread();
    private final int framesInFlight;
    private final boolean ownsSubmission;
    private final Encoder encoder = new Encoder();

    private long frame;
    private long retired = -1;
    private int nextId = 1;
    private int live;
    private int draws;
    private boolean logging = true;
    private Pass open;
    private ComputePass openCompute;
    private Texture surfaceColor, surfaceDepth;

    public CgRecordingDevice(int width, int height) {
        this(width, height, 2, true);
    }

    /** @param ownsSubmission {@code false} stands in for a hosted device: {@link #waitRetired} refuses */
    public CgRecordingDevice(int width, int height, int framesInFlight, boolean ownsSubmission) {
        this.framesInFlight = framesInFlight;
        this.ownsSubmission = ownsSubmission;
        resize(width, height);
        log.clear();
    }

    // ── what a test reads ──────────────────────────────────────────────────────

    public List<String> log() { return Collections.unmodifiableList(log); }

    /** A position in the log, for {@link #logSince}. */
    public int mark() { return log.size(); }

    public List<String> logSince(int mark) { return new ArrayList<>(log.subList(mark, log.size())); }

    public List<CgPassDesc> passes() { return Collections.unmodifiableList(passes); }

    public int draws() { return draws; }

    /**
     * Stops keeping the log and the pass list, for a long run: every command is still validated. A harness
     * scene on the tracked backend makes thousands a frame.
     */
    public CgRecordingDevice withoutLog() {
        logging = false;
        log.clear();
        passes.clear();
        return this;
    }

    private void record(String line) {
        if (logging) log.add(line);
    }

    /** Objects created and not yet destroyed. */
    public int liveObjects() { return live; }

    public boolean isDestroyed(CgDeviceObject object) { return ((Obj) object).destroyed; }

    /** The id the log names an object by, as {@code #id}. */
    public static int idOf(CgDeviceObject object) { return ((Obj) object).id; }

    /** Retires every ended frame now, as an idle GPU would. */
    public void retireAll() { retireThrough(frame - 1); }

    /** A new default framebuffer, as a window resize gives; the old images are released. */
    public void resize(int width, int height) {
        if (surfaceColor != null) release(surfaceColor);
        if (surfaceDepth != null) release(surfaceDepth);
        surfaceColor = (Texture) createTexture(new CgGpuTexture.Desc("surface", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, width, height, 1, 1, 1, CgGpuTexture.Usage.ALL));
        surfaceDepth = (Texture) createTexture(new CgGpuTexture.Desc("surfaceDepth", CgGpuTexture.Kind.D2,
                CgFormat.DEPTH24_PLUS_STENCIL8, width, height, 1, 1, 1, CgGpuTexture.Usage.ALL));
    }

    // ── CgDevice ───────────────────────────────────────────────────────────────

    @Override public CgDeviceInfo info() { return INFO; }

    @Override public void describe(BiConsumer<String, String> fact) { fact.accept("version", "recording"); }

    @Override public boolean supports(CgFormat format, CgGpuTexture.Usage usage) { return true; }

    @Override public boolean ownedByCurrentThread() { return Thread.currentThread() == owner; }

    @Override
    public CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc) {
        Buffer b = new Buffer(desc);
        record("createBuffer #" + b.id + " " + desc.label() + " " + desc.size() + (desc.hostVisible() ? " host" : ""));
        return b;
    }

    @Override
    public CgGpuTexture createTexture(CgGpuTexture.Desc desc) {
        if (desc.width() < 1 || desc.height() < 1 || desc.depthOrLayers() < 1 || desc.mips() < 1)
            throw new IllegalArgumentException("Empty texture " + desc);
        Texture t = new Texture(desc);
        record("createTexture #" + t.id + " " + desc.label() + " " + desc.kind() + " " + desc.format() + " "
                + desc.width() + "x" + desc.height() + "x" + desc.depthOrLayers() + " mips=" + desc.mips()
                + (desc.samples() > 1 ? " samples=" + desc.samples() : ""));
        return t;
    }

    @Override
    public CgGpuSampler createSampler(CgGpuSampler.Desc desc) {
        Sampler s = new Sampler(desc);
        record("createSampler #" + s.id);
        return s;
    }

    @Override
    public CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label) {
        if (spirv.remaining() < 20 || spirv.remaining() % 4 != 0
                || spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(spirv.position()) != SPIRV_MAGIC)
            throw new IllegalArgumentException(label + ": not SPIR-V");
        Module m = new Module(stage, label);
        record("createShaderModule #" + m.id + " " + label + " " + stage);
        return m;
    }

    @Override
    public CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots) {
        Layout l = new Layout(label, new ArrayList<>(slots));
        record("createBindingLayout #" + l.id + " " + label + " " + slots.size());
        return l;
    }

    @Override
    public CgPipeline createPipeline(CgPipelineDesc desc) {
        use(desc.layout(), desc.vertex(), desc.fragment());
        Pipeline p = new Pipeline(desc);
        record("createPipeline #" + p.id + " " + desc.label());
        return p;
    }

    @Override
    public CgComputePipeline createComputePipeline(String label, CgShaderModule module, CgBindingLayout layout) {
        use(layout, module);
        if (module.stage() != CgShaderModule.Stage.COMPUTE)
            throw new IllegalArgumentException(label + ": a compute pipeline from a " + module.stage() + " module");
        ComputePipeline p = new ComputePipeline(label, module, layout);
        record("createComputePipeline #" + p.id + " " + label);
        return p;
    }

    @Override
    public CgTimerQuery createTimerQuery(String label) {
        Timer t = new Timer(label);
        record("createTimerQuery #" + t.id);
        return t;
    }

    @Override
    public void release(CgDeviceObject object) {
        Obj o = (Obj) object;
        if (o.released) throw new IllegalStateException("#" + o.id + " " + o.label + " released twice");
        o.released = true;
        record("release #" + o.id);
        whenRetired(frame, () -> {
            o.destroyed = true;
            live--;
            record("destroy #" + o.id);
        });
    }

    @Override public CgCommandEncoder encoder() { return encoder; }

    @Override public CgGpuTexture surfaceColor() { return surfaceColor; }

    @Override public CgGpuTexture surfaceDepth() { return surfaceDepth; }

    @Override public long frameIndex() { return frame; }

    @Override public long retiredFrame() { return retired; }

    @Override
    public void whenRetired(long f, Runnable action) {
        if (f <= retired) action.run();
        else retirements.computeIfAbsent(f, k -> new ArrayList<>()).add(action);
    }

    @Override
    public void endFrame() {
        if (open != null) throw new IllegalStateException("endFrame with pass '" + open.desc.label() + "' open");
        record("endFrame " + frame);
        frame++;
        retireThrough(frame - 1 - framesInFlight);
    }

    @Override public boolean ownsSubmission() { return ownsSubmission; }

    @Override
    public void waitRetired(long f) {
        if (!ownsSubmission) throw new IllegalStateException("A hosted device cannot wait for frame " + f);
        if (f >= frame) endFrame();
        retireThrough(f);
    }

    private void retireThrough(long f) {
        while (retired < f) {
            retired++;
            record("retire " + retired);
            List<Runnable> due = retirements.remove(retired);
            if (due != null) due.forEach(Runnable::run);
        }
    }

    // ── validation ─────────────────────────────────────────────────────────────

    private void use(CgDeviceObject... objects) {
        for (CgDeviceObject o : objects) {
            if (o != null && ((Obj) o).destroyed)
                throw new IllegalStateException("#" + ((Obj) o).id + " " + o.label() + " used after it was destroyed");
        }
    }

    private void outsidePass(String what) {
        if (open != null) throw new IllegalStateException(what + " inside pass '" + open.desc.label() + "'");
    }

    private void noCompute(String what) {
        if (openCompute != null) throw new IllegalStateException(what + " inside compute pass '" + openCompute.label + "'");
    }

    /** {@code from} copied into {@code into}, every resource checked live; the bindings as a log line. */
    private String push(CgBindings from, CgBindings into) {
        into.clear();
        StringBuilder s = new StringBuilder("pushBindings");
        for (int i = 0; i < from.count(); i++) {
            s.append(' ').append(from.binding(i)).append(':');
            switch (from.type(i)) {
                case SAMPLED_TEXTURE -> {
                    use(from.view(i).texture(), from.sampler(i));
                    into.texture(from.binding(i), from.view(i), from.sampler(i));
                    s.append(ref(from.view(i).texture()));
                }
                case STORAGE_IMAGE -> {
                    use(from.view(i).texture());
                    into.image(from.binding(i), from.view(i));
                    s.append(ref(from.view(i).texture()));
                }
                case TEXEL_BUFFER -> {
                    use(from.buffer(i));
                    into.texel(from.binding(i), from.buffer(i), from.offset(i), from.size(i), from.texelFormat(i));
                    s.append(ref(from.buffer(i))).append('+').append(from.offset(i));
                }
                default -> {
                    use(from.buffer(i));
                    into.buffer(from.binding(i), from.type(i), from.buffer(i), from.offset(i), from.size(i));
                    s.append(ref(from.buffer(i))).append('+').append(from.offset(i));
                }
            }
        }
        return s.toString();
    }

    /** Every slot of {@code layout} pushed with its type. */
    private static void pushedAll(String label, CgBindingLayout layout, CgBindings pushed) {
        for (CgBindingLayout.Slot slot : layout.slots()) {
            int i = pushed.indexOf(slot.binding());
            if (i < 0 || pushed.type(i) != slot.type())
                throw new IllegalStateException(label + " reads binding " + slot.binding() + " (" + slot.type()
                        + "), which was not pushed");
        }
    }

    private static String ref(CgDeviceObject o) { return o == null ? "-" : "#" + ((Obj) o).id; }

    // ── the encoder and the pass ───────────────────────────────────────────────

    private final class Encoder implements CgCommandEncoder {

        @Override
        public CgRenderPass beginPass(CgPassDesc desc) {
            outsidePass("beginPass");
            noCompute("beginPass");
            StringBuilder s = new StringBuilder("beginPass ").append(desc.label()).append(" colors=[");
            for (int i = 0; i < desc.colors().size(); i++) {
                CgPassDesc.Color c = desc.colors().get(i);
                use(c.view().texture());
                s.append(i == 0 ? "" : ", ").append(ref(c.view().texture())).append(':').append(c.load());
            }
            s.append(']');
            if (desc.depth() != null) {
                use(desc.depth().view().texture());
                s.append(" depth=").append(ref(desc.depth().view().texture())).append(':')
                        .append(desc.depth().depthLoad()).append('/').append(desc.depth().stencilLoad());
            }
            if (logging) passes.add(desc);
            record(s.toString());
            return open = new Pass(desc);
        }

        @Override
        public CgComputePass beginCompute(String label) {
            outsidePass("beginCompute");
            noCompute("beginCompute");
            openCompute = new ComputePass(label);
            record("beginCompute " + label);
            return openCompute;
        }

        @Override
        public void bufferBarrier(CgGpuBuffer buffer, int from, int to) {
            outsidePass("bufferBarrier");
            use(buffer);
            record("bufferBarrier " + ref(buffer) + " " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void imageBarrier(CgGpuTexture texture, int from, int to) {
            outsidePass("imageBarrier");
            use(texture);
            record("imageBarrier " + ref(texture) + " " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void memoryBarrier(int from, int to) {
            outsidePass("memoryBarrier");
            record("memoryBarrier " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data) {
            outsidePass("writeBuffer");
            use(dst);
            int n = data.remaining();
            ByteBuffer to = ((Buffer) dst).memory.duplicate();
            to.position((int) dstOffset);
            to.put(data.duplicate());
            record("writeBuffer " + ref(dst) + "+" + dstOffset + " " + n);
        }

        @Override
        public void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size) {
            outsidePass("copyBuffer");
            use(src, dst);
            ByteBuffer from = ((Buffer) src).memory.duplicate();
            from.limit((int) (srcOffset + size));
            from.position((int) srcOffset);
            ByteBuffer to = ((Buffer) dst).memory.duplicate();
            to.position((int) dstOffset);
            to.put(from);
            record("copyBuffer " + ref(src) + "+" + srcOffset + " " + ref(dst) + "+" + dstOffset + " " + size);
        }

        @Override
        public void writeTexture(CgGpuTexture dst, CgTextureRegion r, ByteBuffer data) {
            outsidePass("writeTexture");
            use(dst);
            long need = r.texels() * dst.desc().format().bytes();
            if (data.remaining() < need)
                throw new IllegalArgumentException("writeTexture needs " + need + " bytes, got " + data.remaining());
            record("writeTexture " + ref(dst) + " " + region(r));
        }

        @Override
        public void copyTexture(CgGpuTexture src, CgTextureRegion sr, CgGpuTexture dst, CgTextureRegion dr) {
            outsidePass("copyTexture");
            use(src, dst);
            record("copyTexture " + ref(src) + " " + region(sr) + " " + ref(dst) + " " + region(dr));
        }

        @Override
        public void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
                         CgTextureView dst, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter) {
            outsidePass("blit");
            use(src.texture(), dst.texture());
            record("blit " + ref(src.texture()) + " " + sx0 + "," + sy0 + "," + sx1 + "," + sy1 + " "
                    + ref(dst.texture()) + " " + dx0 + "," + dy0 + "," + dx1 + "," + dy1 + " " + filter);
        }

        @Override
        public void resolve(CgTextureView src, CgTextureView dst) {
            outsidePass("resolve");
            use(src.texture(), dst.texture());
            record("resolve " + ref(src.texture()) + " " + ref(dst.texture()));
        }

        @Override
        public void generateMipmaps(CgGpuTexture texture) {
            outsidePass("generateMipmaps");
            use(texture);
            record("generateMipmaps " + ref(texture));
        }

        @Override
        public void readTexture(CgGpuTexture src, CgTextureRegion r, ByteBuffer out) {
            outsidePass("readTexture");
            use(src);
            ByteBuffer o = out.duplicate();
            while (o.hasRemaining()) o.put((byte) 0);
            record("readTexture " + ref(src) + " " + region(r));
        }

        @Override
        public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion r, CgGpuBuffer dst, long dstOffset) {
            outsidePass("copyTextureToBuffer");
            use(src, dst);
            record("copyTextureToBuffer " + ref(src) + " " + region(r) + " " + ref(dst) + "+" + dstOffset);
        }

        @Override
        public void beginTimer(CgTimerQuery query) {
            use(query);
            record("beginTimer " + ref(query));
        }

        @Override
        public void endTimer(CgTimerQuery query) {
            use(query);
            ((Timer) query).frame = frame;
            record("endTimer " + ref(query));
        }

        private String region(CgTextureRegion r) {
            return "mip" + r.mip() + " " + r.x() + "," + r.y() + "," + r.z() + " " + r.width() + "x" + r.height() + "x" + r.depth();
        }
    }

    private final class Pass implements CgRenderPass {
        final CgPassDesc desc;
        final CgBindings pushed = new CgBindings();
        final Map<Integer, Buffer> vertexBuffers = new TreeMap<>();
        Pipeline pipeline;
        Buffer indexBuffer;
        boolean ended;

        Pass(CgPassDesc desc) { this.desc = desc; }

        private void live() {
            if (ended || open != this) throw new IllegalStateException("Pass '" + desc.label() + "' has ended");
        }

        @Override
        public void setPipeline(CgPipeline p) {
            live();
            use(p);
            CgPipelineDesc d = p.desc();
            if (d.colorTargets().size() != desc.colors().size())
                throw new IllegalStateException(d.label() + " has " + d.colorTargets().size() + " targets, pass '"
                        + desc.label() + "' " + desc.colors().size());
            for (int i = 0; i < desc.colors().size(); i++) {
                CgFormat attached = desc.colors().get(i).view().texture().desc().format();
                if (d.colorTargets().get(i).format() != attached)
                    throw new IllegalStateException(d.label() + " target " + i + " is " + d.colorTargets().get(i).format()
                            + ", the attachment " + attached);
            }
            CgFormat depth = desc.depth() == null ? null : desc.depth().view().texture().desc().format();
            if (d.depthFormat() != depth)
                throw new IllegalStateException(d.label() + " depth is " + d.depthFormat() + ", the attachment " + depth);
            if (d.samples() != desc.samples())
                throw new IllegalStateException(d.label() + " has " + d.samples() + " samples, the pass " + desc.samples());
            pipeline = (Pipeline) p;
            record("setPipeline " + ref(p));
        }

        @Override
        public void pushBindings(CgBindings b) {
            live();
            record(push(b, pushed));
        }

        @Override
        public void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset) {
            live();
            use(buffer);
            vertexBuffers.put(binding, (Buffer) buffer);
            record("setVertexBuffer " + binding + " " + ref(buffer) + "+" + offset);
        }

        @Override
        public void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide) {
            live();
            use(buffer);
            indexBuffer = (Buffer) buffer;
            record("setIndexBuffer " + ref(buffer) + "+" + offset + (wide ? " u32" : " u16"));
        }

        @Override
        public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
            live();
            record("setViewport " + (int) x + "," + (int) y + " " + (int) width + "x" + (int) height);
        }

        @Override
        public void setScissor(int x, int y, int width, int height) {
            live();
            record("setScissor " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void setDepthBias(float constant, float slope) {
            live();
            record("setDepthBias " + constant + " " + slope);
        }

        @Override
        public void setStencilReference(int reference) {
            live();
            record("setStencilReference " + reference);
        }

        @Override
        public void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height) {
            live();
            if (attachment >= desc.colors().size())
                throw new IllegalStateException("No colour attachment " + attachment + " in '" + desc.label() + "'");
            record("clearColor " + attachment + " " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                                      int x, int y, int width, int height) {
            live();
            if (desc.depth() == null) throw new IllegalStateException("No depth attachment in '" + desc.label() + "'");
            record("clearDepthStencil" + (depth ? " depth" : "") + (stencil ? " stencil" : "") + " "
                    + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
            drawable();
            draws++;
            record("draw " + vertexCount + " " + instanceCount + " " + firstVertex + " " + firstInstance);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
            drawable();
            if (indexBuffer == null) throw new IllegalStateException("drawIndexed with no index buffer");
            use(indexBuffer);
            draws++;
            record("drawIndexed " + indexCount + " " + instanceCount + " " + firstIndex + " " + baseVertex + " " + firstInstance);
        }

        @Override
        public void drawIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            drawable();
            use(buffer);
            draws++;
            record("drawIndirect " + ref(buffer) + "+" + offset + " " + drawCount + " " + stride);
        }

        @Override
        public void drawIndexedIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            drawable();
            if (indexBuffer == null) throw new IllegalStateException("drawIndexedIndirect with no index buffer");
            use(buffer, indexBuffer);
            draws++;
            record("drawIndexedIndirect " + ref(buffer) + "+" + offset + " " + drawCount + " " + stride);
        }

        @Override
        public void drawIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws,
                                      int stride) {
            drawable();
            use(buffer, count);
            draws++;
            record("drawIndirectCount " + ref(buffer) + "+" + offset + " " + ref(count) + "+" + countOffset + " "
                    + maxDraws + " " + stride);
        }

        @Override
        public void drawIndexedIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset,
                                             int maxDraws, int stride) {
            drawable();
            if (indexBuffer == null) throw new IllegalStateException("drawIndexedIndirectCount with no index buffer");
            use(buffer, count, indexBuffer);
            draws++;
            record("drawIndexedIndirectCount " + ref(buffer) + "+" + offset + " " + ref(count) + "+" + countOffset + " "
                    + maxDraws + " " + stride);
        }

        private void drawable() {
            live();
            if (pipeline == null) throw new IllegalStateException("Draw with no pipeline in '" + desc.label() + "'");
            use(pipeline);
            pushedAll(pipeline.desc().label(), pipeline.desc().layout(), pushed);
            for (CgPipelineDesc.VertexBuffer vb : pipeline.desc().vertexBuffers()) {
                Buffer b = vertexBuffers.get(vb.binding());
                if (b == null) throw new IllegalStateException(pipeline.desc().label() + " reads vertex binding "
                        + vb.binding() + ", which has no buffer");
                use(b);
            }
        }

        @Override
        public void end() {
            live();
            ended = true;
            open = null;
            record("endPass");
        }
    }

    // ── objects ────────────────────────────────────────────────────────────────

    private abstract class Obj implements CgDeviceObject {
        final int id = nextId++;
        final String label;
        boolean released, destroyed;

        Obj(String label) {
            this.label = label;
            live++;
        }

        @Override public String label() { return label; }

        @Override public String toString() { return "#" + id + " " + label; }
    }

    private final class Buffer extends Obj implements CgGpuBuffer {
        final CgGpuBuffer.Desc desc;
        final ByteBuffer memory;

        Buffer(CgGpuBuffer.Desc desc) {
            super(desc.label());
            this.desc = desc;
            this.memory = ByteBuffer.allocateDirect((int) desc.size()).order(ByteOrder.nativeOrder());
        }

        @Override public long size() { return desc.size(); }

        @Override public boolean hostVisible() { return desc.hostVisible(); }

        @Override
        public ByteBuffer mapped() {
            if (!desc.hostVisible()) throw new IllegalStateException("#" + id + " " + label + " is device-local");
            use(this);
            return memory;
        }
    }

    private final class Texture extends Obj implements CgGpuTexture {
        final CgGpuTexture.Desc desc;

        Texture(CgGpuTexture.Desc desc) {
            super(desc.label());
            this.desc = desc;
        }

        @Override public CgGpuTexture.Desc desc() { return desc; }
    }

    private final class Sampler extends Obj implements CgGpuSampler {
        final CgGpuSampler.Desc desc;

        Sampler(CgGpuSampler.Desc desc) {
            super("sampler");
            this.desc = desc;
        }

        @Override public CgGpuSampler.Desc desc() { return desc; }
    }

    private final class Module extends Obj implements CgShaderModule {
        final Stage stage;

        Module(Stage stage, String label) {
            super(label);
            this.stage = stage;
        }

        @Override public Stage stage() { return stage; }
    }

    private final class Layout extends Obj implements CgBindingLayout {
        final List<Slot> slots;

        Layout(String label, List<Slot> slots) {
            super(label);
            this.slots = slots;
        }

        @Override public List<Slot> slots() { return slots; }
    }

    private final class Pipeline extends Obj implements CgPipeline {
        final CgPipelineDesc desc;

        Pipeline(CgPipelineDesc desc) {
            super(desc.label());
            this.desc = desc;
        }

        @Override public CgPipelineDesc desc() { return desc; }
    }

    private final class ComputePass implements CgComputePass {
        final String label;
        final CgBindings pushed = new CgBindings();
        ComputePipeline pipeline;
        boolean ended;

        ComputePass(String label) { this.label = label; }

        private void live() {
            if (ended || openCompute != this) throw new IllegalStateException("Compute pass '" + label + "' has ended");
        }

        @Override
        public void setPipeline(CgComputePipeline p) {
            live();
            use(p);
            pipeline = (ComputePipeline) p;
            record("setComputePipeline " + ref(p));
        }

        @Override
        public void pushBindings(CgBindings b) {
            live();
            record(push(b, pushed));
        }

        @Override
        public void dispatch(int groupsX, int groupsY, int groupsZ) {
            dispatchable();
            record("dispatch " + groupsX + " " + groupsY + " " + groupsZ);
        }

        @Override
        public void dispatchIndirect(CgGpuBuffer buffer, long offset) {
            dispatchable();
            use(buffer);
            record("dispatchIndirect " + ref(buffer) + "+" + offset);
        }

        private void dispatchable() {
            live();
            if (pipeline == null) throw new IllegalStateException("Dispatch with no pipeline in '" + label + "'");
            use(pipeline);
            pushedAll(pipeline.label(), pipeline.layout, pushed);
        }

        @Override
        public void end() {
            live();
            ended = true;
            openCompute = null;
            record("endCompute");
        }
    }

    private final class ComputePipeline extends Obj implements CgComputePipeline {
        final CgShaderModule module;
        final CgBindingLayout layout;

        ComputePipeline(String label, CgShaderModule module, CgBindingLayout layout) {
            super(label);
            this.module = module;
            this.layout = layout;
        }

        @Override public CgShaderModule module() { return module; }
        @Override public CgBindingLayout layout() { return layout; }
    }

    private final class Timer extends Obj implements CgTimerQuery {
        long frame = Long.MAX_VALUE;

        Timer(String label) { super(label); }

        @Override public long resultNanos() { return frame <= retired ? 0 : -1; }
    }
}
