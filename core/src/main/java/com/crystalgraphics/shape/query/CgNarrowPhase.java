package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * {@link CgShapes}' work, one per thread: compounds taken apart child by child, non-convex shapes to
 * {@link CgNonConvex}, closed forms where there are some, GJK and EPA for the rest. Every query works relative to its
 * first shape's position, so a world position far out costs no precision.
 */
final class CgNarrowPhase {

    static final ThreadLocal<CgNarrowPhase> CURRENT = ThreadLocal.withInitial(CgNarrowPhase::new);

    /** In blocks: how near two shapes may come and count as meeting. */
    static final double COLLISION_TOLERANCE = 1e-10;
    /** Relative to the squared depth: when EPA stops refining. */
    static final double PENETRATION_TOLERANCE = 1e-8;
    static final double CAST_TOLERANCE = 1e-10;
    /** In blocks: how far short of a cast's hit its normal is measured. */
    private static final double NORMAL_BACKOFF = 1e-4;

    /** What the walk does at each pair of leaves. */
    private static final int OVERLAP = 0, DISTANCE = 1, CONTACT = 2, CAST = 3, RAY = 4;

    private final CgGjk gjk = new CgGjk();
    private final CgEpa epa = new CgEpa(gjk);
    private final CgClosedForms closed = new CgClosedForms();
    private final CgClosedRays rays = new CgClosedRays();
    private final CgNonConvex nonConvex = new CgNonConvex(this);
    private final CgPlaced a = new CgPlaced(), b = new CgPlaced();
    private final Vector3d v = new Vector3d(), onA = new Vector3d(), onB = new Vector3d(), normal = new Vector3d();
    private final Vector3d refineA = new Vector3d(), refineB = new Vector3d();

    /** One per level of compound nesting, grown on first use. */
    private CgPose[] poses = new CgPose[0];
    private int depth;

    double baseX, baseY, baseZ;

    private int op;
    /** Stands in for the second shape when the query is against a box. */
    private @Nullable CgAABBc box;
    private boolean found;
    /** The best so far over a compound's children. */
    private double best;
    private final Vector3d bestA = new Vector3d(), bestB = new Vector3d(), bestNormal = new Vector3d();
    /** A ray, relative to the base point, its direction unit length; or a cast's motion. */
    private double ox, oy, oz, dx, dy, dz, max;

    private void start(int op, CgPose base, @Nullable CgAABBc box) {
        this.op = op;
        this.box = box;
        baseX = base.x;
        baseY = base.y;
        baseZ = base.z;
        found = false;
    }

    // ---- the walk

    /**
     * Takes compounds apart on either side and runs {@link #op} on each pair of leaves. {@code swapped}: a and b were
     * exchanged to take b's compound apart, so a directed answer turns back round. {@code sb} is null against a box or
     * for a ray.
     */
    private void walk(CgShape sa, CgPose pa, @Nullable CgShape sb, @Nullable CgPose pb, boolean swapped) {
        if (sa instanceof CgCompound) {
            CgCompound c = (CgCompound) sa;
            CgPose child = push();
            try {
                for (int i = 0; i < c.size() && !done(); i++) {
                    walk(c.shape(i), pa.mul(c.pose(i, child), child), sb, pb, swapped);
                }
            } finally {
                depth--;
            }
            return;
        }
        if (sb instanceof CgCompound) {
            walk(sb, pb, sa, pa, !swapped);
            return;
        }
        switch (op) {
            case OVERLAP:
                found = overlapLeaf(sa, pa, sb, pb);
                break;
            case DISTANCE:
                distanceLeaf(sa, pa, sb, pb);
                break;
            case CONTACT:
                contactLeaf(sa, pa, sb, pb, swapped);
                break;
            case CAST:
                castLeaf(sa, pa, sb, pb, swapped);
                break;
            default:
                rayLeaf(sa, pa);
        }
    }

    private boolean done() {
        return op == OVERLAP ? found : op == DISTANCE && best <= 0;
    }

    private CgPose push() {
        if (depth == poses.length) {
            CgPose[] grown = new CgPose[depth + 4];
            System.arraycopy(poses, 0, grown, 0, depth);
            for (int i = depth; i < grown.length; i++) grown[i] = new CgPose();
            poses = grown;
        }
        return poses[depth++];
    }

    private CgPlaced placeB(@Nullable CgShape sb, @Nullable CgPose pb) {
        return box != null ? b.box(box, baseX, baseY, baseZ) : b.set(sb, pb, baseX, baseY, baseZ);
    }

    // ---- overlap

    boolean overlaps(CgShape sa, CgPose pa, CgShape sb, CgPose pb) {
        start(OVERLAP, pa, null);
        walk(sa, pa, sb, pb, false);
        return found;
    }

    boolean overlaps(CgShape s, CgPose p, CgAABBc box) {
        if (box.isEmpty()) return false;
        start(OVERLAP, p, box);
        walk(s, p, null, null, false);
        return found;
    }

