package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One full-screen draw of a material: what {@link CgPostContext#screen} answers, set up, then drawn {@link #over} a
 * picture or {@link #into} a texture of its own (Unreal's {@code AddDrawScreenPass}).
 *
 * <pre>{@code
 * return ctx.screen(tint).over(input);                                   // drawn onto the picture, under its blend
 * return ctx.screen(blur).from(input).into(ctx.texture("mymod.blur", input));   // a texture of its own, cleared
 * ctx.screen(mask).reads(ctx.scene().customDepth()).weight(0.5f).over(input);    // a declared read; POST_WEIGHT 0.5
 * }</pre>
 *
 * <ul>
 *   <li>{@code CG_SCENE_COLOR} reads {@link #from}, the effect's input unless set; {@code CG_SCENE_EYE_DEPTH} the
 *       view's depth; {@code CG_CUSTOM_STENCIL}, {@code CG_SCENE_WORLD_NORMAL} and {@code CG_CUSTOM_DATA} what the
 *       view published this frame.</li>
 *   <li>It neither tests nor writes depth; a shader declaring no blend writes unblended, as Unreal's post pass does.</li>
 *   <li>A texture the material samples by a property is set on the material; {@link #reads} declares it to the graph.</li>
 *   <li>One instance per context, refilled by every {@code ctx.screen}: draw it before asking for the next.</li>
 * </ul>
 */
public final class CgScreenPass {

    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int MAX_READS = 8;

    private final CgPostContext ctx;
    private final Map<CgPipeline, CgPipeline> depthless = new IdentityHashMap<>();
    private final CgTexture[] reads = new CgTexture[MAX_READS];
    private int readCount;
    private CgMaterialInstance material;
    @Nullable
    private CgPipeline pipeline;
    private String name;
    @Nullable
    private CgScreenTexture from;
    private float weight;
    private int zone;

    CgScreenPass(CgPostContext ctx) {
        this.ctx = ctx;
    }

    CgScreenPass begin(CgMaterialInstance material, @Nullable CgScreenTexture input) {
        this.material = material;
        this.pipeline = null;
        this.name = "post.screen";
        this.from = input;
        this.weight = 1f;
        this.zone = 0;
        for (int i = 0; i < readCount; i++) reads[i] = null;
        readCount = 0;
        return this;
    }

    /** What {@code CG_SCENE_COLOR} reads: the effect's input unless set. */
    public CgScreenPass from(CgScreenTexture source) {
        this.from = source;
        return this;
    }

    /** Declares a texture the material samples by a property it was given: the graph orders the pass after its writer. */
    public CgScreenPass reads(@Nullable CgTexture texture) {
        if (texture == null) return this;
        if (readCount == MAX_READS) throw new IllegalStateException("a screen pass declares at most " + MAX_READS + " reads");
        reads[readCount++] = texture;
        return this;
    }

    /** {@code POST_WEIGHT} for this draw, 0 to 1; 1 unless set. */
    public CgScreenPass weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    /** The pass's name in captures and traces. */
    public CgScreenPass name(String name) {
        this.name = name;
        return this;
    }

    /** Engine side: the material's pass to draw, rather than its first. */
    CgScreenPass pipeline(CgPipeline pipeline) {
        this.pipeline = pipeline;
        return this;
    }

    /**
     * Times the pass under GPU zone {@code zone} ({@code CgGpuTrace.name}), as the chain's own passes are.
     *
     * <pre>{@code
     * ctx.screen(resolve).name("taa.resolve").timed(GPU_RESOLVE).from(input).into(out);
     * }</pre>
     */
    public CgScreenPass timed(int zone) {
        this.zone = zone;
        return this;
    }

    /** Draws onto {@code target} as it stands, under the material's blend; answers {@code target}. */
    public CgScreenTexture over(CgScreenTexture target) {
        draw(target.texture(), CgLoad.load());
        return target;
    }

    /** Clears {@code target}, then draws into it; answers {@code target}. */
    public CgScreenTexture into(CgScreenTexture target) {
        draw(target.texture(), CgLoad.CLEAR);
        return target;
    }

    private void draw(CgGraphTexture into, CgLoad load) {
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        CgPipeline p = pipeline != null ? pipeline : proxy.pipeline(CgInstanceKind.OBJECT);
        if (p == null) return;
        if (proxy.handedScene()) handScene(proxy);
        CgRecording recording = ctx.recording();
        CgRasterPass pass = recording.raster(name).color(into, load).constants(ctx.constants())
                .texture(CgBindingModel.LIGHTMAP, ctx.view().hostTextures().lightmapTexture());
        if (zone != 0) pass.timed(zone);
        CgPipeline drawn = depthless(p);
        CgGraphTexture source = from != null ? from.texture() : null;
        if (proxy.readsSceneColor() && source != null) {
            pass.readsColor(source);
            // Its one full-screen triangle writes every pixel: read the picture where it stands, not a copy of it
            CgBlendState blend = drawn.state().getBlend();
            if (source == into && (blend == null || !blend.enabled()) && !proxy.discards()) pass.replaces();
        }
        if (proxy.readsSceneDepth()) pass.readsDepth(ctx.depth());
        for (int i = 0; i < readCount; i++) pass.reads(reads[i]);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(drawn, proxy.bindings(), FULLSCREEN);
        weighted(chunks, weight);
        pass.add(chunks.end());
        pass.end();
    }

    /** Unreal's {@code SceneTextures}, from what this firing published; the fallbacks read stencil 0, no normal, no data. */
    private void handScene(CgMaterialProxy proxy) {
        CgGraphTexture custom = ctx.scene().customDepth();
        CgGraphTexture normals = ctx.scene().normals();
        CgGraphTexture data = ctx.scene().customData();
        bind(proxy, "_cg_CustomStencil", custom != null ? custom.stencil() : CgFallbackTextures.ZERO_UINT);
        bind(proxy, "_cg_WorldNormal", normals != null ? normals : CgFallbackTextures.TRANSPARENT);
        bind(proxy, "_cg_CustomData", data != null ? data : CgFallbackTextures.TRANSPARENT);
    }

    private void bind(CgMaterialProxy proxy, String name, CgTexture texture) {
        // Its own unit: a second input on unit 0 would take the first's.
        material.sampler(name, proxy.samplerUnit(name), texture);
    }

    /**
     * {@code pipeline} neither testing nor writing depth, as Unreal's post-process material pass: an opaque material's
     * default depth write would otherwise stamp the full-screen triangle's depth over the scene, and before
     * translucency hide every transparent draw behind it. A shader declaring no blend writes unblended rather than
     * under whatever blend the last draw left. Made once a pipeline.
     */
    private CgPipeline depthless(CgPipeline pipeline) {
        CgPipeline made = depthless.get(pipeline);
        if (made == null) {
            CgRenderState state = pipeline.state().withDepth(CgDepthState.NONE);
            if (state.getBlend() == null) state = state.withBlend(CgBlendState.DISABLED);
            made = pipeline.withState(state);
            depthless.put(pipeline, made);
        }
        return made;
    }

    /** The draw's object record: identity, {@code custom0.x} the weight ({@code POST_WEIGHT}). */
    private static void weighted(CgChunkBuilder chunks, float weight) {
        int at = chunks.instance();
        float[] d = chunks.data();
        d[at] = d[at + 5] = d[at + 10] = d[at + 15] = 1f;
        d[at + 32] = weight;
    }
}
