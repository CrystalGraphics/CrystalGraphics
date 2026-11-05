package com.crystalgraphics.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandListContract;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import static org.junit.Assert.assertEquals;

/** The RHI's command contract on the GL 4.6 device, in a hidden window's context: a frame GL reports an error in fails. */
public class GLCommandListTest extends CgCommandListContract {

    private static GLTestWindow window;
    private static CgGLDevice device;
    private int errorsBefore;

    @BeforeClass
    public static void open() {
        window = new GLTestWindow();
        device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
    }

    @AfterClass
    public static void close() {
        if (device != null) device.close();
        if (window != null) window.close();
    }

    @Before
    public void count() {
        errorsBefore = window.errors.size();
    }

    @Override protected CgRHI rhi() { return device; }

    @Override
    protected CgShaderModule module(CgShaderModule.Stage stage, String glsl) {
        return window.spirv(stage, glsl, spirv -> device.createShaderModule(stage, spirv, "contract"));
    }

    @Override
    protected void checkFrame() {
        device.waitRetired(device.frameIndex() - 1);
        assertEquals("GL errors in this frame: " + window.errors.subList(errorsBefore, window.errors.size()), errorsBefore,
                window.errors.size());
    }
}
