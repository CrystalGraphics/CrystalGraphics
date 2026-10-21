package com.crystalgraphics.render.stage;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.frame.CgFrameBoundary;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgAllocationSources;
import com.crystalgraphics.trace.CgThreadAllocation;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.joml.Matrix4f;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * A frame of playing effects allocates nothing on the render thread once warm: {@link CgVfxSystem#update}, then
 * {@link CgVfxSystem#submit} into the world renderer, then both world stages recorded, built and executed on the
 * tracked backend over the recording device, as a host's frame runs them. Also the Nova Cannon played from its files,
 * the asset frame boundary in every frame: phase C's "no allocation".
 */
public class CgVfxSubmitAllocationTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:submit_allocation")).build();
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("submit_sparks").capacity(512)
            .rate(200f).burst(0.5f, 20).life(0.4f, 0.8f).speed(1f, 4f)
            .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Drag(0.5f, 0f)).build();
    private static final CgVfxEmitter SMOKE = CgVfxEmitter.builder("submit_smoke").capacity(256)
            .ratePerBlock(4f).life(1f, 2f).speed(0.2f, 0.6f).module(new CgVfxModule.Buoyancy(2f, 0.5f)).build();
    private static final CgRenderStage[] WORLD = {CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT};

    /** A desktop GPU's compute limits: the VFX range's kernels are wider than Vulkan's minimum of 128. */
    private static final CgDeviceInfo.Compute DESKTOP =
            new CgDeviceInfo.Compute(49152, 1024, 1024, 1024, 64, 65535, 65535, 65535, 32, 0xFF);

    private static ShadercGlslCompiler compiler;
    /** The plan's Nova Cannon kit, from this module's directory; the case skips where plan/ is not checked out. */
    private static final Path NOVA = Paths.get("../../../../plan/crystalgraphics/vfx-aaa/examples/novamod/assets/novamod");

    /** Two emitters running for ever, one of them moving. */
    static final class Fountain extends CgVfxEffect<Fountain> {
        final CgVfxEmitterInstance sparks = new CgVfxEmitterInstance(SPARKS, 0.21f);
        final CgVfxEmitterInstance smoke = new CgVfxEmitterInstance(SMOKE, 0.73f);

        Fountain(double x) {
            super(LOOK, x, 0, -8);
            sparks.start(0f, 1f, 0f);
            smoke.start(0f, 0f, 0f);
        }

        @Override
        protected void tick(float dt) {
            smoke.moveTo((float) Math.sin(age * 3f) * 2f, 0f, (float) Math.cos(age * 3f) * 2f);
            tick(sparks, dt);
            tick(smoke, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(64, 64).withoutLog().withCompute(DESKTOP), compiler, true);
        CgGL.init(gl);
        CgCapabilities.init(new CgTrackedGLContext());
        CgCapabilities.clearCache();
        CgGlState.reset();
        CgGlState.setProvider(new CgTrackedStateProvider(gl));
        CgBindingPoints.init(CgCapabilities.detect());
        CgFallbackTextures.init();
    }

    @AfterClass
    public static void uninstall() {
        CgFrameRing.reset();
        CgGlState.reset();
        compiler.close();
    }

    @Test
    public void aFrameOfPlayingEffectsAllocatesNothing() {
        assumeTrue(CgThreadAllocation.available());
        CgVfxSystem vfx = new CgVfxSystem();
        for (int i = 0; i < 6; i++) vfx.play(new Fountain(i * 4 - 10));
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        Matrix4f view = new Matrix4f(), projection = new Matrix4f().perspective(1.2f, 1f, 0.1f, 256f);
        double[] clock = {0.0};
        Runnable frame = () -> {
            clock[0] += 1.0 / 60.0;
            vfx.update(clock[0]);
            vfx.submit(world);
            for (CgRenderStage stage : WORLD) {
                stage.host().set(0f, 64, 64, 0).view().set(0, 0, 0, view, projection);
                stage.recordAndExecute();   // fire() less the host section and the lifecycle, which want a platform
            }
            CgFrameRing.endFrame();
        };
        try {
            for (int i = 0; i < 3_000; i++) frame.run();
            long perFrame = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) {   // the least of three: a one-off is no frame's
                long before = CgThreadAllocation.current();
                for (int i = 0; i < 500; i++) frame.run();
                perFrame = Math.min(perFrame, (CgThreadAllocation.current() - before) / 500);
            }
            if (perFrame != 0) {
                assertEquals("bytes a frame of effects allocates on the render thread; sources:\n"
                        + CgAllocationSources.of(500, frame), 0L, perFrame);
            }
        } finally {
            vfx.delete();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theNovaCannonPlayedFromItsFilesAllocatesNothing() {
        assumeTrue(CgThreadAllocation.available());
        assumeTrue("the Nova Cannon kit is absent", Files.isDirectory(NOVA));
        CgVfxSystemAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan("novamod", NOVA, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        CgVfxAssets.install(assets);
        String[] ids = {"novamod:nova_cannon/charge", "novamod:nova_cannon/beam", "novamod:nova_cannon/impact"};
        CgAssetRef<CgVfxSystemDefinition>[] refs = new CgAssetRef[ids.length];
        for (int i = 0; i < ids.length; i++) refs[i] = assets.declare(CgParticleKinds.SYSTEM, ids[i]);
        CgLoadHandle loading = assets.load(List.of(refs), CgLoadPriority.MOVE);
        for (int i = 0; i < 20_000 && !loading.ready() && !loading.failed(); i++) {
            CgFrameBoundary.run();
            LockSupport.parkNanos(1_000_000L);
        }
        assertTrue("the Nova Cannon's bundle did not load", loading.ready());

        CgVfxSystem vfx = new CgVfxSystem();
        for (CgAssetRef<CgVfxSystemDefinition> ref : refs) vfx.play(new CgVfxSystemInstance<>(ref, 0, 1.5, -8)).ground(0f);
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        Matrix4f view = new Matrix4f(), projection = new Matrix4f().perspective(1.2f, 1f, 0.1f, 256f);
        double[] clock = {0.0};
        Runnable frame = () -> {
            CgFrameBoundary.run();   // the assets' drain and publish, as every host frame runs them
            clock[0] += 1.0 / 60.0;
            vfx.update(clock[0]);
            vfx.submit(world);
            for (CgRenderStage stage : WORLD) {
                stage.host().set(0f, 64, 64, 0).view().set(0, 0, 0, view, projection);
                stage.recordAndExecute();
            }
            CgFrameRing.endFrame();
        };
        try {
            for (int i = 0; i < 3_000; i++) frame.run();
            long perFrame = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) {
                long before = CgThreadAllocation.current();
                for (int i = 0; i < 500; i++) frame.run();
                perFrame = Math.min(perFrame, (CgThreadAllocation.current() - before) / 500);
            }
            if (perFrame != 0) {
                assertEquals("bytes a frame of the Nova Cannon allocates on the render thread; sources:\n"
                        + CgAllocationSources.of(5_000, frame), 0L, perFrame);
            }
        } finally {
            vfx.delete();
        }
    }
}
