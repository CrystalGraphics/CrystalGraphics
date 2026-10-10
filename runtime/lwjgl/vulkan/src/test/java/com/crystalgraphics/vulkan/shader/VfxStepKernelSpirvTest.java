package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxEmitterCompiler;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** The Step kernel the emitter compiler writes for each shipped emitter compiles to SPIR-V, as the Vulkan device compiles it. */
public class VfxStepKernelSpirvTest {

    private static final ShadercGlslCompiler COMPILER = new ShadercGlslCompiler();

    /** Every module kind, the current and the ground the ones after the solver. */
    private static final CgVfxEmitter EVERY_KIND = CgVfxEmitter.builder("every").capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.3f, 0.15f))
            .module(new CgVfxModule.Wind(0.2f))
            .module(new CgVfxModule.Turbulence(1f, 0.06f, 0.15f))
            .module(new CgVfxModule.Buoyancy(1.2f, 2.5f))
            .module(new CgVfxModule.Updraft(4f, 2f, 6f, 1f))
            .module(new CgVfxModule.Current(2f, 0.3f, 2f, 2.5f))
            .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f))
            .module(new CgVfxModule.Spin(0.5f))
            .build();

    @AfterClass
    public static void close() { COMPILER.close(); }

    @Test
    public void everyShippedEmittersStepKernelCompiles() {
        CgVfxEmitter[] emitters = {EVERY_KIND, CgVfxExplosion.BILLOWS, CgVfxExplosion.SURGE, CgVfxExplosion.SPECKS, CgVfxExplosion.DUST, CgVfxExplosion.SPARKLES,
                CgVfxExplosion.INK, CgVfxExplosion.RAYS, CgVfxExplosion.RINGS};
        List<String> failures = new ArrayList<>();
        for (CgVfxEmitter emitter : emitters) {
            CgVfxShape shape = CgVfxShape.of(emitter);
            String key = CgVfxEmitterCompiler.key(shape);
            CgComputeSource source = CgComputeParser.parse(CgVfxEmitterCompiler.source(shape), key);
            String glsl = new CgShaderPreprocessor().process(
                    CgKernelEmitter.emit(source, source.kernel("Step"), Collections.emptySet(), CgKernelTarget.GL43), key);
            try {
                COMPILER.compileCompute(glsl, key);
            } catch (RuntimeException e) {
                failures.add(emitter.name() + " (" + shape + "): " + e.getMessage());
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
}
