package io.github.somehussar.crystalgraphics.api.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable composite render state assembled from typed slots.
 *
 * <p>One {@code CgRenderState} per render layer or material pass. Each slot (blend,
 * depth, cull, stencil, alpha, colorMasks) is an independently shareable object — the
 * same {@link CgBlendState#ALPHA} instance can be used across multiple render states.</p>
 *
 * <h3>Apply/Clear Contract</h3>
 * <p>{@link #apply()} enables blend/depth/cull/stencil/alpha/colorMask state for
 * the current pass. {@link #clear()} reverses all state changes: disables cull, resets
 * depth (test off, write on, func GL_LESS), disables blend and resets equations, disables
 * stencil, disables alpha test, and restores all-channel color writes. This bracketed
 * apply/clear pattern prevents GL state leaks between passes.</p>
 *
 * <p>The render state does <strong>not</strong> own shaders or textures — those are
 * the caller's responsibility.</p>
 *
 * @see CgBlendState
 * @see CgDepthState
 * @see CgCullState
 * @see CgStencilState
 * @see CgAlphaState
 * @see CgColorMask
 */
public final class CgRenderState {

    /**
     * Default opaque-geometry state: blend disabled, depth test+write (LEqual),
     * back-face culling, stencil disabled, alpha test disabled, color mask all channels.
     */
    public static final CgRenderState DEFAULT = builder().build();

    private final CgStencilState     stencil;
    private final CgBlendState       blend;
    private final CgDepthState       depth;
    private final CgCullState        cull;
    private final CgAlphaState       alpha;
    private final List<CgColorMask>  colorMasks;

    private CgRenderState(Builder b) {
        this.alpha      = b.alpha   != null ? b.alpha   : CgAlphaState.DISABLED;
        this.blend      = b.blend   != null ? b.blend   : CgBlendState.DISABLED;
        this.depth      = b.depth   != null ? b.depth   : CgDepthState.TEST_WRITE;
        this.cull       = b.cull    != null ? b.cull    : CgCullState.BACK;
        this.stencil    = b.stencil != null ? b.stencil : CgStencilState.DISABLED;
        this.colorMasks = Collections.unmodifiableList(new ArrayList<>(b.colorMasks));
    }

    public void apply() {
        alpha.apply();
        blend.apply();
        depth.apply();
        cull.apply();
        stencil.apply();
        for (CgColorMask mask : colorMasks) mask.apply();
    }

    public void clear() {
        alpha.clear();
        CgBlendState.clearToDefault();
        depth.clear();
        cull.clear();
        CgStencilState.DISABLED.apply();
        if (!colorMasks.isEmpty()) CgColorMask.clearToDefault();
    }

    public CgAlphaState      getAlpha()      { return alpha; }
    public CgBlendState      getBlend()      { return blend; }
    public CgDepthState      getDepth()      { return depth; }
    public CgCullState       getCull()       { return cull; }
    public CgStencilState    getStencil()    { return stencil; }
    public List<CgColorMask> getColorMasks() { return colorMasks; }

    /** Returns a builder with default values: blend=DISABLED, depth=TEST_WRITE, cull=BACK,
     *  stencil=DISABLED, alpha=DISABLED, colorMasks=empty. */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CgAlphaState      alpha;
        private CgBlendState      blend;
        private CgDepthState      depth;
        private CgCullState       cull;
        private CgStencilState    stencil;
        private final List<CgColorMask> colorMasks = new ArrayList<>();

        private Builder() {}

        public Builder alpha(CgAlphaState alpha)     { this.alpha   = alpha;   return this; }
        public Builder blend(CgBlendState blend)     { this.blend   = blend;   return this; }
        public Builder depth(CgDepthState depth)     { this.depth   = depth;   return this; }
        public Builder cull(CgCullState cull)        { this.cull    = cull;    return this; }
        public Builder stencil(CgStencilState s)     { this.stencil = s;       return this; }

        /** Appends one color mask entry (allows multiple per-MRT entries). */
        public Builder colorMask(CgColorMask mask) {
            this.colorMasks.add(mask);
            return this;
        }

        /** Replaces the entire color mask list. */
        public Builder colorMasks(List<CgColorMask> masks) {
            this.colorMasks.clear();
            this.colorMasks.addAll(masks);
            return this;
        }

        public CgRenderState build() { return new CgRenderState(this); }
    }
}
