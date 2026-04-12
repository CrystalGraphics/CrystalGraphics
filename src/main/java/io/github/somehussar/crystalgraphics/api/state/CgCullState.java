package io.github.somehussar.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;

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
    public static final CgCullState BACK = new CgCullState(true, GL11.GL_BACK);
    public static final CgCullState FRONT = new CgCullState(true, GL11.GL_FRONT);
    
    public void apply() {
        if (enabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(face);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }

    public void clear() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }
}
