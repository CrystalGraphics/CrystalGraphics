package com.crystalgraphics.compute;

import com.crystalgraphics.compute.emit.CgGlslBuiltins;
import com.crystalgraphics.compute.lower.CgLowering;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * How a context runs a kernel (gpu-compute §6.5): as compute, lowered below compute, or by its Java body on the CPU;
 * and which kernel that is, itself or the {@code #pragma fallback} it names. A frame never mixes forms for one kernel.
 *
 * <pre>{@code
 * CgKernelForm form = kernel.form();                   // render thread: the context's tier
 * if (form.how() == CgKernelForm.How.LOWERED) ...
 * CgKernelForm atG40 = CgKernelForm.choose(source, decl, ComputeTier.G40, name -> false);   // GL-free
 * CgKernelForm.check(source, decl, name -> false);     // every tier a player may have, or throws naming one
 * }</pre>
 *
 * <ul>
 *   <li>On V and G43 every kernel runs as compute.</li>
 *   <li>On G40 and G33: the kernel lowered; else its fallback lowered; else its CPU body; else its fallback's. A
 *       {@code compute_only} kernel is never lowered.</li>
 *   <li>On CPU: its CPU body, else its fallback's.</li>
 *   <li>A kernel that can run nowhere throws when its form is chosen, which is before any dispatch of it runs,
 *       naming what stops it.</li>
 * </ul>
 *
 * @param runs the kernel that runs: this one, or its fallback
 */
public record CgKernelForm(How how, ComputeTier tier, CgKernelDecl runs) {

    public enum How { COMPUTE, LOWERED, CPU }

    /** The tiers a player's context may be at, which {@link #check} asks: CPU is only ever forced. */
    private static final ComputeTier[] PLAYER_TIERS = {ComputeTier.G43, ComputeTier.G40, ComputeTier.G33};

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
                refusal = kernel.computeOnly() ? "it is declared compute_only" : CgLowering.refusal(source, kernel);
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
        String named = "[" + source.path() + "] kernel " + kernel.name() + " cannot run at tier " + tier + describe(tier);
        if (kernel.computeOnly()) {
            throw new IllegalStateException(named + ", which has no compute, and it is declared compute_only: ask "
                    + "kernel.runs() before dispatching it, or give it a Java body with kernel.cpu(...)");
        }
        if (tier == ComputeTier.CPU) {
            throw new IllegalStateException(named + ": the CPU tier runs a kernel's Java body, and it has none: "
                    + "give it one with kernel.cpu(...)");
        }
        throw new IllegalStateException(named + ", which has no compute: " + refusal + ". Give it a lowerable "
                + "#pragma fallback or a Java body with kernel.cpu(...), or declare it '#pragma compute_only "
                + kernel.name() + "' and ask kernel.runs() before dispatching it");
    }

    /**
     * Asks every tier a player's context may be at, not only this one's (decision 13): the form each takes, and that
     * each compiles the builtins the kernel it runs names, polyfilled where its GLSL lacks them. GL-free, so the
     * author's machine finds what a player's would. A {@code compute_only} kernel is asked only of compute.
     *
     * @throws IllegalStateException naming the kernel, the tier and what stops it
     */
    public static void check(CgComputeSource source, CgKernelDecl kernel, Predicate<String> hasCpuBody) {
        for (ComputeTier tier : PLAYER_TIERS) {
            boolean lowered = tier == ComputeTier.G40 || tier == ComputeTier.G33;
            if (lowered && kernel.computeOnly()) continue;
            CgKernelForm form = choose(source, kernel, tier, hasCpuBody);
            if (form.how() == How.CPU) continue;
            int glsl = lowestGlsl(tier);
            String why = CgGlslBuiltins.refusal(form.runs().builtins(), glsl);
            if (why == null) continue;
            String which = form.runs() == kernel ? "kernel " + kernel.name() : "kernel " + kernel.name() + "'s fallback "
                    + form.runs().name();
            String hint = lowered && !kernel.computeOnly()
                    ? ". Avoid it, or declare '#pragma compute_only " + kernel.name() + "' and ask kernel.runs()" : ". Avoid it";
            throw new IllegalStateException("[" + source.path() + "] " + which + " " + why + ": tier " + tier
                    + describe(tier) + " compiles GLSL " + CgGlslBuiltins.versionName(glsl) + hint);
        }
    }

    /**
     * The lowest GLSL a context at {@code tier} compiles a kernel at: 4.20 for compute ({@code ARB_compute_shader}
     * needs GL 4.2), 4.00 and 3.30 for the lowered tiers.
     */
    public static int lowestGlsl(ComputeTier tier) {
        return switch (tier) {
            case V, G43 -> 420;
            case G40 -> 400;
            default -> 330;
        };
    }

    private static String describe(@Nullable ComputeTier tier) {
        if (tier == null) return "";
        return switch (tier) {
            case G43 -> " (GL 4.2 with compute, 4.3)";
            case G40 -> " (GL 4.0 to 4.2, macOS's 4.1)";
            case G33 -> " (GL 3.3)";
            default -> "";
        };
    }
}
