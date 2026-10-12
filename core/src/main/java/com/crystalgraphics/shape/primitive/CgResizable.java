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

    private final CgShape base, copy;

    private CgResizable(CgShape base, CgShape copy) {
        this.base = base;
        this.copy = copy;
    }

    /**
     * @throws IllegalArgumentException for a compound or a convex hull, which have neither a radius nor a resize
     */
    public static CgResizable of(CgShape base) {
        CgShape copy;
        if (base instanceof CgSphere) copy = ((CgSphere) base).copy();
        else if (base instanceof CgBox) copy = ((CgBox) base).copy();
        else if (base instanceof CgCapsule) copy = ((CgCapsule) base).copy();
        else if (base instanceof CgCylinder) copy = ((CgCylinder) base).copy();
        else if (base instanceof CgCone) copy = ((CgCone) base).copy();
        else if (base instanceof CgShell) copy = ((CgShell) base).copy();
        else if (base instanceof CgRing) copy = ((CgRing) base).copy();
        else throw new IllegalArgumentException("cannot resize " + base);
        return new CgResizable(base, copy);
    }

    /** The shape it was made from, unchanged. */
    public CgShape base() {
        return base;
    }

    /** Whether {@link #radius} applies: everything but a box. */
    public boolean hasRadius() {
        return !(base instanceof CgBox);
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
        double lo = Math.min(from, to), hi = Math.max(from, to);
        if (base instanceof CgSphere) {
            ((CgSphere) copy).resize(hi);
        } else if (base instanceof CgCylinder) {
            ((CgCylinder) copy).resize(hi, ((CgCylinder) base).halfHeight());
        } else if (base instanceof CgCapsule) {
            CgCapsule b = (CgCapsule) base;
            ((CgCapsule) copy).resize(b.ax(), b.ay(), b.az(), b.bx(), b.by(), b.bz(), hi);
        } else if (base instanceof CgCone) {
            ((CgCone) copy).resize(hi);
        } else if (base instanceof CgShell) {
            CgShell b = (CgShell) base;
            ((CgShell) copy).resize(innerAt(b.inner(), b.outer(), b.isFront(), lo), hi);
        } else if (base instanceof CgRing) {
            CgRing b = (CgRing) base;
            ((CgRing) copy).resize(innerAt(b.inner(), b.outer(), b.isFront(), lo), hi, b.halfHeight());
        } else {
            throw new IllegalArgumentException("a box has no radius: scale it");
        }
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
        double lo = Math.min(from, to), hi = Math.max(from, to);
        if (base instanceof CgSphere) {
            ((CgSphere) copy).resize(((CgSphere) base).radius() * hi);
        } else if (base instanceof CgBox) {
            CgBox b = (CgBox) base;
            ((CgBox) copy).resize(b.halfX() * hi, b.halfY() * hi, b.halfZ() * hi);
        } else if (base instanceof CgCylinder) {
            CgCylinder b = (CgCylinder) base;
            ((CgCylinder) copy).resize(b.radius() * hi, b.halfHeight() * hi);
        } else if (base instanceof CgCapsule) {
            CgCapsule b = (CgCapsule) base;
            ((CgCapsule) copy).resize(b.ax() * hi, b.ay() * hi, b.az() * hi, b.bx() * hi, b.by() * hi, b.bz() * hi,
                    b.radius() * hi);
        } else if (base instanceof CgCone) {
            ((CgCone) copy).resize(((CgCone) base).range() * hi);
        } else if (base instanceof CgShell) {
            CgShell b = (CgShell) base;
            ((CgShell) copy).resize(b.inner() * lo, b.outer() * hi);
        } else {
            CgRing b = (CgRing) base;
            ((CgRing) copy).resize(b.inner() * lo, b.outer() * hi, b.halfHeight() * hi);
        }
        return copy;
    }

    /** A shell's or ring's inner edge at outer radius {@code r}. */
    private static double innerAt(double inner, double outer, boolean front, double r) {
        if (front) return Math.max(0, r - (outer - inner));
        return outer == 0 ? 0 : inner * r / outer;
    }

    @Override
    public String toString() {
        return "CgResizable[" + base + " now " + copy + "]";
    }
}