    private boolean overlapLeaf(CgShape sa, CgPose pa, @Nullable CgShape sb, @Nullable CgPose pb) {
        if (!sa.isConvex()) {
            if (sb != null && !sb.isConvex()) {
                throw new IllegalArgumentException("no overlap test between two non-convex shapes: " + sa + ", " + sb);
            }
            return nonConvex.overlaps(sa, pa, placeB(sb, pb));
        }
        if (sb != null && !sb.isConvex()) return nonConvex.overlaps(sb, pb, a.set(sa, pa, baseX, baseY, baseZ));
        return convexOverlap(a.set(sa, pa, baseX, baseY, baseZ), placeB(sb, pb));
    }

    /** Two placed convex shapes; leaves {@link #a} and {@link #b} alone, which {@link CgNonConvex} relies on. */
    boolean convexOverlap(CgPlaced pa, CgPlaced pb) {
        int answer = closed.overlap(pa, pb);
        if (answer != CgClosedForms.UNKNOWN) return answer == 1;
        startAxis(pa, pb);
        double radius = pa.radius() + pb.radius();
        if (radius == 0) return gjk.intersects(pa, pb, COLLISION_TOLERANCE, v);
        // GJK's separating-axis exit ignores radii, so a round pair asks for the cores' distance instead
        double reach = radius + COLLISION_TOLERANCE;
        return gjk.closestPoints(pa.core, pb.core, COLLISION_TOLERANCE, reach * reach, v, onA, onB) <= reach * reach;
    }

    private void startAxis(CgPlaced pa, CgPlaced pb) {
        v.set(pb.px - pa.px, pb.py - pa.py, pb.pz - pa.pz);
        if (v.lengthSquared() == 0) v.set(1, 0, 0);
    }

    // ---- distance

    double distance(CgShape sa, CgPose pa, CgShape sb, CgPose pb) {
        start(DISTANCE, pa, null);
        best = Double.POSITIVE_INFINITY;
        walk(sa, pa, sb, pb, false);
        return best;
    }

    private void distanceLeaf(CgShape sa, CgPose pa, CgShape sb, CgPose pb) {
        requireConvex("distance", sa, sb);
        a.set(sa, pa, baseX, baseY, baseZ);
        b.set(sb, pb, baseX, baseY, baseZ);
        double d = closed.distance(a, b);
        if (Double.isNaN(d)) {
            startAxis(a, b);
            double sq = gjk.closestPoints(a.core, b.core, COLLISION_TOLERANCE, Double.MAX_VALUE, v, onA, onB);
            d = Math.max(0, Math.sqrt(sq) - a.radius() - b.radius());
        }
        best = Math.min(best, d);
    }

    // ---- contact

    boolean contact(CgShape sa, CgPose pa, CgShape sb, CgPose pb, CgContact out) {
        start(CONTACT, pa, null);
        best = -1;
        walk(sa, pa, sb, pb, false);
        if (best < 0) return false;
        out.pointA.set(bestA).add(baseX, baseY, baseZ);
        out.pointB.set(bestB).add(baseX, baseY, baseZ);
        out.normal.set(bestNormal);
        out.depth = best;
        return true;
    }

    private void contactLeaf(CgShape sa, CgPose pa, CgShape sb, CgPose pb, boolean swapped) {
        requireConvex("contact", sa, sb);
        a.set(sa, pa, baseX, baseY, baseZ);
        b.set(sb, pb, baseX, baseY, baseZ);
        startAxis(a, b);
        if (!epa.penetration(a.core, a.radius(), a, b.core, b.radius(), b, COLLISION_TOLERANCE,
                PENETRATION_TOLERANCE, v, onA, onB)) {
            return;
        }
        double depthHere = v.length();
        if (depthHere <= best) return;
        best = depthHere;
        if (swapped) {
            bestA.set(onB);
            bestB.set(onA);
            bestNormal.set(v).div(-depthHere);
        } else {
            bestA.set(onA);
            bestB.set(onB);
            bestNormal.set(v).div(depthHere);
        }
    }

    // ---- rays

    boolean castRay(CgShape s, CgPose p, double rox, double roy, double roz, double rdx, double rdy, double rdz,
                    double maxDistance, CgCastHit out) {
        double len = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
        if (!(len > 0)) {
            throw new IllegalArgumentException("a ray needs a direction: " + rdx + ", " + rdy + ", " + rdz);
        }
        start(RAY, p, null);
        ox = rox - baseX;
        oy = roy - baseY;
        oz = roz - baseZ;
        dx = rdx / len;
        dy = rdy / len;
        dz = rdz / len;
        max = maxDistance;
        best = Double.POSITIVE_INFINITY;
        walk(s, p, null, null, false);
        if (best == Double.POSITIVE_INFINITY) return false;
        out.distance = best;
        out.fraction = maxDistance > 0 ? best / maxDistance : 0;
        out.point.set(rox + dx * best, roy + dy * best, roz + dz * best);
        out.normal.set(bestNormal);
        return true;
    }

