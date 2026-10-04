package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.material.CgMaterialTestSupport;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * gpu-compute C3, GL-free: what the frame builder decides for compute passes and graph buffers — order from what
 * kernels read and write, culling, transient lifetimes, the accesses barriers are derived from — and the barrier rule
 * itself.
 */
public class CgComputeGraphTest {

    private static final String SOURCE = """
            #pragma kernel Produce map
            #pragma kernel Consume map
            #pragma kernel Step map
            Buffers {
                SRC   ("Source",    vec4, readonly)
                DST   ("Dest",      vec4, writeonly)
                IN    ("State in",  vec4, readonly)
                OUT   ("State out", vec4, writeonly)
            }
            void Produce() { DST_WRITE(vec4(float(CG_ELEMENT))); }
            void Consume() { DST_WRITE(SRC(CG_ELEMENT) * 2.0); }
            void Step() { OUT_WRITE(IN(CG_ELEMENT) + vec4(1.0)); }
            """;

    private static final CgBufferDesc DESC = CgBufferDesc.elements(64, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY);

    private final CgCompute compute = CgCompute.fromSource("test:compute-graph", SOURCE);
    private final CgKernel produce = compute.kernel("Produce");
    private final CgKernel consume = compute.kernel("Consume");
    private final CgKernel step = compute.kernel("Step");
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private CgMaterial material;

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        material = CgMaterial.fromSource("""
                #type none
                Pass {
                    void vertex(out v2f o) { }
                    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
                }
                """);
    }

    private CgFrame build(CgRecording... recordings) {
        CgFrameGraph graph = new CgFrameGraph();
        for (CgRecording r : recordings) graph.add(r.seal());
        return builder.build(graph);
    }

    private static List<String> names(CgFrame frame) {
        List<String> names = new ArrayList<>();
        for (int s = 0; s < frame.passes(); s++) names.add(frame.passName(s));
        return names;
    }

    // ── Order and culling ─────────────────────────────────────────────────────

    @Test
    public void aKernelRunsAfterTheKernelWhoseOutputItReads_whicheverWasRecordedFirst() {
        CgGraphBuffer scratch = CgGraphBuffer.transientBuffer("scratch", DESC);
        CgGraphBuffer out = CgGraphBuffer.persistent("out", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass second = rec.compute("consume");
        CgComputePass first = rec.compute("produce");
        first.dispatch(produce, 64).bind("DST", scratch);
        first.end();
        second.dispatch(consume, 64).bind("SRC", scratch).bind("DST", out);
        second.end();
        assertEquals(List.of("produce", "consume"), names(build(rec)));
    }

    @Test
    public void aKernelWritingWhatNobodyReadsIsCulled_oneWritingWhatOutlivesTheFrameIsNot() {
        CgRecording rec = new CgRecording();
        CgComputePass wasted = rec.compute("wasted");
        wasted.dispatch(produce, 64).bind("DST", CgGraphBuffer.transientBuffer("nobody", DESC));
        wasted.end();
        CgComputePass kept = rec.compute("kept");
        kept.dispatch(produce, 64).bind("DST", CgGraphBuffer.persistent("kept", DESC));
        kept.end();
        assertEquals(List.of("kept"), names(build(rec)));
    }

    @Test
    public void aDrawReadingABufferRunsAfterTheKernelThatWroteIt() {
        CgGraphBuffer cells = CgGraphBuffer.transientBuffer("cells", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass write = rec.compute("write");
        write.dispatch(produce, 64).bind("DST", cells);
        write.end();
        CgRasterPass draw = rec.raster(CgGraphTexture.requested("target", new CgTextureDesc(8, 8, CgTextureDesc.RGBA8)),
                CgLoad.load(), new CgPassConstants(), null, CgOrder.LOOKBACK);
        int bindings = rec.bindings().begin().storage(3, cells).end();
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(material.pipeline(CgInstanceKind.QUAD), bindings);
        c.instance();
        c.bounds(0, 0, 8, 8);
        draw.add(c.end());
        draw.end();
        CgFrame frame = build(rec);
        assertEquals(List.of("write", "raster target"), names(frame));
        assertEquals(CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ, bitsOf(frame, 1, cells));
    }

    @Test
    public void fillsAndCopiesAreOrderedLikeAnyWrite() {
        CgGraphBuffer counts = CgGraphBuffer.persistent("counts", DESC);
        CgGraphBuffer copy = CgGraphBuffer.persistent("copy", DESC);
        CgRecording rec = new CgRecording();
        rec.fill(counts, 0);
        CgComputePass add = rec.compute("add");
        add.dispatch(consume, 64).bind("SRC", counts).bind("DST", copy);
        add.end();
        rec.copy(copy, 0, counts, 0, DESC.bytes());
        assertEquals(List.of("fill counts", "add", "copy copy -> counts"), names(build(rec)));
    }

    @Test
    public void aResizedHistory_isANewHandleHoldingBothVersions_andTheOldOneIsReleasedAfter() {
        CgGraphBuffer state = CgGraphBuffer.history("state", DESC);
        CgBufferDesc bigger = CgBufferDesc.elements(256, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
        CgRecording rec = new CgRecording();
        CgComputePass sim = rec.compute("sim");
        sim.dispatch(step, 64).bind("IN", state).bind("OUT", state);
        sim.end();
        CgGraphBuffer grown = rec.resize(state, bigger);
        assertNotSame(state, grown);
        assertEquals(CgGraphBuffer.Kind.HISTORY, grown.kind());
        assertEquals(bigger.bytes(), grown.size());
        assertEquals(List.of("sim", "copy state.previous -> state", "copy state -> state", "release state"), names(build(rec)));
    }

    @Test
    public void aResizedPersistentBuffer_copiesTheSmallerSize_andOnlyKeptBuffersResize() {
        CgGraphBuffer counts = CgGraphBuffer.persistent("counts", DESC);
        CgBufferDesc smaller = CgBufferDesc.elements(16, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
        CgRecording rec = new CgRecording();
        rec.fill(counts, 0);
        CgGraphBuffer shrunk = rec.resize(counts, smaller);
        assertEquals(CgGraphBuffer.Kind.PERSISTENT, shrunk.kind());
        assertEquals(List.of("fill counts", "copy counts -> counts", "release counts"), names(build(rec)));
        CgRecording other = new CgRecording();
        assertThrows(IllegalArgumentException.class, () -> other.resize(CgGraphBuffer.transientBuffer("t", DESC), DESC));
        assertThrows(IllegalArgumentException.class, () -> other.resize(CgGraphBuffer.history("h", DESC).previous(), DESC));
    }

    @Test
    public void aReadbackKeepsTheTransientWriterItReads_andIsNeverCulled() {
        CgGraphBuffer scratch = CgGraphBuffer.transientBuffer("scratch", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass write = rec.compute("write");
        write.dispatch(produce, 64).bind("DST", scratch);
        write.end();
        CgComputePass wasted = rec.compute("wasted");
        wasted.dispatch(produce, 64).bind("DST", CgGraphBuffer.transientBuffer("nobody", DESC));
        wasted.end();
        CgRequest got = rec.readback(scratch, 16, 32, data -> { });
        CgFrame frame = build(rec);
        assertEquals(List.of("write", "readback scratch"), names(frame));
        assertEquals(CgAccess.COPY_READ, bitsOf(frame, 1, scratch));
        assertEquals("answered once the bytes land, frames after the frame executes", CgRequest.Status.PENDING, got.status());
    }

    @Test
    public void aReadbackOutsideItsBufferOrOfANonCopyBufferIsRefused() {
        CgRecording rec = new CgRecording();
        CgGraphBuffer storageOnly = CgGraphBuffer.transientBuffer("storage", CgBufferDesc.elements(4, 4, CgBufferUsage.STORAGE));
        assertThrows(IllegalArgumentException.class, () -> rec.readback(storageOnly, 0, 4, data -> { }));
        CgGraphBuffer counts = CgGraphBuffer.persistent("counts", DESC);
        assertThrows(IllegalArgumentException.class, () -> rec.readback(counts, DESC.bytes() - 4, 8, data -> { }));
    }

    // ── Lifetimes and accesses ────────────────────────────────────────────────

    @Test
    public void transientBuffersLiveFromFirstToLastUse() {
        CgGraphBuffer a = CgGraphBuffer.transientBuffer("a", DESC), b = CgGraphBuffer.transientBuffer("b", DESC);
        CgGraphBuffer out = CgGraphBuffer.persistent("out", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass p1 = rec.compute("p1");
        p1.dispatch(produce, 64).bind("DST", a);
        p1.end();
        CgComputePass p2 = rec.compute("p2");
        p2.dispatch(consume, 64).bind("SRC", a).bind("DST", b);
        p2.end();
        CgComputePass p3 = rec.compute("p3");
        p3.dispatch(consume, 64).bind("SRC", b).bind("DST", out);
        p3.end();
        CgFrame frame = build(rec);
        Map<CgGraphResource, int[]> lives = new IdentityHashMap<>();
        for (int i = 0; i < frame.transients().size(); i++) {
            lives.put(frame.transients().get(i), new int[]{frame.acquiredAt(i), frame.releasedAfter(i)});
        }
        assertArrayEquals(new int[]{0, 1}, lives.get(a));
        assertArrayEquals(new int[]{1, 2}, lives.get(b));
        assertFalse(lives.containsKey(out));
    }

    @Test
    public void eachStepListsWhatItReadsAndWrites_fromWhatItsKernelsUse() {
        CgGraphBuffer src = CgGraphBuffer.persistent("src", DESC), dst = CgGraphBuffer.persistent("dst", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass pass = rec.compute("consume");
        pass.dispatch(consume, 64).bind("SRC", src).bind("DST", dst);
        pass.end();
        CgFrame frame = build(rec);
        assertEquals(CgAccess.COMPUTE_READ, bitsOf(frame, 0, src));
        assertEquals(CgAccess.COMPUTE_WRITE, bitsOf(frame, 0, dst));
    }

    // ── Barriers ──────────────────────────────────────────────────────────────

    @Test
    public void barrierRule_aReadAfterAKernelWriteWaitsOncePerKindOfReader() {
        CgHazards hazards = new CgHazards();
        long key = CgHazards.buffer(7);
        assertEquals(0, hazards.access(key, CgAccess.COMPUTE_WRITE));
        assertEquals(CgAccess.COMPUTE_WRITE, hazards.access(key, CgAccess.COMPUTE_READ));
        assertEquals(0, hazards.access(key, CgAccess.COMPUTE_READ));
        assertEquals(CgAccess.COMPUTE_WRITE, hazards.access(key, CgAccess.VERTEX_READ));
        assertEquals(0, hazards.access(key, CgAccess.VERTEX_READ));
    }

    @Test
    public void barrierRule_aWriteWaitsForTheWriteBeforeIt_orForTheReadsThatWaitedForIt() {
        CgHazards hazards = new CgHazards();
        long key = CgHazards.buffer(7);
        hazards.access(key, CgAccess.COMPUTE_WRITE);
        assertEquals(CgAccess.COMPUTE_WRITE, hazards.access(key, CgAccess.COMPUTE_WRITE));
        hazards.access(key, CgAccess.VERTEX_READ);
        assertEquals(CgAccess.VERTEX_READ, hazards.access(key, CgAccess.COMPUTE_WRITE));
    }

    @Test
    public void barrierRule_drawsAndCopiesAloneAreLeftToTheBackend_butAKernelAfterThemWaits() {
        CgHazards hazards = new CgHazards();
        long key = CgHazards.texture(3);
        assertEquals(0, hazards.access(key, CgAccess.COLOR_WRITE));
        assertEquals(0, hazards.access(key, CgAccess.SAMPLED_READ));
        assertEquals(CgAccess.COLOR_WRITE, hazards.access(key, CgAccess.COMPUTE_READ));
        assertEquals(CgAccess.SAMPLED_READ | CgAccess.COMPUTE_READ, hazards.access(key, CgAccess.COMPUTE_WRITE));
    }

    @Test
    public void barrierRule_aStorageIsItsOwn_andForgottenWhenFreed() {
        CgHazards hazards = new CgHazards();
        hazards.access(CgHazards.buffer(1), CgAccess.COMPUTE_WRITE);
        assertEquals(0, hazards.access(CgHazards.buffer(2), CgAccess.COMPUTE_READ));
        assertEquals(0, hazards.access(CgHazards.texture(1), CgAccess.COMPUTE_READ));
        hazards.forget(CgHazards.buffer(1));
        assertEquals(0, hazards.access(CgHazards.buffer(1), CgAccess.COMPUTE_READ));
    }

    /**
     * The frame's accesses, run through the rule as the executor runs them, give a barrier exactly where a kernel's write
     * meets a later access; the same accesses with that barrier missing are what the check below catches.
     */
    @Test
    public void aBuiltFrameFencesEveryKernelWriteBeforeItsReader_andAMissingFenceIsCaught() {
        CgGraphBuffer scratch = CgGraphBuffer.transientBuffer("scratch", DESC);
        CgGraphBuffer out = CgGraphBuffer.persistent("out", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass p1 = rec.compute("produce");
        p1.dispatch(produce, 64).bind("DST", scratch);
        p1.end();
        CgComputePass p2 = rec.compute("consume");
        p2.dispatch(consume, 64).bind("SRC", scratch).bind("DST", out);
        p2.end();
        CgFrame frame = build(rec);

        List<String> barriers = derive(frame);
        assertEquals(List.of("consume: scratch COMPUTE_WRITE -> COMPUTE_READ"), barriers);
        assertNull(unfenced(frame, barriers));
        assertEquals("consume reads scratch after a kernel wrote it, with no barrier", unfenced(frame, List.of()));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @Test
    public void aHistoryIsReadThroughOneBindingAndWrittenThroughAnother() {
        CgGraphBuffer state = CgGraphBuffer.history("state", DESC);
        CgRecording rec = new CgRecording();
        CgComputePass sim = rec.compute("step");
        sim.dispatch(step, 64).bind("IN", state).bind("OUT", state);
        sim.end();
        assertEquals(List.of("step"), names(build(rec)));
        assertThrows(IllegalArgumentException.class,
                () -> new CgRecording().compute("x").dispatch(produce, 64).bind("DST", state.previous()));
    }

    @Test
    public void aHistoryWriteMakesTheNextVersionTheNewest() {
        CgGraphBuffer state = CgGraphBuffer.history("state", DESC);
        int[] versions = state.versions();
        versions[0] = 10;
        versions[1] = 11;
        assertEquals(10, state.bufferId());
        assertEquals(11, state.previous().bufferId());
        assertEquals(11, state.nextVersion());
        state.advance();
        assertEquals(11, state.bufferId());
        assertEquals(10, state.previous().bufferId());
    }

    // ── Scratch ───────────────────────────────────────────────────────────────

    @Test
    public void aRecordingReusedEachFrameHandsOutTheSameScratch_inTheOrderAskedFor() {
        CgFrameBufferFormat rgba8 = CgFrameBufferFormat.builder("scratch").color(0, CgTextureType.RGBA8).build();
        CgRecording rec = new CgRecording();
        CgGraphTexture across = rec.scratch("blur", 64, 32, rgba8);
        CgGraphBuffer a = rec.scratch("a", 256, CgBufferUsage.STORAGE), b = rec.scratch("b", 256, CgBufferUsage.STORAGE);
        assertNotSame(a, b);

        rec.reset();
        assertSame(across, rec.scratch("blur", 64, 32, rgba8));
        assertSame(a, rec.scratch("a", 256, CgBufferUsage.STORAGE));
        assertNotSame(b, rec.scratch("b", 512, CgBufferUsage.STORAGE));   // resized: made again, and kept from now
        CgGraphBuffer resized = rec.scratch("c", 64, CgBufferUsage.STORAGE);

        rec.reset();
        rec.scratch("blur", 64, 32, rgba8);
        rec.scratch("a", 256, CgBufferUsage.STORAGE);
        assertEquals(512, rec.scratch("b", 512, CgBufferUsage.STORAGE).desc().bytes());
        assertSame(resized, rec.scratch("c", 64, CgBufferUsage.STORAGE));
    }

    // ── What a dispatch refuses ───────────────────────────────────────────────

    @Test
    public void aDispatchMustBindEveryBufferItsKernelUses() {
        CgComputePass pass = new CgRecording().compute("consume");
        pass.dispatch(consume, 64).bind("DST", CgGraphBuffer.persistent("dst", DESC));
        IllegalStateException e = assertThrows(IllegalStateException.class, pass::end);
        assertTrue(e.getMessage(), e.getMessage().contains("uses SRC, which is not bound"));
    }

    @Test
    public void aDispatchRefusesUnknownNamesAndBuffersWithoutTheUse() {
        CgDispatch d = new CgRecording().compute("p").dispatch(produce, 64);
        assertThrows(IllegalArgumentException.class, () -> d.bind("NOPE", CgGraphBuffer.persistent("x", DESC)));
        CgGraphBuffer vertices = CgGraphBuffer.persistent("v", CgBufferDesc.of(256, CgBufferUsage.VERTEX));
        assertThrows(IllegalArgumentException.class, () -> d.bind("DST", vertices));
        assertThrows(IllegalArgumentException.class, () -> new CgRecording().compute("i").dispatchIndirect(produce,
                CgGraphBuffer.persistent("args", DESC), 0));
    }

    // ── The executor's walk, GL-free ──────────────────────────────────────────

    /** The barriers the executor's rule gives the frame, one storage per resource. */
    private static List<String> derive(CgFrame frame) {
        CgHazards hazards = new CgHazards();
        Map<CgGraphResource, Integer> ids = new IdentityHashMap<>();
        List<String> out = new ArrayList<>();
        walk(frame, (s, resource, bits) -> {
            int id = ids.computeIfAbsent(resource, r -> ids.size() + 1);
            int from = hazards.access(CgHazards.buffer(id), bits);
            if (from != 0) {
                out.add(frame.passName(s) + ": " + resource.name() + " " + CgAccess.names(from) + " -> " + CgAccess.names(bits));
            }
        });
        return out;
    }

    /** The first read after a kernel's write with no barrier in {@code barriers} between them, or null. */
    private static String unfenced(CgFrame frame, List<String> barriers) {
        Map<CgGraphResource, Boolean> written = new IdentityHashMap<>();
        String[] found = {null};
        walk(frame, (s, resource, bits) -> {
            if (found[0] != null) return;
            boolean fenced = barriers.stream().anyMatch(b -> b.startsWith(frame.passName(s) + ": " + resource.name() + " "));
            if ((bits & CgAccess.COMPUTE_READ) != 0 && Boolean.TRUE.equals(written.get(resource)) && !fenced) {
                found[0] = frame.passName(s) + " reads " + resource.name() + " after a kernel wrote it, with no barrier";
            }
            if ((bits & CgAccess.COMPUTE_WRITE) != 0) written.put(resource, true);
        });
        return found[0];
    }

    private interface Access {
        void at(int step, CgGraphResource resource, int bits);
    }

    /** Each access in executed order: a compute step's per dispatch and binding, any other's from its list. */
    private static void walk(CgFrame frame, Access access) {
        for (int s = 0; s < frame.passes(); s++) {
            if (frame.pass(s) instanceof CgComputePass compute) {
                for (CgDispatch d : compute.dispatches()) {
                    for (int b = 0; b < d.buffers.length; b++) {
                        if (d.buffers[b] != null && d.bufferAccess[b] != 0) access.at(s, d.buffers[b], d.bufferAccess[b]);
                    }
                }
            } else {
                for (int i = 0; i < frame.accesses(s); i++) access.at(s, frame.accessed(s, i), frame.accessBits(s, i));
            }
        }
    }

    private static int bitsOf(CgFrame frame, int step, CgGraphResource resource) {
        for (int i = 0; i < frame.accesses(step); i++) if (frame.accessed(step, i) == resource) return frame.accessBits(step, i);
        return 0;
    }
}
