package com.crystalgraphics.api.material;

import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A material's render state where it is not its shader's: how it blends, whether it is two-sided, whether it writes
 * depth, whether it clips. Mirrors Unreal's material instance base property overrides: each field is the shader's
 * until set, so one shader serves every state a kit draws it under.
 *
 * <pre>{@code
 * CgMaterialState glow = CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build();
 * CgMaterial material = CgMaterial.newInstance("mymod:shaders/fx/soft.shader", glow);
 * }</pre>
 *
 * <p>A clipped decal: drawn blended, writing depth where it is solid, its threshold the material's {@code _Clip}:</p>
 *
 * <pre>{@code
 * CgMaterialState ink = CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build();
 * CgMaterial decal = CgMaterial.newInstance("mymod:shaders/fx/ink.shader", ink);
 * decal.applyProperties(b -> b.set1f("_Clip", 0.33f));
 * }</pre>
 *
 * <ul>
 *   <li><b>A blend or clip compiles</b>: the generated fragment {@code main} is built from the blend (fog, the HDR
 *       scene's decode, smoke dimming, the Emissive merge), so each blend mode is a program of its own, made on first
 *       use. Cull and depth write are pipeline state alone: they share the program.</li>
 *   <li><b>Refused at load</b>, naming the shader: {@code depthWrite} with {@code clip()}, or on a shader whose
 *       {@code "Depth"} tag owns its depth; {@code clip()} on a {@code "Prepass"} shader.</li>
 *   <li>The queue stays the shader's: an additive mode on an opaque-queue shader still draws in the opaque pass.</li>
 * </ul>
 */
public final class CgMaterialState {

    /** Everything as the shader authors it. */
    public static final CgMaterialState SHADER = new CgMaterialState(null, false, null, null);

    @Nullable
    private final CgBlendMode blend;
    private final boolean clip;
    @Nullable
    private final CgCullState cull;
    @Nullable
    private final Boolean depthWrite;

    private CgMaterialState(@Nullable CgBlendMode blend, boolean clip, @Nullable CgCullState cull,
                            @Nullable Boolean depthWrite) {
        this.blend = blend;
        this.clip = clip;
        this.cull = cull;
        this.depthWrite = depthWrite;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Its blend mode, or null for the shader's. */
    @Nullable
    public CgBlendMode blend() {
        return blend;
    }

    /** Whether it clips: Unreal's Masked, the shader's {@code "Depth" = "Clip"}. */
    public boolean clip() {
        return clip;
    }

    /** Which faces it culls, or null for the shader's. */
    @Nullable
    public CgCullState cull() {
        return cull;
    }

    /** Whether its Forward pass writes depth, or null for the shader's. */
    @Nullable
    public Boolean depthWrite() {
        return depthWrite;
    }

    /** Whether it changes what is compiled: a blend or a clip. Cull and depth write never do. */
    public boolean changesProgram() {
        return blend != null || clip;
    }

    /** What a shader asset compiled under it is told apart by: its blend and clip; empty for the shader's. */
    public String programKey() {
        if (!changesProgram()) return "";
        return (blend == null ? "" : "blend=" + blend.name()) + (clip ? ";clip" : "");
    }

    /**
     * The state a pass draws under: {@code authored} with this cull, on every pass as Unreal's two-sidedness is the
     * material's, and on a Forward pass this depth write. {@code authored} itself when neither is set.
     */
    public CgRenderState onPass(CgRenderState authored, boolean forward) {
        boolean writes = forward && depthWrite != null;
        if (cull == null && !writes) return authored;
        CgRenderState state = authored;
        if (cull != null) state = state.withCull(cull);
        if (writes) {
            CgDepthState depth = state.getDepth() != null ? state.getDepth() : CgDepthState.TEST_ONLY;
            state = state.withDepth(new CgDepthState(depth.test(), depthWrite, depth.compareFunc()));
        }
        return state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgMaterialState)) return false;
        CgMaterialState s = (CgMaterialState) o;
        return clip == s.clip && blend == s.blend && Objects.equals(cull, s.cull) && Objects.equals(depthWrite, s.depthWrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blend, clip, cull, depthWrite);
    }

    @Override
    public String toString() {
        if (equals(SHADER)) return "CgMaterialState{shader's}";
        return "CgMaterialState{" + (blend == null ? "" : "blend " + blend) + (clip ? " clip" : "")
                + (cull == null ? "" : " cull " + (cull.enabled() ? cull.face() : "none"))
                + (depthWrite == null ? "" : " depthWrite " + depthWrite) + "}";
    }

    /** Builds a {@link CgMaterialState}; anything unset stays the shader's. */
    public static final class Builder {
        private CgBlendMode blend;
        private boolean clip;
        private CgCullState cull;
        private Boolean depthWrite;

        private Builder() {}

        /** How it blends, in place of the shader's {@code Blend} and {@code BlendEquation}. */
        public Builder blend(CgBlendMode blend) {
            this.blend = Objects.requireNonNull(blend, "blend");
            return this;
        }

        /**
         * Discards below the material's {@code _Clip} (0.5 where the shader declares none) and writes depth where it
         * draws: the shader's {@code "Depth" = "Clip"}. Its fragment says where it is solid with {@code cg_Clip}.
         */
        public Builder clip() {
            this.clip = true;
            return this;
        }

        /** Which faces it culls: {@link CgCullState#NONE}, {@link CgCullState#BACK} or {@link CgCullState#FRONT}. */
        public Builder cull(CgCullState cull) {
            this.cull = Objects.requireNonNull(cull, "cull");
            return this;
        }

        /** Both faces drawn: {@code cull(CgCullState.NONE)}, Unreal's two-sided. */
        public Builder twoSided() {
            return cull(CgCullState.NONE);
        }

        /** Whether its Forward pass writes depth. */
        public Builder depthWrite(boolean write) {
            this.depthWrite = write;
            return this;
        }

        public CgMaterialState build() {
            if (clip && depthWrite != null) {
                throw new IllegalStateException("A clipped material writes depth where it draws: drop depthWrite");
            }
            CgMaterialState state = new CgMaterialState(blend, clip, cull, depthWrite);
            return state.equals(SHADER) ? SHADER : state;
        }
    }
}
