package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.util.io.CgIO;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Every shipped kernel, in every keyword set, with subgroups native and emulated, compiles to SPIR-V as the Vulkan
 * device compiles it: the GLSL the emitter writes is GLSL a compiler accepts, checked on any machine.
 */
public class ShippedKernelSpirvTest {

    private static final ShadercGlslCompiler COMPILER = new ShadercGlslCompiler();
    private static final CgKernelTarget[] TARGETS = {CgKernelTarget.GL43, CgKernelTarget.GL43.withSubgroups(0)};

    @AfterClass
    public static void close() { COMPILER.close(); }

    @Test
    public void everyShippedKernelCompiles() {
        List<String> files = CgIO.list("crystalgraphics", "shaders", ".compute");
        assertFalse("no shipped .compute: this would pass vacuously", files.isEmpty());
        List<String> failures = new ArrayList<>();
        int compiled = 0;
        for (String path : files) {
            CgComputeSource source = CgComputeParser.parse(CgIO.loadSource(path), path);
            for (CgKernelDecl kernel : source.kernels()) {
                for (Set<String> keywords : keywordSets(source.features())) {
                    for (CgKernelTarget target : TARGETS) {
                        String label = path + "#" + kernel.name() + keywords + (target.nativeSubgroups() ? "" : " emulated");
                        String glsl = new CgShaderPreprocessor().process(
                                CgKernelEmitter.emit(source, kernel, keywords, target), path);
                        try {
                            COMPILER.compileCompute(glsl, label);
                            compiled++;
                        } catch (RuntimeException e) {
                            failures.add(label + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue(compiled > 0);
    }

    private static List<Set<String>> keywordSets(List<String> features) {
        List<Set<String>> sets = new ArrayList<>();
        for (int mask = 0; mask < 1 << features.size(); mask++) {
            Set<String> set = new HashSet<>();
            for (int i = 0; i < features.size(); i++) if ((mask & 1 << i) != 0) set.add(features.get(i));
            sets.add(set);
        }
        return sets;
    }
}
