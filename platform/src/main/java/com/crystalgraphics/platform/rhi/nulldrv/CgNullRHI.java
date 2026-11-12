package com.crystalgraphics.platform.rhi.nulldrv;

import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.rhi.resource.CgGpuHeap;
import com.crystalgraphics.platform.rhi.memory.CgTransientMemory;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
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
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * A {@link CgRHI} with no GPU: it keeps buffer contents, logs every command, and refuses what a Vulkan
 * device's validation would. What the engine's headless tests run on.
 *
 * <pre>{@code
 * CgNullRHI device = new CgNullRHI(64, 64);
 * // ... the engine records and executes ...
 * assertEquals(List.of("beginPass fbo colors=[#1:CLEAR]", "setPipeline #4", "draw 6 1 0 0", "endPass"),
 *         device.logSince(mark));
 * }</pre>
 *
 * <p>Frames retire {@code framesInFlight} frames after they end, as a GPU running that far behind would;
 * {@link #retireAll()} catches up. A released object is destroyed at its frame's retirement and refused from
 * then on. Readbacks return zeros. It places textures in heaps ({@link #transientMemory()}), refusing one outside its
 * heap or off its alignment.</p>
 */
public final class CgNullRHI implements CgRHI {

    /** Kept, never run: a null device has no close, and creates nothing on other threads. */
    private final List<Runnable> closing = new ArrayList<>();

    @Override
    public void onClose(Runnable task) {
        synchronized (closing) {
            closing.add(task);
        }
    }

    private static final int SPIRV_MAGIC = 0x07230203;

    /** The smallest module a test can hand {@link #createShaderModule}: a SPIR-V header and nothing else. */
    public static ByteBuffer emptySpirv() {
        ByteBuffer b = ByteBuffer.allocateDirect(20).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(SPIRV_MAGIC).putInt(0x00010500).putInt(0).putInt(1).putInt(0).flip();
        return b;
    }

    private static final CgRHI.Globals INFO = new CgRHI.Globals("recording", "CrystalGraphics", "0",
            new CgRHI.Globals.Limits(16384, 2048, 2048, 8, 8, 16, 32, 65536, 24, 8, 16, 4, 1 << 26,
                    256, 256, 16, 16384, 16f, CgRHI.Globals.Compute.MINIMUM), true, true, true, true, true, true, true, true, true,
            true);
    private CgRHI.Globals info = INFO;

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
    private boolean async, transfer, overlap;
    /**
     * Begun transitions, each with a hash of what it held when begun: ending it changed is refused. Arrays, since a
     * frame begins a few and an allocation test measures the engine, not this device.
     */
    private CgTransition[] begun = new CgTransition[8];
    private long[] begunHeld = new long[8];
    private int begunCount;
    private long asyncPoint;
    private Pass open;
    /** Unlogged, the last ended pass, begun again: an allocation test measures the engine, not this device. */
    private Pass spare;
    private ComputePass openCompute;
    private Texture surfaceColor, surfaceDepth;

    public CgNullRHI(int width, int height) {
        this(width, height, 2, true);
    }

    /** @param ownsSubmission {@code false} stands in for a hosted device: {@link #waitRetired} refuses */
    public CgNullRHI(int width, int height, int framesInFlight, boolean ownsSubmission) {
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
     * Answers {@code compute} for its compute limits in place of Vulkan's minimums: a test of work sized for a desktop
     * GPU, where a kernel wider than 128 would be refused.
     *
     * <pre>{@code
     * new CgNullRHI(64, 64).withCompute(new CgRHI.Globals.Compute(49152, 1024, 1024, 1024, 64, 65535, 65535, 65535, 32, 0xFF));
     * }</pre>
     */
    public CgNullRHI withCompute(CgRHI.Globals.Compute compute) {
        CgRHI.Globals.Limits l = INFO.limits();
        info = new CgRHI.Globals(INFO.name(), INFO.vendor(), INFO.driver(), new CgRHI.Globals.Limits(l.maxTextureSize(),
                l.max3DTextureSize(), l.maxArrayLayers(), l.maxColorAttachments(), l.maxSamples(), l.maxVertexAttributes(),
                l.maxTextureUnits(), l.maxUniformBlockSize(), l.maxUniformBufferBindings(), l.maxDynamicUniformBuffers(),
                l.maxStorageBufferBindings(), l.maxDynamicStorageBuffers(),
                l.maxTexelBufferElements(), l.uniformOffsetAlignment(), l.storageOffsetAlignment(), l.texelOffsetAlignment(),
                l.maxViewportSize(), l.maxAnisotropy(), compute), INFO.timestamps(), INFO.anisotropy(), INFO.nonSolidFill(),
                INFO.multiDrawIndirect(), INFO.indirectCount(), INFO.indirectFirstInstance(), INFO.asyncCompute(),
                INFO.drawParameters(), INFO.independentBlend(), INFO.bindless());
        return this;
    }

    /**
     * Stops keeping the log and the pass list, for a long run: every command is still validated. A harness
     * scene on it makes thousands a frame.
     */
    public CgNullRHI withoutLog() {
        logging = false;
        log.clear();
        passes.clear();
        return this;
    }

    private void record(String line) {
        if (logging) log.add(line);
    }

    /**
     * Makes this the process's device, as a test's: as {@link CgRHI}, {@code compiler}, and host imports of its surface
     * alone.
     *
     * <pre>{@code
     * CgNullRHI device = new CgNullRHI(64, 64).install(new ShadercGlslCompiler());
     * }</pre>
     */
    public CgNullRHI install(CgGlslCompiler compiler) {
        CgRHI.install(this);
        CgGlslCompiler.install(compiler);
        CgHostImports.install(CgHostImports.surface(this));
        return this;
    }

    /** Objects created and not yet destroyed. */
    public int liveObjects() { return live; }

    public boolean isDestroyed(CgRHIResource object) { return ((Obj) object).destroyed; }

    /** The id the log names an object by, as {@code #id}. */
    public static int idOf(CgRHIResource object) { return ((Obj) object).id; }

    /** Retires every ended frame now, as an idle GPU would. */
    public void retireAll() { retireThrough(frame - 1); }

    /** A new default framebuffer, as a window resize gives; the old images are released. */
    public void resize(int width, int height) {
        if (surfaceColor != null) release(surfaceColor);
        if (surfaceDepth != null) release(surfaceDepth);
        surfaceColor = (Texture) createTexture(new CgGpuTexture.Desc("surface", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, width, height, 1, 1, 1, CgGpuTexture.Usage.ALL));
        surfaceDepth = (Texture) createTexture(new CgGpuTexture.Desc("surfaceDepth", CgGpuTexture.Kind.D2,
                CgFormat.DEPTH32_FLOAT_STENCIL8, width, height, 1, 1, 1, CgGpuTexture.Usage.ALL));
    }

    // ── CgRHI ───────────────────────────────────────────────────────────────

    @Override public CgRHI.Globals globals() { return info; }

    @Override public void describe(BiConsumer<String, String> fact) { fact.accept("version", "recording"); }

    @Override public boolean supports(CgFormat format, CgGpuTexture.Usage usage) { return true; }

    @Override public boolean ownedByCurrentThread() { return Thread.currentThread() == owner; }

    @Override
    public CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc) {
        Buffer b = new Buffer(desc);
        if (logging) record("createBuffer #" + b.id + " " + desc.label() + " " + desc.size() + (desc.hostVisible() ? " host" : ""));
        return b;
    }

    @Override
    public CgGpuTexture createTexture(CgGpuTexture.Desc desc) {
        if (desc.width() < 1 || desc.height() < 1 || desc.depthOrLayers() < 1 || desc.mips() < 1)
            throw new IllegalArgumentException("Empty texture " + desc);
        Texture t = new Texture(desc);
        if (logging) record("createTexture #" + t.id + " " + desc.label() + " " + desc.kind() + " " + desc.format() + " "
                + desc.width() + "x" + desc.height() + "x" + desc.depthOrLayers() + " mips=" + desc.mips()
                + (desc.samples() > 1 ? " samples=" + desc.samples() : ""));
        return t;
    }

    @Override
    public CgGpuSampler createSampler(CgGpuSampler.Desc desc) {
        Sampler s = new Sampler(desc);
        if (logging) record("createSampler #" + s.id);
        return s;
    }

    @Override
    public CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label) {
        if (spirv.remaining() < 20 || spirv.remaining() % 4 != 0
                || spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(spirv.position()) != SPIRV_MAGIC)
            throw new IllegalArgumentException(label + ": not SPIR-V");
        Module m = new Module(stage, label);
        if (logging) record("createShaderModule #" + m.id + " " + label + " " + stage);
        return m;
    }

    @Override
    public CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots, boolean pushed) {
        Layout l = new Layout(label, new ArrayList<>(slots), pushed);
        if (logging) record("createBindingLayout #" + l.id + " " + label + " " + slots.size() + (pushed ? " pushed" : ""));
        return l;
    }

    @Override
    public CgBindingSet createBindingSet(String label, CgBindingLayout layout, CgBindings contents) {
        use(layout);
        if (layout.pushed()) throw new IllegalArgumentException(label + ": a set of " + layout.label() + ", which is pushed");
        BindingSet s = new BindingSet(label, layout);
        push(contents, s.contents, null);
        pushedAll(label, layout, s.contents);
        if (logging) record("createBindingSet #" + s.id + " " + label + " " + ref(layout));
        return s;
    }

    @Override
    public CgGraphicsPipeline createPipeline(CgGraphicsPipelineDesc desc) {
        use(desc.vertex(), desc.fragment());
        use(desc.layout());
        Pipeline p = new Pipeline(desc);
        if (logging) record("createPipeline #" + p.id + " " + desc.label());
        return p;
    }

    @Override
    public CgComputePipeline createComputePipeline(String label, CgShaderModule module, CgPipelineLayout layout) {
        use(module);
        use(layout);
        if (module.stage() != CgShaderModule.Stage.COMPUTE)
            throw new IllegalArgumentException(label + ": a compute pipeline from a " + module.stage() + " module");
        ComputePipeline p = new ComputePipeline(label, module, layout);
        if (logging) record("createComputePipeline #" + p.id + " " + label);
        return p;
    }

    // ── the bindless heap ──────────────────────────────────────────────────────

    private Layout heapLayout;
    private BindingSet heapSet;
    private final Map<CgTextureView, Map<CgGpuSampler, Integer>> heapViews = new HashMap<>();
    private final List<Integer> heapFree = new ArrayList<>();
    private int heapNext;

    @Override
    public CgBindingLayout bindlessLayout() {
        if (heapLayout == null) {
            heapLayout = new Layout("bindless", List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)), false);
            heapSet = new BindingSet("bindless", heapLayout);
        }
        return heapLayout;
    }

    @Override
    public CgBindingSet bindlessSet() {
        bindlessLayout();
        return heapSet;
    }

    @Override
    public int bindlessIndex(CgTextureView view, CgGpuSampler sampler) {
        use(view.texture(), sampler);
        if (!view.texture().desc().usage().contains(CgGpuTexture.Usage.SAMPLED))
            throw new IllegalStateException(view.texture().label() + " in the bindless heap without SAMPLED usage");
        Map<CgGpuSampler, Integer> bySampler = heapViews.computeIfAbsent(view, v -> new IdentityHashMap<>());
        Integer at = bySampler.get(sampler);
        if (at != null) return at;
        int index = heapFree.isEmpty() ? heapNext++ : heapFree.remove(heapFree.size() - 1);
        bySampler.put(sampler, index);
        if (logging) record("bindlessIndex " + ref(view.texture()) + " " + ref(sampler) + " " + index);
        return index;
    }

    /** A released texture's heap indices, free again once its frame has retired. */
    private void forgetHeap(CgRHIResource o) {
        if (!(o instanceof Texture)) return;
        heapViews.entrySet().removeIf(e -> {
            if (e.getKey().texture() != o) return false;
            heapFree.addAll(e.getValue().values());
            return true;
        });
    }

    @Override
    public CgTimerQuery createTimerQuery(String label) {
        Timer t = new Timer(label);
        if (logging) record("createTimerQuery #" + t.id);
        return t;
    }

    @Override
    public void release(CgRHIResource object) {
        Obj o = (Obj) object;
        if (o.released) throw new IllegalStateException("#" + o.id + " " + o.label + " released twice");
        o.released = true;
        if (logging) record("release #" + o.id);
        whenRetired(frame, () -> {
            o.destroyed = true;
            forgetHeap(o);
            live--;
            if (logging) record("destroy #" + o.id);
        });
    }

    @Override public CgCommandList encoder() { return encoder; }

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
        if (async) throw new IllegalStateException("endFrame inside async work");
        if (begunCount > 0) throw new IllegalStateException("endFrame with " + begunCount + " transitions begun and not ended");
        if (overlap) throw new IllegalStateException("endFrame inside a storage overlap");
        if (logging) record("endFrame " + frame);
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
            if (logging) record("retire " + retired);
            if (retirements.isEmpty() || retirements.firstKey() != retired) continue;   // firstKey: no box for a miss
            List<Runnable> due = retirements.remove(retirements.firstKey());
            due.forEach(Runnable::run);
        }
    }

    // ── validation ─────────────────────────────────────────────────────────────

    private void use(CgRHIResource object) {
        if (object != null && ((Obj) object).destroyed)
            throw new IllegalStateException("#" + ((Obj) object).id + " " + object.label() + " used after it was destroyed");
    }

    private void use(CgRHIResource a, CgRHIResource b) {
        use(a);
        use(b);
    }

    private void use(CgRHIResource... objects) {
        for (CgRHIResource o : objects) {
            if (o != null && ((Obj) o).destroyed)
                throw new IllegalStateException("#" + ((Obj) o).id + " " + o.label() + " used after it was destroyed");
        }
    }

    private void use(CgPipelineLayout layout) {
        for (int i = 0; i < layout.sets().size(); i++) use(layout.sets().get(i));
    }

    private void outsidePass(String what) {
        if (open != null) throw new IllegalStateException(what + " inside pass '" + open.desc.label() + "'");
    }

    private void noCompute(String what) {
        if (openCompute != null) throw new IllegalStateException(what + " inside compute pass '" + openCompute.label + "'");
    }

    /** {@code from} copied into {@code into}, every resource checked live; logged as {@code line} unless null. */
    private void push(CgBindings from, CgBindings into, String line) {
        into.clear();
        StringBuilder s = logging && line != null ? new StringBuilder(line) : null;
        for (int i = 0; i < from.count(); i++) {
            if (s != null) s.append(' ').append(from.binding(i)).append(':');
            switch (from.type(i)) {
                case SAMPLED_TEXTURE -> {
                    use(from.view(i).texture(), from.sampler(i));
                    into.texture(from.binding(i), from.view(i), from.sampler(i));
                    if (s != null) s.append(ref(from.view(i).texture())).append('/').append(ref(from.sampler(i)));
                }
                case STORAGE_IMAGE -> {
                    use(from.view(i).texture());
                    into.image(from.binding(i), from.view(i));
                    if (s != null) s.append(ref(from.view(i).texture()));
                }
                case TEXEL_BUFFER -> {
                    use(from.buffer(i));
                    into.texel(from.binding(i), from.buffer(i), from.offset(i), from.size(i), from.texelFormat(i));
                    if (s != null) s.append(ref(from.buffer(i))).append('+').append(from.offset(i));
                }
                default -> {
                    use(from.buffer(i));
                    into.buffer(from.binding(i), from.type(i), from.buffer(i), from.offset(i), from.size(i));
                    if (s != null) s.append(ref(from.buffer(i))).append('+').append(from.offset(i));
                }
            }
        }
        if (s != null) record(s.toString());
    }

    /** Every slot of {@code layout} pushed with its type. */
    private static void pushedAll(String label, CgBindingLayout layout, CgBindings pushed) {
        List<CgBindingLayout.Slot> slots = layout.slots();
        for (int s = 0; s < slots.size(); s++) {
            CgBindingLayout.Slot slot = slots.get(s);
            int i = pushed.indexOf(slot.binding());
            if (i < 0 || pushed.type(i) != slot.type())
                throw new IllegalStateException(label + " reads binding " + slot.binding() + " (" + slot.type()
                        + "), which was not pushed");
        }
    }

    /**
     * Every set of {@code layout} there: the pushed one pushed in full, each other bound with its own layout. A set with
     * no bindings, holding a place below a higher one, need not be bound, as Vulkan asks only for the sets a shader reads.
     */
    private void boundAll(String label, CgPipelineLayout layout, CgBindings pushed, CgBindingSet[] sets) {
        for (int i = 0; i < layout.sets().size(); i++) {
            CgBindingLayout want = layout.sets().get(i);
            if (want.slots().isEmpty()) continue;
            if (want.pushed()) {
                pushedAll(label, want, pushed);
                continue;
            }
            CgBindingSet set = i < sets.length ? sets[i] : null;
            if (set == null) throw new IllegalStateException(label + " reads set " + i + " (" + want.label() + "), which is not bound");
            if (set.layout() != want)
                throw new IllegalStateException(label + " reads set " + i + " as " + want.label() + "; " + set.label()
                        + " bound there is " + set.layout().label());
            use(set);
        }
    }

    /** {@code bytes} at {@code offset} inside {@code layout}'s push constants. */
    private static void constants(String label, CgPipelineLayout layout, int offset, int bytes) {
        if (offset % 4 != 0 || bytes % 4 != 0 || offset < 0 || offset + bytes > layout.pushConstantBytes())
            throw new IllegalStateException(label + " takes " + layout.pushConstantBytes() + " bytes of push constants; "
                    + bytes + " pushed at " + offset);
    }

    private void bindSet(CgBindingSet[] sets, int index, CgBindingSet set, int[] offsets, int count) {
        use(set);
        if (index < 0 || index >= sets.length) throw new IllegalArgumentException("set " + index + ": sets are 0-" + (sets.length - 1));
        int dynamic = set.layout().dynamicCount();
        if (count != dynamic) throw new IllegalArgumentException(set.label() + " takes " + dynamic + " dynamic offsets; " + count + " given");
        // The stricter alignment of the two kinds a dynamic slot may be
        int align = Math.max(info.limits().uniformOffsetAlignment(), info.limits().storageOffsetAlignment());
        for (int i = 0; i < count; i++) {
            if (offsets[i] < 0 || offsets[i] % align != 0)
                throw new IllegalArgumentException(set.label() + ": dynamic offset " + offsets[i] + " is not a multiple of " + align);
        }
        sets[index] = set;
        if (logging) record("setBindingSet " + index + " " + ref(set) + (count == 0 ? "" : " at " + Arrays.toString(Arrays.copyOf(offsets, count))));
    }

    /** What {@code t} holds, hashed, to tell a batch ended as begun from one changed between. */
    private static long held(CgTransition t) {
        long h = t.count();
        for (int i = 0; i < t.count(); i++) {
            Obj o = (Obj) (t.buffer(i) != null ? t.buffer(i) : t.image(i));
            h = (h * 31 + (o == null ? 0 : o.id)) * 31 + t.from(i);
            h = h * 31 + t.to(i);
        }
        return h;
    }

    private int begunAt(CgTransition t) {
        for (int i = 0; i < begunCount; i++) if (begun[i] == t) return i;
        return -1;
    }

    private void moves(String what, CgTransition t) {
        for (int i = 0; i < t.count(); i++) {
            use(t.buffer(i) != null ? t.buffer(i) : t.image(i));
            if (t.image(i) != null) usable(t.image(i), t.from(i) | t.to(i));
        }
        if (!logging) return;
        StringBuilder s = new StringBuilder(what);
        for (int i = 0; i < t.count(); i++) {
            s.append(' ').append(t.buffer(i) != null ? ref(t.buffer(i)) : ref(t.image(i))).append(':')
                    .append(CgAccess.names(t.from(i))).append('>').append(CgAccess.names(t.to(i)));
        }
        record(s.toString());
    }

    /** An image's layout for {@code access} needs the usage it was made with: Vulkan refuses the barrier otherwise. */
    private static void usable(CgGpuTexture texture, int access) {
        needs(texture, access, CgAccess.SAMPLED_READ, CgGpuTexture.Usage.SAMPLED);
        needs(texture, access, CgAccess.STORAGE, CgGpuTexture.Usage.STORAGE);
        needs(texture, access, CgAccess.COLOR_WRITE | CgAccess.DEPTH_READ | CgAccess.DEPTH_WRITE, CgGpuTexture.Usage.ATTACHMENT);
        needs(texture, access, CgAccess.COPY_READ, CgGpuTexture.Usage.COPY_SRC);
        needs(texture, access, CgAccess.COPY_WRITE, CgGpuTexture.Usage.COPY_DST);
    }

    private static void needs(CgGpuTexture texture, int access, int bits, CgGpuTexture.Usage usage) {
        if ((access & bits) != 0 && !texture.desc().usage().contains(usage))
            throw new IllegalStateException(texture.label() + " moves to " + CgAccess.names(access & bits) + " without "
                    + usage + " usage");
    }

    private static boolean writesDepthOrStencil(CgGraphicsPipelineDesc.DepthStencil d) {
        if (d.depthWrite()) return true;
        if (!d.stencilTest() || d.writeMask() == 0) return false;
        return writes(d.front()) || writes(d.back());
    }

    private static boolean writes(CgGraphicsPipelineDesc.StencilFace f) {
        return f.fail() != CgGraphicsPipelineDesc.StencilOp.KEEP || f.depthFail() != CgGraphicsPipelineDesc.StencilOp.KEEP
                || f.pass() != CgGraphicsPipelineDesc.StencilOp.KEEP;
    }

    /** Vulkan's rule for an indirect draw's stride: a multiple of 4, at least one command, wherever it draws more than one. */
    private void indirect(String what, int draws, int stride, int command) {
        if (draws > 1 && !info.multiDrawIndirect()) throw new IllegalStateException(what + " of " + draws + " draws: no multi-draw indirect");
        if (draws > 1 && (stride % 4 != 0 || stride < command))
            throw new IllegalStateException(what + " stride " + stride + ": a multiple of 4, at least " + command);
    }

    private static String ref(CgRHIResource o) { return o == null ? "-" : "#" + ((Obj) o).id; }

    // ── the encoder and the pass ───────────────────────────────────────────────

    private final class Encoder implements CgCommandList {

        @Override
        public CgRenderPass beginPass(CgPassDesc desc) {
            outsidePass("beginPass");
            noCompute("beginPass");
            if (async) throw new IllegalStateException("A render pass inside async work: a compute queue draws nothing");
            if (transfer) throw new IllegalStateException("A render pass inside a transfer: a transfer queue draws nothing");
            for (int i = 0; i < desc.colors().size(); i++) use(desc.colors().get(i).view().texture());
            if (desc.depth() != null) use(desc.depth().view().texture());
            if (desc.depth() != null && desc.depth().readOnly()
                    && (desc.depth().depthLoad() == CgPassDesc.LoadOp.CLEAR || desc.depth().stencilLoad() == CgPassDesc.LoadOp.CLEAR))
                throw new IllegalStateException("Pass '" + desc.label() + "' clears a read-only depth attachment");
            if (!logging && spare != null) {
                Pass p = spare;
                spare = null;
                return open = p.reopen(desc);
            }
            if (logging) {
                StringBuilder s = new StringBuilder("beginPass ").append(desc.label()).append(" colors=[");
                for (int i = 0; i < desc.colors().size(); i++) {
                    CgPassDesc.Color c = desc.colors().get(i);
                    s.append(i == 0 ? "" : ", ").append(ref(c.view().texture())).append(':').append(c.load());
                }
                s.append(']');
                if (desc.depth() != null) {
                    s.append(" depth=").append(ref(desc.depth().view().texture())).append(':')
                            .append(desc.depth().depthLoad()).append('/').append(desc.depth().stencilLoad());
                }
                passes.add(desc);
                record(s.toString());
            }
            return open = new Pass(desc);
        }

        @Override
        public CgComputePass beginCompute(String label) {
            outsidePass("beginCompute");
            noCompute("beginCompute");
            if (transfer) throw new IllegalStateException("A compute pass inside a transfer");
            // One pass object reused once ended: a steady frame allocates none.
            if (spareCompute == null || !spareCompute.ended) spareCompute = new ComputePass(label);
            else spareCompute.reuse(label);
            openCompute = spareCompute;
            if (logging) record("beginCompute " + label);
            return openCompute;
        }

        @Override
        public void bufferBarrier(CgGpuBuffer buffer, int from, int to) {
            outsidePass("bufferBarrier");
            use(buffer);
            if (logging) record("bufferBarrier " + ref(buffer) + " " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void imageBarrier(CgGpuTexture texture, int from, int to) {
            outsidePass("imageBarrier");
            use(texture);
            if (logging) record("imageBarrier " + ref(texture) + " " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void memoryBarrier(int from, int to) {
            outsidePass("memoryBarrier");
            if (logging) record("memoryBarrier " + CgAccess.names(from) + " " + CgAccess.names(to));
        }

        @Override
        public void transition(CgTransition t) {
            outsidePass("transition");
            if (begunAt(t) >= 0) throw new IllegalStateException("transition of a batch begun and not ended");
            moves("transition", t);
        }

        @Override
        public void beginTransitions(CgTransition t) {
            outsidePass("beginTransitions");
            if (begunAt(t) >= 0) throw new IllegalStateException("beginTransitions twice without an end");
            moves("beginTransitions", t);
            if (begunCount == begun.length) {
                begun = Arrays.copyOf(begun, begunCount * 2);
                begunHeld = Arrays.copyOf(begunHeld, begunCount * 2);
            }
            begun[begunCount] = t;
            begunHeld[begunCount++] = held(t);
        }

        @Override
        public void endTransitions(CgTransition t) {
            outsidePass("endTransitions");
            int at = begunAt(t);
            if (at < 0) throw new IllegalStateException("endTransitions of a batch never begun");
            long was = begunHeld[at];
            begun[at] = begun[--begunCount];
            begunHeld[at] = begunHeld[begunCount];
            begun[begunCount] = null;
            if (was != held(t)) throw new IllegalStateException("endTransitions of a batch changed since it was begun");
            moves("endTransitions", t);
        }

        @Override
        public void beginStorageOverlap() {
            outsidePass("beginStorageOverlap");
            if (overlap) throw new IllegalStateException("beginStorageOverlap inside a storage overlap");
            overlap = true;
            if (logging) record("beginStorageOverlap");
        }

        @Override
        public void endStorageOverlap() {
            outsidePass("endStorageOverlap");
            if (!overlap) throw new IllegalStateException("endStorageOverlap with none begun");
            overlap = false;
            if (logging) record("endStorageOverlap");
        }

        @Override
        public void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data) {
            outsidePass("writeBuffer");
            use(dst);
            int n = data.remaining(), from = data.position(), at = (int) dstOffset;
            ByteBuffer to = ((Buffer) dst).memory;
            // Absolute reads and writes, as copyBuffer's: a view of either buffer would allocate each write.
            for (int i = 0; i < n; i++) to.put(at + i, data.get(from + i));
            if (logging) record("writeBuffer " + ref(dst) + "+" + dstOffset + " " + n);
        }

        @Override
        public void fillBuffer(CgGpuBuffer dst, long dstOffset, long size, int value) {
            outsidePass("fillBuffer");
            use(dst);
            ByteBuffer to = ((Buffer) dst).memory;   // native order; absolute writes, no view to allocate
            for (long at = dstOffset; at < dstOffset + size; at += 4) to.putInt((int) at, value);
            if (logging) record("fillBuffer " + ref(dst) + "+" + dstOffset + " " + size);
        }

        @Override
        public void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size) {
            outsidePass("copyBuffer");
            use(src, dst);
            // Absolute reads and writes: a view of either buffer would allocate each copy.
            ByteBuffer from = ((Buffer) src).memory, to = ((Buffer) dst).memory;
            int s = (int) srcOffset, d = (int) dstOffset, n = (int) size, i = 0;
            if (from.order() == to.order()) for (; i + 8 <= n; i += 8) to.putLong(d + i, from.getLong(s + i));
            for (; i < n; i++) to.put(d + i, from.get(s + i));
            if (logging) record("copyBuffer " + ref(src) + "+" + srcOffset + " " + ref(dst) + "+" + dstOffset + " " + size);
        }

        @Override
        public void writeTexture(CgGpuTexture dst, CgTextureRegion r, ByteBuffer data) {
            outsidePass("writeTexture");
            use(dst);
            long need = dst.desc().format().bytes(r.width(), r.height(), r.depth());
            if (data.remaining() < need)
                throw new IllegalArgumentException("writeTexture needs " + need + " bytes, got " + data.remaining());
            if (logging) record("writeTexture " + ref(dst) + " " + region(r));
        }

        @Override
        public void copyBufferToTexture(CgGpuBuffer src, long srcOffset, CgGpuTexture dst, CgTextureRegion r) {
            outsidePass("copyBufferToTexture");
            use(src, dst);
            long need = dst.desc().format().bytes(r.width(), r.height(), r.depth());
            if (srcOffset % 4 != 0 || srcOffset % dst.desc().format().bytes() != 0)
                throw new IllegalArgumentException("copyBufferToTexture from offset " + srcOffset);
            if (srcOffset + need > src.size())
                throw new IllegalArgumentException("copyBufferToTexture needs " + need + " bytes at " + srcOffset
                        + " of a " + src.size() + "-byte buffer");
            if (logging) record("copyBufferToTexture " + ref(src) + "+" + srcOffset + " " + ref(dst) + " " + region(r));
        }

        @Override
        public void copyTexture(CgGpuTexture src, CgTextureRegion sr, CgGpuTexture dst, CgTextureRegion dr) {
            outsidePass("copyTexture");
            use(src, dst);
            if (logging) record("copyTexture " + ref(src) + " " + region(sr) + " " + ref(dst) + " " + region(dr));
        }

        @Override
        public void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
                         CgTextureView dst, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter) {
            outsidePass("blit");
            use(src.texture(), dst.texture());
            if (logging) record("blit " + ref(src.texture()) + " " + sx0 + "," + sy0 + "," + sx1 + "," + sy1 + " "
                    + ref(dst.texture()) + " " + dx0 + "," + dy0 + "," + dx1 + "," + dy1 + " " + filter);
        }

        @Override
        public void resolve(CgTextureView src, CgTextureView dst) {
            outsidePass("resolve");
            use(src.texture(), dst.texture());
            if (logging) record("resolve " + ref(src.texture()) + " " + ref(dst.texture()));
        }

        @Override
        public void generateMipmaps(CgGpuTexture texture) {
            outsidePass("generateMipmaps");
            use(texture);
            if (logging) record("generateMipmaps " + ref(texture));
        }

        @Override
        public void readTexture(CgGpuTexture src, CgTextureRegion r, ByteBuffer out) {
            outsidePass("readTexture");
            use(src);
            ByteBuffer o = out.duplicate();
            while (o.hasRemaining()) o.put((byte) 0);
            if (logging) record("readTexture " + ref(src) + " " + region(r));
        }

        @Override
        public void readBuffer(CgGpuBuffer src, long srcOffset, ByteBuffer out) {
            outsidePass("readBuffer");
            use(src);
            ByteBuffer from = ((Buffer) src).memory;
            int size = out.remaining(), at = out.position(), base = (int) srcOffset;
            for (int i = 0; i < size; i++) out.put(at + i, from.get(base + i));   // absolute: no view to allocate
            out.position(at + size);
            if (logging) record("readBuffer " + ref(src) + "+" + srcOffset + " " + size);
        }

        @Override
        public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion r, CgGpuBuffer dst, long dstOffset) {
            outsidePass("copyTextureToBuffer");
            use(src, dst);
            if (logging) record("copyTextureToBuffer " + ref(src) + " " + region(r) + " " + ref(dst) + "+" + dstOffset);
        }

        /** Recorded in order: async on paper, so a test sees where the tracker puts the brackets. */
        @Override
        public void beginAsync() {
            outsidePass("beginAsync");
            if (async) throw new IllegalStateException("beginAsync inside async work");
            if (transfer) throw new IllegalStateException("beginAsync inside a transfer");
            async = true;
            if (logging) record("beginAsync");
        }

        @Override
        public long endAsync() {
            if (!async) throw new IllegalStateException("endAsync with no async work open");
            if (openCompute != null) throw new IllegalStateException("endAsync with a compute pass open");
            async = false;
            ++asyncPoint;
            if (logging) record("endAsync " + asyncPoint);
            return asyncPoint;
        }

        @Override
        public void waitAsync(long point) {
            outsidePass("waitAsync");
            if (async) throw new IllegalStateException("waitAsync inside async work");
            if (logging) record("waitAsync " + point);
        }

        /** Recorded in order, as a device with no transfer queue runs it. */
        @Override
        public void beginTransfer() {
            outsidePass("beginTransfer");
            noCompute("beginTransfer");
            if (transfer) throw new IllegalStateException("beginTransfer inside a transfer");
            transfer = true;
            if (logging) record("beginTransfer");
        }

        @Override
        public void endTransfer() {
            if (!transfer) throw new IllegalStateException("endTransfer with no transfer open");
            transfer = false;
            if (logging) record("endTransfer");
        }

        @Override
        public void finish() {
            outsidePass("finish");
            if (!ownsSubmission) throw new IllegalStateException("A hosted device cannot wait for frame " + frame);
            if (logging) record("finish");
        }

        @Override
        public void beginTimer(CgTimerQuery query) {
            use(query);
            if (logging) record("beginTimer " + ref(query));
        }

        @Override
        public void endTimer(CgTimerQuery query) {
            use(query);
            ((Timer) query).frame = frame;
            if (logging) record("endTimer " + ref(query));
        }

        @Override
        public void timestamp(CgTimerQuery query) {
            use(query);
            ((Timer) query).frame = frame;
            if (logging) record("timestamp " + ref(query));
        }

        private String region(CgTextureRegion r) {
            return "mip" + r.mip() + " " + r.x() + "," + r.y() + "," + r.z() + " " + r.width() + "x" + r.height() + "x" + r.depth();
        }
    }

    private final class Pass implements CgRenderPass {
        CgPassDesc desc;
        final CgBindings pushed = new CgBindings();
        final CgBindingSet[] sets = new CgBindingSet[8];
        final Map<Integer, Buffer> vertexBuffers = new TreeMap<>();
        Pipeline pipeline;
        Buffer indexBuffer;
        boolean ended;

        Pass(CgPassDesc desc) { this.desc = desc; }

        /** This ended pass begun again on {@code next}, as if new. */
        Pass reopen(CgPassDesc next) {
            desc = next;
            pushed.clear();
            Arrays.fill(sets, null);
            vertexBuffers.clear();
            pipeline = null;
            indexBuffer = null;
            ended = false;
            return this;
        }

        private void live() {
            if (ended || open != this) throw new IllegalStateException("Pass '" + desc.label() + "' has ended");
        }

        @Override
        public void setPipeline(CgGraphicsPipeline p) {
            live();
            use(p);
            CgGraphicsPipelineDesc d = p.desc();
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
            if (desc.depth() != null && desc.depth().readOnly() && writesDepthOrStencil(d.depthStencil()))
                throw new IllegalStateException(d.label() + " writes depth or stencil, read-only in pass '" + desc.label() + "'");
            pipeline = (Pipeline) p;
            if (logging) record("setPipeline " + ref(p));
        }

        @Override
        public void pushBindings(CgBindings b) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushBindings before a pipeline in '" + desc.label() + "'");
            if (pipeline.desc().layout().pushSet() < 0) throw new IllegalStateException(pipeline.desc().label() + " pushes no set");
            push(b, pushed, "pushBindings");
        }

        @Override
        public void setBindingSet(int set, CgBindingSet bindings, int[] dynamicOffsets, int count) {
            live();
            bindSet(sets, set, bindings, dynamicOffsets, count);
        }

        @Override
        public void pushConstants(int offset, int value) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushConstants before a pipeline in '" + desc.label() + "'");
            constants(pipeline.desc().label(), pipeline.desc().layout(), offset, 4);
            if (logging) record("pushConstants " + offset + " " + value);
        }

        @Override
        public void pushConstants(int offset, ByteBuffer data) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushConstants before a pipeline in '" + desc.label() + "'");
            constants(pipeline.desc().label(), pipeline.desc().layout(), offset, data.remaining());
            if (logging) record("pushConstants " + offset + " +" + data.remaining());
        }

        @Override
        public void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset) {
            live();
            use(buffer);
            vertexBuffers.put(binding, (Buffer) buffer);
            if (logging) record("setVertexBuffer " + binding + " " + ref(buffer) + "+" + offset);
        }

        @Override
        public void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide) {
            live();
            use(buffer);
            indexBuffer = (Buffer) buffer;
            if (logging) record("setIndexBuffer " + ref(buffer) + "+" + offset + (wide ? " u32" : " u16"));
        }

        @Override
        public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
            live();
            if (logging) record("setViewport " + (int) x + "," + (int) y + " " + (int) width + "x" + (int) height);
        }

        @Override
        public void setScissor(int x, int y, int width, int height) {
            live();
            if (logging) record("setScissor " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void setDepthBias(float constant, float slope) {
            live();
            if (logging) record("setDepthBias " + constant + " " + slope);
        }

        @Override
        public void setStencilReference(int reference) {
            live();
            if (logging) record("setStencilReference " + reference);
        }

        @Override
        public void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height) {
            live();
            if (attachment >= desc.colors().size())
                throw new IllegalStateException("No colour attachment " + attachment + " in '" + desc.label() + "'");
            if (logging) record("clearColor " + attachment + " " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                                      int x, int y, int width, int height) {
            live();
            if (desc.depth() == null) throw new IllegalStateException("No depth attachment in '" + desc.label() + "'");
            if (desc.depth().readOnly()) throw new IllegalStateException("clearDepthStencil in '" + desc.label() + "', read-only");
            if (logging) record("clearDepthStencil" + (depth ? " depth" : "") + (stencil ? " stencil" : "") + " "
                    + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
            drawable();
            draws++;
            if (logging) record("draw " + vertexCount + " " + instanceCount + " " + firstVertex + " " + firstInstance);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
            drawable();
            if (indexBuffer == null) throw new IllegalStateException("drawIndexed with no index buffer");
            use(indexBuffer);
            draws++;
            if (logging) record("drawIndexed " + indexCount + " " + instanceCount + " " + firstIndex + " " + baseVertex + " " + firstInstance);
        }

        @Override
        public void drawIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            drawable();
            indirect("drawIndirect", drawCount, stride, 16);
            use(buffer);
            draws++;
            if (logging) record("drawIndirect " + ref(buffer) + "+" + offset + " " + drawCount + " " + stride);
        }

        @Override
        public void drawIndexedIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
            drawable();
            indirect("drawIndexedIndirect", drawCount, stride, 20);
            if (indexBuffer == null) throw new IllegalStateException("drawIndexedIndirect with no index buffer");
            use(buffer, indexBuffer);
            draws++;
            if (logging) record("drawIndexedIndirect " + ref(buffer) + "+" + offset + " " + drawCount + " " + stride);
        }

        @Override
        public void drawIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws,
                                      int stride) {
            drawable();
            if (!info.indirectCount()) throw new IllegalStateException("drawIndirectCount: the device has no indirect count");
            indirect("drawIndirectCount", maxDraws, stride, 16);
            use(buffer, count);
            draws++;
            if (logging) record("drawIndirectCount " + ref(buffer) + "+" + offset + " " + ref(count) + "+" + countOffset + " "
                    + maxDraws + " " + stride);
        }

        @Override
        public void drawIndexedIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset,
                                             int maxDraws, int stride) {
            drawable();
            if (!info.indirectCount()) throw new IllegalStateException("drawIndexedIndirectCount: the device has no indirect count");
            indirect("drawIndexedIndirectCount", maxDraws, stride, 20);
            if (indexBuffer == null) throw new IllegalStateException("drawIndexedIndirectCount with no index buffer");
            use(buffer, count, indexBuffer);
            draws++;
            if (logging) record("drawIndexedIndirectCount " + ref(buffer) + "+" + offset + " " + ref(count) + "+" + countOffset + " "
                    + maxDraws + " " + stride);
        }

        private void drawable() {
            live();
            if (pipeline == null) throw new IllegalStateException("Draw with no pipeline in '" + desc.label() + "'");
            use(pipeline);
            boundAll(pipeline.desc().label(), pipeline.desc().layout(), pushed, sets);
            List<CgGraphicsPipelineDesc.VertexBuffer> reads = pipeline.desc().vertexBuffers();
            for (int i = 0; i < reads.size(); i++) {
                CgGraphicsPipelineDesc.VertexBuffer vb = reads.get(i);
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
            if (logging) record("endPass");
            else spare = this;
        }
    }

    /** The alignment a placed texture's offset needs here: 64 KiB, as a desktop GPU's optimal images. */
    public static final long PLACEMENT_ALIGNMENT = 64 << 10;

    private final CgTransientMemory transientMemory = new TransientMemory();

    @Override
    public CgTransientMemory transientMemory() {
        return transientMemory;
    }

    /** One memory type; a texture needs its mips' bytes, unpadded. */
    private final class TransientMemory implements CgTransientMemory {

        @Override
        public Requirements requirements(CgGpuTexture.Desc desc) {
            boolean volume = desc.kind() == CgGpuTexture.Kind.D3;
            long size = 0;
            for (int m = 0; m < desc.mips(); m++) {
                size += desc.format().bytes(Math.max(1, desc.width() >> m), Math.max(1, desc.height() >> m),
                        volume ? Math.max(1, desc.depthOrLayers() >> m) : desc.depthOrLayers());
            }
            return new Requirements(size * desc.samples(), PLACEMENT_ALIGNMENT, 1);
        }

        @Override
        public CgGpuHeap createHeap(String label, long size, int memoryTypes) {
            if ((memoryTypes & 1) == 0) throw new IllegalArgumentException(label + ": no memory type of " + memoryTypes);
            Heap h = new Heap(label, size);
            record("createHeap #" + h.id + " " + label + " " + size);
            return h;
        }

        @Override
        public CgGpuTexture place(CgGpuHeap heap, long offset, CgGpuTexture.Desc desc) {
            use(heap);
            Requirements need = requirements(desc);
            if (offset < 0 || offset % need.alignment() != 0 || offset + need.size() > heap.size()) {
                throw new IllegalArgumentException(desc.label() + " (" + need.size() + " bytes, aligned to "
                        + need.alignment() + ") at " + offset + " in " + heap.label() + " of " + heap.size());
            }
            Texture t = new Texture(desc);
            record("place #" + t.id + " " + desc.label() + " in #" + ((Obj) heap).id + " at " + offset);
            return t;
        }

        @Override
        public void discard(CgGpuTexture texture) {
            use(texture);
            if (logging) record("discard #" + ((Obj) texture).id);
        }
    }

    // ── objects ────────────────────────────────────────────────────────────────

    private abstract class Obj implements CgRHIResource {
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

    private final class Heap extends Obj implements CgGpuHeap {
        final long size;

        Heap(String label, long size) {
            super(label);
            this.size = size;
        }

        @Override public long size() { return size; }

        @Override public int memoryType() { return 0; }
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
        final boolean pushed;

        Layout(String label, List<Slot> slots, boolean pushed) {
            super(label);
            this.slots = slots;
            this.pushed = pushed;
        }

        @Override public List<Slot> slots() { return slots; }

        @Override public boolean pushed() { return pushed; }
    }

    private final class BindingSet extends Obj implements CgBindingSet {
        final CgBindingLayout layout;
        final CgBindings contents = new CgBindings();

        BindingSet(String label, CgBindingLayout layout) {
            super(label);
            this.layout = layout;
        }

        @Override public CgBindingLayout layout() { return layout; }
    }

    private final class Pipeline extends Obj implements CgGraphicsPipeline {
        final CgGraphicsPipelineDesc desc;

        Pipeline(CgGraphicsPipelineDesc desc) {
            super(desc.label());
            this.desc = desc;
        }

        @Override public CgGraphicsPipelineDesc desc() { return desc; }
    }

    private ComputePass spareCompute;

    private final class ComputePass implements CgComputePass {
        String label;
        final CgBindings pushed = new CgBindings();
        final CgBindingSet[] sets = new CgBindingSet[8];
        ComputePipeline pipeline;
        boolean ended;

        ComputePass(String label) { this.label = label; }

        void reuse(String label) {
            this.label = label;
            pushed.clear();
            Arrays.fill(sets, null);
            pipeline = null;
            ended = false;
        }

        private void live() {
            if (ended || openCompute != this) throw new IllegalStateException("Compute pass '" + label + "' has ended");
        }

        @Override
        public void setPipeline(CgComputePipeline p) {
            live();
            use(p);
            pipeline = (ComputePipeline) p;
            if (logging) record("setComputePipeline " + ref(p));
        }

        @Override
        public void pushBindings(CgBindings b) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushBindings before a pipeline in '" + label + "'");
            if (pipeline.layout.pushSet() < 0) throw new IllegalStateException(pipeline.label() + " pushes no set");
            push(b, pushed, "pushBindings");
        }

        @Override
        public void setBindingSet(int set, CgBindingSet bindings, int[] dynamicOffsets, int count) {
            live();
            bindSet(sets, set, bindings, dynamicOffsets, count);
        }

        @Override
        public void pushConstants(int offset, int value) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushConstants before a pipeline in '" + label + "'");
            constants(pipeline.label(), pipeline.layout, offset, 4);
            if (logging) record("pushConstants " + offset + " " + value);
        }

        @Override
        public void pushConstants(int offset, ByteBuffer data) {
            live();
            if (pipeline == null) throw new IllegalStateException("pushConstants before a pipeline in '" + label + "'");
            constants(pipeline.label(), pipeline.layout, offset, data.remaining());
            if (logging) record("pushConstants " + offset + " +" + data.remaining());
        }

        @Override
        public void dispatch(int groupsX, int groupsY, int groupsZ) {
            dispatchable();
            if (logging) record("dispatch " + groupsX + " " + groupsY + " " + groupsZ);
        }

        @Override
        public void dispatchIndirect(CgGpuBuffer buffer, long offset) {
            dispatchable();
            use(buffer);
            if (logging) record("dispatchIndirect " + ref(buffer) + "+" + offset);
        }

        private void dispatchable() {
            live();
            if (pipeline == null) throw new IllegalStateException("Dispatch with no pipeline in '" + label + "'");
            use(pipeline);
            boundAll(pipeline.label(), pipeline.layout, pushed, sets);
        }

        @Override
        public void end() {
            live();
            ended = true;
            openCompute = null;
            if (logging) record("endCompute");
        }
    }

    private final class ComputePipeline extends Obj implements CgComputePipeline {
        final CgShaderModule module;
        final CgPipelineLayout layout;

        ComputePipeline(String label, CgShaderModule module, CgPipelineLayout layout) {
            super(label);
            this.module = module;
            this.layout = layout;
        }

        @Override public CgShaderModule module() { return module; }
        @Override public CgPipelineLayout layout() { return layout; }
    }

    private final class Timer extends Obj implements CgTimerQuery {
        long frame = Long.MAX_VALUE;

        Timer(String label) { super(label); }

        @Override public long resultNanos() { return frame <= retired ? 0 : -1; }
    }
}
