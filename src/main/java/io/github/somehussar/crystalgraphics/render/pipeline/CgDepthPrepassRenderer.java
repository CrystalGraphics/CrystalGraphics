package io.github.somehussar.crystalgraphics.render.pipeline;

import io.github.somehussar.crystalgraphics.api.render.CgRenderPipeline;
import io.github.somehussar.crystalgraphics.api.material.CgRenderPassVariant;
import io.github.somehussar.crystalgraphics.api.render.CgRenderCommand;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.api.state.CgColorMask;
import io.github.somehussar.crystalgraphics.api.state.CgDepthState;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import org.lwjgl.opengl.GL11;

/**
 * Depth prepass renderer: fills the depth buffer from the camera's perspective
 * before the opaque forward pass. Eliminates overdraw by allowing the forward
 * pass to use {@code GL_EQUAL} depth test.
 *
 * <p>Returns {@code true} if any geometry was drawn (→ forward pass uses {@code GL_EQUAL}),
 * or {@code false} if nothing was drawn (→ forward pass uses {@code GL_LEQUAL}).</p>
 *
 * <h3>Two-path architecture</h3>
 * <ul>
 *   <li><strong>Path 1 — Solid opaque (GEOMETRY, not ALPHA_TEST)</strong>: shared engine depth
 *       shader — minimal GLSL that only transforms position and writes depth.</li>
 *   <li><strong>Path 2 — Alpha-test (ALPHA_TEST)</strong>: {@code bindForVariant(FORWARD)} to
 *       execute the material's authored fragment discard logic, then {@code colorMask=false}.</li>
 * </ul>
 *
 * <p>Source: render-pipeline-curation.md Section 13.</p>
 */
public final class CgDepthPrepassRenderer {

    /** Shared engine depth shader — minimal position-only, no fragment output. */
    private final CgShader engineDepthShader;

    /**
     * Loads the engine depth shader from the crystalgraphics resource domain.
     * Silently degrades to stub mode ({@code execute()} returns {@code false}) if the
     * engine shader assets are not present yet.
     */
    public CgDepthPrepassRenderer() {
        CgShader loaded = null;
        try {
            loaded = CgShaderFactory.load(
                "crystalgraphics:shaders/engine/depth_prepass.vert",
                "crystalgraphics:shaders/engine/depth_prepass.frag");
        } catch (Exception e) {
            // Engine shader not yet present or failed to compile — stub mode returns false
        }
        this.engineDepthShader = loaded;
    }

    /**
     * Executes the depth prepass over the supplied sorted opaque command list.
     *
     * <p>GL state contract: sets {@code ColorMask=false}, {@code DepthWrite=true},
     * {@code DepthTest=LEQUAL}, {@code Blend=false} for the duration of the pass.
     * Restores {@code ColorMask=true} before returning (the outer {@code CgGlScope}
     * will also restore all state fully on frame-end).</p>
     *
     * <p>GL state overrides are applied <em>after</em> {@code bindForVariant()} — the
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
        if (count == 0 || engineDepthShader == null) return false;

        // Global depth prepass state — overrides material state where needed
        CgDepthState.TEST_WRITE.apply();
        CgBlendState.DISABLED.apply();

        CgShaderBuffer objBuf = pipeline.objectBuffer();
        boolean drew = false;

        for (int i = 0; i < count; i++) {
            CgRenderCommand cmd = sorted[i];

            // Write one transform record for this command
            CgBufferWriter w = objBuf.beginWrite(1);
            w.beginRecord()
             .mat4("modelMatrix",  cmd.modelMatrix)
             .mat4("normalMatrix", cmd.normalMatrix);
            objBuf.endRecord();
            objBuf.endWrite();

            boolean isAlphaTest = (cmd.passFlags & CgRenderCommand.FLAG_ALPHA_TEST) != 0;
            if (isAlphaTest) {
                // Path 2: material's FORWARD pass fragment body contains alpha-clip discard logic.
                // bindForVariant() applies the material's authored RenderState internally.
                // Do NOT call rs.apply() before — that would double-manage state.
                cmd.material.bindForVariant(CgRenderPassVariant.FORWARD);
            } else {
                // Path 1: shared engine depth shader — position-only, no color output
                engineDepthShader.bind();
            }

            // Override colorMask AFTER bind (Section 13 ordering rule)
            CgColorMask.NONE.apply();

            cmd.mesh.drawInstanced(1);

            if (isAlphaTest) {
                cmd.material.unbind();
            } else {
                engineDepthShader.unbind();
            }

            drew = true;
        }

        // Restore colorMask — outer CgGlScope will also restore fully on frame-end
        CgColorMask.ALL.apply();
        return drew;
    }
}
