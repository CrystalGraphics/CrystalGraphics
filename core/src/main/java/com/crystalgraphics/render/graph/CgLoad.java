package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgPassDesc;

/**
 * What a raster pass does with its target before drawing, per aspect: keep what is there, or clear it (a render pass's
 * load operations).
 *
 * <pre>{@code
 * CgLoad.load()                          // draw over what the target holds
 * CgLoad.CLEAR                           // a colour: transparent black first
 * CgLoad.clear(0.5f, 0.7f, 1f, 1f)       // another colour
 * CgLoad.CLEAR_DEPTH                     // a depth: far first
 * CgLoad.CLEAR_DEPTH.andStencil(0)       // a depth and its stencil
 *
 * rec.raster("preview").color(picture, CgLoad.CLEAR).depth(depth, CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE);
 * }</pre>
 *
 * <ul>
 *   <li>Depth values are far at 1, near at 0; the executor mirrors them for a host whose depth runs far to near. A
 *       depth cleared to 0 is near: every draw then fails its test.</li>
 *   <li>Loads are values: the constants and {@link #load()} allocate nothing.</li>
 *   <li>A colour's load clears colour alone and a depth's depth and stencil alone: each throws for the other's.</li>
 * </ul>
 */
public final class CgLoad {

    private static final CgLoad LOAD = new CgLoad(CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.LoadOp.LOAD, 1,
            CgPassDesc.LoadOp.LOAD, 0);
    /** Colour cleared to transparent black, {@code (0, 0, 0, 0)}: most clears. */
    public static final CgLoad CLEAR = new CgLoad(CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 0, CgPassDesc.LoadOp.LOAD, 1,
            CgPassDesc.LoadOp.LOAD, 0);
    /** Depth cleared to far, 1; stencil kept. */
    public static final CgLoad CLEAR_DEPTH = new CgLoad(CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.LoadOp.CLEAR, 1,
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
        if (r == 0f && g == 0f && b == 0f && a == 0f) return CLEAR;
        return new CgLoad(CgPassDesc.LoadOp.CLEAR, r, g, b, a, CgPassDesc.LoadOp.LOAD, 1, CgPassDesc.LoadOp.LOAD, 0);
    }

    /** Clear depth to {@code value}, far at 1; stencil kept. A depth's load ({@link CgRasterPass#depth}). */
    public static CgLoad clearDepth(double value) {
        if (value == 1.0) return CLEAR_DEPTH;
        return new CgLoad(CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.LoadOp.CLEAR, value, CgPassDesc.LoadOp.LOAD, 0);
    }

    /** A pass's whole load: {@code color}'s colour, {@code depth}'s depth and stencil. */
    static CgLoad of(CgLoad color, CgLoad depth) {
        if (!depth.clearsDepth() && !depth.clearsStencil()) return color;
        if (!color.clearsColor()) return depth;
        return new CgLoad(color.color, color.r, color.g, color.b, color.a, depth.depthOp, depth.depth, depth.stencilOp,
                depth.stencil);
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
