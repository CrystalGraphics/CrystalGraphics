package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.world.CgPipelineList;

import java.util.HashMap;
import java.util.Map;

/**
 * The instances of {@code text.shader} text draws with: one per tier, screen or world, label form, depth cut,
 * {@link CgTextGamma} and blend, each made once with its keywords, its {@code TextData} and the gradient ramps fixed;
 * and a Text-domain material's, for a layer it paints. Screen text tests no depth: it
 * paints in submit order over whatever the host left there, as a GUI does; world text tests the scene's. Never toggled, so neighbouring glyphs of one atlas and
 * tier stay one canvas run. Any thread; released with the context ({@link #releaseAll}).
 *
 * <pre>{@code
 * CgPipelinePrecache.register("crystalgraphics:world", CgTextMaterials::precache);
 * }</pre>
 */
public final class CgTextMaterials {

    public static final String TEXT_SHADER = "crystalgraphics:shaders/canvas/text.shader";

    /** {@code TextData}: the gamma correction and a label depth pass's discard. */
    private static final CgBufferFormat TEXT_DATA_FORMAT = CgBufferFormat
            .builder("TextData", CgBufferFormat.MemoryLayout.STD140)
            .vec4("u_TextGammaSmall")
            .vec4("u_TextGammaLarge")
            .vec4("u_TextGammaRamp")
            // x: the alpha below which a fragment is discarded; 0 but in a label depth pass
            .vec4("u_TextCut")
            .build();

    /** {@code TextData}'s declaration; each instance overrides it with its own bytes. */
    private static final CgUniformBuffer TEXT_DATA_UBO = CgShaderBufferRegistry.get().getOrCreateUboInternal(
            TEXT_DATA_FORMAT, "TextData", CgBindingPoints.TEXT_DATA_UBO, CgBufferLifetime.FRAME);

    /** The alpha a label's depth pass writes from: half covered, so an antialiased edge stays see-through. */
    static final float DEPTH_PASS_CUT = 0.5f;

    private record Key(boolean msdf, boolean screen, boolean label, boolean depthPass, CgTextGamma gamma,
                       boolean additive, boolean linear) {
    }

    private record PaintKey(CgMaterialDefinition definition, boolean msdf) {
    }

    private record StateKey(CgRenderState state, boolean screen, boolean additive) {
    }

    private static final CgMaterialState SCREEN = CgMaterialState.builder().disableDepthTest().build();
    private static final CgMaterialState ADDITIVE = CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build();
    private static final CgMaterialState SCREEN_ADDITIVE = CgMaterialState.builder().disableDepthTest()
            .blend(CgBlendMode.ADDITIVE).build();

    private static final Map<Key, CgMaterial> MATERIALS = new HashMap<>();
    private static final Map<PaintKey, CgMaterial> PAINTS = new HashMap<>();
    private static final Map<StateKey, CgRenderState> PAINT_STATES = new HashMap<>();

    private CgTextMaterials() {
    }

    /** The {@code text} engine token's block ({@code CgEngineBufferRegistry}). */
    public static CgUniformBuffer textData() {
        return TEXT_DATA_UBO;
    }

    /** Names {@code text.shader} in every form a draw takes: on quads and as world labels, bitmap and MSDF. */
    public static void precache(CgPipelineList list) {
        list.add(TEXT_SHADER);
        list.addPass(TEXT_SHADER, CgInstanceKind.QUAD);
    }

    /**
     * A canvas glyph's material: {@code msdf} for distance-field atlases, {@code screen} under a screen camera,
     * {@code linear} into a float target, whose fills take the linear-blend correction in place of {@code gamma}.
     */
    static CgMaterial of(boolean msdf, boolean screen, CgTextGamma gamma, boolean additive, boolean linear) {
        return get(new Key(msdf, screen, false, false, linear ? CgTextGamma.NONE : gamma, additive, linear));
    }

    /**
     * A Text-domain material's instance for a layer it paints, on {@code msdf} glyphs; drawn under {@link #paintState}.
     */
    static synchronized CgMaterial paint(CgMaterialDefinition definition, boolean msdf) {
        return PAINTS.computeIfAbsent(new PaintKey(definition, msdf), k -> {
            CgMaterial m = CgMaterial.newInstance(definition, null);
            m.toggleKeyword("MSDF_MODE", msdf);
            m.overrideBlock(TEXT_DATA_UBO, block(CgTextGamma.initial(), 0f));
            return m;
        });
    }

