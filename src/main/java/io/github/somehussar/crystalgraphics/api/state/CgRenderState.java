package io.github.somehussar.crystalgraphics.api.state;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;

import org.joml.Matrix4f;

/**
 * Immutable composite render state assembled from typed slots.
 *
 * <p>One {@code CgRenderState} per render layer. Each slot (blend, depth, cull,
 * texture) is an independently shareable object — the same {@link CgBlendState#ALPHA}
 * instance can be used across multiple render states.</p>
 *
 * <h3>Apply/Clear Contract</h3>
 * <p>{@link #apply(Matrix4f)} binds the shader, sets the projection uniform, binds
 * the texture (if any), and enables blend/depth/cull state. {@link #clear()} reverses
 * all state changes in reverse order: disables cull, disables depth, disables blend,
 * unbinds texture, and unbinds the shader. This bracketed apply/clear pattern ensures
 * no GL state leaks between layers.</p>
 *
 * <p>The render state does <strong>not</strong> own the shader or texture — it holds
 * references and delegates lifecycle to the caller.</p>
 *
 * @see CgBlendState
 * @see CgDepthState
 * @see CgCullState
 * @see CgTextureState
 */
public final class CgRenderState {

    private final CgShader shader;
    private final CgBlendState blend;
    private final CgDepthState depth;
    private final CgCullState cull;
    private final CgTextureState texture;
    private final String projectionUniform;

    private CgRenderState(Builder b) {
        this.shader = b.shader;
        this.blend = b.blend != null ? b.blend : CgBlendState.ALPHA;
        this.depth = b.depth != null ? b.depth : CgDepthState.NONE;
        this.cull = b.cull != null ? b.cull : CgCullState.NONE;
        this.texture = b.texture != null ? b.texture : CgTextureState.none();
        this.projectionUniform = b.projectionUniform != null ? b.projectionUniform : "u_projection";
    }

    public void apply(Matrix4f projection) {
        apply(projection, -1);
    }

    public void apply(Matrix4f projection, int overrideTextureId) {
        if (!shader.isCompiled()) return;
        
        shader.applyBindings(b -> b.mat4(projectionUniform, projection)).bind();
        texture.apply(shader, overrideTextureId);
        blend.apply();
        depth.apply();
        cull.apply();
    }

    public void clear() {
        cull.clear();
        depth.clear();
        CgBlendState.DISABLED.apply();
        texture.clear();
        shader.unbind();
    }

    public CgShader getShader() { return shader; }

    public static Builder builder(CgShader shader) { return new Builder(shader); }

    public static final class Builder {
        private final CgShader shader;
        private CgBlendState blend;
        private CgDepthState depth;
        private CgCullState cull;
        private CgTextureState texture;
        private String projectionUniform;

        private Builder(CgShader shader) { this.shader = shader; }

        public Builder blend(CgBlendState blend) { this.blend = blend; return this; }
        public Builder depth(CgDepthState depth) { this.depth = depth; return this; }
        public Builder cull(CgCullState cull) { this.cull = cull; return this; }
        public Builder texture(CgTextureState texture) { this.texture = texture; return this; }
        public Builder projectionUniform(String projectionUniform) { this.projectionUniform = projectionUniform; return this; }
        public CgRenderState build() { return new CgRenderState(this); }
    }
}
