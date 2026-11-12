package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * The base pass and its depth prepass ({@code EMeshPass::BasePass}, {@code DepthPass}): each draw of the firing's phase
 * through its material's passes, its glow folded into its colour's draw where it merges, else drawn after it at its key.
 *
 * <pre>{@code
 * basePass.recordPass(stage, recording, constants, OPAQUE_STATE, true, false, view, false);    // the prepass
 * basePass.recordPass(stage, recording, constants, TRANSPARENT_STATE, false, false, view, scene);
 * }</pre>
 */
final class CgBasePass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    CgBasePass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * The opaque, prepass or transparent pass; with {@code after}, the transparent draws drawn after distortion. With
     * {@code sceneGlows}, each draw's Emissive pass adds its glow into the HDR scene: folded into its colour's draw
     * where it merges ({@link CgPipeline#sceneFold}), else as a draw after it at its key.
     */
    void recordPass(CgStageFrame stage, CgRecording recording, CgPassConstants constants, CgRenderState state,
                    boolean depthOnlyPass, boolean after, CgCamera view, boolean sceneGlows) {
        CgRasterPass pass = recording.raster("world.base").color(stage.target(), CgLoad.load()).depth(stage.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).state(state)
                .readsDepth(stage.depth())
                .readsColor(stage.target())
                .texture(CgBindingModel.LIGHTMAP, stage.host().textures().lightmapTexture());
        if (!depthOnlyPass) world.mediated(pass);
        boolean normals = world.normalsInto != null && state == CgWorldRenderer.OPAQUE_STATE && !depthOnlyPass;
        if (normals) pass.colorIfTaken(world.normalsInto, CgLoad.load());
        // The graded layer beside the scene: its draws write it too, as their second output (T12 L3)
        CgGradeLayerPass layers = world.gradeLayerPass;
        boolean layered = layers.outputSlot >= 0 && state == CgWorldRenderer.TRANSPARENT_STATE && !depthOnlyPass;
        if (layered) pass.colorIfTaken(layers.gradeTargets[layers.outputSlot], CgLoad.load());
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] == CgWorldDraws.SKIP || world.halfSize(i)) continue;
            if (depthOnlyPass ? draws.phase[i] != CgWorldDraws.FORWARD_AND_PREPASS && !world.prepassed(i)
                    : draws.phase[i] == CgWorldDraws.APART || (draws.phase[i] == CgWorldDraws.AFTER) != after) continue;
            boolean glows = sceneGlows && draws.emissions[i] > 0f && draws.queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
            for (CgMaterial link = draws.materials[i]; link != null; link = depthOnlyPass ? null : link.getNextPass()) {
                CgPipeline pipeline = depthOnlyPass ? commands.depthPipeline(link) : link.pipeline(CgInstanceKind.OBJECT);
                boolean intoLayer = layered && layers.viaOutput[i];
                if (glows && link.hasEmissivePass()) {
                    CgPipeline folded = pipeline != null && world.mergeEmission ? pipeline.as(CgPipelineForm.FOLD) : null;
                    if (folded != null) {
                        commands.passDraw(chunks, intoLayer ? folded.as(CgPipelineForm.LAYER) : folded, link, recording, i);
                        continue;
                    }
                    // Its glow follows its colour: SORTED is stable, so a draw of the same key after it covers both.
                    if (pipeline != null && !pipeline.state().writesNothing()) commands.passDraw(chunks, pipeline, link, recording, i);
                    CgPipeline glow = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                    if (glow != null) commands.passDraw(chunks, glow, link, recording, i);
                    continue;
                }
                if (pipeline == null || (!depthOnlyPass && pipeline.state().writesNothing())) continue;
                if (normals) {
                    CgPipeline normalled = pipeline.as(CgPipelineForm.NORMALS);
                    if (normalled != null) pipeline = normalled;
                }
                if (intoLayer) pipeline = pipeline.as(CgPipelineForm.LAYER);
                commands.passDraw(chunks, pipeline, link, recording, i);
            }
        }
        if (state == CgWorldRenderer.TRANSPARENT_STATE && !depthOnlyPass && !after) {
            world.distortion.addApplies(chunks, recording);
            if (world.volumeOut != null) world.volumePass.addComposite(chunks, recording);
        }
        pass.add(chunks.end());
        pass.end();
    }
}
