package com.crystalgraphics.api.mesh;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgProceduralMeshTest {

    /** SPATIAL: position at 0, UV at 12, normal at 20, 32 bytes a vertex. */
    private static ByteBuffer vertices(CgMesh mesh) {
        ByteBuffer bytes = ByteBuffer.allocate(mesh.vertexCount() * 32).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, mesh.vertexCount(), bytes);
        return bytes;
    }

    @Test
    public void revolvedSphereFacesOutward() {
        CgMesh mesh = CgProceduralMesh.revolve(CgProceduralMesh.sphere(2f, -2f, 2f)).divisions(24, 12).build();
        assertEquals(24 * 12, mesh.vertexCount());
        assertEquals(23 * 11 * 2 * 3, mesh.indexCount());
        ByteBuffer v = vertices(mesh);
        int[] faces = new int[mesh.indexCount()];
        mesh.readIndices(0, faces.length, faces, 0);
        for (int f = 0; f < faces.length; f += 3) {
            float[] a = position(v, faces[f]), b = position(v, faces[f + 1]), c = position(v, faces[f + 2]);
            float[] n = CgProceduralMesh.cross(sub(b, a), sub(c, a));
            if (CgProceduralMesh.length(n) < 1e-5f) continue;
            float[] centre = {(a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3};
            assertTrue("face " + f / 3 + " winds inward", dot(n, centre) > 0f);
        }
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float[] p = position(v, i);
            if (Math.abs(p[1]) > 1.99f) continue;
            float[] n = {v.getFloat(i * 32 + 20), v.getFloat(i * 32 + 24), v.getFloat(i * 32 + 28)};
            assertTrue("vertex " + i + "'s normal is not radial", dot(n, CgProceduralMesh.normalize(p)) > 0.95f);
        }
    }

    @Test
    public void closedRevolveSharesItsSeam() {
        CgMesh mesh = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 1f)).divisions(16, 2).build();
        ByteBuffer v = vertices(mesh);
        for (int row = 0; row < 2; row++) {
            float[] first = position(v, row * 16), last = position(v, row * 16 + 15);
            assertEquals(first[0], last[0], 1e-6f);
            assertEquals(first[2], last[2], 1e-6f);
            assertEquals(v.getFloat(row * 16 * 32 + 20), v.getFloat((row * 16 + 15) * 32 + 20), 1e-5f);
        }
    }

    @Test
    public void ribbonsMakeTheirSectionsAlongTheProfile() {
        CgMesh plane = CgProceduralMesh.ribbons(CgProceduralMesh.cone(1f, 3f)).count(3).vertices(20).build();
        int perRibbon = plane.vertexCount() / 3;
        assertEquals(0, plane.vertexCount() % 3);
        assertEquals(0, perRibbon % 3);
        int points = perRibbon / 3;
        assertEquals(3 * (points - 1) * 2 * 2 * 3, plane.indexCount());

        CgMesh cross = CgProceduralMesh.ribbons(CgProceduralMesh.cone(1f, 3f)).section(CgProceduralMesh.Section.CROSS)
                .vertices(20).build();
        assertEquals(points * 6, cross.vertexCount());
    }

    @Test
    public void splineWalksThroughItsEnds() {
        CgProceduralMesh.Profile spline = CgProceduralMesh.spline(0f, 0f, 1f, 1f, 1f, 2f, 0f, 3f);
        float[] out = new float[2];
        spline.at(0f, out);
        assertEquals(0f, out[0], 1e-5f);
        assertEquals(0f, out[1], 1e-5f);
        spline.at(1f, out);
        assertEquals(0f, out[0], 1e-5f);
        assertEquals(3f, out[1], 1e-5f);
    }

    private static float[] position(ByteBuffer v, int i) {
        return new float[]{v.getFloat(i * 32), v.getFloat(i * 32 + 4), v.getFloat(i * 32 + 8)};
    }

    private static float[] sub(float[] p, float[] q) {
        return new float[]{p[0] - q[0], p[1] - q[1], p[2] - q[2]};
    }

    private static float dot(float[] p, float[] q) {
        return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
    }
}
