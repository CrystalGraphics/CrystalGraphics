package com.crystalgraphics.shape.query.convex;

import org.joml.Vector3d;

/**
 * The convex hull EPA grows toward the Minkowski difference's surface, trading accuracy for speed and giving up once
 * round-off forms defects. A port of Jolt Physics' {@code Jolt/Geometry/EPAConvexHullBuilder.h} (MIT, Jorrit Rouwe),
 * in doubles, its triangles pooled so a query allocates nothing.
 */
final class CgEpaHull {

    /** Faces of a closed triangle mesh are about twice its vertices (Euler), so 256 triangles hold 128 points. */
    static final int MAX_TRIANGLES = 256;
    static final int MAX_POINTS = MAX_TRIANGLES / 2;
    static final int MAX_EDGES = 128;
    /** Jolt's 1e-10 in floats. */
    private static final double MIN_TRIANGLE_AREA = 1e-24;
    /**
     * In blocks: how far in front of a triangle a point must be to see it. Jolt tests against zero, which splits a flat
     * face whose triangles round either way for a point in its plane, and the hull loses its convexity there.
     */
    private static final double FACING_EPSILON = 1e-10;
    /** How far outside a triangle its closest point may fall and still count as interior. */
    private static final double BARYCENTRIC_EPSILON = 1e-3;

    static final class Triangle {
        final Triangle[] neighbour = new Triangle[3];
        /** Which edge of the neighbour this edge is joined to. */
        final int[] neighbourEdge = new int[3];
        /** Each edge's start vertex, an index into the hull's points. */
        final int[] start = new int[3];
        /** Its length is twice the triangle's area. */
        final Vector3d normal = new Vector3d();
        double normalLength;
        final Vector3d centroid = new Vector3d();
        /** Signed: negative while the origin is in front of the triangle. */
        double closestLenSq;
        /**
         * The closest point's barycentric coordinates: y0 + l0 (y1 - y0) + l1 (y2 - y0), or from y1 if not relative
         * to 0.
         */
        double lambda0, lambda1;
        boolean lambdaRelativeTo0;
        boolean closestPointInterior;
        boolean removed;
        /** Stays true once popped: the EPA loop frees it. */
        boolean inQueue;
        Triangle nextFree;

        void init(int i0, int i1, int i2, Vector3d[] positions) {
            start[0] = i0;
            start[1] = i1;
            start[2] = i2;
            neighbour[0] = neighbour[1] = neighbour[2] = null;
            closestLenSq = Double.MAX_VALUE;
            closestPointInterior = false;
            removed = false;
            inQueue = false;

            Vector3d y0 = positions[i0], y1 = positions[i1], y2 = positions[i2];
            centroid.set(y0).add(y1).add(y2).div(3);
            double y10x = y1.x - y0.x, y10y = y1.y - y0.y, y10z = y1.z - y0.z;
            double y20x = y2.x - y0.x, y20y = y2.y - y0.y, y20z = y2.z - y0.z;
            double y21x = y2.x - y1.x, y21y = y2.y - y1.y, y21z = y2.z - y1.z;
            double y20Sq = y20x * y20x + y20y * y20y + y20z * y20z;
            double y21Sq = y21x * y21x + y21y * y21y + y21z * y21z;
            double y10Sq = y10x * y10x + y10y * y10y + y10z * y10z;
            // the most accurate normal comes from the two shortest edges
            if (y20Sq < y21Sq) {
                normal.set(y10y * y20z - y10z * y20y, y10z * y20x - y10x * y20z, y10x * y20y - y10y * y20x);
                double nSq = normal.lengthSquared();
                if (nSq > MIN_TRIANGLE_AREA) {
                    // the distance along the normal, its sign kept to tell which side the origin is on
                    double cDotN = centroid.dot(normal);
                    closestLenSq = Math.abs(cDotN) * cDotN / nSq;
                    double y10DotY20 = y10x * y20x + y10y * y20y + y10z * y20z;
                    double det = y10Sq * y20Sq - y10DotY20 * y10DotY20;
                    if (det > 0) {
                        double y0DotY10 = y0.x * y10x + y0.y * y10y + y0.z * y10z;
                        double y0DotY20 = y0.x * y20x + y0.y * y20y + y0.z * y20z;
                        double l0 = (y10DotY20 * y0DotY20 - y20Sq * y0DotY10) / det;
                        double l1 = (y10DotY20 * y0DotY10 - y10Sq * y0DotY20) / det;
                        lambda0 = l0;
                        lambda1 = l1;
                        lambdaRelativeTo0 = true;
                        // of coplanar triangles only the one holding the origin's projection gives good contact points
                        closestPointInterior = l0 > -BARYCENTRIC_EPSILON && l1 > -BARYCENTRIC_EPSILON
                                && l0 + l1 < 1 + BARYCENTRIC_EPSILON;
                    }
                }
            } else {
                normal.set(y10y * y21z - y10z * y21y, y10z * y21x - y10x * y21z, y10x * y21y - y10y * y21x);
                double nSq = normal.lengthSquared();
                if (nSq > MIN_TRIANGLE_AREA) {
                    double cDotN = centroid.dot(normal);
                    closestLenSq = Math.abs(cDotN) * cDotN / nSq;
                    double y10DotY21 = y10x * y21x + y10y * y21y + y10z * y21z;
                    double det = y10Sq * y21Sq - y10DotY21 * y10DotY21;
                    if (det > 0) {
                        double y1DotY10 = y1.x * y10x + y1.y * y10y + y1.z * y10z;
                        double y1DotY21 = y1.x * y21x + y1.y * y21y + y1.z * y21z;
                        double l0 = (y21Sq * y1DotY10 - y10DotY21 * y1DotY21) / det;
                        double l1 = (y10DotY21 * y1DotY10 - y10Sq * y1DotY21) / det;
                        lambda0 = l0;
                        lambda1 = l1;
                        lambdaRelativeTo0 = false;
                        closestPointInterior = l0 > -BARYCENTRIC_EPSILON && l1 > -BARYCENTRIC_EPSILON
                                && l0 + l1 < 1 + BARYCENTRIC_EPSILON;
                    }
                }
            }
            normalLength = normal.length();
        }

