package com.crystalgraphics.platform.gl.state;

import java.util.Arrays;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * What a GL context has, per field, as the GL device sets it (Unreal's {@code FOpenGLContextState}): fixed-function
 * state, the program, vertex array and framebuffers, buffers at indexed points and generic targets, textures and
 * samplers per unit, clip control and pixel store. A field holding {@link #UNKNOWN} (NaN for a float) differs from
 * every value, so the next call setting it reaches GL.
 *
 * <p>Public fields, no accessors: {@link GLStateFilter} owns the rules, this the values.</p>
 */
public final class GLContextState {

    public static final int UNKNOWN = Integer.MIN_VALUE;
    public static final long UNKNOWN_LONG = Long.MIN_VALUE;

    public static final int DRAW_BUFFERS = 8, UNITS = 32, POINTS = 32;

    /** The capabilities kept, by index into {@link #caps}. */
    public static final int[] CAPS = {GL_CULL_FACE, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST,
            GL_POLYGON_OFFSET_FILL, GL_POLYGON_OFFSET_LINE, GL_POLYGON_OFFSET_POINT, GL_FRAMEBUFFER_SRGB,
            GL_TEXTURE_CUBE_MAP_SEAMLESS, GL_PROGRAM_POINT_SIZE};

    /** The generic buffer targets kept, by index into {@link #targetBuffer}. */
    public static final int[] TARGETS = {GL_DRAW_INDIRECT_BUFFER, GL_DISPATCH_INDIRECT_BUFFER, GL_PARAMETER_BUFFER,
            GL_PIXEL_PACK_BUFFER, GL_PIXEL_UNPACK_BUFFER};

    /** The pixel-store parameters kept, by index into {@link #pixelStore}. */
    public static final int[] PIXEL_STORE = {GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_ROWS,
            GL_UNPACK_SKIP_PIXELS, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_IMAGES, GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH,
            GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES};
    /** {@link #PIXEL_STORE}'s values for rows packed one after another from the start: every device copy's layout. */
    public static final int[] TIGHT = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0};

    /** 1 on, 0 off, per {@link #CAPS}; blending is per draw buffer, in {@link #blendEnabled}. */
    public final int[] caps = new int[CAPS.length];
    public final int[] blendEnabled = new int[DRAW_BUFFERS];

    public int program, vertexArray, drawFramebuffer, readFramebuffer;
    public int cullFace, frontFace, polygonMode;
    public float offsetFactor, offsetUnits;
    public int depthFunc, depthMask;

    /** Index 0 the front face's, 1 the back's. */
    public final int[] stencilFunc = new int[2], stencilRef = new int[2], stencilValueMask = new int[2],
            stencilFail = new int[2], stencilDepthFail = new int[2], stencilPass = new int[2], stencilWriteMask = new int[2];

    public final int[] blendSrcRgb = new int[DRAW_BUFFERS], blendDstRgb = new int[DRAW_BUFFERS],
            blendSrcAlpha = new int[DRAW_BUFFERS], blendDstAlpha = new int[DRAW_BUFFERS],
            blendEqRgb = new int[DRAW_BUFFERS], blendEqAlpha = new int[DRAW_BUFFERS];
    /** RGBA write bits, 1 2 4 8. */
    public final int[] colorMask = new int[DRAW_BUFFERS];

    public float viewportX, viewportY, viewportW, viewportH, depthNear, depthFar;
    public int scissorX, scissorY, scissorW, scissorH;
    public int clipOrigin, clipDepth;
    public final int[] pixelStore = new int[PIXEL_STORE.length];

    public final int[] uniformBuffer = new int[POINTS], storageBuffer = new int[POINTS];
    public final long[] uniformOffset = new long[POINTS], uniformSize = new long[POINTS],
            storageOffset = new long[POINTS], storageSize = new long[POINTS];
    public final int[] unitTexture = new int[UNITS], unitSampler = new int[UNITS];
    public final int[] targetBuffer = new int[TARGETS.length];

    public GLContextState() {
        invalidate();
    }

    public static int cap(int cap) {
        for (int i = 0; i < CAPS.length; i++) if (CAPS[i] == cap) return i;
        return -1;
    }

    public static int pixelStore(int name) {
        for (int i = 0; i < PIXEL_STORE.length; i++) if (PIXEL_STORE[i] == name) return i;
        return -1;
    }

    public static int target(int target) {
        for (int i = 0; i < TARGETS.length; i++) if (TARGETS[i] == target) return i;
        return -1;
    }

    /** Every field unknown: what code outside the device may have changed. */
    public void invalidate() {
        Arrays.fill(caps, UNKNOWN);
        Arrays.fill(blendEnabled, UNKNOWN);
        program = vertexArray = drawFramebuffer = readFramebuffer = UNKNOWN;
        cullFace = frontFace = polygonMode = UNKNOWN;
        offsetFactor = offsetUnits = Float.NaN;
        depthFunc = depthMask = UNKNOWN;
        Arrays.fill(stencilFunc, UNKNOWN);
        Arrays.fill(stencilRef, UNKNOWN);
        Arrays.fill(stencilValueMask, UNKNOWN);
        Arrays.fill(stencilFail, UNKNOWN);
        Arrays.fill(stencilDepthFail, UNKNOWN);
        Arrays.fill(stencilPass, UNKNOWN);
        Arrays.fill(stencilWriteMask, UNKNOWN);
        Arrays.fill(blendSrcRgb, UNKNOWN);
        Arrays.fill(blendDstRgb, UNKNOWN);
        Arrays.fill(blendSrcAlpha, UNKNOWN);
        Arrays.fill(blendDstAlpha, UNKNOWN);
        Arrays.fill(blendEqRgb, UNKNOWN);
        Arrays.fill(blendEqAlpha, UNKNOWN);
        Arrays.fill(colorMask, UNKNOWN);
        Arrays.fill(uniformBuffer, UNKNOWN);
        Arrays.fill(storageBuffer, UNKNOWN);
        Arrays.fill(unitTexture, UNKNOWN);
        Arrays.fill(unitSampler, UNKNOWN);
        Arrays.fill(targetBuffer, UNKNOWN);
        viewportX = viewportY = viewportW = viewportH = depthNear = depthFar = Float.NaN;
        scissorX = scissorY = scissorW = scissorH = UNKNOWN;
        clipOrigin = clipDepth = UNKNOWN;
        Arrays.fill(pixelStore, UNKNOWN);
        Arrays.fill(uniformOffset, UNKNOWN_LONG);
        Arrays.fill(uniformSize, UNKNOWN_LONG);
        Arrays.fill(storageOffset, UNKNOWN_LONG);
        Arrays.fill(storageSize, UNKNOWN_LONG);
    }

    public void copyFrom(GLContextState o) {
        System.arraycopy(o.caps, 0, caps, 0, caps.length);
        System.arraycopy(o.blendEnabled, 0, blendEnabled, 0, DRAW_BUFFERS);
        program = o.program;
        vertexArray = o.vertexArray;
        drawFramebuffer = o.drawFramebuffer;
        readFramebuffer = o.readFramebuffer;
        cullFace = o.cullFace;
        frontFace = o.frontFace;
        polygonMode = o.polygonMode;
        offsetFactor = o.offsetFactor;
        offsetUnits = o.offsetUnits;
        depthFunc = o.depthFunc;
        depthMask = o.depthMask;
        for (int f = 0; f < 2; f++) {
            stencilFunc[f] = o.stencilFunc[f];
            stencilRef[f] = o.stencilRef[f];
            stencilValueMask[f] = o.stencilValueMask[f];
            stencilFail[f] = o.stencilFail[f];
            stencilDepthFail[f] = o.stencilDepthFail[f];
            stencilPass[f] = o.stencilPass[f];
            stencilWriteMask[f] = o.stencilWriteMask[f];
        }
        System.arraycopy(o.blendSrcRgb, 0, blendSrcRgb, 0, DRAW_BUFFERS);
        System.arraycopy(o.blendDstRgb, 0, blendDstRgb, 0, DRAW_BUFFERS);
        System.arraycopy(o.blendSrcAlpha, 0, blendSrcAlpha, 0, DRAW_BUFFERS);
        System.arraycopy(o.blendDstAlpha, 0, blendDstAlpha, 0, DRAW_BUFFERS);
        System.arraycopy(o.blendEqRgb, 0, blendEqRgb, 0, DRAW_BUFFERS);
        System.arraycopy(o.blendEqAlpha, 0, blendEqAlpha, 0, DRAW_BUFFERS);
        System.arraycopy(o.colorMask, 0, colorMask, 0, DRAW_BUFFERS);
        viewportX = o.viewportX;
        viewportY = o.viewportY;
        viewportW = o.viewportW;
        viewportH = o.viewportH;
        depthNear = o.depthNear;
        depthFar = o.depthFar;
        scissorX = o.scissorX;
        scissorY = o.scissorY;
        scissorW = o.scissorW;
        scissorH = o.scissorH;
        clipOrigin = o.clipOrigin;
        clipDepth = o.clipDepth;
        System.arraycopy(o.pixelStore, 0, pixelStore, 0, PIXEL_STORE.length);
        System.arraycopy(o.uniformBuffer, 0, uniformBuffer, 0, POINTS);
        System.arraycopy(o.uniformOffset, 0, uniformOffset, 0, POINTS);
        System.arraycopy(o.uniformSize, 0, uniformSize, 0, POINTS);
        System.arraycopy(o.storageBuffer, 0, storageBuffer, 0, POINTS);
        System.arraycopy(o.storageOffset, 0, storageOffset, 0, POINTS);
        System.arraycopy(o.storageSize, 0, storageSize, 0, POINTS);
        System.arraycopy(o.unitTexture, 0, unitTexture, 0, UNITS);
        System.arraycopy(o.unitSampler, 0, unitSampler, 0, UNITS);
        System.arraycopy(o.targetBuffer, 0, targetBuffer, 0, TARGETS.length);
    }
}
