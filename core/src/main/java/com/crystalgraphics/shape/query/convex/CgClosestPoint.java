package com.crystalgraphics.shape.query.convex;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The point of a segment, triangle or tetrahedron nearest the origin, and which of its vertices make that feature:
 * GJK's sub-simplex solver. A port of Jolt Physics' {@code Jolt/Geometry/ClosestPoint.h} (MIT, Jorrit Rouwe), after
 * Christer Ericson's <i>Real-Time Collision Detection</i>, in doubles.
 *
 * <p>A feature is a bit set over the inputs: 1 the first, 2 the second, 4 the third, 8 the fourth.</p>
 */
final class CgClosestPoint {

    /** Jolt's {@code FLT_EPSILON}, at double precision. */
    static final double EPSILON = Math.ulp(1.0);
    static final double EPSILON_SQ = EPSILON * EPSILON;
    /** Below this a triangle's normal is degenerate; Jolt's 1e-10 in floats. */
    private static final double MIN_NORMAL_SQ = 1e-24;
    /** {@code 1 - cos^2} of a triangle's angle below which it is a line; Jolt's 1e-6 in floats. */
    private static final double DEGENERATE_ANGLE = 1e-12;

    private CgClosestPoint() {
    }

    /** {@code uv} such that {@code a * u + b * v} is nearest the origin on the line; false when a and b coincide. */
    static boolean barycentric(Vector3dc a, Vector3dc b, double[] uv) {
        double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        double den = abx * abx + aby * aby + abz * abz;
        if (den < EPSILON_SQ) {
            boolean aCloser = a.lengthSquared() < b.lengthSquared();
            uv[0] = aCloser ? 1 : 0;
            uv[1] = aCloser ? 0 : 1;
            return false;
        }
        uv[1] = -(a.x() * abx + a.y() * aby + a.z() * abz) / den;
        uv[0] = 1 - uv[1];
        return true;
    }

    /** {@code uvw} such that {@code a * u + b * v + c * w} is nearest the origin on the plane; false if degenerate. */
    static boolean barycentric(Vector3dc a, Vector3dc b, Vector3dc c, double[] uvw) {
        double v0x = b.x() - a.x(), v0y = b.y() - a.y(), v0z = b.z() - a.z();
        double v1x = c.x() - a.x(), v1y = c.y() - a.y(), v1z = c.z() - a.z();
        double v2x = c.x() - b.x(), v2y = c.y() - b.y(), v2z = c.z() - b.z();
        double d00 = v0x * v0x + v0y * v0y + v0z * v0z;
        double d11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double d22 = v2x * v2x + v2y * v2y + v2z * v2z;
        // always include the shortest edge, keeping the products small
        if (d00 <= d22) {
            double d01 = v0x * v1x + v0y * v1y + v0z * v1z;
            double den = d00 * d11 - d01 * d01;
            if (den <= DEGENERATE_ANGLE * d00 * d11) {
                if (d00 > d11) {
                    barycentric(a, b, uvw);
                    uvw[2] = 0;
                } else {
                    barycentric(a, c, uvw);
                    uvw[2] = uvw[1];
                    uvw[1] = 0;
                }
                return false;
            }
            double a0 = a.x() * v0x + a.y() * v0y + a.z() * v0z;
            double a1 = a.x() * v1x + a.y() * v1y + a.z() * v1z;
            uvw[1] = (d01 * a1 - d11 * a0) / den;
            uvw[2] = (d01 * a0 - d00 * a1) / den;
            uvw[0] = 1 - uvw[1] - uvw[2];
        } else {
            double d12 = v1x * v2x + v1y * v2y + v1z * v2z;
            double den = d11 * d22 - d12 * d12;
            if (den <= DEGENERATE_ANGLE * d11 * d22) {
                if (d11 > d22) {
                    barycentric(a, c, uvw);
                    uvw[2] = uvw[1];
                    uvw[1] = 0;
                } else {
                    barycentric(b, c, uvw);
                    uvw[2] = uvw[1];
                    uvw[1] = uvw[0];
                    uvw[0] = 0;
                }
                return false;
            }
            double c1 = c.x() * v1x + c.y() * v1y + c.z() * v1z;
            double c2 = c.x() * v2x + c.y() * v2y + c.z() * v2z;
            uvw[0] = (d22 * c1 - d12 * c2) / den;
            uvw[1] = (d11 * c2 - d12 * c1) / den;
            uvw[2] = 1 - uvw[0] - uvw[1];
        }
        return true;
    }

    /** The point of segment ab nearest the origin into {@code out}; answers its feature (1, 2 or 3). */
    static int closestOnLine(Vector3dc a, Vector3dc b, double[] uv, Vector3d out) {
        barycentric(a, b, uv);
        if (uv[1] <= 0) {
            out.set(a);
            return 0b0001;
        }
        if (uv[0] <= 0) {
            out.set(b);
            return 0b0010;
        }
        out.set(a.x() * uv[0] + b.x() * uv[1], a.y() * uv[0] + b.y() * uv[1], a.z() * uv[0] + b.z() * uv[1]);
        return 0b0011;
    }

