package com.crystalgraphics.text.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.world.CgPipelineList;

import java.util.HashMap;
import java.util.Map;

/**
 * The instances of {@code text.shader} text draws with: one per tier, screen or world, label form, depth cut and
 * {@link CgTextGamma}, each made once with its keywords and {@code TextData} fixed. Screen text tests no depth: it
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

    private record Key(boolean msdf, boolean screen, boolean label, boolean depthPass, CgTextGamma gamma) {
    }

    private static final CgMaterialState SCREEN = CgMaterialState.builder().disableDepthTest().build();

    private static final Map<Key, CgMaterial> MATERIALS = new HashMap<>();

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

    /** A canvas glyph's material: {@code msdf} for distance-field atlases, {@code screen} under a screen camera. */
    static CgMaterial of(boolean msdf, boolean screen, CgTextGamma gamma) {
        return get(new Key(msdf, screen, false, false, gamma));
    }

    /** A retained world label's material; {@code depthPass} discards below half coverage. */
    static CgMaterial label(boolean msdf, boolean depthPass, CgTextGamma gamma) {
        return get(new Key(msdf, false, true, depthPass, gamma));
    }

    private static synchronized CgMaterial get(Key key) {
        CgMaterial m = MATERIALS.get(key);
        if (m == null) {
            m = key.screen() ? CgMaterial.newInstance(TEXT_SHADER, SCREEN) : CgMaterial.newInstance(TEXT_SHADER);
            m.toggleKeyword("MSDF_MODE", key.msdf());
            m.toggleKeyword("WORLD_LABEL", key.label());
            m.overrideBlock(TEXT_DATA_UBO, block(key.gamma(), key.depthPass() ? DEPTH_PASS_CUT : 0f));
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

    /** Drops every instance: the context is going, and the materials with it. */
    public static synchronized void releaseAll() {
        MATERIALS.clear();
    }
}
