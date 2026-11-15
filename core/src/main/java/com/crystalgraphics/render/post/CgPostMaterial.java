package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.shader.CgShaderSpec;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A post effect that is only a material: its {@code #domain post} file drawn over the picture at a point, at a weight it
 * reads. Unreal's post-process material at a blendable location, so a sequence's {@code post_material} is data, not
 * code. Made by {@link CgPost#material}.
 *
 * <pre>{@code
 * CgPostMaterial thermal = CgPost.client().material("mymod:shaders/post/thermal.shader");   // at its Location
 * thermal.weight(0.8f);                                       // each tick, as a track says; 0 draws nothing
 * thermal.material().set1f("_Heat", 2f);
 * thermal.remove();
 * }</pre>
 *
 * <pre>{@code
 * // mymod:shaders/post/thermal.shader
 * #domain post
 * Material { Location DISPLAY }
 * void surface(in CgScreen i, inout CgPostSurface s) { s.color = mix(s.color, thermal(s.color), POST_WEIGHT); }
 * }</pre>
 *
 * <p>Several steps, as CC2's screen filters draw (a blur at half size, chained, then composited): every step but the
 * last draws into a texture of its own, a share of the screen, which each step after it reads by name. The last draws
 * over the picture.
 *
 * <pre>{@code
 * Step half { Target RGBA16_FLOAT  Scale 0.5  void surface(in CgScreen i, inout CgPostSurface s) { s.color = CG_SCENE_COLOR(i.uv).rgb; } }
 * Step blur { Target RGBA16_FLOAT  Scale 0.5  void surface(in CgScreen i, inout CgPostSurface s) { s.color = blurred(i.uv); } }   // CG_STEP(half, uv) around uv
 * Step over { void surface(in CgScreen i, inout CgPostSurface s) { s.color = CG_STEP(blur, i.uv).rgb; s.opacity = POST_WEIGHT; } }
 * }</pre>
 *
 * <ul>
 *   <li>{@code SURFACES} draws into the lit opaque scene before any transparent draw, so a glow is drawn over its result;
 *       it reads custom depth, normals and custom data. {@code SCENE} and {@code HDR} draw into the linear scene, before
 *       and after bloom is taken. {@code DISPLAY} draws over the final picture, in the target's encoding.</li>
 *   <li>A material reading the scene ({@code CG_SCENE_COLOR}, {@code CG_SCENE_EYE_DEPTH}) gets the picture as it stands;
 *       {@code Material { SceneColorMargin }} says how far past its own pixel it samples (0.001 for its own alone).</li>
 *   <li>The weight is per draw ({@code POST_WEIGHT}), so animating it leaves the material's properties alone.</li>
 *   <li>A step's texture is its {@code Target} format, cleared before it draws. {@code CG_RESOLUTION} is still the
 *       screen's: a step reading its input's texels asks {@code CG_STEP_SIZE}.</li>
 *   <li>Held until every pass is built, while the view skips unready draws: added the frame it shows, it compiles
 *       ahead and draws from the frame it is ready.</li>
 * </ul>
 */
public final class CgPostMaterial implements CgPostEffect {

    private CgMaterialInstance material;
    private volatile float weight = 1f;
    @Nullable
    private CgPost.Registration registration;
    /** Whether its programs are built; polled until they are. */
    private boolean warm;

    // The parse its passes were resolved for, per pass but the last its pipeline and texture, and the last's pipeline.
    private List<CgShaderSpec.PostTarget> resolved;
    private CgPipeline[] pipelines = new CgPipeline[0];
    @Nullable
    private CgPipeline over;
    private CgGraphTexture[] textures = new CgGraphTexture[0];

    private CgPostMaterial(CgMaterialInstance material) {
        this.material = Objects.requireNonNull(material, "material");
    }

    /**
     * {@code material} drawn by the engine where it says, never added to a chain: a colour region's grade, the height
     * fog, a graded layer's look. Its owner calls {@link #draw}.
     */
    public static CgPostMaterial drawer(CgMaterialInstance material) {
        return new CgPostMaterial(material);
    }

    /** {@link CgPost#material}'s: added to {@code post} at {@code point}. */
    static CgPostMaterial added(CgPost post, CgMaterialInstance material, CgPostPoint point, int order) {
        CgPostMaterial m = new CgPostMaterial(material);
        m.registration = post.add(point, order, m);
        return m;
    }

    /** The instance it draws, whose values its owner sets. */
    public CgMaterialInstance material() {
        return material;
    }

    /**
     * Draws {@code material} from now on: another keyword variant's instance, as a region switching shape draws. Render
     * thread.
     *
     * <pre>{@code
     * post.material(instances.of(base.keyword("SHAPE_BOX", true)));
     * }</pre>
     */
    public CgPostMaterial material(CgMaterialInstance material) {
        if (material != this.material) {
            this.material = Objects.requireNonNull(material, "material");
            resolved = null;
            warm = false;
        }
        return this;
    }

    /** How much of it the shader is asked for, 0 to 1; 0 draws nothing. Any thread. */
    public CgPostMaterial weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    public float weight() {
        return weight;
    }

    /** Takes it out of its chain; a drawer has none. */
    public void remove() {
        if (registration != null) registration.close();
    }

    /** Whether it draws this firing: weighed, and built or not held for it. */
    public boolean shows(CgPostContext ctx) {
        // Built while it waits at 0, within the warm budget: a darkening first raised at a cut compiles nothing there.
        // The shader first, then the one full-screen pipeline it draws: the world's precache would build its draw forms
        // too (multi-draw, depth, normals), compiled for nothing at a cut that spawns it.
        if (!warm && material.precache() && resolve()) warm = precacheAll();
        // Held until every pass is built, as Unreal skips a draw whose PSO is not (r.SkipDrawOnPSOPrecaching): a fade
        // raised the frame it is added would otherwise compile at its draw. The view's hold switch is its draws' too.
        return (warm || !ctx.view().flag(CgSceneRenderer.SKIP_UNREADY)) && weight > 0f;
    }

    /** Starts or polls every pipeline it draws, each pass's and the last's; whether all are built. */
    private boolean precacheAll() {
        boolean ready = true;
        if (pipelines.length == 0) {
            CgPipeline p = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
            return p == null || p.precache();
        }
        for (CgPipeline p : pipelines) ready &= p.precache();
        return over.precache() & ready;
    }

    @Override
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        if (shows(ctx)) draw(ctx, input);
        return input;
    }

    /**
     * Draws it over {@code target}, reading {@code target} where it reads the picture: its steps first, each into its
     * texture, then its last over {@code target}. The weight is the caller's to have checked ({@link #shows}).
     */
    public void draw(CgPostContext ctx, CgScreenTexture target) {
        if (!resolve()) return;
        int screenW = target.width(), screenH = target.height();
        for (int t = 0; t < textures.length; t++) {
            CgShaderSpec.PostTarget step = resolved.get(t);
            int w = Math.max(1, Math.round(screenW * step.scale()));
            int h = Math.max(1, Math.round(screenH * step.scale()));
            CgGraphTexture into = textures[t];
            if (into == null || into.getWidth() != w || into.getHeight() != h || into.format() != step.format()) {
                textures[t] = into = CgGraphTexture.transientTexture("cg_post_pass_" + t, new CgTextureDesc(w, h, step.format(), 1));
            }
            ctx.screen(material).name("post.material").pipeline(pipelines[t]).from(target).weight(weight)
                    .into(CgScreenTexture.of(into));
            material.sampler(step.property(), CgMaterialProxy.of(material).samplerUnit(step.property()), into);
        }
        CgScreenPass last = ctx.screen(material).name("post.material").from(target).weight(weight);
        if (over != null) last.pipeline(over);
        last.over(target);
    }

    /**
     * Draws its one pass over {@code layer} as it draws over the scene, reading {@code layer}'s colour where it reads the
     * scene's: a grade over a graded layer as well as the frame ({@code CgColourRegion}), the light the layer holds
     * graded as the scene around it is. The scene's depth is read as ever.
     *
     * <ul>
     *   <li>Its shader keeps the alpha it reads, or {@code layer}'s alpha is lost.</li>
     *   <li>A material of several passes draws nothing here.</li>
     * </ul>
     */
    public void drawOver(CgPostContext ctx, CgGraphTexture layer) {
        if (!resolve() || textures.length > 0) return;
        CgScreenTexture over = CgScreenTexture.of(layer);
        ctx.screen(material).name("post.material").from(over).weight(weight).over(over);
    }

    /** Each pass's pipeline, resolved again when the shader parses again; false while one is not ready. */
    private boolean resolve() {
        List<CgShaderSpec.PostTarget> targets = CgMaterialProxy.of(material).postTargets();
        if (targets != resolved) {
            CgPipeline[] made = new CgPipeline[targets.size()];
            for (int t = 0; t < made.length; t++) {
                made[t] = CgMaterialProxy.of(material).pipeline(targets.get(t).pass(), CgInstanceKind.OBJECT);
                if (made[t] == null) return false;
            }
            CgPipeline last = null;
            if (made.length > 0) {
                String name = CgMaterialProxy.of(material).lastForwardPass();
                last = name == null ? null : CgMaterialProxy.of(material).pipeline(name, CgInstanceKind.OBJECT);
                if (last == null) return false;
            }
            over = last;
            pipelines = made;
            textures = new CgGraphTexture[made.length];
            resolved = targets;
        }
        return true;
    }
}
