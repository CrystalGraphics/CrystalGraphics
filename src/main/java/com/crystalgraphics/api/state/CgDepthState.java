package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;

/**
 * Immutable depth-test state slot for {@link CgRenderState}.
 *
 * <p>Pre-defined constants cover typical 2D/3D rendering scenarios:</p>
 * <ul>
 *   <li>{@link #NONE} — depth test disabled, write disabled</li>
 *   <li>{@link #TEST_ONLY} — depth test enabled (LEqual), writes disabled (read depth only)</li>
 *   <li>{@link #TEST_WRITE} — both enabled (standard 3D opaque rendering, LEqual)</li>
 *   <li>{@link #TEST_WRITE_EQUAL} — test + write with {@code GL_EQUAL} (secondary passes)</li>
 *   <li>{@link #TEST_WRITE_ALWAYS} — test + write that always passes (overwrites depth)</li>
 * </ul>
 *
 * <p>{@link #clear()} fully restores GL depth defaults: test disabled, write enabled,
 * compare function reset to {@code GL_LESS}.</p>
 */
@Desugar
public record CgDepthState(boolean test, boolean write, int compareFunc) {

    /** Depth test disabled, write disabled. {@code compareFunc} is {@code GL_ALWAYS} (unused). */
    public static final CgDepthState NONE = new CgDepthState(false, false, GL11.GL_ALWAYS);

    /** Depth test enabled (LEqual), writes disabled. Reads depth without modifying it. */
    public static final CgDepthState TEST_ONLY = new CgDepthState(true, false, GL11.GL_LEQUAL);

    /** Depth test and write enabled with {@code GL_LEQUAL}. Standard 3D opaque geometry. */
    public static final CgDepthState TEST_WRITE = new CgDepthState(true, true, GL11.GL_LEQUAL);

    /** Depth test and write enabled with {@code GL_EQUAL}. For secondary passes at equal depth. */
    public static final CgDepthState TEST_WRITE_EQUAL = new CgDepthState(true, true, GL11.GL_EQUAL);

    /** Depth test and write enabled with {@code GL_ALWAYS}. Always overwrites depth. */
    public static final CgDepthState TEST_WRITE_ALWAYS = new CgDepthState(true, true, GL11.GL_ALWAYS);

    /**
     * Applies this depth state to the current GL context.
     *
     * <p>Enables or disables the depth test, sets the depth write mask, and
     * when the test is enabled also sets the depth comparison function via
     * {@code glDepthFunc}.</p>
     */
    public void apply() {
        if (test) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(compareFunc);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        GL11.glDepthMask(write);
    }

    /**
     * Restores GL depth defaults: test disabled, write enabled, compare function {@code GL_LESS}.
     */
    public void clear() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LESS);
    }
}
