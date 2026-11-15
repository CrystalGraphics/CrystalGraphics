package com.crystalgraphics.render.post;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.post.fog.CgHeightFogPass;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.composite.CgCompositeFeature;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.distortion.CgPostDistortion;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.look.CgPostLooks;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.camera.CgCamera;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * The post-processing stack: what runs after the scene renderer has drawn a view of the client scene, in the same
 * firing, between the {@code TRANSLUCENT} and {@code OVERLAY} stages. Every active {@link CgPostEffect} records at its
 * {@link CgPostPoint}, then one composite pass lays the view's looks over the target, as every production engine
 * composes bloom inside its tonemap or uber pass. The scene renderer produces (the view's scene textures); the stack
 * consumes.
 *
 * <pre>{@code
 * CgPostStack post = CgPostStack.get();
 * post.bloom().intensity(1.2f);                                 // the built-in bloom's settings
 * CgPostStack.Registration outline = post.add(new Outline());   // a mod's effect at its point
 * outline.close();
 *
 * // An effect's look for a moment: a volume, never the global settings
 * CgPostVolume burst = post.volume(10, new CgPostSettings().flash(1.5f)).at(x, y, z).radius(24f).blend(16f);
 * burst.weight(fade);
 * burst.close();
 * }</pre>
 *
 * <ul>
 *   <li>Run by the scene renderer for the client scene's views whose show flags keep post on.</li>
 *   <li>Render thread for recording; any thread may {@link #add} or remove an effect, which takes effect at the next
 *       firing.</li>
 * </ul>
 */
public final class CgPostStack {


    private static final CgPostStack INSTANCE = new CgPostStack();

    private final CgPostComposite composite = new CgPostComposite();
    private final CgPostDistortion distortion = new CgPostDistortion();
    private final CgPostContext context = new CgPostContext(composite, distortion);
    private final CgBloom bloom = new CgBloom();
    private final CgPostLooks looks = new CgPostLooks();
    private final CgPostCrossfade crossfade = new CgPostCrossfade();
    private final CgTranslucencyComposition translucency = new CgTranslucencyComposition();
    /** The height fog of the scene each view draws. */
    private final CgHeightFogPass fog = new CgHeightFogPass();
    /** {@code -Dcrystalgraphics.post.debug}'s view; null when unset. */
    private final CgPostDebug debug = CgPostDebug.fromProperty();
    /** Every effect, by point then order then when it was added: replaced whole, so recording reads a snapshot. */
    private volatile CgPostEffect[] effects = debug == null ? new CgPostEffect[]{fog, bloom, looks, crossfade}
            : new CgPostEffect[]{fog, bloom, looks, crossfade, debug};
    /** Every volume, by priority ascending then when it was made: replaced whole, as the effects are. */
    private volatile CgPostVolume[] volumes = new CgPostVolume[0];
    /** The volumes blended at the camera, this firing. */
    /** A view's volumes blended at its camera this frame (Unreal's {@code FinalPostProcessSettings}): its own, per view. */
    private static final CgViewState.Key<CgPostSettings> SETTINGS =
            new CgViewState.Key<>("crystalgraphics:post.settings", CgPostSettings::new);
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private boolean installed;

    private CgPostStack() {
    }

    public static CgPostStack get() {
        return INSTANCE;
    }

    /** An effect added to the stack; closing it removes it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** Requests its programs and kernels for the precache, once. The engine calls it when it starts. */
    public void install() {
        if (installed) return;
        installed = true;
        // A step a shader: each reads and parses its file to name its permutations, together 100 ms and more of a frame
        CgPipelinePrecache.Steps steps = CgPipelinePrecache.steps("crystalgraphics:post");
        for (String pass : WARMED) steps.step(pipelines -> pipelines.addPass(pass));
        // The bloom's and the tonemapper's table's kernels: their files parsed on a worker first
        steps.ahead(() -> CgKernel.ahead(CgPostStack::precacheKernels)).step(list -> list.kernels(CgPostStack::precacheKernels));
        steps.start();
    }

    private static void precacheKernels() {
        CgBloom.precache();
        CgFilmLut.precache();
    }

    /**
     * The composite's passes, each in every keyword set a setting can switch to, as its shader's permutation
     * domain names them (the HDR scene, bloom, a fade); the HDR scene's way in; and the distortion's bend,
     * which a tier below Medium never draws. The looks' keywords
     * follow volumes, not settings: each volume registers its own as it is made, and an asset's preparer requests them
     * ahead of it ({@link #lookPasses}).
     */
    private static final String[] WARMED = {
            "crystalgraphics:shaders/world/scene_in.shader", "crystalgraphics:shaders/world/scene_out.shader",
            "crystalgraphics:shaders/post/distortion_bend.shader", CgTranslucencyComposition.SHADER,
            "crystalgraphics:shaders/post/composite.shader", "crystalgraphics:shaders/post/composite_copy.shader"};

    /**
     * Adds the composite passes a volume of {@code settings} draws with: its looks, in every keyword set of the
     * composite's permutation domain. A preparer requests them before the volume plays, so the frame it turns a look on
     * compiles nothing; nothing for settings that switch no look on.
     *
     * <pre>{@code
     * CgPipelinePrecache.Request looks = CgPipelinePrecache.request(p -> CgPostStack.get().lookPasses(settings, p));
     * }</pre>
     */
    public void lookPasses(CgPostSettings settings, CgPipelineList pipelines) {
        String[] keywords = lookKeywords(settings);
        if (keywords != null) pipelines.addPass(COMPOSITE_COPY, keywords);
    }

    private static final String COMPOSITE_COPY = "crystalgraphics:shaders/post/composite_copy.shader";

    /** The composite's look keywords {@code settings} switch on; null for none. */
    @Nullable
    private String[] lookKeywords(CgPostSettings settings) {
        EnumSet<CgCompositeFeature> on = CgPostLooks.features(settings);
        if (on.isEmpty()) return null;
        List<String> keywords = new ArrayList<>();
        for (CgCompositeFeature f : on) keywords.add(f.keyword());
        if (looks.drawsImpact(settings)) keywords.add("SUBJECT");
        return keywords.toArray(new String[0]);
    }

    /** The look sets a volume has registered for precaching: each once a session. */
    private final Set<String> lookSets = ConcurrentHashMap.newKeySet();

    /** A volume's look, built ahead as Unreal precaches a component's pipelines when it registers. */
    private void precacheLook(CgPostSettings settings) {
        String[] keywords = lookKeywords(settings);
        if (keywords == null) return;
        String key = String.join(",", keywords);
        if (lookSets.add(key)) CgPipelinePrecache.register("crystalgraphics:post volume " + key, p -> p.addPass(COMPOSITE_COPY, keywords));
    }

    /**
     * Engine side, the scene renderer's at a view's opaque callback, before {@code PREPARE}: blends the volumes at the
     * view's camera, and asks the view's textures for what this frame's effects read (a drawn impact frame's subject,
     * the scene textures a post material is handed).
     */
    public void prepare(CgView view) {
        CgPostSettings settings = view.state().get(SETTINGS);
        resolve(view, settings);
        CgSceneTextures textures = view.textures();
        if (looks.drawsImpact(settings)) textures.requestSubject(settings.impactSubject());
        if (readsScene()) {
            textures.requestNormals();
            textures.requestCustomData();
        }
    }

    /** Whether this firing's before-translucency effects were recorded at the renderer's point. */
    private boolean recordedBefore;

    /**
     * Engine side, the scene renderer's just before {@code SURFACES}: the effects at {@link CgPostPoint#BEFORE_TRANSLUCENCY}
     * onto the lit opaque HDR scene (Unreal's {@code BL_BeforeTranslucency}). With no HDR scene yet (its programs
     * unbuilt) they record with the rest.
     */
    public void recordBeforeTranslucency(CgStageFiring firing) {
        recordedBefore = false;
        CgPostEffect[] current = effects;
        if (current.length == 0 || current[0].point() != CgPostPoint.BEFORE_TRANSLUCENCY || !firing.view().textures().linear()) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.beforeTranslucency")) {
            context.begin(firing, firing.view().state().get(SETTINGS));
            recordAt(CgPostPoint.BEFORE_TRANSLUCENCY, current, 0);
            recordedBefore = true;
        }
    }

    /** Whether an active post material reads the scene textures it is handed ({@code env/handed/scene.glsl}). */
    private boolean readsScene() {
        for (CgPostEffect e : effects) {
            if (e instanceof CgPostMaterial m && m.weight() > 0f && CgMaterialProxy.of(m.material()).handedScene()) return true;
        }
        return false;
    }

    /** The built-in bloom, and its settings. */
    public CgBloom bloom() {
        return bloom;
    }

    /**
     * A cut's crossfade: the next firing's final picture is held, and the frames after it draw it over theirs, fading
     * out linearly over {@code seconds} of the frame clock. Call it on the old shot's last frame. Any thread.
     *
     * <pre>{@code
     * CgPostStack.get().crossfade(0.5f);   // this frame is held; the cut comes next frame
     * }</pre>
     */
    public void crossfade(float seconds) {
        crossfade.crossfade(seconds);
    }

    /** Adds {@code effect} at its point; closing the registration removes it. */
    public Registration add(CgPostEffect effect) {
        Objects.requireNonNull(effect, "effect");
        synchronized (this) {
            CgPostEffect[] next = Arrays.copyOf(effects, effects.length + 1);
            next[next.length - 1] = effect;
            // Stable: ties keep the order they were added in.
            Arrays.sort(next, (a, b) -> a.point() != b.point() ? a.point().compareTo(b.point())
                    : Integer.compare(a.order(), b.order()));
            effects = next;
        }
        return () -> remove(effect);
    }

    /**
     * A volume applying {@code settings} at {@code priority} (a higher blends over a lower), everywhere at full weight
     * until placed, sized or weighed. Close it to stop it. The look {@code settings} switch on now is precached; one
     * switched on later by changing them is not, and its owner requests it ({@link #lookPasses}).
     */
    public CgPostVolume volume(int priority, CgPostSettings settings) {
        Objects.requireNonNull(settings, "settings");
        CgPostVolume volume = new CgPostVolume(priority, settings, this::remove);
        precacheLook(settings);
        synchronized (this) {
            CgPostVolume[] next = Arrays.copyOf(volumes, volumes.length + 1);
            next[next.length - 1] = volume;
            Arrays.sort(next, (a, b) -> Integer.compare(a.priority(), b.priority()));   // stable: ties keep their order
            volumes = next;
        }
        return volume;
    }

    private synchronized void remove(CgPostVolume volume) {
        CgPostVolume[] current = volumes;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != volume) continue;
            CgPostVolume[] next = new CgPostVolume[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, current.length - i - 1);
            volumes = next;
            return;
        }
    }

    private synchronized void remove(CgPostEffect effect) {
        CgPostEffect[] current = effects;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != effect) continue;
            CgPostEffect[] next = new CgPostEffect[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, current.length - i - 1);
            effects = next;
            return;
        }
    }

    /** Forgets what it made on the GPU. At context teardown. */
    public void release() {
        composite.release();
        distortion.release();
        bloom.release();
        crossfade.release();
        if (debug != null) debug.release();
    }

    /**
     * Engine side, the scene renderer's after {@code TRANSLUCENT}: every effect at its point, and the composite, which
     * encodes the HDR scene back into the host's target, every later stage's again.
     */
    public void record(CgStageFiring firing) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.record")) {
            context.begin(firing, firing.view().state().get(SETTINGS));   // blended at the view's opaque callback
            composite.begin();
            CgPostEffect[] current = effects;
            // Before-translucency effects the world renderer recorded at its point are past; otherwise they record here
            int i = 0;
            if (recordedBefore) {
                while (i < current.length && current[i].point() == CgPostPoint.BEFORE_TRANSLUCENCY) i++;
            } else {
                i = recordAt(CgPostPoint.BEFORE_TRANSLUCENCY, current, 0);
            }
            recordedBefore = false;
            i = recordAt(CgPostPoint.BEFORE_SEPARATE_TRANSLUCENCY, current, i);
            translucency.record(context);
            i = recordAt(CgPostPoint.AFTER_WORLD, current, i);
            i = recordAt(CgPostPoint.BEFORE_COMPOSITE, current, i);
            CgSceneTextures textures = firing.view().textures();
            CgGraphTexture scene = textures.linear() ? textures.sceneColor() : null;
            if (scene != null) {
                // The composite encodes the scene into the host's target, which is every later stage's again.
                composite.scene(scene);
                firing.retarget(null, null);
                textures.sceneColor(null, false);
            }
            composite.record(firing.recording(), firing.target(), firing.constants());
            recordAt(CgPostPoint.AFTER_COMPOSITE, current, i);
        }
    }

    /**
     * Blends every volume into {@code settings} at {@code of}'s camera, a placed one's focus where it is on screen. The
     * volumes are the client world's: a view of a scene of its own blends none.
     */
    private void resolve(CgView of, CgPostSettings settings) {
        settings.reset();
        CgPostVolume[] current = volumes;
        if (current.length == 0 || of.scene() != CgScene.client()) return;
        CgCamera view = of.camera();
        double cx = view.x(), cy = view.y(), cz = view.z();
        viewProjection.set(view.projection()).mul(view.view());
        for (CgPostVolume v : current) {
            float w = v.weightAt(cx, cy, cz);
            if (w <= 0f) continue;
            float fx = v.settings().focusX(), fy = v.settings().focusY();
            if (v.placed() && !v.settings().overrides(CgPostSettings.FOCUS)) {
                viewProjection.transform((float) (v.x() - cx), (float) (v.y() - cy), (float) (v.z() - cz), 1f, clip);
                if (clip.w > 0f) {
                    fx = clip.x / clip.w * 0.5f + 0.5f;
                    fy = clip.y / clip.w * 0.5f + 0.5f;
                }
            }
            settings.blend(v.settings(), w, fx, fy);
        }
    }

    /** Records the effects at {@code point}, which start at {@code from} in the sorted array; answers where the next point starts. */
    private int recordAt(CgPostPoint point, CgPostEffect[] current, int from) {
        int i = from;
        for (; i < current.length && current[i].point() == point; i++) {
            if (current[i].active(context)) current[i].record(context);
        }
        return i;
    }
}
