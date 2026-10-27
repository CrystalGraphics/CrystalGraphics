package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * What a raster pass does with its target before drawing: keep what is there, or clear it.
 *
 * <pre>{@code
 * CgLoad.load()                          // draw over what the target holds
 * CgLoad.clear(0, 0, 0, 0)               // a layer: transparent first
 * CgLoad.clear(0, 0, 0, 1).andDepth(1)   // a preview with a depth buffer
 * CgLoad.clear(0, 0, 0, 0).andDepth(1).andStencil(0)   // a mask: depth and stencil too
 * }</pre>
 */
public final class CgLoad {

    private static final CgLoad LOAD = new CgLoad(0, 0, 0, 0, 0, 1, 0);

    private final int mask;
    private final float r, g, b, a;
    private final double depth;
    private final int stencil;

    private CgLoad(int mask, float r, float g, float b, float a, double depth, int stencil) {
        this.mask = mask;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.depth = depth;
        this.stencil = stencil;
    }

    /** Keep the target's contents. */
    public static CgLoad load() {
        return LOAD;
    }

    /** Clear colour to {@code (r, g, b, a)}, premultiplied. */
    public static CgLoad clear(float r, float g, float b, float a) {
        return new CgLoad(CgGL.GL_COLOR_BUFFER_BIT, r, g, b, a, 1, 0);
    }

    /** This load, clearing depth to {@code value} as well. */
    public CgLoad andDepth(double value) {
        return new CgLoad(mask | CgGL.GL_DEPTH_BUFFER_BIT, r, g, b, a, value, stencil);
    }

    /** This load, clearing stencil to {@code value} as well: a target with a stencil attachment. */
    public CgLoad andStencil(int value) {
        return new CgLoad(mask | CgGL.GL_STENCIL_BUFFER_BIT, r, g, b, a, depth, value);
    }

    /** The stencil it clears to, where {@link #mask()} holds {@code GL_STENCIL_BUFFER_BIT}. */
    public int stencil() {
        return stencil;
    }

    /** The {@code glClear} mask, 0 for {@link #load()}. */
    public int mask() {
        return mask;
    }

    public float r() {
        return r;
    }

    public float g() {
        return g;
    }

    public float b() {
        return b;
    }

    public float a() {
        return a;
    }

    public double depth() {
        return depth;
    }
}