    /**
     * The state a material's layer draws under: its pipeline's, premultiplied over or added, and untested on a screen,
     * as the built-in instances are. Null when its pipeline is not built yet.
     */
    static synchronized CgRenderState paintState(CgMaterial material, boolean screen, boolean additive) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.QUAD);
        if (pipeline == null) return null;
        return PAINT_STATES.computeIfAbsent(new StateKey(pipeline.state(), screen, additive), k -> {
            CgRenderState s = k.state().withBlend((additive ? CgBlendMode.ADDITIVE : CgBlendMode.PREMULTIPLIED).state());
            return screen ? s.withDepth(CgDepthState.NONE) : s;
        });
    }

    /** A retained world label's material; {@code depthPass} discards below half coverage, {@code linear} as {@link #of}. */
    static CgMaterial label(boolean msdf, boolean depthPass, CgTextGamma gamma, boolean linear) {
        boolean corrected = linear && !depthPass;
        return get(new Key(msdf, false, true, depthPass, corrected ? CgTextGamma.NONE : gamma, false, corrected));
    }

    private static synchronized CgMaterial get(Key key) {
        CgMaterial m = MATERIALS.get(key);
        if (m == null) {
            CgMaterialState state = key.screen() ? key.additive() ? SCREEN_ADDITIVE : SCREEN : key.additive() ? ADDITIVE : null;
            m = state != null ? CgMaterial.newInstance(TEXT_SHADER, state) : CgMaterial.newInstance(TEXT_SHADER);
            m.toggleKeyword("MSDF_MODE", key.msdf());
            m.toggleKeyword("WORLD_LABEL", key.label());
            m.overrideBlock(TEXT_DATA_UBO, key.linear() ? linearBlock() : block(key.gamma(), key.depthPass() ? DEPTH_PASS_CUT : 0f));
            m.applyProperties(v -> v.sampler("_Gradients", 1, CgTextGradients.texture()));
            MATERIALS.put(key, m);
        }
        return m;
    }

    private static CgBufferWriter block(CgTextGamma gamma, float cut) {
        CgTextGamma.Level small = gamma.small(), large = gamma.large();
        CgBufferWriter w = new CgBufferWriter(new CgStagingBuffer(TEXT_DATA_FORMAT.getFloatCount()), TEXT_DATA_FORMAT);
        w.beginRecord()
                .vec4("u_TextGammaSmall", small.exponent(), small.contrast(), 1f / small.exponent(), 0f)
                .vec4("u_TextGammaLarge", large.exponent(), large.contrast(), 1f / large.exponent(), 0f)
                .vec4("u_TextGammaRamp", gamma.smallPx(), gamma.largePx(), gamma.isIdentity() ? 0f : 1f, 0f)
                .vec4("u_TextCut", cut, 0f, 0f, 0f);
        return w;
    }

    /** A float target's: fills corrected for its linear blend at the encoding's gamma (text_linear_coverage). */
    private static CgBufferWriter linearBlock() {
        CgBufferWriter w = new CgBufferWriter(new CgStagingBuffer(TEXT_DATA_FORMAT.getFloatCount()), TEXT_DATA_FORMAT);
        w.beginRecord()
                .vec4("u_TextGammaSmall", ENCODING_GAMMA, 0f, 1f / ENCODING_GAMMA, 0f)
                .vec4("u_TextGammaLarge", ENCODING_GAMMA, 0f, 1f / ENCODING_GAMMA, 0f)
                .vec4("u_TextGammaRamp", 0f, 0f, 0f, 1f)
                .vec4("u_TextCut", 0f, 0f, 0f, 0f);
        return w;
    }

    /** sRGB's, near enough: what a blend in the encoded space blends in. */
    private static final float ENCODING_GAMMA = 2.2f;

    /** Drops every instance: the context is going, and the materials with it. */
    public static synchronized void releaseAll() {
        MATERIALS.clear();
        PAINTS.clear();
        PAINT_STATES.clear();
        CgTextGradients.releaseAll();
    }
}
