package com.crystalgraphics.render.shader;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.variable.CgVar;

/**
 * A device a host makes once the shader toolchain is on disk: the toolchain fetched behind the loading screen on a
 * worker, then {@link #install} on the render thread, the engine's device paths taking over from the next frame. A
 * host whose own platform ships no shader compiler (every GL-era Minecraft) subclasses it once per era, with that era's
 * driver and window.
 *
 * <pre>{@code
 * final class GLDevice1710 extends CgToolchainDevice {
 *     private CgGLDevice device;
 *     @Override protected String ineligible() { return null; }
 *     @Override protected void install(List<Path> jars) {
 *         Minecraft mc = Minecraft.getMinecraft();
 *         device = CgShaderToolchain.load(jars, natives()).glDeviceBesideCgGL(new Lwjgl2GLDriver(), mc.displayWidth, mc.displayHeight);
 *     }
 *     @Override protected void resize() { ... device.resize(w, h); }
 *     @Override protected void close() { ... }
 * }
 * CgEngineLifecycle.addListener(new GLDevice1710());   // once, where the host makes its backend
 * }</pre>
 *
 * <ul>
 *   <li>Where it cannot (no natives pinned for this machine, offline, {@link #install} throwing) the host stays as it
 *       is, logged once.</li>
 *   <li>{@code crystalgraphics.device.toolchain} turns it off.</li>
 *   <li>{@link #resize} each frame and {@link #close} with the context run only once {@link #install} returned.</li>
 *   <li>{@link #install} installs the device as {@link CgRHI}: its frame is ended here, once a host frame.</li>
 * </ul>
 */
public abstract class CgToolchainDevice implements CgLifecycleListener {

    private static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.device.toolchain", true, CgVar.DEV,
            "Whether a host whose platform ships no shader compiler fetches the toolchain and draws through a device.");
    private static final Logger LOGGER = LogManager.getLogger(CgToolchainDevice.class);

    private volatile List<Path> jars = Collections.emptyList();
    private boolean installed;

    /** Why this host makes no device, logged once; null when it does. */
    protected abstract @Nullable String ineligible();

    /** Makes the device from the toolchain's {@code jars} and installs it. Render thread. */
    protected abstract void install(List<Path> jars) throws Exception;

    /** Follows the window: each frame once installed. Render thread. */
    protected abstract void resize();

    /** Closes the device while the context is whole. Render thread. */
    protected abstract void close();

    /** Where the toolchain's natives are extracted: a folder of its own, beside nothing the host loads. */
    protected static Path natives() {
        return CgCacheDirectory.of("shader-toolchain").resolve("natives");
    }

    @Override
    public final void onLoad(CgLoadPlan plan) {
        if (!ENABLED.get() || installed) return;
        String why = ineligible();
        if (why != null) {
            LOGGER.info("[device] no device beside the host: {}", why);
            return;
        }
        plan.task("crystalgraphics:shader-toolchain")
                .work(ctx -> {
                    jars = CgShaderToolchainFiles.fetch(ctx.progress());
                    return null;
                })
                .render(task -> {
                    if (jars.isEmpty()) return true;   // why was logged by the fetch
                    try {
                        install(jars);
                        installed = true;
                    } catch (Exception | LinkageError e) {
                        LOGGER.warn("[device] the device did not start, so this host draws as before", e);
                    }
                    return true;
                })
                .start();
    }

    @Override
    public final void onFrame(long frame) {
        if (!installed) return;
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
        if (!installed) return;
        installed = false;
        close();
    }
}
