package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgGL;

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
 */
@Desugar
public record CgDepthState(boolean test, boolean write, int compareFunc) {




    /** Depth test disabled, write disabled. {@code compareFunc} is {@code GL_ALWAYS} (unused). */
    /**
     * The OpenGL default: depth test disabled, depth writes enabled, compare function {@code GL_LESS}.
     *
     * <p>Distinct from {@link #NONE}, which disables writes and uses {@code GL_ALWAYS}. This is the value
     * a reset-to-defaults wants; {@code NONE} is the value a pass that must not touch depth at all wants.
     * Conflating them would silently leave depth writes disabled after a reset.</p>
     */
    public static final CgDepthState GL_DEFAULT = new CgDepthState(false, true, CgGL.GL_LESS);

    public static final CgDepthState NONE = new CgDepthState(false, false, CgGL.GL_ALWAYS);

    /** Depth test enabled (LEqual), writes disabled. Reads depth without modifying it. */
    public static final CgDepthState TEST_ONLY = new CgDepthState(true, false, CgGL.GL_LEQUAL);

    /** Depth test and write enabled with {@code GL_LEQUAL}. Standard 3D opaque geometry. */
    public static final CgDepthState TEST_WRITE = new CgDepthState(true, true, CgGL.GL_LEQUAL);

    /** Depth test and write enabled with {@code GL_EQUAL}. For secondary passes at equal depth. */
    public static final CgDepthState TEST_WRITE_EQUAL = new CgDepthState(true, true, CgGL.GL_EQUAL);

    /** Depth test and write enabled with {@code GL_ALWAYS}. Always overwrites depth. */
    public static final CgDepthState TEST_WRITE_ALWAYS = new CgDepthState(true, true, CgGL.GL_ALWAYS);
}
