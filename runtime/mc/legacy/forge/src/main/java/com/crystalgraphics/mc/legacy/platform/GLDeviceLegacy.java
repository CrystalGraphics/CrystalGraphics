package com.crystalgraphics.mc.legacy.platform;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lwjgl2.Lwjgl2GLDriver;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.render.shader.CgToolchainDevice;
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import net.minecraft.client.Minecraft;

/**
 * The GL device on Minecraft 1.8.9 to 1.12.2: the shader toolchain fetched behind the loading screen, then a {@link CgGLDevice} beside
 * {@code CgGL} in Minecraft's context, on {@link Lwjgl2GLDriver}.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDeviceLegacy());   // once, where the GL backend is made
 * }</pre>
 */
public final class GLDeviceLegacy extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDeviceLegacy.class);

    private CgGLDevice device;

    @Override
    protected String ineligible() {
        return null;
    }

    @Override
    protected void install(List<Path> jars) {
        Minecraft mc = Minecraft.getMinecraft();
        int width = mc.displayWidth, height = mc.displayHeight;
        device = CgShaderToolchain.load(jars, natives()).glDeviceBesideCgGL(new Lwjgl2GLDriver(), width, height);
        LOGGER.info("[device] the GL device draws beside CgGL: {}, {}", device.globals().vendor(), device.globals().driver());
    }

    @Override
    protected void resize() {
        Minecraft mc = Minecraft.getMinecraft();
        int width = mc.displayWidth, height = mc.displayHeight;
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
