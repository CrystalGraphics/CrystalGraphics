package com.crystalgraphics.vulkan.toolchain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryUtil;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.shader.CgShaderToolchainFiles;

/**
 * The toolchain in a loader of its own beside a host whose LWJGL already runs: the same version here, 26.1's case, where
 * both would extract to one versioned folder. Built from the LWJGL jars on this test's classpath.
 */
public class CgShaderToolchainTest {

    private static final String COMPUTE = String.join("\n",
            "#version 450",
            "layout(local_size_x = 8) in;",
            "shared float lanes[8];",
            "void main() { lanes[gl_LocalInvocationIndex] = float(gl_LocalInvocationIndex); }");

    @Rule
    public final TemporaryFolder natives = new TemporaryFolder();

    @Test
    public void compilesAndTranslatesOnItsOwnLwjgl() {
        List<Path> jars = toolchainJars();
        assumeTrue("no LWJGL natives for this platform on the classpath", jars.size() == 6);
        MemoryUtil.memFree(MemoryUtil.memAlloc(16));   // the host's LWJGL loads its natives first

        CgShaderToolchain toolchain = CgShaderToolchain.load(jars, natives.getRoot().toPath());
        CgGlslCompiler compiler = toolchain.compiler(null);
        CgGlslCompiler.ComputeProgram program = compiler.compileCompute(COMPUTE, "toolchain");
        String glsl = toolchain.translator().glsl(program.spirv(), CgShaderModule.Stage.COMPUTE,
                new GLBindingPoints(CgPipelineLayout.of()), "toolchain");

        assertEquals(8, program.localSize()[0]);
        assertTrue(glsl, glsl.startsWith("#version 460"));
        ClassLoader own = compiler.getClass().getClassLoader();
        assertNotSame("the compiler runs in the toolchain's loader", CgShaderToolchainTest.class.getClassLoader(), own);
        try {
            assertNotSame("on the toolchain's own LWJGL", Version.class, Class.forName("org.lwjgl.Version", false, own));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        assertTrue("its natives extracted where it was told", new File(natives.getRoot(), "shaderc.dll").exists()
                || new File(natives.getRoot(), "libshaderc.so").exists() || new File(natives.getRoot(), "libshaderc.dylib").exists());
    }

    /** LWJGL core, shaderc and spvc, and this platform's natives of each, from the classpath. */
    private static List<Path> toolchainJars() {
        String platform = CgShaderToolchainFiles.platform();
        if (platform == null) return List.of();
        Pattern wanted = Pattern.compile("lwjgl(-shaderc|-spvc)?-" + Pattern.quote(Version.getVersion().split("[+ ]")[0])
                + "(-natives-" + Pattern.quote(platform) + ")?\\.jar");
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Paths.get(entry);
            if (wanted.matcher(path.getFileName().toString()).matches()) jars.add(path);
        }
        return jars;
    }
}
