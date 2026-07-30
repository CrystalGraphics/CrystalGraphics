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
 *
 * <h3>Why {@code frontFace} lives here</h3>
 * <p>Winding order is part of cull state, not a separate domain: it decides <em>which</em> faces
 * {@code face} refers to, so a value that omitted it could not fully describe what culling will do.
 * It is also what the {@code CgGlSlot#CULL} slot has always captured and restored, so keeping it
 * anywhere else would let this record and the tracked shadow disagree about what "cull state" means —
 * and a shadow that disagrees with its value type is exactly how a state manager starts skipping calls
 * it needed to issue.</p>
 */
@Desugar
public record CgCullState(boolean enabled, int face, int frontFace) {

    /**
     * Culling disabled.
     *
     * <p>{@code face} is {@code GL_BACK} rather than {@code 0} deliberately: a state value must describe
     * something {@link #apply()} can actually establish, or the tracked shadow claims a value GL never
     * received. With {@code 0} the shadow read "face=0" while the driver still reported {@code GL_BACK},
     * which a state-verification pass duly flagged as stale.</p>
     */
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




    /**
     * Makes this state current.
     *
     * <p>Calls {@code CgGL} directly; {@code CgGL} decides whether each call reaches the driver, so a
     * repeated apply of the same value costs nothing.</p>
     */
    public void apply() {
        if (enabled) {
            CgGL.glEnable(CgGL.GL_CULL_FACE);
        } else {
            CgGL.glDisable(CgGL.GL_CULL_FACE);
        }
        // Set the mode even when disabled, so this value fully describes what GL now holds. Guarded
        // because 0 is not a valid face and would raise GL_INVALID_ENUM.
        if (face != 0) CgGL.glCullFace(face);
        // Unconditional, matching what the CULL slot has always restored: winding is independent of
        // whether culling is currently enabled, and other features (two-sided stencil) depend on it.
        CgGL.glFrontFace(frontFace);
    }

    public void clear() {
        CgGL.glDisable(CgGL.GL_CULL_FACE);
    }
}
