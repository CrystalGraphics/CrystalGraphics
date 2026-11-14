package com.crystalgraphics.render.scene.renderer.feature.glow;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The scene's glows (a material's {@code s.emissive}) drawn apart from their colour: light added into the HDR scene for
 * what draws before the translucent pass, and an impact frame's subject. Unreal's emissive light is part of the base
 * pass; ours is a pass of its own wherever it cannot fold into the colour's draw. The scene renderer's feature.
 *
 * <pre>{@code
 * glows.recordOwnedSubject(firing, passes, key);           // opaque callback, before our draws
 * glows.recordSceneGlows(firing, passes, constants, folds); // translucent callback, on the lit opaque scene
 * glows.recordSubject(firing, passes, key);                 // after the translucent passes
 * }</pre>
 */
public final class CgSceneGlows {

    private static final CgFormat EMISSION_FORMAT = CgFormat.RG11B10_UFLOAT;
    /** Glows add into the scene or a target of their own with no depth: the scene hides them by its copied depth. */
    private static final CgRenderState EMISSIVE_STATE = CgRenderState.builder().depth(CgDepthState.NONE).blend(CgBlendState.SUM).build();
    private static final int GPU_EMISSION = CgGpuTrace.name("scene.emission");

    /** A view's keyed subject drawn at its opaque callback, and the frame it holds. */
    private static final class OwnedSubject {
        CgGraphTexture texture;
        long frame = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<OwnedSubject> OWNED_SUBJECT = new CgViewState.Key<>("scene.subject", OwnedSubject::new);

    private final CgSceneRenderer renderer;
    private final CgPassConstants emissionConstants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private CgDrawCommands merged;
    private CgGraphTexture subjectTarget;
    private int subjectKey;
    private boolean volumeFolds;

    /** Glows whose colour draws before the translucent pass: solids and half-size draws, a folded volume's not. */
    private final CgCommandForm before = (rows, b, p) -> {
        if (!CgSceneRenderer.emits(rows, b)) return null;
        boolean half = (rows.flags(b) & CgMeshBatches.HALF_RESOLUTION) != 0;
        if (half && volumeFolds && CgEnginePasses.covers(rows.material(b)) && CgSceneRenderer.folds(rows, b)) return null;
        if (CgSceneRenderer.queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD && !half) return null;
        return p;
    };
    /** Every glow in view. */
    private final CgCommandForm all = (rows, b, p) -> CgSceneRenderer.emits(rows, b) ? p : null;
    /** The glows of the primitives marked with this frame's subject key. */
    private final CgCommandForm owned = (rows, b, p) -> CgSceneRenderer.emits(rows, b) && rows.instances(b) == null
            && rows.subject(b) == subjectKey ? p : null;
    /** A translucent surface's cover in a glow target: what it hides of the glows behind it. */
    private final CgCommandForm cover = (rows, b, p) -> {
        int q = CgSceneRenderer.queue(rows, b);
        if (p.pass() != CgRenderPassVariant.FORWARD || q < CgRenderQueue.TRANSPARENT_THRESHOLD || q >= CgRenderQueue.OVERLAY_THRESHOLD
                || p.state().writesNothing()) return null;
        return p.as(CgPipelineForm.COVER);
    };

