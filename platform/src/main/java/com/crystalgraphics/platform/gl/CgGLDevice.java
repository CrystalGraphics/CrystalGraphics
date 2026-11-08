package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.gl.binding.GLBindingLayout;
import com.crystalgraphics.platform.gl.binding.GLBindingSet;
import com.crystalgraphics.platform.gl.command.GLEncoder;
import com.crystalgraphics.platform.gl.command.GLFramebuffers;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.pipeline.GLComputePipeline;
import com.crystalgraphics.platform.gl.pipeline.GLGraphicsPipeline;
import com.crystalgraphics.platform.gl.pipeline.GLLinkWorker;
import com.crystalgraphics.platform.gl.pipeline.GLProgramBinaries;
import com.crystalgraphics.platform.gl.pipeline.GLShaderModule;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.gl.resource.GLBuffer;
import com.crystalgraphics.platform.gl.resource.GLImports;
import com.crystalgraphics.platform.gl.resource.GLSampler;
import com.crystalgraphics.platform.gl.resource.GLTexture;
import com.crystalgraphics.platform.gl.state.GLContextState;
import com.crystalgraphics.platform.gl.state.GLHostState;
import com.crystalgraphics.platform.gl.state.GLStateFilter;
import com.crystalgraphics.platform.gl.sync.GLTimerQuery;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * {@link CgRHI} on GL 4.6, as Unreal's OpenGL RHI implements its RHI: commands run as they are called, a pipeline's
 * state is applied before each draw where it differs from the context's (the PSO fallback), framebuffers are cached by
 * attachments, binding sets are bound at ranges of binding points, and a frame retires when its fence signals. GL is
 * reached through a {@link CgGLDriver}, so one device serves every LWJGL era, and every call passes a
 * {@link GLStateFilter}, which drops one setting what the context already has.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), width, height, true);
 * CgRHI.install(device);
 * ... record through device.encoder() ...
 * device.endFrame();                     // presents the surface to the window's framebuffer, then the host swaps
 * device.resize(newWidth, newHeight);    // a new surfaceColor() and surfaceDepth()
 * }</pre>
 *
 * <p>Sharing a context with a host that draws too (Minecraft), it is made with the host's {@link GLHostState}: each
 * host section then opens with {@link #fromHost} and closes with {@link #toHost}.</p>
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, translator, width, height, false, hostState);
 * device.fromHost();      // the host's state adopted; the device's conventions set
 * ... record ...
 * device.toHost();        // every field the section changed set back to what the host believes
 * }</pre>
 *
 * <ul>
 *   <li>Made, used and closed on the thread whose GL 4.6 context is current; nothing is created on another.</li>
 *   <li>The surface is the device's own colour and depth textures (Unreal's back buffer); {@code presents} blits the
 *   colour to the window's framebuffer at each {@link #endFrame}.</li>
 * </ul>
 */
public final class CgGLDevice implements CgRHI {

    /** {@link CgRHI#onClose}'s tasks: run first as it closes. */
    private final List<Runnable> closing = new ArrayList<>();

    @Override
    public void onClose(Runnable task) {
        synchronized (closing) {
            closing.add(task);
        }
    }

    /** Frames recorded ahead of the GPU before {@link #endFrame} waits for the oldest. */
    public static final int FRAMES_IN_FLIGHT = 3;

    private static final int HEAP_CAPACITY = 1 << 16;

    private final GLStateFilter gl;
    private final GLHostState host;
    private final GLContextState entry = new GLContextState();
    private final GLShaderTranslator translator;
    private GLProgramBinaries binaries;
    private GLLinkWorker worker;
    private GLImports imports;
    private CgGLWorkerContext workerContext;
    private final CgGLDriver raw;
    private final boolean presents;
    private final Thread owner = Thread.currentThread();
    private final Globals globals;
    private final GLFramebuffers framebuffers;
    private final GLEncoder encoder;
    private GLTexture surfaceColor, surfaceDepth;

    private long frame;
    private long retired = -1;
    private final long[] fences = new long[FRAMES_IN_FLIGHT];

    // what retires with a frame: an object to delete or a Runnable to run
    private long[] retireFrames = new long[64];
    private Object[] retireItems = new Object[64];
    private int retireCount;
    private Object[] due = new Object[64];
    private CgTextureView[] presented;

