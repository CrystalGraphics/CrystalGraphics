package com.crystalgraphics.mc.modern.platform.gl;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.Version;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.render.shader.CgToolchainDevice;
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * The GL device on a client Minecraft renders with GL: the shader toolchain fetched behind the loading screen,
 * then a {@link CgGLDevice} beside {@code CgGL} in Minecraft's context, on {@link Lwjgl3GLDriver}.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDeviceModern());   // once, where the GL backend is made
 * }</pre>
 */
public final class GLDeviceModern extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDeviceModern.class);

    private CgGLDevice device;

    @Override
    protected String ineligible() {
        String lwjgl = Version.getVersion();
        return atLeast(lwjgl, 3, 3) ? null : "LWJGL " + lwjgl + "; the GL device runs from LWJGL 3.3";
    }

    /** Whether LWJGL's version string ({@code "3.3.3+5"}, {@code "3.2.2 build 10"}) is at least major.minor. */
    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("[^0-9]+");
        if (parts.length < 2) return false;
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        return a > major || (a == major && b >= minor);
    }

    @Override
    protected void install(List<Path> jars) {
        Window window = Windows.of(Minecraft.getInstance());
        int width = window.getWidth(), height = window.getHeight();
        device = CgShaderToolchain.load(jars, natives()).glDeviceBesideCgGL(new Lwjgl3GLDriver(), width, height);
        LOGGER.info("[device] the GL device draws beside CgGL: {}, {}", device.globals().vendor(), device.globals().driver());
    }

    @Override
    protected void resize() {
        Window window = Windows.of(Minecraft.getInstance());
        int width = window.getWidth(), height = window.getHeight();
        device.resize(width, height);
    }

    @Override
    protected void close() {
        device.fromHost();
        device.close();
        device.toHost();
        device = null;
    }
}