    public CgSceneGlows(CgSceneRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Under the HDR scene, the glows of what draws before the translucent pass (solids and half-size draws) added into
     * the scene, hidden by its depth, so the translucent pass covers them as it covers what they glow on. A translucent
     * draw's glow follows its own draw in that pass.
     */
    public void recordSceneGlows(CgStageFiring firing, CgViewPasses passes, CgPassConstants constants, boolean volumeFolds) {
        this.volumeFolds = volumeFolds;
        CgDrawCommands glows = passes.commands(CgEnginePasses.EMISSIVE, firing.recording(), before);
        if (glows.size() == 0) return;
        CgRasterPass glow = firing.recording().raster("scene.glows").color(firing.target(), CgLoad.load())
                .depth(firing.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).state(EMISSIVE_STATE)
                .readsDepth(firing.depth()).timed(GPU_EMISSION);
        renderer.mediated(glow);
        glow.add(glows).end();
        CgTrace.counter(CgChannels.WORLD, "scene.emissiveDraws", glows.size());
    }

    /**
     * What the hitting effect glows with, for an impact frame, where the view's textures asked: with a key, what
     * {@link #recordOwnedSubject} drew; else every visible glow into a quarter-size target, each translucent surface
     * covering the glows behind it.
     */
    public void recordSubject(CgStageFiring firing, CgViewPasses passes, int key) {
        CgView view = firing.view();
        if (key != 0) {
            OwnedSubject kept = view.state().get(OWNED_SUBJECT);
            if (kept.frame == view.state().frame()) view.textures().subject(kept.texture);
            return;
        }
        CgRecording rec = firing.recording();
        if (merged == null || merged.table() != rec.primitives()) merged = new CgDrawCommands(rec.primitives());
        merged.clear();
        merged.append(passes.commands(CgEnginePasses.EMISSIVE, rec, all));
        if (merged.size() == 0) return;
        merged.append(passes.commands(CgEnginePasses.TRANSLUCENT, rec, cover));
        merged.append(passes.commands(CgEnginePasses.AFTER_DISTORTION, rec, cover));
        merged.sort();
        int w = Math.max(1, (int) (view.width() * 0.25f)), h = Math.max(1, (int) (view.height() * 0.25f));
        if (subjectTarget == null || subjectTarget.getWidth() != w || subjectTarget.getHeight() != h) {
            subjectTarget = CgGraphTexture.transientTexture("cg_subject", new CgTextureDesc(w, h, EMISSION_FORMAT));
        }
        CgGraphTexture target = subjectTarget;
        firing.constants().write(block, 0);
        emissionConstants.read(block, 0).resolution(w, h);
        CgRasterPass glow = rec.raster("scene.subject").color(target, CgLoad.CLEAR).constants(emissionConstants)
                .state(EMISSIVE_STATE).readsDepth(firing.depth()).timed(GPU_EMISSION);
        renderer.mediated(glow);
        glow.add(merged).end();
        view.textures().subject(target);
    }

    /**
     * The subject of an impact frame whose look names {@code key}: the glows of the primitives marked with it
     * ({@code subject(key)}), drawn at the opaque callback before our own draws, so the depth hiding them is the host's
     * world alone and no other effect's smoke covers them. A frame where none shows holds none.
     */
    public void recordOwnedSubject(CgStageFiring firing, CgViewPasses passes, int key) {
        subjectKey = key;
        CgView view = firing.view();
        CgRecording rec = firing.recording();
        CgDrawCommands glows = passes.commands(CgEnginePasses.EMISSIVE, rec, owned);
        OwnedSubject kept = view.state().get(OWNED_SUBJECT);
        kept.frame = glows.size() > 0 ? view.state().frame() : Long.MIN_VALUE;
        if (glows.size() == 0) return;
        int w = Math.max(1, (int) (view.width() * 0.25f)), h = Math.max(1, (int) (view.height() * 0.25f));
        if (kept.texture == null || kept.texture.getWidth() != w || kept.texture.getHeight() != h) {
            if (kept.texture != null) rec.release(kept.texture);
            kept.texture = CgGraphTexture.requested("cg_subject_owned", new CgTextureDesc(w, h, EMISSION_FORMAT));
        }
        firing.constants().write(block, 0);
        emissionConstants.read(block, 0).resolution(w, h);
        rec.raster("scene.ownedSubject").color(kept.texture, CgLoad.CLEAR).constants(emissionConstants).state(EMISSIVE_STATE)
                .readsDepth(firing.depth()).timed(GPU_EMISSION).add(glows).end();
    }
}
