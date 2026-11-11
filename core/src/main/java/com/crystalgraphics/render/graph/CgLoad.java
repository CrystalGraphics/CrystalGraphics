package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgPassDesc;

/**
 * What a raster pass does with its target before drawing, per aspect: keep what is there, or clear it (a render pass's
 * load operations).
 *
 * <pre>{@code
 * CgLoad.load()                          // draw over what the target holds
 * CgLoad.clear(0, 0, 0, 0)               // a layer: transparent first
 * CgLoad.clear(0, 0, 0, 1).andDepth(1)   // a preview with a depth buffer
 * CgLoad.clear(0, 0, 0, 0).andDepth(1).andStencil(0)   // a mask: depth and stencil too
 * }</pre>
 *
 * <ul>
 *   <li>Depth values are far at 1, near at 0; the executor mirrors them for a host whose depth runs far to near.</li>
 *   <li>An aspect the target lacks is ignored: a depth clear on a target of colour alone does nothing.</li>
 * </ul>
 */
public final class CgLoad {

    private static final CgLoad LOAD = new CgLoad(CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.LoadOp.LOAD, 1,
            CgPassDesc.LoadOp.LOAD, 0);

    private final CgPassDesc.LoadOp color, depthOp, stencilOp;
    private final float r, g, b, a;
    private final double depth;
    private final int stencil;

    private CgLoad(CgPassDesc.LoadOp color, float r, float g, float b, float a, CgPassDesc.LoadOp depthOp, double depth,
                   CgPassDesc.LoadOp stencilOp, int stencil) {
        this.color = color;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.depthOp = depthOp;
        this.depth = depth;
        this.stencilOp = stencilOp;
        this.stencil = stencil;
    }

    /** Keep the target's contents. */
    public static CgLoad load() {
        return LOAD;
    }

    /** Clear colour to {@code (r, g, b, a)}, premultiplied; depth and stencil kept. */
    public static CgLoad clear(float r, float g, float b, float a) {
        return new CgLoad(CgPassDesc.LoadOp.CLEAR, r, g, b, a, CgPassDesc.LoadOp.LOAD, 1, CgPassDesc.LoadOp.LOAD, 0);
    }

    /** This load, clearing depth to {@code value} as well. */
    public CgLoad andDepth(double value) {
        return new CgLoad(color, r, g, b, a, CgPassDesc.LoadOp.CLEAR, value, stencilOp, stencil);
    }

    /** This load, clearing stencil to {@code value} as well: a target with a stencil attachment. */
    public CgLoad andStencil(int value) {
        return new CgLoad(color, r, g, b, a, depthOp, depth, CgPassDesc.LoadOp.CLEAR, value);
    }

    /** What happens to colour. */
    public CgPassDesc.LoadOp color() {
        return color;
    }

    /** What happens to depth. */
    public CgPassDesc.LoadOp depthOp() {
        return depthOp;
    }

    /** What happens to stencil. */
    public CgPassDesc.LoadOp stencilOp() {
        return stencilOp;
    }

    public boolean clearsColor() {
        return color == CgPassDesc.LoadOp.CLEAR;
    }

    public boolean clearsDepth() {
        return depthOp == CgPassDesc.LoadOp.CLEAR;
    }

    public boolean clearsStencil() {
        return stencilOp == CgPassDesc.LoadOp.CLEAR;
    }

    /** Whether it clears anything: a pass that does begins a render pass of its own. */
    public boolean clears() {
        return clearsColor() || clearsDepth() || clearsStencil();
    }

    /** The stencil it clears to, where {@link #clearsStencil()}. */
    public int stencil() {
        return stencil;
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

    /** The depth it clears to, where {@link #clearsDepth()}: far at 1. */
    public double depth() {
        return depth;
    }
}