        /** Whether {@code point} is more than {@link #FACING_EPSILON} in front of it. */
        boolean isFacing(Vector3d point) {
            return normal.x * (point.x - centroid.x) + normal.y * (point.y - centroid.y)
                    + normal.z * (point.z - centroid.z) > FACING_EPSILON * normalLength;
        }

        /** False for a sliver too thin to have a normal: it cannot say which side the origin is on. */
        boolean isFacingOrigin() {
            return closestLenSq != Double.MAX_VALUE && normal.dot(centroid) < -FACING_EPSILON * normalLength;
        }
    }

    private final Triangle[] pool = new Triangle[MAX_TRIANGLES];
    private int highWater;
    private Triangle free;

    /** A binary min-heap on {@code closestLenSq}; removed triangles stay in it until popped. */
    private final Triangle[] heap = new Triangle[MAX_TRIANGLES];
    private int heapSize;

    private Vector3d[] positions;

    /** The rim {@link #findEdge} finds: per edge its outside neighbour, that neighbour's edge, and its start. */
    private final Triangle[] edgeNeighbour = new Triangle[MAX_EDGES];
    private final int[] edgeNeighbourEdge = new int[MAX_EDGES], edgeStart = new int[MAX_EDGES];
    private int edgeCount;

    private final Triangle[] stackTriangle = new Triangle[MAX_EDGES];
    private final int[] stackEdge = new int[MAX_EDGES], stackIter = new int[MAX_EDGES];

    /** What the last {@link #addPoint} made. */
    final Triangle[] added = new Triangle[MAX_EDGES];
    int addedCount;

    CgEpaHull() {
        for (int i = 0; i < MAX_TRIANGLES; i++) pool[i] = new Triangle();
    }

    /** Starts over with two back-to-back triangles of three points of {@code positions}. */
    void initialize(Vector3d[] positions, int i1, int i2, int i3) {
        this.positions = positions;
        highWater = 0;
        free = null;
        heapSize = 0;
        Triangle t1 = create(i1, i2, i3), t2 = create(i1, i3, i2);
        link(t1, 0, t2, 2);
        link(t1, 1, t2, 1);
        link(t1, 2, t2, 0);
        push(t1);
        push(t2);
    }

    boolean hasNextTriangle() {
        return heapSize > 0;
    }

    Triangle peekClosest() {
        return heap[0];
    }

    Triangle popClosest() {
        Triangle top = heap[0];
        Triangle last = heap[--heapSize];
        heap[heapSize] = null;
        if (heapSize > 0) {
            int i = 0;
            for (;;) {
                int l = 2 * i + 1, r = l + 1, m = i;
                double best = last.closestLenSq;
                if (l < heapSize && heap[l].closestLenSq < best) {
                    m = l;
                    best = heap[l].closestLenSq;
                }
                if (r < heapSize && heap[r].closestLenSq < best) m = r;
                if (m == i) break;
                heap[i] = heap[m];
                i = m;
            }
            heap[i] = last;
        }
        return top;
    }

    private void push(Triangle t) {
        t.inQueue = true;
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (heap[parent].closestLenSq <= t.closestLenSq) break;
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = t;
    }

