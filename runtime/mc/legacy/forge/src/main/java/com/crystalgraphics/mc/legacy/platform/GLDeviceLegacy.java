package com.crystalgraphics.mc.legacy.platform;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lwjgl2.Lwjgl2GLDriver;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.pipeline.GLNativeGlslCompiler;
import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.render.shader.CgToolchainDevice;
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import net.minecraft.client.Minecraft;

/**
 * The GL device on Minecraft 1.8.9 to 1.12.2: a {@link CgGLDevice} in Minecraft's context, on
 * {@link Lwjgl2GLDriver}, from the graphics start, the shader toolchain taking over its compiles once fetched.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDeviceLegacy());   // once, from the platform's startDevice
 * }</pre>
 */
public final class GLDeviceLegacy extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDeviceLegacy.class);

    private final GLNativeTranslator translator = new GLNativeTranslator();
    private CgGLDevice device;

    @Override
    protected void start() {
        Minecraft mc = Minecraft.getMinecraft();
        Lwjgl2GLDriver driver = new Lwjgl2GLDriver();
        device = new CgGLDevice(driver, translator, mc.displayWidth, mc.displayHeight, false, HostStateLegacy.create(driver));
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
        Minecraft mc = Minecraft.getMinecraft();
        device.resize(mc.displayWidth, mc.displayHeight);
    }

    @Override
    protected void close() {
        device.fromHost();
        device.close();
        device.toHost();
        device = null;
    }
}