    private void rayLeaf(CgShape s, CgPose p) {
        double t;
        if (s.isConvex()) {
            a.set(s, p, baseX, baseY, baseZ);
            t = rays.ray(a, ox, oy, oz, dx, dy, dz, max, normal);
            if (Double.isNaN(t)) t = gjkRay(a);
        } else if (s instanceof CgShell) {
            CgShell shell = (CgShell) s;
            t = rays.shell(p.x - baseX, p.y - baseY, p.z - baseZ, shell.inner(), shell.outer(), ox, oy, oz, dx, dy, dz,
                    max, normal);
        } else if (s instanceof CgRing) {
            CgRing ring = (CgRing) s;
            t = rays.ring(a.frame(p, baseX, baseY, baseZ), ring.inner(), ring.outer(), ring.halfHeight(), ox, oy, oz,
                    dx, dy, dz, max, normal);
        } else {
            throw new IllegalArgumentException("no ray test for the non-convex " + s);
        }
        if (t < best) {
            best = t;
            bestNormal.set(normal);
        }
    }

    /** A point moving along the ray, cast at the shape: GJK's ray cast. */
    private double gjkRay(CgPlaced s) {
        b.point(ox, oy, oz);
        double lambda = gjk.castShape(b, dx * max, dy * max, dz * max, CAST_TOLERANCE, s.core, 0, s.radius(), 1, onA,
                onB, v);
        if (lambda == Double.POSITIVE_INFINITY) return lambda;
        castNormal(b, s, lambda, dx * max, dy * max, dz * max);
        return lambda * max;
    }

    /**
     * The surface normal facing the mover at a cast's hit; against the motion when they overlapped at the start. With
     * no radius GJK's axis is the one before it closed in, so the normal is measured again from just short of the hit.
     */
    private void castNormal(CgPlaced mover, CgPlaced still, double lambda, double mx, double my, double mz) {
        double len = v.length();
        if (lambda == 0 || !(len > 0)) {
            normal.set(-mx, -my, -mz).normalize();
            return;
        }
        normal.set(v).div(-len);
        if (mover.radius() + still.radius() > 0) return;
        double t = Math.max(0, lambda - NORMAL_BACKOFF / Math.sqrt(mx * mx + my * my + mz * mz));
        mover.translate(mx * t, my * t, mz * t);
        double sq = gjk.closestPoints(mover, still, COLLISION_TOLERANCE, Double.MAX_VALUE,
                v.set(-normal.x, -normal.y, -normal.z), refineA, refineB);
        mover.translate(-mx * t, -my * t, -mz * t);
        if (sq > 0 && sq < Double.MAX_VALUE) normal.set(v).div(-Math.sqrt(sq));
    }

    // ---- casts

    boolean cast(CgShape sa, CgPose pa, double mx, double my, double mz, CgShape sb, CgPose pb, CgCastHit out) {
        return cast(sa, pa, mx, my, mz, sb, pb, null, out);
    }

    boolean cast(CgShape s, CgPose p, double mx, double my, double mz, CgAABBc box, CgCastHit out) {
        return !box.isEmpty() && cast(s, p, mx, my, mz, null, null, box, out);
    }

    private boolean cast(CgShape sa, CgPose pa, double mx, double my, double mz, @Nullable CgShape sb,
                         @Nullable CgPose pb, @Nullable CgAABBc box, CgCastHit out) {
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        if (len == 0) {
            start(OVERLAP, pa, box);
            walk(sa, pa, sb, pb, false);
            if (!found) return false;
            out.fraction = 0;
            out.distance = 0;
            out.point.set(pa.x, pa.y, pa.z);
            out.normal.zero();
            return true;
        }
        start(CAST, pa, box);
        dx = mx;
        dy = my;
        dz = mz;
        best = Double.POSITIVE_INFINITY;
        walk(sa, pa, sb, pb, false);
        if (best == Double.POSITIVE_INFINITY) return false;
        out.fraction = best;
        out.distance = best * len;
        out.point.set(bestB).add(baseX, baseY, baseZ);
        out.normal.set(bestNormal);
        return true;
    }

    private void castLeaf(CgShape sa, CgPose pa, @Nullable CgShape sb, @Nullable CgPose pb, boolean swapped) {
        requireConvex("cast", sa, sb);
        // the mover is always the caller's a, whichever side its compound was taken apart on; never swapped on a box
        CgPlaced mover = swapped ? b.set(sb, pb, baseX, baseY, baseZ) : a.set(sa, pa, baseX, baseY, baseZ);
        CgPlaced still = swapped ? a.set(sa, pa, baseX, baseY, baseZ) : placeB(sb, pb);
        double lambda = gjk.castShape(mover.core, dx, dy, dz, CAST_TOLERANCE, still.core, mover.radius(),
                still.radius(), 1, onA, onB, v);
        if (lambda >= best) return;
        best = lambda;
        castNormal(mover, still, lambda, dx, dy, dz);
        bestB.set(onB);
        bestNormal.set(normal);
    }

    private static void requireConvex(String query, CgShape sa, @Nullable CgShape sb) {
        if (!sa.isConvex() || sb != null && !sb.isConvex()) {
            throw new IllegalArgumentException(query + " takes convex shapes and compounds of them: "
                    + (sa.isConvex() ? sb : sa));
        }
    }
}
