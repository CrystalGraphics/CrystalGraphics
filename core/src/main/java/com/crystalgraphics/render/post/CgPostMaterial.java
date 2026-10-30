package com.crystalgraphics.render.post;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A post effect that is only a material: its {@code .shader} drawn over the whole screen at a point, at a weight it
 * reads. Unreal's post-process material at a blendable location, so a sequence's {@code post_material} is data, not
 * code.
 *
 * <pre>{@code
 * CgPostMaterial thermal = CgPostMaterial.of("mymod:shaders/post/thermal.shader", CgPostPoint.AFTER_COMPOSITE);
 * CgRenderStage.Registration on = CgPostStack.get().add(thermal);
 * thermal.weight(0.8f);                                       // each tick, as a track says; 0 draws nothing
 * thermal.material().applyProperties(b -> b.set1f("_Heat", 2f));
 * on.close();                                                 // removes it
 * }</pre>
 *
 * <pre>{@code
 * #type none
 * #include "crystalgraphics:shaders/lib/post/post_material.glsl"
 * Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.001" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
 * Queue = "Overlay"
 * struct v2f { vec2 uv; };
 * Pass {
 *     RenderState { Blend OFF DepthTest ALWAYS DepthWrite OFF Cull OFF }
 *     void vertex(out v2f o) { o.uv = post_fullscreen_uv(CG_VERTEX_ID); gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0); }
 *     void fragment(in v2f i, out vec4 fragColor) {
 *         vec3 scene = CG_SCENE_COLOR(i.uv).rgb;
 *         fragColor = vec4(mix(scene, thermal(scene), POST_WEIGHT), 1.0);
 *     }
 * }
 * }</pre>
 *
 * <p>Several passes, as CC2's screen filters draw (a blur at half size, chained, then composited): every pass but
 * the last draws into a texture of its own, a share of the screen, which each pass after it reads through the
 * {@code sampler2D} it names. The last pass draws over the scene.
 *
 * <pre>{@code
 * Properties {
 *     _Half ("The scene at half size", sampler2D) = "black"
 *     _Blur ("Blurred", sampler2D) = "black"
 * }
 * Pass { Tags { "Name" = "Down" "Target" = "_Half" "Scale" = "0.5" } ... fragColor = CG_SCENE_COLOR(i.uv); }
 * Pass { Tags { "Name" = "Blur" "Target" = "_Blur" "Scale" = "0.5" } ... fragColor = blur(_Half, i.uv); }
 * Pass { Tags { "Name" = "Over" } ... fragColor = vec4(texture(_Blur, i.uv).rgb, POST_WEIGHT * _Alpha); }
 * }</pre>
 *
 * <ul>
 *   <li>{@code BEFORE_TRANSLUCENCY} draws into the lit opaque scene before any transparent draw, so a glow is drawn
 *       over its result (Unreal's {@code BL_BeforeTranslucency}); it reads custom depth, normals and custom data.
 *       {@code AFTER_WORLD} draws into the scene, before every look: linear HDR under {@code CgWorldRenderer.hdrScene}.
 *       {@code AFTER_COMPOSITE} draws over the final picture, in the target's encoding. {@code BEFORE_COMPOSITE} is
 *       refused: it feeds the composite.</li>
 *   <li>A shader reading {@code cg_SceneColor} or {@code cg_DepthBuffer} gets the target as it stands; tag it
 *       {@code "ColorSpace" = "Linear"} so its colour is written as read, and with the {@code "SceneColorMargin"} it
 *       samples past its own pixel (0.001 for its own alone). A pass into a target reads the scene the same way.</li>
 *   <li>The weight is per draw ({@code POST_WEIGHT}), so animating it leaves the material's properties alone.</li>
 *   <li>A pass's target is RGBA16F, cleared before it draws. {@code CG_RESOLUTION} is still the screen's: a pass
 *       reading its input's texels asks {@code textureSize}.</li>
 *   <li>{@code Tags { "Emission" = "_Mask" }} binds the firing's emission (what glows, bent with the scene) to the
 *       {@code sampler2D} it names, or under the HDR scene the scene itself, where glows are added: the mask a glare
 *       reads.</li>
 * </ul>
 */
public final class CgPostMaterial implements CgPostEffect {

    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final CgFrameBufferFormat TARGET = CgFrameBufferFormat.builder("cg_post_pass")
            .color(0, CgTextureType.RGBA16F).build();

    private final CgMaterial material;
    private final CgPostPoint point;
    private final int order;
    private volatile float weight = 1f;
    /** Whether its programs are built; polled from {@link #active} until they are. */
    private boolean warm;

    // The parse its passes were resolved for, per pass but the last its pipeline and texture, and the last's pipeline.
    private List<CgParsedShader.PostTarget> resolved;
    private CgPipeline[] pipelines = new CgPipeline[0];
    private CgPipeline over;
    private CgGraphTexture[] textures = new CgGraphTexture[0];
    private String emissionInput;
    // What the held binder sets: a sampler and its texture, so binding allocates nothing.
    private String bindName;
    private int bindUnit;
    private CgTexture bindTexture;
    private final Consumer<CgShaderBindings> bind = b -> b.sampler(bindName, bindUnit, bindTexture);

    private CgPostMaterial(CgMaterial material, CgPostPoint point, int order) {
        this.material = Objects.requireNonNull(material, "material");
        this.point = Objects.requireNonNull(point, "point");
        if (point == CgPostPoint.BEFORE_COMPOSITE) {
            throw new IllegalArgumentException("a post material draws BEFORE_TRANSLUCENCY, AFTER_WORLD or AFTER_COMPOSITE: "
                    + "BEFORE_COMPOSITE feeds the composite");
        }
        this.order = order;
    }

    public static CgPostMaterial of(String shader, CgPostPoint point) {
        return of(CgMaterial.newInstance(shader), point, 0);
    }

    /** {@code material} at {@code point}, {@code order} among the effects there. */
    public static CgPostMaterial of(CgMaterial material, CgPostPoint point, int order) {
        return new CgPostMaterial(material, point, order);
    }

    public CgMaterial material() {
        return material;
    }

    /** How much of it the shader is asked for, 0 to 1; 0 records nothing. Any thread. */
    public CgPostMaterial weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    public float weight() {
        return weight;
    }

    @Override
    public CgPostPoint point() {
        return point;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public boolean active(CgPostContext post) {
        // Built while it waits at 0, within the warm budget: a darkening first raised at a cut compiles nothing there.
        // The shader first, then the one full-screen pipeline it draws: the world's prepare would build its draw forms
        // too (multi-draw, depth, normals), compiled for nothing at a cut that spawns it.
        if (!warm && material.prepare()) {
            CgPipeline p = material.pipeline(CgInstanceKind.OBJECT);
            warm = p == null || p.prepare();
        }
        return weight > 0f;
    }

    @Override
    public void record(CgPostContext post) {
        if (!resolve()) return;
        // One pass: the first Forward pass, under the material's state. Several: the last, which draws over the scene.
        CgPipeline pipeline = over != null ? over : material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRecording recording = post.recording();
        if (emissionInput != null) {
            CgGraphTexture scene = post.resources().get(CgFrameKeys.SCENE);
            CgGraphTexture emission = scene != null ? scene : post.distorted(post.resources().get(CgFrameKeys.EMISSION));
            if (emission == null) return;
            bind(emissionInput, emission);
        }
        if (material.handedScene()) {
            // Unreal's SceneTextures, from what this firing published; the fallbacks read stencil 0, no normal, no data.
            CgGraphTexture stencil = post.resources().get(CgFrameKeys.CUSTOM_DEPTH);
            CgGraphTexture normals = post.resources().get(CgFrameKeys.WORLD_NORMALS);
            CgGraphTexture data = post.resources().get(CgFrameKeys.CUSTOM_DATA);
            bind("_cg_CustomStencil", stencil != null ? stencil : CgFallbackTextures.BLACK_1x1);
            bind("_cg_WorldNormal", normals != null ? normals : CgFallbackTextures.TRANSPARENT);
            bind("_cg_CustomData", data != null ? data : CgFallbackTextures.TRANSPARENT);
        }
        CgGraphTexture screen = post.target();
        // The host's size: an imported target's texture knows none until it executes.
        int screenW = post.stage().host().width(), screenH = post.stage().host().height();
        for (int t = 0; t < textures.length; t++) {
            CgParsedShader.PostTarget target = resolved.get(t);
            int w = Math.max(1, Math.round(screenW * target.scale()));
            int h = Math.max(1, Math.round(screenH * target.scale()));
            CgGraphTexture into = textures[t];
            if (into == null || into.getWidth() != w || into.getHeight() != h) {
                textures[t] = into = CgGraphTexture.transientTexture("cg_post_pass_" + t, new CgTextureDesc(w, h, TARGET, 1));
            }
            draw(recording, into, CgLoad.clear(0f, 0f, 0f, 0f), post, pipelines[t]);
            bind(target.property(), into);
        }
        draw(recording, screen, CgLoad.load(), post, pipeline);
    }

    private void draw(CgRecording recording, CgGraphTexture into, CgLoad load, CgPostContext post, CgPipeline pipeline) {
        CgRasterPass pass = recording.raster(into, load, post.constants(), null, CgOrder.SORTED);
        // A pass into a target of its own reads the scene's colour and depth, not the target it clears.
        CgGraphTexture screen = post.target();
        if (material.readsSceneColor() && into == screen) pass.sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
        else if (material.readsSceneColor()) pass.sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, screen);
        if (material.readsSceneDepth() && into == screen) pass.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT);
        else if (material.readsSceneDepth()) pass.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, screen);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(depthless(pipeline), material.captureBindings(recording.bindings()), FULLSCREEN);
        weighted(chunks, weight);
        pass.add(chunks.end());
        pass.end();
    }

    /**
     * {@code pipeline} neither testing nor writing depth, as Unreal's post-process material pass: an opaque material's
     * default depth write would otherwise stamp the full-screen triangle's depth over the scene, and before
     * translucency hide every transparent draw behind it. Made once a pipeline.
     */
    private CgPipeline depthless(CgPipeline pipeline) {
        CgPipeline made = depthless.get(pipeline);
        if (made == null) {
            made = pipeline.withState(pipeline.state().withDepth(CgDepthState.NONE));
            depthless.put(pipeline, made);
        }
        return made;
    }

    private final Map<CgPipeline, CgPipeline> depthless = new IdentityHashMap<>();

    private void bind(String name, CgTexture texture) {
        // Its own unit: a second input on unit 0 would take the first's.
        bindName = name;
        bindUnit = material.samplerUnit(name);
        bindTexture = texture;
        material.applyProperties(bind);
    }

    /** Each pass's pipeline, resolved again when the shader parses again; false while one is not ready. */
    private boolean resolve() {
        List<CgParsedShader.PostTarget> targets = material.postTargets();
        if (targets != resolved) {
            CgPipeline[] made = new CgPipeline[targets.size()];
            for (int t = 0; t < made.length; t++) {
                made[t] = material.pipeline(targets.get(t).pass(), CgInstanceKind.OBJECT);
                if (made[t] == null) return false;
            }
            CgPipeline last = null;
            if (made.length > 0) {
                String name = material.lastForwardPass();
                last = name == null ? null : material.pipeline(name, CgInstanceKind.OBJECT);
                if (last == null) return false;
            }
            over = last;
            pipelines = made;
            textures = new CgGraphTexture[made.length];
            emissionInput = material.emissionInput();
            resolved = targets;
        }
        return true;
    }

    /** The draw's object record: identity, {@code custom0.x} the weight ({@code POST_WEIGHT}). */
    static void weighted(CgChunkBuilder chunks, float weight) {
        int at = chunks.instance();
        float[] d = chunks.data();
        d[at] = d[at + 5] = d[at + 10] = d[at + 15] = 1f;
        d[at + 32] = weight;
    }
}
