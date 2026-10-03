package com.crystalgraphics.compute.lower;

import com.crystalgraphics.compute.lower.CgLowering.Kind;
import com.crystalgraphics.compute.lower.CgLowering.Op;
import com.crystalgraphics.compute.lower.CgLowering.Pass;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class CgLoweringTest {

    /** 17 vec4s: 68 words, more than one capture holds. */
    private static final String WIDE_FIELDS = "vec4 a0; vec4 a1; vec4 a2; vec4 a3; vec4 a4; vec4 a5; vec4 a6; vec4 a7; "
            + "vec4 a8; vec4 a9; vec4 a10; vec4 a11; vec4 a12; vec4 a13; vec4 a14; vec4 a15; vec4 a16; ";

    /** One kernel {@code K} of {@code shape} over a buffer of each kind and two images. */
    private static CgComputeSource parse(String shape, String body) {
        return CgComputeParser.parse("#pragma kernel K " + shape + "\n"
                + "struct Wide { " + WIDE_FIELDS + "};\n"
                + "Buffers {\n"
                + "    IN    (\"In\",    float, readonly)\n"
                + "    OUT   (\"Out\",   vec4,  readwrite)\n"
                + "    WIDE  (\"Wide\",  Wide,  writeonly)\n"
                + "    SPAWN (\"Spawn\", uvec2, append)\n"
                + "    BINS  (\"Bins\",  uint,  counter)\n"
                + "    VALS  (\"Vals\",  float, readwrite)\n"
                + "}\n"
                + "Images {\n"
                + "    PIC  (\"Pic\",  rgba8,       writeonly)\n"
                + "    SN   (\"Sn\",   rgba8_snorm, writeonly)\n"
                + "    CUBE (\"Cube\", rgba8,       writeonly, cube)\n"
                + "}\n"
                + "shared float cache[64];\n"
                + "void K() {\n" + body + "\n}\n", "test:shaders/lower.compute");
    }

    private static String refusal(String shape, String body) {
        CgComputeSource s = parse(shape, body);
        return CgLowering.refusal(s, s.kernel("K"));
    }

    private static List<Pass> passes(String shape, String body) {
        CgComputeSource s = parse(shape, body);
        assertNull(CgLowering.refusal(s, s.kernel("K")));
        return CgLowering.passes(s, s.kernel("K"));
    }

    // ── Passes ────────────────────────────────────────────────────────────────

    @Test
    public void map_capturesEachWrittenBuffer() {
        List<Pass> p = passes("map", "OUT_WRITE(OUT(CG_ELEMENT) * IN(CG_ELEMENT)); VALS_WRITE(1.0);");
        assertEquals(2, p.size());
        assertEquals(Kind.OUTPUT, p.get(0).kind());
        assertEquals("OUT", p.get(0).buffer().name());
        assertEquals("VALS", p.get(1).buffer().name());
    }

    @Test
    public void append_isOnePassPerBuffer_afterTheOutputs() {
        List<Pass> p = passes("append", "VALS_WRITE(0.0); if (CG_ELEMENT % 2 == 0) SPAWN_APPEND(uvec2(1u)); SPAWN_APPEND(uvec2(2u));");
        assertEquals(List.of(Kind.OUTPUT, Kind.APPEND), p.stream().map(Pass::kind).toList());
        assertEquals("SPAWN", p.get(1).buffer().name());
    }

    @Test
    public void scatter_isOnePassPerOperation_buffersInOrder_storeFirst() {
        List<Pass> p = passes("scatter", "VALS_STORE(1, 2.0); VALS_ADD(0, 1.0); VALS_MIN(2, -1.0); BINS_INC(CG_ELEMENT % 4);");
        assertEquals(List.of(Op.ADD, Op.STORE, Op.ADD, Op.MIN), p.stream().map(Pass::op).toList());
        assertEquals("BINS", p.get(0).buffer().name());
        assertTrue("a buffer added to holds floats in its target, its stores too", p.get(1).floats());
    }

    @Test
    public void scatter_storeAlone_keepsTheElementsBits() {
        List<Pass> p = passes("scatter", "OUT_STORE(CG_ELEMENT, vec4(1.0));");
        assertEquals(1, p.size());
        assertFalse(p.get(0).floats());
    }

    @Test
    public void image_isAFragmentPassPerWrittenImage() {
        List<Pass> p = passes("8 8 image", "PIC_WRITE(vec4(1.0));");
        assertEquals(1, p.size());
        assertEquals(Kind.IMAGE, p.get(0).kind());
        assertEquals("PIC", p.get(0).image().name());
    }

    // ── Refusals, each naming its construct ───────────────────────────────────

    @Test
    public void general_namesSharedMemory() {
        String why = refusal("general", "cache[CG_LOCAL_INDEX] = IN(CG_ELEMENT); barrier(); VALS_WRITE(cache[0]);");
        assertTrue(why, why.contains("shared memory (cache)"));
    }

    @Test
    public void general_namesABarrier() {
        String why = refusal("general", "barrier(); VALS_WRITE(1.0);");
        assertTrue(why, why.contains("barrier"));
    }

    @Test
    public void general_withNothingComputeOnly_saysItIsDeclaredSo() {
        String why = refusal("general", "VALS_WRITE(1.0);");
        assertTrue(why, why.contains("declared so"));
    }

    @Test
    public void counterResult_used_isRefused_asAStatement_isNot() {
        String used = refusal("scatter", "uint before = BINS_INC(0); VALS_STORE(0, float(before));");
        assertTrue(used, used.contains("BINS_INC"));
        assertNull(refusal("scatter", "if (CG_ELEMENT > 2) BINS_INC(0); else BINS_ADD(1, 2u);"));
    }

    @Test
    public void elementWiderThanOneCapture_isRefused() {
        String why = refusal("map", "Wide w; WIDE_WRITE(w);");
        assertTrue(why, why.contains("WIDE") && why.contains("64 words"));
    }

    @Test
    public void cubeAndSnormWrites_areRefused() {
        assertTrue(refusal("8 8 image", "CUBE_WRITE(vec4(1.0));").contains("cube"));
        assertTrue(refusal("8 8 image", "SN_WRITE(vec4(1.0));").contains("rgba8_snorm"));
    }

    // ── Emission ──────────────────────────────────────────────────────────────

    @Test
    public void emit_eachKindTakesTheStagesItRunsIn() {
        CgComputeSource s = parse("append", "VALS_WRITE(IN(CG_ELEMENT)); SPAWN_APPEND(uvec2(1u));");
        List<Pass> p = CgLowering.passes(s, s.kernel("K"));
        CgLoweredEmitter.Stages output = CgLoweredEmitter.emit(s, s.kernel("K"), Set.of(), p.get(0), CgLoweredTarget.GL33);
        assertNull("an output pass is a vertex stage alone", output.geometry());
        assertNull(output.fragment());
        assertTrue(output.vertex().startsWith("#version 330 core\n"));
        assertArrayEquals(CgLoweredEmitter.captures(p.get(0).buffer()), output.varyings());

        CgLoweredEmitter.Stages append = CgLoweredEmitter.emit(s, s.kernel("K"), Set.of(), p.get(1), CgLoweredTarget.GL33);
        assertNotNull("an append pass emits from a geometry stage", append.geometry());
        assertTrue(append.geometry().contains("max_vertices"));
        assertNotNull("and counts what it emits in a fragment stage", append.fragment());
    }
}
