package com.crystalgraphics.collision.debug;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgConvex;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A shape's wireframe as line segments in the world: what a debug view draws. Pure, so a server writes the same lines
 * a client draws.
 *
 * <pre>{@code
 * CgShapeLines lines = new CgShapeLines();                      // reused: writing allocates nothing
 * lines.write(CgSphere.of(2), pose.at(x, y, z), (ax, ay, az, bx, by, bz) -> draw(ax, ay, az, bx, by, bz));
 * }</pre>
 *
 * Its faces too, as triangles, for a view that fills a shape in:
 *
 * <pre>{@code
 * lines.faces(CgSphere.of(2), pose.at(x, y, z), sink);          // into the sink's triangle(...)
 * }</pre>
 *
 * Given where the eye is, a ball also draws its outline as the eye sees it, as Unity's sphere gizmo does, so it reads
 * as a ball however its circles turn:
 *
 * <pre>{@code
 * lines.eye(camX, camY, camZ);                                  // until changed; noEye() for none, as on a server
 * }</pre>
 *
 * <ul>
 *   <li>The outlines are Unreal's {@code DrawWire*}: a ball's three great circles, a capsule's two rings, four sides
 *       and end arcs, a cylinder's two rings and four sides, a cone's rim, four sides and cap arcs. A shell or ring
 *       draws both its walls, a compound each child, a plane a disc of at most {@link #PLANE_SHOWN} with its normal,
 *       a hull its edges. A shape known only by its support draws that around its three axes.</li>
 *   <li>Faces are the shape's whole surface, each part once, wound counter-clockwise seen from outside the solid
 *       (a shell's inner ball and a ring's inner wall face their hollow), so a viewer can tell back faces from front.
 *       A hull's are its own faces; a support shape's, its support over a ball of directions.</li>
 *   <li>Circles take {@link #CIRCLE} segments, edges and faces alike: every corner of an outline is a corner of the
 *       faces, so a fill meets its edges. One instance a thread.</li>
 * </ul>
 */
final class CgShapeLines {

    /** Segments in a full circle. */
    public static final int CIRCLE = 24;
    /** How far a plane's disc is drawn from its origin: its own radius is usually Jolt's 1000. */
    public static final double PLANE_SHOWN = 4;
    private static final double[] COS = new double[CIRCLE + 1], SIN = new double[CIRCLE + 1];

    static {
        for (int i = 0; i <= CIRCLE; i++) {
            COS[i] = Math.cos(2 * Math.PI * i / CIRCLE);
            SIN[i] = Math.sin(2 * Math.PI * i / CIRCLE);
        }
    }

    /** Where {@link #write} puts each segment and {@link #faces} each triangle, in world space. */
    @FunctionalInterface
    public interface Sink {
        void line(double ax, double ay, double az, double bx, double by, double bz);

        /** A triangle of a shape's surface; a sink of lines alone leaves it. */
        default void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
                double cz) {
        }
    }

    private CgPose[] poses = {new CgPose()};
    private int depth;
    private final Vector3d a = new Vector3d(), b = new Vector3d(), u = new Vector3d(), v = new Vector3d();
    private final Vector3d local = new Vector3d();
    private Sink out;
    /** A surface of revolution's outline, as pairs: how far from its axis, how far along it. */
    private final double[] profile = new double[2 * (CIRCLE + 8)];
    private int profiled;
    /** A support surface's last row of points and the one being written. */
    private double[] rowBefore = new double[3 * (CIRCLE + 1)], row = new double[3 * (CIRCLE + 1)];
    /** Each hull's faces and edges, built the first time it is drawn. */
    private final Map<CgConvex, HullMesh> hulls = new WeakHashMap<>();
    private double eyeX = Double.NaN, eyeY, eyeZ;
    /** Whether the faces being written face in: a shell's inner ball. */
    private boolean inward;

    /** Where the eye is, in the world: each ball written from now on draws its outline from there too. */
    public CgShapeLines eye(double x, double y, double z) {
        eyeX = x;
        eyeY = y;
        eyeZ = z;
        return this;
    }

    /** No eye: balls draw their three circles alone. */
    public CgShapeLines noEye() {
        eyeX = Double.NaN;
        return this;
    }

    /** A world box's twelve edges. */
    public static void box(CgAABBc box, Sink out) {
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX(), y1 = box.maxY(), z1 = box.maxZ();
        for (int s = 0; s < 2; s++) {
            for (int t = 0; t < 2; t++) {
                double xs = s == 0 ? x0 : x1, ys = s == 0 ? y0 : y1, yt = t == 0 ? y0 : y1, zt = t == 0 ? z0 : z1;
                out.line(x0, ys, zt, x1, ys, zt);
                out.line(xs, y0, zt, xs, y1, zt);
                out.line(xs, yt, z0, xs, yt, z1);
            }
        }
    }

    /** A hit: a cross {@code size} wide each way on the surface at a point, and its unit normal. */
    public static void mark(double x, double y, double z, double nx, double ny, double nz, double size, Sink out) {
        // two tangents: the normal crossed with whichever axis it is furthest from, then with that
        double ax = Math.abs(ny) < 0.9 ? 0 : 1, ay = 1 - ax;
        double tx = -nz * ay, ty = nz * ax, tz = nx * ay - ny * ax;
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len == 0) return;
        double r = size / len;
        tx *= r;
        ty *= r;
        tz *= r;
        double ux = ny * tz - nz * ty, uy = nz * tx - nx * tz, uz = nx * ty - ny * tx;
        out.line(x - tx, y - ty, z - tz, x + tx, y + ty, z + tz);
        out.line(x - ux, y - uy, z - uz, x + ux, y + uy, z + uz);
        out.line(x, y, z, x + nx, y + ny, z + nz);
    }

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

    /** Writes the surface of {@code shape} placed at {@code pose} into {@code out}, as triangles. */
    public void faces(CgShape shape, CgPose pose, Sink out) {
        this.out = out;
        depth = 0;
        poses[0].set(pose);
        try {
            surface(shape);
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
        else if (mesh(shape) != null) hullEdges(mesh(shape));
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
        centred(0, 0, 0, r);
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
        // the far cap is part of the sphere of radius range: arcs over it from rim to rim, from the axis out so their
        // corners are the faces'
        arc(0, 0, 0, 0, 0, 1, 1, 0, 0, range, 0, halfAngle);
        arc(0, 0, 0, 0, 0, 1, 1, 0, 0, range, 0, -halfAngle);
        arc(0, 0, 0, 0, 0, 1, 0, 1, 0, range, 0, halfAngle);
        arc(0, 0, 0, 0, 0, 1, 0, 1, 0, range, 0, -halfAngle);
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
        compound(compound, false);
    }

    private void compound(CgCompound compound, boolean faces) {
        if (depth + 1 == poses.length) {
            poses = Arrays.copyOf(poses, poses.length * 2);
            for (int i = depth + 1; i < poses.length; i++) poses[i] = new CgPose();
        }
        CgPose parent = poses[depth], child = poses[depth + 1];
        depth++;
        try {
            for (int i = 0; i < compound.size(); i++) {
                parent.mul(compound.pose(i, child), child);
                if (faces) surface(compound.shape(i));
                else shape(compound.shape(i));
            }
        } finally {
            depth--;
        }
    }

    private void surface(CgShape shape) {
        if (shape instanceof CgBox) {
            boxFaces((CgBox) shape);
        } else if (shape instanceof CgSphere) {
            ball(0, 0, 0, ((CgSphere) shape).radius());
        } else if (shape instanceof CgCapsule) {
            capsuleFaces((CgCapsule) shape);
        } else if (shape instanceof CgCylinder) {
            ringFaces(0, ((CgCylinder) shape).radius(), ((CgCylinder) shape).halfHeight());
        } else if (shape instanceof CgCone) {
            coneFaces(((CgCone) shape).range(), ((CgCone) shape).halfAngle());
        } else if (shape instanceof CgShell) {
            ball(0, 0, 0, ((CgShell) shape).outer());
            if (((CgShell) shape).inner() > 0) {
                inward = true;
                ball(0, 0, 0, ((CgShell) shape).inner());
                inward = false;
            }
        } else if (shape instanceof CgRing) {
            ringFaces(((CgRing) shape).inner(), ((CgRing) shape).outer(), ((CgRing) shape).halfHeight());
        } else if (shape instanceof CgPlane) {
            planeFaces((CgPlane) shape);
        } else if (shape instanceof CgCompound) {
            compound((CgCompound) shape, true);
        } else if (mesh(shape) != null) {
            hullFaces(mesh(shape));
        } else {
            supportFaces(shape);
        }
    }

    /** A hull's own faces and edges; null for a shape known only by its support, or a hull with none. */
    private HullMesh mesh(CgShape shape) {
        if (!(shape instanceof CgConvex) || ((CgConvex) shape).pointCount() == 0) return null;
        CgConvex hull = (CgConvex) shape;
        HullMesh mesh = hulls.get(hull);
        if (mesh == null && !hulls.containsKey(hull)) {
            mesh = HullMesh.of(hull);
            hulls.put(hull, mesh);
        }
        return mesh;
    }

    private void hullEdges(HullMesh mesh) {
        double[] e = mesh.edges;
        for (int i = 0; i < e.length; i += 6) line(e[i], e[i + 1], e[i + 2], e[i + 3], e[i + 4], e[i + 5]);
    }

    private void hullFaces(HullMesh mesh) {
        double[] t = mesh.triangles;
        for (int i = 0; i < t.length; i += 9) {
            triangle(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5], t[i + 6], t[i + 7], t[i + 8]);
        }
    }

    private void boxFaces(CgBox box) {
        double x = box.halfX(), y = box.halfY(), z = box.halfZ();
        for (int s = -1; s <= 1; s += 2) {
            // each side's corners counter-clockwise seen from its own side of the box
            quad(s * x, -y, -z, s * x, s * y, -s * z, s * x, y, z, s * x, -s * y, s * z);
            quad(-x, s * y, -z, -s * x, s * y, s * z, x, s * y, z, s * x, s * y, -s * z);
            quad(-x, -y, s * z, s * x, -s * y, s * z, x, y, s * z, -s * x, s * y, s * z);
        }
    }

    /** A ball of radius {@code r} about a local point. */
    private void ball(double cx, double cy, double cz, double r) {
        profiled = 0;
        profileArc(0, r, 0, Math.PI);
        lathe(cx, cy, cz, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    private void capsuleFaces(CgCapsule capsule) {
        double r = capsule.radius();
        capsule.a(a);
        capsule.b(b);
        double ax = a.x, ay = a.y, az = a.z;
        double wx = b.x - ax, wy = b.y - ay, wz = b.z - az;
        double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (length < 1e-9) {
            ball(ax, ay, az, r);
            return;
        }
        wx /= length;
        wy /= length;
        wz /= length;
        basis(wx, wy, wz);
        // the cap over b down to its rim, then a's rim to its cap: the side joins the two rims
        profiled = 0;
        profileArc(length, r, 0, Math.PI / 2);
        profileArc(0, r, Math.PI / 2, Math.PI);
        lathe(ax, ay, az, u.x, u.y, u.z, v.x, v.y, v.z, wx, wy, wz);
    }

    /** An upright ring's walls and its two ends; a cylinder with no {@code inner}. */
    private void ringFaces(double inner, double outer, double h) {
        profiled = 0;
        profile(inner, h);
        profile(outer, h);
        profile(outer, -h);
        profile(inner, -h);
        if (inner > 0) profile(inner, h);
        // turned from Z toward X about Y, the outline's corners at the same points as cylinder()'s rings
        lathe(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0);
    }

    private void coneFaces(double range, double halfAngle) {
        profiled = 0;
        profileArc(0, range, 0, halfAngle);
        profile(0, 0);
        lathe(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    private void planeFaces(CgPlane plane) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        basis(nx, ny, nz);
        profiled = 0;
        profile(0, 0);
        profile(Math.min(plane.radius(), PLANE_SHOWN), 0);
        lathe(0, 0, 0, u.x, u.y, u.z, v.x, v.y, v.z, nx, ny, nz);
    }

    /** The farthest point along each direction of a ball of them, joined band by band: a smooth shape's skin. */
    private void supportFaces(CgShape shape) {
        for (int band = 0; band <= CIRCLE / 2; band++) {
            double t = 2 * Math.PI * band / CIRCLE, round = Math.sin(t), up = Math.cos(t);
            for (int i = 0; i <= CIRCLE; i++) {
                shape.support(round * COS[i], up, round * SIN[i], a);
                row[i * 3] = a.x;
                row[i * 3 + 1] = a.y;
                row[i * 3 + 2] = a.z;
            }
            if (band > 0) {
                for (int i = 0; i < CIRCLE; i++) {
                    int p = i * 3, q = p + 3;
                    quad(rowBefore[p], rowBefore[p + 1], rowBefore[p + 2], rowBefore[q], rowBefore[q + 1],
                            rowBefore[q + 2], row[q], row[q + 1], row[q + 2], row[p], row[p + 1], row[p + 2]);
                }
            }
            double[] swap = rowBefore;
            rowBefore = row;
            row = swap;
        }
    }

    /** Adds a point to {@link #profile}: {@code r} from the axis, {@code along} it. */
    private void profile(double r, double along) {
        profile[profiled * 2] = r;
        profile[profiled * 2 + 1] = along;
        profiled++;
    }

    /** Adds an arc of a circle of radius {@code r}, centred {@code centre} along the axis, by its angle from it. */
    private void profileArc(double centre, double r, double from, double to) {
        int steps = segments(to - from);
        for (int i = 0; i <= steps; i++) {
            double t = from + (to - from) * i / steps;
            profile(r * Math.sin(t), centre + r * Math.cos(t));
        }
    }

    /**
     * Turns {@link #profile} about the axis W through a local point, U and V square to it with U x V = W: its
     * surface, facing away from the axis where the profile runs down W, and up W where it runs out from the axis.
     */
    private void lathe(double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy,
            double vz, double wx, double wy, double wz) {
        for (int p = 0; p + 1 < profiled; p++) {
            double r0 = profile[p * 2], h0 = profile[p * 2 + 1], r1 = profile[p * 2 + 2], h1 = profile[p * 2 + 3];
            double x0 = cx + h0 * wx, y0 = cy + h0 * wy, z0 = cz + h0 * wz;
            double x1 = cx + h1 * wx, y1 = cy + h1 * wy, z1 = cz + h1 * wz;
            for (int i = 0; i < CIRCLE; i++) {
                double ix = COS[i] * ux + SIN[i] * vx, iy = COS[i] * uy + SIN[i] * vy, iz = COS[i] * uz + SIN[i] * vz;
                double jx = COS[i + 1] * ux + SIN[i + 1] * vx, jy = COS[i + 1] * uy + SIN[i + 1] * vy;
                double jz = COS[i + 1] * uz + SIN[i + 1] * vz;
                // a point on the axis has no width: its half of the quad is left out
                if (r1 > 1e-9) {
                    triangle(x0 + r0 * ix, y0 + r0 * iy, z0 + r0 * iz, x1 + r1 * ix, y1 + r1 * iy, z1 + r1 * iz,
                            x1 + r1 * jx, y1 + r1 * jy, z1 + r1 * jz);
                }
                if (r0 > 1e-9) {
                    triangle(x0 + r0 * ix, y0 + r0 * iy, z0 + r0 * iz, x1 + r1 * jx, y1 + r1 * jy, z1 + r1 * jz,
                            x0 + r0 * jx, y0 + r0 * jy, z0 + r0 * jz);
                }
            }
        }
    }

    private void quad(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz, double dx, double dy, double dz) {
        triangle(ax, ay, az, bx, by, bz, cx, cy, cz);
        triangle(ax, ay, az, cx, cy, cz, dx, dy, dz);
    }

    /** A ball of radius {@code r} about a local point. */
    private void centred(double cx, double cy, double cz, double r) {
        arc(cx, cy, cz, 1, 0, 0, 0, 1, 0, r, 0, 2 * Math.PI);
        arc(cx, cy, cz, 0, 1, 0, 0, 0, 1, r, 0, 2 * Math.PI);
        arc(cx, cy, cz, 0, 0, 1, 1, 0, 0, r, 0, 2 * Math.PI);
        outline(cx, cy, cz, r);
    }

    /** Where the eye's lines of sight graze the ball: a circle nearer the eye than its centre, square to the view. */
    private void outline(double cx, double cy, double cz, double r) {
        if (Double.isNaN(eyeX)) return;
        poses[depth].toLocal(eyeX, eyeY, eyeZ, local);
        double dx = local.x - cx, dy = local.y - cy, dz = local.z - cz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 <= r * r) return;
        double d = Math.sqrt(d2), toward = r * r / d, radius = r * Math.sqrt(d2 - r * r) / d;
        dx /= d;
        dy /= d;
        dz /= d;
        basis(dx, dy, dz);
        arc(cx + dx * toward, cy + dy * toward, cz + dz * toward, u.x, u.y, u.z, v.x, v.y, v.z, radius, 0, 2 * Math.PI);
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
        int segments = segments(to - from);
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

    /** Segments in an arc of {@code angle}: a circle's share, at least two, the same for edges and faces. */
    private static int segments(double angle) {
        return Math.max(2, (int) Math.ceil(CIRCLE * Math.abs(angle) / (2 * Math.PI) - 1e-9));
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

    /** A triangle in the current shape's space, out in the world; turned over while {@link #inward}. */
    private void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz) {
        if (inward) {
            double tx = bx, ty = by, tz = bz;
            bx = cx;
            by = cy;
            bz = cz;
            cx = tx;
            cy = ty;
            cz = tz;
        }
        CgPose pose = poses[depth];
        out.triangle(pose.m00() * ax + pose.m01() * ay + pose.m02() * az + pose.x,
                pose.m10() * ax + pose.m11() * ay + pose.m12() * az + pose.y,
                pose.m20() * ax + pose.m21() * ay + pose.m22() * az + pose.z,
                pose.m00() * bx + pose.m01() * by + pose.m02() * bz + pose.x,
                pose.m10() * bx + pose.m11() * by + pose.m12() * bz + pose.y,
                pose.m20() * bx + pose.m21() * by + pose.m22() * bz + pose.z,
                pose.m00() * cx + pose.m01() * cy + pose.m02() * cz + pose.x,
                pose.m10() * cx + pose.m11() * cy + pose.m12() * cz + pose.y,
                pose.m20() * cx + pose.m21() * cy + pose.m22() * cz + pose.z);
    }
}