    // the bindless heap: a storage buffer of ARB_bindless_texture handles
    private GLBindingLayout heapLayout;
    private GLBindingSet heapSet;
    private GLBuffer heap;
    private final Map<HeapKey, Integer> heapIndex = new HashMap<>();
    private final List<HeapKey> heapKeys = new ArrayList<>();
    private long[] heapHandles = new long[0];
    private int[] freeIndices = new int[16];
    private int freeCount, heapNext;

    private record HeapKey(CgTextureView view, GLSampler sampler) {}

    /** What the device calls past GL 3.3, by the extension that brought each into 4.x core. */
    private static final List<String> PAST_33 = List.of(
            "GL_ARB_direct_state_access", "GL_ARB_clip_control", "GL_ARB_compute_shader",
            "GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_load_store", "GL_ARB_base_instance",
            "GL_ARB_draw_indirect", "GL_ARB_multi_draw_indirect", "GL_ARB_buffer_storage", "GL_ARB_texture_storage",
            "GL_ARB_texture_storage_multisample", "GL_ARB_copy_image", "GL_ARB_clear_buffer_object",
            "GL_ARB_draw_buffers_blend", "GL_ARB_ES2_compatibility", "GL_ARB_get_program_binary", "GL_KHR_debug",
            "GL_ARB_texture_view", "GL_ARB_texture_buffer_range", "GL_ARB_vertex_attrib_binding",
            "GL_ARB_viewport_array", "GL_ARB_get_texture_sub_image", "GL_ARB_invalidate_subdata");

    /** Whether the driver compiles a vertex shader at GLSL {@code version}, core. */
    private static boolean compiles(CgGLDriver gl, int version) {
        int shader = gl.glCreateShader(GL_VERTEX_SHADER);
        try {
            gl.glShaderSource(shader, "#version " + version + " core\nvoid main() { gl_Position = vec4(0.0); }\n");
            gl.glCompileShader(shader);
            return gl.glGetShaderi(shader, GL_COMPILE_STATUS) != 0;
        } finally {
            gl.glDeleteShader(shader);
        }
    }

    /** A device owning its context: no host draws between its frames. */
    public CgGLDevice(CgGLDriver driver, GLShaderTranslator translator, int width, int height, boolean presents) {
        this(driver, translator, width, height, presents, null);
    }

