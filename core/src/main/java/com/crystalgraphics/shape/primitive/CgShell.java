package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;

/**
 * The space between two spheres about the origin: what hits only at its edge. A {@link #front front} is an expanding
 * blast's leading edge, {@code width} thick behind whatever radius a hitbox gives it.
 *
 * <pre>{@code
 * static final CgShape HALO = CgShell.of(3, 4);              // from 3 to 4 blocks out
 * static final CgShape FRONT = CgShell.front(2);             // an edge 2 thick, at the radius a hitbox gives it
 * }</pre>
 *
 * <ul>
 *   <li>Not convex: its support is its outer sphere's, and tests against it are its own.</li>
 *   <li>A front on its own, before anything sizes it, is a ball of radius {@code width}.</li>
 * </ul>
 */
public final class CgShell extends CgShape {

    private final double inner, outer;
    private final boolean front;

    private CgShell(double inner, double outer, boolean front) {
        this.inner = inner;
        this.outer = outer;
        this.front = front;
    }

    /** @throws IllegalArgumentException for a negative radius, or {@code inner} past {@code outer} */
    public static CgShell of(double inner, double outer) {
        CgShapeArgs.length("inner", inner);
        CgShapeArgs.length("outer", outer);
        if (inner > outer) throw new IllegalArgumentException("inner " + inner + " is past outer " + outer);
        return new CgShell(inner, outer, false);
    }

    /** A leading edge {@code width} thick: sized by a hitbox's radius, it reaches from {@code radius - width} out. */
    public static CgShell front(double width) {
        return new CgShell(0, CgShapeArgs.length("width", width), true);
    }

    public double inner() {
        return inner;
    }

    public double outer() {
        return outer;
    }

    /** Whether this is a {@link #front}, keeping its width as its radius changes. */
    public boolean isFront() {
        return front;
    }

    public double width() {
        return outer - inner;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        return CgShapeArgs.toLength(dx, dy, dz, outer, out);
    }

    @Override
    public boolean isConvex() {
        return inner == 0;
    }

    @Override
    public CgAABB bounds(CgAABB out) {
        return out.set(-outer, -outer, -outer, outer, outer, outer);
    }

    @Override
    public CgAABB bounds(CgPose pose, CgAABB out) {
        return out.setCenterHalf(pose.x, pose.y, pose.z, outer, outer, outer);
    }

    @Override
    public String toString() {
        return front ? "CgShell[front " + outer + "]" : "CgShell[" + inner + " .. " + outer + "]";
    }
}
