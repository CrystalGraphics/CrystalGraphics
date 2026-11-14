package com.crystalgraphics.render.scene.pass;

import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;

import javax.annotation.Nullable;

/**
 * What a view's pass makes of each command its pass type built: the same pipeline, another form of it, or none. The
 * scene renderer's per-view choices that no processor can see, as Unreal's base pass picks a permutation from the view's
 * settings: our normals written beside the scene, a glow folded into its colour, a graded draw's layer, a row of one
 * distortion slot. Cached commands stay unformed; the form applies as a list is made.
 *
 * <pre>{@code
 * CgCommandForm normals = (rows, b, p) -> { CgPipeline n = p.as(CgPipelineForm.NORMALS); return n != null ? n : p; };
 * pass.add(passes.commands(CgEnginePasses.BASE, rec, normals));
 *
 * CgCommandForm early = (rows, b, p) -> rows.material(b).renderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD ? p : null;
 * }</pre>
 *
 * <ul>
 *   <li>A list is kept per pass type and form for its recording: make each form once (a field) and keep it, or every
 *       ask builds a new list.</li>
 *   <li>Answering null leaves the command out; answering a pipeline of another program is allowed (a layer's
 *       state).</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCommandForm {

    /** The pipeline row {@code b}'s command draws through in this pass, or null to leave it out. */
    @Nullable
    CgPipeline form(CgMeshBatches rows, int b, CgPipeline pipeline);
}
