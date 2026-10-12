package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;

/**
 * A shape sized in place, allocating nothing: what a growing blast front or a scaling hitbox resizes each tick. It
 * keeps a private copy of the shape it was made from and hands that copy out at the size asked; the shape it was made
 * from never changes.
 *
 * <pre>{@code
 * private final CgResizable front = CgResizable.of(CgShell.front(2));   // once
 *
 * CgShape now = front.radius(6);                  // a shell from 4 to 6 blocks out
 * CgShape swept = front.sweptRadius(5, 6);        // all it covered growing from 5 to 6: from 3 to 6
 * }</pre>
 *
 * A shape with no radius scales instead:
 *
 * <pre>{@code
 * CgResizable slam = CgResizable.of(CgBox.of(2, 0.5, 3));
 * CgShape twice = slam.scale(2);                  // 8 wide, 2 high, 12 long
 * }</pre>
 *
 * <ul>
 *   <li>A radius is a sphere's, cylinder's or capsule's radius, a cone's range, or a shell's or ring's outer edge: a
 *       front keeps its width, any other shell or ring its proportions. A box has none.</li>
 *   <li>A scale multiplies every length about the shape's origin.</li>
 *   <li>The shape handed out changes with the next call: use it at once, never keep or share it, one thread each.</li>
 * </ul>
 */
public final class CgResizable {

    private final CgSizedShape base, copy;

    private CgResizable(CgSizedShape base, CgSizedShape copy) {
        this.base = base;
        this.copy = copy;
    }

    /**
     * @throws IllegalArgumentException for a compound, a convex hull or a plane, which have neither a radius nor a
     *                                  resize
     */
    public static CgResizable of(CgShape base) {
        if (!(base instanceof CgSizedShape)) throw new IllegalArgumentException("cannot resize " + base);
        CgSizedShape sized = (CgSizedShape) base;
        return new CgResizable(sized, sized.copy());
    }

    /** The shape it was made from, unchanged. */
    public CgShape base() {
        return base;
    }

    /** Whether {@link #radius} applies: everything but a box. */
    public boolean hasRadius() {
        return base.hasRadius();
    }

    /**
     * The shape at radius {@code r}.
     *
     * @throws IllegalArgumentException for a negative radius, or on a box
     */
    public CgShape radius(double r) {
        return sweptRadius(r, r);
    }

    /**
     * Everything the shape covers at every radius from {@code from} to {@code to}: what a growing shape swept between
     * two ticks.
     */
    public CgShape sweptRadius(double from, double to) {
        CgShapeArgs.length("radius", from);
        CgShapeArgs.length("radius", to);
        base.sweptRadius(copy, Math.min(from, to), Math.max(from, to));
        return copy;
    }

    /** The shape with every length times {@code s}. */
    public CgShape scale(double s) {
        return sweptScale(s, s);
    }

    /** Everything the shape covers at every scale from {@code from} to {@code to}. */
    public CgShape sweptScale(double from, double to) {
        CgShapeArgs.length("scale", from);
        CgShapeArgs.length("scale", to);
        base.sweptScale(copy, Math.min(from, to), Math.max(from, to));
        return copy;
    }

    @Override
    public String toString() {
        return "CgResizable[" + base + " now " + copy + "]";
    }
}
