package com.crystalgraphics.collision.debug;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * A shape's wireframe as line segments in the world: what a debug view draws. Pure, so a server writes the same lines
 * a client draws.
 *
 * <pre>{@code
 * CgShapeLines lines = new CgShapeLines();                      // reused: writing allocates nothing
 * lines.write(CgSphere.of(2), pose.at(x, y, z), (ax, ay, az, bx, by, bz) -> draw(ax, ay, az, bx, by, bz));
 * }</pre>
 *
 * <ul>
 *   <li>The outlines are Unreal's {@code DrawWire*}: a ball's three great circles, a capsule's two rings, four sides
 *       and end arcs, a cylinder's two rings and four sides, a cone's rim, four sides and cap arcs. A shell or ring
 *       draws both its walls, a compound each child, a plane a disc of at most {@link #PLANE_SHOWN} with its normal.
 *       Any other shape draws its support around its three axes.</li>
 *   <li>Circles take {@link #CIRCLE} segments. One instance a thread.</li>
 * </ul>
 */
public final class CgShapeLines {

    /** Segments in a full circle. */
    public static final int CIRCLE = 24;
    /** How far a plane's disc is drawn from its origin: its own radius is usually Jolt's 1000. */
    public static final double PLANE_SHOWN = 4;

    /** Where {@link #write} puts each segment, in world space. */
    @FunctionalInterface
    public interface Sink {
        void line(double ax, double ay, double az, double bx, double by, double bz);
    }

    private CgPose[] poses = {new CgPose()};
    private int depth;
    private final Vector3d a = new Vector3d(), b = new Vector3d(), u = new Vector3d(), v = new Vector3d();
    private Sink out;

    /** Writes {@code shape} placed at {@code pose} into {@code out}. */
    public void write(CgShape shape, CgPose pose, Sink out) {
        this.out = out;
        depth = 0;
        poses[0].set(pose);
        try {
            shape(shape);
        } finally {
            this.out = null;
        }
    }

    private void shape(CgShape shape) {
        if (shape instanceof CgBox) box((CgBox) shape);
        else if (shape instanceof CgSphere) sphere(((CgSphere) shape).radius());
        else if (shape instanceof CgCapsule) capsule((CgCapsule) shape);
        else if (shape instanceof CgCylinder) cylinder(((CgCylinder) shape).radius(), ((CgCylinder) shape).halfHeight());
        else if (shape instanceof CgCone) cone(((CgCone) shape).range(), ((CgCone) shape).halfAngle());
        else if (shape instanceof CgShell) shell((CgShell) shape);
        else if (shape instanceof CgRing) ring((CgRing) shape);
        else if (shape instanceof CgPlane) plane((CgPlane) shape);
        else if (shape instanceof CgCompound) compound((CgCompound) shape);
        else supportLoops(shape);
    }

    private void box(CgBox box) {
        double x = box.halfX(), y = box.halfY(), z = box.halfZ();
        for (int s = -1; s <= 1; s += 2) {
            for (int t = -1; t <= 1; t += 2) {
                line(-x, s * y, t * z, x, s * y, t * z);
                line(s * x, -y, t * z, s * x, y, t * z);
                line(s * x, t * y, -z, s * x, t * y, z);
            }
        }
    }

    private void sphere(double r) {
        arc(0, 0, 0, 1, 0, 0, 0, 1, 0, r, 0, 2 * Math.PI);
        arc(0, 0, 0, 0, 1, 0, 0, 0, 1, r, 0, 2 * Math.PI);
        arc(0, 0, 0, 0, 0, 1, 1, 0, 0, r, 0, 2 * Math.PI);
    }

    private void capsule(CgCapsule capsule) {
        double r = capsule.radius();
        capsule.a(a);
        capsule.b(b);
        double ax = a.x, ay = a.y, az = a.z, bx = b.x, by = b.y, bz = b.z;
        double wx = bx - ax, wy = by - ay, wz = bz - az;
        double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (length < 1e-9) {
            centred(ax, ay, az, r);
            return;
        }
        wx /= length;
        wy /= length;
        wz /= length;
        basis(wx, wy, wz);
        double ux = u.x, uy = u.y, uz = u.z, vx = v.x, vy = v.y, vz = v.z;
        arc(ax, ay, az, ux, uy, uz, vx, vy, vz, r, 0, 2 * Math.PI);
        arc(bx, by, bz, ux, uy, uz, vx, vy, vz, r, 0, 2 * Math.PI);
        for (int s = -1; s <= 1; s += 2) {
            line(ax + s * r * ux, ay + s * r * uy, az + s * r * uz, bx + s * r * ux, by + s * r * uy, bz + s * r * uz);
            line(ax + s * r * vx, ay + s * r * vy, az + s * r * vz, bx + s * r * vx, by + s * r * vy, bz + s * r * vz);
        }
        // the end caps: half circles over each end, in the two planes through the axis
        arc(bx, by, bz, ux, uy, uz, wx, wy, wz, r, 0, Math.PI);
        arc(bx, by, bz, vx, vy, vz, wx, wy, wz, r, 0, Math.PI);
        arc(ax, ay, az, ux, uy, uz, -wx, -wy, -wz, r, 0, Math.PI);
        arc(ax, ay, az, vx, vy, vz, -wx, -wy, -wz, r, 0, Math.PI);
    }

    private void cylinder(double r, double h) {
        arc(0, h, 0, 1, 0, 0, 0, 0, 1, r, 0, 2 * Math.PI);
        arc(0, -h, 0, 1, 0, 0, 0, 0, 1, r, 0, 2 * Math.PI);
        line(r, -h, 0, r, h, 0);
        line(-r, -h, 0, -r, h, 0);
        line(0, -h, r, 0, h, r);
        line(0, -h, -r, 0, h, -r);
    }

    private void cone(double range, double halfAngle) {
        double rimZ = range * Math.cos(halfAngle), rimR = range * Math.sin(halfAngle);
        if (rimR > 1e-9) {
            arc(0, 0, rimZ, 1, 0, 0, 0, 1, 0, rimR, 0, 2 * Math.PI);
            line(0, 0, 0, rimR, 0, rimZ);
            line(0, 0, 0, -rimR, 0, rimZ);
            line(0, 0, 0, 0, rimR, rimZ);
            line(0, 0, 0, 0, -rimR, rimZ);
        }
        // the far cap is part of the sphere of radius range: arcs over it from rim to rim
        arc(0, 0, 0, 0, 0, 1, 1, 0, 0, range, -halfAngle, halfAngle);
        arc(0, 0, 0, 0, 0, 1, 0, 1, 0, range, -halfAngle, halfAngle);
    }

    private void shell(CgShell shell) {
        sphere(shell.outer());
        if (shell.inner() > 0) sphere(shell.inner());
    }

    private void ring(CgRing ring) {
        cylinder(ring.outer(), ring.halfHeight());
        if (ring.inner() > 0) cylinder(ring.inner(), ring.halfHeight());
    }

    private void plane(CgPlane plane) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz(), r = Math.min(plane.radius(), PLANE_SHOWN);
        basis(nx, ny, nz);
        double ux = u.x, uy = u.y, uz = u.z, vx = v.x, vy = v.y, vz = v.z;
        arc(0, 0, 0, ux, uy, uz, vx, vy, vz, r, 0, 2 * Math.PI);
        line(-r * ux, -r * uy, -r * uz, r * ux, r * uy, r * uz);
        line(-r * vx, -r * vy, -r * vz, r * vx, r * vy, r * vz);
        line(0, 0, 0, nx, ny, nz);
    }

    private void compound(CgCompound compound) {
        if (depth + 1 == poses.length) {
            poses = Arrays.copyOf(poses, poses.length * 2);
            for (int i = depth + 1; i < poses.length; i++) poses[i] = new CgPose();
        }
        CgPose parent = poses[depth], child = poses[depth + 1];
        depth++;
        try {
            for (int i = 0; i < compound.size(); i++) {
                parent.mul(compound.pose(i, child), child);
                shape(compound.shape(i));
            }
        } finally {
            depth--;
        }
    }

    /** A ball of radius {@code r} about a local point. */
    private void centred(double cx, double cy, double cz, double r) {
        arc(cx, cy, cz, 1, 0, 0, 0, 1, 0, r, 0, 2 * Math.PI);
        arc(cx, cy, cz, 0, 1, 0, 0, 0, 1, r, 0, 2 * Math.PI);
        arc(cx, cy, cz, 0, 0, 1, 1, 0, 0, r, 0, 2 * Math.PI);
    }

    /** The farthest points along each direction around the three axes, joined: a hull's girth. */
    private void supportLoops(CgShape shape) {
        for (int axis = 0; axis < 3; axis++) {
            double px = 0, py = 0, pz = 0;
            for (int i = 0; i <= CIRCLE; i++) {
                double t = 2 * Math.PI * i / CIRCLE, c = Math.cos(t), s = Math.sin(t);
                if (axis == 0) shape.support(0, c, s, a);
                else if (axis == 1) shape.support(s, 0, c, a);
                else shape.support(c, s, 0, a);
                if (i > 0) line(px, py, pz, a.x, a.y, a.z);
                px = a.x;
                py = a.y;
                pz = a.z;
            }
        }
    }

    /** Points {@code c + r (cos t U + sin t V)} for {@code t} from {@code from} to {@code to}, in local space. */
    private void arc(double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy, double vz,
            double r, double from, double to) {
        int segments = Math.max(2, (int) Math.ceil(CIRCLE * (to - from) / (2 * Math.PI)));
        double px = 0, py = 0, pz = 0;
        for (int i = 0; i <= segments; i++) {
            double t = from + (to - from) * i / segments, c = r * Math.cos(t), s = r * Math.sin(t);
            double x = cx + c * ux + s * vx, y = cy + c * uy + s * vy, z = cz + c * uz + s * vz;
            if (i > 0) line(px, py, pz, x, y, z);
            px = x;
            py = y;
            pz = z;
        }
    }

    /** Two unit vectors into {@link #u} and {@link #v}, square to each other and to the unit {@code w}. */
    private void basis(double wx, double wy, double wz) {
        if (Math.abs(wx) < 0.9) u.set(0, -wz, wy);
        else u.set(wz, 0, -wx);
        u.normalize();
        v.set(wy * u.z - wz * u.y, wz * u.x - wx * u.z, wx * u.y - wy * u.x);
    }

    /** A segment in the current shape's space, out in the world. */
    private void line(double ax, double ay, double az, double bx, double by, double bz) {
        CgPose pose = poses[depth];
        double wax = pose.m00() * ax + pose.m01() * ay + pose.m02() * az + pose.x;
        double way = pose.m10() * ax + pose.m11() * ay + pose.m12() * az + pose.y;
        double waz = pose.m20() * ax + pose.m21() * ay + pose.m22() * az + pose.z;
        double wbx = pose.m00() * bx + pose.m01() * by + pose.m02() * bz + pose.x;
        double wby = pose.m10() * bx + pose.m11() * by + pose.m12() * bz + pose.y;
        double wbz = pose.m20() * bx + pose.m21() * by + pose.m22() * bz + pose.z;
        out.line(wax, way, waz, wbx, wby, wbz);
    }
}
