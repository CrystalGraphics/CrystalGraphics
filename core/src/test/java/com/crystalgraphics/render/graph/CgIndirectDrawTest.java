package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.material.CgMaterialTestSupport;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * gpu-compute C4, GL-free: an indirect draw orders its pass after the kernel that writes its count, reads the count as
 * a kernel does (its command's kernel), never batches, and carries its count to the frame; a draw of {@code objects()}
 * the same with its records' buffer (C9b); and what the builder refuses.
 */
public class CgIndirectDrawTest {

    private static final String SOURCE = """
            #pragma kernel Count scatter
            Buffers {
                COUNTS ("Counts", uint, writeonly)
            }
            void Count() { COUNTS_STORE(0, 37u); }
            """;

    private static final CgBufferDesc DESC = CgBufferDesc.of(16, CgBufferUsage.STORAGE);

    private final CgKernel count = CgCompute.fromSource("test:indirect-draw", SOURCE).kernel("Count");
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private CgPipeline pipeline;
    private CgPipeline quadPipeline;

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        CgMaterial material = CgMaterial.fromSource("""
                #type none
                Pass {
                    void vertex(out v2f o) { }
                    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
                }
                """);
        pipeline = material.pipeline(CgInstanceKind.OBJECT);
        quadPipeline = material.pipeline(CgInstanceKind.QUAD);
    }

    private static CgRasterPass raster(CgRecording rec) {
        return rec.raster(CgGraphTexture.requested("target", new CgTextureDesc(8, 8, CgTextureDesc.RGBA8)),
                CgLoad.load(), new CgPassConstants(), null, CgOrder.LOOKBACK);
    }

    private CgFrame build(CgRecording rec) {
        return builder.build(new CgFrameGraph().add(rec.seal()));
    }

    @Test
    public void anIndirectDrawRunsAfterTheKernelThatWritesItsCount_whichItReadsAsAKernel() {
        CgGraphBuffer live = CgGraphBuffer.transientBuffer("live", DESC);
        CgRecording rec = new CgRecording();
        CgRasterPass draw = raster(rec);   // made first: what orders passes is when each access is
        CgComputePass write = rec.compute("count");
        write.dispatch(count, 1).bind("COUNTS", live);
        write.end();
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(pipeline, rec.bindings().begin().end(), CgMesh.quads(64)).indirect(live, 0, CgIndirect.INDICES, 6);
        c.instance();
        draw.add(c.end());
        draw.end();

        CgFrame frame = build(rec);
        assertEquals(List.of("count", "raster target"), names(frame));
        assertEquals(CgAccess.COMPUTE_READ, bitsOf(frame, 1, live));
        assertTrue("an indirect draw's command is a kernel's", frame.kernels);
    }

    @Test
    public void indirectDrawsNeverBatch_andCarryTheirCountToTheFrame() {
        CgGraphBuffer live = CgGraphBuffer.persistent("live", DESC);
        CgMesh quads = CgMesh.quads(64);
        CgRecording rec = new CgRecording();
        int bindings = rec.bindings().begin().end();
        CgRasterPass draw = raster(rec);
        CgChunkBuilder c = rec.chunks().begin();
        for (int i = 0; i < 2; i++) {
            c.draw(pipeline, bindings, quads).indirect(live, 4, CgIndirect.INDICES, 6);
            c.instance();
        }
        c.draw(pipeline, bindings, quads).indirect(live, 8, CgIndirect.INSTANCES, 2);
        c.instance();
        for (int i = 0; i < 2; i++) {
            c.draw(pipeline, bindings, quads);
            c.instance();
        }
        draw.add(c.end());
        draw.end();

        CgFrame.Raster packed = build(rec).rasters[0];
        assertEquals("three indirect draws alone, two direct ones together", 4, packed.count);
        assertEquals(3, packed.indirects);
        assertSame(live, packed.counts[0]);
        assertEquals(4, packed.countOffsets[1]);
        assertEquals(CgIndirect.INSTANCES.ordinal() | 2 << 2, packed.countModes[2]);
        assertNull(packed.counts[3]);
        assertEquals(2, packed.instances[3]);
    }

    @Test
    public void theBuilderRefusesWhatAnIndirectDrawCannotMean() {
        CgGraphBuffer live = CgGraphBuffer.persistent("live", DESC);
        CgRecording rec = new CgRecording();
        int bindings = rec.bindings().begin().end();
        CgChunkBuilder c = rec.chunks().begin();
        CgMesh strip = CgMesh.vertices(32, CgMeshTopology.TRIANGLE_STRIP);

        c.draw(pipeline, bindings, CgMesh.quads(4));
        assertRefused(() -> c.indirect(live, 0, CgIndirect.VERTICES, 1), "on a mesh with indices");
        assertRefused(() -> c.indirect(live, 2, CgIndirect.INDICES, 1), "a whole uint's");
        assertRefused(() -> c.indirect(live, 0, CgIndirect.INDICES, 0), "factor 0");
        c.draw(pipeline, bindings, strip);
        assertRefused(() -> c.indirect(live, 0, CgIndirect.INDICES, 1), "on a mesh without indices");
        c.draw(quadPipeline, bindings);
        assertRefused(() -> c.indirect(live, 0, CgIndirect.INSTANCES, 1), "no mesh");

        c.draw(pipeline, bindings, strip).indirect(live, 0, CgIndirect.INSTANCES, 1);
        c.instance();
        c.instance();
        assertRefused(c::end, "holds 2");
    }

    @Test
    public void aDrawOfObjectsRunsAfterTheKernelWritingThem_andDrawsAloneFromTheirBuffer() {
        CgGraphBuffer records = CgGraphBuffer.transientBuffer("records",
                CgBufferDesc.elements(64, CgInstanceKind.OBJECT.floats() * 4, CgBufferUsage.STORAGE));
        CgGraphBuffer live = CgGraphBuffer.persistent("live", DESC);
        CgMesh quad = CgMesh.quads(1);
        CgRecording rec = new CgRecording();
        int bindings = rec.bindings().begin().end();
        CgRasterPass draw = raster(rec);
        CgComputePass write = rec.compute("records");
        write.dispatch(count, 1).bind("COUNTS", records);
        write.end();
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(pipeline, bindings, quad).objects(records, 8, 40);
        c.draw(pipeline, bindings, quad).objects(records, 0, 40).indirect(live, 0, CgIndirect.INSTANCES, 1);
        for (int i = 0; i < 2; i++) {
            c.draw(pipeline, bindings, quad);
            c.instance();
        }
        draw.add(c.end());
        draw.end();

        CgFrame frame = build(rec);
        assertEquals(List.of("records", "raster target"), names(frame));
        assertEquals(CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ, bitsOf(frame, 1, records));
        CgFrame.Raster packed = frame.rasters[1];
        assertEquals("each draw of objects alone, the two of records together", 3, packed.count);
        assertSame(records, packed.objects[0]);
        assertEquals(40, packed.instances[0]);
        assertEquals("records from the first named", 8, packed.first[0]);
        assertSame(records, packed.objects[1]);
        assertSame(live, packed.counts[1]);
        assertNull(packed.objects[2]);
        assertEquals(2, packed.instances[2]);
        assertEquals("only the records written here are the frame's", 1 << CgInstanceKind.OBJECT.ordinal(), packed.kinds);
    }

    @Test
    public void theBuilderRefusesObjectsBesideRecordsOfItsOwn() {
        CgGraphBuffer records = CgGraphBuffer.persistent("records", DESC);
        CgRecording rec = new CgRecording();
        int bindings = rec.bindings().begin().end();
        CgChunkBuilder c = rec.chunks().begin();

        c.draw(pipeline, bindings, CgMesh.quads(1)).objects(records, 0, 4);
        assertRefused(c::instance, "objects()");
        c.draw(pipeline, bindings, CgMesh.quads(1));
        c.instance();
        assertRefused(() -> c.objects(records, 0, 4), "wrote its own records");
        c.draw(pipeline, bindings, CgMesh.quads(1));
        assertRefused(() -> c.objects(records, 0, 0), "count 0");
        assertRefused(() -> c.objects(records, -1, 4), "first -1");
        c.draw(quadPipeline, bindings);
        assertRefused(() -> c.objects(records, 0, 4), "no mesh");
    }

    private static void assertRefused(Runnable call, String saying) {
        try {
            call.run();
            fail("not refused: expected one saying '" + saying + "'");
        } catch (IllegalArgumentException | IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(saying));
        }
    }

    private static int bitsOf(CgFrame frame, int step, CgGraphResource resource) {
        for (int i = 0; i < frame.accesses(step); i++) if (frame.accessed(step, i) == resource) return frame.accessBits(step, i);
        return 0;
    }

    private static List<String> names(CgFrame frame) {
        List<String> names = new ArrayList<>();
        for (int s = 0; s < frame.passes(); s++) names.add(frame.passName(s));
        return names;
    }
}
