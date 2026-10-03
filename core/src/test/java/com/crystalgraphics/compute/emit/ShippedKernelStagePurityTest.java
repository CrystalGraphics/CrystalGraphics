package com.crystalgraphics.compute.emit;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.compute.lower.CgLoweredEmitter;
import com.crystalgraphics.compute.lower.CgLoweredTarget;
import com.crystalgraphics.compute.lower.CgLowering;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.material.parse.ShippedShaderStagePurityTest;
import com.crystalgraphics.api.shader.CgShaderStages;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.util.io.CgIO;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Every shipped kernel, in every keyword set and on every target, reaches no builtin a compute shader lacks once its
 * includes are expanded and its stage conditionals resolved: what a driver refuses, caught without one. The env files
 * every kernel includes are covered with them.
 */
public class ShippedKernelStagePurityTest {

    private static final String[] NOT_COMPUTE = {
            "fwidth", "fwidthFine", "fwidthCoarse", "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
            "discard", "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth", "interpolateAtCentroid",
            "interpolateAtSample", "interpolateAtOffset", "gl_SampleID", "gl_SamplePosition", "gl_SampleMask",
            "gl_SampleMaskIn", "gl_VertexID", "gl_InstanceID", "gl_Position", "gl_PointSize", "gl_ClipDistance",
            "gl_VertexIndex", "gl_InstanceIndex",
    };
    private static final Pattern BANNED = Pattern.compile("\\b(" + String.join("|", NOT_COMPUTE) + ")\\b");
    private static final CgKernelTarget[] TARGETS = {
            CgKernelTarget.GL43, CgKernelTarget.GL43.withSubgroups(0), CgKernelTarget.GL43.withArb(true),
            CgKernelTarget.GL43.withFloatAtomics(true),
    };

    @Test
    public void shippedKernels_reachNoBuiltinAComputeShaderLacks() {
        List<String> files = CgIO.list("crystalgraphics", "shaders", ".compute");
        assertFalse("no shipped .compute: this would pass vacuously", files.isEmpty());
        for (String path : files) {
            CgComputeSource source = CgComputeParser.parse(CgIO.loadSource(path), path);
            for (CgKernelDecl kernel : source.kernels()) {
                for (Set<String> keywords : keywordSets(source.features())) {
                    for (CgKernelTarget target : TARGETS) {
                        String glsl = CgKernelEmitter.emit(source, kernel, keywords, target);
                        String offender = offender(glsl, path);
                        assertNull(path + " kernel " + kernel.name() + keywords + " on " + target + " reaches '"
                                + offender + "', which a compute shader does not have", offender);
                    }
                }
            }
        }
    }

    /** What a vertex, geometry or fragment stage below compute has none of. */
    private static final Pattern NOT_BELOW_COMPUTE = Pattern.compile("\\b(gl_GlobalInvocationID|gl_LocalInvocationID"
            + "|gl_WorkGroupID|gl_NumWorkGroups|gl_LocalInvocationIndex|gl_WorkGroupSize|barrier|memoryBarrier\\w*"
            + "|groupMemoryBarrier|shared|imageLoad|imageStore|imageAtomic\\w*|atomic(Add|Min|Max|And|Or|Xor|Exchange"
            + "|CompSwap)|subgroup\\w*)\\b");
    private static final CgLoweredTarget[] LOWERED = {
            CgLoweredTarget.GL33,
            new CgLoweredTarget(CgCapabilities.ShaderBufferPath.SSBO_GL43, 256, 1024, 16384, 1 << 27, true, 400),
    };

    @Test
    public void shippedKernels_lowered_reachNothingBelowComputeLacks() {
        for (String path : CgIO.list("crystalgraphics", "shaders", ".compute")) {
            CgComputeSource source = CgComputeParser.parse(CgIO.loadSource(path), path);
            for (CgKernelDecl kernel : source.kernels()) {
                if (CgLowering.refusal(source, kernel) != null) continue;
                for (Set<String> keywords : keywordSets(source.features())) {
                    for (CgLoweredTarget target : LOWERED) {
                        for (CgLowering.Pass pass : CgLowering.passes(source, kernel)) {
                            CgLoweredEmitter.Stages stages = CgLoweredEmitter.emit(source, kernel, keywords, pass, target);
                            for (String stage : new String[]{stages.vertex(), stages.geometry(), stages.fragment()}) {
                                if (stage == null) continue;
                                String code = CgShaderStages.reachable(ShippedShaderStagePurityTest.stripComments(
                                        new CgShaderPreprocessor().process(stage, path)), CgShaderStages.Stage.COMPUTE);
                                Matcher m = NOT_BELOW_COMPUTE.matcher(code);
                                assertFalse(path + " kernel " + kernel.name() + keywords + ", " + pass.kind() + " pass on "
                                        + target.bufferPath() + ", reaches '" + (m.find(0) ? m.group(1) : "") + "'",
                                        m.find(0));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void detector_flagsAFragmentBuiltin_andIgnoresOneGuardedOutOfCompute() {
        assertEquals("gl_FragCoord", offender("#version 430 core\nvec2 f() { return gl_FragCoord.xy; }\n", "test"));
        assertNull(offender(String.join("\n", "#version 430 core", "#define CG_COMPUTE_STAGE 1",
                "#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)", "vec2 f() { return gl_FragCoord.xy; }",
                "#endif", ""), "test"));
    }

    private static String offender(String glsl, String path) {
        String expanded = new CgShaderPreprocessor().process(glsl, path);
        String code = CgShaderStages.reachable(ShippedShaderStagePurityTest.stripComments(expanded), CgShaderStages.Stage.COMPUTE);
        Matcher m = BANNED.matcher(code);
        return m.find() ? m.group(1) : null;
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
