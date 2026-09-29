package com.crystalgraphics.platform.device;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
                    256, 256, 16, 16384, 16f), true, true, true);

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
    private Pass open;
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

    @Override public boolean supports(CgFormat format, CgGpuTexture.Usage usage) { return true; }

    @Override public boolean ownedByCurrentThread() { return Thread.currentThread() == owner; }

    @Override
    public CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc) {
        Buffer b = new Buffer(desc);
        log.add("createBuffer #" + b.id + " " + desc.label() + " " + desc.size() + (desc.hostVisible() ? " host" : ""));
        return b;
    }

    @Override
    public CgGpuTexture createTexture(CgGpuTexture.Desc desc) {
        if (desc.width() < 1 || desc.height() < 1 || desc.depthOrLayers() < 1 || desc.mips() < 1)
            throw new IllegalArgumentException("Empty texture " + desc);
        Texture t = new Texture(desc);
        log.add("createTexture #" + t.id + " " + desc.label() + " " + desc.kind() + " " + desc.format() + " "
                + desc.width() + "x" + desc.height() + "x" + desc.depthOrLayers() + " mips=" + desc.mips()
                + (desc.samples() > 1 ? " samples=" + desc.samples() : ""));
        return t;
    }

    @Override
    public CgGpuSampler createSampler(CgGpuSampler.Desc desc) {
        Sampler s = new Sampler(desc);
        log.add("createSampler #" + s.id);
        return s;
    }

    @Override
    public CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label) {
        if (spirv.remaining() < 20 || spirv.remaining() % 4 != 0
                || spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(spirv.position()) != SPIRV_MAGIC)
            throw new IllegalArgumentException(label + ": not SPIR-V");
        Module m = new Module(stage, label);
        log.add("createShaderModule #" + m.id + " " + label + " " + stage);
        return m;
    }

    @Override
    public CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots) {
        Layout l = new Layout(label, new ArrayList<>(slots));
        log.add("createBindingLayout #" + l.id + " " + label + " " + slots.size());
        return l;
    }

    @Override
    public CgPipeline createPipeline(CgPipelineDesc desc) {
        use(desc.layout(), desc.vertex(), desc.fragment());
        Pipeline p = new Pipeline(desc);
        log.add("createPipeline #" + p.id + " " + desc.label());
        return p;
    }

    @Override
    public CgTimerQuery createTimerQuery(String label) {
        Timer t = new Timer(label);
        log.add("createTimerQuery #" + t.id);
        return t;
    }

    @Override
    public void release(CgDeviceObject object) {
        Obj o = (Obj) object;
        if (o.released) throw new IllegalStateException("#" + o.id + " " + o.label + " released twice");
        o.released = true;
        log.add("release #" + o.id);
        whenRetired(frame, () -> {
            o.destroyed = true;
            live--;
            log.add("destroy #" + o.id);
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
        log.add("endFrame " + frame);
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
            log.add("retire " + retired);
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

    private static String ref(CgDeviceObject o) { return o == null ? "-" : "#" + ((Obj) o).id; }

    // ── the encoder and the pass ───────────────────────────────────────────────

    private final class Encoder implements CgCommandEncoder {

        @Override
        public CgRenderPass beginPass(CgPassDesc desc) {
            outsidePass("beginPass");
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
            passes.add(desc);
            log.add(s.toString());
            return open = new Pass(desc);
        }

        @Override
        public void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data) {
            outsidePass("writeBuffer");
            use(dst);
            int n = data.remaining();
            ByteBuffer to = ((Buffer) dst).memory.duplicate();
            to.position((int) dstOffset);
            to.put(data.duplicate());
            log.add("writeBuffer " + ref(dst) + "+" + dstOffset + " " + n);
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
            log.add("copyBuffer " + ref(src) + "+" + srcOffset + " " + ref(dst) + "+" + dstOffset + " " + size);
        }

        @Override
        public void writeTexture(CgGpuTexture dst, CgTextureRegion r, ByteBuffer data) {
            outsidePass("writeTexture");
            use(dst);
            long need = r.texels() * dst.desc().format().bytes();
            if (data.remaining() < need)
                throw new IllegalArgumentException("writeTexture needs " + need + " bytes, got " + data.remaining());
            log.add("writeTexture " + ref(dst) + " " + region(r));
        }

        @Override
        public void copyTexture(CgGpuTexture src, CgTextureRegion sr, CgGpuTexture dst, CgTextureRegion dr) {
            outsidePass("copyTexture");
            use(src, dst);
            log.add("copyTexture " + ref(src) + " " + region(sr) + " " + ref(dst) + " " + region(dr));
        }

        @Override
        public void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
                         CgTextureView dst, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter) {
            outsidePass("blit");
            use(src.texture(), dst.texture());
            log.add("blit " + ref(src.texture()) + " " + sx0 + "," + sy0 + "," + sx1 + "," + sy1 + " "
                    + ref(dst.texture()) + " " + dx0 + "," + dy0 + "," + dx1 + "," + dy1 + " " + filter);
        }

        @Override
        public void resolve(CgTextureView src, CgTextureView dst) {
            outsidePass("resolve");
            use(src.texture(), dst.texture());
            log.add("resolve " + ref(src.texture()) + " " + ref(dst.texture()));
        }

        @Override
        public void generateMipmaps(CgGpuTexture texture) {
            outsidePass("generateMipmaps");
            use(texture);
            log.add("generateMipmaps " + ref(texture));
        }

        @Override
        public void readTexture(CgGpuTexture src, CgTextureRegion r, ByteBuffer out) {
            outsidePass("readTexture");
            use(src);
            ByteBuffer o = out.duplicate();
            while (o.hasRemaining()) o.put((byte) 0);
            log.add("readTexture " + ref(src) + " " + region(r));
        }

        @Override
        public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion r, CgGpuBuffer dst, long dstOffset) {
            outsidePass("copyTextureToBuffer");
            use(src, dst);
            log.add("copyTextureToBuffer " + ref(src) + " " + region(r) + " " + ref(dst) + "+" + dstOffset);
        }

        @Override
        public void beginTimer(CgTimerQuery query) {
            use(query);
            log.add("beginTimer " + ref(query));
        }

        @Override
        public void endTimer(CgTimerQuery query) {
            use(query);
            ((Timer) query).frame = frame;
            log.add("endTimer " + ref(query));
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
            log.add("setPipeline " + ref(p));
        }

        @Override
        public void pushBindings(CgBindings b) {
            live();
            pushed.clear();
            StringBuilder s = new StringBuilder("pushBindings");
            for (int i = 0; i < b.count(); i++) {
                s.append(' ').append(b.binding(i)).append(':');
                switch (b.type(i)) {
                    case SAMPLED_TEXTURE -> {
                        use(b.view(i).texture(), b.sampler(i));
                        pushed.texture(b.binding(i), b.view(i), b.sampler(i));
                        s.append(ref(b.view(i).texture()));
                    }
                    case TEXEL_BUFFER -> {
                        use(b.buffer(i));
                        pushed.texel(b.binding(i), b.buffer(i), b.offset(i), b.size(i), b.texelFormat(i));
                        s.append(ref(b.buffer(i))).append('+').append(b.offset(i));
                    }
                    default -> {
                        use(b.buffer(i));
                        pushed.buffer(b.binding(i), b.type(i), b.buffer(i), b.offset(i), b.size(i));
                        s.append(ref(b.buffer(i))).append('+').append(b.offset(i));
                    }
                }
            }
            log.add(s.toString());
        }

        @Override
        public void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset) {
            live();
            use(buffer);
            vertexBuffers.put(binding, (Buffer) buffer);
            log.add("setVertexBuffer " + binding + " " + ref(buffer) + "+" + offset);
        }

        @Override
        public void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide) {
            live();
            use(buffer);
            indexBuffer = (Buffer) buffer;
            log.add("setIndexBuffer " + ref(buffer) + "+" + offset + (wide ? " u32" : " u16"));
        }

        @Override
        public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
            live();
            log.add("setViewport " + (int) x + "," + (int) y + " " + (int) width + "x" + (int) height);
        }

        @Override
        public void setScissor(int x, int y, int width, int height) {
            live();
            log.add("setScissor " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void setDepthBias(float constant, float slope) {
            live();
            log.add("setDepthBias " + constant + " " + slope);
        }

        @Override
        public void setStencilReference(int reference) {
            live();
            log.add("setStencilReference " + reference);
        }

        @Override
        public void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height) {
            live();
            if (attachment >= desc.colors().size())
                throw new IllegalStateException("No colour attachment " + attachment + " in '" + desc.label() + "'");
            log.add("clearColor " + attachment + " " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                                      int x, int y, int width, int height) {
            live();
            if (desc.depth() == null) throw new IllegalStateException("No depth attachment in '" + desc.label() + "'");
            log.add("clearDepthStencil" + (depth ? " depth" : "") + (stencil ? " stencil" : "") + " "
                    + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
            drawable();
            draws++;
            log.add("draw " + vertexCount + " " + instanceCount + " " + firstVertex + " " + firstInstance);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
            drawable();
            if (indexBuffer == null) throw new IllegalStateException("drawIndexed with no index buffer");
            use(indexBuffer);
            draws++;
            log.add("drawIndexed " + indexCount + " " + instanceCount + " " + firstIndex + " " + baseVertex + " " + firstInstance);
        }

        private void drawable() {
            live();
            if (pipeline == null) throw new IllegalStateException("Draw with no pipeline in '" + desc.label() + "'");
            use(pipeline);
            for (CgBindingLayout.Slot slot : pipeline.desc().layout().slots()) {
                int i = pushed.indexOf(slot.binding());
                if (i < 0 || pushed.type(i) != slot.type())
                    throw new IllegalStateException(pipeline.desc().label() + " reads binding " + slot.binding()
                            + " (" + slot.type() + "), which was not pushed");
            }
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
            log.add("endPass");
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

    private final class Timer extends Obj implements CgTimerQuery {
        long frame = Long.MAX_VALUE;

        Timer(String label) { super(label); }

        @Override public long resultNanos() { return frame <= retired ? 0 : -1; }
    }
}
