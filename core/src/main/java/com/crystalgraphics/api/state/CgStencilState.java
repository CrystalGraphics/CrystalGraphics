package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Immutable stencil-test state slot for {@link CgRenderState}.
 *
 * <p>Encapsulates the three GL calls needed to configure the stencil test:</p>
 * <ul>
 *   <li>{@code glStencilFunc(compFunc, ref, readMask)} — comparison function and reference</li>
 *   <li>{@code glStencilMask(writeMask)} — which stencil bits are written</li>
 *   <li>{@code glStencilOp(failOp, zfailOp, passOp)} — what happens on each stencil/depth outcome</li>
 * </ul>
 *
 * <p>Note the argument order of {@code glStencilOp}: sfail, dpfail, dppass.
 * The field names match GL conventions: {@code failOp} = sfail, {@code zfailOp} = dpfail,
 * {@code passOp} = dppass.</p>
 *
 * <p>{@link #DISABLED} leaves the stencil test off and the write mask fully open (0xFF).</p>
 */
@Desugar
public record CgStencilState(
        boolean enabled,
        int     ref,
        int     readMask,
        int     writeMask,
        int     compFunc,
        int     passOp,
        int     failOp,
        int     zfailOp
) {




    /**
     * Stencil test disabled. Write mask is fully open (0xFF) so subsequent clears are unobstructed.
     * All operation fields are set to {@code GL_KEEP} / {@code GL_ALWAYS} — harmless if accidentally applied.
     */
    public static final CgStencilState DISABLED = new CgStencilState(
            false, 0, 0xFF, 0xFF,
            CgGL.GL_ALWAYS, CgGL.GL_KEEP, CgGL.GL_KEEP, CgGL.GL_KEEP);

    /**
     * Passes where the stencil's {@code readMask} bits equal {@code ref}'s, and writes nothing: a draw kept to the pixels
     * a stencil value marks.
     *
     * <pre>{@code
     * CgStencilState bit3 = CgStencilState.equal(1 << 3, 1 << 3);   // where bit 3 is set
     * }</pre>
     */
    public static CgStencilState equal(int ref, int readMask) {
        return new CgStencilState(true, ref, readMask, 0, CgGL.GL_EQUAL, CgGL.GL_KEEP, CgGL.GL_KEEP, CgGL.GL_KEEP);
    }

    /**
     * Writes {@code value} into the {@code writeMask} bits wherever the draw passes; where depth fails, kept, or written
     * too with {@code depthFailWrites} (Unreal's CustomDepth under a partial mask).
     *
     * <pre>{@code
     * CgStencilState mark = CgStencilState.write(7, 0xFF, false);   // 7 where the draw is nearest
     * }</pre>
     */
    public static CgStencilState write(int value, int writeMask, boolean depthFailWrites) {
        return new CgStencilState(true, value, 0xFF, writeMask, CgGL.GL_ALWAYS, CgGL.GL_REPLACE, CgGL.GL_KEEP,
                depthFailWrites ? CgGL.GL_REPLACE : CgGL.GL_KEEP);
    }
}