    /**
     * The live triangle {@code point} is farthest in front of, or null. Works while every point was added with
     * {@code addPoint(..., MAX_VALUE)}.
     */
    Triangle findFacingTriangle(Vector3d point) {
        Triangle best = null;
        double bestDistSq = 0;
        for (int i = 0; i < heapSize; i++) {
            Triangle t = heap[i];
            if (t.removed) continue;
            double dot = t.normal.x * (point.x - t.centroid.x) + t.normal.y * (point.y - t.centroid.y)
                    + t.normal.z * (point.z - t.centroid.z);
            if (dot > FACING_EPSILON * t.normalLength) {
                double distSq = dot * dot / t.normal.lengthSquared();
                if (distSq > bestDistSq) {
                    best = t;
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    /**
     * Adds point {@code index}, removing every triangle it is in front of, starting from {@code facing}, and filling
     * the hole: the new triangles are {@link #added}. False when round-off makes the hole ragged or a pool runs out;
     * the old triangles are unlinked by then, so the hull cannot be used further.
     */
    boolean addPoint(Triangle facing, int index, double closestDistSq) {
        addedCount = 0;
        if (!findEdge(facing, positions[index])) return false;
        int n = edgeCount;
        for (int i = 0; i < n; i++) {
            Triangle nt = create(edgeStart[i], edgeStart[(i + 1) % n], index);
            if (nt == null) return false;
            added[addedCount++] = nt;
            // the main loop wants interior points nearer than the best so far; growing to the origin, any behind it
            if ((nt.closestPointInterior && nt.closestLenSq < closestDistSq) || nt.closestLenSq < 0) push(nt);
        }
        for (int i = 0; i < n; i++) {
            link(added[i], 0, edgeNeighbour[i], edgeNeighbourEdge[i]);
            link(added[i], 1, added[(i + 1) % n], 2);
        }
        return true;
    }

    void freeTriangle(Triangle t) {
        t.nextFree = free;
        free = t;
    }

    private Triangle create(int i0, int i1, int i2) {
        Triangle t;
        if (free != null) {
            t = free;
            free = t.nextFree;
        } else {
            if (highWater >= MAX_TRIANGLES) return null;
            t = pool[highWater++];
        }
        t.init(i0, i1, i2, positions);
        return t;
    }

    private static void link(Triangle t1, int edge1, Triangle t2, int edge2) {
        t1.neighbour[edge1] = t2;
        t1.neighbourEdge[edge1] = edge2;
        t2.neighbour[edge2] = t1;
        t2.neighbourEdge[edge2] = edge1;
    }

    private void unlink(Triangle t) {
        for (int i = 0; i < 3; i++) {
            Triangle n = t.neighbour[i];
            if (n != null) {
                n.neighbour[t.neighbourEdge[i]] = null;
                t.neighbour[i] = null;
            }
        }
        // in the heap it is freed when popped
        if (!t.inQueue) freeTriangle(t);
    }

    /** The rim of the triangles {@code vertex} is in front of, flagging them removed; false on islands or overflow. */
    private boolean findEdge(Triangle facing, Vector3d vertex) {
        edgeCount = 0;
        facing.removed = true;
        int top = 0;
        stackTriangle[0] = facing;
        stackEdge[0] = 0;
        stackIter[0] = -1;
        // the start index the next rim edge should have; any other means islands
        int nextExpected = -1;
        for (;;) {
            if (++stackIter[top] >= 3) {
                unlink(stackTriangle[top]);
                if (--top < 0) break;
            } else {
                Triangle cur = stackTriangle[top];
                int e = (stackEdge[top] + stackIter[top]) % 3;
                Triangle n = cur.neighbour[e];
                if (n != null && !n.removed) {
                    if (n.isFacing(vertex)) {
                        n.removed = true;
                        if (++top >= MAX_EDGES) return false;
                        stackTriangle[top] = n;
                        stackEdge[top] = cur.neighbourEdge[e];
                        // its first edge is the one we came from
                        stackIter[top] = 0;
                    } else {
                        // the point is so near some coplanar triangles that it is in front of some and behind others:
                        // the rim breaks into islands, and adding the point is past the precision there is
                        if (cur.start[e] != nextExpected && nextExpected != -1) return false;
                        nextExpected = n.start[cur.neighbourEdge[e]];
                        if (edgeCount >= MAX_EDGES) return false;
                        edgeNeighbour[edgeCount] = n;
                        edgeNeighbourEdge[edgeCount] = cur.neighbourEdge[e];
                        edgeStart[edgeCount++] = cur.start[e];
                    }
                }
            }
        }
        // two triangles back to back and a point on their plane can remove both, leaving no rim: touching, no depth
        return edgeCount >= 3;
    }
}
