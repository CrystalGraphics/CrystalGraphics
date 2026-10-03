package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.material.CgMaterialTestSupport;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * render-graph G1: what the frame builder decides with no GL — order from reads and writes, culling, transient
 * lifetimes, batching and packing — including on a thread that is not the render thread.
 */
public class CgFrameBuilderTest {

    private static final String SOURCE = """
            #type none
            Properties {
                _Alpha ("Alpha", float) = 1
            }
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Alpha); }
            }
            """;

    private static final CgTextureDesc DESC = new CgTextureDesc(64, 64, CgTextureDesc.RGBA8);
    private final CgPassConstants constants = new CgPassConstants().resolution(64, 64);
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private CgMaterial material;
    private CgPipeline quads;

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        material = CgMaterial.fromSource(SOURCE);
        quads = material.pipeline(CgInstanceKind.QUAD);
    }

    /** A chunk of quads, each one record whose first float is {@code tag}, laid left to right. */
    private CgDrawChunk quads(CgRecording rec, float tag, int count, float x) {
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(quads, material.captureBindings(rec.bindings()));
        for (int i = 0; i < count; i++) {
            int at = c.instance();
            c.data()[at] = tag;
            c.bounds(x + i * 10, 0, x + i * 10 + 10, 10);
        }
        return c.end();
    }

    /** A chunk that samples {@code texture}: what compositing a layer records. */
    private CgDrawChunk sampling(CgRecording rec, CgGraphTexture texture) {
        int bindings = rec.bindings().begin().texture(0, texture).end();
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(quads, bindings);
        c.instance();
        c.bounds(0, 0, 64, 64);
        return c.end();
    }

    private CgRasterPass raster(CgRecording rec, CgGraphTexture target) {
        return rec.raster(target, CgLoad.load(), constants, null, CgOrder.LOOKBACK);
    }

    private static final String READER = """
            #type none
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = texture(cg_SceneColor, vec2(0.5)); }
            }
            """;

    /** One draw of {@code pipeline} at sort key {@code key}. */
    private CgDrawChunk keyed(CgRecording rec, CgMaterial of, CgPipeline pipeline, long key) {
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(pipeline, of.captureBindings(rec.bindings())).sortKey(key);
        c.instance();
        c.bounds(0, 0, 10, 10);
        return c.end();
    }

    /**
     * A reader of the target is copied for before it whenever a draw since the last copy wrote what it reads; readers
     * in a row share one copy, in sorted order whatever order they were added in.
     */
    @Test
    public void aReaderGetsACopyOfWhatDrewBeforeIt() {
        CgMaterial haze = CgMaterial.fromSource(READER);
        CgPipeline reads = haze.pipeline(CgInstanceKind.QUAD);
        assertTrue(reads.shader().readsSceneColor());
        assertFalse(quads.shader().readsSceneColor());

        CgRecording rec = new CgRecording();
        CgRasterPass pass = rec.raster(CgGraphTexture.requested("t", DESC), CgLoad.load(), constants, null, CgOrder.SORTED)
                .sceneColor(5);
        pass.add(keyed(rec, haze, reads, 5));      // after the second plain draw: a copy of its own
        pass.add(keyed(rec, material, quads, 1));
        pass.add(keyed(rec, haze, reads, 2));      // first reader: a copy
        pass.add(keyed(rec, haze, reads, 3));      // shares it
        pass.add(keyed(rec, material, quads, 4));
        pass.end();
        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        CgFrame.Raster packed = frame.rasters[0];

        int copies = 0, readersSeen = 0;
        for (int b = 0; b < packed.count; b++) {
            boolean reader = packed.pipeline[b] == reads.id();
            if (packed.copyBefore[b] != 0) {
                assertTrue("a copy only before a reader", reader);
                assertEquals(CgTargetCopy.COLOR, packed.copyBefore[b]);
                copies++;
            }
            if (reader) readersSeen++;
        }
        assertTrue(readersSeen >= 2);
        assertEquals(2, copies);
        builder.recycle(frame);
    }

    /** A nested scissor is issued once per pass, from the entry that changed, each inside the one before it. */
    @Test
    public void aScissorChainIsIssuedFromWhereItChanged() {
        CgRecording rec = new CgRecording();
        CgPassRecorder recorder = new CgPassRecorder();
        recorder.recordInto(rec, CgGraphTexture.requested("surface", DESC), CgLoad.load(), constants);
        recorder.pushScissor(0, 0, 64, 64);
        recorder.pushScissor(3, 0f, 0f, 20f, 20f);
        recorder.add(quads(rec, 1, 1, 0));
        recorder.popScissor();
        recorder.pushScissor(3, 0f, 0f, 20f, 20f);
        recorder.add(quads(rec, 2, 1, 0));
        recorder.popScissor();
        recorder.pushScissor(4, 0f, 0f, 10f, 10f);
        recorder.add(quads(rec, 3, 1, 0));
        recorder.stop();

        CgRasterPass pass = (CgRasterPass) rec.pass(0);
        assertEquals("the same chain again is the same scissor", pass.chunkScissor(0), pass.chunkScissor(1));
        int outer = pass.scissorParent(pass.chunkScissor(0));
        assertEquals(-1, pass.scissorNode(outer));
        assertEquals("a changed inner entry is cut by the same outer one", outer, pass.scissorParent(pass.chunkScissor(2)));
        assertEquals(4, pass.scissorNode(pass.chunkScissor(2)));
    }

    @Test
    public void aLayerRunsBeforeThePassThatCompositesItWhicheverWasMadeFirst() {
        CgRecording rec = new CgRecording();
        CgGraphTexture surface = CgGraphTexture.requested("surface", DESC);
        CgGraphTexture layer = CgGraphTexture.transientTexture("layer", DESC);

        CgRasterPass parent = raster(rec, surface);
        parent.add(quads(rec, 1, 1, 0));
        CgRasterPass inner = raster(rec, layer);
        inner.add(quads(rec, 2, 1, 0));
        inner.end();
        parent.add(sampling(rec, layer));
        parent.end();

        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        assertEquals(2, frame.passes());
        assertEquals("raster layer", frame.passName(0));
        assertEquals("raster surface", frame.passName(1));
        assertEquals(1, frame.transients.size());
        assertEquals("acquired for the layer's pass", 0, frame.acquireAt[0]);
        assertEquals("returned after the composite reads it", 1, frame.releaseAfter[0]);
    }

    @Test
    public void aBackdropSplitRunsInRecordedOrder() {
        CgRecording rec = new CgRecording();
        CgGraphTexture surface = CgGraphTexture.requested("surface", DESC);
        CgGraphTexture backdrop = CgGraphTexture.transientTexture("backdrop", DESC);

        CgRasterPass before = raster(rec, surface);
        before.add(quads(rec, 1, 1, 0));
        before.end();
        rec.copy(surface, 0, 0, 64, 64, backdrop, 0, 0, 64, 64, false);
        CgRasterPass after = raster(rec, surface);
        after.add(sampling(rec, backdrop));
        after.end();

        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        assertEquals(3, frame.passes());
        assertSame(before, frame.steps[0]);
        assertTrue(frame.steps[1] instanceof CgPass.Copy);
        assertSame(after, frame.steps[2]);
    }

    @Test
    public void workNobodyReadsIsCulled() {
        CgRecording rec = new CgRecording();
        CgRasterPass unread = raster(rec, CgGraphTexture.transientTexture("unread", DESC));
        unread.add(quads(rec, 1, 1, 0));
        unread.end();
        CgRasterPass shown = raster(rec, CgGraphTexture.requested("surface", DESC));
        shown.add(quads(rec, 2, 1, 0));
        shown.end();

        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        assertEquals(1, frame.passes());
        assertSame(shown, frame.steps[0]);
        assertTrue(frame.transients.isEmpty());
    }

    @Test
    public void recordingsBatchAndPackTogether() {
        CgGraphTexture surface = CgGraphTexture.requested("surface", DESC);
        CgRecording first = new CgRecording(), second = new CgRecording();
        CgRasterPass a = raster(first, surface);
        a.add(quads(first, 1, 2, 0));
        a.end();
        CgRasterPass b = raster(second, surface);
        b.add(quads(second, 2, 3, 0));
        b.end();

        CgFrame frame = builder.build(new CgFrameGraph().add(first.seal()).add(second.seal()));
        assertEquals("the second recording draws over the first", 2, frame.passes());
        assertSame(a, frame.steps[0]);
        assertEquals("one material snapshot from two tables, one constants snapshot from two passes", 2,
                frame.bindings.size());
        assertEquals(5, frame.instances(CgInstanceKind.QUAD));
        assertEquals(2, frame.batches());

        float[] packed = frame.instances[CgInstanceKind.QUAD.ordinal()];
        int floats = CgInstanceKind.QUAD.floats();
        float[] tags = new float[5];
        for (int i = 0; i < 5; i++) tags[i] = packed[i * floats];
        assertArrayEquals(new float[]{1, 1, 2, 2, 2}, tags, 0f);
        CgFrame.Raster secondPass = frame.rasters[1];
        assertEquals("the second pass's batch starts after the first's instances", 2, secondPass.first[0]);
        assertEquals(3, secondPass.instances[0]);
    }

    @Test
    public void builtOnAnotherThread() throws Exception {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = raster(rec, CgGraphTexture.requested("surface", DESC));
        pass.add(quads(rec, 1, 4, 0));
        pass.add(quads(rec, 1, 4, 100));
        pass.end();
        CgFrameGraph graph = new CgFrameGraph().add(rec.seal());

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            CgFrame frame = other.submit(() -> builder.build(graph)).get();
            assertEquals(1, frame.batches());
            assertEquals(2, frame.draws());
            assertEquals(8, frame.instances(CgInstanceKind.QUAD));
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    public void aRecordingIsFrozenOnceSealed() {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = raster(rec, CgGraphTexture.requested("surface", DESC));
        assertThrows(IllegalStateException.class, rec::seal);
        pass.end();
        rec.seal();
        assertThrows(IllegalStateException.class, () -> raster(rec, CgGraphTexture.current()));
    }
}
