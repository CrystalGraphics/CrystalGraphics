package com.crystalgraphics.vulkan.shader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** shaderc's output kept across compilers: read back as kept, and a damaged file compiled again. */
public class SpirvCacheTest {

    private static final String KERNEL = String.join("\n",
            "#version 430 core",
            "layout(local_size_x = 64) in;",
            "layout(std430) buffer Out { uint words[]; };",
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
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> kept = files.collect(Collectors.toList());
            assertEquals(kept.toString(), 1, kept.size());
            return kept.get(0);
        }
    }
}
