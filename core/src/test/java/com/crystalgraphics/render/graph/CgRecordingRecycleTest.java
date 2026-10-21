package com.crystalgraphics.render.graph;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * A reset clears only what its recording used, so nothing a smaller frame leaves unused may still name what a larger
 * one bound: every pooled pass and every dispatch shell drops its buffers, images and samplers.
 */
public class CgRecordingRecycleTest {

    private static final String SOURCE = """
            #pragma kernel Step map
            Buffers {
                IN    ("State in",  vec4, readonly)
                OUT   ("State out", vec4, writeonly)
            }
            void Step() { OUT_WRITE(IN(CG_ELEMENT) + vec4(1.0)); }
            """;

    private static final CgBufferDesc DESC = CgBufferDesc.elements(64, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY);

    private final CgKernel step = CgCompute.fromSource("test:recording-recycle", SOURCE).kernel("Step");

    private void record(CgRecording rec, int passes, int dispatches) {
        for (int p = 0; p < passes; p++) {
            CgComputePass pass = rec.compute("step " + p);
            for (int d = 0; d < dispatches; d++) {
                CgGraphBuffer in = CgGraphBuffer.persistent("in", DESC), out = CgGraphBuffer.persistent("out", DESC);
                pass.dispatch(step, 64).bind("IN", in).bind("OUT", out);
            }
            pass.end();
        }
    }

    @Test
    public void aSmallFrameAfterALargeOneLeavesNoPassOrShellHoldingWhatTheLargeOneBound() {
        CgRecording rec = new CgRecording();
        record(rec, 40, 12);
        rec.reset();
        record(rec, 2, 2);
        rec.reset();

        List<CgComputePass> pool = rec.computePool();
        assertTrue("the large frame's passes are pooled", pool.size() >= 40);
        int shells = 0;
        for (CgComputePass pass : pool) {
            assertTrue("no dispatch kept past a reset", pass.dispatches().isEmpty());
            for (int i = 0; i < pass.shellCount(); i++, shells++) {
                CgDispatch d = pass.shell(i);
                for (CgGraphBuffer b : d.buffers) assertNull(pass.name() + " shell " + i + " keeps a buffer", b);
                for (CgGraphBuffer b : d.counters) assertNull(pass.name() + " shell " + i + " keeps a counter", b);
                for (CgGraphTexture t : d.images) assertNull(pass.name() + " shell " + i + " keeps an image", t);
                for (Object s : d.samplers) assertNull(pass.name() + " shell " + i + " keeps a sampler", s);
                assertNull(pass.name() + " shell " + i + " keeps its args", d.args);
            }
        }
        assertTrue("the large frame's shells are pooled", shells >= 40 * 12);
    }
}
