package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import java.util.Arrays;
import java.util.IdentityHashMap;
import javax.annotation.Nullable;

/**
 * The layer passes: each graded draw drawn again into its look's layer, which its post look grades and composites
 * (CgPostSettings), and separate translucency drawn into a layer of its own after every other translucent draw,
 * unbent by their distortion (Unreal's {@code TranslucencyAfterDOF}).
 *
 * <pre>{@code
 * if (scene) gradeLayerPass.recordGradeLayers(stage, recording, view, constants);
 * if (apartDrawn > 0) gradeLayerPass.recordSeparate(stage, recording, view, constants);
 * }</pre>
 */
final class CgGradeLayerPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final CgFrameBufferFormat GRADE_FORMAT = CgFrameBufferFormat.builder("cg_grade_layer")
            .color(0, CgTextureType.RGBA16F).build();

    final CgGraphTexture[] gradeApart = new CgGraphTexture[CgGradeLayers.MAX];

    boolean gradesWarned, gradeDepthWarned;

    final CgGradeLayers gradeLayers = new CgGradeLayers();

    final boolean[] gradeSeen = new boolean[CgGradeLayers.MAX];

    final int[] gradeSizes = new int[CgGradeLayers.MAX * 2];

    /** Each slot's look, kept while it draws so its baked table stays baked; and whether it drew this firing. */
    final CgPostSettings[] gradeSlots = new CgPostSettings[CgGradeLayers.MAX];

    final CgGraphTexture[] gradeTargets = new CgGraphTexture[CgGradeLayers.MAX];

    /** Per draw this firing, whether it goes into its look's layer; grown with the draws. */
    boolean[] inLayer = new boolean[64];

    int separateWidth, separateHeight;

    CgGraphTexture separateTarget;

    /** Each pipeline's layer form; itself where it has none (a multiply), so the answer is asked once. */
    final IdentityHashMap<CgPipeline, CgPipeline> layerForms = new IdentityHashMap<>();

    boolean separateMultiplyWarned;

    CgGradeLayerPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * Each look's draws again into its layer, cleared, tested against the scene's depth and writing none: what they add
     * premultiplied, and in alpha how much of what is behind them they cover. Published as {@link CgGradeLayers#KEY}.
     */
    void recordGradeLayers(CgStageFrame stage, CgRecording recording, CgHostView view, CgPassConstants constants) {
        Arrays.fill(gradeSeen, false);
        if (inLayer.length < draws.count) inLayer = new boolean[Math.max(draws.count, inLayer.length * 2)];
        // This firing's transparent draws, and the opaque and masked ones the opaque firing drew: each hides what is
        // behind it in the scene, so its layer holds it too
        for (int i = 0; i < draws.count; i++) {
            inLayer[i] = draws.grades[i] != null && (draws.phase[i] != CgWorldDraws.SKIP ? !world.halfSize(i)
                    : draws.queues[i] < CgRenderQueue.TRANSPARENT_THRESHOLD && world.classify(i, CgWorldRenderer.OPAQUE, view) != CgWorldDraws.SKIP);
        }
        boolean any = false;
        for (int i = 0; i < draws.count; i++) {
            if (!inLayer[i]) continue;
            for (int k = 0; k < CgGradeLayers.MAX; k++) if (gradeSlots[k] == draws.grades[i]) gradeSeen[k] = any = true;
        }
        for (int i = 0; i < draws.count; i++) {
            CgPostSettings g = draws.grades[i];
            if (!inLayer[i] || slotOf(g) >= 0) continue;
            int free = -1;
            for (int k = 0; k < CgGradeLayers.MAX && free < 0; k++) if (!gradeSeen[k]) free = k;
            if (free < 0) {
                if (!gradesWarned) CgWorldRenderer.LOGGER.warn("More than {} graded looks in one frame: the rest keep the frame's look", CgGradeLayers.MAX);
                gradesWarned = true;
                continue;
            }
            gradeSlots[free] = g;
            gradeSeen[free] = any = true;
        }
        for (int k = 0; k < CgGradeLayers.MAX; k++) gradeLayers.looks[k] = null;
        if (!any) return;
        int fb = stage.host().mainFramebuffer();
        if (!CgGraphTexture.takesCurrentDepth(fb)) {
            // A layer is tested against the scene's depth: a host lending none keeps one look
            if (!gradeDepthWarned) CgWorldRenderer.LOGGER.warn("The host's framebuffer lends no depth to share: graded draws keep the frame's look");
            gradeDepthWarned = true;
            return;
        }
        // A set the opaque firing drew draws its culled records: cull it here too, as the passes' are
        world.cullSets(stage, recording, view, CgWorldRenderer.CULL_LAYERS);
        CgGraphTexture scene = stage.resources().get(CgFrameKeys.SCENE);
        int w = Math.max(1, Math.round(world.targetWidth)), h = Math.max(1, Math.round(world.targetHeight));
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (!gradeSeen[k]) continue;
            if (gradeTargets[k] == null || gradeSizes[k * 2] != w || gradeSizes[k * 2 + 1] != h) {
                gradeTargets[k] = CgGraphTexture.besideCurrentDepth("cg_grade_layer" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
                gradeApart[k] = CgGraphTexture.besideCurrentDepth("cg_grade_apart" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
                gradeSizes[k * 2] = w;
                gradeSizes[k * 2 + 1] = h;
            }
            layerPass(stage, recording, view, constants, gradeTargets[k], k, false, scene);
            // Its separate draws apart, laid over it after the colour regions as they are over the scene
            boolean apartHere = false;
            for (int i = 0; i < draws.count && !apartHere; i++) apartHere = inLayer[i] && draws.phase[i] == CgWorldDraws.APART && draws.grades[i] == gradeSlots[k];
            if (apartHere) {
                layerPass(stage, recording, view, constants, gradeApart[k], k, true, scene);
                world.separate.layers[k] = gradeApart[k];
            }
            gradeLayers.layers[k] = gradeTargets[k];
            gradeLayers.looks[k] = gradeSlots[k];
        }
        stage.resources().put(CgGradeLayers.KEY, gradeLayers);
        if (world.separate.any()) stage.resources().put(CgSeparateTranslucency.KEY, world.separate);
    }

    /**
     * Into {@code target}, cleared: graded slot {@code k}'s draws, its separate ones alone with {@code apart} or the rest
     * without; with {@code k} below 0 every separate draw.
     */
    void layerPass(CgStageFrame stage, CgRecording recording, CgHostView view, CgPassConstants constants,
                   CgGraphTexture target, int k, boolean apart, @Nullable CgGraphTexture scene) {
        CgRasterPass pass = recording.raster(target, CgWorldRenderer.CLEAR_LAYER, constants, null, CgOrder.SORTED)
                .timed(k < 0 ? CgWorldRenderer.GPU_SEPARATE : CgWorldRenderer.GPU_GRADE)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT)
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture());
        if (scene != null) pass.sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, scene);
        world.mediated(pass);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (k < 0 ? draws.phase[i] != CgWorldDraws.APART : !inLayer[i] || draws.grades[i] != gradeSlots[k] || (draws.phase[i] == CgWorldDraws.APART) != apart) continue;
            layerDraws(chunks, recording, view, i);
        }
        pass.add(chunks.end());
        pass.end();
    }

    /** Draw {@code i} in its layer forms: the programs the transparent pass draws it with, or a solid's cover. */
    void layerDraws(CgChunkBuilder chunks, CgRecording recording, CgHostView view, int i) {
        commands.modelOf(i, view);
        commands.model.normal(commands.normal);
        boolean glows = draws.emissions[i] > 0f && draws.queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
        boolean solid = draws.queues[i] < CgRenderQueue.TRANSPARENT_THRESHOLD;
        for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
            CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) continue;
            if (solid) {
                // It covers what is behind it whatever alpha its fragment writes; its glow adds, as before the
                // transparent pass, into the scene
                layerDraw(chunks, pipeline.as(CgPipelineForm.SOLID), link, recording, i);
                if (glows && link.hasEmissivePass()) {
                    layerDraw(chunks, link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT), link, recording, i);
                }
                continue;
            }
            // The same programs the transparent pass draws it with, so the layer holds what it adds there
            if (glows && link.hasEmissivePass()) {
                CgPipeline folded = world.mergeEmission ? pipeline.as(CgPipelineForm.FOLD) : null;
                if (folded != null) {
                    layerDraw(chunks, folded, link, recording, i);
                    continue;
                }
                if (!pipeline.state().writesNothing()) layerDraw(chunks, pipeline, link, recording, i);
                CgPipeline glow = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                if (glow != null) layerDraw(chunks, glow, link, recording, i);
                continue;
            }
            if (!pipeline.state().writesNothing()) layerDraw(chunks, pipeline, link, recording, i);
        }
    }

    /**
     * Moves this transparent firing's separate translucency ({@code CgMaterialState.separateTranslucency}, drawn after
     * distortion) to {@link CgWorldDraws#APART} under the HDR scene, where the host lends its depth to share; how many moved. A draw
     * whose blend multiplies has no form of light and coverage and stays in the scene, warned once.
     */
    int separateDraws(CgStageFrame stage) {
        if (!stage.resources().has(CgFrameKeys.SCENE) || !CgGraphTexture.takesCurrentDepth(stage.host().mainFramebuffer())) return 0;
        int moved = 0;
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] != CgWorldDraws.AFTER || !draws.materials[i].state().separateTranslucency()) continue;
            if (!layerable(i)) {
                if (!separateMultiplyWarned) {
                    CgWorldRenderer.LOGGER.warn("A separate translucency draw whose blend multiplies draws into the scene, where colour regions grade it");
                }
                separateMultiplyWarned = true;
                continue;
            }
            draws.phase[i] = CgWorldDraws.APART;
            moved++;
        }
        return moved;
    }

    /** Whether every program draw {@code i} draws has a layer form. */
    boolean layerable(int i) {
        for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
            CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
            if (pipeline != null && !pipeline.state().writesNothing() && layered(pipeline) == null) return false;
        }
        return true;
    }

    /** The separate draws into their target, published as {@link CgSeparateTranslucency#KEY} for the post stack. */
    void recordSeparate(CgStageFrame stage, CgRecording recording, CgHostView view, CgPassConstants constants) {
        int w = Math.max(1, Math.round(world.targetWidth)), h = Math.max(1, Math.round(world.targetHeight));
        if (separateTarget == null || separateWidth != w || separateHeight != h) {
            separateTarget = CgGraphTexture.besideCurrentDepth("cg_separate_translucency", new CgTextureDesc(w, h, GRADE_FORMAT));
            separateWidth = w;
            separateHeight = h;
        }
        layerPass(stage, recording, view, constants, separateTarget, -1, true, stage.resources().get(CgFrameKeys.SCENE));
        world.separate.target = separateTarget;
        stage.resources().put(CgSeparateTranslucency.KEY, world.separate);
    }

    int slotOf(CgPostSettings look) {
        for (int k = 0; k < CgGradeLayers.MAX; k++) if (gradeSlots[k] == look && gradeSeen[k]) return k;
        return -1;
    }

    void layerDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterial link, CgRecording recording, int i) {
        CgPipeline layer = layered(pipeline);
        if (layer != null) commands.passDraw(chunks, layer, link, recording, i);
    }

    /**
     * {@code pipeline} drawing into a graded layer: its colour blend kept, its alpha the coverage (over what is behind for
     * a blend that covers, nothing for one that adds), depth tested at LEQUAL and unwritten. Null for no pipeline, or one
     * whose blend multiplies, which a layer of added light cannot hold.
     */
    @Nullable
    CgPipeline layered(@Nullable CgPipeline pipeline) {
        if (pipeline == null) return null;
        CgPipeline made = layerForms.get(pipeline);
        if (made == null) {
            CgRenderState state = layerState(pipeline.state());
            made = state == null ? pipeline : pipeline.withState(state);
            layerForms.put(pipeline, made);
        }
        return made == pipeline ? null : made;
    }

    @Nullable
    static CgRenderState layerState(CgRenderState own) {
        // Unset is the transparent pass's alpha blend.
        CgBlendState b = own.getBlend() != null ? own.getBlend() : CgBlendState.ALPHA;
        CgBlendState blend = b.layered();
        if (blend == null) return null;
        CgDepthState depth = own.getDepth();
        return CgRenderState.builder().blend(blend)
                .depth(depth == null || depth.test() ? CgDepthState.TEST_ONLY : CgDepthState.NONE)
                .cull(own.getCull()).colorMask(CgColorMask.ALL).build();
    }
}
