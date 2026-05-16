package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Immutable face-culling state slot for {@link CgRenderState}.
 *
 * <p>Pre-defined constants:</p>
 * <ul>
 *   <li>{@link #NONE} — culling disabled (typical for 2D UI)</li>
 *   <li>{@link #BACK} — back-face culling (standard 3D)</li>
 *   <li>{@link #FRONT} — front-face culling (shadow volumes, inside-out rendering)</li>
 * </ul>
 *
 * <p>{@link #clear()} restores the GL default: culling disabled.</p>
 */
@Desugar
public record CgCullState(boolean enabled, int face) {

    public static final CgCullState NONE = new CgCullState(false, 0);
    public static final CgCullState BACK = new CgCullState(true, CgGL.GL_BACK);
    public static final CgCullState FRONT = new CgCullState(true, CgGL.GL_FRONT);
    
    public void apply() {
        if (enabled) {
            CgGL.glEnable(CgGL.GL_CULL_FACE);
            CgGL.glCullFace(face);
        } else {
            CgGL.glDisable(CgGL.GL_CULL_FACE);
        }
    }

    public void clear() {
        CgGL.glDisable(CgGL.GL_CULL_FACE);
    }
}
