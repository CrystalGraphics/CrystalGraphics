package com.crystalgraphics.render.shader;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.job.progress.CgProgress;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.variable.CgVar;

/**
 * The device a host whose platform ships no shader compiler (every GL-era Minecraft) draws through: started with the
 * graphics, its shaders compiled by the GL driver itself, then given the shader toolchain once a worker has fetched it. A host subclasses it once per era, with that era's driver and window.
 *
 * <pre>{@code
 * final class GLDevice1710 extends CgToolchainDevice {
 *     private CgGLDevice device;
 *     private final GLNativeTranslator translator = new GLNativeTranslator();
 *     @Override protected void start() {
 *         CgGLDriver driver = new Lwjgl2GLDriver();
 *         device = new CgGLDevice(driver, translator, w, h, false, GLHostState.byQuery(driver));
 *         device.install(new GLNativeGlslCompiler(device.driver()));
 *     }
 *     @Override protected void toolchain(List<Path> jars) {
 *         CgShaderToolchain t = CgShaderToolchain.load(jars, natives());
 *         translator.spirv(t.translator());
 *         CgGlslCompiler.install(t.compiler(CgCacheDirectory.of("spirv")));
 *     }
 *     @Override protected void resize() { ... device.resize(w, h); }
 *     @Override protected void close() { ... }
 * }
 * CgEngineLifecycle.addListener(new GLDevice1710());   // once, from the platform's startDevice
 * }</pre>
 *
 * <ul>
 *   <li>{@link #start} runs in the graphics start's first stage, before anything compiles. The fetch is a background
 *       job, never a loading-screen task: offline it fails only after its retries and timeouts. What compiles before
 *       the toolchain lands is the driver's for the session; what compiles after, the toolchain's.</li>
 *   <li>Where {@link #start} throws, the graphics start fails with its cause: the device is how the host draws.
 *       Where the toolchain cannot be had (no natives pinned, offline, {@link #toolchain} throwing) the device keeps
 *       the driver's compiler for the session.</li>
 *   <li>A {@code CgGLDevice} it started links programs on its worker and keeps their binaries in the cache's
 *       {@code glprogram} folder, so a launch after the first loads them rather than compiling: nothing for a host to
 *       call. LWJGL 2 gives no worker context, so 1.7.10 and legacy Forge link on the render thread and keep binaries.</li>
 *   <li>{@code crystalgraphics.shader.native=true} never fetches the toolchain.</li>
 *   <li>{@link #resize} each frame and {@link #close} with the context run only once {@link #start} returned.</li>
 * </ul>
 */
public abstract class CgToolchainDevice implements CgLifecycleListener {

    private static final CgVar.Bool NATIVE = CgVar.bool("crystalgraphics.shader.native", false, CgVar.DEV,
            "Keep a GL-era host's device on the driver's own GLSL compiler: the shader toolchain is never fetched.");
    private static final Logger LOGGER = LogManager.getLogger(CgToolchainDevice.class);
    private static final List<CgToolchainDevice> MADE = new CopyOnWriteArrayList<>();
    /** {@link CgCacheDirectory}'s folder for the GL device's program binaries. */
    private static final String PROGRAMS = "glprogram";

    /** The fetch's jars, empty when it failed; null until it ends. Written by the worker. */
    private volatile @Nullable List<Path> fetched;
    private boolean tried, started, fetching;

    protected CgToolchainDevice() {
        MADE.add(this);
    }

    /** Makes the device, its compiler the driver's own, and installs it. Render thread, the context current. */
    protected abstract void start() throws Exception;

    /** Gives the started device the toolchain's compiler and translator, from its {@code jars}. Render thread. */
    protected abstract void toolchain(List<Path> jars) throws Exception;

    /** Follows the window: each frame once started. Render thread. */
    protected abstract void resize();

    /** Closes the device while the context is whole. Render thread. */
    protected abstract void close();

    /** Where the toolchain's natives are extracted: a folder of its own, beside nothing the host loads. */
    protected static Path natives() {
        return CgCacheDirectory.of("shader-toolchain").resolve("natives");
    }

    /** Starts every device a host made and none has started: the graphics start's first stage. Render thread. */
    public static void startAll() {
        for (CgToolchainDevice d : MADE) d.startOnce();
    }

    private void startOnce() {
        if (tried) return;
        tried = true;
        try {
            start();
        } catch (Exception e) {
            throw new IllegalStateException("the device did not start, and this host draws through nothing else", e);
        }
        started = true;
        if (CgRHI.get() instanceof CgGLDevice gl) keepPrograms(gl);
        if (!NATIVE.get()) fetch();
    }

    /** Programs linked off the render thread and kept across launches: before the device's first pipeline. */
    private static void keepPrograms(CgGLDevice device) {
        Path cache = CgCacheDirectory.of(PROGRAMS);
        if (cache != null) device.programCache(cache, CgJob.executor(CgJobLane.BACKGROUND));
        boolean worker = device.linkWorker();
        LOGGER.info("[device] programs link {}, {}", device.parallelLinks() ? "on the driver's compiler threads"
                        : worker ? "on a worker" : "on the render thread",
                cache != null ? "kept in " + cache : "kept nowhere");
    }

    private void fetch() {
        fetching = true;
        fetched = null;
        CgJob.lane(CgJobLane.BACKGROUND).named("shader toolchain").run(() -> {
            List<Path> jars = Collections.emptyList();
            try {
                jars = CgShaderToolchainFiles.fetch(CgProgress.NONE);
            } catch (RuntimeException e) {
                LOGGER.warn("[device] the shader toolchain was not fetched, so the driver compiles for the session", e);
            }
            fetched = jars;
        });
    }

    /** Installs the toolchain the worker fetched, once it has. Render thread. */
    private void install() {
        List<Path> jars = fetched;
        if (jars == null) return;
        fetching = false;
        if (jars.isEmpty()) return;   // why was logged by the fetch
        try {
            toolchain(jars);
            LOGGER.info("[device] the shader toolchain compiles from now on");
        } catch (Exception | LinkageError e) {
            LOGGER.warn("[device] the shader toolchain did not load, so the driver compiles for the session", e);
        }
    }

    @Override
    public final void onFrame(long frame) {
        if (!started) return;
        if (fetching) install();
        resize();
        // The host ends no frame of the device's: unended, nothing it records ever retires, and its readbacks never land
        CgDeviceSection.begin();
        try {
            CgRHI.get().endFrame();
        } finally {
            CgDeviceSection.end();
        }
    }

    @Override
    public final void onContextDestroy() {
        if (!started) return;
        started = false;
        fetching = false;
        close();
    }
}
