package io.github.somehussar.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;

/**
 * Immutable depth-test state slot for {@link CgRenderState}.
 *
 * <p>Pre-defined constants cover typical 2D/3D UI scenarios:</p>
 * <ul>
 *   <li>{@link #NONE} — depth test disabled, write enabled (GL default restore)</li>
 *   <li>{@link #TEST_ONLY} — depth test enabled, writes disabled (read depth, don't write)</li>
 *   <li>{@link #TEST_WRITE} — both enabled (standard 3D rendering)</li>
 * </ul>
 *
 * <p>{@link #clear()} restores the GL default: depth test disabled, depth write enabled.</p>
 */
@Desugar
public record CgDepthState(boolean test, boolean write) {

    public static final CgDepthState NONE = new CgDepthState(false, false);
    public static final CgDepthState TEST_ONLY = new CgDepthState(true, false);
    public static final CgDepthState TEST_WRITE = new CgDepthState(true, true);
    
    public void apply() {
        if (test) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(write);
    }

    public void clear() {
        // We maybe don't want to disable depth testing on every state clear
        // GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
    }
}
