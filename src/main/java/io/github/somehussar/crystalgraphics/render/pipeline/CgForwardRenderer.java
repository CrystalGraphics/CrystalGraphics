package io.github.somehussar.crystalgraphics.render.pipeline;

import io.github.somehussar.crystalgraphics.api.render.CgRenderPipeline;
import io.github.somehussar.crystalgraphics.api.material.CgRenderPassVariant;
import io.github.somehussar.crystalgraphics.api.render.CgRenderCommand;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.lwjgl.opengl.GL11;

/**
 * Opaque auto-instanced forward renderer.
 *
 * <p>Processes the {@code GEOMETRY + ALPHA_TEST} queue slots (front-to-back sorted).
 * Consecutive commands with the same {@link io.github.somehussar.crystalgraphics.api.material.CgMaterial}
 * and {@link io.github.somehussar.crystalgraphics.gl.mesh.CgMesh} identity are merged
 * into a single instanced draw call (Filament {@code instanceify()} equivalent).</p>
 *
 * <h3>GL state owned by this renderer</h3>
 * <ul>
 *   <li>Depth function: {@code depthTestMode} (GL_EQUAL if depth prepass ran, GL_LEQUAL otherwise)</li>
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
     * @param sorted        pre-sorted opaque command array (front-to-back)
     * @param count         number of valid entries in {@code sorted}
     * @param depthTestMode {@code GL_EQUAL} if depth prepass ran; {@code GL_LEQUAL} otherwise
     * @param pipeline      the active material pipeline (provides the object buffer)
     */
    public void execute(CgRenderCommand[] sorted, int count,
                        int depthTestMode, CgRenderPipeline pipeline) {
        if (count == 0) return;

        GL11.glDepthFunc(depthTestMode);
        GL11.glDepthMask(true);
        CgBlendState.DISABLED.apply();

        CgShaderBuffer objBuf = pipeline.objectBuffer();
        int i = 0;

        while (i < count) {
            CgRenderCommand base = sorted[i];

            // Auto-instancing: find run of consecutive commands with same material + mesh
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

            // bind/draw/unbind via drawChain — CgMaterial.doBind() owns render state.
            // Do NOT call rs.apply() / rs.clear() externally here.
            final int instances = runLen;
            base.material.drawChain(CgRenderPassVariant.FORWARD,
                    () -> base.mesh.drawInstanced(instances));
            
            i = j;
        }
    }

    /**
     * Merge criterion: same {@code CgMaterial} AND same {@code CgMesh} by identity
     * (reference equality, not {@code equals()}). Matches Filament's
     * {@code mi == b.mi && rph == b.rph} test in {@code instanceify()}.
     *
     * <p>Transparent commands are never passed here (they go to {@link CgTransparentRenderer}).</p>
     */
    private static boolean canMerge(CgRenderCommand a, CgRenderCommand b) {
        return a.material == b.material && a.mesh == b.mesh;
    }
}
