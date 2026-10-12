package com.crystalgraphics.shape.bounds;

import com.crystalgraphics.shape.CgPose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgAABBTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    @Test
    public void emptyUnionsCleanly() {
        CgAABB box = new CgAABB();
        assertTrue(box.isEmpty());
        assertFalse(box.intersects(CgAABBc.UNIT));
        assertFalse(CgAABBc.UNIT.contains(box));
        assertTrue(box.grow(5).isEmpty());
        box.union(CgAABB.of(0, 0, 0, 1, 2, 3));
        assertEquals(0, box.minX, 0);
        assertEquals(3, box.maxZ, 0);
        box.union(new CgAABB());
        assertEquals(2, box.maxY, 0);
        assertEquals(0, new CgAABB().boundingRadius(), 0);
        assertTrue(new CgAABB().transform(new CgPose().at(4, 4, 4), new CgAABB()).isEmpty());
    }

    @Test
    public void containsIntersectsAndDistance() {
        CgAABB box = CgAABB.of(-1, -1, -1, 1, 1, 1);
        assertTrue(box.contains(1, 1, 1));
        assertFalse(box.contains(1.01, 0, 0));
        assertTrue(box.intersects(CgAABB.of(1, 1, 1, 2, 2, 2)));
        assertFalse(box.intersects(CgAABB.of(1.01, 0, 0, 2, 1, 1)));
        assertTrue(box.contains(CgAABB.of(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)));
        assertEquals(0, box.distanceSquared(0.5, 0.5, 0.5), 0);
        assertEquals(4 + 9, box.distanceSquared(3, -4, 0), 1e-12);
        Vector3d p = box.closestPoint(3, 0.25, -7, new Vector3d());
        assertEquals(1, p.x, 0);
        assertEquals(0.25, p.y, 0);
        assertEquals(-1, p.z, 0);
        assertEquals(Math.sqrt(3), box.boundingRadius(), 1e-12);
    }

    @Test
    public void rayHitIsTheSlabTest() {
        CgAABB box = CgAABB.of(2, -1, -1, 4, 1, 1);
        assertEquals(2, box.rayHit(0, 0, 0, 1, INF, INF, 10), 1e-12);            // along +x
        assertEquals(0, box.rayHit(3, 0, 0, 1, INF, INF, 10), 0);                 // starts inside
        assertEquals(INF, box.rayHit(0, 0, 0, -1, INF, INF, 10), 0);              // facing away
        assertEquals(INF, box.rayHit(0, 0, 0, 1, INF, INF, 1.5), 0);              // enters past max
        assertEquals(INF, box.rayHit(0, 2, 0, 1, INF, INF, 10), 0);               // parallel, outside the y slab
        assertEquals(2, box.rayHit(0, 1, 0, 1, INF, INF, 10), 1e-12);             // parallel, on the slab's plane
        double inv = 1 / Math.sqrt(0.5);
        assertEquals(Math.sqrt(8), box.rayHit(0, -2, 0, inv, inv, INF, 10), 1e-9); // diagonal, through the corner edge
    }

    @Test
    public void transformAgreesBetweenMatrixAndPose() {
        CgAABB local = CgAABB.of(-1, -2, -0.5, 3, 1, 2);
        Quaternionf q = new Quaternionf().rotateXYZ(0.3f, -1.1f, 0.7f);
        CgPose pose = new CgPose().at(10, -5, 2).rotation(q);
        Matrix4f m = new Matrix4f().translation(10, -5, 2).rotate(q);
        CgAABB a = local.transform(pose, new CgAABB()), b = local.transform(m, new CgAABB());
        assertEquals(a.minX, b.minX, 1e-5);
        assertEquals(a.minY, b.minY, 1e-5);
        assertEquals(a.minZ, b.minZ, 1e-5);
        assertEquals(a.maxX, b.maxX, 1e-5);
        assertEquals(a.maxY, b.maxY, 1e-5);
        assertEquals(a.maxZ, b.maxZ, 1e-5);
        Vector3d corner = pose.toWorld(3, 1, 2, new Vector3d());
        assertTrue(new CgAABB(a).grow(1e-9).contains(corner.x, corner.y, corner.z));
    }
}
