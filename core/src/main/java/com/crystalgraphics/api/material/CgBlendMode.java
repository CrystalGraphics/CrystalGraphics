package com.crystalgraphics.api.material;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * How a material's colour lands on what is behind it: Unreal's material blend modes, set per material through
 * {@link CgMaterialState#blend}. Each is exactly the {@code Blend} line a shader would author, so a shader written
 * without one draws under each as if it had been written with it.
 *
 * <pre>{@code
 * CgMaterial glow = CgMaterial.newInstance("mymod:shaders/fx/soft.shader", CgMaterialState.of().blend(CgBlendMode.ADDITIVE));
 * CgMaterial smoke = CgMaterial.newInstance("mymod:shaders/fx/soft.shader", CgMaterialState.of().blend(CgBlendMode.PREMULTIPLIED));
 * }</pre>
 *
 * <ul>
 *   <li>The fragment writes for its mode: straight colour and alpha for {@link #ALPHA}, colour already times its
 *       coverage for {@link #PREMULTIPLIED}, light for {@link #ADDITIVE} and {@link #SUBTRACT}, whose alpha is unused.</li>
 *   <li>A mode never moves a draw between the opaque and transparent passes: that is the shader's {@code Queue}.</li>
 * </ul>
 */
public enum CgBlendMode {
    /** No blend: {@code Blend OFF}. Unreal's Opaque. */
    OPAQUE(CgBlendState.DISABLED),
    /** {@code Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA}. Unreal's Translucent. */
    ALPHA(blend(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA, CgGL.GL_FUNC_ADD)),
    /** {@code Blend ONE ONE_MINUS_SRC_ALPHA}. Unreal's AlphaComposite. */
    PREMULTIPLIED(blend(CgGL.GL_ONE, CgGL.GL_ONE_MINUS_SRC_ALPHA, CgGL.GL_FUNC_ADD)),
    /** {@code Blend ONE ONE}. Unreal's Additive. */
    ADDITIVE(blend(CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_FUNC_ADD)),
    /**
     * {@code Blend ONE ONE} with {@code BlendEquation REV_SUB}: what is behind, less the colour. No Unreal mode; CC2's
     * kits author it. Fogged and decoded as {@link #ADDITIVE} is.
     */
    SUBTRACT(blend(CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_FUNC_REVERSE_SUBTRACT));

    private final CgBlendState state;

    CgBlendMode(CgBlendState state) {
        this.state = state;
    }

    /** The blend state a pass draws under in this mode. */
    public CgBlendState state() {
        return state;
    }

    /** RGB and alpha alike, as a two-factor {@code Blend} line sets them. */
    private static CgBlendState blend(int src, int dst, int equation) {
        return new CgBlendState(true, src, dst, src, dst, equation, equation);
    }
}
