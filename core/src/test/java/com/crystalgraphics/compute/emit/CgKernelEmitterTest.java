package com.crystalgraphics.compute.emit;

import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.util.io.CgIO;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

public class CgKernelEmitterTest {

    private static final String EXAMPLE = "crystalgraphics:shaders/example.compute";
    private static final CgKernelTarget EMULATED = CgKernelTarget.GL43.withSubgroups(0);

    private static CgComputeSource example() {
        return CgComputeParser.parse(CgIO.loadSource(EXAMPLE), EXAMPLE);
    }

    private static String emit(String kernel, CgKernelTarget target, String... keywords) {
        CgComputeSource s = example();
        return CgKernelEmitter.emit(s, s.kernel(kernel), Set.of(keywords), target);
    }

    @Test
    public void header_versionLocalSizeAndEnvironment() {
        String glsl = emit("Shade", CgKernelTarget.GL43);
        assertTrue(glsl.startsWith("#version 430 core\n"));
        assertTrue(glsl.contains("layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;"));
        assertTrue(glsl.contains("#define CG_GROUP_SIZE 64\n"));
        assertTrue(glsl.contains("#define CG_COMPUTE_STAGE 1\n"));
        assertTrue(glsl.contains("#define CG_KERNEL_Shade 1\n"));
        assertTrue(glsl.indexOf("#define CG_COMPUTE_STAGE") < glsl.indexOf("cg_env.glsl"));
    }

    @Test
    public void arbTarget_asksForTheExtensions() {
        String glsl = emit("Integrate", CgKernelTarget.GL43.withArb(true));
        assertTrue(glsl.startsWith("#version 330 core\n#extension GL_ARB_compute_shader : require\n"));
    }

    @Test
    public void keywords_areDefinedOnlyWhenAsked() {
        assertFalse(emit("Integrate", CgKernelTarget.GL43).contains("#define WIND 1"));
        assertTrue(emit("Integrate", CgKernelTarget.GL43, "WIND").contains("#define WIND 1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void undeclaredKeyword_isRefused() {
        emit("Integrate", CgKernelTarget.GL43, "NOT_DECLARED");
    }

    @Test
    public void lowerableShapes_returnPastTheCount_generalDoesNot() {
        assertTrue(emit("Integrate", CgKernelTarget.GL43).contains("if (!CG_IN_RANGE) return;"));
        assertFalse(emit("Reduce", CgKernelTarget.GL43).contains("if (!CG_IN_RANGE) return;"));
    }

    @Test
    public void onlyReachedFunctionsAndSharedVariables_areEmitted() {
        String histogram = emit("Histogram", CgKernelTarget.GL43);
        assertFalse(histogram.contains("vec3 wind("));
        assertFalse(histogram.contains("void Integrate("));
        assertFalse(histogram.contains("shared float partial"));
        assertTrue(emit("Reduce", CgKernelTarget.GL43).contains("shared float partial"));
    }

    @Test
    public void accessorsAndBlocks_areTheOnesTheKernelReaches() {
        String integrate = emit("Integrate", CgKernelTarget.GL43);
        assertTrue(integrate.contains("Particle STATE(int i)"));
        assertTrue(integrate.contains("void STATE_WRITE(Particle v)"));
        assertFalse(integrate.contains("STATE_STORE"));
        assertFalse("a stage holds few storage blocks: one the kernel never reaches is not declared",
                integrate.contains("CgBuffer_SPAWNED") || integrate.contains("CgCounter_SPAWNED"));
        String spawn = emit("Spawn", CgKernelTarget.GL43);
        assertTrue(spawn.contains("layout(std430) buffer CgBuffer_SPAWNED"));
        assertTrue(spawn.contains("layout(std430) buffer CgCounter_SPAWNED"));
    }

    @Test
    public void floatAdd_isNativeWithFloatAtomics_andACompareAndSwapWithout() {
        String cas = emit("Histogram", CgKernelTarget.GL43);
        assertTrue(cas.contains("CG_ATOMIC_ADD_FLOAT(_cg_WEIGHTS_bits[i], v)"));
        assertTrue(cas.contains("buffer CgBufferBits_WEIGHTS"));
        String nativeAdd = emit("Histogram", CgKernelTarget.GL43.withFloatAtomics(true));
        assertTrue(nativeAdd.contains("#extension GL_NV_shader_atomic_float : require"));
        assertTrue(nativeAdd.contains("atomicAdd(_cg_WEIGHTS[i], v);"));
        assertFalse(nativeAdd.contains("CgBufferBits_WEIGHTS"));
    }

    @Test
    public void subgroups_areNativeOnlyWithEveryOperation() {
        String nativeOps = emit("Reduce", CgKernelTarget.GL43);
        assertTrue(nativeOps.contains("#extension GL_KHR_shader_subgroup_arithmetic : require"));
        assertTrue(nativeOps.contains("#define CG_SUBGROUPS 1"));
        String emulated = emit("Reduce", EMULATED);
        assertFalse(emulated.contains("GL_KHR_shader_subgroup"));
        assertFalse(emulated.contains("#define CG_SUBGROUPS"));
        assertTrue(emulated.contains("compute/subgroup.glsl"));
        assertFalse(emit("Integrate", CgKernelTarget.GL43).contains("compute/subgroup.glsl"));
    }

    @Test
    public void deviceLimits_areChecked() {
        CgComputeSource s = example();
        CgKernelDecl reduce = s.kernel("Reduce");
        CgKernelTarget small = new CgKernelTarget(false, 0, false, 32768, 128, 1024, 1024, 64);
        assertRefused(s, reduce, small, "256 invocations");
        CgKernelTarget tight = new CgKernelTarget(false, 0, false, 1024, 1024, 1024, 1024, 64);
        assertRefused(s, reduce, tight, "shared memory");
    }

    @Test
    public void emulatedBallot_overAWideGroup_isRefused() {
        CgComputeSource s = CgComputeParser.parse("#pragma kernel K 256 general\nvoid K() { uvec4 b = CG_SUBGROUP_BALLOT(true); }",
                "test:shaders/ballot.compute");
        CgKernelEmitter.emit(s, s.kernel("K"), Set.of(), CgKernelTarget.GL43);
        assertRefused(s, s.kernel("K"), EMULATED, "a ballot holds 128");
    }

    private static void assertRefused(CgComputeSource s, CgKernelDecl k, CgKernelTarget target, String expected) {
        try {
            CgKernelEmitter.emit(s, k, Set.of(), target);
            fail("emitted " + k.name());
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }
}
