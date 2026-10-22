package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgProceduralMesh;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxEmitterCompiler;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
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
    private static final CgVfxEmitter EVERY_KIND = CgVfxEmitter.builder("every").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
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
    /** Spawning on a mesh, whose kernel reads the spawn meshes. */
    private static final CgVfxEmitter ON_MESH = CgVfxEmitter.builder("on_mesh").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .mesh(CgProceduralMesh.revolve(CgProceduralMesh.cone(1f, 1f)).build()).build();
    /** Flocking, whose kernel reads the neighbour grid before the solver. */
    private static final CgVfxEmitter FLOCKING = CgVfxEmitter.builder("flocking").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Flock(1.5f, 0.4f, 3f, 1.5f, 0.8f)).module(new CgVfxModule.LimitSpeed(4f)).build();
    /** Carried by a gas, whose kernel reads the gas atlas before the solver. */
    private static final CgVfxEmitter IN_GAS = CgVfxEmitter.builder("inGas").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Flow(3f)).module(new CgVfxModule.Gravity(2f)).build();
    /** Pouring into a gas, whose kernel appends deposits. */
    private static final CgVfxEmitter FEEDING = CgVfxEmitter.builder("feeding").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .module(new CgVfxModule.Feed(4f, 0.5f)).build();
    /** In a grid: steering by it, pushed down its slope, and splatting into it. */
    private static final CgVfxEmitter IN_GRID = CgVfxEmitter.builder("inGrid").state(CgVfxEmitterState.ONCE)
            .capacity(64).burst(0f, 64).life(1f, 2f)
            .speed(1f, 2f).module(new CgVfxModule.Follow(0, 0.6f, 0.5f, 6f)).module(new CgVfxModule.Gradient(1, 2f))
            .module(new CgVfxModule.Splat(1f, 0.5f, 0f, 0f)).build();

    /** A clip's particles firing at its nodes, their children spawning grandchildren: one shape a feed tier. */
    private static final CgVfxEmitter LEAF = CgVfxEmitter.builder("leaf").capacity(64).life(0.5f, 0.5f)
            .event(CgVfxEvent.onDeath().readback(4)).build();
    private static final CgVfxEmitter MIDDLE = CgVfxEmitter.builder("middle").capacity(64).life(1f, 1f)
            .module(new CgVfxModule.Gravity(9.8f)).event(CgVfxEvent.every(0.1f).between(0.2f, 0.8f).spawn(LEAF, 2)).build();
    private static final CgVfxPrefab SPINNER = CgVfxPrefab.builder("test:spinner").node("hub").node("tip").parent("hub")
            .at(0f, 1f, 0f)
            .part(CgMaterialDefinition.of("test:blade", "crystalgraphics:shaders/vfx/sequence/part.shader").build())
            .build();
    private static final CgVfxEmitter SEQUENCE = CgVfxEmitter.builder("sequence").state(CgVfxEmitterState.ONCE).capacity(4)
            .burst(0f, 1).life(1f, 1f).sequence(CgVfxNodeClip.of(SPINNER, 30).channel("hub", CgVfxNodeClip.Channel.ROTATION_Y,
                    CgVfxNodeClip.Interp.LINEAR, 0, 0f, 30, 360f).burst("tip", MIDDLE, 10, 3).stream("tip", MIDDLE, 5, 25, 20f)
                    .build()).build();

    @AfterClass
    public static void close() { COMPILER.close(); }

    @Test
    public void everyShippedEmittersStepKernelCompiles() {
        CgVfxEmitter[] emitters = {EVERY_KIND, ON_MESH, FLOCKING, IN_GAS, FEEDING, IN_GRID, CgVfxExplosion.BILLOWS, CgVfxExplosion.SURGE, CgVfxExplosion.SPECKS, CgVfxExplosion.DUST, CgVfxExplosion.SPARKLES,
                CgVfxExplosion.INK, CgVfxExplosion.RAYS, CgVfxExplosion.RINGS, CgVfxExplosion.SKIRT};
        List<String> failures = new ArrayList<>();
        List<CgVfxShape> shapes = new ArrayList<>();
        for (CgVfxEmitter emitter : emitters) shapes.add(CgVfxShape.of(emitter));
        shapes.add(CgVfxShape.of(SEQUENCE));
        shapes.add(CgVfxShape.of(MIDDLE, 1));
        shapes.add(CgVfxShape.of(LEAF, 2));
        for (CgVfxShape shape : shapes) {
            String key = CgVfxEmitterCompiler.key(shape);
            CgComputeSource source = CgComputeParser.parse(CgVfxEmitterCompiler.source(shape), key);
            String glsl = new CgShaderPreprocessor().process(
                    CgKernelEmitter.emit(source, source.kernel("Step"), Collections.emptySet(), CgKernelTarget.GL43), key);
            try {
                COMPILER.compileCompute(glsl, key);
            } catch (RuntimeException e) {
                failures.add(shape.key() + ": " + e.getMessage());
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
}
