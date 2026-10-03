package com.crystalgraphics.compute;

import com.crystalgraphics.compute.lower.CgLowering;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;

import java.util.function.Predicate;

/**
 * How a context runs a kernel (gpu-compute §6.5): as compute, lowered below compute, or by its Java body on the CPU;
 * and which kernel that is, itself or the {@code #pragma fallback} it names. A frame never mixes forms for one kernel.
 *
 * <pre>{@code
 * CgKernelForm form = kernel.form();                   // render thread: the context's tier
 * if (form.how() == CgKernelForm.How.LOWERED) ...
 * CgKernelForm atG40 = CgKernelForm.choose(source, decl, ComputeTier.G40, name -> false);   // GL-free
 * }</pre>
 *
 * <ul>
 *   <li>On V and G43 every kernel runs as compute.</li>
 *   <li>On G40 and G33: the kernel lowered; else its fallback lowered; else its CPU body; else its fallback's.</li>
 *   <li>On CPU: its CPU body, else its fallback's.</li>
 *   <li>A kernel that can run nowhere throws when its form is chosen, which is before any dispatch of it runs,
 *       naming what stops it.</li>
 * </ul>
 *
 * @param runs the kernel that runs: this one, or its fallback
 */
public record CgKernelForm(How how, ComputeTier tier, CgKernelDecl runs) {

    public enum How { COMPUTE, LOWERED, CPU }

    /**
     * The form {@code kernel} of {@code source} takes at {@code tier}, given which kernels have a CPU body.
     *
     * @throws IllegalStateException naming the kernel, the tier and what stops it, where it can run nowhere
     */
    public static CgKernelForm choose(CgComputeSource source, CgKernelDecl kernel, ComputeTier tier,
                                      Predicate<String> hasCpuBody) {
        CgKernelDecl fallback = kernel.fallback() == null ? null : source.kernel(kernel.fallback());
        String refusal = null;
        switch (tier) {
            case V, G43:
                return new CgKernelForm(How.COMPUTE, tier, kernel);
            case G40, G33:
                refusal = CgLowering.refusal(source, kernel);
                if (refusal == null) return new CgKernelForm(How.LOWERED, tier, kernel);
                if (fallback != null && CgLowering.refusal(source, fallback) == null) {
                    return new CgKernelForm(How.LOWERED, tier, fallback);
                }
                break;
            default:
                break;
        }
        if (hasCpuBody.test(kernel.name())) return new CgKernelForm(How.CPU, tier, kernel);
        if (fallback != null && hasCpuBody.test(fallback.name())) return new CgKernelForm(How.CPU, tier, fallback);
        String named = "[" + source.path() + "] kernel " + kernel.name() + " cannot run at tier " + tier;
        if (tier == ComputeTier.CPU) {
            throw new IllegalStateException(named + ": the CPU tier runs a kernel's Java body, and it has none: "
                    + "give it one with kernel.cpu(...)");
        }
        throw new IllegalStateException(named + ", which has no compute: " + refusal + ". Give it a lowerable "
                + "#pragma fallback, or a Java body with kernel.cpu(...)");
    }
}