    /** @param host what a host sharing the context believes it has; null when nothing else draws in it */
    public CgGLDevice(CgGLDriver driver, GLShaderTranslator translator, int width, int height, boolean presents,
                      GLHostState host) {
        this.gl = new GLStateFilter(driver);
        this.raw = driver;
        this.host = host;
        CgGLDriver gl = this.gl;
        this.translator = translator;
        this.presents = presents;
        String version = gl.glGetString(GL_VERSION);
        int at = gl.glGetInteger(GL_MAJOR_VERSION) * 10 + gl.glGetInteger(GL_MINOR_VERSION);
        if (at < 45) {
            // A context named older may still do 4.5's work: NVIDIA answers a 3.2 core request with 3.3 and every
            // later ARB extension. What the device calls past 3.3, extension by extension, then GLSL 4.60 compiled.
            List<String> missing = new ArrayList<>();
            for (String extension : PAST_33) if (!gl.hasExtension(extension)) missing.add(extension);
            if (at < 33 || !missing.isEmpty() || !compiles(gl, 460)) {
                throw new IllegalStateException("CgGLDevice needs GL 4.5, or 3.3 with the extensions 4.5 absorbed and "
                        + "GLSL 4.60, not " + version + (missing.isEmpty() ? "" : "; missing " + missing));
            }
        }
        boolean gl46 = at >= 46;
        Globals.Limits limits = new Globals.Limits(gl.glGetInteger(GL_MAX_TEXTURE_SIZE),
                gl.glGetInteger(GL_MAX_3D_TEXTURE_SIZE), gl.glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS),
                gl.glGetInteger(GL_MAX_COLOR_ATTACHMENTS), gl.glGetInteger(GL_MAX_SAMPLES),
                gl.glGetInteger(GL_MAX_VERTEX_ATTRIBS), gl.glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),
                gl.glGetInteger(GL_MAX_UNIFORM_BLOCK_SIZE), gl.glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS),
                gl.glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS), gl.glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE),
                gl.glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT),
                gl.glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT),
                gl.glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT), gl.glGetInteger(GL_MAX_VIEWPORT_DIMS),
                gl.glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY),
                new Globals.Compute(gl.glGetInteger(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE),
                        gl.glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS), Globals.Compute.MINIMUM.sizeX(),
                        Globals.Compute.MINIMUM.sizeY(), Globals.Compute.MINIMUM.sizeZ(), Globals.Compute.MINIMUM.countX(),
                        Globals.Compute.MINIMUM.countY(), Globals.Compute.MINIMUM.countZ(), 1, 0));
        globals = new Globals("OpenGL", gl.glGetString(GL_VENDOR) + " " + gl.glGetString(GL_RENDERER), version, limits,
                true, limits.maxAnisotropy() > 1f, true, true, gl46 || gl.hasExtension("GL_ARB_indirect_parameters"),
                true, false, true, true, gl.hasExtension("GL_ARB_bindless_texture"));

        if (host == null) conventions();
        framebuffers = new GLFramebuffers(gl);
        encoder = new GLEncoder(this, gl, framebuffers, limits.uniformOffsetAlignment());
        makeSurface(width, height);
    }

    /** Vulkan's conventions: depth in [0, 1], images' rows in memory as Vulkan writes them (present flips). */
    private void conventions() {
        gl.glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
        gl.glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        gl.glEnable(GL_PROGRAM_POINT_SIZE);
        gl.glEnable(GL_FRAMEBUFFER_SRGB);
        gl.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        gl.glPixelStorei(GL_PACK_ALIGNMENT, 1);
    }

    /** The host drew since the last section: what it believes becomes what the filter knows. No-op when owned. */
    @Override
    public void fromHost() {
        if (host == null) return;
        long t = CgTrace.stamp(GL_CHANNEL);
        host.read(entry);
        gl.adopt(entry);
        encoder.invalidate();
        conventions();
        CgGLAudit.deviceSection(true);
        CgTrace.zoneDone(GL_CHANNEL, FROM_HOST, t);
    }

    /** Sets back every field the section changed to what the host believes. No-op when owned. */
    @Override
    public void toHost() {
        if (host == null) return;
        if (encoder.inPass()) throw new IllegalStateException("toHost with a pass open");
        CgGLAudit.deviceSection(false);
        long t = CgTrace.stamp(GL_CHANNEL);
        gl.restore(entry);
        host.conventions(gl);
        encoder.invalidate();
        host.left();
        CgTrace.zoneDone(GL_CHANNEL, TO_HOST, t);
    }

    /** A host section's two hand-overs: once each a device pass on a GL host. */
    private static final CgTraceChannel GL_CHANNEL = CgTrace.channel("crystalgraphics.gl");
    private static final int FROM_HOST = CgTrace.name("device.fromHost"), TO_HOST = CgTrace.name("device.toHost");
    /** The frame {@code FRAMES_IN_FLIGHT} back retiring: the GPU's pace, a wait. */
    private static final int FRAME_FENCE = CgTrace.waitName("device.frameFence");
    private static final int FRAME_FLUSH = CgTrace.name("device.frameFlush"), FRAME_ENCODER = CgTrace.name("device.frameEncoder"),
            FRAME_RETIRE = CgTrace.name("device.frameRetire");

    /**
     * Keeps linked programs' binaries under {@code root} across launches, written by {@code writer}: before the first
     * pipeline. Off where the driver gives no binaries.
     */
    public void programCache(Path root, Executor writer) {
        binaries = new GLProgramBinaries(gl, root, writer);
    }

    /**
     * Links programs on a worker thread in a context of its own, handed over as binaries ({@link GLLinkWorker}): before
     * the first pipeline. Answers whether it runs: not where the driver gives no binaries or no context.
     */
    public boolean linkWorker() {
        if (worker != null) return true;
        if (gl.glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS) <= 0) return false;
        workerContext = raw.createIsolatedContext();
        if (workerContext == null) return false;
        worker = new GLLinkWorker(raw, workerContext);
        return true;
    }

    /** The device's {@link CgGLImports}: {@code CgGL}'s names as its resources and its resources by name. */
    public GLImports imports() {
        if (imports == null) imports = new GLImports(gl, framebuffers);
        return imports;
    }

    /**
     * Makes this the process's device beside {@code CgGL} in the same context, whole (Unreal's {@code RHIInit}, one
     * place): as {@link CgRHI}, device sections as {@link #fromHost}/{@link #toHost}, {@code compiler}, and its
     * {@link #imports()}. Made with {@link GLHostState#sharedWithCgGL()}.
     *
     * <pre>{@code
     * CgGLDevice device = new CgGLDevice(driver, translator, w, h, false, GLHostState.sharedWithCgGL());
     * device.installBesideCgGL(new ShadercGlslCompiler(CgCacheDirectory.of("spirv")));
     * }</pre>
     */
    public CgGLDevice installBesideCgGL(CgGlslCompiler compiler) {
        CgRHI.install(this);
        CgDeviceSection.install(new CgDeviceSection.Host() {
            @Override public void begin() { fromHost(); }

            @Override public void end() { toHost(); }
        });
        CgGlslCompiler.install(compiler);
        CgGLImports.install(imports());
        return this;
    }

    /** What the context has, as the device's filter knows it. */
    public GLContextState contextState() { return gl.state(); }

    // ── what the device is ────────────────────────────────────────────────────

    @Override public Globals globals() { return globals; }

    @Override
    public void describe(BiConsumer<String, String> fact) {
        fact.accept("api", "OpenGL " + globals.driver());
        fact.accept("bindless", String.valueOf(globals.bindless()));
        fact.accept("indirectCount", String.valueOf(globals.indirectCount()));
        fact.accept("computeInvocations", String.valueOf(globals.limits().compute().invocations()));
    }

    @Override
    public boolean supports(CgFormat format, CgGpuTexture.Usage usage) {
        return switch (usage) {
            case ATTACHMENT -> !format.compressed();
            case STORAGE -> format.aspect() == CgFormat.Aspect.COLOR && !format.compressed()
                    && format != CgFormat.RGBA8_SRGB && format != CgFormat.BGRA8_UNORM;
            default -> true;
        };
    }

    @Override public boolean ownedByCurrentThread() { return Thread.currentThread() == owner; }

    /** The GL calls this device makes. */
    public CgGLDriver driver() { return gl; }

    // ── objects ───────────────────────────────────────────────────────────────

    @Override public CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc) { return new GLBuffer(gl, desc); }

    @Override public CgGpuTexture createTexture(CgGpuTexture.Desc desc) { return new GLTexture(gl, desc); }

    @Override
    public CgGpuSampler createSampler(CgGpuSampler.Desc desc) {
        return new GLSampler(gl, desc, globals.limits().maxAnisotropy());
    }

    @Override
    public CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label) {
        return new GLShaderModule(stage, spirv, label);
    }

    @Override
    public CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots, boolean pushed) {
        return new GLBindingLayout(label, slots, pushed, false);
    }

    @Override
    public CgBindingSet createBindingSet(String label, CgBindingLayout layout, CgBindings contents) {
        if (layout.pushed()) throw new IllegalArgumentException(label + ": a set of a pushed layout");
        return new GLBindingSet(label, layout, contents);
    }

    @Override
    public CgGraphicsPipeline createPipeline(CgGraphicsPipelineDesc desc) {
        return new GLGraphicsPipeline(gl, translator, binaries, worker, desc);
    }

    @Override
    public CgComputePipeline createComputePipeline(String label, CgShaderModule module, CgPipelineLayout layout) {
        return new GLComputePipeline(gl, translator, binaries, worker, label, module, layout);
    }

    @Override
    public CgTimerQuery createTimerQuery(String label) {
        return new GLTimerQuery(gl, this::retiredFrame, label);
    }

    @Override
    public void release(CgRHIResource object) {
        retireAt(frame, object);
    }

    private void delete(Object o) {
        // A freed name is GL's to hand out again, to CgGL at another size: what the imports adopted under it goes with it.
        if (o instanceof GLBuffer b) {
            if (b.owned() && imports != null) imports.bufferDeleted(b.name);
            b.delete(gl);
        } else if (o instanceof GLTexture t) {
            framebuffers.forget(t);
            dropHeapEntries(t);
            if (t.owned() && imports != null) imports.textureDeleted(t.name);
            t.delete(gl);
        } else if (o instanceof GLSampler s) gl.glDeleteSamplers(s.name);
        else if (o instanceof GLGraphicsPipeline p) p.delete(gl);
        else if (o instanceof GLComputePipeline p) p.delete(gl);
        else if (o instanceof GLTimerQuery q) q.delete();
    }

    // ── the bindless heap ─────────────────────────────────────────────────────

    @Override
    public CgBindingLayout bindlessLayout() {
        if (!globals.bindless()) return null;
        if (heapLayout == null) {
            heapLayout = new GLBindingLayout("bindless", List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)),
                    false, true);
            heapSet = new GLBindingSet("bindless", heapLayout, new CgBindings());
            heap = new GLBuffer(gl, new CgGpuBuffer.Desc("bindless heap", (long) HEAP_CAPACITY * 8,
                    EnumSet.of(CgGpuBuffer.Usage.STORAGE), true, false, true));
        }
        return heapLayout;
    }

    @Override
    public CgBindingSet bindlessSet() {
        bindlessLayout();
        return heapSet;
    }

    /** The heap's storage buffer of handles, which set {@link #BINDLESS_SET} binds. */
    public GLBuffer heapBuffer() { return heap; }

    @Override
    public int bindlessIndex(CgTextureView view, CgGpuSampler sampler) {
        if (bindlessLayout() == null) return CgRHI.super.bindlessIndex(view, sampler);
        if (!view.texture().desc().usage().contains(CgGpuTexture.Usage.SAMPLED))
            throw new IllegalStateException(view.texture().label() + " in the bindless heap without SAMPLED usage");
        HeapKey key = new HeapKey(view, (GLSampler) sampler);
        Integer known = heapIndex.get(key);
        if (known != null) return known;
        int index = freeCount > 0 ? freeIndices[--freeCount] : heapNext++;
        if (index >= HEAP_CAPACITY) throw new IllegalStateException("the bindless heap is full: " + HEAP_CAPACITY);
        GLTexture t = (GLTexture) view.texture();
        long handle = gl.glGetTextureSamplerHandleARB(t.sampled(gl, view), key.sampler().name);
        gl.glMakeTextureHandleResidentARB(handle);
        heap.mapped().putLong(index * 8, handle);
        if (index >= heapHandles.length) heapHandles = Arrays.copyOf(heapHandles, Math.max(64, index * 2));
        heapHandles[index] = handle;
        while (heapKeys.size() <= index) heapKeys.add(null);
        heapKeys.set(index, key);
        heapIndex.put(key, index);
        return index;
    }

    /** A deleted texture's handles go non-resident and their indices free: its frames have retired. */
    private void dropHeapEntries(GLTexture t) {
        for (int i = 0; i < heapKeys.size(); i++) {
            HeapKey k = heapKeys.get(i);
            if (k == null || k.view().texture() != t) continue;
            gl.glMakeTextureHandleNonResidentARB(heapHandles[i]);
            heapKeys.set(i, null);
            heapIndex.remove(k);
            if (freeCount == freeIndices.length) freeIndices = Arrays.copyOf(freeIndices, freeCount * 2);
            freeIndices[freeCount++] = i;
        }
    }

    // ── commands ──────────────────────────────────────────────────────────────

    @Override public CgCommandList encoder() { return encoder; }

    @Override public CgGpuTexture surfaceColor() { return surfaceColor; }

    @Override public CgGpuTexture surfaceDepth() { return surfaceDepth; }

    /** The window was resized: a new surface, the old one released with its frames. */
    public void resize(int width, int height) {
        if (surfaceColor.desc().width() == width && surfaceColor.desc().height() == height) return;
        release(surfaceColor);
        release(surfaceDepth);
        makeSurface(width, height);
    }

    private void makeSurface(int width, int height) {
        surfaceColor = new GLTexture(gl, new CgGpuTexture.Desc("surface", CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM,
                width, height, 1, 1, 1, CgGpuTexture.Usage.ALL));
        surfaceDepth = new GLTexture(gl, new CgGpuTexture.Desc("surface depth", CgGpuTexture.Kind.D2,
                CgFormat.DEPTH24_PLUS_STENCIL8, width, height, 1, 1, 1,
                EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.SAMPLED, CgGpuTexture.Usage.COPY_SRC)));
        presented = new CgTextureView[] {CgTextureView.whole(surfaceColor)};
    }

    // ── frames ────────────────────────────────────────────────────────────────

    @Override public long frameIndex() { return frame; }

    @Override public long retiredFrame() { return retired; }

    @Override
    public void whenRetired(long f, Runnable action) {
        if (f <= retired) action.run();
        else retireAt(f, action);
    }

    private void retireAt(long f, Object item) {
        if (retireCount == retireFrames.length) {
            retireFrames = Arrays.copyOf(retireFrames, retireCount * 2);
            retireItems = Arrays.copyOf(retireItems, retireCount * 2);
        }
        retireFrames[retireCount] = f;
        retireItems[retireCount++] = item;
    }

    @Override
    public void endFrame() {
        if (encoder.inPass()) throw new IllegalStateException("endFrame with a pass open");
        if (presents) present();
        fences[(int) (frame % FRAMES_IN_FLIGHT)] = gl.glFenceSync();
        long t = CgTrace.stamp(GL_CHANNEL);
        gl.glFlush();
        CgTrace.zoneDone(GL_CHANNEL, FRAME_FLUSH, t);
        frame++;
        t = CgTrace.stamp(GL_CHANNEL);
        encoder.endFrame(frame);
        CgTrace.zoneDone(GL_CHANNEL, FRAME_ENCODER, t);
        if (frame - retired > FRAMES_IN_FLIGHT) {
            t = CgTrace.stamp(GL_CHANNEL);
            waitFence(frame - FRAMES_IN_FLIGHT);
            CgTrace.zoneDone(GL_CHANNEL, FRAME_FENCE, t);
        }
        t = CgTrace.stamp(GL_CHANNEL);
        poll();
        CgTrace.zoneDone(GL_CHANNEL, FRAME_RETIRE, t);
    }

    /** The surface's colour onto the window's framebuffer, rows flipped: GL's window has its first row at the bottom. */
    private void present() {
        int w = surfaceColor.desc().width(), h = surfaceColor.desc().height();
        gl.glDisable(GL_FRAMEBUFFER_SRGB);
        gl.glBlitNamedFramebuffer(framebuffers.get(presented, 1, null), 0,
                0, 0, w, h, 0, h, w, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        gl.glEnable(GL_FRAMEBUFFER_SRGB);
    }

    /** Retires every frame whose fence has signalled, in order. */
    private void poll() {
        while (retired + 1 < frame) {
            long next = retired + 1;
            long fence = fences[(int) (next % FRAMES_IN_FLIGHT)];
            int r = gl.glClientWaitSync(fence, 0, 0);
            if (r != GL_ALREADY_SIGNALED && r != GL_CONDITION_SATISFIED) break;
            retire(next);
        }
    }

    private void waitFence(long f) {
        while (retired < f) {
            long next = retired + 1;
            long fence = fences[(int) (next % FRAMES_IN_FLIGHT)];
            int r;
            do r = gl.glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            while (r == GL_TIMEOUT_EXPIRED);
            if (r == GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed on frame " + next);
            retire(next);
        }
    }

    private void retire(long f) {
        gl.glDeleteSync(fences[(int) (f % FRAMES_IN_FLIGHT)]);
        retired = f;
        // taken out first: an action run may release or ask for more
        int dueCount = 0, kept = 0;
        for (int i = 0; i < retireCount; i++) {
            if (retireFrames[i] <= f) {
                if (dueCount == due.length) due = Arrays.copyOf(due, dueCount * 2);
                due[dueCount++] = retireItems[i];
            } else {
                retireFrames[kept] = retireFrames[i];
                retireItems[kept++] = retireItems[i];
            }
        }
        for (int i = kept; i < retireCount; i++) retireItems[i] = null;
        retireCount = kept;
        long t = CgTrace.stamp(GL_CHANNEL);
        for (int i = 0; i < dueCount; i++) {
            Object item = due[i];
            due[i] = null;
            if (item instanceof Runnable r) r.run();
            else delete(item);
        }
        CgTrace.zoneDone(GL_CHANNEL, RETIRE_ACTIONS, t);
        CgTrace.add(GL_CHANNEL, RETIRED_ITEMS, dueCount);
    }

    private static final int RETIRE_ACTIONS = CgTrace.name("device.retireActions"),
            RETIRED_ITEMS = CgTrace.name("device.retired-items");

    @Override public boolean ownsSubmission() { return true; }

    @Override
    public void waitRetired(long f) {
        if (f >= frame) endFrame();
        waitFence(Math.min(f, frame - 1));
    }

    /** Waits for the GPU and deletes everything the device made. */
    public void close() {
        List<Runnable> tasks;
        synchronized (closing) {
            tasks = new ArrayList<>(closing);
            closing.clear();
        }
        for (Runnable task : tasks) task.run();
        waitRetired(frame - 1);
        release(surfaceColor);
        release(surfaceDepth);
        endFrame();
        waitRetired(frame - 1);
        encoder.delete();
        if (heap != null) heap.delete(gl);
        if (worker != null) {
            worker.stop();
            workerContext.destroy();
        }
    }
}
