package io.github.somehussar.crystalgraphics.api.state;

/**
 * Immutable composite render state assembled from typed slots.
 *
 * <p>One {@code CgRenderState} per render layer or material pass. Each slot (blend,
 * depth, cull, stencil) is an independently shareable object — the same
 * {@link CgBlendState#ALPHA} instance can be used across multiple render states.</p>
 *
 * <h3>Apply/Clear Contract</h3>
 * <p>{@link #apply()} enables blend/depth/cull/stencil state for the current pass.
 * {@link #clear()} reverses all state changes: disables cull, resets depth (test off,
 * write on, func GL_LESS), disables blend and resets equations, and disables stencil.
 * This bracketed apply/clear pattern prevents GL state leaks between passes.</p>
 *
 * <p>The render state does <strong>not</strong> own shaders or textures — those are
 * the caller's responsibility.</p>
 *
 * @see CgBlendState
 * @see CgDepthState
 * @see CgCullState
 * @see CgStencilState
 */
public final class CgRenderState {

    /**
     * Default opaque-geometry state: blend disabled, depth test+write (LEqual),
     * back-face culling, stencil disabled.
     */
    public static final CgRenderState DEFAULT = builder().build();

    private final CgBlendState blend;
    private final CgDepthState depth;
    private final CgCullState cull;
    private final CgStencilState stencil;

    private CgRenderState(Builder b) {
        this.blend   = b.blend   != null ? b.blend   : CgBlendState.DISABLED;
        this.depth   = b.depth   != null ? b.depth   : CgDepthState.TEST_WRITE;
        this.cull    = b.cull    != null ? b.cull    : CgCullState.BACK;
        this.stencil = b.stencil != null ? b.stencil : CgStencilState.DISABLED;
    }

    /**
     * Applies all state slots to the current GL context: blend, depth, cull, stencil.
     */
    public void apply() {
        blend.apply();
        depth.apply();
        cull.apply();
        stencil.apply();
    }

    /**
     * Restores all slots to GL defaults: blend disabled (equations reset), depth test
     * disabled (write on, func GL_LESS), cull disabled, stencil disabled.
     */
    public void clear() {
        CgBlendState.clearToDefault();
        depth.clear();
        cull.clear();
        CgStencilState.DISABLED.apply();
    }

    public CgBlendState   getBlend()   { return blend; }
    public CgDepthState   getDepth()   { return depth; }
    public CgCullState    getCull()    { return cull; }
    public CgStencilState getStencil() { return stencil; }

    /** Returns a builder with default values: blend=DISABLED, depth=TEST_WRITE, cull=BACK, stencil=DISABLED. */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CgBlendState   blend;
        private CgDepthState   depth;
        private CgCullState    cull;
        private CgStencilState stencil;

        private Builder() {}

        public Builder blend(CgBlendState blend)     { this.blend   = blend;   return this; }
        public Builder depth(CgDepthState depth)     { this.depth   = depth;   return this; }
        public Builder cull(CgCullState cull)        { this.cull    = cull;    return this; }
        public Builder stencil(CgStencilState s)     { this.stencil = s;       return this; }
        public CgRenderState build()                 { return new CgRenderState(this); }
    }
}
