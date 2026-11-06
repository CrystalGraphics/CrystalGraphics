package com.crystalgraphics.mc.v1710.platform.gl;

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
 * The GL device on Minecraft 1.7.10: the shader toolchain fetched behind the loading screen, then a {@link CgGLDevice} beside
 * {@code CgGL} in Minecraft's context, on {@link Lwjgl2GLDriver}.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDevice1710());   // once, where the GL backend is made
 * }</pre>
 */
public final class GLDevice1710 extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDevice1710.class);

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
