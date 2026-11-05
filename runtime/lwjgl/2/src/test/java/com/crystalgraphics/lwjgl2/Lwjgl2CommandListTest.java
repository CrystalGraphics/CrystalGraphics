package com.crystalgraphics.lwjgl2;

import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandListContract;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.KHRDebugCallback;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * The RHI's command contract on the GL device over {@link Lwjgl2GLDriver}, in an LWJGL 2 pbuffer's GL 4.5 core
 * context (LWJGL 2.9.4 asks for 4.5 at most; drivers answer with their newest). LWJGL 2 has no SPIRV-Cross, so a module carries its Vulkan GLSL and each translation is the fixture
 * {@code GLCommandListTest} keeps from SPIRV-Cross ({@code gl-contract/} beside this test).
 */
public class Lwjgl2CommandListTest extends CgCommandListContract {

    private static Pbuffer pbuffer;
    private static CgGLDevice device;
    private static final List<String> errors = new ArrayList<>();
    private int errorsBefore;

    @BeforeClass
    public static void open() {
        try {
            pbuffer = new Pbuffer(64, 64, new PixelFormat(), null, null,
                    new ContextAttribs(4, 5).withProfileCore(true).withForwardCompatible(true).withDebug(true));
            pbuffer.makeCurrent();
        } catch (LWJGLException e) {
            assumeTrue("no LWJGL 2 GL 4.5 context here: " + e.getMessage(), false);
        }
        GL43.glDebugMessageCallback(new KHRDebugCallback((source, type, id, severity, message) -> {
            if (type == GL43.GL_DEBUG_TYPE_ERROR) errors.add(message);
        }));
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        device = new CgGLDevice(new Lwjgl2GLDriver(), FIXTURES, 64, 64, true);
    }

    @AfterClass
    public static void close() {
        if (device != null) device.close();
        if (pbuffer != null) pbuffer.destroy();
    }

    /** The module's bytes are its Vulkan GLSL; its translation under {@code points} is the kept fixture. */
    private static final GLShaderTranslator FIXTURES = (source, stage, points, label) -> {
        String glsl = StandardCharsets.UTF_8.decode(source.duplicate()).toString();
        String name = "/gl-contract/" + key(stage, glsl, points.toString()) + ".glsl";
        try (InputStream in = Lwjgl2CommandListTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("no fixture " + name + ": rerun GLCommandListTest with"
                    + " -PupdateGlFixtures=true");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    };

    /** As {@code GLCommandListTest.fixtureKey}. */
    private static String key(CgShaderModule.Stage stage, String vulkanGlsl, String points) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update((vulkanGlsl + "\0" + points).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(40);
            for (byte b : sha.digest()) s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return stage.name().toLowerCase() + "-" + s.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void count() {
        errorsBefore = errors.size();
    }

    @Override protected CgRHI rhi() { return device; }

    @Override
    protected CgShaderModule module(CgShaderModule.Stage stage, String glsl) {
        byte[] bytes = glsl.getBytes(StandardCharsets.UTF_8);
        return device.createShaderModule(stage, ByteBuffer.allocateDirect(bytes.length).put(bytes).flip(), "contract");
    }

    @Override
    protected void checkFrame() {
        device.waitRetired(device.frameIndex() - 1);
        assertEquals("GL errors in this frame: " + errors.subList(errorsBefore, errors.size()), errorsBefore, errors.size());
    }
}
