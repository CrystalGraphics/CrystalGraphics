package com.crystalgraphics.render.stage;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.buffer.CgFrameRing;
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
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.trace.CgThreadAllocation;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * T5: a stage firing recorded, built and executed on the tracked backend over the recording device, measured in bytes a
 * frame on the render thread after warm-up. What a GPU-less run can prove of the executor's share.
 */
public class CgStageExecuteAllocationTest {

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

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(64, 64).withoutLog(), compiler, true);
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
        CgFrameRing.reset();   // its fences are this backend's: the next class installs another
        CgGlState.reset();
        compiler.close();
    }

    /**
     * 11.8 KB a firing, by JFR's TLAB samples, most first: {@code CgChunkBuilder.end}'s copies (float[], int[], the
     * chunk); {@code CgRecording.block}'s float[] and a {@code CgRasterPass} with its list and int[] per raster;
     * {@code FrameRingStreamBuffer.map}'s DirectByteBuffer from {@code CgBindingTable.upload}; a
     * {@code CgMaterialShader.ProgramKey} per program lookup; {@code CgTrackedGLBackend.glFenceSync}'s boxed Long; and
     * the recording device's own byte[] at retirement (a test device's, never a player's).
     */
    @Test
    public void aFiringExecutesWithoutAllocating() {
        assumeTrue(CgThreadAllocation.available());
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgPipeline quads = material.pipeline(CgInstanceKind.QUAD);
        CgRenderStage stage = CgRenderStage.define("test:execute_allocation");
        stage.host().set(0f, 64, 64, 0);
        CgStageFrame frame = new CgStageFrame(stage);
        CgPassConstants constants = new CgPassConstants();
        Runnable firing = () -> {
            frame.begin(stage.host());
            CgRasterPass pass = frame.pass(frame.defaults(constants), CgOrder.SORTED);
            for (int c = 0; c < 4; c++) {
                CgChunkBuilder chunk = frame.recording().chunks().begin();
                chunk.draw(quads, material.captureBindings(frame.recording().bindings())).sortKey(c);
                for (int i = 0; i < 16; i++) {
                    int at = chunk.instance();
                    chunk.data()[at] = i;
                    chunk.bounds(i * 4, c * 16, i * 4 + 4, c * 16 + 4);
                }
                pass.add(chunk.end());
            }
            pass.end();
            frame.execute();
            CgFrameRing.endFrame();
        };
        for (int i = 0; i < 2_000; i++) firing.run();
        long perFrame = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {   // the least of three: a one-off (a JIT, a lazy init) is no frame's
            long before = CgThreadAllocation.current();
            for (int i = 0; i < 500; i++) firing.run();
            perFrame = Math.min(perFrame, (CgThreadAllocation.current() - before) / 500);
        }
        if (perFrame != 0) {
            assertEquals("bytes a firing allocates on the render thread; sources:\n" + sources(500, firing), 0L, perFrame);
        }
    }

    /** The sites {@code body} allocates at, most bytes first, from JFR's TLAB samples on this thread. */
    private static String sources(int calls, Runnable body) {
        Path file = null;
        try {
            file = Files.createTempFile("cg-alloc", ".jfr");
            long thread = Thread.currentThread().getId();
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
                recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                recording.start();
                for (int i = 0; i < calls * 40; i++) body.run();
                recording.stop();
                recording.dump(file);
            }
            Map<String, Long> bytes = new HashMap<>();
            for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
                if (event.getThread() == null || event.getThread().getJavaThreadId() != thread) continue;
                String site = site(event.getStackTrace()) + " <- " + event.getClass("objectClass").getName();
                long size = event.hasField("tlabSize") ? event.getLong("tlabSize") : event.getLong("allocationSize");
                bytes.merge(site, size, Long::sum);
            }
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(bytes.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(12, ranked.size()); i++) out.append("  ").append(ranked.get(i).getKey()).append('\n');
            return ranked.isEmpty() ? "  (no TLAB sample)\n" : out.toString();
        } catch (IOException | RuntimeException e) {
            return "  (JFR unavailable: " + e + ")\n";
        } finally {
            if (file != null) file.toFile().delete();
        }
    }

    private static String site(RecordedStackTrace stack) {
        if (stack == null) return "?";
        List<RecordedFrame> frames = stack.getFrames();
        for (RecordedFrame f : frames) {
            String type = f.getMethod().getType().getName();
            if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) {
                StringBuilder at = new StringBuilder();
                for (int k = frames.indexOf(f); k < Math.min(frames.size(), frames.indexOf(f) + 4); k++) {
                    RecordedFrame c = frames.get(k);
                    if (at.length() > 0) at.append(" < ");
                    at.append(c.getMethod().getType().getName().replaceAll(".*\\.", "")).append('.').append(c.getMethod().getName())
                            .append(':').append(c.getLineNumber());
                }
                return at.toString();
            }
        }
        return frames.isEmpty() ? "?" : frames.get(0).getMethod().getType().getName();
    }
}
