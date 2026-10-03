package com.crystalgraphics.compute.parse;

import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgImageFormat;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgKernelShape;
import com.crystalgraphics.compute.source.CgSourcePart;
import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.util.io.CgIO;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class CgComputeParserTest {

    private static final String EXAMPLE = "crystalgraphics:shaders/example.compute";

    private static CgComputeSource example() {
        return CgComputeParser.parse(CgIO.loadSource(EXAMPLE), EXAMPLE);
    }

    /** A file with the given kernel pragmas and body, around one readwrite and one readonly buffer. */
    private static CgComputeSource parse(String pragmas, String body) {
        return CgComputeParser.parse(pragmas + "\n"
                + "struct Item { vec4 a; vec4 b; };\n"
                + "struct Odd { vec3 p; float w; };\n"
                + "Buffers {\n"
                + "    OUT  (\"Out\",  Item, readwrite)\n"
                + "    ODD  (\"Odd\",  Odd,  readwrite)\n"
                + "    VALS (\"Vals\", float, readwrite)\n"
                + "}\n" + body, "test:shaders/t.compute");
    }

    private static String refusal(String pragmas, String body) {
        try {
            parse(pragmas, body);
        } catch (CgShaderParseException e) {
            return e.getMessage();
        }
        fail("parsed: " + pragmas);
        return null;
    }

    // ── The reference file ────────────────────────────────────────────────────

    @Test
    public void example_declaresEveryKernelWithItsSizeAndShape() {
        CgComputeSource s = example();
        assertEquals(List.of("Integrate", "Spawn", "Histogram", "Shade", "Reduce", "ReduceScatter"),
                s.kernels().stream().map(CgKernelDecl::name).toList());
        CgKernelDecl integrate = s.kernel("Integrate");
        assertEquals(CgKernelShape.MAP, integrate.shape());
        assertEquals(List.of(CgKernelDecl.DEFAULT_SIZE, 1, 1), List.of(integrate.sizeX(), integrate.sizeY(), integrate.sizeZ()));
        CgKernelDecl shade = s.kernel("Shade");
        assertEquals(List.of(8, 8, 1, 2), List.of(shade.sizeX(), shade.sizeY(), shade.sizeZ(), shade.dimensions()));
        assertEquals("ReduceScatter", s.kernel("Reduce").fallback());
        assertEquals(List.of("WIND"), s.features());
        assertEquals(5, s.properties().size());
    }

    @Test
    public void example_buffersAndImages() {
        CgComputeSource s = example();
        CgBufferDecl state = s.buffer("STATE");
        assertEquals(0, state.index());
        assertEquals(32, state.stride());
        assertTrue(state.lowerable());
        assertEquals(CgBufferAccess.COUNTER, s.buffer("BINS").access());
        assertTrue(s.buffer("WEIGHTS").scalar());
        assertEquals(CgImageFormat.RGBA8, s.image("HEAT").format());
        assertEquals(CgImageDimension.D2, s.image("HEAT").dimension());
        assertEquals(1, s.image("HEAT").index());
    }

    @Test
    public void example_eachKernelReachesOnlyWhatItCalls() {
        CgComputeSource s = example();
        assertTrue(s.kernel("Integrate").functions().contains("wind"));
        assertFalse(s.kernel("Histogram").functions().contains("wind"));
        assertEquals(Set.of("STATE", "STATE_WRITE"), s.kernel("Integrate").accessors());
        assertEquals(Set.of("STATE", "BINS_INC", "WEIGHTS_ADD"), s.kernel("Histogram").accessors());
        assertEquals(Set.of("partial"), s.kernel("Reduce").shared());
        assertEquals(256 * 4, s.kernel("Reduce").sharedBytes());
        assertTrue(s.kernel("Reduce").subgroups().contains("CG_SUBGROUP_ADD"));
        assertTrue(s.kernel("Integrate").shared().isEmpty());
    }

    @Test
    public void example_partsKeepEachFunctionAndBlockInPlace() {
        List<CgSourcePart> parts = example().parts();
        int buffers = -1, integrate = -1, particle = -1;
        for (int i = 0; i < parts.size(); i++) {
            CgSourcePart p = parts.get(i);
            if (p instanceof CgSourcePart.Buffers) buffers = i;
            if (p instanceof CgSourcePart.Function f && f.name().equals("Integrate")) integrate = i;
            if (p instanceof CgSourcePart.Text t && t.text().contains("struct Particle") && particle < 0) particle = i;
        }
        assertTrue(particle >= 0 && particle < buffers && buffers < integrate);
        for (CgSourcePart p : parts) {
            if (p instanceof CgSourcePart.Text t) {
                assertFalse(t.text(), t.text().contains("#pragma kernel Integrate"));
                assertFalse(t.text(), t.text().contains("Properties {"));
            }
        }
    }

    // ── Shapes ────────────────────────────────────────────────────────────────

    @Test
    public void mapKernel_reachingABarrierThroughAHelper_isRefused() {
        String message = refusal("#pragma kernel K map",
                "void sync() { barrier(); }\nvoid K() { sync(); OUT_WRITE(OUT(CG_ELEMENT)); }");
        assertTrue(message, message.contains("kernel K (map) reaches barrier"));
    }

    @Test
    public void mapKernel_storingAtAnIndex_isRefused() {
        String message = refusal("#pragma kernel K map", "void K() { OUT_STORE(0, OUT(1)); }");
        assertTrue(message, message.contains("kernel K (map) uses OUT_STORE"));
    }

    @Test
    public void scatterKernel_mayStoreAndAdd() {
        CgKernelDecl k = parse("#pragma kernel K scatter", "void K() { VALS_ADD(3, 1.0); OUT_STORE(1, OUT(0)); }").kernel("K");
        assertEquals(Set.of("VALS_ADD", "OUT_STORE", "OUT"), k.accessors());
    }

    @Test
    public void addOnAStructElement_isRefused() {
        String message = refusal("#pragma kernel K scatter", "void K() { OUT_ADD(0, OUT(1)); }");
        assertTrue(message, message.contains("needs a float, int or uint element"));
    }

    @Test
    public void lowerableKernel_reachingAVec3Element_isRefused_generalKeepsIt() {
        String message = refusal("#pragma kernel K map", "void K() { ODD_WRITE(ODD(CG_ELEMENT)); }");
        assertTrue(message, message.contains("a tier without compute cannot hold"));
        parse("#pragma kernel K general", "void K() { ODD_STORE(CG_ELEMENT, ODD(CG_ELEMENT)); }");
    }

    @Test
    public void mapKernel_namingAWorkGroup_isRefused() {
        String message = refusal("#pragma kernel K map", "void K() { if (CG_LOCAL_INDEX == 0) OUT_WRITE(OUT(0)); }");
        assertTrue(message, message.contains("names a work group"));
    }

    @Test
    public void sharedMemory_inANonGeneralKernel_isRefused() {
        String message = refusal("#pragma kernel K gather",
                "shared float tile[64];\nvoid K() { tile[0] = 1.0; OUT_WRITE(OUT(0)); }");
        assertTrue(message, message.contains("shared tile"));
    }

    @Test
    public void unreachedHelper_isNotHeldAgainstAKernel() {
        CgComputeSource s = parse("#pragma kernel K map\n#pragma kernel G 64 general",
                "shared float tile[CG_GROUP_SIZE * 2];\n"
                + "void sync() { barrier(); tile[0] = 0.0; }\n"
                + "void K() { OUT_WRITE(OUT(CG_ELEMENT)); }\n"
                + "void G() { sync(); }\n");
        assertFalse(s.kernel("K").functions().contains("sync"));
        assertEquals(64 * 2 * 4, s.kernel("G").sharedBytes());
    }

    // ── Declarations ──────────────────────────────────────────────────────────

    @Test
    public void kernelWithoutItsFunction_isRefused() {
        assertTrue(refusal("#pragma kernel K map", "void Other() {}").contains("defines no 'void K()'"));
    }

    @Test
    public void kernelWithParameters_isRefused() {
        assertTrue(refusal("#pragma kernel K map", "void K(int i) {}").contains("takes nothing"));
    }

    @Test
    public void kernelWithoutAShape_isRefused() {
        assertTrue(refusal("#pragma kernel K 64", "void K() {}").contains("needs a shape"));
    }

    @Test
    public void computeOnly_namesAKernel_andTakesNoFallback() {
        assertTrue(parse("#pragma kernel A general\n#pragma compute_only A", "void A() {}").kernel("A").computeOnly());
        assertTrue(refusal("#pragma kernel A general\n#pragma compute_only B", "void A() {}").contains("names no kernel 'B'"));
        assertTrue(refusal("#pragma kernel A general\n#pragma kernel L scatter\n#pragma fallback A L\n#pragma compute_only A",
                "void A() {}\nvoid L() {}").contains("compute_only and names a #pragma fallback"));
    }

    @Test
    public void fallbackToAGeneralKernel_isRefused() {
        assertTrue(refusal("#pragma kernel A general\n#pragma kernel B general\n#pragma fallback A B",
                "void A() {}\nvoid B() {}").contains("a fallback is lowerable"));
    }

    @Test
    public void versionAndUniforms_areRefused() {
        assertTrue(refusal("#version 430\n#pragma kernel K map", "void K() {}").contains("#version"));
        assertTrue(refusal("#pragma kernel K map", "uniform float x;\nvoid K() {}").contains("Properties"));
    }

    @Test
    public void appendKernel_withoutAnAppendBuffer_isRefused() {
        assertTrue(refusal("#pragma kernel K append", "void K() {}").contains("no append buffer"));
    }

    @Test
    public void structDeclaredAfterBuffers_isRefused() {
        try {
            CgComputeParser.parse("#pragma kernel K general\nBuffers {\n    B (\"B\", Late, readwrite)\n}\n"
                    + "struct Late { vec4 a; };\nvoid K() {}", "test:shaders/late.compute");
            fail();
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("declared after Buffers"));
            assertEquals(3, e.line());
        }
    }

    @Test
    public void refusal_isPlacedAtItsLine() {
        try {
            parse("#pragma kernel K map", "void K() {\n    OUT_STORE(0, OUT(1));\n}");
            fail();
        } catch (CgShaderParseException e) {
            assertEquals(10, e.line());
        }
    }
}
