package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
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
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The emissive passes ({@code "LightMode" = "Emissive"}): a draw's glow, light added into a target of its own that
 * bloom takes, into the HDR scene where it merges, and an impact frame's subject drawn apart. Unreal's emissive light is
 * part of the base pass; ours is a pass of its own wherever it cannot fold into the colour's draw.
 *
 * <pre>{@code
 * emissivePass.recordGlows(stage, recording, view, into);      // a glow target bloom reads
 * emissivePass.recordSceneGlows(stage, recording, view);       // into the HDR scene
 * }</pre>
 */
final class CgEmissivePass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final CgFrameBufferFormat EMISSION_FORMAT = CgFrameBufferFormat.builder("cg_emission")
            .color(0, CgTextureType.R11F_G11F_B10F).build();

    /**
     * Emissive passes add into the scene, or a glow target of their own (an impact frame's subject), which has no depth:
     * the scene hides them by its copied depth. ONE ONE, since emitted light is written with an alpha of 0.
     */
    static final CgRenderState EMISSIVE_STATE = CgRenderState.builder()
            .depth(CgDepthState.NONE).blend(CgBlendState.SUM).build();

    static final int GPU_EMISSION = CgGpuTrace.name("world.emission");

    final CgPassConstants emissionConstants = new CgPassConstants();

    // Glows drawn apart, for an impact frame's subject: which draws, and the target.
    boolean[] emits = new boolean[64];

    CgGraphTexture subjectTarget;

    /** An impact frame's subject drawn at the opaque stage ({@link #recordOwnedSubject}), and the ring frame it holds. */
    CgGraphTexture ownedSubject;

    long ownedSubjectFrame = -1;

    /** {@link #markGlows}'s parts: every glow, or those whose colour draws before the transparent pass. */
    static final int EMIT_ALL = 0, EMIT_BEFORE = 1;

    CgEmissivePass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * The glows {@link #emits} marks into {@code into}, cleared, hidden by the stage's depth; each transparent surface
     * covers those behind it too ({@code CgPipeline.emissionOccluder}).
     */
    void recordGlows(CgStageFrame stage, CgRecording recording, CgHostView view, CgGraphTexture into) {
        world.cullSets(stage, recording, view, CgWorldRenderer.CULL_EMITTING);
        stage.constants().write(world.constantsBlock, 0);
        emissionConstants.read(world.constantsBlock, 0).resolution(into.getWidth(), into.getHeight());

        CgRasterPass glow = recording.raster(into, CgWorldRenderer.CLEAR, emissionConstants, EMISSIVE_STATE, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target()).timed(GPU_EMISSION);
        world.mediated(glow);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            // Each transparent surface's cover too, in the glows' order.
            boolean covers = (draws.phase[i] == CgWorldDraws.FORWARD || draws.phase[i] == CgWorldDraws.AFTER)
                    && draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD && draws.queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
            if (!emits[i] && !covers) continue;
            commands.modelOf(i, view);
            commands.model.normal(commands.normal);
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline forward = covers ? link.pipeline(CgInstanceKind.OBJECT) : null;
                if (forward != null && !forward.state().writesNothing()) {
                    CgPipeline occluder = forward.as(CgPipelineForm.COVER);
                    if (occluder != null) commands.emissionDraw(chunks, occluder, link, recording, i);
                }
                if (!emits[i] || !link.hasEmissivePass()) continue;
                CgPipeline pipeline = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                if (pipeline != null) commands.emissionDraw(chunks, pipeline, link, recording, i);
            }
        }
        glow.add(chunks.end());
        glow.end();
    }

    /**
     * Marks {@link #emits}: the visible draws with a glow in {@code part}; answers how many, and counts every visible glow.
     */
    int markGlows(CgHostView view, int part) {
        if (emits.length < draws.meshes.length) emits = new boolean[draws.meshes.length];
        int emitting = 0, recorded = 0;
        for (int i = 0; i < draws.count; i++) {
            int queue = draws.queues[i];
            // A transparent draw was classified for this stage; an opaque one is classified again, its pass recorded.
            boolean e = queue < CgRenderQueue.OVERLAY_THRESHOLD && draws.emissions[i] > 0f && CgWorldRenderer.emitsLight(draws.materials[i])
                    && (queue >= CgRenderQueue.TRANSPARENT_THRESHOLD ? draws.phase[i] != CgWorldDraws.SKIP : world.classify(i, CgWorldRenderer.OPAQUE, view) != CgWorldDraws.SKIP);
            if (e) emitting++;
            // A volume's glow folded into its light is drawn there.
            if (draws.phase[i] == CgWorldDraws.VOLUME && world.volumeFolds && world.allFold(i)) e = false;
            boolean before = queue < CgRenderQueue.TRANSPARENT_THRESHOLD || world.halfSize(i);
            e &= part == EMIT_ALL || (part == EMIT_BEFORE) == before;
            emits[i] = e;
            if (e) recorded++;
        }
        CgTrace.counter(CgChannels.WORLD, "world.emissiveDraws", emitting);
        return recorded;
    }

    /**
     * Under the HDR scene, the Emissive passes of the draws whose colour draws before the transparent pass (opaque and
     * half-size draws) added into the scene, hidden by its depth, so the transparent pass covers them as it covers what
     * they glow on. A transparent draw's glow follows its own draw in that pass ({@link CgBasePass#recordPass}).
     */
    void recordSceneGlows(CgStageFrame stage, CgRecording recording, CgHostView view) {
        if (markGlows(view, EMIT_BEFORE) == 0) return;
        world.cullSets(stage, recording, view, CgWorldRenderer.CULL_EMITTING);
        CgRasterPass glow = recording.raster(stage.target(), CgLoad.load(), stage.constants().sceneGlow(world.sceneEmission),
                        EMISSIVE_STATE, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).timed(GPU_EMISSION);
        world.mediated(glow);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (!emits[i]) continue;
            commands.modelOf(i, view);
            commands.model.normal(commands.normal);
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.hasEmissivePass() ? link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT) : null;
                if (pipeline != null) commands.emissionDraw(chunks, pipeline, link, recording, i);
            }
        }
        glow.add(chunks.end());
        glow.end();
    }

    /**
     * What the hitting effect glows with, for an impact frame ({@link CgFrameKeys#SUBJECT}), while the post stack asks
     * ({@link CgFrameKeys#SUBJECT_READ}): with a key, what {@link #recordOwnedSubject} drew; else every visible glow,
     * into a quarter-size target. The only time glows are drawn apart from the scene.
     */
    void recordSubject(CgStageFrame stage, CgRecording recording, CgHostView view, int key) {
        if (key != 0) {
            if (ownedSubjectFrame == world.frame) stage.resources().put(CgFrameKeys.SUBJECT, ownedSubject);
            return;
        }
        if (markGlows(view, EMIT_ALL) == 0) return;
        int w = Math.max(1, (int) (world.targetWidth * 0.25f)), h = Math.max(1, (int) (world.targetHeight * 0.25f));
        if (subjectTarget == null || subjectTarget.getWidth() != w || subjectTarget.getHeight() != h) {
            subjectTarget = CgGraphTexture.transientTexture("cg_subject", new CgTextureDesc(w, h, EMISSION_FORMAT));
        }
        recordGlows(stage, recording, view, subjectTarget);
        stage.resources().put(CgFrameKeys.SUBJECT, subjectTarget);
    }

    /**
     * The subject of an impact frame whose settings name {@code key}: the glows of the draws holding it
     * ({@link Draw#subject}), drawn at the opaque stage before its own draws, so the depth hiding them is the host's
     * world alone and no other effect's smoke covers them. The transparent stage publishes it as
     * {@link CgFrameKeys#SUBJECT}; a frame where no such draw shows publishes none.
     */
    void recordOwnedSubject(CgStageFrame stage, CgRecording recording, CgHostView view, int key) {
        if (emits.length < draws.meshes.length) emits = new boolean[draws.meshes.length];
        int marked = 0;
        for (int i = 0; i < draws.count; i++) {
            int queue = draws.queues[i];
            boolean e = draws.subjects[i] == key && draws.sets[i] == null && queue < CgRenderQueue.OVERLAY_THRESHOLD
                    && draws.emissions[i] > 0f && CgWorldRenderer.emitsLight(draws.materials[i])
                    && world.classify(i, queue >= CgRenderQueue.TRANSPARENT_THRESHOLD ? CgWorldRenderer.TRANSPARENT : CgWorldRenderer.OPAQUE, view) != CgWorldDraws.SKIP;
            emits[i] = e;
            if (e) marked++;
        }
        ownedSubjectFrame = marked > 0 ? world.frame : -1;
        if (marked == 0) return;
        int w = Math.max(1, (int) (world.targetWidth * 0.25f)), h = Math.max(1, (int) (world.targetHeight * 0.25f));
        if (ownedSubject == null || ownedSubject.getWidth() != w || ownedSubject.getHeight() != h) {
            if (ownedSubject != null) recording.release(ownedSubject);
            ownedSubject = CgGraphTexture.requested("cg_subject_owned", new CgTextureDesc(w, h, EMISSION_FORMAT));
        }
        stage.constants().write(world.constantsBlock, 0);
        emissionConstants.read(world.constantsBlock, 0).resolution(w, h);
        CgRasterPass glow = recording.raster(ownedSubject, CgWorldRenderer.CLEAR, emissionConstants, EMISSIVE_STATE,
                CgOrder.SORTED).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target()).timed(GPU_EMISSION);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (!emits[i]) continue;
            commands.modelOf(i, view);
            commands.model.normal(commands.normal);
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                if (!link.hasEmissivePass()) continue;
                CgPipeline pipeline = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                if (pipeline != null) commands.emissionDraw(chunks, pipeline, link, recording, i);
            }
        }
        glow.add(chunks.end());
        glow.end();
    }
}
