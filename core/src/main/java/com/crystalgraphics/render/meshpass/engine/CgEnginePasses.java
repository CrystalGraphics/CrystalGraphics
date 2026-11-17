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
 *   <li>{@link #SEPARATE}: separate translucency (a material whose {@code TranslucencyPass} is after depth of field),
 *       drawn into a target of its own, laid over the scene after depth of field (Unreal's {@code TranslucencyAfterDOF}).</li>
 *   <li>{@link #HALF}: what blends by adding, marked half-resolution, drawn at half size and added over the scene.</li>
 *   <li>{@link #VOLUME}: what covers premultiplied (smoke, a volume), marked half-resolution: marched at half size and
 *       blended with its last frame.</li>
 *   <li>{@link #EMISSIVE}: what names {@code s.emissive}, its Emissive pass, into the bloom's input.</li>
 *   <li>{@link #DISTORTION}: what names {@code s.refraction}, its Distortion pass, into the distortion target
 *       ({@code Distortion}).</li>
 *   <li>{@link #CUSTOM_DEPTH}: primitives marked {@code customDepth}, depth alone writing their stencil value
 *       ({@code CustomDepth}).</li>
 *   <li>{@link #CUSTOM_DATA}: solids whose material writes custom data, into its target.</li>
 *   <li>{@link #VELOCITY}: solids with a velocity this frame ({@code hasVelocity}, or a material moving its vertices
 *       by time), their Velocity pass, into an RG16 target cleared to 0 ({@code Velocity}). Built every frame.</li>
 *   <li>{@link #VELOCITY_WITH_STILL}: every solid, those still or keeping no previous transform writing no motion of
 *       their own: a written texel then means the engine drew it (Unreal's
 *       {@code VelocityIncludeStationaryPrimitives}), what temporal AA keeps to.</li>
 *   <li>{@link #TRANSLUCENT_VELOCITY}: what blends with {@code OutputVelocity On}, its Velocity pass writing velocity
 *       and depth where it covers, into the velocity target over a copy of the scene's depth (Unreal's early translucency
 *       velocity, {@code FTranslucentVelocityMeshProcessor}).</li>
 *   <li>{@link #RESPONSIVE}: what blends, its Responsive pass, its mark into an R8 target where it covers: 1 with
 *       {@code ResponsiveAA On} (Unreal's Responsive AA), where temporal AA keeps a short history; 0.5 with it Off.</li>
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
    /**
     * A light's shadow depth, Unreal's {@code CSMShadowDepth} (FShadowDepthPassMeshProcessor): every solid casting
     * shadows, through its own depth pipeline and so its own vertex factory (a skinned mesh posed, a masked one clipped).
     */
    public static final CgMeshPassType SHADOW_DEPTH = CgMeshPassType.register("crystalgraphics:shadow_depth",
            CgPassLayout.depthOnly(CgFormat.DEPTH32_FLOAT), CgEnginePasses::shadowDepth);
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
    /**
     * The velocity target's format: Unreal's encoding over two 16-bit unsigned normalized channels, 0 meaning nothing wrote
     * ({@code lib/velocity.glsl}). Not half floats: at the encoding's 0.5 bias they step a texel and more, so a written
     * velocity loses the jitter it carries.
     */
    public static final CgFormat VELOCITY_FORMAT = CgFormat.RG16_UNORM;
    public static final CgMeshPassType VELOCITY = CgMeshPassType.registerPerFrame("crystalgraphics:velocity",
            CgPassLayout.color(VELOCITY_FORMAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::velocity);
    public static final CgMeshPassType VELOCITY_WITH_STILL = CgMeshPassType.registerPerFrame("crystalgraphics:velocity.still",
            CgPassLayout.color(VELOCITY_FORMAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::velocityWithStill);
    /** The responsive target's format: 1 where a blended draw covers, 0 elsewhere. */
    public static final CgMeshPassType TRANSLUCENT_VELOCITY = CgMeshPassType.registerPerFrame("crystalgraphics:velocity.translucent",
            CgPassLayout.color(VELOCITY_FORMAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::translucentVelocity);

    public static final CgFormat RESPONSIVE_FORMAT = CgFormat.R8_UNORM;
    public static final CgMeshPassType RESPONSIVE = CgMeshPassType.register("crystalgraphics:responsive",
            CgPassLayout.color(RESPONSIVE_FORMAT).depth(CgFormat.DEPTH32_FLOAT), CgEnginePasses::responsive);
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

    private static void shadowDepth(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.flags() & CgMeshBatch.CASTS_SHADOW) == 0) return;
        if ((batch.relevance() & (CgPrimitiveRelevance.MAIN | CgPrimitiveRelevance.DEPTH)) != (CgPrimitiveRelevance.MAIN | CgPrimitiveRelevance.DEPTH)) return;
        CgPipeline depth = depthPipeline(batch.material());
        if (depth != null) out.draw(batch, batch.material(), depth);
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

    /** Whether a translucent batch draws into the separate translucency's target: its material's pass, or its sort layer. */
    private static boolean separate(CgMeshBatch batch) {
        return batch.separate() || CgMaterialProxy.of(batch.material()).translucencyPass().apart();
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
     * Unreal's {@code UseDefaultMaterial} path takes it (CustomDepthRendering.cpp:560-591). A graded solid row is drawn
     * too, its look id in the stencil's bits 5-7 ({@link CgCustomDepth#withLook}): the record the look weights read.
     */
    private static void customDepth(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        CgCustomDepth mark = primitive.customDepth();
        int look = (batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 ? batch.look() : 0;
        if (look != 0) mark = (mark == null ? CgCustomDepth.DEPTH : mark).withLook(look);
        if (mark == null || (batch.relevance() & CgPrimitiveRelevance.DEPTH) == 0) return;
        CgPipeline depth = depthPipeline(batch.material());
        if (depth != null) out.draw(batch, batch.material(), depth.withState(stencilled(depth.state(), mark)));
    }

    /**
     * A solid with a velocity this frame: its material's Velocity pass (Unreal's {@code FOpaqueVelocityMeshProcessor},
     * {@code PrimitiveHasVelocityForFrame}). What stood still writes nothing; the camera's motion over it is derived from
     * depth by whoever reads velocity.
     */
    private static void velocity(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.MAIN) == 0) return;
        if (!primitive.hasVelocity()
                && !(primitive.keepsPrevious() && CgMaterialProxy.of(batch.material()).animatesVertices())) {
            return;
        }
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.VELOCITY));
    }

    /** What blends and outputs velocity ({@link #TRANSLUCENT_VELOCITY}), still or moving: it writes the depth too. */
    private static void translucentVelocity(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0 || !CgMaterialProxy.of(batch.material()).outputsVelocity()) {
            return;
        }
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.VELOCITY));
    }

    /** What blends: its material's Responsive pass ({@link #RESPONSIVE}). */
    private static void responsive(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0) return;
        out.draw(batch, batch.material(), CgMeshPassCommands.pipeline(batch.material(), CgRenderPassVariant.RESPONSIVE));
    }

    /** Every solid, moving or still: {@link #VELOCITY_WITH_STILL}. A frame record reads its model as last frame's. */
    private static void velocityWithStill(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
        if ((batch.relevance() & CgPrimitiveRelevance.MAIN) == 0) return;
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
     * ({@code GetCustomDepthStencilState}, CustomDepthRendering.cpp:534-557). A mark carrying a look is always kept
     * where depth fails: a look id written behind would claim the pixel of what is in front.
     */
    private static CgRenderState stencilled(CgRenderState own, CgCustomDepth mark) {
        return STENCILLED.computeIfAbsent(own, s -> new ConcurrentHashMap<>()).computeIfAbsent(mark, m -> {
            CgRenderState depth = own.withDepth(CgDepthState.TEST_WRITE);
            boolean replaceOnFail = m.look() == 0 && m.writeMask() != CgCustomDepth.MARK_BITS;
            return m.writeMask() == 0 ? depth : depth.withStencil(CgStencilState.write(m.value(), m.writeMask(), replaceOnFail));
        });
    }
}
