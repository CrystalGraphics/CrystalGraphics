package io.github.somehussar.crystalgraphics.render.pipeline;

import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.render.CgRenderPipeline;
import io.github.somehussar.crystalgraphics.api.material.CgRenderPassVariant;
import io.github.somehussar.crystalgraphics.api.render.CgRenderCommand;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.api.state.CgColorMask;
import io.github.somehussar.crystalgraphics.api.state.CgDepthState;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;

/**
 * Depth prepass renderer: fills the depth buffer from the camera's perspective
 * before the opaque forward pass. Eliminates overdraw by allowing the forward
 * pass to use {@code GL_EQUAL} depth test.
 *
 * <p>Returns {@code true} if any geometry was drawn (→ forward pass uses {@code GL_EQUAL}),
 * or {@code false} if nothing was drawn (→ forward pass uses {@code GL_LEQUAL}).</p>
 *
 * <h3>Auto-instancing</h3>
 * <p>Consecutive commands with the same {@link CgMaterial}
 * and {@link CgMesh} identity are merged into
 * a single {@code drawInstanced(N)} call — same {@code canMerge()} criterion as
 * {@link CgForwardRenderer}.</p>
 *
 * <h3>Two-path architecture</h3>
 * <ul>
 *   <li><strong>Primary path</strong>: {@code bindForPass(DEPTH)} for all geometry with a
 *       compiled depth variant (auto-generated or explicit). Preserves vertex animation and
 *       handles both solid and alpha-clip via the auto-gen case selection.</li>
 *   <li><strong>Fallback path — alpha-test only</strong>: {@code bindForPass(FORWARD)} for
 *       alpha-test materials that have no compiled depth variant. Solid geometry without a
 *       depth variant is skipped entirely (batch advances without drawing).</li>
 * </ul>
 *
 * <p>Source: render-pipeline-curation.md Section 13.</p>
 */
public final class CgDepthPrepassRenderer {

    /**
     * Executes the depth prepass over the supplied sorted opaque command list.
     *
     * <p>GL state contract: sets {@code ColorMask=false}, {@code DepthWrite=true},
     * {@code DepthTest=LEQUAL}, {@code Blend=false} for the duration of the pass.
     * Restores {@code ColorMask=true} before returning (the outer {@code CgGlScope}
     * will also restore all state fully on frame-end).</p>
     *
     * <p>GL state overrides are applied <em>after</em> {@code bindForPass()} — the
     * material's authored {@code RenderState} is applied first, then overridden for
     * depth-only output. This matches the ordering rule from Section 13.</p>
     *
     * @param sorted   pre-sorted opaque command array (front-to-back)
     * @param count    number of valid entries in {@code sorted}
     * @param pipeline the active material pipeline (provides the object buffer)
     * @return {@code true} if geometry was drawn; {@code false} if nothing rendered
     *         (caller should use {@code GL_LEQUAL} for the forward pass)
     */
    public boolean execute(CgRenderCommand[] sorted, int count, CgRenderPipeline pipeline) {
        if (count == 0) return false;

        // Global depth prepass state — overrides material state where needed
        CgDepthState.TEST_WRITE.apply();
        CgBlendState.DISABLED.apply();

        CgShaderBuffer objBuf = pipeline.objectBuffer();
        boolean drew = false;

        int i = 0;
        while (i < count) {
            CgRenderCommand base = sorted[i];
            boolean isAlphaTest = (base.passFlags & CgRenderCommand.FLAG_ALPHA_TEST) != 0;

            int j = i + 1;
            while (j < count && CgForwardRenderer.canMerge(base, sorted[j])) j++;
            int runLen = j - i;

            if (!base.material.hasCompiledDepthPass() && !isAlphaTest) {
                i = j;
                continue;
            }

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

            if (base.preDrawHook != null) base.preDrawHook.apply(sorted, i, runLen);

            if (base.material.hasCompiledDepthPass()) {
                base.material.bindForPass(CgRenderPassVariant.DEPTH);
            } else {
                base.material.bindForPass(CgRenderPassVariant.FORWARD);
            }
            CgColorMask.NONE.apply();
            base.mesh.drawInstanced(runLen);
            base.material.unbind();

            drew = true;
            i = j;
        }

        // Restore colorMask — outer CgGlScope will also restore fully on frame-end
        CgColorMask.ALL.apply();
        return drew;
    }
    
}
