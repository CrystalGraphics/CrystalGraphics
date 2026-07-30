package com.crystalgraphics.render.pipeline;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Back-to-front transparent pass renderer.
 *
 * <p>Processes the {@code TRANSPARENT} queue slot commands (already sorted back-to-front
 * by {@code CgRenderCommandQueue.sort()}). Each command is drawn individually with
 * {@code drawDirect()} to preserve per-object depth ordering required for correct blending.
 * No auto-instancing is applied — transparent objects must retain their sort order.</p>
 *
 * <h3>GL state owned by this renderer</h3>
 * <ul>
 *   <li>{@code DepthMask = false} (ZWrite OFF — don't pollute the depth buffer)</li>
 *   <li>{@code DepthTest = LEQUAL}</li>
 *   <li>{@code Blend = ON}, {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} (default blend mode;
 *       individual material {@code RenderState} may override blend equation)</li>
 * </ul>
 *
 * <p>Render state per-material is applied and restored by {@code CgMaterial.doBind()}
 * (called inside {@code drawChain(FORWARD, drawCall)}). Do NOT call {@code rs.apply()} / {@code rs.clear()}
 * externally — that would double-manage state and corrupt the save/restore ordering.</p>
 *
 * <p>Source: render-pipeline-curation.md Section 14 (CgTransparentRenderer spec).</p>
 */
public final class CgTransparentRenderer {

    /**
     * Executes the transparent pass for the given back-to-front sorted command list.
     *
     * @param sorted   pre-sorted transparent command array (back-to-front)
     * @param count    number of valid entries in {@code sorted}
     * @param pipeline the active material pipeline (provides the object buffer)
     */
    public void execute(CgRenderCommand[] sorted, int count, CgRenderPipeline pipeline) {
        if (count == 0) return;

        // Transparent pass ambient state. Re-asserted per command below, not just here — see there.
        CgDepthState.TEST_ONLY.apply(); // ZWrite OFF — preserve opaque depth
        CgBlendState.ALPHA.apply();

        CgShaderBuffer objBuf = pipeline.objectBuffer();

        for (int i = 0; i < count; i++) {
            CgRenderCommand cmd = sorted[i];

            // Write one transform record per command — no batching for transparent
            CgBufferWriter w = objBuf.beginWrite(1);
            w.beginRecord()
             .mat4("modelMatrix",  cmd.modelMatrix)
             .mat4("normalMatrix", cmd.normalMatrix)
             .vec4("custom0",      cmd.custom0)
             .vec4("custom1",      cmd.custom1)
             .vec4("custom2",      cmd.custom2)
             .vec4("custom3",      cmd.custom3);
            objBuf.endRecord();
            objBuf.endWrite();

            // Re-assert the pass ambient state before every material.
            //
            // CgMaterial.doBind() no longer saves and restores GL state around each bind (it cannot: bind
            // and unbind are not last-in-first-out, so a scope closed there throws). It applies only the
            // domains its render state DECLARES, and leaves the rest untouched.
            //
            // Without this, a material declaring "DepthWrite ON" (text.shader does) would leave ZWrite on
            // for the next material that declares no Depth block — silently writing depth in the
            // transparent pass, which the TEST_ONLY above exists specifically to prevent. Re-asserting
            // gives every material the same starting point the old per-bind restore used to guarantee.
            //
            // Nearly free: CgGlStateManager eliminates these as redundant whenever the previous material
            // did not disturb them, which is the common case.
            CgDepthState.TEST_ONLY.apply();
            CgBlendState.ALPHA.apply();

            // Do NOT call rs.apply() / rs.clear() externally here — doBind applies the render state.
            // TODO(instancing): additive-blend transparent objects are order-independent and could be
            // batched via canMerge() + drawInstanced(N) like the opaque pass. Deferred — requires a
            // blend-mode flag in CgRenderCommand.passFlags or a separate TRANSPARENT_ADDITIVE queue slot
            // so the sort knows not to depth-order these and the renderer knows they are batchable.
            cmd.material.drawChain(CgRenderPassVariant.FORWARD, () -> cmd.mesh.drawDirect());
        }

        // Restore depth write. Routed through the state manager rather than raw glDepthMask so the
        // shadow stays truthful — a raw write here desynced DEPTH and showed up as a stale-shadow report.
        CgDepthState.TEST_WRITE.apply();
    }
}
