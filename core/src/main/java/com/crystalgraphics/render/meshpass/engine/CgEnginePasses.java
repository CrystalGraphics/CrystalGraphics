package com.crystalgraphics.render.meshpass.engine;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.meshpass.CgCustomDepth;
import com.crystalgraphics.render.meshpass.CgMeshBatch;
import com.crystalgraphics.render.meshpass.CgMeshPassCommands;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.meshpass.CgPassLayout;
import com.crystalgraphics.render.meshpass.CgPrimitiveInfo;
import com.crystalgraphics.render.meshpass.CgPrimitiveRelevance;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's mesh passes, each a {@link CgMeshPassType} with its processor, as Unreal's {@code EMeshPass} entries
 * are: which batches draw in each, through which of their material's pipelines. The scene renderer runs them in its
 * order; a harness scene or a tool may run one into targets of its own.
 *
 * <pre>{@code
 * CgViewPasses passes = CgViewPasses.of(view, scene, camera);
 * rec.raster("scene.base").color(scene, CgLoad.load()).depth(depth, CgLoad.load(), CgDepthAccess.WRITE)
 *         .add(passes.commands(CgEnginePasses.BASE, rec));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #DEPTH}: what writes depth, depth alone (Unreal's {@code DepthPass}).</li>
 *   <li>{@link #BASE}: solids, their Forward pass ({@code BasePass}).</li>
 *   <li>{@link #TRANSLUCENT}: what blends, its Forward pass, sorted back to front by its key
 *       ({@code TranslucencyStandard}); none drawn at half size, after distortion or as separate translucency.</li>
 *   <li>{@link #AFTER_DISTORTION}: what blends after the hazes, unbent by them.</li>
 *   <li>{@link #SEPARATE}: separate translucency (a material whose state says so), drawn into a layer of its own after
 *       every other translucent draw (Unreal's {@code TranslucencyAfterDOF}).</li>
 *   <li>{@link #HALF}: what blends by adding, marked half-resolution, drawn at half size and added over the scene.</li>
 *   <li>{@link #VOLUME}: what covers premultiplied (smoke, a volume), marked half-resolution: marched at half size and
 *       blended with its last frame.</li>
 *   <li>{@link #EMISSIVE}: what names {@code s.emissive}, its Emissive pass, into the bloom's input.</li>
 *   <li>{@link #DISTORTION}: what names {@code s.refraction}, its Distortion pass, into the distortion target
 *       ({@code Distortion}).</li>
 *   <li>{@link #CUSTOM_DEPTH}: primitives marked {@code customDepth}, depth alone writing their stencil value
 *       ({@code CustomDepth}).</li>
 *   <li>{@link #CUSTOM_DATA}: solids whose material writes custom data, into its target.</li>
 *   <li>{@link #VELOCITY}: solids that moved this frame, skinned meshes and what asks ({@code writesVelocity}), their
 *       Velocity pass, into an RG16F target cleared to 0 ({@code Velocity}). Built every frame, never kept.</li>
 *   <li>{@link #DECAL}: decal components' boxes, their {@code #domain decal} material reading the scene's depth and
 *       blending into the scene's colour after the opaque scene, in their sort order (Unreal's deferred decals as its
 *       forward renderer draws them). A raster pass of it reads the scene's depth ({@code readsDepth}).</li>
 * </ul>
 */
public final class CgEnginePasses {

    /** The HDR scene's colour and the scene's depth: what the base, translucent and emissive passes draw into. */
    public static final CgPassLayout SCENE = CgPassLayout.color(CgFormat.RGBA16_FLOAT).depth(CgFormat.DEPTH32_FLOAT);

    public static final CgMeshPassType DEPTH = CgMeshPassType.register("crystalgraphics:depth",
            CgPassLayout.depthOnly(CgFormat.DEPTH32_FLOAT), CgEnginePasses::depth);
    public static final CgMeshPassType BASE = CgMeshPassType.register("crystalgraphics:base", SCENE, CgEnginePasses::base);
    public static final CgMeshPassType TRANSLUCENT = CgMeshPassType.register("crystalgraphics:translucent", SCENE,
            CgEnginePasses::translucent);
    public static final CgMeshPassType AFTER_DISTORTION = CgMeshPassType.register("crystalgraphics:after_distortion", SCENE,
            CgEnginePasses::afterDistortion);
    public static final CgMeshPassType SEPARATE = CgMeshPassType.register("crystalgraphics:separate", SCENE,
            CgEnginePasses::separate);
    public static final CgMeshPassType HALF = CgMeshPassType.register("crystalgraphics:half",
            CgPassLayout.color(CgFormat.RG11B10_UFLOAT), CgEnginePasses::half);
    public static final CgMeshPassType VOLUME = CgMeshPassType.register("crystalgraphics:volume",
            CgPassLayout.color(CgFormat.RGBA16_FLOAT), CgEnginePasses::volume);
    public static final CgMeshPassType EMISSIVE = CgMeshPassType.register("crystalgraphics:emissive", SCENE,
            CgEnginePasses::emissive);
    public static final CgMeshPassType DISTORTION = CgMeshPassType.register("crystalgraphics:distortion", SCENE,
            CgEnginePasses::distortion);
    /** Unreal's PF_DepthStencil custom depth, cleared to far and stencil 0. */
    public static final CgMeshPassType CUSTOM_DEPTH = CgMeshPassType.register("crystalgraphics:custom_depth",
            CgPassLayout.depthOnly(CgFormat.DEPTH32_FLOAT_STENCIL8), CgEnginePasses::customDepth);
    public static final CgMeshPassType CUSTOM_DATA = CgMeshPassType.register("crystalgraphics:custom_data",
            CgPassLayout.color(CgFormat.RG32_FLOAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::customData);
    /** The velocity target's format: Unreal's encoding over two halves, 0 meaning nothing wrote ({@code lib/velocity.glsl}). */
    public static final CgFormat VELOCITY_FORMAT = CgFormat.RG16_FLOAT;
    public static final CgMeshPassType VELOCITY = CgMeshPassType.registerPerFrame("crystalgraphics:velocity",
            CgPassLayout.color(VELOCITY_FORMAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::velocity);
    public static final CgMeshPassType DECAL = CgMeshPassType.register("crystalgraphics:decal",
            CgPassLayout.color(CgFormat.RGBA16_FLOAT), CgEnginePasses::decal);

    /** Derived states by their base, interned: a state has no value equality, and each is a pipeline of its own. */
    private static final Map<CgRenderState, CgRenderState> COLOURLESS = new ConcurrentHashMap<>();
    private static final Map<CgRenderState, Map<CgCustomDepth, CgRenderState>> STENCILLED = new ConcurrentHashMap<>();

    private CgEnginePasses() {}

    /** Loads the engine's passes: their indices are taken before any consumer's. */
    public static void register() {
        // Loading the class registers them
    }

    private static void depth(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.DEPTH) == 0) return;
        out.draw(batch, batch.material(), depthPipeline(batch.material()));
    }

    private static void base(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.MAIN) != 0) out.draw(batch, batch.material());
    }

    private static void translucent(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0) return;
        if ((batch.flags() & CgMeshBatch.HALF_RESOLUTION) != 0 || after(batch) || separate(batch)) return;
        withGlow(batch, out);
    }

    /**
     * A translucent batch's Forward pass, and its Emissive pass after it at the same key, so its glow follows its colour
     * in the pass; the scene renderer folds the two into one where the glow merges, or leaves the glow out where no
     * HDR scene takes it.
     */
    private static void withGlow(CgMeshBatch batch, CgMeshPassCommands out) {
        out.draw(batch, batch.material());
        if ((batch.relevance() & CgPrimitiveRelevance.EMISSIVE) != 0) {
            out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.EMISSIVE));
        }
    }

    private static void afterDistortion(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 || (batch.flags() & CgMeshBatch.HALF_RESOLUTION) != 0) return;
        if (after(batch) && !separate(batch)) withGlow(batch, out);
    }

    private static void separate(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 || (batch.flags() & CgMeshBatch.HALF_RESOLUTION) != 0) return;
        if (separate(batch)) withGlow(batch, out);
    }

    private static void half(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 || (batch.flags() & CgMeshBatch.HALF_RESOLUTION) == 0) return;
        if (!covers(batch.material())) out.draw(batch, batch.material());
    }

    private static void volume(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 || (batch.flags() & CgMeshBatch.HALF_RESOLUTION) == 0) return;
        if (covers(batch.material())) out.draw(batch, batch.material());
    }

    /** Whether a translucent batch draws after the hazes: its row says so, or its material's queue does. */
    private static boolean after(CgMeshBatch batch) {
        return (batch.flags() & CgMeshBatch.AFTER_DISTORTION) != 0
                || batch.material().renderQueue() >= CgRenderQueue.AFTER_DISTORTION_THRESHOLD || separate(batch);
    }

    /** Whether a translucent batch is separate translucency: its material's state, or its sort layer. */
    private static boolean separate(CgMeshBatch batch) {
        return batch.separate() || CgMaterialProxy.of(batch.material()).separateTranslucency();
    }

    /** Whether a material's Forward pass covers what is behind it, premultiplied: a volume's blend. */
    public static boolean covers(CgMaterialInterface material) {
        CgPipeline forward = CgMeshPassCommands.pipeline(material, CgRenderPassVariant.FORWARD);
        CgBlendState blend = forward == null ? null : forward.state().getBlend();
        return blend != null && blend.enabled() && blend.srcRgb() == CgBlendState.PREMULTIPLIED_ALPHA.srcRgb()
                && blend.dstRgb() == CgBlendState.PREMULTIPLIED_ALPHA.dstRgb();
    }

    private static void emissive(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.EMISSIVE) == 0) return;
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.EMISSIVE));
    }

    private static void distortion(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.DISTORTION) == 0) return;
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.DISTORTION));
    }

    /**
     * A marked primitive's depth, writing its stencil: a translucent one only where its material writes depth, as
     * Unreal's {@code UseDefaultMaterial} path takes it (CustomDepthRendering.cpp:560-591).
     */
    private static void customDepth(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        CgCustomDepth mark = primitive.customDepth();
        if (mark == null || (batch.relevance() & CgPrimitiveRelevance.DEPTH) == 0) return;
        CgPipeline depth = depthPipeline(batch.material());
        if (depth != null) out.draw(batch, batch.material(), depth.withState(stencilled(depth.state(), mark)));
    }

    /**
     * A solid that moved or deforms this frame: its material's Velocity pass (Unreal's {@code
     * FOpaqueVelocityMeshProcessor}, {@code PrimitiveHasVelocityForFrame}). What stood still writes nothing; the camera's
     * motion over it is derived from depth by whoever reads velocity.
     */
    private static void velocity(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.MAIN) == 0 || !primitive.hasVelocity()) return;
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.VELOCITY));
    }

    private static void decal(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.DECAL) != 0) out.draw(batch, batch.material());
    }

    private static void customData(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        int r = batch.relevance();
        if ((r & CgPrimitiveRelevance.CUSTOM_DATA) == 0 || (r & CgPrimitiveRelevance.MAIN) == 0) return;
        CgPipeline forward = CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.FORWARD);
        out.draw(batch, batch.material(), forward == null ? null : forward.as(CgPipelineForm.CUSTOM_DATA));
    }

    /** A material's depth prepass, or its Forward pass writing depth alone; null while it does not parse. */
    @Nullable
    public static CgPipeline depthPipeline(CgMaterialInterface material) {
        CgPipeline forward = CgMeshPassCommands.pipeline(material, CgRenderPassVariant.FORWARD);
        if (forward == null) return null;
        if (CgMaterialProxy.of(material).hasDepthPrepass()) return forward.as(CgPipelineForm.DEPTH);
        CgRenderState own = forward.state();
        return forward.withState(COLOURLESS.computeIfAbsent(own, s -> s.withColorMask(CgColorMask.NONE)));
    }

    /**
     * {@code own} writing custom depth: depth tested near-or-equal and written, the stencil set to the mark's value in
     * its mask's bits, kept where depth fails under the full mask and replaced under a partial one
     * ({@code GetCustomDepthStencilState}, CustomDepthRendering.cpp:534-557).
     */
    private static CgRenderState stencilled(CgRenderState own, CgCustomDepth mark) {
        return STENCILLED.computeIfAbsent(own, s -> new ConcurrentHashMap<>()).computeIfAbsent(mark, m -> {
            CgRenderState depth = own.withDepth(CgDepthState.TEST_WRITE);
            return m.writeMask() == 0 ? depth
                    : depth.withStencil(CgStencilState.write(m.value(), m.writeMask(), m.writeMask() != 0xFF));
        });
    }
}
