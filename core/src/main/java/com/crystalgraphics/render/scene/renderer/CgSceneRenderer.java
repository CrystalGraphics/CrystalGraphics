package com.crystalgraphics.render.scene.renderer;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.service.CgShaderPacks;
import com.crystalgraphics.platform.world.CgWorldBegin;
import com.crystalgraphics.render.scene.component.CgCanvasLabels;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.look.CgLookIds;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.scene.renderer.feature.ao.CgAmbientOcclusion;
import com.crystalgraphics.render.scene.renderer.feature.debug.CgOverdraw;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortion;
import com.crystalgraphics.render.scene.renderer.feature.glow.CgSceneGlows;
import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgHalfResolution;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgLayerPasses;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Draws a scene through a view, inside the host's two world callbacks, in one declared order (Unreal's
 * {@code FDeferredShadingSceneRenderer::Render}); a view of one's own runs both callbacks back to back. One per scene,
 * views passed in. Engine side: a consumer reaches it only through stages ({@link CgRenderStage}) and flags.
 *
 * <p><b>Opaque</b>: the client world's frame (once a frame); {@code PREPARE}; a keyed subject's glow; our opaque scene in;
 * the instance cull; the depth prepass (normals beside it where asked); the solids' velocity; ambient occlusion; the
 * base pass; our opaque scene out; {@code OPAQUE}.</p>
 * <p><b>Translucent</b>: the HDR scene in; the labels' depth; custom data; custom depth; the blends' velocity and
 * responsive marks; normals published; lights;
 * media; the scene glows; decals; {@code SURFACES}; graded layers prepared; the instance cull; the translucent prepass;
 * late solids; half size; volumes; distortion's bends; the translucent pass; distortion's final apply; after distortion;
 * separate translucency; graded layers; the subject; overdraw; labels; {@code TRANSLUCENT}; post; {@code OVERLAY}.</p>
 *
 * <ul>
 *   <li>What it reads is flags ({@link #HALF_RESOLUTION} and the rest), each overridable per view
 *       ({@code view.override(flag, value)}), and the view's show flags: never a setter.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgSceneRenderer {

    static final Logger LOGGER = LogManager.getLogger("CgSceneRenderer");

    /** A translucent draw's codeless Emissive pass folded into its Forward draw; false draws each on its own. */
    public static final CgVar.Bool MERGE_EMISSION = CgVar.bool("crystalgraphics.world.mergeEmission", true, CgVar.DEV,
            "A translucent draw's codeless Emissive pass folded into its Forward draw; false draws every Emissive pass on"
                    + " its own. Overridable per view.");
    /** Primitives marked half resolution drawn at half size; false at full size, in the translucent pass. */
    public static final CgVar.Bool HALF_RESOLUTION = CgVar.bool("crystalgraphics.world.halfResolution", true, CgVar.DEV,
            "Primitives marked halfResolution draw at half size; false at full size, in the translucent pass (H in the"
                    + " harness). Overridable per view.");
    /** A draw whose program is not built yet is held, its build started, rather than compiled at the draw. */
    public static final CgVar.Bool SKIP_UNREADY = CgVar.bool("crystalgraphics.world.skipUnready", true, CgVar.DEV,
            "Holds a draw whose programs are not built yet, starting them and counting it as scene.passes.unready; false"
                    + " compiles at the draw. Overridable per view.");
    /** The distortion target's size as a share of the view's: 0.5 by default; 1 a texel a pixel. */
    public static final CgVar.Num DISTORTION_SCALE = CgVar.number("crystalgraphics.world.distortionScale", 0.5f, 0.1f, 1f,
            CgVar.DEV, "The distortion target's size as a share of the view's; 1 a texel a pixel. Overridable per view.");
    /** A glow's strength in the HDR scene: half, as strengths were set for a glow bloom spreads wide. */
    public static final CgVar.Num SCENE_EMISSION = CgVar.number("crystalgraphics.world.sceneEmission", 0.5f, 0f, 16f,
            CgVar.DEV, "What every glow is multiplied by in the HDR scene (cg_SceneGlow). Overridable per view.");
    /** Graded primitives draw into their look's layer; false keeps every draw in the scene's look. */
    public static final CgVar.Bool GRADE_DRAWS = CgVar.bool("crystalgraphics.world.gradeDraws", true, CgVar.DEV,
            "Primitives with a grade of their own draw into its layer; false keeps the scene's look. Overridable per view.");

    static final CgRenderState OPAQUE_STATE = CgRenderState.builder().depth(CgDepthState.TEST_WRITE).blend(CgBlendState.DISABLED).build();
    static final CgRenderState TRANSPARENT_STATE = CgRenderState.builder().depth(CgDepthState.TEST_ONLY).blend(CgBlendState.ALPHA).build();
    private static final CgFormat NORMAL_FORMAT = CgFormat.RGBA16_FLOAT;
    private static final CgFormat CUSTOM_DATA_FORMAT = CgFormat.RG32_FLOAT;
    /** Custom data cleared to DefaultLit's shading model: a pixel the host drew is a lit surface, as in Unreal's GBuffer. */
    private static final CgLoad HOST_LIT_CLEAR = CgLoad.clear(256f, 0f, 0f, 0f);
    private static final CgLoad STENCIL_CLEAR = CgLoad.CLEAR_DEPTH.andStencil(0);
    private static final String DEPTH_IN_SHADER = "crystalgraphics:shaders/world/depth_in.shader";

    private static final int OPAQUE_ZONE = CgTrace.name("scene.opaque"), TRANSLUCENT_ZONE = CgTrace.name("scene.translucent"),
            LIGHTS_ZONE = CgTrace.name("scene.lights"), MEDIA_ZONE = CgTrace.name("scene.media"),
            PASSES_ZONE = CgTrace.name("scene.translucentPasses");

    /** A view's normals target and the frame its opaque callback wrote it in. */
    private static final class Normals {
        CgGraphTexture target;
        long written = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<Normals> NORMALS = new CgViewState.Key<>("scene.normals", Normals::new);

    /**
     * A view's velocity target and the frame its solids wrote it in: kept, as the post chain reads it in a later firing than
     * the opaque callback's that writes it.
     */
    private static final class Velocity {
        CgGraphTexture target;
        long written = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<Velocity> VELOCITY = new CgViewState.Key<>("scene.velocity", Velocity::new);

    private static boolean shaderPackWarned, normalsRefusedWarned;

    private final CgScene scene;
    private final CgSceneColour colour = new CgSceneColour();
    private final CgSceneLights lights = new CgSceneLights(this::sceneLights);
    private final CgSceneMedia media = new CgSceneMedia();
    private final CgSceneGlows glows = new CgSceneGlows(this);
    private final CgHalfResolution half = new CgHalfResolution(this);
    private final CgLayerPasses layers = new CgLayerPasses(this);
    private final CgDistortion distortion = new CgDistortion(this);
    private final CgOverdraw overdraw = new CgOverdraw();
    private final CgCanvasLabels labels = new CgCanvasLabels("scene.labels");
    private long frame = Long.MIN_VALUE, labelsFrame = Long.MIN_VALUE;
    private boolean labelDepthDrawn;

    /** This firing's media and the transmittance the passes bind; null with nothing in the air. */
    @Nullable
    private CgGraphTexture mediaOut;
    private final Matrix4f toWorld = new Matrix4f();
    private final float[] casterTransform = new float[16];
    private final Matrix4f casterBasis = new Matrix4f();
    private final IdentityHashMap<CgRenderState, CgRenderState> customDataStates = new IdentityHashMap<>();
    /** A depth-writing state tested near-or-equal and writing none, by the state it came from. */
    private final IdentityHashMap<CgRenderState, CgRenderState> depthReadStates = new IdentityHashMap<>();
    @Nullable
    private CgMaterialInterface depthIn;
    /** The custom data and depth targets, kept while the view's size holds: a steady frame makes no handle. */
    @Nullable
    private CgGraphTexture customDataTarget, customDataDepth, customDepthTarget, velocityDepth, responsiveTarget;

    // The forms the passes are drawn through this firing
    private boolean linear, merge, layered;
    /** The all-opaque prepass's and the base pass's GPU time: what the prepass costs against the overdraw it saves. */
    private static final int GPU_PREPASS = CgGpuTrace.name("scene.prepass"), GPU_BASE = CgGpuTrace.name("scene.base");
    /**
     * Solid, queued before the translucent: the opaque callback's base pass, testing the prepass's depth near-or-equal
     * and writing none.
     */
    private final CgCommandForm opaque = (rows, b, p) -> queue(rows, b) < CgRenderQueue.TRANSPARENT_THRESHOLD ? depthRead(p) : null;
    /**
     * Every solid's depth, the lit solids queued after the lights too: the all-opaque prepass forward shading forces
     * (Unreal's DDM_AllOpaque), before the base pass and the ambient occlusion.
     */
    private final CgCommandForm allOpaquePrepass = (rows, b, p) ->
            queue(rows, b) < CgRenderQueue.TRANSPARENT_THRESHOLD || CgMaterialProxy.of(rows.material(b)).solid() ? p : null;
    /** The prepass with our normals beside its depth: what the world lights and GTAO read (Unreal's G-buffer normal). */
    private final CgCommandForm allOpaquePrepassNormals = (rows, b, p) -> {
        CgPipeline depth = allOpaquePrepass.form(rows, b, p);
        if (depth == null) return null;
        CgPipeline n = depth.as(CgPipelineForm.NORMALS);
        return n != null ? n : depth;
    };
    /** A translucent draw with a depth prepass ({@code "Depth" = "Prepass"}). */
    private final CgCommandForm translucentPrepass = (rows, b, p) ->
            queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD && CgMaterialProxy.of(rows.material(b)).hasDepthPrepass() ? p : null;
    /**
     * A solid queued with the translucent (a program lighting itself, drawn after the lights), on the prepass's depth as
     * the base pass is.
     */
    private final CgCommandForm lateSolid = (rows, b, p) -> {
        if (queue(rows, b) < CgRenderQueue.TRANSPARENT_THRESHOLD) return null;
        return CgMaterialProxy.of(rows.material(b)).solid() ? depthRead(p) : p;
    };
    /** The translucent passes: a glow folded into its colour where it merges, a graded draw's layer its second output. */
    private final CgCommandForm translucent = this::translucentForm;
    /** Custom data, tested against the scene's depth and writing none. */
    private final CgCommandForm customData = (rows, b, p) -> {
        if (queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD) return null;
        CgRenderState own = p.state(), tested = customDataStates.get(own);
        if (tested == null) customDataStates.put(own, tested = own.withDepth(CgDepthState.TEST_ONLY).withBlend(CgBlendState.DISABLED));
        return p.withState(tested);
    };

    /** Every scene's renderer, weakly: what teardown releases. */
    private static final Set<CgSceneRenderer> ALL = Collections.newSetFromMap(new WeakHashMap<>());

    /** Engine side: the scene's. */
    public CgSceneRenderer(CgScene scene) {
        this.scene = scene;
        labels.held(scene.primitives().heldLabels());
        media.add(new CgSceneMedia.Injector() {
            @Override
            public boolean inject(CgComputePass pass, CgSceneMedia m) {
                return scene.primitives().injectHaze(pass, m);
            }

            @Override
            public boolean holds() {
                return scene.primitives().holdsHaze();
            }
        });
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** The scene's light components, written into each frame's lights. */
    private void sceneLights(CgView view, CgLightCollector out) {
        scene.primitives().writeLights(out);
    }

    /** Engine side, once at the engine's start: the client world begins its frame at the host's camera hook. */
    public static void install() {
        CgScenePipelines.install();
        CgWorldBegin.listen(CgSceneRenderer::worldBegin);
    }

    /**
     * {@code WORLD_BEGIN}, from a host's camera hook: this frame's partial tick into the world callbacks' frame, whose
     * camera is still last frame's, and the client world up to its shakes, once a ring frame.
     */
    private static void worldBegin(float partialTick) {
        // Minecraft runs its camera hook for a frame or two after the engine has shut down
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        CgHostFrame host = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
        host.partialTick(partialTick);
        CgWorld.client().begin(host.camera(), CgFrameRing.frame());
    }

    public CgScene scene() {
        return scene;
    }

    /** The scene's lights: point, spot and tube lights, their shadows. Components at R13. */
    public CgSceneLights lights() {
        return lights;
    }

    /** What is in the scene's air: smoke, haze. */
    public CgSceneMedia media() {
        return media;
    }

    /**
     * The scene's labels, drawn into every view's translucent callback: its spawned label components, then what
     * {@code CgScene.labelOnce} queues this frame.
     */
    public CgCanvasLabels labels() {
        long now = CgFrameRing.frame();
        if (now != labelsFrame) {
            labels.clear();
            labelsFrame = now;
        }
        return labels;
    }

    /** Engine side, at context teardown: every scene's renderer drops what it holds of the context. */
    public static void releaseAll() {
        synchronized (ALL) {
            for (CgSceneRenderer renderer : ALL) renderer.release();
        }
    }

    private void release() {
        colour.release();
        half.release();
        distortion.release();
        labels.release();
        customDataStates.clear();
        depthIn = null;
        customDataTarget = customDataDepth = customDepthTarget = null;
    }

    // ── The opaque callback ───────────────────────────────────────────────────────────────────────

    /** The host's opaque callback, for the firing's view. */
    public void opaque(CgStageFiring firing) {
        CgView view = firing.view();
        beginFrame(view.camera());
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, OPAQUE_ZONE)) {
            CgViewPasses passes = passes(view);
            if (view.showFlags().post()) CgPostRenderer.prepare(view);
            firing.run(CgRenderStage.PREPARE);
            CgSceneTextures textures = view.textures();
            CgMeshBatches rows = passes.visibility().batches();
            CgRecording rec = firing.recording();
            if (textures.subjectRequested() && textures.subjectKey() != 0) glows.recordOwnedSubject(firing, passes, textures.subjectKey());
            Normals normals = view.state().get(NORMALS);
            if (rows.size() == 0) {
                normals.written = Long.MIN_VALUE;
                recordVelocity(firing, passes);
            } else {
                warnShaderPack();
                CgPostSettings post = view.showFlags().post() ? CgPostRenderer.settings(view) : null;
                boolean normalsWanted = textures.normalsRequested() || lights.wantsNormals()
                        || post != null && CgAmbientOcclusion.wantsNormals(view, post);
                CgGraphTexture n = normalsWanted ? normalsFor(firing) : null;
                if (n == null) normals.written = Long.MIN_VALUE;
                boolean opaqueScene = colour.opaqueIn(firing);
                cull(firing, passes);
                CgPassConstants constants = firing.constants().sceneGlow(view.flag(SCENE_EMISSION));
                // Every solid's depth first, then the screen's ambient occlusion from it, before any solid shades. A lit
                // solid draws its depth before the lights are handed it: none, until they are
                for (int b = 0, count = rows.size(); b < count; b++) {
                    if (CgMaterialProxy.of(rows.material(b)).handedLights()) lights.handNone(rows.material(b), rec);
                }
                CgDrawCommands prepass = passes.commands(CgEnginePasses.DEPTH, rec, n != null ? allOpaquePrepassNormals : allOpaquePrepass);
                if (prepass.size() > 0) {
                    CgRasterPass pass = scenePass(firing, "scene.prepass", constants, OPAQUE_STATE, false).timed(GPU_PREPASS);
                    if (n != null) pass.colorIfTaken(n, CgLoad.load());
                    pass.add(prepass).end();
                }
                recordVelocity(firing, passes);
                CgGraphTexture occlusion = post != null && prepass.size() > 0 && CgAmbientOcclusion.wants(view, post)
                        ? CgAmbientOcclusion.record(firing, rec, view, post, n) : null;
                CgDrawCommands base = passes.commands(CgEnginePasses.BASE, rec, opaque);
                if (base.size() > 0) {
                    CgRasterPass pass = scenePass(firing, "scene.base", constants, OPAQUE_STATE, true).timed(GPU_BASE);
                    if (occlusion != null) pass.texture(CgBindingModel.AMBIENT_OCCLUSION, occlusion);
                    pass.add(base).end();
                }
                if (opaqueScene) colour.opaqueOut(firing);
            }
            firing.run(CgRenderStage.OPAQUE);
        }
    }

    /** The client world's schedule and the parameter collections, once a ring frame, before anything records. */
    private void beginFrame(CgCamera camera) {
        long now = CgFrameRing.frame();
        if (now == frame) return;
        frame = now;
        CgLookIds.frame(now);
        if (scene != CgScene.client()) return;
        CgTraceRun.worldFrame();
        CgWorld.client().frame(camera, now);
        CgParameterCollection.upload();   // after the schedule, where effects and sequences set them
        CgScenePipelines.settle();
    }

    private CgViewPasses passes(CgView view) {
        return CgViewPasses.of(view, scene, view.camera()).skipUnready(view.flag(SKIP_UNREADY));
    }

    /** The view's instances and culled records culled on the GPU against its depth pyramid, where it has any: once a recording. */
    private static void cull(CgStageFiring firing, CgViewPasses passes) {
        if (passes.culls()) passes.cull(firing.recording(), firing.depthPyramid());
    }

    /**
     * The view's normal target, cleared, for the base pass to write beside the scene; null where the host's target takes
     * no second attachment, warned once: the lights then rebuild normals from depth.
     */
    @Nullable
    private CgGraphTexture normalsFor(CgStageFiring firing) {
        CgExternalTarget host = firing.host().target();
        if (!host.lendsAttachments()) {
            if (!normalsRefusedWarned) {
                normalsRefusedWarned = true;
                LOGGER.warn("{} takes no normal target: the lights rebuild our surfaces' normals from depth, snapped to"
                        + " world axes as blocks', so a curved mesh shades in facets. Draw the world into a framebuffer of"
                        + " the host's own", host);
            }
            return null;
        }
        CgView view = firing.view();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        Normals normals = view.state().get(NORMALS);
        CgRecording rec = firing.recording();
        if (normals.target == null || normals.target.getWidth() != w || normals.target.getHeight() != h) {
            if (normals.target != null) rec.release(normals.target);
            normals.target = CgGraphTexture.requested("cg_scene.normals", new CgTextureDesc(w, h, NORMAL_FORMAT));
        }
        rec.raster("scene.normalTarget").color(normals.target, CgLoad.CLEAR).constants(firing.constants()).end();
        normals.written = view.state().frame();
        return normals.target;
    }

    @Nullable
    private static CgGraphTexture normalsWritten(CgView view) {
        Normals normals = view.state().get(NORMALS);
        return normals.written == view.state().frame() ? normals.target : null;
    }

    // ── The translucent callback ──────────────────────────────────────────────────────────────────

    /** The host's translucent callback, for the firing's view; post and {@code OVERLAY} after. */
    public void translucent(CgStageFiring firing) {
        CgView view = firing.view();
        beginFrame(view.camera());
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, TRANSLUCENT_ZONE)) {
            recordTranslucent(firing, view);
        }
        firing.run(CgRenderStage.TRANSLUCENT);
        if (view.showFlags().post()) CgPostRenderer.record(firing);
        CgPostRenderer.present(firing);
        firing.run(CgRenderStage.OVERLAY);
    }

    private void recordTranslucent(CgStageFiring firing, CgView view) {
        CgViewPasses passes = passes(view);
        CgSceneTextures textures = view.textures();
        CgRecording rec = firing.recording();
        CgCamera camera = view.camera();
        CgMeshBatches rows = passes.visibility().batches();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        toWorld.set(camera.view()).invert();

        colour.sceneIn(firing);
        linear = textures.linear();
        textures.depth(firing.depth());
        merge = view.flag(MERGE_EMISSION) && CgScenePipelines.mergeApplied();
        CgPassConstants constants = firing.constants().sceneGlow(view.flag(SCENE_EMISSION));

        // Before any glow: a label's text then hides the glows behind it, as it hides their colour
        boolean showLabels = view.showFlags().labels() && scene.renderer() == this;
        if (showLabels) recordLabelDepth(firing);
        if (linear && view.flag(GRADE_DRAWS) && layers.looksReadScene(rows)) textures.requestCustomData();
        if (textures.customDataRequested()) recordCustomData(firing, passes);
        recordCustomDepth(firing, passes);
        recordVelocity(firing, passes);
        recordTranslucentVelocity(firing, passes);
        CgGraphTexture normals = normalsWritten(view);
        textures.normals(normals);

        boolean lit = view.showFlags().lights();
        try (CgTrace.Zone zl = CgTrace.zone(CgChannels.WORLD, LIGHTS_ZONE)) {
            if (lit) {
                lights.normals(normals);
                lights.record(firing, camera, toWorld, w, h);
                casters(rows);
                lights.apply(firing, rec, rows.size() > 0, surfaceLit(rows));
                handLights(rows, rec);
            }
        }
        try (CgTrace.Zone zm = CgTrace.zone(CgChannels.WORLD, MEDIA_ZONE)) {
            mediaOut = view.showFlags().media() && (rows.size() > 0 || media.holdsAny())
                    ? media.record(rec, firing.constants(), view.state(), camera, toWorld, w, h, lights) : null;
            if (mediaOut != null) media.composite(firing, rec, mediaOut);
            if (linear) glows.recordSceneGlows(firing, passes, constants, merge);
        }
        if (view.showFlags().decals()) {
            CgDrawCommands decals = passes.commands(CgEnginePasses.DECAL, rec);
            if (decals.size() > 0) {
                rec.raster("scene.decals").color(firing.target(), CgLoad.load()).readsDepth(firing.depth())
                        .constants(firing.constants()).add(decals).end();
            }
        }
        // The graded layers' solids before post's SURFACES: a graded look's own post materials grade them there
        layered = linear && view.flag(GRADE_DRAWS) && layers.prepare(firing, passes, constants);
        boolean post = view.showFlags().post();
        if (post) CgPostRenderer.surfaces(firing);
        firing.run(CgRenderStage.SURFACES);

        if (rows.size() > 0 && view.showFlags().translucency()) {
            try (CgTrace.Zone zp = CgTrace.zone(CgChannels.WORLD, PASSES_ZONE)) {
                translucentPasses(firing, passes, constants, linear);
            }
        }
        if (layered) layers.record(firing, passes, constants);
        if (textures.subjectRequested()) glows.recordSubject(firing, passes, textures.subjectKey());
        if (view.showFlags().overdraw() && rows.size() > 0) overdraw.record(firing, passes);
        if (showLabels) recordLabels(firing);
    }

    /** Late solids, half size, volumes, the bends, the translucent pass with its applies, after distortion, separate. */
    private void translucentPasses(CgStageFiring firing, CgViewPasses passes, CgPassConstants constants, boolean linear) {
        CgView view = firing.view();
        CgRecording rec = firing.recording();
        cull(firing, passes);
        CgDrawCommands prepass = passes.commands(CgEnginePasses.DEPTH, rec, translucentPrepass);
        if (prepass.size() > 0) scenePass(firing, "scene.translucentPrepass", constants, OPAQUE_STATE, false).add(prepass).end();
        CgDrawCommands late = passes.commands(CgEnginePasses.BASE, rec, lateSolid);
        if (late.size() > 0) {
            // The lit solids read the ambient occlusion the opaque callback ran from the prepass
            CgGraphTexture occlusion = view.read(CgAmbientOcclusion.RESULT);
            CgRasterPass pass = scenePass(firing, "scene.lateSolids", constants, OPAQUE_STATE, true);
            if (occlusion != null) pass.texture(CgBindingModel.AMBIENT_OCCLUSION, occlusion);
            pass.add(late).end();
        }

        boolean halfSize = view.flag(HALF_RESOLUTION);
        if (halfSize) half.recordHalf(firing, passes);
        CgGraphTexture volumes = halfSize ? half.recordVolumes(firing, passes, linear && merge) : null;
        int distorting = view.showFlags().distortion()
                ? distortion.plan(firing, passes, view.flag(DISTORTION_SCALE), translucent) : 0;
        if (distorting > 0) distortion.recordBends(firing, passes);

        CgDrawCommands main = passes.commands(CgEnginePasses.TRANSLUCENT, rec, translucent);
        if (distorting > 0) distortion.addApplies(main, passes, rec);
        if (volumes != null) half.addComposite(main, rec, volumes);
        if (distorting > 0 || volumes != null) main.sort();
        CgRasterPass pass = scenePass(firing, "scene.translucent", constants, TRANSPARENT_STATE, true);
        if (layered && layers.outputSlot() >= 0) pass.colorIfTaken(layers.outputTarget(), CgLoad.load());
        pass.add(main);
        if (!halfSize) {
            // Half-size draws drawn at full size, in the translucent pass
            pass.add(passes.commands(CgEnginePasses.HALF, rec, translucent));
            pass.add(passes.commands(CgEnginePasses.VOLUME, rec, translucent));
        }
        pass.end();
        if (distorting > 0) distortion.recordFinal(firing, passes);

        // After the hazes, unbent by them; separate translucency apart into a layer of its own under the HDR scene
        CgDrawCommands after = passes.commands(CgEnginePasses.AFTER_DISTORTION, rec,
                distorting > 0 ? distortion.afterForm() : translucent);
        boolean apart = linear && firing.host().target().lendsAttachments();
        CgDrawCommands separateInScene = apart ? null : passes.commands(CgEnginePasses.SEPARATE, rec, translucent);
        if (after.size() > 0 || separateInScene != null && separateInScene.size() > 0) {
            CgRasterPass afterPass = scenePass(firing, "scene.afterDistortion", constants, TRANSPARENT_STATE, true);
            if (layered && layers.outputSlot() >= 0) afterPass.colorIfTaken(layers.outputTarget(), CgLoad.load());
            afterPass.add(after);
            if (separateInScene != null) afterPass.add(separateInScene);
            afterPass.end();
        }
        if (apart) layers.recordSeparate(firing, passes, constants);
    }

    /**
     * A translucent row's command in this firing's pass. Its colour: folded with its glow where glows merge, its graded
     * layer written as a second output where the layer passes carry it, left out where it writes nothing (a pure haze,
     * whose cost is its Distortion pass). Its glow, which its processor put after it at its key: left out where it
     * folded, or where no HDR scene takes light.
     */
    @Nullable
    private CgPipeline translucentForm(CgMeshBatches rows, int b, CgPipeline p) {
        boolean glows = linear && emits(rows, b);
        if (p.pass() == CgRenderPassVariant.EMISSIVE) return glows && !(merge && folds(rows, b)) ? p : null;
        CgPipeline formed = p;
        if (glows && merge) {
            CgPipeline folded = p.as(CgPipelineForm.FOLD);
            if (folded != null) formed = folded;
        }
        if (formed == p && p.state().writesNothing()) return null;
        if (layered && layers.carries(rows, b)) {
            CgPipeline layer = formed.as(CgPipelineForm.LAYER);
            if (layer != null) formed = layer;
        }
        return formed;
    }

    /** Whether row {@code b}'s glow folds into its Forward draw: its program has the fold form. */
    public static boolean folds(CgMeshBatches rows, int b) {
        CgPipeline forward = CgMaterialProxy.of(rows.material(b)).pipeline(CgInstanceKind.OBJECT);
        return forward != null && forward.as(CgPipelineForm.FOLD) != null;
    }

    /** A pass into the firing's target and depth: our scene's draws, the lightmap and the air bound. */
    CgRasterPass scenePass(CgStageFiring firing, String name, CgPassConstants constants, CgRenderState state, boolean mediated) {
        CgRasterPass pass = firing.recording().raster(name).color(firing.target(), CgLoad.load())
                .depth(firing.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).state(state)
                .readsDepth(firing.depth()).readsColor(firing.target())
                .texture(CgBindingModel.LIGHTMAP, firing.view().hostTextures().lightmapTexture());
        if (mediated) mediated(pass);
        return pass;
    }

    /** Engine side, the features': {@code pass} reading this firing's media and local lights, where any. */
    public void mediated(CgRasterPass pass) {
        if (mediaOut != null) pass.texture(CgBindingModel.MEDIA, mediaOut);
        CgGraphTexture lit = lights.localLights();
        if (lit != null) pass.texture(CgBindingModel.LOCAL_LIGHTS, lit);
    }

    /** Whether row {@code b} glows: its material has an Emissive pass and its emission is above 0, below the overlay. */
    public static boolean emits(CgMeshBatches rows, int b) {
        return queue(rows, b) < CgRenderQueue.OVERLAY_THRESHOLD && emission(rows, b) > 0f
                && CgMaterialProxy.of(rows.material(b)).hasEmissivePass();
    }

    /** Row {@code b}'s emission scale: its own where a collector set it, else its primitive's. */
    public static float emission(CgMeshBatches rows, int b) {
        if (rows.objectId(b) == CgMeshBatches.NO_OBJECT && rows.instances(b) == null) {
            return rows.values()[b * CgMeshBatches.VALUES + CgMeshBatches.EMISSION];
        }
        return ((CgPrimitiveComponent<?>) rows.primitive(b)).emission();
    }

    /** Row {@code b}'s material queue. */
    public static int queue(CgMeshBatches rows, int b) {
        return rows.material(b).renderQueue();
    }

    /**
     * {@code p} on the all-opaque prepass's depth: a state writing depth tests near-or-equal and writes none (Unreal's
     * base pass under a full prepass, {@code DepthRead} and {@code CF_DepthNearOrEqual}); any other as it is.
     */
    private CgPipeline depthRead(CgPipeline p) {
        CgDepthState depth = p.state().getDepth();
        if (depth == null || !depth.write()) return p;
        CgRenderState own = p.state(), read = depthReadStates.get(own);
        if (read == null) depthReadStates.put(own, read = own.withDepth(CgDepthState.TEST_ONLY));
        return p.withState(read);
    }

    /** Whether a translucent row lights its surface by the scene's lights (a lit translucent material). */
    private static boolean surfaceLit(CgMeshBatches rows) {
        for (int b = 0, n = rows.size(); b < n; b++) {
            if (queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD && CgMaterialProxy.of(rows.material(b)).surfaceLit()) return true;
        }
        return false;
    }

    /** The light grid and the sun's map bound to every drawn material reading what it is handed of them. */
    private void handLights(CgMeshBatches rows, CgRecording rec) {
        for (int b = 0, n = rows.size(); b < n; b++) {
            CgMaterialInterface material = rows.material(b);
            CgMaterialProxy proxy = CgMaterialProxy.of(material);
            if (proxy.handedLights()) lights.hand(material, rec);
            if (proxy.handedSun()) lights.handSun(material, true);
        }
    }

    /**
     * The view's solids as the lights' shadow casters, each traced through its mesh's distance field (Unreal's
     * bCastShadow): rows whose geometry the CPU sees, casting shadows. Instances, GPU-fed rows, half-size rows and
     * skinned rows (their bind pose is not where they stand) cast none.
     */
    private void casters(CgMeshBatches rows) {
        for (int b = 0, n = rows.size(); b < n; b++) {
            if ((rows.flags(b) & (CgMeshBatches.CASTS_SHADOW | CgMeshBatches.HALF_RESOLUTION)) != CgMeshBatches.CASTS_SHADOW
                    || rows.instances(b) != null || rows.count(b) != null || rows.records(b) != null || rows.bones(b) >= 0) continue;
            if (queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD && !CgMaterialProxy.of(rows.material(b)).solid()) continue;
            CgMesh mesh = rows.mesh(b);
            double x, y, z;
            if (rows.objectId(b) == CgMeshBatches.NO_OBJECT) {
                float[] v = rows.values();
                int at = b * CgMeshBatches.VALUES;
                casterBasis.set(v[at], v[at + 1], v[at + 2], 0f, v[at + 3], v[at + 4], v[at + 5], 0f, v[at + 6], v[at + 7], v[at + 8], 0f,
                        0f, 0f, 0f, 1f);
                x = rows.position(b, 0);
                y = rows.position(b, 1);
                z = rows.position(b, 2);
            } else {
                CgPrimitiveComponent<?> p = (CgPrimitiveComponent<?>) rows.primitive(b);
                Matrix4dc world = p.worldTransform();
                p.basis(casterBasis);
                x = world.m30();
                y = world.m31();
                z = world.m32();
            }
            casterBasis.get(casterTransform);
            lights.caster(mesh, rows.submesh(b), 0, -1, x, y, z, casterTransform, 0);
        }
    }

    /**
     * Unreal's custom data and shading model (GBufferD, GBufferB's model), which our forward passes write nowhere: every
     * solid in view whose material writes them drawn again into an RG32F target beside the scene's depth, tested against
     * it and writing none. A host lending no depth gets a target with a depth of its own, the host's copied in first.
     */
    private void recordCustomData(CgStageFiring firing, CgViewPasses passes) {
        CgView view = firing.view();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        CgFormat depth = firing.host().target().lendsAttachments() ? null : firing.host().depthFormat();
        CgRecording rec = firing.recording();
        if (customDataTarget == null || customDataTarget.getWidth() != w || customDataTarget.getHeight() != h
                || customDataDepth.isCurrent() == (depth != null)) {
            customDataTarget = CgGraphTexture.transientTexture("cg_custom_data", CgTextureDesc.of(w, h, CUSTOM_DATA_FORMAT));
            customDataDepth = depth != null ? CgGraphTexture.transientTexture("cg_custom_data_depth", CgTextureDesc.of(w, h, depth))
                    : CgGraphTexture.current();
        }
        CgGraphTexture target = customDataTarget, targetDepth = customDataDepth;
        if (depth != null) seedDepth(firing, "scene.customDataDepth", CgGraphTexture.current(), targetDepth, STENCIL_CLEAR);
        rec.raster("scene.customData").color(target, HOST_LIT_CLEAR).constants(firing.constants())
                .depth(targetDepth, CgLoad.load(), CgDepthAccess.WRITE)
                .add(passes.commands(CgEnginePasses.CUSTOM_DATA, rec, customData)).end();
        view.textures().customData(target);
    }

    /**
     * Unreal's velocity pass in its depth pass ({@code r.VelocityOutputPass 0}), which then renders every frame whatever
     * asks for it: once a frame, as soon as the depth holds every solid, what moved or deformed this frame writes where
     * it was last frame into an RG16 target cleared to 0; the rest stood still, its motion the camera's over its depth.
     * With still solids asked ({@code requestVelocityWithStill}), every engine solid writes, the still ones zero. The
     * opaque callback runs it after the prepass, so ambient occlusion reads it; a frame whose opaque callback did not
     * run gets it in the translucent. Where the host's target lends no attachment, nothing.
     */
    private void recordVelocity(CgStageFiring firing, CgViewPasses passes) {
        CgView view = firing.view();
        if (!firing.host().target().lendsAttachments()) return;
        Velocity velocity = view.state().get(VELOCITY);
        if (velocity.written == view.state().frame()) return;
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        CgRecording rec = firing.recording();
        if (velocity.target == null || velocity.target.getWidth() != w || velocity.target.getHeight() != h) {
            if (velocity.target != null) rec.release(velocity.target);
            velocity.target = CgGraphTexture.requested("cg_velocity", CgTextureDesc.of(w, h, CgEnginePasses.VELOCITY_FORMAT));
        }
        rec.raster("scene.velocity").color(velocity.target, CgLoad.clear(0f, 0f, 0f, 0f))
                .depth(firing.depth(), CgLoad.load(), CgDepthAccess.READ).constants(firing.constants())
                .add(passes.commands(view.textures().velocityWithStill() ? CgEnginePasses.VELOCITY_WITH_STILL
                        : CgEnginePasses.VELOCITY, rec)).end();
        velocity.written = view.state().frame();
        view.textures().velocity(velocity.target);
    }

    /** This frame's velocity target, once the solids wrote it; null before, or where the host lends no attachment. */
    @Nullable
    private static CgGraphTexture velocityWritten(CgView view) {
        Velocity velocity = view.state().get(VELOCITY);
        return velocity.written == view.state().frame() ? velocity.target : null;
    }

    /**
     * After the solids' velocity, in the translucent callback: what blends with {@code OutputVelocity On} writes its
     * velocity and depth where it covers, over a copy of the scene's depth (Unreal's early translucency velocity, which
     * leaves the depth the translucent pass tests against as it was); and with still solids asked, what blends marks the
     * responsive target where it covers (Unreal's Responsive AA).
     */
    private void recordTranslucentVelocity(CgStageFiring firing, CgViewPasses passes) {
        CgView view = firing.view();
        CgGraphTexture velocityTarget = velocityWritten(view);
        if (velocityTarget == null) return;
        view.textures().velocity(velocityTarget);
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        recordBlendVelocity(firing, passes, velocityTarget, w, h);
        if (!view.textures().velocityWithStill()) return;
        if (responsiveTarget == null || responsiveTarget.getWidth() != w || responsiveTarget.getHeight() != h) {
            responsiveTarget = CgGraphTexture.transientTexture("cg_responsive", CgTextureDesc.of(w, h, CgEnginePasses.RESPONSIVE_FORMAT));
        }
        firing.recording().raster("scene.responsive").color(responsiveTarget, CgLoad.clear(0f, 0f, 0f, 0f))
                .depth(firing.depth(), CgLoad.load(), CgDepthAccess.READ).constants(firing.constants())
                .add(passes.commands(CgEnginePasses.RESPONSIVE, firing.recording())).end();
        view.textures().responsive(responsiveTarget);
    }

    private void recordBlendVelocity(CgStageFiring firing, CgViewPasses passes, CgGraphTexture velocityTarget, int w, int h) {
        CgDrawCommands blends = passes.commands(CgEnginePasses.TRANSLUCENT_VELOCITY, firing.recording());
        if (blends.size() == 0) return;
        if (velocityDepth == null || velocityDepth.getWidth() != w || velocityDepth.getHeight() != h) {
            velocityDepth = CgGraphTexture.transientTexture("cg_velocity_depth",
                    CgTextureDesc.of(w, h, CgEnginePasses.TRANSLUCENT_VELOCITY.layout().depth()));
        }
        seedDepth(firing, "scene.velocityDepth", firing.depth(), velocityDepth, CgLoad.CLEAR_DEPTH);
        firing.recording().raster("scene.translucentVelocity").color(velocityTarget, CgLoad.load())
                .depth(velocityDepth, CgLoad.load(), CgDepthAccess.WRITE).constants(firing.constants()).add(blends).end();
        firing.view().textures().velocityDepth(velocityDepth);
    }

    /** {@code from}'s depth drawn into {@code into}, a pass of its own: mesh-pass commands cannot share a pass with a draw. */
    private void seedDepth(CgStageFiring firing, String name, CgGraphTexture from, CgGraphTexture into, CgLoad clear) {
        if (depthIn == null) depthIn = CgMaterial.load(DEPTH_IN_SHADER).instance();
        CgRasterPass pass = firing.recording().raster(name).depth(into, clear, CgDepthAccess.WRITE).constants(firing.constants());
        pass.readsDepth(from);
        pass.draw(CgSceneColour.FULLSCREEN, depthIn);
        pass.end();
    }

    /** Unreal's CustomDepth: the primitives marked {@code customDepth}, depth alone, writing their stencil value. */
    private void recordCustomDepth(CgStageFiring firing, CgViewPasses passes) {
        CgDrawCommands marked = passes.commands(CgEnginePasses.CUSTOM_DEPTH, firing.recording());
        if (marked.size() == 0) return;
        CgView view = firing.view();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        if (customDepthTarget == null || customDepthTarget.getWidth() != w || customDepthTarget.getHeight() != h) {
            customDepthTarget = CgGraphTexture.transientTexture("cg_custom_depth",
                    CgTextureDesc.of(w, h, CgEnginePasses.CUSTOM_DEPTH.layout().depth()));
        }
        CgGraphTexture target = customDepthTarget;
        firing.recording().raster("scene.customDepth").depth(target, STENCIL_CLEAR, CgDepthAccess.WRITE)
                .constants(firing.constants()).add(marked).end();
        view.textures().customDepth(target);
    }

    // ── Labels ────────────────────────────────────────────────────────────────────────────────────

    /** Places the frame's labels and draws their text into the depth, before any glow and translucent draw. */
    private void recordLabelDepth(CgStageFiring firing) {
        labelDepthDrawn = false;
        CgCanvasLabels l = labels();
        if (!l.any()) return;
        l.prepare(firing.recording(), firing.view().camera(), firing.view().width(), firing.view().height());
        labelPass(firing, true);
        labelDepthDrawn = true;
    }

    /** The frame's labels' colour, after every translucent draw. */
    private void recordLabels(CgStageFiring firing) {
        CgCanvasLabels l = labels();
        if (!l.any()) return;
        if (!labelDepthDrawn) {
            l.prepare(firing.recording(), firing.view().camera(), firing.view().width(), firing.view().height());
            labelPass(firing, true);
        }
        labelDepthDrawn = false;
        labelPass(firing, false);
    }

    private void labelPass(CgStageFiring firing, boolean depth) {
        CgGraphTexture target = firing.target();
        CgRasterPass pass = firing.recording().raster("scene.labels").color(target, CgLoad.load())
                .constants(firing.constants()).order(CgOrder.LOOKBACK);
        // The host's own target carries its depth: labels test and write it there
        if (target.kind() == CgGraphTexture.Kind.EXTERNAL) pass.depth(target, CgLoad.load(), CgDepthAccess.WRITE);
        if (depth) labels.drawDepth(pass);
        else labels.draw(pass, target);
        pass.end();
    }

    private static void warnShaderPack() {
        if (shaderPackWarned || !CgShaderPacks.packInUse()) return;
        shaderPackWarned = true;
        LOGGER.warn("A shader pack is active in " + CgShaderPacks.mod() + ": the scene renderer draws into the main framebuffer,"
                + " outside its deferred G-buffer, so its geometry is unlit under a deferred pack. cg_DepthBuffer remains valid.");
    }
}
