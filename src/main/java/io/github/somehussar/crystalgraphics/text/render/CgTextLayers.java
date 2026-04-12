package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.render.CgDynamicTextureRenderLayer;
import io.github.somehussar.crystalgraphics.gl.render.CgLayer;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.state.CgTextureState;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import org.lwjgl.opengl.GL11;

/**
 * Cg-owned text render layer factory and typed key holder.
 *
 * <p>This class provides the canonical factory methods for creating text render
 * layers that target the new batching/layer architecture. Text rendering remains
 * owned by CrystalGraphics; CgUi consumes these factories but does not redefine
 * them.</p>
 *
 * <h3>Architecture Role</h3>
 * <p>{@code CgTextLayers} is the Cg-side counterpart to {@code CgUiLayers}. While
 * CgUi defines layer keys and factories for UI-specific concerns (solid, panel,
 * rounded, overlay), this class defines the text layer key and factory. CgUi
 * registers the text layer from here into its {@code CgBufferSource} assembly.</p>
 *
 * <h3>Dynamic Texture Layer</h3>
 * <p>Text layers use {@link CgDynamicTextureRenderLayer} because the active atlas
 * page texture changes mid-frame as glyphs from different atlas pages are submitted.
 * The dynamic layer handles texture transitions by flushing when the texture ID
 * changes, ensuring correct batching without manual caller intervention.</p>
 *
 * <h3>Shader Contract</h3>
 * <p>The MSDF text layer expects a shader that accepts:</p>
 * <ul>
 *   <li>{@code u_atlas} — sampler2D uniform bound to texture unit 0</li>
 *   <li>{@code u_projection} — mat4 projection uniform (set via {@code CgRenderState})</li>
 *   <li>{@code u_pxRange} — float SDF pixel range (set per-batch by the text renderer)</li>
 *   <li>{@code u_modelview} — mat4 model-view uniform (set per-draw by the text renderer)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In CgUi buffer source assembly:
 * CgBufferSource uiSource = CgBufferSource.builder()
 *     .layer(CgUiLayers.SOLID,   CgUiLayers.solid(solidShader))
 *     .layer(CgUiLayers.TEXT,    CgTextLayers.msdf(msdfShader))
 *     .build();
 * }</pre>
 *
 * @see CgDynamicTextureRenderLayer
 * @see CgLayer.Key
 */
public final class CgTextLayers {

    /**
     * Typed key for the MSDF text render layer.
     *
     * <p>This key is used to register and retrieve the text layer from a
     * {@code CgBufferSource}. CgUi re-exports this key (or defines its own
     * UI-scoped key) when assembling its buffer source.</p>
     */
    public static final CgLayer.Key<CgDynamicTextureRenderLayer> MSDF = new CgLayer.Key<>("cg:text_msdf");

    /**
     * Creates an MSDF text render layer with the given shader.
     *
     * <p>The layer is configured with:</p>
     * <ul>
     *   <li>Standard alpha blending ({@link CgBlendState#ALPHA})</li>
     *   <li>Dynamic texture on unit 0 with sampler uniform {@code "u_atlas"}</li>
     *   <li>{@link CgVertexFormat#POS2_UV2_COL4UB} vertex format</li>
     *   <li>Initial staging capacity for 4096 quads</li>
     * </ul>
     *
     * @param shader the MSDF text shader (must support {@code u_atlas}, {@code u_projection})
     * @return a new dynamic texture render layer configured for MSDF text
     */
    public static CgDynamicTextureRenderLayer msdf(CgShader shader) {
        return CgDynamicTextureRenderLayer.create(
            "cg:text_msdf",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ALPHA)
                .texture(CgTextureState.dynamic(GL11.GL_TEXTURE_2D, 0, "u_atlas"))
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            4096
        );
    }

    /**
     * Creates a bitmap text render layer with the given shader.
     *
     * <p>Similar to {@link #msdf(CgShader)} but intended for bitmap text rendering
     * where no distance-field reconstruction is needed. The shader should not require
     * a {@code u_pxRange} uniform.</p>
     *
     * @param shader the bitmap text shader
     * @return a new dynamic texture render layer configured for bitmap text
     */
    public static CgDynamicTextureRenderLayer bitmap(CgShader shader) {
        return CgDynamicTextureRenderLayer.create(
            "cg:text_bitmap",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ALPHA)
                .texture(CgTextureState.dynamic(GL11.GL_TEXTURE_2D, 0, "u_atlas"))
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            4096
        );
    }

    // Prevent instantiation
    private CgTextLayers() {}
}
