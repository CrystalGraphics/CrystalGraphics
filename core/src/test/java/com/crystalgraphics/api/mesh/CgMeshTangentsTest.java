package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/** The tangents a mesh written with none is given: MikkTSpace's, its vertices split where a mirrored UV seam needs. */
public class CgMeshTangentsTest {

    private static final CgVertexFormat F = CgVertexFormat.SPATIAL;
    private static final CgVertexAttribute P = F.getAttribute(F.indexOf("cg_Position")),
            N = F.getAttribute(F.indexOf("cg_Normal")), UV = F.getAttribute(F.indexOf("cg_TexCoord0")),
            T = F.getAttribute(F.indexOf("cg_Tangent"));

    private static ByteBuffer vertices(CgMesh mesh) {
        ByteBuffer b = ByteBuffer.allocate(mesh.vertexCount() * F.getStride()).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, mesh.vertexCount(), b);
        return b;
    }

    private static float[] read(CgVertexAttribute a, ByteBuffer b, int v, int n) {
        float[] out = new float[n];
        for (int c = 0; c < n; c++) out[c] = a.read(b, v * F.getStride(), c);
        return out;
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] unit(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return new float[] {a[0] / l, a[1] / l, a[2] / l};
    }

    @Test
    public void eachFaceOfACubeHasItsTangentAlongUAndItsBitangentAlongV() {
        CgMesh cube = CgMesh.build(F, CgMeshShapes::cube);
        ByteBuffer b = vertices(cube);
        int[] index = new int[cube.indexCount()];
        cube.readIndices(0, index.length, index, 0);
        for (int i = 0; i < index.length; i += 3) {
            int a = index[i], c1 = index[i + 1], c2 = index[i + 2];
            float[] p0 = read(P, b, a, 3), e1 = sub(read(P, b, c1, 3), p0), e2 = sub(read(P, b, c2, 3), p0);
            float[] t0 = read(UV, b, a, 2), t1 = read(UV, b, c1, 2), t2 = read(UV, b, c2, 2);
            float du1 = t1[0] - t0[0], dv1 = t1[1] - t0[1], du2 = t2[0] - t0[0], dv2 = t2[1] - t0[1];
            float r = 1f / (du1 * dv2 - du2 * dv1);
            float[] dpdu = unit(new float[] {(e1[0] * dv2 - e2[0] * dv1) * r, (e1[1] * dv2 - e2[1] * dv1) * r, (e1[2] * dv2 - e2[2] * dv1) * r});
            float[] dpdv = unit(new float[] {(e2[0] * du1 - e1[0] * du2) * r, (e2[1] * du1 - e1[1] * du2) * r, (e2[2] * du1 - e1[2] * du2) * r});
            for (int v : new int[] {a, c1, c2}) {
                float[] t = read(T, b, v, 4), n = unit(read(N, b, v, 3));
                assertEquals("unit tangent at " + v, 1f, (float) Math.sqrt(dot(t, t)), 1e-3f);
                assertEquals("perpendicular at " + v, 0f, dot(t, n), 1e-3f);
                assertTrue("along +u at " + v, dot(t, dpdu) > 0.999f);
                float[] bitangent = cross(n, t);
                for (int c = 0; c < 3; c++) bitangent[c] *= t[3];
                assertTrue("bitangent along +v at " + v, dot(bitangent, dpdv) > 0.999f);
            }
        }
    }

    @Test
    public void aMirroredSeamSplitsTheVerticesItShares() {
        // Two quads side by side on z = 0, the right one's u mirrored: the seam's two vertices are shared, and their
        // corners disagree on the tangent's sign
        CgMesh mesh = CgMesh.build(F, m -> {
            int a = m.vertex().position(-1, 0, 0).normal(0, 0, 1).uv(0, 0).end();
            int s0 = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(1, 0).end();
            int s1 = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(1, 1).end();
            int d = m.vertex().position(-1, 1, 0).normal(0, 0, 1).uv(0, 1).end();
            int e = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(0, 0).end();
            int f = m.vertex().position(1, 1, 0).normal(0, 0, 1).uv(0, 1).end();
            m.quad(a, s0, s1, d);
            m.quad(s0, e, f, s1);
        });
        assertEquals("the seam's two vertices copied", 8, mesh.vertexCount());
        assertEquals(8, mesh.submesh(0).vertexCount());
        ByteBuffer b = vertices(mesh);
        int[] index = new int[mesh.indexCount()];
        mesh.readIndices(0, index.length, index, 0);
        for (int i = 0; i < 6; i++) {
            float[] t = read(T, b, index[i], 4);
            assertTrue("left: tangent +x", t[0] > 0.999f && t[3] > 0f);
        }
        for (int i = 6; i < 12; i++) {
            float[] t = read(T, b, index[i], 4);
            assertTrue("right, mirrored: tangent -x", t[0] < -0.999f);
            float[] bitangent = cross(new float[] {0, 0, 1}, t);
            assertTrue("right: bitangent still +y", bitangent[1] * t[3] > 0.999f);
        }
    }

    @Test
    public void givenTangentsAreKeptAndMixingThrows() {
        CgMesh given = CgMesh.build(F, m -> {
            int a = m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0).tangent(0, 0, 1, -1).end();
            int b = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).tangent(0, 0, 1, -1).end();
            int c = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).tangent(0, 0, 1, -1).end();
            m.triangle(a, b, c);
        });
        float[] t = read(T, vertices(given), 1, 4);
        assertArrayEquals(new float[] {0, 0, 1, -1}, t, 1e-4f);

        try {
            CgMesh.build(F, m -> {
                int a = m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0).tangent(1, 0, 0, 1).end();
                int b = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).end();
                int c = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).end();
                m.triangle(a, b, c);
            });
            fail("some vertices given a tangent and some not");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2 of 3 vertices"));
        }
    }
}
