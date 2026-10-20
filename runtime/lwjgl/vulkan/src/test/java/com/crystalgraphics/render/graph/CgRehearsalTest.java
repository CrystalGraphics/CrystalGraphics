package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T6: a rehearsal records and builds on the device's thread and never executes: the recording device sees no command
 * while it runs, it ends by measurement, and once it has, the rehearsed path loads no class.
 */
public class CgRehearsalTest {

    private static final String SOURCE = """
            #type none
            Tags { "RenderType" = "Transparent" }
            Properties {
                _Alpha ("Alpha", float) = 1
            }
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Alpha); }
            }
            """;

    private static ShadercGlslCompiler compiler;
    private static CgRecordingDevice device;

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        device = new CgRecordingDevice(64, 64);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, compiler, true);
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
        CgGlState.reset();
        compiler.close();
    }

    @Test
    public void aRehearsalRecordsAndBuildsAndNeverExecutes() {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgPipeline quads = material.pipeline(CgInstanceKind.QUAD);
        CgGraphTexture target = CgGraphTexture.requested("rehearsal", new CgTextureDesc(128, 128, CgTextureDesc.RGBA8));
        CgPassConstants constants = new CgPassConstants().resolution(128, 128);
        CgRehearsal.Act cast = rec -> {
            CgRasterPass pass = rec.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED);
            for (int c = 0; c < 6; c++) {
                CgChunkBuilder chunk = rec.chunks().begin();
                chunk.draw(quads, material.captureBindings(rec.bindings())).sortKey(c);
                for (int i = 0; i < 24; i++) {
                    int at = chunk.instance();
                    chunk.data()[at] = i;
                    chunk.bounds(i * 5, c * 20, i * 5 + 5, c * 20 + 5);
                }
                pass.add(chunk.end());
            }
            pass.end();
        };

        int mark = device.mark();
        int draws = device.draws();
        CgRehearsal rehearsal = CgRehearsal.of(cast).minPasses(64).window(32).maxPasses(50_000)
                .runAll();
        assertEquals("nothing reached the device", mark, device.mark());
        assertEquals(draws, device.draws());
        assertTrue(rehearsal.done());
        assertTrue("ran at least its least passes", rehearsal.passes() >= 64);

        long loaded = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
        CgRehearsal cue = CgRehearsal.of(cast).minPasses(1).window(1).maxPasses(1).runAll();
        assertEquals(1, cue.passes());
        assertEquals("the rehearsed path loads no class once rehearsed", loaded,
                ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
    }

    @Test
    public void stepRunsUnderTheWarmBudgetAndEnds() {
        CgRehearsal rehearsal = CgRehearsal.of(rec -> { }).minPasses(8).window(4).maxPasses(500);
        int frames = 0;
        while (!rehearsal.step()) frames++;
        assertTrue(rehearsal.done());
        assertTrue(rehearsal.passes() >= 8 && rehearsal.passes() <= 500);
        assertTrue("a step runs at least one pass", frames < rehearsal.passes());
    }
}
