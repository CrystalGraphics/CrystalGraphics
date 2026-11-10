package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;

/**
 * What a host sharing the GL device's context believes the context has, read as a host section opens so the section
 * can set it back as it closes. A host with a state cache (Minecraft's {@code GlStateManager}) answers from the cache,
 * whose belief is what its next call compares against ({@link GLCheckedHostState}); {@link #byQuery} reads GL itself.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, translator, width, height, false, GLHostState.byQuery(driver));
 * CgDeviceSection.begin();   // the device reads the host's state, then sets its conventions
 * ... record ...
 * CgDeviceSection.end();     // every field the section changed set back
 * }</pre>
 *
 * <p>A field the host keeps no belief about stays {@link GLContextState#UNKNOWN}: the section leaves it as it finds it,
 * and {@link #conventions} is where such a host says what it draws under.</p>
 */
public interface GLHostState {

    /** The fields a host's cache answers, or {@code glGet} reads, together. */
    enum Domain {
        BLEND, COLOR_MASK, DEPTH, CULL, STENCIL, POLYGON, VIEWPORT, SCISSOR, PROGRAM, FRAMEBUFFERS, VERTEX_ARRAY, TEXTURES
    }

    /** Fills {@code into} with what the host believes; on the context's thread. */
    void read(GLContextState into);

    /**
     * Sets unknown in {@code adopted}, the device's view of the context, what {@link #read} answered by a guess the
     * driver may not share: the device sets such a field before relying on it, and the section still sets the guess
     * back as it closes.
     */
    default void guessed(GLContextState adopted) {}

    /** The device's conventions are set: what the host leaves on that the device's draws must not see goes off here. */
    default void entered(CgGLDriver gl) {}

    /**
     * Sets back what the section set that {@link #read} left unknown: the conventions the device set (sRGB writes,
     * zero-to-one clip depth, seamless cube maps, program point size, tight pixel rows) to what the host draws under,
     * and what {@link #entered} turned off. Through {@code gl}, as the section closes.
     */
    default void conventions(CgGLDriver gl) {}

    /** The section closed and the context went back to the host. */
    default void left() {}

    /** Reads every field from GL by {@code glGet}: exact, and a stall on a threaded driver, so for hosts with no cache. */
    static GLHostState byQuery(CgGLDriver gl) {
        GLStateQuery query = new GLStateQuery(gl);
        return new GLHostState() {
            @Override
            public void read(GLContextState into) {
                for (Domain d : Domain.values()) query.read(d, into);
                query.rest(into);
            }

            @Override
            public void entered(CgGLDriver through) {
                query.alphaOff(GLContextState.UNKNOWN);
            }

            @Override
            public void conventions(CgGLDriver through) {
                query.alphaBack(through);
            }
        };
    }
}
