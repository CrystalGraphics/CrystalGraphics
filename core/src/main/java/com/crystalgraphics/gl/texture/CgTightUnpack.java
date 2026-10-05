package com.crystalgraphics.gl.texture;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Tightly packed client-memory unpacking for one texture upload, with GL's initial unpack state after.
 *
 * <p>Every upload here reads a buffer whose rows follow each other with no padding and no offset. GL's
 * unpack state decides how rows are found, and it is shared with the host and every other mod — so it
 * is stated per upload rather than trusted.</p>
 *
 * <pre>
 * try (CgTightUnpack ignored = CgTightUnpack.begin()) {
 *     CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
 * }
 * </pre>
 *
 * <ul>
 *   <li>Minecraft leaves {@code GL_UNPACK_ROW_LENGTH} and the skips at whatever its last partial upload
 *       needed. Until 1.21.1 its per-frame lightmap upload reset them; from 1.21.2 the lightmap is drawn,
 *       and a glyph upload read with a stale row length comes out as scattered dashes.</li>
 *   <li>Alignment 1 is right for every format: 4 misreads any row whose byte width is not a multiple
 *       of 4, such as an R8 glyph of odd width.</li>
 *   <li>What the host had is not read back, since a {@code glGet} waits for the driver to drain every queued
 *       call: closing leaves GL's initial values, which Minecraft states over before each upload of its own.</li>
 * </ul>
 */
final class CgTightUnpack implements AutoCloseable {

    private static final int[] PARAMS = {
            CgGL.GL_UNPACK_ROW_LENGTH, CgGL.GL_UNPACK_SKIP_ROWS, CgGL.GL_UNPACK_SKIP_PIXELS,
            CgGL.GL_UNPACK_IMAGE_HEIGHT, CgGL.GL_UNPACK_SKIP_IMAGES, CgGL.GL_UNPACK_ALIGNMENT,
    };
    private static final int[] TIGHT = {0, 0, 0, 0, 0, 1};

    private static final CgTightUnpack INSTANCE = new CgTightUnpack();

    private CgTightUnpack() {}

    /** Sets tight unpacking until {@link #close()}. */
    static CgTightUnpack begin() {
        for (int i = 0; i < PARAMS.length; i++) CgGL.glPixelStorei(PARAMS[i], TIGHT[i]);
        return INSTANCE;
    }

    @Override
    public void close() {
        CgGL.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, 4);   // the rest are tight at their initial 0
    }
}