    /**
     * The point of triangle abc nearest the origin into {@code out}; answers its feature. With {@code mustIncludeC}
     * the feature is assumed to include c, and less is tested.
     */
    static int closestOnTriangle(Vector3dc inA, Vector3dc inB, Vector3dc inC, boolean mustIncludeC, Vector3d out) {
        // the most accurate normal comes from the two shortest edges: make ab shorter than bc by swapping a and c
        double bax = inA.x() - inB.x(), bay = inA.y() - inB.y(), baz = inA.z() - inB.z();
        double bcx = inC.x() - inB.x(), bcy = inC.y() - inB.y(), bcz = inC.z() - inB.z();
        boolean swap = bcx * bcx + bcy * bcy + bcz * bcz < bax * bax + bay * bay + baz * baz;
        Vector3dc a = swap ? inC : inA, c = swap ? inA : inC;

        double abx = inB.x() - a.x(), aby = inB.y() - a.y(), abz = inB.z() - a.z();
        double acx = c.x() - a.x(), acy = c.y() - a.y(), acz = c.z() - a.z();
        double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
        double nLenSq = nx * nx + ny * ny + nz * nz;

        if (nLenSq < MIN_NORMAL_SQ) {
            // degenerate: vertices first, so a vertex wins over an edge at the same distance
            int set = 0b0100;
            out.set(inC);
            double best = inC.lengthSquared();
            if (!mustIncludeC) {
                double al = inA.lengthSquared();
                if (al < best) {
                    set = 0b0001;
                    out.set(inA);
                    best = al;
                }
                double bl = inB.lengthSquared();
                if (bl < best) {
                    set = 0b0010;
                    out.set(inB);
                    best = bl;
                }
            }
            double acl = acx * acx + acy * acy + acz * acz;
            if (acl > EPSILON_SQ) {
                double v = clamp01(-(a.x() * acx + a.y() * acy + a.z() * acz) / acl);
                double qx = a.x() + v * acx, qy = a.y() + v * acy, qz = a.z() + v * acz;
                double d = qx * qx + qy * qy + qz * qz;
                if (d < best) {
                    set = 0b0101;
                    out.set(qx, qy, qz);
                    best = d;
                }
            }
            double bcl = bcx * bcx + bcy * bcy + bcz * bcz;
            if (bcl > EPSILON_SQ) {
                double v = clamp01(-(inB.x() * bcx + inB.y() * bcy + inB.z() * bcz) / bcl);
                double qx = inB.x() + v * bcx, qy = inB.y() + v * bcy, qz = inB.z() + v * bcz;
                double d = qx * qx + qy * qy + qz * qz;
                if (d < best) {
                    set = 0b0110;
                    out.set(qx, qy, qz);
                    best = d;
                }
            }
            if (!mustIncludeC) {
                double ex = inB.x() - inA.x(), ey = inB.y() - inA.y(), ez = inB.z() - inA.z();
                double el = ex * ex + ey * ey + ez * ez;
                if (el > EPSILON_SQ) {
                    double v = clamp01(-(inA.x() * ex + inA.y() * ey + inA.z() * ez) / el);
                    double qx = inA.x() + v * ex, qy = inA.y() + v * ey, qz = inA.z() + v * ez;
                    if (qx * qx + qy * qy + qz * qz < best) {
                        set = 0b0011;
                        out.set(qx, qy, qz);
                    }
                }
            }
            return set;
        }

        // vertex region outside a
        double d1 = -(abx * a.x() + aby * a.y() + abz * a.z());
        double d2 = -(acx * a.x() + acy * a.y() + acz * a.z());
        if (d1 <= 0 && d2 <= 0) {
            out.set(a);
            return swap ? 0b0100 : 0b0001;
        }
        // vertex region outside b
        double d3 = -(abx * inB.x() + aby * inB.y() + abz * inB.z());
        double d4 = -(acx * inB.x() + acy * inB.y() + acz * inB.z());
        if (d3 >= 0 && d4 <= d3) {
            out.set(inB);
            return 0b0010;
        }
        // edge region ab
        if (d1 * d4 <= d3 * d2 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            out.set(a.x() + v * abx, a.y() + v * aby, a.z() + v * abz);
            return swap ? 0b0110 : 0b0011;
        }
        // vertex region outside c
        double d5 = -(abx * c.x() + aby * c.y() + abz * c.z());
        double d6 = -(acx * c.x() + acy * c.y() + acz * c.z());
        if (d6 >= 0 && d5 <= d6) {
            out.set(c);
            return swap ? 0b0001 : 0b0100;
        }
        // edge region ac
        if (d5 * d2 <= d1 * d6 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            out.set(a.x() + w * acx, a.y() + w * acy, a.z() + w * acz);
            return 0b0101;
        }
        // edge region bc
        double d43 = d4 - d3, d56 = d5 - d6;
        if (d3 * d6 <= d5 * d4 && d43 >= 0 && d56 >= 0) {
            double w = d43 / (d43 + d56);
            out.set(inB.x() + w * (c.x() - inB.x()), inB.y() + w * (c.y() - inB.y()), inB.z() + w * (c.z() - inB.z()));
            return swap ? 0b0011 : 0b0110;
        }
        // the face: the plane's distance along the normal, more accurate than barycentric coordinates
        double k = ((a.x() + inB.x() + c.x()) * nx + (a.y() + inB.y() + c.y()) * ny + (a.z() + inB.z() + c.z()) * nz)
                / (3 * nLenSq);
        out.set(nx * k, ny * k, nz * k);
        return 0b0111;
    }

