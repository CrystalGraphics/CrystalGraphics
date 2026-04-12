package io.github.somehussar.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Texture-bind policy slot for {@link CgRenderState}.
 *
 * <p>Supports three modes:</p>
 * <ul>
 *   <li><strong>{@link #none()}</strong> — no texture binding; {@link #apply} and {@link #clear}
 *       are effectively no-ops (no sampler uniform to set).</li>
 *   <li><strong>{@link #fixed(CgTextureBinding, int, String)}</strong> — binds a known texture
 *       at apply time. The texture ID comes from the {@link CgTextureBinding}. Used for
 *       layers with a single atlas or sprite sheet.</li>
 *   <li><strong>{@link #dynamic(int, int, String)}</strong> — texture ID is supplied per-flush
 *       via the {@code overrideTextureId} parameter of {@link #apply}. Used for
 *       text rendering and any layer whose active texture changes mid-frame.</li>
 * </ul>
 *
 * <h3>Relationship to {@code CgTextureBinding}</h3>
 * <p>{@link CgTextureBinding} (in {@code api/vertex/}) is a lightweight value holding
 * (target, textureId). This class is the render-state <em>policy</em> that wraps a
 * binding with texture unit selection and sampler uniform propagation. The two compose:
 * {@code CgTextureState.fixed(binding, unit, sampler)} delegates the texture identity
 * to the binding while owning the unit/sampler policy.</p>
 *
 * @see CgRenderState
 * @see CgTextureBinding
 */
@Desugar
public record CgTextureState(int target, int unit, String samplerUniform,
                             CgTextureBinding fixedBinding, boolean dynamic) {

    public static CgTextureState none() {
        return new CgTextureState(GL11.GL_TEXTURE_2D, 0, null, null, false);
    }

    public static CgTextureState fixed(CgTextureBinding binding, int unit, String samplerUniform) {
        return new CgTextureState(binding.getTarget(), unit, samplerUniform, binding, false);
    }

    public static CgTextureState dynamic(int target, int unit, String samplerUniform) {
        return new CgTextureState(target, unit, samplerUniform, null, true);
    }

    public void apply(CgShader shader, int overrideTextureId) {
        int textureId = fixedBinding != null ? fixedBinding.getTextureId() : overrideTextureId;
        if (samplerUniform == null || textureId < 0) return;

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(target, textureId);

        CgShaderProgram program = shader.getProgram();
        int loc = shader.getUniformLocation(samplerUniform);
        if (program != null && loc >= 0) {
            program.setSampler(loc, unit);
        }
    }

    public void clear() {
        if (samplerUniform == null) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(target, 0);
    }

    public boolean isDynamic() {
        return dynamic;
    }
}
