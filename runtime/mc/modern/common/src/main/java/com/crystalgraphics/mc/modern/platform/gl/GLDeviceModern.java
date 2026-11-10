package com.crystalgraphics.mc.modern.platform.gl;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.pipeline.GLNativeGlslCompiler;
import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.render.shader.CgToolchainDevice;
//? if >=26.3 {
/*import com.crystalgraphics.sdl.SdlWorkerContext;
*///?}
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * The GL device on a client Minecraft renders with GL: a {@link CgGLDevice} in Minecraft's context, on
 * {@link Lwjgl3GLDriver}, its host sections reading Minecraft's state cache ({@link HostStateModern}), from the
 * graphics start, the shader toolchain taking over its compiles once fetched.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDeviceModern());   // once, from the platform's startDevice
 * }</pre>
 */
public final class GLDeviceModern extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDeviceModern.class);

    private final GLNativeTranslator translator = new GLNativeTranslator();
    private CgGLDevice device;

    @Override
    protected void start() {
        Window window = Windows.of(Minecraft.getInstance());
        //? if >=26.3 {
        /*Lwjgl3GLDriver driver = new Lwjgl3GLDriver(SdlWorkerContext::isolated);   // SDL3's window: no GLFW
        *///?} else {
        Lwjgl3GLDriver driver = new Lwjgl3GLDriver();
        //?}
        device = new CgGLDevice(driver, translator, window.getWidth(), window.getHeight(), false,
                HostStateModern.create(driver));
        device.install(new GLNativeGlslCompiler(device.driver()));
        LOGGER.info("[device] the GL device draws in Minecraft's context: {}, {}", device.globals().vendor(),
                device.globals().driver());
    }

    @Override
    protected void toolchain(List<Path> jars) {
        CgShaderToolchain.load(jars, natives()).installOver(translator);
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
