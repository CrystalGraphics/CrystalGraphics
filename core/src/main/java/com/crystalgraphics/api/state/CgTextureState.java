package com.crystalgraphics.api.state;


import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.github.bsideup.jabel.Desugar;

/**
 * Texture-bind policy slot for {@link CgRenderState}.
 *
 * <p>Supports three modes:</p>
 * <ul>
 *   <li><strong>{@link #none()}</strong> — no texture binding; {@link #apply} and {@link #clear}
 *       are effectively no-ops (no sampler uniform to set).</li>
 *   <li><strong>{@link #fixed(int, int, int, String)}</strong> — binds a known texture
 *       at apply time using a raw (target, textureId) pair. Use for
 *       layers with a single atlas or sprite sheet.</li>
 *   <li><strong>{@link #fixed(CgTexture, int, String)}</strong> — convenience for
 *       binding a {@link CgTexture} directly.</li>
 *   <li><strong>{@link #dynamic(int, int, String)}</strong> — texture ID is supplied per-flush
 *       via the {@code overrideTextureId} parameter of {@link #apply}. Used for
 *       text rendering and any layer whose active texture changes mid-frame.</li>
 * </ul>
 *
 * <p>This type is the render-state <em>policy</em>: it owns texture unit selection
 * and sampler uniform propagation. The raw GL identity is just a (target, id) pair.</p>
 *
 * @see CgRenderState
 */
@Desugar
public record CgTextureState(int target, int unit, String samplerUniform,
                             int fixedTextureId, boolean hasFixed, boolean dynamic) {

    /** No texture bound; apply/clear no-op. */
    public static CgTextureState none() {
        return new CgTextureState(CgGL.GL_TEXTURE_2D, 0, null, 0, false, false);
    }

    /**
     * Fixed-texture state from a raw GL identity.
     *
     * @param target         GL texture target (e.g. {@code GL_TEXTURE_2D})
     * @param textureId      GL texture object id
     * @param unit           zero-based texture unit
     * @param samplerUniform sampler uniform name in the shader
     */
    public static CgTextureState fixed(int target, int textureId, int unit, String samplerUniform) {
        return new CgTextureState(target, unit, samplerUniform, textureId, true, false);
    }

    /**
     * Fixed-texture state from a {@link CgTexture}. The texture is referenced
     * by id and target — its lifecycle is the caller's responsibility.
     */
    public static CgTextureState fixed(CgTexture texture, int unit, String samplerUniform) {
        return new CgTextureState(texture.getTarget(), unit, samplerUniform, texture.getId(), true, false);
    }

    /**
     * Dynamic state: texture id is supplied per-flush via
     * {@link #apply(CgShader, int)}'s {@code overrideTextureId}.
     */
    public static CgTextureState dynamic(int target, int unit, String samplerUniform) {
        return new CgTextureState(target, unit, samplerUniform, 0, false, true);
    }

    public void apply(CgShader shader, int overrideTextureId) {
        int textureId = hasFixed ? fixedTextureId : overrideTextureId;
        if (samplerUniform == null || textureId < 0) return;

        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
        CgGL.glBindTexture(target, textureId);

        CgShaderProgram program = shader.getProgram();
        int loc = shader.getUniformLocation(samplerUniform);
        if (program != null && loc >= 0) {
            program.setSampler(loc, unit);
        }
    }

    public void clear() {
        if (samplerUniform == null) return;
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
        CgGL.glBindTexture(target, 0);
    }

    public boolean isDynamic() {
        return dynamic;
    }
}
