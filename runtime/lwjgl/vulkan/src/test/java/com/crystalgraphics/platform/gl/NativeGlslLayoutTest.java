package com.crystalgraphics.platform.gl;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.pipeline.GLNativeGlslCompiler;
import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.vulkan.ShippedPrograms;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.assertTrue;

/**
 * Every program the shipped materials make, compiled by the GL driver itself ({@link GLNativeGlslCompiler}, a GL-era
 * host's first start) and by shaderc: the native layout declares no slot past shaderc's and the same colour outputs,
 * and builds as a pipeline on the GL device. What lets the loading screen and everything before the toolchain lands draw
 * as it draws after.
 */
public class NativeGlslLayoutTest {

    private record Recorded(String label, String vertex, String fragment) {}

    /** The tracked backend's compiler, recording each material program's source. */
    private record Recording(CgGlslCompiler linker, Consumer<Recorded> out) implements CgGlslCompiler {
        @Override
        public Program compile(String vertex, String fragment, Map<String, Integer> attribs, String label) {
            if (vertex.contains("CG_BIND(")) out.accept(new Recorded(label, vertex, fragment));
            return linker.compile(vertex, fragment, attribs, label);
        }

        @Override
        public ComputeProgram compileCompute(String glsl, String label) {
            return linker.compileCompute(glsl, label);
        }
    }

    @Test
    public void nativeLayoutsMatchShadercsAndBuild() throws Exception {
        List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());
        try (ShadercGlslCompiler linker = new ShadercGlslCompiler()) {
            CgTrackedGLBackend tracked = new CgTrackedGLBackend(new CgNullRHI(64, 64), new Recording(linker, recorded::add), true);
            CgGL.init(tracked);
            CgCapabilities.init(new CgTrackedGLContext());
            CgCapabilities.clearCache();
            CgGlState.reset();
            CgGlState.setProvider(new CgTrackedStateProvider(tracked));
            CgBindingPoints.init(CgCapabilities.detect());
            CgFallbackTextures.init();
            Class.forName("com.crystalgraphics.text.render.CgTextRenderer");
            List<String> walkFailures = new ArrayList<>();
            ShippedPrograms.linkEveryVariant(walkFailures);
            assertTrue(String.join("\n", walkFailures), walkFailures.isEmpty());
        } finally {
            CgGlState.reset();
        }
        assertTrue("no program was recorded: the test proves nothing", recorded.size() > 10);

        List<String> failures = new ArrayList<>();
        int built = 0;
        try (GLTestWindow window = new GLTestWindow(); ShadercGlslCompiler shaderc = new ShadercGlslCompiler()) {
            CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), new GLNativeTranslator(), 64, 64, true);
            GLNativeGlslCompiler nativeGlsl = new GLNativeGlslCompiler(device.driver());
            Set<String> seen = new HashSet<>();
            for (Recorded r : recorded) {
                if (!seen.add(r.vertex() + "#fragment" + r.fragment())) continue;   // variants whose sources come out equal
                CgGlslCompiler.Explicit expected, actual;
                try {
                    expected = shaderc.compileExplicit(r.vertex(), r.fragment(), Map.of(), r.label());
                } catch (CgShaderModule.CompileException refused) {
                    continue;   // shaderc's own refusals are EngineOnGLDeviceTest's
                }
                try {
                    actual = nativeGlsl.compileExplicit(r.vertex(), r.fragment(), Map.of(), r.label());
                } catch (CgShaderModule.CompileException e) {
                    failures.add(r.label() + ": " + firstLine(e.getMessage()));
                    continue;
                }
                if (!within(actual.sets(), expected.sets())) {
                    failures.add(r.label() + ": sets " + actual.sets() + ", past shaderc's " + expected.sets());
                    continue;
                }
                if (expected.colorOutputs() != actual.colorOutputs()) {
                    failures.add(r.label() + ": " + actual.colorOutputs() + " outputs, shaderc's " + expected.colorOutputs());
                    continue;
                }
                try {
                    CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, actual.vertexGlDepth(), r.label());
                    CgShaderModule fs = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, actual.fragment(), r.label());
                    List<CgGraphicsPipelineDesc.ColorTarget> targets = new ArrayList<>();
                    for (int i = 0; i < actual.colorOutputs(); i++) {
                        targets.add(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA16_FLOAT, null, 0xF));
                    }
                    CgGraphicsPipeline pipeline = device.createPipeline(new CgGraphicsPipelineDesc(r.label(),
                            CgDeviceStates.layout(device, actual), vs, fs, List.of(),
                            CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                            CgGraphicsPipelineDesc.DepthStencil.OFF, targets, CgFormat.DEPTH32_FLOAT, 1));
                    device.release(pipeline);
                    device.endFrame();
                    built++;
                } catch (RuntimeException e) {
                    failures.add(r.label() + ": built natively: " + firstLine(e.getMessage()));
                }
            }
            device.close();
            assertTrue(failures.size() + " of " + recorded.size() + " programs:\n"
                    + String.join("\n", failures.subList(0, Math.min(20, failures.size()))), failures.isEmpty());
            assertTrue("no program was built natively", built > 10);
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    /**
     * Whether every slot {@code actual} declares is one of {@code expected}'s, set by set. The driver's reads are what
     * its optimiser kept, SPIR-V's every reachable one, so the native layout may be narrower: a set fills only the
     * slots its layout names, so a slot the driver dropped is never bound and never read.
     */
    private static boolean within(List<List<CgBindingLayout.Slot>> actual, List<List<CgBindingLayout.Slot>> expected) {
        for (int set = 0; set < actual.size(); set++) {
            if (actual.get(set).isEmpty()) continue;
            if (set >= expected.size() || !expected.get(set).containsAll(actual.get(set))) return false;
        }
        return true;
    }

    /** The message's start: a driver's log follows its first line. */
    private static String firstLine(String s) {
        String m = String.valueOf(s).replace('\n', ' ');
        return m.length() <= 400 ? m : m.substring(0, 400) + " ...";
    }
}
