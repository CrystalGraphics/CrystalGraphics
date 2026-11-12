package com.crystalgraphics.render.world;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import java.util.Arrays;
import java.util.IdentityHashMap;
import javax.annotation.Nullable;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * The layer passes: each graded draw drawn again into its look's layer, which its post look grades and composites
 * (CgPostSettings), and separate translucency drawn into a layer of its own after every other translucent draw,
 * unbent by their distortion (Unreal's {@code TranslucencyAfterDOF}).
 *
 * <pre>{@code
 * if (scene) gradeLayerPass.prepareLayers(stage, recording, view, constants);      // before the transparent passes
 * ... the transparent passes, the output slot's layer their second output ...
 * if (scene) gradeLayerPass.recordGradeLayers(stage, recording, view, constants);
 * if (apartDrawn > 0) gradeLayerPass.recordSeparate(stage, recording, view, constants);
 * }</pre>
 */
final class CgGradeLayerPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final CgFormat GRADE_FORMAT = CgFormat.RGBA16_FLOAT;

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
    /** The slot whose layer the transparent passes write as their second output this firing; -1 for none. */
    int outputSlot = -1;
    /** Per draw this firing: in the output slot's layer already, drawn there by the transparent passes or its cover. */
    boolean[] viaOutput = new boolean[64];
    /** Whether {@link #prepareLayers} found a look to grade this firing. */
    private boolean anySeen;
    /** Graded draws drawn once, their layer the transparent pass's second output; drawn again into a layer. */
    private static final int LAYER_ONCE = CgTrace.name("world.graded-once"), LAYER_AGAIN = CgTrace.name("world.graded-again");

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
     * This firing's looks given slots and their layers made, before its transparent passes: the slot with the most
     * transparent draws that can take it becomes the transparent passes' second output ({@link #outputSlot}), its layer
     * cleared with the covers of its opaque draws in it, which are behind every transparent fragment over them. On the
     * device alone; the rest wait for {@link #recordGradeLayers}.
     */
    void prepareLayers(CgStageFrame stage, CgRecording recording, CgCamera view, CgPassConstants constants) {
        outputSlot = -1;
        anySeen = false;
        Arrays.fill(gradeSeen, false);
        if (inLayer.length < draws.count) inLayer = new boolean[Math.max(draws.count, inLayer.length * 2)];
        if (viaOutput.length < draws.count) viaOutput = new boolean[inLayer.length];
        Arrays.fill(viaOutput, 0, draws.count, false);
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
        if (!stage.host().target().lendsAttachments()) {
            // A layer is tested against the scene's depth: a host lending none keeps one look
            if (!gradeDepthWarned) CgWorldRenderer.LOGGER.warn("The host's framebuffer lends no depth to share: graded draws keep the frame's look");
            gradeDepthWarned = true;
            return;
        }
        anySeen = true;
        // A set the opaque firing drew draws its culled records: cull it here too, as the passes' are
        world.cullSets(stage, recording, view, CgWorldRenderer.CULL_LAYERS);
        int w = Math.max(1, Math.round(world.targetWidth)), h = Math.max(1, Math.round(world.targetHeight));
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (!gradeSeen[k]) continue;
            if (gradeTargets[k] == null || gradeSizes[k * 2] != w || gradeSizes[k * 2 + 1] != h) {
                gradeTargets[k] = CgGraphTexture.transientTexture("cg_grade_layer" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
                gradeApart[k] = CgGraphTexture.transientTexture("cg_grade_apart" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
                gradeSizes[k * 2] = w;
                gradeSizes[k * 2 + 1] = h;
            }
        }
        if (!CgExecutor.drawsOnDevice()) return;
        // The slot whose transparent draws the passes can carry most of
        int best = 0;
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (!gradeSeen[k]) continue;
            int n = 0;
            for (int i = 0; i < draws.count; i++) if (draws.grades[i] == gradeSlots[k] && carried(i)) n++;
            if (n > best) {
                best = n;
                outputSlot = k;
            }
        }
        if (outputSlot < 0) return;
        for (int i = 0; i < draws.count; i++) viaOutput[i] = draws.grades[i] == gradeSlots[outputSlot] && carried(i);
        // Its layer cleared, its opaque draws' covers in it first
        CgRasterPass pass = layerPassBegin(stage, recording, constants, gradeTargets[outputSlot], outputSlot, false,
                stage.resources().get(CgFrameKeys.SCENE));
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (!inLayer[i] || draws.grades[i] != gradeSlots[outputSlot] || draws.phase[i] == CgWorldDraws.APART
                    || draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD) continue;
            layerDraws(chunks, recording, view, i);
            viaOutput[i] = true;
        }
        pass.add(chunks.end());
        pass.end();
    }

    /**
     * Whether transparent draw {@code i} draws its layer in the transparent pass: in it this firing, no set, every
     * program it draws there with a layer form already built (one not yet built is started, and the layer pass draws
     * the draw meanwhile).
     */
    private boolean carried(int i) {
        if (!inLayer[i] || draws.queues[i] < CgRenderQueue.TRANSPARENT_THRESHOLD || draws.sets[i] != null) return false;
        byte phase = draws.phase[i];
        if (phase != CgWorldDraws.FORWARD && phase != CgWorldDraws.FORWARD_AND_PREPASS && phase != CgWorldDraws.AFTER) return false;
        boolean glows = draws.emissions[i] > 0f && draws.queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
        boolean skinned = commands.skinnedDraw(i);
        CgMaterialProxy material = CgMaterialProxy.of(draws.materials[i]);
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return true;
        if (glows && material.hasEmissivePass()) {
            // Its glow only folded into its colour: a glow drawn apart is an Emissive pass, which takes no layer form
            CgPipeline folded = world.mergeEmission ? pipeline.as(CgPipelineForm.FOLD) : null;
            return folded != null && built(folded.as(CgPipelineForm.LAYER), skinned);
        }
        return pipeline.state().writesNothing() || built(pipeline.as(CgPipelineForm.LAYER), skinned);
    }

    private static boolean built(@Nullable CgPipeline layered, boolean skinned) {
        return layered != null && CgWorldRenderer.built(skinned ? layered.as(CgPipelineForm.SKINNED) : layered);
    }

    /**
     * Each look's draws into its layer, tested against the scene's depth and writing none: what they add premultiplied,
     * and in alpha how much of what is behind them they cover. The output slot's layer holds the transparent passes'
     * already: what they did not carry is added over it. Published as {@link CgGradeLayers#KEY}.
     */
    void recordGradeLayers(CgStageFrame stage, CgRecording recording, CgCamera view, CgPassConstants constants) {
        if (!anySeen) return;
        anySeen = false;
        CgGraphTexture scene = stage.resources().get(CgFrameKeys.SCENE);
        int once = 0, again = 0;
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (!gradeSeen[k]) continue;
            boolean output = k == outputSlot;
            int rest = 0;
            for (int i = 0; i < draws.count; i++) {
                if (!inLayer[i] || draws.grades[i] != gradeSlots[k] || draws.phase[i] == CgWorldDraws.APART) continue;
                if (output && viaOutput[i]) {
                    if (draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD) once++;
                    else again++;   // an opaque draw's cover: drawn in the opaque firing, and here
                } else {
                    rest++;
                }
            }
            again += rest;
            if (!output || rest > 0) layerPass(stage, recording, view, constants, gradeTargets[k], k, false, scene, output);
            // Its separate draws apart, laid over it after the colour regions as they are over the scene
            boolean apartHere = false;
            for (int i = 0; i < draws.count && !apartHere; i++) apartHere = inLayer[i] && draws.phase[i] == CgWorldDraws.APART && draws.grades[i] == gradeSlots[k];
            if (apartHere) {
                layerPass(stage, recording, view, constants, gradeApart[k], k, true, scene, false);
                world.separate.layers[k] = gradeApart[k];
            }
            gradeLayers.layers[k] = gradeTargets[k];
            gradeLayers.looks[k] = gradeSlots[k];
        }
        CgTrace.add(CgChannels.WORLD, LAYER_ONCE, once);
        CgTrace.add(CgChannels.WORLD, LAYER_AGAIN, again);
        outputSlot = -1;
        stage.resources().put(CgGradeLayers.KEY, gradeLayers);
        if (world.separate.any()) stage.resources().put(CgSeparateTranslucency.KEY, world.separate);
    }

    /**
     * Into {@code target}, cleared: graded slot {@code k}'s draws, its separate ones alone with {@code apart} or the rest
     * without; with {@code k} below 0 every separate draw.
     */
    void layerPass(CgStageFrame stage, CgRecording recording, CgCamera view, CgPassConstants constants,
                   CgGraphTexture target, int k, boolean apart, @Nullable CgGraphTexture scene, boolean over) {
        CgRasterPass pass = layerPassBegin(stage, recording, constants, target, k, over, scene);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (k < 0 ? draws.phase[i] != CgWorldDraws.APART : !inLayer[i] || draws.grades[i] != gradeSlots[k] || (draws.phase[i] == CgWorldDraws.APART) != apart) continue;
            if (over && viaOutput[i]) continue;
            layerDraws(chunks, recording, view, i);
        }
        pass.add(chunks.end());
        pass.end();
    }

    /** A layer pass into {@code target}: cleared, or over what is in it; tested against the scene's depth. */
    private CgRasterPass layerPassBegin(CgStageFrame stage, CgRecording recording, CgPassConstants constants,
                                        CgGraphTexture target, int k, boolean over, @Nullable CgGraphTexture scene) {
        // Drawn beside the host's depth, tested against it and reading it
        CgRasterPass pass = recording.raster("world.gradeLayer").color(target, over ? CgLoad.load() : CgWorldRenderer.CLEAR_LAYER)
                .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants)
                .timed(k < 0 ? CgWorldRenderer.GPU_SEPARATE : CgWorldRenderer.GPU_GRADE)
                .readsDepth(CgGraphTexture.current())
                .texture(CgBindingModel.LIGHTMAP, stage.host().textures().lightmapTexture());
        if (scene != null) pass.readsColor(scene);
        world.mediated(pass);
        return pass;
    }

    /** Draw {@code i} in its layer forms: the programs the transparent pass draws it with, or a solid's cover. */
    void layerDraws(CgChunkBuilder chunks, CgRecording recording, CgCamera view, int i) {
        boolean glows = draws.emissions[i] > 0f && draws.queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
        boolean solid = draws.queues[i] < CgRenderQueue.TRANSPARENT_THRESHOLD;
        CgMaterialProxy proxy = CgMaterialProxy.of(draws.materials[i]);
        CgPipeline pipeline = proxy.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        if (solid) {
            // It covers what is behind it whatever alpha its fragment writes; its glow adds, as before the
            // transparent pass, into the scene
            layerDraw(chunks, pipeline.as(CgPipelineForm.SOLID), draws.materials[i], recording, i);
            if (glows && proxy.hasEmissivePass()) {
                layerDraw(chunks, proxy.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT), draws.materials[i], recording, i);
            }
            return;
        }
        // The same programs the transparent pass draws it with, so the layer holds what it adds there
        if (glows && proxy.hasEmissivePass()) {
            CgPipeline folded = world.mergeEmission ? pipeline.as(CgPipelineForm.FOLD) : null;
            if (folded != null) {
                layerDraw(chunks, folded, draws.materials[i], recording, i);
                return;
            }
            if (!pipeline.state().writesNothing()) layerDraw(chunks, pipeline, draws.materials[i], recording, i);
            CgPipeline glow = proxy.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
            if (glow != null) layerDraw(chunks, glow, draws.materials[i], recording, i);
            return;
        }
        if (!pipeline.state().writesNothing()) layerDraw(chunks, pipeline, draws.materials[i], recording, i);
    }

    /**
     * Moves this transparent firing's separate translucency ({@code CgMaterialState.separateTranslucency}, drawn after
     * distortion) to {@link CgWorldDraws#APART} under the HDR scene, where the host lends its depth to share; how many moved. A draw
     * whose blend multiplies has no form of light and coverage and stays in the scene, warned once.
     */
    int separateDraws(CgStageFrame stage) {
        if (!stage.resources().has(CgFrameKeys.SCENE) || !stage.host().target().lendsAttachments()) return 0;
        int moved = 0;
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] != CgWorldDraws.AFTER || !CgMaterialProxy.of(draws.materials[i]).separateTranslucency()) continue;
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
        CgMaterialProxy proxy = CgMaterialProxy.of(draws.materials[i]);
        CgPipeline pipeline = proxy.pipeline(CgInstanceKind.OBJECT);
        if (pipeline != null && !pipeline.state().writesNothing() && layered(pipeline) == null) return false;
        return true;
    }

    /** The separate draws into their target, published as {@link CgSeparateTranslucency#KEY} for the post stack. */
    void recordSeparate(CgStageFrame stage, CgRecording recording, CgCamera view, CgPassConstants constants) {
        int w = Math.max(1, Math.round(world.targetWidth)), h = Math.max(1, Math.round(world.targetHeight));
        if (separateTarget == null || separateWidth != w || separateHeight != h) {
            separateTarget = CgGraphTexture.transientTexture("cg_separate_translucency", new CgTextureDesc(w, h, GRADE_FORMAT));
            separateWidth = w;
            separateHeight = h;
        }
        layerPass(stage, recording, view, constants, separateTarget, -1, true, stage.resources().get(CgFrameKeys.SCENE), false);
        world.separate.target = separateTarget;
        stage.resources().put(CgSeparateTranslucency.KEY, world.separate);
    }

    int slotOf(CgPostSettings look) {
        for (int k = 0; k < CgGradeLayers.MAX; k++) if (gradeSlots[k] == look && gradeSeen[k]) return k;
        return -1;
    }

    void layerDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterialInterface material, CgRecording recording, int i) {
        CgPipeline layer = layered(pipeline);
        if (layer != null) commands.passDraw(chunks, layer, material, recording, i);
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
