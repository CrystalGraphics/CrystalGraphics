package com.crystalgraphics.mc.modern.platform.gl;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.Version;

import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.state.GLHostState;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.render.shader.CgShaderToolchainFiles;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * The GL device on a client Minecraft renders with GL: the shader toolchain fetched behind the loading screen, then a
 * {@link CgGLDevice} installed beside {@code CgGL} in Minecraft's context, whose paths the engine takes from the next
 * frame. Where it cannot be (an LWJGL older than 3.3, GL below 4.5, no natives pinned for this machine, offline) the
 * client stays on {@code CgGL}, logged once.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDeviceModern());   // once, where the GL backend is made
 * }</pre>
 */
public final class GLDeviceModern implements CgLifecycleListener {

    private static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.device.gl", true, CgVar.DEV,
            "Whether a client rendering with GL fetches the shader toolchain and draws through the GL device beside CgGL.");
    private static final Logger LOGGER = LogManager.getLogger(GLDeviceModern.class);

    private volatile List<Path> jars = Collections.emptyList();
    private CgGLDevice device;

    @Override
    public void onLoad(CgLoadPlan plan) {
        if (!ENABLED.get() || device != null) return;
        String lwjgl = Version.getVersion();
        if (!atLeast(lwjgl, 3, 3)) {
            LOGGER.info("[device] LWJGL {} draws on CgGL: the GL device runs from LWJGL 3.3", lwjgl);
            return;
        }
        plan.task("crystalgraphics:shader-toolchain")
                .work(ctx -> {
                    jars = CgShaderToolchainFiles.fetch(ctx.progress());
                    return null;
                })
                .render(task -> {
                    install();
                    return true;
                })
                .start();
    }

    private void install() {
        if (jars.isEmpty()) return;   // why was logged by the fetch
        Window window = Windows.of(Minecraft.getInstance());
        try {
            CgShaderToolchain toolchain = CgShaderToolchain.load(jars,
                    CgCacheDirectory.of("shader-toolchain").resolve("natives"));
            CgGLDevice made = new CgGLDevice(new Lwjgl3GLDriver(), toolchain.translator(), window.getWidth(),
                    window.getHeight(), false, GLHostState.sharedWithCgGL());
            made.installBesideCgGL(toolchain.compiler(CgCacheDirectory.of("spirv")));
            device = made;
            LOGGER.info("[device] the GL device draws beside CgGL: {}, {}", made.globals().vendor(), made.globals().driver());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[device] the GL device did not start, so this client draws on CgGL", e);
        }
    }

    @Override
    public void onFrame(long frame) {
        if (device == null) return;
        Window window = Windows.of(Minecraft.getInstance());
        device.resize(window.getWidth(), window.getHeight());
    }

    @Override
    public void onContextDestroy() {
        if (device == null) return;
        device.fromHost();
        device.close();
        device.toHost();
        device = null;
    }

    /** Whether LWJGL's version string ({@code "3.3.3+5"}, {@code "3.2.2 build 10"}) is at least major.minor. */
    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("[^0-9]+");
        if (parts.length < 2) return false;
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        return a > major || (a == major && b >= minor);
    }
}
