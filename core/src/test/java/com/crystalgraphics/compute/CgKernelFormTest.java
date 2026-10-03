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

    private static final CgComputeSource SOURCE = CgComputeParser.parse(String.join("\n",
            "#pragma kernel Step map",
            "#pragma kernel Reduce general",
            "#pragma kernel ReduceLowered scatter",
            "#pragma kernel Alone general",
            "#pragma fallback Reduce ReduceLowered",
            "Buffers {",
            "    VALS (\"Vals\", float, readwrite)",
            "    SUMS (\"Sums\", uint,  counter)",
            "}",
            "shared uint partial[64];",
            "void Step() { VALS_WRITE(VALS(CG_ELEMENT) * 2.0); }",
            "void Reduce() { partial[CG_LOCAL_INDEX] = 1u; barrier(); if (CG_LOCAL_INDEX == 0) SUMS_ADD(0, partial[0]); }",
            "void ReduceLowered() { SUMS_INC(0); }",
            "void Alone() { partial[CG_LOCAL_INDEX] = 0u; barrier(); }",
            ""), "test:shaders/form.compute");

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
    public void belowCompute_aKernelThatCannotLower_runsItsCpuBody() {
        CgKernelForm f = form("Alone", ComputeTier.G40, "Alone");
        assertEquals(How.CPU, f.how());
        assertEquals("Alone", f.runs().name());
    }

    @Test
    public void belowCompute_withNoFallbackAndNoBody_throwsNamingTheConstruct() {
        String why = refusal("Alone", ComputeTier.G40);
        assertTrue(why, why.contains("Alone") && why.contains("G40") && why.contains("shared memory (partial)"));
        assertTrue(why, why.contains("kernel.cpu"));
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
}
