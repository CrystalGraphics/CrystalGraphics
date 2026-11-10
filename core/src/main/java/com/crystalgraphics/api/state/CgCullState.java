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
 * <p>{@code frontFace} lives here because winding decides which faces {@code face} names.</p>
 */
@Desugar
public record CgCullState(boolean enabled, int face, int frontFace) {

    /** Culling disabled; {@code face} stays a valid face, {@code GL_BACK}. */
    public static final CgCullState NONE = new CgCullState(false, CgGL.GL_BACK);
    public static final CgCullState BACK = new CgCullState(true, CgGL.GL_BACK);
    public static final CgCullState FRONT = new CgCullState(true, CgGL.GL_FRONT);

    /**
     * Convenience constructor defaulting {@code frontFace} to counter-clockwise, the GL default.
     *
     * <p>Exists so the presets above and any two-argument call site keep working after
     * {@code frontFace} was added.</p>
     */
    public CgCullState(boolean enabled, int face) {
        this(enabled, face, CgGL.GL_CCW);
    }



}
