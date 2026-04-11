package io.github.somehussar.crystalgraphics.gl.state;

import lombok.Getter;

import java.util.Arrays;

/**
 * Immutable point-in-time snapshot of the GL state tracked by {@link GLStateMirror}.
 *
 * <p>A snapshot captures the framebuffer bindings (draw and read), shader program
 * binding, active texture unit, and per-unit 2D texture bindings at the moment
 * it is created.  Once created, the snapshot is fully immutable and safe to pass
 * between methods or store for later restoration via
 * {@link CgStateBoundary#restore(CgStateSnapshot)}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgStateSnapshot snapshot = CgStateSnapshot.captureFromMirror();
 * // ... perform GL operations ...
 * CgStateBoundary.restore(snapshot);
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable and therefore inherently thread-safe.  However, the
 * {@link #captureFromMirror()} factory method reads from {@link GLStateMirror},
 * which tracks state per the render thread — calling it from a non-render thread
 * will produce a snapshot of whatever state that thread has observed.</p>
 *
 * @see GLStateMirror
 * @see CgStateBoundary
 */
public final class CgStateSnapshot {

    /**
     * Maximum number of texture units tracked, matching {@link GLStateMirror}'s
     * internal limit.
     */
    private static final int MAX_TEXTURE_UNITS = 32;

    /** The draw framebuffer object ID at the time of capture. */
    private final int drawFboId;

    /** The read framebuffer object ID at the time of capture. */
    private final int readFboId;

    /** The GL call family that produced the framebuffer binding at capture time. */
    private final CallFamily fboFamily;

    /** The shader program ID at the time of capture. */
    private final int programId;

    /** The GL call family that produced the shader program binding at capture time. */
    private final CallFamily programFamily;

    /** The active texture unit index (0-based) at the time of capture. */
    private final int activeTextureUnit;

    /**
     * Defensive copy of the per-unit 2D texture bindings at capture time.
     * Index {@code i} holds the texture ID bound to {@code GL_TEXTURE0 + i}.
     */
    private final int[] boundTextures2D;

    /** The bound VAO ID at capture time. */
    @Getter
    private final int vaoId;

    /** The bound GL_ARRAY_BUFFER ID at capture time. */
    @Getter
    private final int arrayBufferId;

    /** The bound GL_ELEMENT_ARRAY_BUFFER ID at capture time. */
    @Getter
    private final int elementArrayBufferId;

    CgStateSnapshot(int drawFboId, int readFboId, CallFamily fboFamily,
                    int programId, CallFamily programFamily,
                    int activeTextureUnit, int[] boundTextures2D,
                    int vaoId, int arrayBufferId, int elementArrayBufferId) {
        this.drawFboId = drawFboId;
        this.readFboId = readFboId;
        this.fboFamily = fboFamily;
        this.programId = programId;
        this.programFamily = programFamily;
        this.activeTextureUnit = activeTextureUnit;
        this.boundTextures2D = Arrays.copyOf(boundTextures2D, boundTextures2D.length);
        this.vaoId = vaoId;
        this.arrayBufferId = arrayBufferId;
        this.elementArrayBufferId = elementArrayBufferId;
    }

    /**
     * Captures the current state from {@link GLStateMirror} into a new snapshot.
     *
     * <p>Reads all tracked state from the mirror (FBO IDs, program ID, active
     * texture unit, and per-unit 2D texture bindings) and packages them into an
     * immutable snapshot.  This method does not issue any GL calls.</p>
     *
     * @return a new snapshot reflecting the mirror's current state
     */
    public static CgStateSnapshot captureFromMirror() {
        int[] textures = new int[MAX_TEXTURE_UNITS];
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            textures[i] = GLStateMirror.getBoundTexture2D(i);
        }
        return new CgStateSnapshot(
            GLStateMirror.getDrawFboId(),
            GLStateMirror.getReadFboId(),
            GLStateMirror.getCurrentFboFamily(),
            GLStateMirror.getProgramId(),
            GLStateMirror.getCurrentProgramFamily(),
            GLStateMirror.getActiveTextureUnit(),
            textures,
            GLStateMirror.getCurrentVaoId(),
            GLStateMirror.getCurrentArrayBufferId(),
            GLStateMirror.getCurrentElementArrayBufferId()
        );
    }

    /**
     * Returns whether this snapshot contains unknown state.
     *
     * <p>A snapshot has unknown state if either the framebuffer family or the
     * program family is {@link CallFamily#UNKNOWN}, meaning no intercepted call
     * had been observed for that binding slot at capture time.</p>
     *
     * @return {@code true} if either family is {@link CallFamily#UNKNOWN},
     *         {@code false} if all tracked families are known
     */
    public boolean hasUnknownState() {
        return fboFamily == CallFamily.UNKNOWN || programFamily == CallFamily.UNKNOWN;
    }

    /**
     * Returns the draw framebuffer object ID captured in this snapshot.
     *
     * @return the draw FBO ID (0 = default framebuffer)
     */
    public int getDrawFboId() {
        return drawFboId;
    }

    /**
     * Returns the read framebuffer object ID captured in this snapshot.
     *
     * @return the read FBO ID (0 = default framebuffer)
     */
    public int getReadFboId() {
        return readFboId;
    }

    /**
     * Returns the GL call family that was active for framebuffer bindings
     * at the time of capture.
     *
     * @return the FBO {@link CallFamily}, or {@link CallFamily#UNKNOWN} if
     *         no bind had been observed
     */
    public CallFamily getFboFamily() {
        return fboFamily;
    }

    /**
     * Returns the shader program ID captured in this snapshot.
     *
     * @return the program ID (0 = no program / fixed-function)
     */
    public int getProgramId() {
        return programId;
    }

    /**
     * Returns the GL call family that was active for shader program bindings
     * at the time of capture.
     *
     * @return the program {@link CallFamily}, or {@link CallFamily#UNKNOWN}
     *         if no bind had been observed
     */
    public CallFamily getProgramFamily() {
        return programFamily;
    }

    /**
     * Returns the active texture unit index captured in this snapshot.
     *
     * @return the active texture unit (0 = {@code GL_TEXTURE0})
     */
    public int getActiveTextureUnit() {
        return activeTextureUnit;
    }

    /**
     * Returns the 2D texture ID bound to the specified texture unit at
     * capture time.
     *
     * @param unit the texture unit index (0..31)
     * @return the bound 2D texture ID for that unit, or 0 if the unit is
     *         out of range or was unbound
     */
    public int getBoundTexture2D(int unit) {
        if (unit < 0 || unit >= boundTextures2D.length) {
            return 0;
        }
        return boundTextures2D[unit];
    }

    /**
     * Returns the number of texture units tracked in this snapshot.
     *
     * @return the number of texture unit slots (always {@value #MAX_TEXTURE_UNITS})
     */
    public int getTextureUnitCount() {
        return boundTextures2D.length;
    }
}
