package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.composite.CgCompositeFeature;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.post.look.CgPostLooks;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

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
 * }</pre>
 *
 * <p>The chain, in order: {@code SURFACES} (when {@link #surfaces} could not run it), {@code TRANSLUCENT}, separate
 * translucency composed, {@code SCENE}, bloom taken from the scene, {@code HDR}, the composite (tonemapper, grade, looks,
 * encoding into the host's target), {@code DISPLAY}, the crossfade, the debug view; a picture left in another texture
 * is copied into the host's target last.</p>
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
            "crystalgraphics:shaders/post/composite.shader", COMPOSITE_COPY, COPY};

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
        // The bloom's and the tonemapper's table's kernels: their files parsed on a worker first
        steps.ahead(() -> CgKernel.ahead(CgPostRenderer::precacheKernels)).step(list -> list.kernels(CgPostRenderer::precacheKernels));
        steps.start();
    }

    private static void precacheKernels() {
        CgBloom.precache();
        CgFilmLut.precache();
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
        view.scene().primitives().blendPostVolumes(view.camera(), settings);
        CgSceneTextures textures = view.textures();
        if (CgPostLooks.drawsImpact(settings)) textures.requestSubject(settings.impactSubject());
        if (readsScene(view.post())) {
            textures.requestNormals();
            textures.requestCustomData();
        }
    }

    /** Whether an added post material that shows reads the scene textures it is handed ({@code env/handed/scene.glsl}). */
    private static boolean readsScene(CgPost post) {
        for (CgPost.Entry e : post.entries) {
            if (e.effect() instanceof CgPostMaterial m && m.weight() > 0f && CgMaterialProxy.of(m.material()).handedScene()) return true;
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
        if (entries.length == 0 || entries[0].point() != CgPostPoint.SURFACES || !view.textures().linear()) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.surfaces")) {
            post.context.begin(firing, view.state().get(SETTINGS));
            run(post, CgPostPoint.SURFACES, entries, 0, scene(firing));
            post.ranSurfaces = true;
        }
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
            }
            post.ranSurfaces = false;
            i = run(post, CgPostPoint.TRANSLUCENT, entries, i, picture);
            ctx.input(picture);
            post.translucency.compose(ctx, picture);
            picture = carry(post, CgPostPoint.SCENE, entries, i, picture);
            i = skip(CgPostPoint.SCENE, entries, i);

            CgSceneTextures textures = view.textures();
            boolean linear = textures.linear();
            if (linear && post.bloom.shows(ctx)) post.bloom.record(ctx, picture.texture(), post.composite);
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
            picture = carry(post, CgPostPoint.DISPLAY, entries, i, sized(host, view));
            ctx.input(picture);
            picture = post.crossfade.apply(ctx, picture);
            ctx.input(picture);
            if (post.debug != null) picture = post.debug.apply(ctx, picture);
            settle(ctx, picture, host);
        }
    }

    /** The firing's target as a picture at the view's size: the HDR scene, or the host's target without one. */
    private static CgScreenTexture scene(CgStageFiring firing) {
        return sized(firing.target(), firing.view());
    }

    private static CgScreenTexture sized(CgGraphTexture texture, CgView view) {
        return CgScreenTexture.sized(texture, Math.max(1, view.width()), Math.max(1, view.height()));
    }

    /** Runs the effects at {@code point} on the scene, each answering its input; where the next point starts. */
    private static int run(CgPost post, CgPostPoint point, CgPost.Entry[] entries, int from, CgScreenTexture scene) {
        int i = from;
        for (; i < entries.length && entries[i].point() == point; i++) {
            post.context.input(scene);
            if (entries[i].effect().apply(post.context, scene) != scene) {
                throw new IllegalStateException("a post effect at " + point + " answered another texture than its input:"
                        + " the scene renderer goes on drawing into it (" + entries[i].effect() + ")");
            }
        }
        return i;
    }

    /** Runs the effects at {@code point} in turn, each given the one before's answer; the last answer. */
    private static CgScreenTexture carry(CgPost post, CgPostPoint point, CgPost.Entry[] entries, int from, CgScreenTexture picture) {
        for (int i = from; i < entries.length && entries[i].point() == point; i++) {
            post.context.input(picture);
            picture = entries[i].effect().apply(post.context, picture);
        }
        return picture;
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
        ctx.screen(copy).name("post.copy").from(picture).over(sized(host, ctx.view()));
    }
}
