package com.crystalgraphics.compute;

import com.crystalgraphics.compute.CgKernelForm.How;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import org.junit.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class CgKernelFormTest {

    private static final String TEXT = String.join("\n",
            "#pragma kernel Step map",
            "#pragma kernel Reduce general",
            "#pragma kernel ReduceLowered scatter",
            "#pragma kernel Alone general",
            "#pragma kernel Only general",
            "#pragma kernel Bits map",
            "#pragma kernel Gathers map",
            "#pragma fallback Reduce ReduceLowered",
            "#pragma compute_only Only",
            "#pragma cg_feature WIDE",
            "Buffers {",
            "    VALS (\"Vals\", float, readwrite)",
            "    SUMS (\"Sums\", uint,  counter)",
            "}",
            "Properties {",
            "    _Tex (\"Tex\", sampler2D) = \"white\"",
            "}",
            "shared uint partial[64];",
            "void Step() { VALS_WRITE(VALS(CG_ELEMENT) * 2.0); }",
            "void Reduce() { partial[CG_LOCAL_INDEX] = 1u; barrier(); if (CG_LOCAL_INDEX == 0) SUMS_ADD(0, partial[0]); }",
            "void ReduceLowered() { SUMS_INC(0); }",
            "void Alone() { partial[CG_LOCAL_INDEX] = 0u; barrier(); }",
            "void Only() { partial[CG_LOCAL_INDEX] = 0u; barrier(); }",
            "void Bits() { VALS_WRITE(float(bitCount(uint(CG_ELEMENT))) + unpackHalf2x16(7u).x); }",
            "void Gathers() { VALS_WRITE(float(textureGather(_Tex, vec2(0.5)).x)); }",
            "");
    private static final CgComputeSource SOURCE = CgComputeParser.parse(TEXT, "test:shaders/form.compute");

    private static CgKernelForm form(String kernel, ComputeTier tier, String... bodies) {
        Predicate<String> hasBody = Set.of(bodies)::contains;
        return CgKernelForm.choose(SOURCE, SOURCE.kernel(kernel), tier, hasBody);
    }

    private static String refusal(String kernel, ComputeTier tier, String... bodies) {
        try {
            form(kernel, tier, bodies);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        fail(kernel + " found a form at " + tier);
        return null;
    }

    @Test
    public void aKernelAndEachKeywordSet_isOneInstance_soItsChecksAndProgramAreKept() {
        CgCompute file = CgCompute.fromSource("test:form", TEXT);
        assertSame(file.kernel("Step"), file.kernel("Step"));
        assertSame(file.kernel("Step").withKeywords("WIDE"), file.kernel("Step").withKeywords("WIDE"));
        assertNotSame(file.kernel("Step"), file.kernel("Step").withKeywords("WIDE"));
        assertSame(file.kernel("Step"), file.kernel("Step").withKeywords());
    }

    @Test
    public void compute_runsEveryKernelItself() {
        for (ComputeTier tier : new ComputeTier[]{ComputeTier.V, ComputeTier.G43}) {
            CgKernelForm f = form("Alone", tier);
            assertEquals(How.COMPUTE, f.how());
            assertEquals("Alone", f.runs().name());
        }
    }

    @Test
    public void belowCompute_aShapeLowers_aGeneralKernelTakesItsLowerableFallback() {
        assertEquals(How.LOWERED, form("Step", ComputeTier.G33).how());
        CgKernelForm f = form("Reduce", ComputeTier.G40);
        assertEquals(How.LOWERED, f.how());
        assertEquals("ReduceLowered", f.runs().name());
    }

    @Test
    public void belowCompute_aJavaBodyIsNeverAPlayersForm() {
        for (ComputeTier tier : new ComputeTier[]{ComputeTier.G40, ComputeTier.G33}) {
            String why = refusal("Alone", tier, "Alone");
            assertTrue(why, why.contains("compute_only") && !why.contains("kernel.cpu"));
        }
    }

    @Test
    public void belowCompute_withNoFallback_throwsNamingTheConstruct() {
        String why = refusal("Alone", ComputeTier.G40);
        assertTrue(why, why.contains("Alone") && why.contains("G40") && why.contains("shared memory (partial)"));
        assertTrue(why, why.contains("#pragma fallback") && why.contains("compute_only"));
    }

    @Test
    public void cpu_runsTheBody_elseTheFallbacks_elseThrows() {
        assertEquals(How.CPU, form("Step", ComputeTier.CPU, "Step").how());
        CgKernelForm f = form("Reduce", ComputeTier.CPU, "ReduceLowered");
        assertEquals(How.CPU, f.how());
        assertEquals("ReduceLowered", f.runs().name());
        String why = refusal("Step", ComputeTier.CPU);
        assertTrue(why, why.contains("Step") && why.contains("kernel.cpu"));
    }

    private static String checkRefusal(String kernel) {
        try {
            CgKernelForm.check(SOURCE, SOURCE.kernel(kernel));
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return null;
    }

    @Test
    public void check_asksEveryTier_onAnyMachine() {
        String why = checkRefusal("Alone");
        assertNotNull("a general kernel with no fallback and no body passed", why);
        assertTrue(why, why.contains("tier G40") && why.contains("shared memory (partial)") && why.contains("compute_only"));
        assertNull(checkRefusal("Reduce"));
    }

    @Test
    public void computeOnly_isAskedOnlyOfCompute_andRefusedBelowIt() {
        assertNull(checkRefusal("Only"));
        assertEquals(How.COMPUTE, form("Only", ComputeTier.G43).how());
        for (ComputeTier tier : new ComputeTier[]{ComputeTier.G40, ComputeTier.CPU}) {
            String why = refusal("Only", tier);
            assertTrue(why, why.contains("compute_only") && why.contains("kernel.runs()"));
        }
        String why = refusal("Only", ComputeTier.G33, "Only");
        assertTrue(why, why.contains("compute_only"));
        assertEquals(How.CPU, form("Only", ComputeTier.CPU, "Only").how());
    }

    @Test
    public void builtins_polyfilledWhereExact_refusedNamingTheTierAndItsGlsl() {
        assertEquals(Set.of("bitCount", "unpackHalf2x16"), SOURCE.kernel("Bits").builtins());
        assertNull(checkRefusal("Bits"));
        String why = checkRefusal("Gathers");
        assertNotNull("textureGather passed below 4.00", why);
        assertTrue(why, why.contains("textureGather") && why.contains("4.00") && why.contains("tier G33") && why.contains("3.30"));
    }
}
