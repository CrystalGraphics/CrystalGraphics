package com.crystalgraphics.mc.v1710.platform.gl;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lwjgl2.Lwjgl2GLDriver;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.pipeline.GLNativeGlslCompiler;
import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.platform.gl.state.GLHostState;
import com.crystalgraphics.render.shader.CgToolchainDevice;
import com.crystalgraphics.vulkan.toolchain.CgShaderToolchain;

import net.minecraft.client.Minecraft;

/**
 * The GL device on Minecraft 1.7.10: a {@link CgGLDevice} beside {@code CgGL} in Minecraft's context, on
 * {@link Lwjgl2GLDriver}, from the graphics start, the shader toolchain taking over its compiles once fetched.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new GLDevice1710());   // once, where the GL backend is made
 * }</pre>
 */
public final class GLDevice1710 extends CgToolchainDevice {

    private static final Logger LOGGER = LogManager.getLogger(GLDevice1710.class);

    private final GLNativeTranslator translator = new GLNativeTranslator();
    private CgGLDevice device;

    @Override
    protected String ineligible() {
        return null;
    }

    @Override
    protected void start() {
        Minecraft mc = Minecraft.getMinecraft();
        device = new CgGLDevice(new Lwjgl2GLDriver(), translator, mc.displayWidth, mc.displayHeight, false,
                GLHostState.sharedWithCgGL());
        device.installBesideCgGL(new GLNativeGlslCompiler(device.driver()));
        LOGGER.info("[device] the GL device draws beside CgGL: {}, {}", device.globals().vendor(), device.globals().driver());
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
