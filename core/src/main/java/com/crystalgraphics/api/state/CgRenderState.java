package com.crystalgraphics.api.state;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite render state assembled from typed slots.
 *
 * <p>One {@code CgRenderState} per render layer or material pass. Each slot (blend,
 * depth, cull, stencil, alpha) may be {@code null} — a null slot means the material
 * source did not declare that block, and {@link #apply()} will skip it entirely.
 * Only {@link #DEFAULT} is guaranteed to have every slot populated.</p>
 *
 * <h3>Null-slot semantics</h3>
 * <p>{@code builder().build()} leaves all slots null. The {@link CgRenderStateParser}
 * sets only the slots it finds in source, so a material with no {@code Depth} block
 * produces a state with {@code depth == null} — meaning the depth settings of the
 * prior pass are left unchanged. This prevents the transparent pass from silently
 * overwriting ZWrite / DepthFunc when the material doesn't declare a Depth block.</p>
 *
 * <h3>Apply/Clear Contract</h3>
 * <p>{@link #apply()} enables only the slots that are non-null.
 * {@link #clear()} null-guards <em>instance</em> calls ({@code alpha.clear()},
 * {@code depth.clear()}, {@code cull.clear()}) but <em>always</em> fires the two
 * static GL resets ({@link CgBlendState#clearToDefault()} and
 * {@link CgStencilState#DISABLED DISABLED.apply()}) unconditionally — these are
 * global GL state resets that must run regardless of what the material declared.</p>
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

    private CgRenderState(Builder b) {
        this.alpha      = b.alpha;
        this.blend      = b.blend;
        this.depth      = b.depth;
        this.cull       = b.cull;
        this.stencil    = b.stencil;
        this.colorMasks = new ArrayList<>(b.colorMasks);
    }

    /**
     * Applies all non-null slots. Null slots are silently skipped — the corresponding
     * GL state is left as the previous pass left it.
     */
    public void apply() {
        if (alpha   != null) alpha.apply();
        if (blend   != null) blend.apply();
        if (depth   != null) depth.apply();
        if (cull    != null) cull.apply();
        if (stencil != null) stencil.apply();
        for (CgColorMask mask : colorMasks) mask.apply();
    }

    /**
     * Reverses all state changes, restoring GL defaults.
     *
     * <p>Instance calls ({@code alpha.clear()}, {@code depth.clear()}, {@code cull.clear()})
     * are skipped when the slot is null. The two static GL resets —
     * {@link CgBlendState#clearToDefault()} and {@link CgStencilState#DISABLED}.apply() —
     * always fire regardless of whether blend/stencil slots were set, because they reset
     * global GL blend/stencil state that may have been modified by the previous pass.</p>
     */
    public void clear() {
        if (alpha != null) alpha.clear();
        CgBlendState.clearToDefault();       // always fires — global GL blend reset
        if (depth != null) depth.clear();
        if (cull  != null) cull.clear();
        CgStencilState.DISABLED.apply();     // always fires — global GL stencil reset
        if (!colorMasks.isEmpty()) CgColorMask.clearToDefault();
    }

    /**
     * Returns a builder with all slots null. Set only the slots needed by the
     * material; unset slots will be skipped by {@link #apply()}.
     */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CgAlphaState            alpha;
        private CgBlendState            blend;
        private CgDepthState            depth;
        private CgCullState             cull;
        private CgStencilState          stencil;
        private final List<CgColorMask> colorMasks = new ArrayList<>();

        private Builder() {}

        public Builder alpha(CgAlphaState alpha)   { this.alpha   = alpha;   return this; }
        public Builder blend(CgBlendState blend)   { this.blend   = blend;   return this; }
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
