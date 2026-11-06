package com.crystalgraphics.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandListContract;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;

/**
 * The RHI's command contract on the GL 4.6 device, in a hidden window's context: a frame GL reports an error in fails.
 * Each shader's translation is kept as a fixture of the LWJGL 2 runner (`runtime/lwjgl/2`, which has no SPIRV-Cross),
 * checked here; {@code -PupdateGlFixtures=true} rewrites them.
 */
public class GLCommandListTest extends CgCommandListContract {

    /** Where {@code Lwjgl2CommandListTest} reads each translation, by {@link #fixtureKey}. */
    static final Path FIXTURES = Paths.get("../2/src/test/resources/gl-contract");

    private static GLTestWindow window;
    private static CgGLDevice device;
    private static final Map<String, String> sourceOf = new HashMap<>();   // the SPIR-V's hash -> its Vulkan GLSL
    private static final Map<String, String> translations = new TreeMap<>();
    private int errorsBefore;

    @BeforeClass
    public static void open() {
        window = new GLTestWindow();
        SpvcGlslTranslator spvc = new SpvcGlslTranslator();
        GLShaderTranslator recording = (spirv, stage, points, label) -> {
            String glsl = spvc.glsl(spirv, stage, points, label);
            translations.put(fixtureKey(stage, sourceOf.get(sha(spirv)), points.toString()), glsl);
            return glsl;
        };
        device = new CgGLDevice(new Lwjgl3GLDriver(), recording, 64, 64, true);
    }

    @AfterClass
    public static void close() throws IOException {
        if (device != null) device.close();
        if (window != null) window.close();
        boolean update = Boolean.getBoolean("crystalgraphics.test.updateGlFixtures");
        if (update) Files.createDirectories(FIXTURES);
        StringBuilder stale = new StringBuilder();
        for (Map.Entry<String, String> e : translations.entrySet()) {
            Path file = FIXTURES.resolve(e.getKey() + ".glsl");
            if (update) Files.writeString(file, e.getValue());
            else if (!Files.exists(file) || !Files.readString(file).equals(e.getValue())) stale.append(' ').append(file);
        }
        // A fixture no translation wrote any more: its name is the old source's hash
        try (DirectoryStream<Path> files = Files.newDirectoryStream(FIXTURES, "*.glsl")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (translations.containsKey(name.substring(0, name.length() - ".glsl".length()))) continue;
                if (update) Files.delete(file);
                else stale.append(' ').append(file);
            }
        }
        assertEquals("the LWJGL 2 runner's fixtures are stale; rerun with -PupdateGlFixtures=true:",
                "", stale.toString());
    }

    /** A translation's file name: the stage, the Vulkan GLSL and the binding points it was translated under. */
    static String fixtureKey(CgShaderModule.Stage stage, String vulkanGlsl, String points) {
        return stage.name().toLowerCase() + "-" + sha(ByteBuffer.wrap((vulkanGlsl + "\0" + points).getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
    }

    static String sha(ByteBuffer bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(bytes.duplicate());
            StringBuilder s = new StringBuilder(40);
            for (byte b : sha.digest()) s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void count() {
        errorsBefore = window.errors.size();
    }

    @Override protected CgRHI rhi() { return device; }

    @Override
    protected CgShaderModule module(CgShaderModule.Stage stage, String glsl) {
        return window.spirv(stage, glsl, spirv -> {
            sourceOf.put(sha(spirv), glsl);
            return device.createShaderModule(stage, spirv, "contract");
        });
    }

    @Override
    protected void checkFrame() {
        device.waitRetired(device.frameIndex() - 1);
        assertEquals("GL errors in this frame: " + window.errors.subList(errorsBefore, window.errors.size()), errorsBefore,
                window.errors.size());
    }
}
