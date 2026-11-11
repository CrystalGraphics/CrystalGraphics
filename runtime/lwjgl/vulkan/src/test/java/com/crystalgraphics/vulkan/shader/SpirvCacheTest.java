package com.crystalgraphics.vulkan.shader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * shaderc's output kept across compilers: read back as kept, a damaged file compiled again, a compile as written kept
 * whole with its reflection, and compiles from many threads at once.
 */
public class SpirvCacheTest {

    private static final String KERNEL = String.join("\n",
            "#version 430 core",
            "layout(local_size_x = 64) in;",
            "layout(std430) buffer Out { uint words[]; };",
            "void main() { words[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x; }",
            "");
    /** As {@link #KERNEL}, bound as written: what {@code compileExplicitCompute} takes. */
    private static final String EXPLICIT_KERNEL = String.join("\n",
            "#version 450 core",
            "layout(local_size_x = 64) in;",
            "layout(std430, set = 0, binding = 0) buffer Out { uint words[]; };",
            "void main() { words[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x; }",
            "");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aSecondCompilerReadsWhatTheFirstKept() throws IOException {
        Path dir = folder.getRoot().toPath();
        int generator;
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            generator = word(compiler.compileCompute(KERNEL, "kernel").spirv(), 2);
        }
        Path kept = only(dir);
        // The generator word, which nothing reads: a compiler that ran shaderc again would answer the original.
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(kept)).order(ByteOrder.nativeOrder());
        bytes.putInt(8, generator ^ 1);
        Files.write(kept, bytes.array());

        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            assertEquals(generator ^ 1, word(compiler.compileCompute(KERNEL, "kernel").spirv(), 2));
        }
    }

    @Test
    public void aKernelCompiledAsWrittenIsKeptWholeReflectionAndAll() throws IOException {
        Path dir = folder.getRoot().toPath();
        CgGlslCompiler.ExplicitCompute first;
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            first = compiler.compileExplicitCompute(EXPLICIT_KERNEL, "kernel");
        }
        Path kept = only(dir, ".cgx");
        // The generator word inside the kept module: magic, kind and length come first. A compiler that compiled or
        // reflected again would answer the original.
        int at = 4 + 1 + 4 + 8;
        byte[] bytes = Files.readAllBytes(kept);
        ByteBuffer module = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int generator = module.getInt(at);
        assertEquals(word(first.spirv(), 2), generator);
        module.putInt(at, generator ^ 1);
        Files.write(kept, bytes);

        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            CgGlslCompiler.ExplicitCompute again = compiler.compileExplicitCompute(EXPLICIT_KERNEL, "kernel");
            assertEquals(generator ^ 1, word(again.spirv(), 2));
            assertEquals(first.sets(), again.sets());
            assertArrayEquals(first.localSize(), again.localSize());
            assertEquals(first.pushConstantBytes(), again.pushConstantBytes());
        }
    }

    @Test
    public void compilesFromManyThreadsAtOnceAgreeWithOneAtATime() throws Exception {
        int kernels = 24;
        List<ByteBuffer> alone = new java.util.ArrayList<>();
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler()) {
            for (int i = 0; i < kernels; i++) alone.add(compiler.compileExplicitCompute(kernel(i), "k" + i).spirv());
        }
        Path dir = folder.newFolder("parallel").toPath();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            for (int round = 0; round < 2; round++) {   // compiled, then read back from the cache
                List<java.util.concurrent.Future<ByteBuffer>> together = new java.util.ArrayList<>();
                for (int i = 0; i < kernels; i++) {
                    int k = i;
                    together.add(pool.submit(() -> compiler.compileExplicitCompute(kernel(k), "k" + k).spirv()));
                }
                for (int i = 0; i < kernels; i++) assertEquals("kernel " + i, alone.get(i), together.get(i).get());
            }
        } finally {
            pool.shutdown();
        }
    }

    /** A kernel of its own for each {@code i}: a different constant, so a different module. */
    private static String kernel(int i) {
        return EXPLICIT_KERNEL.replace("= gl_GlobalInvocationID.x;", "= gl_GlobalInvocationID.x + " + i + "u;");
    }

    @Test
    public void aKeptFileThatIsNotSpirvIsCompiledAgainAndReplaced() throws IOException {
        Path dir = folder.getRoot().toPath();
        ByteBuffer first;
        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            first = compiler.compileCompute(KERNEL, "kernel").spirv();
        }
        Path kept = only(dir);
        Files.write(kept, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        try (ShadercGlslCompiler compiler = new ShadercGlslCompiler(dir)) {
            assertEquals(first, compiler.compileCompute(KERNEL, "kernel").spirv());
        }
        assertEquals(0x07230203, ByteBuffer.wrap(Files.readAllBytes(kept)).order(ByteOrder.nativeOrder()).getInt(0));
    }

    private static int word(ByteBuffer spirv, int index) {
        return spirv.duplicate().order(ByteOrder.nativeOrder()).getInt(index * 4);
    }

    private static Path only(Path dir) throws IOException {
        return only(dir, ".spv");
    }

    private static Path only(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> kept = files.filter(f -> f.toString().endsWith(suffix)).collect(Collectors.toList());
            assertEquals(kept.toString(), 1, kept.size());
            return kept.get(0);
        }
    }
}
