package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.look.CgLookWeights;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.aa.CgTemporalGlow;
import com.crystalgraphics.render.post.tsr.CgTemporalSuperResolution;
import com.crystalgraphics.render.post.upscale.CgSpatialUpscale;
import com.crystalgraphics.render.post.upscale.CgUpscaler;
import com.crystalgraphics.render.view.CgViewRenderTarget;
import com.crystalgraphics.render.post.composite.CgCompositeFeature;
import com.crystalgraphics.render.post.dof.CgDepthOfField;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.post.look.CgPostLooks;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.render.post.debug.CgPostDebug;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine side, the scene renderer's: runs a view's {@link CgPost} in the frame's order (Unreal's
 * {@code AddPostProcessingPasses}). Three calls, each where the frame stands:
 *
 * <pre>{@code
 * CgPostRenderer.prepare(view);       // the opaque callback: the scene's volumes blended, the textures asked for
 * CgPostRenderer.surfaces(firing);    // on the lit opaque scene, before the SURFACES stage
 * CgPostRenderer.record(firing);      // after the TRANSLUCENT stage: the chain, its last step into the host's target
 * CgPostRenderer.present(firing);     // before OVERLAY: a view drawn below 100% upscaled into its host's target
 * }</pre>
 *
 * <p>The chain, in order: {@code SURFACES} (when {@link #surfaces} could not run it), {@code TRANSLUCENT}, depth of
 * field, separate translucency composed, temporal anti-aliasing (and, for the engine's bloom, its glow: the light past
 * bloom's threshold per jittered frame, averaged, so {@code SCENE}'s effects do not feed it), {@code SCENE}, bloom taken
 * from the scene, {@code HDR}, the composite (tonemapper, grade, looks,
 * encoding into the host's target), {@code DISPLAY}, the crossfade, the debug view; a picture left in another texture
 * is copied into the host's target last. A view drawn below 100% runs it all at the size it drew at, into the target
 * standing in for its host's ({@code CgViewRenderTarget}), and {@link #present} upscales that into the host's target
 * (Unreal's {@code PrimaryUpscale}).</p>
 */
public final class CgPostRenderer {

    /** A view's volumes blended at its camera this frame (Unreal's {@code FinalPostProcessSettings}): its own, per view. */
    private static final CgViewState.Key<CgPostSettings> SETTINGS =
            new CgViewState.Key<>("crystalgraphics:post.settings", CgPostSettings::new);
    /** A texel-for-texel copy: a picture the chain ends in another texture, into the host's target. */
    private static final String COPY = "crystalgraphics:shaders/post/hold.shader";
    private static final String COMPOSITE_COPY = "crystalgraphics:shaders/post/composite_copy.shader";
    /**
     * The composite's passes, each in every keyword set a setting can switch to, as its shader's permutation domain
     * names them (the HDR scene, bloom, a fade); the HDR scene's way in; and the distortion's bend, which a tier below
     * Medium never draws. The looks' keywords follow volumes, not settings: each volume registers its own as it is
     * made, and an asset's preparer requests them ahead of it ({@link #lookPasses}).
     */
    private static final String[] WARMED = {
            "crystalgraphics:shaders/world/scene_in.shader", "crystalgraphics:shaders/world/scene_out.shader",
            "crystalgraphics:shaders/post/distortion_bend.shader", CgTranslucencyComposition.SHADER,
            "crystalgraphics:shaders/post/composite.shader", COMPOSITE_COPY, COPY, CgDepthOfField.SHADERS[0],
            CgDepthOfField.SHADERS[1], CgDepthOfField.SHADERS[2], CgDepthOfField.SHADERS[3], CgDepthOfField.SHADERS[4],
            CgTemporalAA.SHADER, CgTemporalAA.OUTPUT, CgTemporalGlow.SHADER, CgViewRenderTarget.fillShader()};

    private static boolean installed;
    @Nullable
    private static CgMaterialInstance copy;
    /** The look sets a volume has registered for precaching: each once a session. */
    private static final Set<String> lookSets = ConcurrentHashMap.newKeySet();

    private CgPostRenderer() {
    }

    // ── Building ahead ────────────────────────────────────────────────────────────────────────────

    /** Requests the chain's programs and kernels for the precache, once. The engine calls it when it starts. */
    public static void install() {
        if (installed) return;
        installed = true;
        // A step a shader: each reads and parses its file to name its permutations, together 100 ms and more of a frame
        CgPipelinePrecache.Steps steps = CgPipelinePrecache.steps("crystalgraphics:post");
        for (String pass : WARMED) steps.step(pipelines -> pipelines.addPass(pass));
        steps.step(CgSpatialUpscale::precache);
        // The bloom's and the tonemapper's table's kernels: their files parsed on a worker first
        steps.ahead(() -> CgKernel.ahead(CgPostRenderer::precacheKernels)).step(list -> list.kernels(CgPostRenderer::precacheKernels));
        steps.start();
    }

    private static void precacheKernels() {
        CgBloom.precache();
        CgTemporalSuperResolution.precache();
        CgFilmLut.precache();
        CgLookWeights.precache();
    }

    /**
     * Adds the composite passes a volume of {@code settings} draws with: its looks, in every keyword set of the
     * composite's permutation domain. A preparer requests them before the volume plays, so the frame it turns a look on
     * compiles nothing; nothing for settings that switch no look on.
     *
     * <pre>{@code
     * CgPipelinePrecache.Request looks = CgPipelinePrecache.request(p -> CgPostRenderer.lookPasses(settings, p));
     * }</pre>
     */
    public static void lookPasses(CgPostSettings settings, CgPipelineList pipelines) {
        String[] keywords = lookKeywords(settings);
        if (keywords != null) pipelines.addPass(COMPOSITE_COPY, keywords);
    }

    /** A volume's look, built ahead as Unreal precaches a component's pipelines when it registers: each set once. */
    public static void precacheLook(CgPostSettings settings) {
        String[] keywords = lookKeywords(settings);
        if (keywords == null) return;
        String key = String.join(",", keywords);
        if (lookSets.add(key)) CgPipelinePrecache.register("crystalgraphics:post volume " + key, p -> p.addPass(COMPOSITE_COPY, keywords));
    }

    /** The composite's look keywords {@code settings} switch on; null for none. */
    @Nullable
    private static String[] lookKeywords(CgPostSettings settings) {
        EnumSet<CgCompositeFeature> on = CgPostLooks.features(settings);
        if (on.isEmpty()) return null;
        List<String> keywords = new ArrayList<>();
        for (CgCompositeFeature f : on) keywords.add(f.keyword());
        if (CgPostLooks.drawsImpact(settings)) keywords.add("SUBJECT");
        return keywords.toArray(new String[0]);
    }

    /** Every chain forgets what it made on the GPU. At context teardown. */
    public static void releaseAll() {
        CgPost.releaseAll();
        copy = null;
    }

    // ── The frame ─────────────────────────────────────────────────────────────────────────────────

    /**
     * At a view's opaque callback, before {@code PREPARE}: blends its scene's volumes at its camera, and asks its
     * textures for what this frame's effects read (a drawn impact frame's subject, the scene textures a post material
     * is handed).
     */
    public static void prepare(CgView view) {
        CgPostSettings settings = view.state().get(SETTINGS);
        settings.reset();
        settings.blend(view.post().settings(), 1f, 0.5f, 0.5f);
        view.scene().primitives().blendPostVolumes(view.camera(), settings);
        settings.blend(view.post().camera(), 1f, 0.5f, 0.5f);
        CgSceneTextures textures = view.textures();
        // The temporal step's velocity, and its jitter from here to the chain's end: the world's passes, not the
        // overlay's. Its sequence, and the material mip bias, follow how far it rebuilds the picture
        boolean temporal = view.showFlags().temporalAA();
        // Every engine solid writes, the still ones zero: temporal AA keeps to the texels the engine drew
        CgPostDebug debug = view.post().debug();
        if (temporal || debug != null && debug.showsVelocity()) textures.requestVelocityWithStill();
        CgUpscaler upscaler = view.post().upscaler(view.antiAliasing());
        float fraction = view.resolutionFraction();
        boolean rebuilds = temporal && upscaler.rebuilds(fraction);
        view.jittering(temporal && view.jitters(), upscaler.jitterSamples(rebuilds ? fraction : 1f));
        view.materialMipBias(rebuilds ? upscaler.materialMipBias(fraction) : 0f);
        if (CgPostLooks.drawsImpact(settings)) textures.requestSubject(settings.impactSubject());
        if (readsScene(view.post(), settings)) {
            textures.requestNormals();
            textures.requestCustomData();
        }
    }

    /**
     * Whether an added post material or a blendable that shows reads the scene textures it is handed
     * ({@code env/handed/scene.glsl}).
     */
    private static boolean readsScene(CgPost post, CgPostSettings settings) {
        for (CgPost.Entry e : post.entries) {
            if (e.effect() instanceof CgPostMaterial m && m.weight() > 0f && CgMaterialProxy.of(m.material()).handedScene()) return true;
        }
        for (int i = 0; i < settings.drawnBlendables(); i++) {
            if (CgMaterialProxy.of(settings.drawnBlendable(i).material()).handedScene()) return true;
        }
        return false;
    }

    /**
     * Just before the {@code SURFACES} stage: the {@code SURFACES} effects on the lit opaque HDR scene. With no HDR scene
     * yet (its programs unbuilt) they run with the rest.
     */
    public static void surfaces(CgStageFiring firing) {
        CgView view = firing.view();
        CgPost post = view.post();
        post.ranSurfaces = false;
        CgPost.Entry[] entries = post.entries;
        if (!view.textures().linear()) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.surfaces")) {
            post.context.begin(firing, view.state().get(SETTINGS));
            run(post, CgPostPoint.SURFACES, entries, 0, scene(firing));
            beforeTranslucency(post, view, scene(firing));
            post.ranSurfaces = true;
        }
    }

    /** The temporal upscaler's look at the scene before translucency ({@link CgUpscaler#beforeTranslucency}). */
    private static void beforeTranslucency(CgPost post, CgView view, CgScreenTexture scene) {
        if (CgTemporalAA.shows(view)) post.upscaler(view.antiAliasing()).beforeTranslucency(post.context, scene);
    }

    /** After the {@code TRANSLUCENT} stage: the chain, its last step into the host's target, every later stage's again. */
    public static void record(CgStageFiring firing) {
        CgView view = firing.view();
        CgPost post = view.post();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.record")) {
            CgPostContext ctx = post.context;
            ctx.begin(firing, view.state().get(SETTINGS));
            post.composite.begin();
            CgPost.Entry[] entries = post.entries;
            CgScreenTexture picture = scene(firing);
            int i = 0;
            if (post.ranSurfaces) {
                while (i < entries.length && entries[i].point() == CgPostPoint.SURFACES) i++;
            } else {
                i = run(post, CgPostPoint.SURFACES, entries, 0, picture);
                beforeTranslucency(post, view, picture);
            }
            post.ranSurfaces = false;
            i = run(post, CgPostPoint.TRANSLUCENT, entries, i, picture);
            ctx.input(picture);
            if (post.depthOfField.shows(ctx)) {
                try (CgTrace.Zone dof = CgTrace.zone(CgChannels.WORLD, "post.depthOfField")) {
                    picture = post.depthOfField.apply(ctx, picture);
                }
                ctx.input(picture);
            }
            CgUpscaler upscaler = CgTemporalAA.shows(view) ? post.upscaler(view.antiAliasing()) : null;
            post.translucency.compose(ctx, picture, upscaler == null || !upscaler.composesTranslucency());
            post.lookWeights.record(ctx, picture, upscaler != null && upscaler.composesTranslucency());
            CgGraphTexture glow = null;
            if (upscaler != null) {
                try (CgTrace.Zone taa = CgTrace.zone(CgChannels.WORLD, "post.temporal")) {
                    CgScreenTexture jittered = picture;
                    picture = upscaler.apply(ctx, picture);
                    // Bloom's light, taken per jittered frame and averaged: past the resolve, a light thinner than a texel
                    // falls under bloom's threshold at its coverage and loses its glow
                    if (post.bloom.shows(ctx) && CgBloom.takesExcess(ctx)) {
                        glow = upscaler.bloomLight(ctx, jittered, picture, CgBloom.excessThreshold(ctx));
                    }
                    // Answered at the output size: what records after runs there
                    if (upscaler.presents(view.resolutionFraction()) && firing.scaled()) firing.present();
                }
                ctx.input(picture);
            }
            picture = carry(post, CgPostPoint.SCENE, entries, i, picture);
            i = skip(CgPostPoint.SCENE, entries, i);

            CgSceneTextures textures = view.textures();
            boolean linear = textures.linear();
            if (linear && post.bloom.shows(ctx)) post.bloom.record(ctx, picture.texture(), glow, post.composite);
            picture = carry(post, CgPostPoint.HDR, entries, i, picture);
            i = skip(CgPostPoint.HDR, entries, i);

            post.looks.feed(ctx, post.composite);
            CgGraphTexture host;
            if (linear) {
                // The composite encodes the scene into the host's target, which is every later stage's again.
                post.composite.scene(picture.texture());
                firing.retarget(null, null);
                textures.sceneColor(null, false);
                host = firing.target();
            } else {
                host = firing.target();
                settle(ctx, picture, host);
            }
            post.composite.record(firing.recording(), host, firing.constants());
            picture = carry(post, CgPostPoint.DISPLAY, entries, i, sized(host, firing));
            ctx.input(picture);
            picture = post.crossfade.apply(ctx, picture);
            ctx.input(picture);
            CgPostDebug debug = post.debug();
            if (debug != null) picture = debug.apply(ctx, picture);
            settle(ctx, picture, host);
            view.jittering(false);
        }
    }

    /**
     * Before the {@code OVERLAY} stage, after {@link #record} or in its place: a view drawn below 100%, its picture
     * finished at the size it drew at in the target standing in for its host's, upscaled into the host's target by its
     * {@code upscaleFilter} (Unreal's {@code PrimaryUpscale}). {@code OVERLAY} then draws there at the full size. Nothing
     * for a view drawn at 100%, or one a temporal upscaler already presented.
     */
    public static void present(CgStageFiring firing) {
        if (!firing.scaled() || firing.presented()) return;
        CgView view = firing.view();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.upscale")) {
            firing.retarget(null, null);
            int w = view.width(), h = view.height();
            firing.present();
            view.post().spatialUpscale.record(firing.recording(), firing.constants(), firing.output(), view.upscaleFilter(), w, h);
        }
    }

    /** The firing's target as a picture at the view's size: the HDR scene, or the host's target without one. */
    private static CgScreenTexture scene(CgStageFiring firing) {
        return sized(firing.target(), firing);
    }

    /** {@code texture} at the size the firing draws at: the view's render size, its output's once presented. */
    private static CgScreenTexture sized(CgGraphTexture texture, CgStageFiring firing) {
        CgView view = firing.view();
        boolean out = firing.presented();
        return CgScreenTexture.sized(texture, Math.max(1, out ? view.outputWidth() : view.width()),
                Math.max(1, out ? view.outputHeight() : view.height()));
    }

    /** Runs what is at {@code point} on the scene, each answering its input; where the next point's effects start. */
    private static int run(CgPost post, CgPostPoint point, CgPost.Entry[] entries, int from, CgScreenTexture scene) {
        walk(post, point, entries, from, scene, true);
        return post.next;
    }

    /** Runs what is at {@code point} in turn, each given the one before's answer; the last answer. */
    private static CgScreenTexture carry(CgPost post, CgPostPoint point, CgPost.Entry[] entries, int from, CgScreenTexture picture) {
        return walk(post, point, entries, from, picture, false);
    }

    /**
     * The effects added at {@code point} (from {@code from} in {@code entries}) and the view's blendables there, by
     * order and priority, ascending, an added effect first on a tie; each blendable drawn over the picture at its weight.
     * Leaves where the next point's effects start in {@code post.next}.
     */
    private static CgScreenTexture walk(CgPost post, CgPostPoint point, CgPost.Entry[] entries, int from,
                                        CgScreenTexture picture, boolean onScene) {
        CgPostContext ctx = post.context;
        CgPostSettings s = ctx.settings();
        int blendables = blendablesAt(post, s, point), b = 0, i = from;
        while (true) {
            boolean effect = i < entries.length && entries[i].point() == point;
            if (!effect && b == blendables) break;
            ctx.input(picture);
            if (effect && (b == blendables || entries[i].order() <= s.drawnBlendable(post.order[b]).priority())) {
                CgScreenTexture answer = entries[i].effect().apply(ctx, picture);
                if (onScene && answer != picture) {
                    throw new IllegalStateException("a post effect at " + point + " answered another texture than its input:"
                            + " the scene renderer goes on drawing into it (" + entries[i].effect() + ")");
                }
                picture = answer;
                i++;
            } else {
                int k = post.order[b++];
                CgPostMaterial drawer = s.drawnBlendable(k).drawer();
                drawer.weight(s.drawnWeight(k));
                if (drawer.shows(ctx)) drawer.draw(ctx, picture);
            }
        }
        post.next = i;
        return picture;
    }

    /** The view's blendables at {@code point} into {@code post.order}, by priority, stable; how many. */
    private static int blendablesAt(CgPost post, CgPostSettings s, CgPostPoint point) {
        int n = 0, all = s.drawnBlendables();
        if (post.order.length < all) post.order = new int[Math.max(8, all * 2)];
        int[] order = post.order;
        for (int k = 0; k < all; k++) {
            if (s.drawnBlendable(k).point() != point) continue;
            int at = n++, p = s.drawnBlendable(k).priority();
            while (at > 0 && s.drawnBlendable(order[at - 1]).priority() > p) {
                order[at] = order[at - 1];
                at--;
            }
            order[at] = k;
        }
        return n;
    }

    private static int skip(CgPostPoint point, CgPost.Entry[] entries, int from) {
        int i = from;
        while (i < entries.length && entries[i].point() == point) i++;
        return i;
    }

    /** Copies {@code picture} into {@code host} where an effect left it elsewhere (Unreal's last {@code AddDrawTexturePass}). */
    private static void settle(CgPostContext ctx, CgScreenTexture picture, CgGraphTexture host) {
        if (picture.texture() == host) return;
        if (copy == null) copy = CgMaterial.load(COPY).instance();
        ctx.screen(copy).name("post.copy").from(picture).over(sized(host, ctx.firing()));
    }
}
