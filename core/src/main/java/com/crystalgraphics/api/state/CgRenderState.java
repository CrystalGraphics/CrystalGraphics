package com.crystalgraphics.api.state;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite render state assembled from typed slots.
 *
 * <p>One {@code CgRenderState} per render layer or material pass. Each slot (blend,
 * depth, cull, stencil, alpha) may be {@code null}: the material source did not declare
 * that block, and the pipeline takes the pass's ({@code CgDeviceStates}). So a
 * transparent pass's depth write stands for a material with no {@code Depth} block.
 * Only {@link #DEFAULT} has every slot populated.</p>
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
@Getter
@Setter
public final class CgRenderState {

    /**
     * Default opaque-geometry state: blend disabled, depth test+write (LEqual),
     * back-face culling, stencil disabled, alpha test disabled, color mask all channels.
     * All five slots are explicitly set — this is the only instance guaranteed non-null
     * on every slot.
     */
    public static final CgRenderState DEFAULT = builder()
            .alpha(CgAlphaState.DISABLED).blend(CgBlendState.DISABLED)
            .depth(CgDepthState.TEST_WRITE).cull(CgCullState.BACK)
            .stencil(CgStencilState.DISABLED).build();

    // ── Slots (nullable — null means "not declared in source") ────────────────

    private CgStencilState    stencil;
    private CgBlendState      blend;
    private CgDepthState      depth;
    private CgCullState       cull;
    private CgAlphaState      alpha;
    private List<CgColorMask> colorMasks;
    /**
     * The blend into colour attachment 1 where it is not {@link #blend}'s: a graded layer's, beside the scene.
     */
    private CgBlendState      secondBlend;

    private CgRenderState(Builder b) {
        this.alpha      = b.alpha;
        this.blend      = b.blend;
        this.secondBlend = b.secondBlend;
        this.depth      = b.depth;
        this.cull       = b.cull;
        this.stencil    = b.stencil;
        this.colorMasks = new ArrayList<>(b.colorMasks);
    }

    /** A copy with {@code blend} in place of this state's blend slot. */
    public CgRenderState withBlend(CgBlendState blend) {
        return copy().blend(blend).build();
    }

    /** A copy with {@code blend} into colour attachment 1, the scene's still in {@link #getBlend()}. */
    public CgRenderState withSecondBlend(CgBlendState blend) {
        return copy().secondBlend(blend).build();
    }

    /** A copy with {@code depth} in place of this state's depth slot. */
    public CgRenderState withDepth(CgDepthState depth) {
        return copy().depth(depth).build();
    }

    /** A copy with {@code cull} in place of this state's cull slot. */
    public CgRenderState withCull(CgCullState cull) {
        return copy().cull(cull).build();
    }

    /** A copy with {@code stencil} in place of this state's stencil slot. */
    public CgRenderState withStencil(CgStencilState stencil) {
        return copy().stencil(stencil).build();
    }

    /** A copy whose only colour mask is {@code mask}: what a depth-only variant of a pass draws under. */
    public CgRenderState withColorMask(CgColorMask mask) {
        return builder().alpha(alpha).blend(blend).secondBlend(secondBlend).depth(depth).cull(cull).stencil(stencil)
                .colorMask(mask).build();
    }

    private Builder copy() {
        return builder().alpha(alpha).blend(blend).secondBlend(secondBlend).depth(depth).cull(cull).stencil(stencil)
                .colorMasks(colorMasks);
    }

    /**
     * Whether a draw under it changes no pixel: every colour mask closed, depth declared and unwritten, stencil off. The
     * world renderer skips such a pass, as it does a pure haze's Forward pass.
     */
    public boolean writesNothing() {
        if (colorMasks.isEmpty() || depth == null || depth.write() || (stencil != null && stencil.enabled())) return false;
        for (int i = 0; i < colorMasks.size(); i++) {
            CgColorMask m = colorMasks.get(i);
            if (m.r() || m.g() || m.b() || m.a()) return false;
        }
        return true;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CgAlphaState            alpha;
        private CgBlendState            blend;
        private CgBlendState            secondBlend;
        private CgDepthState            depth;
        private CgCullState             cull;
        private CgStencilState          stencil;
        private final List<CgColorMask> colorMasks = new ArrayList<>();

        private Builder() {}

        public Builder alpha(CgAlphaState alpha)   { this.alpha   = alpha;   return this; }
        public Builder blend(CgBlendState blend)   { this.blend   = blend;   return this; }
        /** Colour attachment 1's blend, where it differs from {@link #blend}'s. */
        public Builder secondBlend(CgBlendState b) { this.secondBlend = b;   return this; }
        public Builder depth(CgDepthState depth)   { this.depth   = depth;   return this; }
        public Builder cull(CgCullState cull)      { this.cull    = cull;    return this; }
        public Builder stencil(CgStencilState s)   { this.stencil = s;       return this; }

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