    /**
     * For each face of tetrahedron abcd, whether the origin is outside it: bit 1 abc, 2 acd, 4 adb, 8 bdc. A degenerate
     * tetrahedron answers outside all four.
     */
    static int originOutsideOfTetrahedronPlanes(Vector3dc a, Vector3dc b, Vector3dc c, Vector3dc d) {
        double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        double acx = c.x() - a.x(), acy = c.y() - a.y(), acz = c.z() - a.z();
        double adx = d.x() - a.x(), ady = d.y() - a.y(), adz = d.z() - a.z();
        double bdx = d.x() - b.x(), bdy = d.y() - b.y(), bdz = d.z() - b.z();
        double bcx = c.x() - b.x(), bcy = c.y() - b.y(), bcz = c.z() - b.z();

        double n0x = aby * acz - abz * acy, n0y = abz * acx - abx * acz, n0z = abx * acy - aby * acx;   // ab x ac
        double n1x = acy * adz - acz * ady, n1y = acz * adx - acx * adz, n1z = acx * ady - acy * adx;   // ac x ad
        double n2x = ady * abz - adz * aby, n2y = adz * abx - adx * abz, n2z = adx * aby - ady * abx;   // ad x ab
        double n3x = bdy * bcz - bdz * bcy, n3y = bdz * bcx - bdx * bcz, n3z = bdx * bcy - bdy * bcx;   // bd x bc

        double p0 = a.x() * n0x + a.y() * n0y + a.z() * n0z;
        double p1 = a.x() * n1x + a.y() * n1y + a.z() * n1z;
        double p2 = a.x() * n2x + a.y() * n2y + a.z() * n2z;
        double p3 = b.x() * n3x + b.y() * n3y + b.z() * n3z;

        double s0 = adx * n0x + ady * n0y + adz * n0z;
        double s1 = abx * n1x + aby * n1y + abz * n1z;
        double s2 = acx * n2x + acy * n2y + acz * n2z;
        double s3 = -(abx * n3x + aby * n3y + abz * n3z);

        // every face is wound so the fourth point is on one side: mixed signs mean a degenerate tetrahedron
        if (s0 >= 0 && s1 >= 0 && s2 >= 0 && s3 >= 0) {
            return (p0 >= -EPSILON ? 1 : 0) | (p1 >= -EPSILON ? 2 : 0) | (p2 >= -EPSILON ? 4 : 0)
                    | (p3 >= -EPSILON ? 8 : 0);
        }
        if (s0 < 0 && s1 < 0 && s2 < 0 && s3 < 0) {
            return (p0 <= EPSILON ? 1 : 0) | (p1 <= EPSILON ? 2 : 0) | (p2 <= EPSILON ? 4 : 0)
                    | (p3 <= EPSILON ? 8 : 0);
        }
        return 0b1111;
    }

    /**
     * The point of tetrahedron abcd nearest the origin into {@code out}, {@code scratch} a temporary; answers its
     * feature, 15 for the origin inside. With {@code mustIncludeD} the feature is assumed to include d.
     */
    static int closestOnTetrahedron(Vector3dc a, Vector3dc b, Vector3dc c, Vector3dc d, boolean mustIncludeD,
                                    Vector3d out, Vector3d scratch) {
        int closest = 0b1111;
        out.set(0, 0, 0);
        double best = Double.MAX_VALUE;
        int outside = originOutsideOfTetrahedronPlanes(a, b, c, d);
        if ((outside & 1) != 0) {
            if (mustIncludeD) {
                // abc cannot be closest, nor the interior: a stands in
                closest = 0b0001;
                out.set(a);
            } else {
                closest = closestOnTriangle(a, b, c, false, out);
            }
            best = out.lengthSquared();
        }
        if ((outside & 2) != 0) {
            int set = closestOnTriangle(a, c, d, mustIncludeD, scratch);
            double dist = scratch.lengthSquared();
            if (dist < best) {
                best = dist;
                out.set(scratch);
                closest = (set & 0b0001) + ((set & 0b0110) << 1);
            }
        }
        if ((outside & 4) != 0) {
            // keep abd's order: GJK adds d last and keeps the previous closest feature in abc
            int set = closestOnTriangle(a, b, d, mustIncludeD, scratch);
            double dist = scratch.lengthSquared();
            if (dist < best) {
                best = dist;
                out.set(scratch);
                closest = (set & 0b0011) + ((set & 0b0100) << 1);
            }
        }
        if ((outside & 8) != 0) {
            int set = closestOnTriangle(b, c, d, mustIncludeD, scratch);
            if (scratch.lengthSquared() < best) {
                out.set(scratch);
                closest = set << 1;
            }
        }
        return closest;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
