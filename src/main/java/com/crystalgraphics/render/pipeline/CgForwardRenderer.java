package com.crystalgraphics.render.pipeline;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgPreDrawHook;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.mesh.CgMesh;

/**
 * Opaque auto-instanced forward renderer.
 *
 * <p>Processes the {@code GEOMETRY + ALPHA_TEST} queue slots (front-to-back sorted).
 * Consecutive commands with the same {@link CgMaterial}
 * and {@link CgMesh} identity are merged
 * into a single instanced draw call (Filament {@code instanceify()} equivalent).</p>
 *
 * <h3>GL state owned by this renderer</h3>
 * <ul>
 *   <li>Depth function: {@code GL_LEQUAL} (pipeline default). Each material's authored
 *       {@code DepthTest} block overrides this naturally via {@code renderState.apply()}
 *       inside {@code bindForPass}. Hardware early-Z eliminates depth-prepass overdraw
 *       without requiring a {@code GL_EQUAL} override.</li>
 *   <li>DepthMask = true</li>
 *   <li>Blend = OFF (opaque pass)</li>
 * </ul>
 *
 * <p>Per-material render state is applied and restored by {@code CgMaterial.doBind()} inside
 * {@code drawChain(CgRenderPassVariant, Runnable)}. Do NOT call {@code rs.apply()} /
 * {@code rs.clear()} externally — that would double-manage GL state.</p>
 *
 * <p>Source: render-pipeline-curation.md Section 14 (CgForwardRenderer spec) and
 * Section 6 (auto-instancing via {@code canMerge()}).</p>
 */
public final class CgForwardRenderer {

    /**
     * Executes the opaque forward pass.
     *
     * @param sorted   pre-sorted opaque command array (front-to-back)
     * @param count    number of valid entries in {@code sorted}
     * @param pipeline the active material pipeline (provides the object buffer)
     */
    public void execute(CgRenderCommand[] sorted, int count, CgRenderPipeline pipeline) {
        if (count == 0) return;

        CgDepthState.TEST_WRITE.apply();
        CgBlendState.DISABLED.apply();

        CgShaderBuffer objBuf = pipeline.objectBuffer();
        int i = 0;

        while (i < count) {
            CgRenderCommand base = sorted[i];

            // Auto-instancing: find run of consecutive commands with same material + mesh + hook
            // (Filament instanceify() equivalent — Section 6)
            int j = i + 1;
            while (j < count && canMerge(base, sorted[j])) j++;
            int runLen = j - i;

            // Write all [i..j) transforms to the object buffer in one session
            CgBufferWriter w = objBuf.beginWrite(runLen);
            for (int k = i; k < j; k++) {
                CgRenderCommand cmd = sorted[k];
                w.beginRecord()
                 .mat4("modelMatrix",  cmd.modelMatrix)
                 .mat4("normalMatrix", cmd.normalMatrix)
                 .vec4("custom0",      cmd.custom0)
                 .vec4("custom1",      cmd.custom1)
                 .vec4("custom2",      cmd.custom2)
                 .vec4("custom3",      cmd.custom3);
                objBuf.endRecord();
            }
            objBuf.endWrite();

            // Fire the hook once, before bindForPass, so it runs for the base material only.
            // drawChain traverses the nextPass chain — the hook must NOT re-fire for decorative
            // chain links (outline, glow, etc.) whose programs are different from the base material.
            final int start = i;
            final int instances = runLen;
            if (base.preDrawHook != null) base.preDrawHook.apply(sorted, start, instances);
            base.material.drawChain(CgRenderPassVariant.FORWARD, () -> base.mesh.drawInstanced(instances));

            i = j;
        }
    }

    /**
     * Merge criterion: same {@code CgMaterial} AND same {@code CgMesh} AND same
     * {@code CgPreDrawHook} by identity (reference equality, not {@code equals()}).
     * Matches Filament's {@code mi == b.mi && rph == b.rph} test in {@code instanceify()}.
     * Different hook references are never merged — see {@link CgPreDrawHook}.
     *
     * <p>Transparent commands are never passed here (they go to {@link CgTransparentRenderer}).</p>
     */
    public static boolean canMerge(CgRenderCommand a, CgRenderCommand b) {
        return a.material == b.material && a.mesh == b.mesh
            && a.preDrawHook == b.preDrawHook;  // reference equality — different hook = no merge
    }
}
